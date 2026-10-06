/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player.clip

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import app.gyrolet.mpvrx.data.network.proxy.NetworkStreamingProxy
import app.gyrolet.mpvrx.domain.network.NetworkPlaybackUri
import app.gyrolet.mpvrx.ui.player.PlaybackItem
import app.gyrolet.mpvrx.ui.player.PlaybackSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Crop coordinates in the video orientation visible to the user. */
data class ClipCrop(
  val x: Int,
  val y: Int,
  val width: Int,
  val height: Int,
  val rotation: Int,
)

data class ClipRequest(
  val item: PlaybackItem,
  val startSeconds: Double,
  val endSeconds: Double,
  val crop: ClipCrop? = null,
  val audioOnly: Boolean = false,
  val audioFormat: AudioExportFormat = AudioExportFormat.M4A,
  val options: DownloadExportOptions = DownloadExportOptions(),
)

sealed interface ClipExportState {
  data object Idle : ClipExportState

  data class Exporting(
    val progress: Float,
    val cancelling: Boolean = false,
  ) : ClipExportState

  data class Success(
    val uri: Uri,
    val displayName: String,
  ) : ClipExportState

  data class Error(
    val message: String,
  ) : ClipExportState
}

/**
 * Process-scoped Clip export worker.
 *
 * Playback remains owned by libmpv. Automatic video and audio exporters use Media3/FFmpeg
 * independently, with shared progress, cancellation and permanent Downloads history.
 */
object ClipExportManager {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val running = java.util.concurrent.ConcurrentHashMap<String, Job>()
  private val streamSequence = AtomicLong(0L)
  private val _state = MutableStateFlow<ClipExportState>(ClipExportState.Idle)

  @Volatile
  private var activeJob: Job? = null

  val state: StateFlow<ClipExportState> = _state.asStateFlow()

