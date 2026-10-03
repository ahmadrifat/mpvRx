/* SPDX-License-Identifier: AGPL-3.0-or-later */
package app.gyrolet.mpvrx.ui.player.clip

import android.content.Context
import android.net.Uri
import app.gyrolet.mpvrx.domain.download.YtdlpDownloadEngine
import app.gyrolet.mpvrx.ui.player.ytdlp.YtdlpManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.koin.core.context.GlobalContext
import java.io.File
import java.util.Locale

/** Decode only audio: sample-accurate trimming without video conversion or video downloading
 * when the extractor supplies a separate audio stream. */
internal object AudioClipExporter {
  suspend fun export(context: Context, source: String, original: String, output: String,
    start: Double, end: Double, headers: Map<String, String>,
    onProgress: (Double) -> Unit, onStage: (String) -> Unit): String? {
    var copiedInput: File? = null
    try {
      onStage("Preparing")
      var input = source
      var requestHeaders = headers
      if (source.startsWith("http") || source.startsWith("edl://")) {
        if (YtdlpManager.requiresYtdlp(original)) {
          val streams = GlobalContext.get().get<YtdlpDownloadEngine>().resolveForClip(original, audioOnly = true)
          input = streams.audio ?: streams.video
          requestHeaders = streams.headers + headers
        }
      }
      if (input.startsWith("content://")) {
        val copy = File.createTempFile("audio-input-", ".media", context.cacheDir)
        copiedInput = copy
        withContext(Dispatchers.IO) {
          context.contentResolver.openInputStream(Uri.parse(input))?.use { src ->
            copy.outputStream().use(src::copyTo)
          } ?: error("Could not read this media")
        }
        input = copy.absolutePath
      }
      val duration = "%.6f".format(Locale.US, end - start)
      val args = buildList {
        addAll(listOf("-hide_banner", "-nostdin", "-y", "-progress", "pipe:1", "-nostats"))
        if (input.startsWith("http") && requestHeaders.isNotEmpty()) {
          addAll(listOf("-headers", requestHeaders.entries.joinToString("") { "${it.key}: ${it.value}\r\n" }))
        }
        addAll(listOf("-ss", "%.6f".format(Locale.US, start), "-i", input,
          "-t", duration, "-map", "0:a:0", "-vn", "-sn", "-dn",
          "-af", "atrim=start=0:end=$duration,asetpts=PTS-STARTPTS",
          "-c:a", "aac", "-b:a", "192k", "-movflags", "+faststart", output))
      }
      onStage("Clipping")
      val result = FfmpegRuntime.run(context, args) { line ->
        if (line.startsWith("out_time_us=")) line.substringAfter('=').toLongOrNull()?.let {
          onProgress((it / ((end - start) * 1_000_000)).coerceIn(0.0, 0.99))
        }
      }
      if (result.first != 0) return "Could not save audio. Check that this media has an audio track and that the source is available."
      val probe = FfmpegRuntime.run(context, listOf("-v", "error", "-show_entries",
        "format=duration:stream=codec_type,start_time", "-of", "json", output), probe = true)
      if (probe.first != 0) return "Could not verify the saved audio"
      val json = org.json.JSONObject(probe.second)
      val actualDuration = json.optJSONObject("format")?.optString("duration")?.toDoubleOrNull()
      val tracks = json.optJSONArray("streams")
      if (actualDuration == null || kotlin.math.abs(actualDuration - (end - start)) > 0.03 ||
        tracks == null || tracks.length() != 1 || tracks.getJSONObject(0).optString("codec_type") != "audio") {
        return "The saved audio did not pass timestamp validation"
      }
      onProgress(1.0)
      return null
    } catch (e: CancellationException) { throw e }
    catch (_: Exception) { return "Could not save audio from this media" }
    finally { withContext(NonCancellable + Dispatchers.IO) { copiedInput?.delete() } }
  }
}
