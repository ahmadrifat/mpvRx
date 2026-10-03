package app.gyrolet.mpvrx

import android.app.Instrumentation
import android.os.Bundle
import app.gyrolet.mpvrx.ui.player.clip.AutomaticClipExporter
import app.gyrolet.mpvrx.ui.player.clip.FfmpegRuntime
import kotlinx.coroutines.runBlocking
import java.io.File

/** Runs on a real Android runtime, without a third-party test runner. */
class ClipSmokeInstrumentation : Instrumentation() {
  private var onlineSource: String? = null
  private var keepFixtures = false
  override fun onCreate(arguments: Bundle?) {
    super.onCreate(arguments)
    onlineSource = arguments?.getString("onlineSource")
    keepFixtures = arguments?.getString("keepFixtures") == "true"
    start()
  }
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
        val videoOnly = File(directory, "video.mp4")
        val audioOnly = File(directory, "audio.m4a")
        check(FfmpegRuntime.run(context, listOf("-y", "-i", source.absolutePath, "-map", "0:v:0", "-c", "copy", videoOnly.absolutePath)).first == 0)
        check(FfmpegRuntime.run(context, listOf("-y", "-i", source.absolutePath, "-map", "0:a:0", "-c", "copy", audioOnly.absolutePath)).first == 0)
        val merged = app.gyrolet.mpvrx.domain.download.YtdlpMediaMerger.merge(context, listOf(videoOnly, audioOnly), File(directory, "merged.mp4"))
        val mergedProbe = FfmpegRuntime.run(context, listOf("-v", "error", "-count_frames", "-show_entries", "stream=codec_type,codec_name,nb_read_frames", "-of", "json", merged.absolutePath), probe = true)
        check(mergedProbe.first == 0)
        val mergedTracks = org.json.JSONObject(mergedProbe.second).getJSONArray("streams")
        val mergedVideo = (0 until mergedTracks.length()).map { mergedTracks.getJSONObject(it) }.first { it.getString("codec_type") == "video" }
        check(mergedVideo.getString("codec_name") == "h264" && mergedVideo.getString("nb_read_frames") == "120")
        check(mergedTracks.length() == 2)
        val audioOutput = File(directory, "audio.m4a")
        val audioError = app.gyrolet.mpvrx.ui.player.clip.AudioClipExporter.export(context,
          source.absolutePath, source.absolutePath, audioOutput.absolutePath,
          1.125, 1.792, emptyMap(), {}, {})
        check(audioError == null) { audioError.orEmpty() }
        val audioProbe = FfmpegRuntime.run(context, listOf("-v", "error", "-show_entries",
          "format=duration:stream=codec_type,start_time", "-of", "json", audioOutput.absolutePath), probe = true)
        val audioJson = org.json.JSONObject(audioProbe.second)
        check(audioJson.getJSONArray("streams").length() == 1)
        check(audioJson.getJSONArray("streams").getJSONObject(0).getString("codec_type") == "audio")
        check(kotlin.math.abs(audioJson.getJSONObject("format").getString("duration").toDouble() - 0.667) <= 0.002)
        check(kotlin.math.abs(audioJson.getJSONArray("streams").getJSONObject(0).getString("start_time").toDouble()) < 0.002)
        app.gyrolet.mpvrx.ui.player.clip.AudioExportFormat.entries.filter { it.name != "M4A" }.forEach { format ->
          val file = File(directory, "audio.${format.extension}")
          val error = app.gyrolet.mpvrx.ui.player.clip.AudioClipExporter.export(context,
            source.absolutePath, source.absolutePath, file.absolutePath, 1.125, 1.792,
            emptyMap(), {}, {}, format)
          check(error == null) { "${format.name}: $error" }
          val probe = FfmpegRuntime.run(context, listOf("-v", "error", "-show_entries",
            "stream=codec_type,codec_name", "-of", "json", file.absolutePath), probe = true)
          val tracks = org.json.JSONObject(probe.second).getJSONArray("streams")
          check(tracks.length() == 1 && tracks.getJSONObject(0).getString("codec_type") == "audio")
          val pcm = File(directory, "decoded-${format.name}.pcm")
          val decoded = FfmpegRuntime.run(context, listOf("-y", "-i", file.absolutePath,
            "-ac", "1", "-ar", "48000", "-f", "s16le", pcm.absolutePath))
          check(decoded.first == 0)
          val seconds = pcm.length() / 96000.0
          // Raw AAC cannot carry gapless delay/padding metadata; MP3/M4A can.
          val tolerance = if (format.name == "AAC") 0.10 else 0.002
          check(kotlin.math.abs(seconds - 0.667) <= tolerance) { "${format.name} decoded duration $seconds" }
        }
        val shortAudioSource = File(directory, "short-audio-source.mp4")
        check(FfmpegRuntime.run(context, listOf("-y", "-i", source.absolutePath,
          "-map", "0:v:0", "-map", "0:a:0", "-c:v", "copy", "-af", "atrim=end=3.5",
          "-c:a", "aac", shortAudioSource.absolutePath)).first == 0)
        val endTrim = File(directory, "end-trim.m4a")
        val endError = app.gyrolet.mpvrx.ui.player.clip.AudioClipExporter.export(context,
          shortAudioSource.absolutePath, shortAudioSource.absolutePath, endTrim.absolutePath,
          1.125, 4.0, emptyMap(), {}, {})
        check(endError == null) { "Audio shorter than video: $endError" }
        val endPcm = File(directory, "end-trim.pcm")
        check(FfmpegRuntime.run(context, listOf("-y", "-i", endTrim.absolutePath,
          "-ac", "1", "-ar", "48000", "-f", "s16le", endPcm.absolutePath)).first == 0)
        val decodedEndDuration = endPcm.length() / 96000.0
        // AAC decoding emits whole codec frames. The M4A timeline stores the exact end;
        // a raw PCM decoder can expose the padded tail of that last frame.
        check(decodedEndDuration >= 2.875 - 0.002 && decodedEndDuration <= 2.875 + 1024.0 / 48000) { "Decoded AAC end $decodedEndDuration" }
        val endProbe = FfmpegRuntime.run(context, listOf("-v", "error", "-show_entries",
          "format=duration", "-of", "json", endTrim.absolutePath), probe = true)
        check(kotlin.math.abs(org.json.JSONObject(endProbe.second).getJSONObject("format")
          .getString("duration").toDouble() - 2.875) <= 0.002)
        val tail = endPcm.readBytes().takeLast(9600)
        check(tail.all { it.toInt() == 0 }) { "Missing end audio should be silence" }
        val longSource = File(directory, "long-audio.wav")
        check(FfmpegRuntime.run(context, listOf("-y", "-f", "lavfi", "-i",
          "sine=frequency=440:sample_rate=44100", "-t", "229.85", "-c:a", "pcm_s16le", longSource.absolutePath)).first == 0)
        val longOutput = File(directory, "long-trim.m4a")
        val longError = app.gyrolet.mpvrx.ui.player.clip.AudioClipExporter.export(context,
          longSource.absolutePath, longSource.absolutePath, longOutput.absolutePath,
          10.7, 230.0, emptyMap(), {}, {})
        check(longError == null) { "219.300 second trim: $longError" }
        val longProbe = FfmpegRuntime.run(context, listOf("-v", "error", "-show_entries",
          "format=duration", "-of", "json", longOutput.absolutePath), probe = true)
        check(kotlin.math.abs(org.json.JSONObject(longProbe.second).getJSONObject("format")
          .getString("duration").toDouble() - 219.3) <= 0.003)
        val delayedSource = File(directory, "delayed-source.mp4")
        check(FfmpegRuntime.run(context, listOf("-y", "-i", source.absolutePath,
          "-itsoffset", "0.25", "-i", source.absolutePath, "-map", "0:v:0", "-map", "1:a:0",
          "-c", "copy", "-t", "4", delayedSource.absolutePath)).first == 0)
        val delayedOutput = File(directory, "delayed.wav")
        val delayedError = app.gyrolet.mpvrx.ui.player.clip.AudioClipExporter.export(context,
          delayedSource.absolutePath, delayedSource.absolutePath, delayedOutput.absolutePath,
          0.0, 1.0, emptyMap(), {}, {}, app.gyrolet.mpvrx.ui.player.clip.AudioExportFormat.WAV)
        check(delayedError == null) { "Delayed audio: $delayedError" }
        testConcurrentDirectDownloads(context, directory)
        result.putString("result", "PASS: Android FFmpeg runtime, automatic export, millisecond non-keyframe start, first/last-frame content, exact 20-frame count and audio/video start alignment; stream-copy merge; overlapping direct downloads; M4A/MP3/WAV/AAC exports, decoded trim checks, M4A 2 ms duration/start checks; audio shorter than video, 219.300 s trim at 44.1 kHz, and delayed-audio timeline regressions")
        onlineSource?.let { url ->
          val onlineOutput = File(directory, "online.m4a")
          val onlineError = app.gyrolet.mpvrx.ui.player.clip.AudioClipExporter.export(context,
            url, url, onlineOutput.absolutePath, 10.7, 230.0, emptyMap(), {}, {})
          result.putString("online_result", if (onlineError == null) "PASS: supplied YouTube interval" else "NOT VERIFIED: $onlineError")
        }
        if (!keepFixtures) directory.deleteRecursively()
      }
      finish(android.app.Activity.RESULT_OK, result)
    } catch (error: Throwable) {
      result.putString("result", "FAIL: ${error.stackTraceToString()}")
      finish(android.app.Activity.RESULT_CANCELED, result)
    }
  }
  private suspend fun testConcurrentDirectDownloads(context: android.content.Context, directory: File) = kotlinx.coroutines.coroutineScope {
    val database = androidx.room.Room.inMemoryDatabaseBuilder(context, app.gyrolet.mpvrx.database.MpvRxDatabase::class.java).build()
    val dao = database.downloadItemDao()
    val locations = org.koin.core.context.GlobalContext.get().get<app.gyrolet.mpvrx.domain.download.DownloadLocations>()
    val manager = app.gyrolet.mpvrx.domain.download.AppDownloadManager(context, dao, locations)
    kotlinx.coroutines.delay(150)
    val server = java.net.ServerSocket(0)
    val arrived = java.util.concurrent.CountDownLatch(2)
    val pool = java.util.concurrent.Executors.newCachedThreadPool()
    val payload = ByteArray(128 * 1024) { (it % 251).toByte() }
    val accepting = pool.submit {
      repeat(2) {
        val client = server.accept()
        pool.submit {
          client.use {
            val reader = it.getInputStream().bufferedReader()
            while (!reader.readLine().isNullOrBlank()) { }
            arrived.countDown()
            check(arrived.await(5, java.util.concurrent.TimeUnit.SECONDS)) { "Second download waited for the first" }
            it.getOutputStream().apply {
              write("HTTP/1.1 200 OK\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n".toByteArray())
              write(payload); flush()
            }
          }
        }
      }
    }
    val ids = (1..2).map { n -> dao.insert(app.gyrolet.mpvrx.database.entities.DownloadItemEntity(url = "http://127.0.0.1:${server.localPort}/$n.mp4", dirPath = directory.absolutePath, fileName = "concurrent-$n.mp4", status = "QUEUED")) }
    try {
      kotlinx.coroutines.withTimeout(15_000) { manager.drainQueue { } }
      check(arrived.count == 0L)
      ids.forEach { id -> val row = dao.findById(id)!!; check(row.status == "SUCCESS"); check(File(row.dirPath, row.fileName).readBytes().contentEquals(payload)) }
      accepting.get(1, java.util.concurrent.TimeUnit.SECONDS)
    } finally { server.close(); pool.shutdownNow(); database.close() }
  }

}
