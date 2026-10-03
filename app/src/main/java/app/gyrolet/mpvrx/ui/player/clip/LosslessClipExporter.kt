/* SPDX-License-Identifier: AGPL-3.0-or-later */
package app.gyrolet.mpvrx.ui.player.clip

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer

/** Packet copy only. The start is snapped to the preceding video sync frame. */
internal object LosslessClipExporter {
  suspend fun export(context: Context, source: String, output: String, startSeconds: Double, endSeconds: Double, headers: Map<String, String>, onProgress: (Double) -> Unit): String? = withContext(Dispatchers.IO) {
    val extractor = MediaExtractor()
    var muxer: MediaMuxer? = null
    var success = false
    try {
      val uri = Uri.parse(source)
      if (uri.scheme == null) extractor.setDataSource(source) else extractor.setDataSource(context, uri, headers)
      val formats = (0 until extractor.trackCount).map(extractor::getTrackFormat)
      val videoIndex = formats.indexOfFirst { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
      require(videoIndex >= 0) { "No video track is available for lossless trimming" }
      extractor.selectTrack(videoIndex)
      extractor.seekTo((startSeconds * 1_000_000).toLong(), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
      val baseUs = extractor.sampleTime
      require(baseUs >= 0) { "Could not seek to a video keyframe" }
      extractor.unselectTrack(videoIndex)
      val writer = MediaMuxer(output, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
      muxer = writer
      val rotation = formats[videoIndex]
      if (rotation.containsKey(MediaFormat.KEY_ROTATION)) writer.setOrientationHint(rotation.getInteger(MediaFormat.KEY_ROTATION))
      val tracks = formats.mapIndexed { index, format ->
        val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
        require(mime.startsWith("video/") || mime.startsWith("audio/")) { "This file contains tracks MP4 lossless export cannot preserve. Use precise export." }
        extractor.selectTrack(index)
        writer.addTrack(format)
      }
      extractor.seekTo(baseUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
      writer.start()
      val suggestedSize = formats.maxOf { if (it.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) it.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0 }
      val buffer = ByteBuffer.allocateDirect(maxOf(suggestedSize, 16 * 1024 * 1024))
      val info = MediaCodec.BufferInfo()
      val endUs = (endSeconds * 1_000_000).toLong()
      var samples = 0
      while (extractor.sampleTrackIndex >= 0 && extractor.sampleTime < endUs) {
        currentCoroutineContext().ensureActive()
        val time = extractor.sampleTime
        buffer.clear()
        val size = extractor.readSampleData(buffer, 0)
        if (size < 0) break
        require(size <= buffer.capacity()) { "Encoded frame exceeds the lossless export buffer" }
        require(extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED == 0) { "Encrypted tracks cannot be copied" }
        if (time >= baseUs) {
          val flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
          info.set(0, size, time - baseUs, flags)
          writer.writeSampleData(tracks[extractor.sampleTrackIndex], buffer, info)
          samples++
          onProgress(((time - baseUs).toDouble() / (endUs - baseUs)).coerceIn(0.0, 1.0))
        }
        extractor.advance()
      }
      require(samples > 0) { "No samples were found in this clip" }
      writer.stop()
      success = true
      null
    } catch (error: kotlinx.coroutines.CancellationException) {
      throw error
    } catch (error: Exception) {
      "Lossless export failed: ${error.message}. Try precise export for this format."
    } finally {
      extractor.release()
      runCatching { muxer?.release() }
      if (!success) File(output).delete()
    }
  }
}
