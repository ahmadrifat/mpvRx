/* SPDX-License-Identifier: AGPL-3.0-or-later */
package app.gyrolet.mpvrx

import android.content.Context
import app.gyrolet.mpvrx.ui.player.PlaybackItem
import app.gyrolet.mpvrx.ui.player.clip.*
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File

/** Real device-runtime checks for metadata, independent exports and live recording finalization. */
object DownloadExportSmoke {
  suspend fun run(context: Context, source: File, audioSource: File) {
    val folder = File(context.cacheDir, "download-export-smoke").apply { mkdirs() }
    val image = File(folder, "cover.jpg")
    val bitmap = android.graphics.Bitmap.createBitmap(64, 64, android.graphics.Bitmap.Config.ARGB_8888)
    bitmap.eraseColor(android.graphics.Color.GRAY)
    image.outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it)) }; bitmap.recycle()
    val cover = ExportFiles.thumbnail(context, image.path)!!
    val preservedAudio = File(folder, "preserved.m4a")
    check(AudioClipExporter.export(context, audioSource.path, audioSource.path, preservedAudio.path, 0.0, .667, emptyMap(), {}, {}, fullMedia = true) == null)
    val sourceHash = FfmpegRuntime.run(context, listOf("-v", "error", "-i", audioSource.path, "-map", "0:a:0", "-c", "copy", "-f", "hash", "-"))
    val savedHash = FfmpegRuntime.run(context, listOf("-v", "error", "-i", preservedAudio.path, "-map", "0:a:0", "-c", "copy", "-f", "hash", "-"))
    check(sourceHash.first == 0 && savedHash.first == 0 && sourceHash.second.trim() == savedHash.second.trim()) { "Full compatible audio was re-encoded" }
    for (extension in listOf("m4a", "mp3", "mp4")) {
      val file = File(folder, "metadata.$extension")
      if (extension == "mp3") {
        check(AudioClipExporter.export(context, source.path, source.path, file.path, 0.0, 1.0, emptyMap(), {}, {}, AudioExportFormat.MP3) == null)
      } else (if (extension == "mp4") source else audioSource).copyTo(file, overwrite = true)
      ExportFiles.decorate(context, file, "Test Artist", cover)
      val probe = FfmpegRuntime.run(context, listOf("-v", "error", "-show_entries", "format_tags=artist:stream=codec_type:stream_disposition=attached_pic", "-of", "json", file.path), probe = true)
      check(probe.first == 0)
      val data = JSONObject(probe.second)
      check(data.getJSONObject("format").getJSONObject("tags").getString("artist") == "Test Artist") { probe.second }
      val tracks = data.getJSONArray("streams")
      check((0 until tracks.length()).any { tracks.getJSONObject(it).optJSONObject("disposition")?.optInt("attached_pic") == 1 }) { "Missing $extension cover: ${probe.second}" }
    }
    val item = PlaybackItem("export-smoke", source.path, title = "Original")
    val options = DownloadExportOptions(fileName = "Whole video", directory = folder.path, fullMedia = true)
    val before = ClipJobs.jobs.value.map { it.id }.toSet()
    check(ClipExportManager.export(context, ClipRequest(item, 0.0, 4.0, options = options)))
    check(ClipExportManager.export(context, ClipRequest(item, 1.125, 1.792, audioOnly = true, audioFormat = AudioExportFormat.MP3, options = options.copy(fileName = "Trimmed audio", author = "Test Artist", thumbnail = cover, fullMedia = false))))
    val finished = withTimeout(40_000) {
      while (true) {
        val jobs = ClipJobs.jobs.value.filterNot { it.id in before }
        if (jobs.size == 2 && jobs.none { it.active }) return@withTimeout jobs
        delay(100)
      }
      error("unreachable")
    }
    check(finished.all { it.status == "Completed" }) { finished.toString() }
    val scaled = File(folder, "scaled.mp4")
    check(FfmpegRuntime.run(context, listOf("-y", "-i", source.path, "-vf", "scale=320:180", "-c:v", "libx264", "-c:a", "copy", scaled.path)).first == 0)
    val cropped = File(folder, "cropped.mp4")
    check(AutomaticClipExporter.export(context, scaled.path, scaled.path, cropped.path, 1.125, 1.792, ClipCrop(40, 0, 80, 90, 0), 160, 90, emptyMap(), {}, {}) == null)
    val dimensions = FfmpegRuntime.run(context, listOf("-v", "error", "-select_streams", "v:0", "-show_entries", "stream=width,height", "-of", "json", cropped.path), probe = true)
    val size = JSONObject(dimensions.second).getJSONArray("streams").getJSONObject(0)
    check(size.getInt("width") == 160 && size.getInt("height") == 180) { dimensions.second }
    val whole = finished.first { !it.audioOnly }
    val wholeFile = File(android.net.Uri.parse(whole.output).path!!)
    check(wholeFile.parentFile == folder && wholeFile.name == "Whole video.mp4")
    check(wholeFile.readBytes().contentEquals(source.readBytes())) { "Whole local export changed the source bytes" }
    val saved = JSONObject(File(context.filesDir, "clip-jobs.json").readText().let { org.json.JSONArray(it) }.let { array ->
      (0 until array.length()).map(array::getJSONObject).first { it.getString("id") == whole.id }.toString()
    })
    check(saved.getJSONObject("options").getString("directory") == folder.path)
    val ts = File(folder, "live-source.ts")
    check(FfmpegRuntime.run(context, listOf("-y", "-stream_loop", "5", "-i", source.path, "-c", "copy", "-f", "mpegts", ts.path)).first == 0)
    val server = java.net.ServerSocket(0)
    val pool = java.util.concurrent.Executors.newSingleThreadExecutor()
    val alive = java.util.concurrent.atomic.AtomicBoolean(true)
    pool.submit {
      runCatching { server.accept().use { client ->
        val reader = client.getInputStream().bufferedReader()
        while (!reader.readLine().isNullOrBlank()) { }
        val out = client.getOutputStream()
        out.write("HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\nConnection: close\r\n\r\n".toByteArray())
        ts.inputStream().use { stream ->
          val buffer = ByteArray(188 * 32)
          while (alive.get()) {
            val count = stream.read(buffer); if (count < 0) break
            out.write(buffer, 0, count); out.flush(); Thread.sleep(40)
          }
        }
      } }
    }
    try {
      val recordingItem = PlaybackItem("live-test", "http://127.0.0.1:${server.localPort}/live.ts", title = "Live test")
      check(LiveRecording.start(context, recordingItem, DownloadExportOptions(fileName = "Recorded stream", directory = folder.path)))
      val recordingId = LiveRecording.state.value!!.id
      withTimeout(30_000) { while ((LiveRecording.state.value?.bytes ?: 0) == 0L) { check(LiveRecording.state.value != null) { "Recording failed before media arrived" }; delay(100) } }
      check(!LiveRecording.start(context, recordingItem, options)) { "Allowed a second concurrent recording" }
      LiveRecording.stop()
      withTimeout(30_000) { while (LiveRecording.state.value != null) delay(100) }
      val recorded = ClipJobs.jobs.value.first { it.id == recordingId }
      check(recorded.status == "Completed") { recorded.toString() }
      val probe = FfmpegRuntime.run(context, listOf("-v", "error", "-show_entries", "format=duration:stream=codec_type", "-of", "json", recorded.output!!), probe = true)
      check(probe.first == 0 && JSONObject(probe.second).getJSONArray("streams").length() == 2) { probe.second }
    } finally { alive.set(false); server.close(); pool.shutdownNow() }
    finished.forEach { ClipJobs.remove(context, it) }
    ClipJobs.jobs.value.filter { it.recording && it.options.directory == folder.path }.forEach { ClipJobs.remove(context, it) }
    folder.deleteRecursively()
  }
}
