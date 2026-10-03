package app.gyrolet.mpvrx

import android.app.Instrumentation
import android.os.Bundle
import app.gyrolet.mpvrx.ui.player.clip.AutomaticClipExporter
import app.gyrolet.mpvrx.ui.player.clip.FfmpegRuntime
import kotlinx.coroutines.runBlocking
import java.io.File

/** Runs on a real Android runtime, without a third-party test runner. */
class ClipSmokeInstrumentation : Instrumentation() {
  override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
  override fun onStart() {
    val result = Bundle()
    try {
      runBlocking {
        val context = targetContext
        val directory = File(context.cacheDir, "clip-smoke").apply { mkdirs() }
        val source = File(directory, "source.mp4")
        val output = File(directory, "clip.mp4")
        val generated = FfmpegRuntime.run(context, listOf("-y", "-f", "lavfi", "-i", "nullsrc=s=160x90:r=30,geq=r='mod(N*11,256)':g=0:b=0", "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000", "-t", "4", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-g", "90", "-c:a", "aac", source.absolutePath))
        check(generated.first == 0) { "FFmpeg generation failed: ${generated.second.takeLast(1000)}" }
        val error = AutomaticClipExporter.export(context, source.absolutePath, source.absolutePath, output.absolutePath, 1.125, 1.792, null, 160, 90, emptyMap(), {}, {})
        check(error == null) { error.orEmpty() + FfmpegRuntime.run(context, listOf("-v", "error", "-count_frames", "-show_streams", "-of", "json", output.absolutePath), probe = true).second }
        val expected = File(directory, "expected.rgb")
        val actual = File(directory, "actual.rgb")
        val a = FfmpegRuntime.run(context, listOf("-y", "-i", source.absolutePath, "-vf", "select=eq(n\\,34)", "-frames:v", "1", "-f", "rawvideo", "-pix_fmt", "rgb24", expected.absolutePath))
        val b = FfmpegRuntime.run(context, listOf("-y", "-i", output.absolutePath, "-frames:v", "1", "-f", "rawvideo", "-pix_fmt", "rgb24", actual.absolutePath))
        check(a.first == 0 && b.first == 0)
        fun red(file: File): Double {
          val bytes = file.readBytes()
          check(bytes.size == 160 * 90 * 3)
          return bytes.indices.filter { it % 3 == 0 }.map { bytes[it].toInt() and 255 }.average()
        }
        check(kotlin.math.abs(red(expected) - red(actual)) < 4) { "First frame does not match requested non-keyframe cut: ${red(expected)} vs ${red(actual)}" }
        val stats = FfmpegRuntime.run(context, listOf("-v", "error", "-count_frames", "-show_streams", "-of", "json", output.absolutePath), probe = true)
        val expectedEnd = File(directory, "expected-end.rgb")
        val actualEnd = File(directory, "actual-end.rgb")
        val endA = FfmpegRuntime.run(context, listOf("-y", "-i", source.absolutePath, "-vf", "select=eq(n\\,53)", "-frames:v", "1", "-f", "rawvideo", "-pix_fmt", "rgb24", expectedEnd.absolutePath))
        val endB = FfmpegRuntime.run(context, listOf("-y", "-i", output.absolutePath, "-vf", "select=eq(n\\,19)", "-frames:v", "1", "-f", "rawvideo", "-pix_fmt", "rgb24", actualEnd.absolutePath))
        check(endA.first == 0 && endB.first == 0)
        check(actualEnd.length() == 160L * 90 * 3) { stats.second }
        check(kotlin.math.abs(red(expectedEnd) - red(actualEnd)) < 4) { "Last frame does not match requested cut" }
        val probe = FfmpegRuntime.run(context, listOf("-v", "error", "-count_frames", "-show_entries", "stream=codec_type,start_time,nb_read_frames,duration", "-of", "json", output.absolutePath), probe = true)
        check(probe.first == 0)
        val streams = org.json.JSONObject(probe.second).getJSONArray("streams")
        val video = (0 until streams.length()).map { streams.getJSONObject(it) }.first { it.getString("codec_type") == "video" }
        check(video.getString("nb_read_frames").toInt() == 20) { "Expected exactly frames 34 through 53" }
        val audio = (0 until streams.length()).map { streams.getJSONObject(it) }.first { it.getString("codec_type") == "audio" }
        check(kotlin.math.abs(audio.getString("start_time").toDouble() - video.getString("start_time").toDouble()) < 0.034)
        result.putString("result", "PASS: Android FFmpeg runtime, automatic export, millisecond non-keyframe start, first/last-frame content, exact 20-frame count and audio/video start alignment")
        directory.deleteRecursively()
      }
      finish(android.app.Activity.RESULT_OK, result)
    } catch (error: Throwable) {
      result.putString("result", "FAIL: ${error.stackTraceToString()}")
      finish(android.app.Activity.RESULT_CANCELED, result)
    }
  }
}
