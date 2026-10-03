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
    onProgress: (Double) -> Unit, onStage: (String) -> Unit, format: AudioExportFormat = AudioExportFormat.M4A, onThumbnail: (String?) -> Unit = {}): String? {
    var copiedInput: File? = null
    try {
      onStage("Preparing")
      var input = source
      var requestHeaders = headers
      if (source.startsWith("http") || source.startsWith("edl://")) {
        if (YtdlpManager.requiresYtdlp(original)) {
          val streams = GlobalContext.get().get<YtdlpDownloadEngine>().resolveForClip(original, audioOnly = true)
          onThumbnail(streams.thumbnail)
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
          // Audio tracks may start later or end earlier than the video timeline.
          // Preserve that timeline with silence, rather than rejecting an otherwise valid cut.
          "-af", "aresample=async=1:first_pts=0,apad=whole_dur=$duration,atrim=start=0:end=$duration,asetpts=PTS-STARTPTS",
          "-c:a", format.encoder))
        if (format != AudioExportFormat.WAV) addAll(listOf("-b:a", "192k"))
        if (format == AudioExportFormat.M4A) addAll(listOf("-movflags", "+faststart"))
        if (format == AudioExportFormat.AAC) addAll(listOf("-f", "adts"))
        add(output)
      }
      onStage("Clipping")
      val result = FfmpegRuntime.run(context, args) { line ->
        if (line.startsWith("out_time_us=")) line.substringAfter('=').toLongOrNull()?.let {
          onProgress((it / ((end - start) * 1_000_000)).coerceIn(0.0, 0.99))
        }
      }
      if (result.first != 0) return "Could not save audio. Check that this media has an audio track and that the source is available."
      val probeArgs = buildList {
        addAll(listOf("-v", "error"))
        if (format == AudioExportFormat.AAC) add("-count_packets")
        addAll(listOf("-show_entries", "format=duration:stream=codec_type,start_time,sample_rate,nb_read_packets", "-of", "json", output))
      }
      val probe = FfmpegRuntime.run(context, probeArgs, probe = true)
      if (probe.first != 0) return "Could not verify the saved audio"
      val json = org.json.JSONObject(probe.second)
      val tracks = json.optJSONArray("streams")
      // ADTS has no duration header. Its bitrate-based estimate can drift over long files;
      // count the AAC-LC packets emitted by our encoder instead of rejecting valid exports.
      val actualDuration = if (format == AudioExportFormat.AAC) {
        val track = tracks?.optJSONObject(0)
        val packets = track?.optString("nb_read_packets")?.toDoubleOrNull()
        val rate = track?.optString("sample_rate")?.toDoubleOrNull()
        if (packets != null && rate != null && rate > 0) packets * 1024.0 / rate else null
      } else json.optJSONObject("format")?.optString("duration")?.toDoubleOrNull()
      if (actualDuration == null || kotlin.math.abs(actualDuration - (end - start)) > (if (format == AudioExportFormat.AAC || format == AudioExportFormat.MP3) 0.10 else 0.03) ||
        tracks == null || tracks.length() != 1 || tracks.getJSONObject(0).optString("codec_type") != "audio") {
        return "Audio duration mismatch (requested ${"%.3f".format(Locale.US, end - start)} s, saved ${actualDuration?.let { "%.3f".format(Locale.US, it) } ?: "unknown"} s)."
      }
      onProgress(1.0)
      return null
    } catch (e: CancellationException) { throw e }
    catch (_: Exception) { return "Could not save audio from this media" }
    finally { withContext(NonCancellable + Dispatchers.IO) { copiedInput?.delete() } }
  }
}
