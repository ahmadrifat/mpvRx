/* SPDX-License-Identifier: AGPL-3.0-or-later */
package app.gyrolet.mpvrx.ui.player.clip

import android.content.Context
import android.media.MediaExtractor
import android.net.Uri
import app.gyrolet.mpvrx.ui.player.ytdlp.YtdlpManager
import app.gyrolet.mpvrx.domain.download.YtdlpDownloadEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import java.io.File
import org.koin.core.context.GlobalContext

internal object AutomaticClipExporter {
  suspend fun export(context: Context, source: String, original: String, output: String, start: Double, end: Double, crop: ClipCrop?, frameWidth: Int, frameHeight: Int, headers: Map<String, String>, onProgress: (Double) -> Unit, onStage: (String) -> Unit, onThumbnail: (String?) -> Unit = {}): String? {
    onStage("Preparing")
    var acquired: File? = null
    var effective = source
    var audio: String? = null
    var requestHeaders = headers
    var extracted = false
    try {
      val extractorSite = (original.startsWith("http://") || original.startsWith("https://")) && YtdlpManager.requiresYtdlp(original)
      if (extractorSite && (source.startsWith("http") || source.startsWith("edl://"))) {
        val streams = GlobalContext.get().get<YtdlpDownloadEngine>().resolveForClip(original)
        onThumbnail(streams.thumbnail)
        effective = streams.video
        audio = streams.audio
        requestHeaders = streams.headers + headers
        extracted = true
      }
      onStage("Clipping")
      // Media3 uses the device's codecs and optimizes compatible local MP4 trims.
      val local = !effective.startsWith("http://") && !effective.startsWith("https://")
      val expectedFrames = if (local) countSourceFrames(context, effective, start, end) else null
      if (audio == null) {
        val hardwareError = Media3ClipExporter.export(context, effective, output, start, end, crop, frameWidth, frameHeight, requestHeaders, onProgress)
        if (hardwareError == null && validate(context, output, end - start, expectedFrames)) return null
        File(output).delete()
      }
      // FFmpeg covers inputs Android's extractor/encoder cannot handle. Content URIs need a path.
      if (effective.startsWith("content://")) {
        val localCopy = File.createTempFile("clip-input-", ".media", context.cacheDir)
        withContext(Dispatchers.IO) { context.contentResolver.openInputStream(Uri.parse(effective))?.use { input -> localCopy.outputStream().use(input::copyTo) } ?: error("Could not read this video") }
        if (acquired == null) acquired = localCopy
        effective = localCopy.absolutePath
      }
      val args = buildList {
        addAll(listOf("-hide_banner", "-nostdin", "-y", "-progress", "pipe:1", "-nostats"))
        fun input(url: String) {
          if (requestHeaders.isNotEmpty() && url.startsWith("http")) addAll(listOf("-headers", requestHeaders.entries.joinToString("") { "${it.key}: ${it.value}\r\n" }))
          addAll(listOf("-ss", "%.6f".format(java.util.Locale.US, start), "-i", url))
        }
        input(effective)
        audio?.let(::input)
        addAll(listOf("-t", "%.6f".format(java.util.Locale.US, end - start), "-map", "0:v:0", "-map", if (audio == null) "0:a:0?" else "1:a:0", "-sn", "-dn"))
        // Accurate seek may deliver a decoded frame just before zero. Explicit filtering
        // prevents encoder timestamp rounding from retaining that extra boundary frame.
        val duration = "%.6f".format(java.util.Locale.US, end - start)
        val videoFilters = mutableListOf("trim=start=0:end=$duration")
        if (crop != null) videoFilters.add("crop=${crop.width}:${crop.height}:${crop.x}:${crop.y}")
        addAll(listOf("-vf", videoFilters.joinToString(","), "-af", "atrim=start=0:end=$duration"))
        addAll(listOf("-c:v", "libx264", "-preset", "veryfast", "-crf", "18", "-pix_fmt", "yuv420p", "-fps_mode", "passthrough", "-c:a", "aac", "-b:a", "192k", "-movflags", "+faststart", output))
      }
      val result = FfmpegRuntime.run(context, args) { line ->
        if (line.startsWith("out_time_us=")) line.substringAfter('=').toLongOrNull()?.let { onProgress((it / ((end - start) * 1_000_000)).coerceIn(0.0, 0.99)) }
      }
      if (result.first != 0 && extracted) {
        onStage("Downloading")
        acquired = GlobalContext.get().get<YtdlpDownloadEngine>().acquireForClip(original) { onProgress(it / 100.0) }
        return export(context, acquired.absolutePath, original, output, start, end, crop, frameWidth, frameHeight, headers, onProgress, onStage)
      }
      if (result.first != 0) return "Could not clip this video: " + result.second.lineSequence().filter { it.isNotBlank() }.toList().takeLast(3).joinToString(" ").replace(Regex("https?://[^\\s]+"), "[source]").take(240)
      if (!validate(context, output, end - start, expectedFrames)) return "The exported clip did not pass timestamp validation"
      onProgress(1.0)
      return null
    } catch (e: CancellationException) { throw e }
    catch (e: Exception) { return "Could not clip this video: ${e.message}" }
    finally { acquired?.let { file -> withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { file.delete(); file.parentFile?.takeIf { it.name.startsWith("clip-acquire-") }?.deleteRecursively() } } }
  }