  fun export(
    context: Context,
    request: ClipRequest,
  ): Boolean {
    if (request.endSeconds <= request.startSeconds + MIN_CLIP_SECONDS) return false


    // Capture the displayed/oriented frame size while this request still refers to the actively
    // playing item. CropSelectionView reports coordinates in this same orientation.
    val cropFrameSize =
      request.crop?.let { crop ->
        val sourceWidth = PlaybackSession.getPropertyInt("video-params/w") ?: 0
        val sourceHeight = PlaybackSession.getPropertyInt("video-params/h") ?: 0
        val rotation = ((crop.rotation % 360) + 360) % 360
        if (rotation == 90 || rotation == 270) {
          sourceHeight to sourceWidth
        } else {
          sourceWidth to sourceHeight
        }
      }

    val appContext = context.applicationContext
    val clipJobId = ClipJobs.add(appContext, request)
    ClipExportService.start(appContext)
    lateinit var job: Job
    job = scope.launch(start = CoroutineStart.LAZY) {
      var resolvedSource: ResolvedSource? = null
      var temporaryOutput: File? = null
      try {
        resolvedSource = resolveSource(appContext, request.item, request.options.formatSelector != null)
        val extension = if (request.audioOnly) request.audioFormat.extension else if (request.options.fullMedia && request.crop == null) request.options.sourceExtension else "mp4"
        temporaryOutput = File.createTempFile("mpvrx-export-", ".$extension", appContext.cacheDir).apply { delete() }
        val artwork = ExportFiles.artwork(appContext, request.options, request.item.artworkUri)
        artwork?.let { ClipJobs.update(clipJobId, posterUrl = it) }

        val error = if (request.audioOnly) AudioClipExporter.export(
          format = request.audioFormat, fullMedia = request.options.fullMedia,
          context = appContext, source = resolvedSource.uri, original = request.item.originalUri,
          output = temporaryOutput.absolutePath, start = request.startSeconds, end = request.endSeconds,
          headers = request.item.headers,
          onThumbnail = { if (artwork == null) ClipJobs.update(clipJobId, posterUrl = it) },
          onProgress = { progress ->
            _state.value = ClipExportState.Exporting(progress.toFloat())
            ClipJobs.update(clipJobId, progress = progress.toFloat())
          }, onStage = { stage -> ClipJobs.update(clipJobId, status = stage) },
        ) else if (request.options.fullMedia && request.crop == null) {
          val input = resolvedSource.uri
          if (!input.startsWith("http") && !input.startsWith("edl://")) {
            val stream = if (input.startsWith("content://")) appContext.contentResolver.openInputStream(Uri.parse(input))
              else File(Uri.parse(input).path ?: input).inputStream()
            stream?.use { source -> temporaryOutput.outputStream().use { target ->
              val buffer = ByteArray(64 * 1024); var bytes = 0L
              val length = if (input.startsWith("content://")) -1L else File(Uri.parse(input).path ?: input).length()
              while (true) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val count = source.read(buffer); if (count < 0) break
                target.write(buffer, 0, count); bytes += count
                if (length > 0) ClipJobs.update(clipJobId, progress = (bytes.toFloat() / length).coerceAtMost(.99f))
              }
            } } ?: error("Could not read the source file")
            null
          } else {
            ClipJobs.update(clipJobId, status = "Downloading")
            val result = FfmpegRuntime.run(appContext, buildList {
              addAll(listOf("-hide_banner", "-nostdin", "-y", "-progress", "pipe:1", "-nostats"))
              if (request.item.headers.isNotEmpty()) addAll(listOf("-headers", request.item.headers.entries.joinToString("") { "${it.key}: ${it.value}\r\n" }))
              addAll(listOf("-i", input, "-map", "0:v:0?", "-map", "0:a?", "-c", "copy", temporaryOutput.path))
            }) { line ->
              if (line.startsWith("out_time_us=")) line.substringAfter('=').toDoubleOrNull()?.let {
                ClipJobs.update(clipJobId, progress = (it / (request.endSeconds * 1_000_000)).toFloat().coerceIn(0f, .99f))
              }
            }
            if (result.first == 0) null else "Could not save the current source"
          }
        } else AutomaticClipExporter.export(
          context = appContext, source = resolvedSource.uri, original = request.item.originalUri,
          output = temporaryOutput.absolutePath, start = request.startSeconds, end = request.endSeconds,
          formatSelector = request.options.formatSelector,
          crop = request.crop, frameWidth = cropFrameSize?.first ?: 0, frameHeight = cropFrameSize?.second ?: 0,
          headers = request.item.headers,
          onThumbnail = { if (artwork == null) ClipJobs.update(clipJobId, posterUrl = it) },
          onProgress = { progress ->
            _state.value = ClipExportState.Exporting(progress.toFloat())
            ClipJobs.update(clipJobId, progress = progress.toFloat())
          }, onStage = { stage -> ClipJobs.update(clipJobId, status = stage) },
        )

        if (error != null) {
          ClipJobs.update(clipJobId, status = "Failed", error = error)
          _state.value = ClipExportState.Error(error)
          return@launch
        }

        if (!temporaryOutput.exists() || temporaryOutput.length() <= 0L) {
          error("Export finished without producing a media file")
        }

        val finalArtwork = artwork ?: ExportFiles.artwork(appContext, request.options, ClipJobs.jobs.value.firstOrNull { it.id == clipJobId }?.posterUrl)
        finalArtwork?.let { ClipJobs.update(clipJobId, posterUrl = it) }
        ExportFiles.decorate(appContext, temporaryOutput, request.options.author, finalArtwork)
        val displayName = request.options.fileName.takeIf(String::isNotBlank)?.let { ExportFiles.name(it, extension) }
          ?: buildDisplayName(request.item, request.audioOnly, request.audioFormat)
        ClipJobs.update(clipJobId, status = "Saving")
        val savedUri = request.options.directory?.let { path ->
          val target = ExportFiles.reserve(File(path), displayName)
          try {
            temporaryOutput.copyTo(target, overwrite = true)
            temporaryOutput.delete()
            app.gyrolet.mpvrx.domain.download.AppDownloadManager.notifyCompletedMedia(appContext, target)
            Uri.fromFile(target)
          } catch (failure: Throwable) { target.delete(); throw failure }
        } ?: saveToMediaLibrary(appContext, temporaryOutput, displayName, request.audioOnly, request.audioFormat)
        temporaryOutput = null
        ClipJobs.update(clipJobId, status = "Completed", progress = 1f, output = savedUri.toString())
        _state.value = ClipExportState.Success(savedUri, displayName)
      } catch (error: CancellationException) {
        ClipJobs.update(clipJobId, status = "Cancelled")
        throw error
      } catch (error: Throwable) {
        ClipJobs.update(clipJobId, status = "Failed", error = error.message ?: "Could not save clip")
        _state.value =
          ClipExportState.Error(
            error.message?.takeIf { it.isNotBlank() } ?: "Unable to save this clip",
          )
      } finally {
        resolvedSource?.close?.invoke()
        temporaryOutput?.delete()
      }
    }
    activeJob = job
    running[clipJobId] = job
    job.invokeOnCompletion { error ->
      running.remove(clipJobId)
      if (activeJob === job) {
        activeJob = null

        if (error is CancellationException && _state.value is ClipExportState.Exporting) {
          _state.value = ClipExportState.Idle
        }
      }
    }
    _state.value = ClipExportState.Exporting(0f)
    job.start()
    return true
  }

  fun cancel(id: String? = null) {
    if (id != null) { running[id]?.cancel(); return }
    val current = _state.value as? ClipExportState.Exporting ?: return
    _state.value = current.copy(cancelling = true)
    activeJob?.cancel()
  }

  fun cancelAll() { running.values.forEach { it.cancel() } }

  fun consumeTerminalState() {
    if (_state.value is ClipExportState.Success || _state.value is ClipExportState.Error) {
      _state.value = ClipExportState.Idle
    }
  }

  private fun createTemporaryOutput(context: Context, audioOnly: Boolean, format: AudioExportFormat): File {
    val directory = File(context.cacheDir, "clips").apply { mkdirs() }
    return File.createTempFile("mpvrx-clip-", if (audioOnly) ".${format.extension}" else ".mp4", directory).apply { delete() }
  }

  private fun resolveSource(
    context: Context,
    item: PlaybackItem,
    selectedQuality: Boolean = false,
  ): ResolvedSource {
    ExportSource.portal(item)?.let { return ResolvedSource(it.uri, it.close) }
    app.gyrolet.mpvrx.domain.torrent.OfflineTorrents.initialize(context)
    app.gyrolet.mpvrx.domain.torrent.OfflineTorrents.find(item.originalUri, item.torrentFileIndex)?.takeIf { it.complete }?.let { return ResolvedSource(it.path) }
    val direct = org.koin.core.context.GlobalContext.get().get<app.gyrolet.mpvrx.domain.download.AppDownloadManager>().downloads.value.firstOrNull { it.entity.sourceUrl == item.originalUri && it.isPlayable }
    if (direct != null && !selectedQuality) return ResolvedSource(direct.file.absolutePath)
    val completed = org.koin.core.context.GlobalContext.get().get<app.gyrolet.mpvrx.domain.download.YtdlpDownloadEngine>().jobs.value.firstOrNull { it.url == item.originalUri && it.state == app.gyrolet.mpvrx.domain.download.YtdlpDownloadEngine.JobState.SUCCESS && it.outputFile?.let(::File)?.isFile == true }
    if (completed != null && !selectedQuality) return ResolvedSource(completed.outputFile!!)
    val contentUri =
      when {
        item.originalUri.startsWith("content://", ignoreCase = true) -> item.originalUri
        item.playableUri.startsWith("content://", ignoreCase = true) -> item.playableUri
        else -> null
      }

    // Media3 understands content:// directly. Do not detach the Android descriptor into fd://;
    // fd:// was only required by the removed native libmpv exporter.
    if (contentUri != null) {
      return ResolvedSource(uri = contentUri)
    }

    val networkReference =
      NetworkPlaybackUri.parse(item.playableUri)
        ?: item.networkSource?.let { source ->
          NetworkPlaybackUri.parse(NetworkPlaybackUri.create(source.connectionId, source.relativePath))
        }
    if (networkReference != null) {
      val proxy = NetworkStreamingProxy.getInstance()
      val streamId = "clip-${streamSequence.incrementAndGet()}"
      val uri =
        proxy.registerStream(
          streamId = streamId,
          connectionId = networkReference.connectionId,
          filePath = networkReference.path.value,
          mimeType = item.mimeType ?: "application/octet-stream",
        )
      return ResolvedSource(
        uri = uri,
        close = { runCatching { proxy.unregisterStream(streamId) } },
      )
    }

    val source = item.playableUri.ifBlank { item.originalUri }
    if (source.startsWith("fd://", ignoreCase = true)) {
      error("This video source can no longer be reopened for clipping")
    }
    return ResolvedSource(uri = source)
  }

  private fun buildDisplayName(item: PlaybackItem, audioOnly: Boolean, format: AudioExportFormat): String {
    val base =
      item.title
        ?.substringBeforeLast('.')
        ?.sanitizeFileName()
        ?.takeIf { it.isNotBlank() }
        ?: "MPVRX"
    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    return if (audioOnly) "${base}_audio_$stamp.${format.extension}" else "${base}_clip_$stamp.mp4"
  }

  private fun saveToMediaLibrary(
    context: Context,
    source: File,
    displayName: String,
    audioOnly: Boolean,
    format: AudioExportFormat,
  ): Uri {
    val mimeType = if (audioOnly) format.mimeType else "video/mp4"
    val mediaDirectory = if (audioOnly) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES
    val collection = if (audioOnly) MediaStore.Audio.Media.EXTERNAL_CONTENT_URI else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      val resolver = context.contentResolver
      val values =
        ContentValues().apply {
          put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
          put(MediaStore.Video.Media.MIME_TYPE, mimeType)
          put(MediaStore.Video.Media.RELATIVE_PATH, if (audioOnly) "${mediaDirectory}/mpvRx" else "${mediaDirectory}/mpvRx/Clips")
          put(MediaStore.Video.Media.IS_PENDING, 1)
        }
      val uri =
        resolver.insert(collection, values)
          ?: error("Unable to create a MediaStore entry for the clip")
      try {
        resolver.openOutputStream(uri, "w")?.use { output ->
          source.inputStream().use { input -> input.copyTo(output) }
        } ?: error("Unable to open the saved clip")
        resolver.update(
          uri,
          ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) },
          null,
          null,
        )
        source.delete()
        return uri
      } catch (error: Throwable) {
        resolver.delete(uri, null, null)
        throw error
      }
    }

    // Android 8/9 still use the legacy public Movies directory. If the platform denies public
    // storage access, fall back to the app's external Movies directory rather than losing output.
    val publicResult =
      runCatching {
        val directory = File(Environment.getExternalStoragePublicDirectory(mediaDirectory), if (audioOnly) "mpvRx" else "mpvRx/Clips")
        check(directory.exists() || directory.mkdirs()) { "Unable to create the media output folder" }
        val target = uniqueFile(directory, displayName)
        source.copyTo(target)
        source.delete()
        MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf(mimeType), null)
        Uri.fromFile(target)
      }
    publicResult.getOrNull()?.let { return it }

    val fallbackDirectory =
      File(context.getExternalFilesDir(mediaDirectory) ?: context.filesDir, if (audioOnly) "mpvRx" else "Clips")
        .apply { mkdirs() }
    val fallback = uniqueFile(fallbackDirectory, displayName)
    source.copyTo(fallback)
    source.delete()
    return Uri.fromFile(fallback)
  }

  private fun uniqueFile(
    directory: File,
    requestedName: String,
  ): File {
    val first = File(directory, requestedName)
    if (!first.exists()) return first
    val stem = requestedName.substringBeforeLast('.')
    val extension = requestedName.substringAfterLast('.', "mp4")
    var index = 2
    while (true) {
      val candidate = File(directory, "${stem}_$index.$extension")
      if (!candidate.exists()) return candidate
      index++
    }
  }

  private data class ResolvedSource(
    val uri: String,
    val close: () -> Unit = {},
  )

  private fun String.sanitizeFileName(): String =
    replace(Regex("[\\/:*?\"<>|\\p{Cntrl}]"), "_")
      .trim()
      .take(80)

  private const val MIN_CLIP_SECONDS = 0.05
}
