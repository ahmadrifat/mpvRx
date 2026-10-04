/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.domain.download

import android.content.Context
import android.net.Uri
import android.util.Log
import app.gyrolet.mpvrx.network.AndroidCookieJar
import app.gyrolet.mpvrx.preferences.YtdlPreferences
import app.gyrolet.mpvrx.ui.player.ytdlp.YtdlpManager
import app.gyrolet.mpvrx.utils.media.HttpUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicInteger

/**
 * Downloads HLS / extractor-backed links (YouTube, m3u8, ...) with the bundled yt-dlp
 * runtime, which handles playlist resolution, segment downloading and AES-128 decryption.
 * Jobs run concurrently inside [YtdlpDownloadService] so they survive backgrounding.
 */
class YtdlpDownloadEngine(
  private val context: Context,
  private val preferences: YtdlPreferences,
) {
  enum class JobState { QUEUED, RUNNING, SUCCESS, FAILED, CANCELLED, PAUSED }

  @Serializable
  data class Job(
    val id: Int,
    val url: String,
    val title: String,
    val directory: String,
    val formatSelector: String? = null,
    val mergeSeparateStreams: Boolean = false,
    val posterUrl: String? = null,
    val state: JobState = JobState.QUEUED,
    val progressPercent: Float = 0f,
    val detail: String = "",
    val error: String? = null,
    val outputFile: String? = null,
    val artifactFiles: Set<String> = emptySet(),
    val headers: Map<String, String> = emptyMap(),
    val fileBaseName: String? = null,
  ) {
    val isActive: Boolean get() = state == JobState.QUEUED || state == JobState.RUNNING
  }

  private val nextId = AtomicInteger(1)
  private val _jobs = MutableStateFlow<List<Job>>(emptyList())
  val jobs: StateFlow<List<Job>> = _jobs.asStateFlow()
  private val historyLock = Any()
  private val historyFile = File(context.filesDir, "ytdlp-downloads.json")
  private val historyJson = Json { ignoreUnknownKeys = true }
  init {
    _jobs.value = runCatching {
      historyJson.decodeFromString<List<Job>>(historyFile.readText()).map { job ->
        if (job.isActive) job.copy(state = JobState.FAILED, error = "Download interrupted. Tap Retry to continue.") else job
      }
    }.getOrDefault(emptyList())
    nextId.set((_jobs.value.maxOfOrNull { it.id } ?: 0) + 1)
    CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
      jobs.collect { saved ->
        runCatching { synchronized(historyLock) { saveHistory() } }.onFailure { Log.w(TAG, "Unable to persist download history") }
      }
    }
  }

  private class Transfer {
    @Volatile var process: Process? = null
    @Volatile var cancelRequested = false
    @Volatile var pauseRequested = false
    var runner: kotlinx.coroutines.Job? = null
  }
  private val transfers = java.util.concurrent.ConcurrentHashMap<Int, Transfer>()

  fun enqueue(
    url: String,
    title: String,
    directory: File,
    formatSelector: String? = null,
    mergeSeparateStreams: Boolean = false,
    posterUrl: String? = null,
    headers: Map<String, String> = emptyMap(),
  ): Int {
    val id = nextId.getAndIncrement()
    if (!directory.exists()) directory.mkdirs()
    cleanupWorkingDirectory(id)
    _jobs.update { current ->
      current +
        Job(
          id = id,
          url = url,
          title = title,
          directory = directory.absolutePath,
          formatSelector = formatSelector?.trim()?.takeIf(String::isNotBlank),
          mergeSeparateStreams = mergeSeparateStreams,
          posterUrl = posterUrl,
          headers = headers,
          fileBaseName = DownloadLocations.sanitizeName(title) + "-" + id,
        )
    }
    YtdlpDownloadService.start(context)
    return id
  }

  suspend fun rename(job: Job, name: String) {
    require(job.state == JobState.SUCCESS) { "Wait for the download to finish" }
    val old = job.outputFile ?: error("File not found")
    DownloadFileRename.rename(context, old, name) { path ->
      val updated = job.copy(title = name.substringBeforeLast('.'), outputFile = path, artifactFiles = job.artifactFiles.map { if (it == old) path else it }.toSet())
      synchronized(historyLock) {
        _jobs.update { current -> current.map { if (it.id == job.id) updated else it } }
        try { saveHistory() } catch (error: Throwable) {
          _jobs.update { current -> current.map { if (it.id == job.id) job else it } }
          throw error
        }
      }
    }
  }

  private fun saveHistory() {
    val temporary = File(context.filesDir, "ytdlp-downloads.json.tmp")
    temporary.writeText(historyJson.encodeToString(_jobs.value))
    java.nio.file.Files.move(temporary.toPath(), historyFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
  }

  fun pause(id: Int) {
    transfers[id]?.let { it.pauseRequested = true; it.cancelRequested = true; it.process?.destroyForcibly(); it.runner?.cancel() }
      ?: updateJob(id) { if (it.state == JobState.QUEUED) it.copy(state = JobState.PAUSED) else it }
  }
  fun resume(id: Int) {
    if (transfers.containsKey(id)) return
    updateJob(id) { if (it.state == JobState.PAUSED) it.copy(state = JobState.QUEUED, error = null) else it }
    YtdlpDownloadService.start(context)
  }
  fun cancel(id: Int) {
    transfers[id]?.let { it.pauseRequested = false; it.cancelRequested = true; it.process?.destroyForcibly(); it.runner?.cancel() }
      ?: updateJob(id) { if (it.state in setOf(JobState.QUEUED, JobState.PAUSED)) it.copy(state = JobState.CANCELLED) else it }
  }

  fun retry(id: Int) {
    _jobs.update { current ->
      current.map { job ->
        if (job.id == id && (job.state == JobState.FAILED || job.state == JobState.CANCELLED)) {
          job.copy(state = JobState.QUEUED, progressPercent = 0f, error = null, detail = "", outputFile = null)
        } else {
          job
        }
      }
    }
    YtdlpDownloadService.start(context)
  }

  fun remove(
    id: Int,
    deleteFiles: Boolean = false,
  ) {
    val job = _jobs.value.firstOrNull { it.id == id } ?: return
    if (job.isActive) cancel(id)
    if (deleteFiles || job.state != JobState.SUCCESS) deleteArtifacts(job)
    if (!job.isActive) cleanupWorkingDirectory(id)
    _jobs.update { current -> current.filterNot { it.id == id } }
  }

  fun hasQueuedWork(): Boolean = _jobs.value.any { it.state == JobState.QUEUED }

  /** Start every pending request concurrently, including requests arriving during transfers. */
  suspend fun drainQueue(onJobUpdate: (Job) -> Unit) = kotlinx.coroutines.supervisorScope {
    while (true) {
      jobs.value.filter { it.state == JobState.QUEUED }.forEach { job ->
        val control = Transfer()
        if (transfers.putIfAbsent(job.id, control) == null) {
          updateJob(job.id) { it.copy(state = JobState.RUNNING) }
          control.runner = launch {
            try { runJob(job.id, control, onJobUpdate) }
            catch (e: CancellationException) {
              updateJob(job.id) { it.copy(state = if (control.pauseRequested) JobState.PAUSED else if (control.cancelRequested) JobState.CANCELLED else JobState.FAILED, detail = "", error = if (control.cancelRequested) null else "Download interrupted. Tap Retry to continue.") }
            } catch (e: Exception) {
              updateJob(job.id) { it.copy(state = JobState.FAILED, detail = "", error = e.message ?: "Download failed") }
            } finally { control.process?.destroyForcibly(); transfers.remove(job.id, control) }
          }
        }
      }
      if (transfers.isEmpty() && !hasQueuedWork()) break
      kotlinx.coroutines.delay(100)
    }
  }

  private suspend fun runJob(
    id: Int,
    control: Transfer,
    onJobUpdate: (Job) -> Unit,
  ) {
    val queuedJob = currentJob(id) ?: return

    try {
      if (queuedJob.posterUrl.isNullOrBlank() && HttpUtils.isYouTubeUrl(Uri.parse(queuedJob.url))) {
        HttpUtils.fetchYouTubeMetadata(queuedJob.url)?.let { metadata ->
          updateJob(id) {
            it.copy(title = metadata.title.takeIf(String::isNotBlank) ?: it.title, posterUrl = metadata.thumbnailUrl)
          }
          currentJob(id)?.let(onJobUpdate)
        }
      }
    } catch (error: CancellationException) {

      throw error
    }
    val job = currentJob(id)
    if (control.cancelRequested || job == null) {

      updateJob(id) { it.copy(state = if (control.pauseRequested) JobState.PAUSED else JobState.CANCELLED, detail = "") }
      currentJob(id)?.let(onJobUpdate)
      return
    }

    val runtimeOutput = StringBuilder()
    val ready =
      try {
        YtdlpManager.ensureRuntimeInstalled(context) { message ->
          runtimeOutput.append(message)
          if (runtimeOutput.length > 8_192) runtimeOutput.delete(0, runtimeOutput.length - 8_192)
        }
      } catch (error: Exception) {
        if (error is CancellationException) {

          throw error
        }
        runtimeOutput.append(error.message ?: error.javaClass.simpleName)
        false
      }
    if (control.cancelRequested || currentJob(id) == null) {

      updateJob(id) { it.copy(state = if (control.pauseRequested) JobState.PAUSED else JobState.CANCELLED, detail = "") }
      currentJob(id)?.let(onJobUpdate)
      return
    }
    if (!ready) {

      updateJob(id) {
        it.copy(
          state = JobState.FAILED,
          error = runtimeOutput.toString().trim().ifBlank { "yt-dlp runtime is not installed" },
        )
      }
      currentJob(id)?.let(onJobUpdate)
      return
    }

    val temporaryDirectory = workingDirectory(id).apply { mkdirs() }
    val outputBaseName = (job.fileBaseName ?: DownloadLocations.sanitizeName(job.title))
    val outputTemplate =
      if (job.mergeSeparateStreams) {
        "$outputBaseName.f%(format_id)s.%(ext)s"
      } else {
        "$outputBaseName.%(ext)s"
      }
    val command =
      buildCommand(
        url = job.url,
        outputTemplate = outputTemplate,
        outputDirectory = if (job.mergeSeparateStreams) temporaryDirectory.absolutePath else job.directory,
        temporaryDirectory = temporaryDirectory.absolutePath,
        formatSelector = job.formatSelector,
        headers = job.headers,
      )
    val observedArtifacts = linkedSetOf<String>()
    val errorOutput = ArrayDeque<String>()
    var destination: String? = null
    var printedOutput: String? = null

    val result =
      withContext(Dispatchers.IO) {
        runCatching {
          if (control.cancelRequested) return@runCatching -1
          val process = startProcess(command)
          control.process = process
          if (control.cancelRequested) process.destroyForcibly()
          BufferedReader(InputStreamReader(process.inputStream)).useLines { lines ->
            lines.forEach { line ->
              parseDestination(line)?.let { path ->
                destination = path
                resolveJobOutput(job, path)?.let { file -> observedArtifacts += file.absolutePath }
              }
              parseFinalOutput(line)?.let { path ->
                printedOutput = path
                resolveJobOutput(job, path)?.let { file -> observedArtifacts += file.absolutePath }
              }
              val progress = parseProgressLine(line)
              if (progress != null) {
                updateJob(id) { it.copy(progressPercent = progress.first, detail = if (progress.first >= 100f) "Finalizing download" else progress.second) }
                currentJob(id)?.let(onJobUpdate)
              } else if (line.isNotBlank()) {
                errorOutput.addLast(line.take(2_048))
                if (errorOutput.size > 8) errorOutput.removeFirst()
              }
            }
          }
          val exitCode = runInterruptible { process.waitFor() }
          exitCode
        }
      }

    control.process = null


    result
      .onSuccess { exitCode ->
        when {
          control.cancelRequested ->
            updateJob(id) {
              it.copy(
                state = if (control.pauseRequested) JobState.PAUSED else JobState.CANCELLED,
                detail = "",
                artifactFiles = it.artifactFiles + observedArtifacts,
              )
            }
          exitCode == 0 -> {
            val sourceArtifacts =
              (observedArtifacts.asSequence() + discoverArtifactFiles(job).map(File::getAbsolutePath)).toSet()
            val resolvedResult =
              runCatching {
                if (job.mergeSeparateStreams) {
                  updateJob(id) { it.copy(progressPercent = 99f, detail = "Finalizing audio and video") }
                  YtdlpMediaMerger.merge(
                    context = context,
                    candidates = discoverSeparateStreamFiles(job).toList(),
                    outputFile = File(job.directory, "$outputBaseName.mp4"),
                  )
                } else {
                  printedOutput
                    ?.let { resolveJobOutput(job, it) }
                    ?.takeIf(File::isFile)
                    ?: destination
                      ?.let { resolveJobOutput(job, it) }
                      ?.takeIf { isFinalOutputCandidate(job, it) }
                    ?: findNewestOutput(job)?.let(::File)
                    ?: throw IllegalStateException("yt-dlp finished without a playable output file")
                }
              }
            resolvedResult.onSuccess { resolved ->
              val artifacts = sourceArtifacts + resolved.absolutePath
              cleanupIntermediateArtifacts(job, artifacts, resolved)
              cleanupWorkingDirectory(id)
              updateJob(id) {
                it.copy(
                  state = JobState.SUCCESS,
                  progressPercent = 100f,
                  detail = "",
                  outputFile = resolved.absolutePath,
                  artifactFiles = it.artifactFiles + artifacts,
                )
              }
              AppDownloadManager.notifyCompletedMedia(context, resolved)
            }.onFailure { error ->
              updateJob(id) {
                it.copy(
                  state = JobState.FAILED,
                  progressPercent = 0f,
                  detail = "",
                  error = error.message ?: "Could not finalize downloaded media",
                  artifactFiles = it.artifactFiles + sourceArtifacts,
                )
              }
            }
          }
          else ->
            updateJob(id) {
              it.copy(
                state = JobState.FAILED,
                error = errorOutput.lastOrNull { line -> line.startsWith("ERROR:", ignoreCase = true) }
                  ?: errorOutput.joinToString("\n").ifBlank { "yt-dlp exited with code $exitCode" },
                artifactFiles = it.artifactFiles + observedArtifacts,
              )
            }
        }
      }.onFailure { error ->
        if (error is CancellationException) throw error
        Log.e(TAG, "yt-dlp download failed", error)
        updateJob(id) {
          it.copy(
            state = if (control.pauseRequested) JobState.PAUSED else if (control.cancelRequested) JobState.CANCELLED else JobState.FAILED,
            error = if (control.cancelRequested) null else error.message ?: "Unknown error",
            artifactFiles = it.artifactFiles + observedArtifacts,
          )
        }
      }
    currentJob(id)?.let(onJobUpdate)
  }

  internal fun buildCommand(
    url: String,
    outputTemplate: String,
    outputDirectory: String,
    temporaryDirectory: String,
    formatSelector: String?,
    headers: Map<String, String> = emptyMap(),
  ): List<String> =
    buildList {
      add(YtdlpManager.getExecutablePath(context))
      add(File(YtdlpManager.getYtdlDir(context), "yt-dlp").absolutePath)
      add("--ffmpeg-location")
      add(app.gyrolet.mpvrx.ui.player.clip.FfmpegRuntime.executable(context))
      headers.forEach { (key, value) -> add("--add-headers"); add("$key:$value") }
      add("--ignore-config")
      add("--no-playlist")
      add("--newline")
      add("--no-warnings")
      add("--no-colors")
      add("--progress")
      add("--retries")
      add("5")
      add("--fragment-retries")
      add("5")
      add("--concurrent-fragments")
      add("4")
      add("--no-keep-video")
      add("--paths")
      add("home:$outputDirectory")
      add("--paths")
      add("temp:$temporaryDirectory")
      add("--print")
      add("after_move:$FINAL_OUTPUT_PREFIX%(filepath)s")
      add("--format")
      add(formatSelector ?: DEFAULT_SINGLE_FILE_FORMAT)
      add("-o")
      add(outputTemplate)

      preferences.customUserAgent.get().takeIf(String::isNotBlank)?.let { userAgent ->
        add("--user-agent")
        add(userAgent)
      }
      preferences.referer.get().takeIf(String::isNotBlank)?.let { referer ->
        add("--referer")
        add(referer)
      }
      preferences.proxy.get().takeIf(String::isNotBlank)?.let { proxy ->
        add("--proxy")
        add(proxy)
      }
      preferences.extractorArgs.get().takeIf(String::isNotBlank)?.let { extractorArgs ->
        add("--extractor-args")
        add(extractorArgs)
      }
      if (preferences.geoBypass.get()) add("--geo-bypass")

      val cookiesFile =
        preferences.cookiesFile.get().takeIf(String::isNotBlank)
          ?.let(::File)
          ?.takeIf(File::isFile)
          ?: AndroidCookieJar.playbackCookieFile(context).takeIf(File::isFile)
      cookiesFile?.let { file ->
        add("--cookies")
        add(file.absolutePath)
      }

      File(context.applicationInfo.nativeLibraryDir, "libqjs.so")
        .takeIf(File::isFile)
        ?.let { quickJs ->
          add("--js-runtimes")
          add("quickjs:${quickJs.absolutePath}")
          add("--remote-components")
          add("ejs:github")
        }
      add("--")
      add(url)
    }

  private fun startProcess(command: List<String>): Process {
    app.gyrolet.mpvrx.ui.player.clip.FfmpegRuntime.libraries(context)
    return YtdlpManager.startPythonProcess(command, context)
  }

  internal data class ClipStreams(val video: String, val audio: String?, val headers: Map<String, String>, val thumbnail: String? = null)
  @OptIn(kotlinx.coroutines.InternalCoroutinesApi::class)
  internal suspend fun resolveForClip(url: String, audioOnly: Boolean = false): ClipStreams = withContext(Dispatchers.IO) {
    require(YtdlpManager.ensureRuntimeInstalled(context)) { "Could not prepare yt-dlp" }
    val base = buildCommand(url, "source.%(ext)s", context.cacheDir.absolutePath, context.cacheDir.absolutePath, if (audioOnly) "bestaudio/best" else "bestvideo+bestaudio/best").toMutableList()
    val separator = base.indexOf("--")
    base.addAll(separator, listOf("--skip-download", "--dump-single-json", "--no-progress"))
    val process = startProcess(base)
    val cancellation = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]?.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { if (it != null) process.destroyForcibly() }
    try {
      var data: org.json.JSONObject? = null
      process.inputStream.bufferedReader().useLines { lines -> lines.forEach { line -> if (line.startsWith("{")) data = runCatching { org.json.JSONObject(line) }.getOrNull() } }
      require(process.waitFor() == 0 && data != null) { "Could not resolve the video for clipping" }
      val root = data!!
      val formats = root.optJSONArray("requested_formats")
      val all = formats?.let { (0 until it.length()).map(it::getJSONObject) }.orEmpty()
      val video = all.firstOrNull { it.optString("vcodec") != "none" } ?: root
      val audio = all.firstOrNull { it.optString("vcodec") == "none" && it.optString("acodec") != "none" }
      val h = video.optJSONObject("http_headers") ?: root.optJSONObject("http_headers") ?: org.json.JSONObject()
      ClipStreams(video.getString("url"), audio?.getString("url"), h.keys().asSequence().associateWith { h.getString(it) }, root.optString("thumbnail").takeIf { it.startsWith("http") })
    } finally { cancellation?.dispose(); if (process.isAlive) process.destroyForcibly() }
  }

  @OptIn(kotlinx.coroutines.InternalCoroutinesApi::class)
  internal suspend fun acquireForClip(url: String, progress: (Double) -> Unit): File = withContext(Dispatchers.IO) {
    require(YtdlpManager.ensureRuntimeInstalled(context)) { "Could not prepare yt-dlp" }
    val directory = File(context.cacheDir, "clip-acquire-${java.util.UUID.randomUUID()}").apply { mkdirs() }
    val command = buildCommand(url, "source.%(ext)s", directory.absolutePath, directory.absolutePath, "bestvideo+bestaudio/best")
    val process = startProcess(command)
    val cancellation = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]?.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { cause -> if (cause != null) process.destroyForcibly() }
    var success = false
    try {
      process.inputStream.bufferedReader().useLines { lines -> lines.forEach { line -> parseProgressLine(line)?.let { progress(it.first.toDouble()) } } }
      require(process.waitFor() == 0) { "Could not download the source for clipping" }
      val file = directory.listFiles().orEmpty().filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }.maxByOrNull(File::length) ?: error("No downloaded video")
      success = true
      file
    } finally {
      cancellation?.dispose()
      if (process.isAlive) process.destroyForcibly()
      if (!success) directory.deleteRecursively()
    }
  }

  private fun findNewestOutput(job: Job): String? {
    return File(job.directory)
      .listFiles()
      ?.filter { isFinalOutputCandidate(job, it) }
      ?.maxByOrNull { it.lastModified() }
      ?.absolutePath
  }

  private fun isFinalOutputCandidate(
    job: Job,
    file: File,
  ): Boolean {
    if (!file.isFile || file.extension.lowercase() !in PLAYABLE_EXTENSIONS) return false
    val prefix = "${(job.fileBaseName ?: DownloadLocations.sanitizeName(job.title))}."
    if (!file.name.startsWith(prefix)) return false
    return !file.name.removePrefix(prefix).contains('.')
  }

  private fun resolveJobOutput(
    job: Job,
    path: String,
  ): File? =
    runCatching {
      val rawPath = path.trim().removeSurrounding("\"")
      val candidate = File(rawPath).let { if (it.isAbsolute) it else File(job.directory, rawPath) }.canonicalFile
      val outputDirectory = File(job.directory).canonicalFile
      candidate.takeIf { it.parentFile == outputDirectory }
    }.getOrNull()

  private fun cleanupIntermediateArtifacts(
    job: Job,
    artifacts: Set<String>,
    finalOutput: File,
  ) {
    (artifacts.asSequence().mapNotNull { resolveJobOutput(job, it) } + discoverArtifactFiles(job))
      .asSequence()
      .distinctBy(File::getAbsolutePath)
      .filterNot { it == finalOutput }
      .forEach { file -> runCatching { file.delete() } }
  }

  private fun deleteArtifacts(job: Job) {
    (
      (job.artifactFiles + listOfNotNull(job.outputFile))
        .asSequence()
        .mapNotNull { resolveJobOutput(job, it) } +
        discoverArtifactFiles(job)
    )
      .distinctBy(File::getAbsolutePath)
      .forEach { file -> runCatching { file.delete() } }
  }

  private fun discoverArtifactFiles(job: Job): Sequence<File> {
    val fileNamePrefix = "${(job.fileBaseName ?: DownloadLocations.sanitizeName(job.title))}."
    return File(job.directory)
      .listFiles()
      ?.asSequence()
      ?.filter { file ->
        file.isFile &&
          file.name.startsWith(fileNamePrefix) &&
          (
            file.extension.lowercase() in JOB_ARTIFACT_EXTENSIONS ||
              file.name.endsWith(".part", ignoreCase = true) ||
              file.name.endsWith(".ytdl", ignoreCase = true)
          )
      }.orEmpty()
  }

  private fun discoverSeparateStreamFiles(job: Job): Sequence<File> {
    val fileNamePrefix = "${(job.fileBaseName ?: DownloadLocations.sanitizeName(job.title))}.f"
    return sequenceOf(File(job.directory), workingDirectory(job.id))
      .filter(File::isDirectory)
      .flatMap { directory -> directory.walkTopDown().maxDepth(2) }
      .filter { file ->
        file.isFile &&
          file.name.startsWith(fileNamePrefix) &&
          file.extension.lowercase() in PLAYABLE_EXTENSIONS
      }.distinctBy(File::getAbsolutePath)
  }

  private fun workingDirectory(id: Int): File = File(context.cacheDir, "$WORK_DIRECTORY/$id")

  private fun cleanupWorkingDirectory(id: Int) {
    runCatching { workingDirectory(id).deleteRecursively() }
  }

  private fun currentJob(id: Int): Job? = _jobs.value.firstOrNull { it.id == id }

  private fun updateJob(
    id: Int,
    transform: (Job) -> Job,
  ) {
    _jobs.update { current -> current.map { if (it.id == id) transform(it) else it } }
  }

  companion object {
    private const val TAG = "YtdlpDownloadEngine"
    private const val WORK_DIRECTORY = "ytdlp_downloads"
    private const val FINAL_OUTPUT_PREFIX = "MPVRX_FINAL_OUTPUT="
    private const val DEFAULT_SINGLE_FILE_FORMAT = "best/bestvideo/bestaudio"

    // Example: "[download]  42.3% of ~ 123.45MiB at 2.34MiB/s ETA 01:23"
    private val PROGRESS_REGEX = Regex("""\[download]\s+([0-9.]+)%(.*)""")
    private val DESTINATION_REGEX = Regex("""\[download] Destination: (.+)""")
    private val ALREADY_DOWNLOADED_REGEX = Regex("""\[download] (.+) has already been downloaded""")
    private val PLAYABLE_EXTENSIONS =
      setOf("mkv", "mp4", "m4v", "webm", "mov", "avi", "ts", "m2ts", "mp3", "m4a", "opus", "ogg", "flac", "wav")
    private val JOB_ARTIFACT_EXTENSIONS =
      PLAYABLE_EXTENSIONS + setOf("aac", "vtt", "srt", "ass", "ssa", "lrc", "json", "jpg", "jpeg", "png", "webp")

    fun parseProgressLine(line: String): Pair<Float, String>? {
      val match = PROGRESS_REGEX.find(line.trim()) ?: return null
      val percent = match.groupValues[1].toFloatOrNull() ?: return null
      return percent.coerceIn(0f, 100f) to match.groupValues[2].trim()
    }

    fun parseDestination(line: String): String? =
      DESTINATION_REGEX.find(line.trim())?.groupValues?.get(1)?.trim()
        ?: ALREADY_DOWNLOADED_REGEX.find(line.trim())?.groupValues?.get(1)?.trim()

    fun parseFinalOutput(line: String): String? =
      line.trim().takeIf { it.startsWith(FINAL_OUTPUT_PREFIX) }?.removePrefix(FINAL_OUTPUT_PREFIX)?.trim()
  }
}