  private suspend fun countSourceFrames(context: Context, source: String, start: Double, end: Double): Int? = withContext(Dispatchers.IO) {
    val extractor = MediaExtractor()
    try {
      if (source.startsWith("content://")) extractor.setDataSource(context, Uri.parse(source), null)
      else extractor.setDataSource(if (source.startsWith("file://")) Uri.parse(source).path!! else source)
      val track = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME)?.startsWith("video/") == true } ?: return@withContext null
      extractor.selectTrack(track)
      extractor.seekTo((start * 1_000_000).toLong(), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
      var count = 0
      val from = (start * 1_000_000).toLong()
      val until = (end * 1_000_000).toLong()
      // Encoded B frames arrive in decode order; read beyond the boundary before counting PTS.
      while (extractor.sampleTime >= 0 && extractor.sampleTime < until + 5_000_000) {
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        if (extractor.sampleTime >= from && extractor.sampleTime < until) count++
        if (!extractor.advance()) break
      }
      count.takeIf { it > 0 }
    } catch (e: CancellationException) { throw e }
    catch (_: Exception) { null }
    finally { extractor.release() }
  }

  private suspend fun validate(context: Context, output: String, requested: Double, expectedFrames: Int?): Boolean = withContext(Dispatchers.IO) {
    if (!File(output).isFile || File(output).length() == 0L) return@withContext false
    val probe = FfmpegRuntime.run(context, listOf("-v", "error", "-count_packets", "-show_entries", "format=duration:stream=codec_type,start_time,duration,avg_frame_rate,nb_read_packets", "-of", "json", output), probe = true)
    if (probe.first != 0) return@withContext false
    val json = org.json.JSONObject(probe.second)
    val duration = json.optJSONObject("format")?.optString("duration")?.toDoubleOrNull() ?: return@withContext false
    val streams = json.optJSONArray("streams") ?: return@withContext false
    val video = (0 until streams.length()).map { streams.getJSONObject(it) }.firstOrNull { it.optString("codec_type") == "video" } ?: return@withContext false
    val rate = video.optString("avg_frame_rate").split('/').mapNotNull(String::toDoubleOrNull)
    val frame = if (rate.size == 2 && rate[0] > 0) rate[1] / rate[0] else 0.05
    val tolerance = maxOf(frame * 2, 0.05)
    val videoDuration = video.optString("duration").toDoubleOrNull() ?: return@withContext false
    // Audio can conceal missing video frames in the container's overall duration.
    // Reject shortened optimized exports so the precise fallback handles them.
    duration > 0 && kotlin.math.abs(duration - requested) <= tolerance &&
      (if (expectedFrames != null) video.optString("nb_read_packets").toIntOrNull() == expectedFrames
       else videoDuration >= requested - frame * 0.5 - 0.002)
  }
}
