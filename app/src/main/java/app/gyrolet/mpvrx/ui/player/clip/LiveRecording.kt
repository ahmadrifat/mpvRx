/* SPDX-License-Identifier: AGPL-3.0-or-later */
package app.gyrolet.mpvrx.ui.player.clip

import android.content.Context
import app.gyrolet.mpvrx.ui.player.PlaybackItem
import app.gyrolet.mpvrx.domain.download.AppDownloadManager
import app.gyrolet.mpvrx.domain.download.YtdlpDownloadEngine
import app.gyrolet.mpvrx.ui.player.ytdlp.YtdlpManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.koin.core.context.GlobalContext
import java.io.File

/** Owns its input connection independently of the playback session. */
object LiveRecording {
  data class State(val id: String, val name: String, val directory: String, val started: Long, val bytes: Long = 0, val stopping: Boolean = false)
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val mutableState = MutableStateFlow<State?>(null)
  val state = mutableState.asStateFlow()
  @Volatile private var process: Process? = null
  @Volatile private var stopRequested = false

  @Synchronized fun start(context: Context, item: PlaybackItem, options: DownloadExportOptions): Boolean {
    if (mutableState.value != null) return false
    val app = context.applicationContext
    val folder = File(options.directory ?: GlobalContext.get().get<AppDownloadManager>().locations.linksDir().path)
    val file = ExportFiles.reserve(folder, ExportFiles.name(options.fileName.ifBlank { item.title ?: "Recording" }, "mkv"))
    val id = ClipJobs.addRecording(app, item, options, file.path)
    mutableState.value = State(id, file.name, folder.path, System.currentTimeMillis())
    stopRequested = false
    try { RecordingService.start(app) }
    catch (error: Exception) { mutableState.value = null; file.delete(); ClipJobs.update(id, status = "Failed", error = "Could not start the recording service"); throw error }
    scope.launch {
      var portal: ExportSource? = null
      try {
        portal = ExportSource.portal(item)
        var input = portal?.uri ?: item.playableUri.ifBlank { item.originalUri }
        var audio: String? = null
        var headers = item.headers
        if (YtdlpManager.requiresYtdlp(item.originalUri) && !StreamExportAvailability.isStream(item.originalUri, item.playableUri, mime = item.mimeType)) {
          val streams = GlobalContext.get().get<YtdlpDownloadEngine>().resolveForClip(item.originalUri)
          input = streams.video; audio = streams.audio; headers = streams.headers + headers
        }
        require(listOf("http://", "https://", "rtsp://", "rtmp://", "rtmps://", "udp://", "srt://").any(input::startsWith)) { "This live source cannot be recorded" }
        if (stopRequested) { file.delete(); ClipJobs.update(id, status = "Cancelled"); return@launch }
        val args = buildList {
          addAll(listOf("-hide_banner", "-y", "-progress", "pipe:1", "-nostats"))
          fun source(url: String) {
            addAll(listOf("-rw_timeout", "15000000", "-fflags", "+genpts"))
            if (url.startsWith("http") && headers.isNotEmpty()) addAll(listOf("-headers", headers.entries.joinToString("") { "${it.key}: ${it.value}\r\n" }))
            if (FfmpegRuntime.isHls(url, item.mimeType)) {
              addAll(FfmpegRuntime.remoteHlsOptions)
              addAll(listOf("-live_start_index", "-1"))
            }
            addAll(listOf("-i", url))
          }
          source(input); audio?.let(::source)
          addAll(listOf("-map", "0:v:0?", "-map", if (audio == null) "0:a:0?" else "1:a:0?", "-c", "copy", "-f", "matroska", file.path))
        }
        val builder = ProcessBuilder(listOf(FfmpegRuntime.executable(app)) + args).redirectErrorStream(true)
        builder.environment()["LD_LIBRARY_PATH"] = FfmpegRuntime.libraries(app).path + ":" + app.applicationInfo.nativeLibraryDir
        val worker = builder.start(); process = worker
        if (stopRequested) requestStop(worker)
        ClipJobs.update(id, status = if (stopRequested) "Stopping" else "Recording")
        worker.inputStream.bufferedReader().useLines { lines -> lines.forEach { line ->
          if (line.startsWith("out_time_us=")) {
            val current = mutableState.value
            if (current?.id == id) mutableState.value = current.copy(bytes = file.length())
          }
        } }
        val code = worker.waitFor(); process = null
        require(file.length() > 0) { "The stream did not provide any media to record" }
        val verified = FfmpegRuntime.run(app, listOf("-v", "error", "-show_entries", "format=duration:stream=codec_type", "-of", "json", file.path), probe = true)
        require(verified.first == 0) { "Could not finalize this recording" }
        val streams = org.json.JSONObject(verified.second).optJSONArray("streams")
        require(streams != null && streams.length() > 0) { "The recording contains no media tracks" }
        ClipJobs.update(id, status = "Saving")
        val artwork = ExportFiles.artwork(app, options, item.artworkUri)
        ExportFiles.decorate(app, file, options.author, artwork)
        AppDownloadManager.notifyCompletedMedia(app, file)
        ClipJobs.update(id, status = "Completed", progress = 1f, output = file.path, posterUrl = artwork,
          error = if (code != 0 && !stopRequested) "The stream ended unexpectedly; the recorded portion was saved." else null, endSeconds = org.json.JSONObject(verified.second).optJSONObject("format")?.optString("duration")?.toDoubleOrNull())
      } catch (error: Throwable) {
        if (file.length() == 0L) file.delete()
        ClipJobs.update(id, status = "Failed", error = "Recording failed. Check the stream connection and save folder.")
      } finally {
        process?.takeIf { it.isAlive }?.destroyForcibly(); process = null
        portal?.close?.invoke()
        mutableState.value = null
      }
    }
    return true
  }

  @Synchronized fun stop() {
    val current = mutableState.value ?: return
    stopRequested = true
    mutableState.value = current.copy(stopping = true)
    ClipJobs.update(current.id, status = "Stopping")
    process?.let(::requestStop)
  }

  private fun requestStop(worker: Process) {
    runCatching { worker.outputStream.write("q\n".toByteArray()); worker.outputStream.flush() }
    scope.launch { delay(20_000); if (worker.isAlive) worker.destroy() }
  }
}
