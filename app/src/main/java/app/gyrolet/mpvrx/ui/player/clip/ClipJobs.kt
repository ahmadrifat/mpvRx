/* SPDX-License-Identifier: AGPL-3.0-or-later */
package app.gyrolet.mpvrx.ui.player.clip

import android.content.Context
import app.gyrolet.mpvrx.ui.player.PlaybackItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object ClipJobs {
  data class Job(val id: String, val title: String, val source: String, val playable: String, val start: Double, val end: Double, val crop: ClipCrop? = null, val headers: Map<String, String> = emptyMap(), val status: String = "Preparing", val progress: Float? = null, val output: String? = null, val error: String? = null, val torrentIndex: Int? = null, val audioOnly: Boolean = false, val audioFormat: AudioExportFormat = AudioExportFormat.M4A, val posterUrl: String? = null, val options: DownloadExportOptions = DownloadExportOptions(), val recording: Boolean = false) {
    val active get() = status in listOf("Preparing", "Downloading", "Clipping", "Saving", "Recording", "Stopping")
  }
  private val state = MutableStateFlow<List<Job>>(emptyList())
  val jobs = state.asStateFlow()
  private var directory: File? = null
  private var lastWrite = 0L
  @Synchronized fun initialize(context: Context) {
    if (directory != null) return
    directory = context.filesDir
    state.value = runCatching {
      val array = JSONArray(File(directory, "clip-jobs.json").readText())
      (0 until array.length()).map { index ->
        val j = array.getJSONObject(index)
        val h = j.optJSONObject("headers") ?: JSONObject()
        val c = j.optJSONObject("crop")
        val job = Job(j.getString("id"), j.getString("title"), j.getString("source"), j.getString("playable"), j.getDouble("start"), j.getDouble("end"), c?.let { ClipCrop(it.getInt("x"), it.getInt("y"), it.getInt("w"), it.getInt("h"), it.getInt("rotation")) }, h.keys().asSequence().associateWith { h.getString(it) }, status = j.getString("status"), output = j.optString("output").takeIf(String::isNotBlank), error = j.optString("error").takeIf(String::isNotBlank), torrentIndex = j.optInt("torrentIndex", -1).takeIf { it >= 0 }, audioOnly = j.optBoolean("audioOnly", false), audioFormat = AudioExportFormat.fromStored(j.optString("audioFormat")), posterUrl = j.optString("posterUrl").takeIf(String::isNotBlank), options = j.optJSONObject("options")?.let { kotlinx.serialization.json.Json.decodeFromString<DownloadExportOptions>(it.toString()) } ?: DownloadExportOptions(), recording = j.optBoolean("recording"))
        if (job.active) job.copy(status = "Failed", error = if (job.recording) "Recording was interrupted." else "Export was interrupted. Tap Retry.") else job
      }
    }.getOrDefault(emptyList())
  }
  @Synchronized fun add(context: Context, request: ClipRequest): String {
    initialize(context)
    val id = java.util.UUID.randomUUID().toString()
    state.value = state.value + Job(id, request.options.fileName.takeIf(String::isNotBlank) ?: request.item.title ?: "Media export", request.item.originalUri, request.item.playableUri, request.startSeconds, request.endSeconds, request.crop, request.item.headers, torrentIndex = request.item.torrentFileIndex, audioOnly = request.audioOnly, audioFormat = request.audioFormat, posterUrl = request.item.artworkUri, options = request.options)
    save()
    return id
  }
  @Synchronized fun addRecording(context: Context, item: PlaybackItem, options: DownloadExportOptions, output: String): String {
    val id = add(context, ClipRequest(item, 0.0, 1.0, options = options))
    state.value = state.value.map { if (it.id == id) it.copy(recording = true, status = "Preparing", output = output) else it }
    save(); return id
  }
  @Synchronized fun update(id: String, status: String? = null, progress: Float? = null, output: String? = null, error: String? = null, posterUrl: String? = null, endSeconds: Double? = null) {
    state.value = state.value.map { if (it.id == id) it.copy(end = endSeconds ?: it.end, status = status ?: it.status, progress = progress, output = output ?: it.output, error = error, posterUrl = posterUrl ?: it.posterUrl) else it }
    if (status != null || System.currentTimeMillis() - lastWrite > 1500) save()
  }
  suspend fun rename(context: Context, job: Job, name: String) {
    require(job.status == "Completed") { "Wait for the export to finish" }
    app.gyrolet.mpvrx.domain.download.DownloadFileRename.rename(context, job.output ?: error("File not found"), name) { path ->
      synchronized(this) {
        val previous = state.value
        state.value = previous.map { if (it.id == job.id) it.copy(output = path, title = name.substringBeforeLast('.')) else it }
        try { save() } catch (error: Throwable) { state.value = previous; throw error }
      }
    }
  }

  @Synchronized fun remove(context: Context, job: Job) {
    if (job.active) return
    job.output?.let { uri ->
      if (uri.startsWith("content://")) context.contentResolver.delete(android.net.Uri.parse(uri), null, null)
      else File(android.net.Uri.parse(uri).path ?: uri).delete()
    }
    state.value = state.value.filterNot { it.id == job.id }; save()
  }
  fun retry(context: Context, job: Job) {
    if (job.recording) return
    if (ClipExportManager.export(context, ClipRequest(PlaybackItem(job.id, job.source, job.playable, title = job.title, headers = job.headers, artworkUri = job.posterUrl, torrentFileIndex = job.torrentIndex), job.start, job.end, job.crop, job.audioOnly, job.audioFormat, job.options))) {
      synchronized(this) { state.value = state.value.filterNot { it.id == job.id }; save() }
    }
  }
  private fun save() {
    lastWrite = System.currentTimeMillis()
    val array = JSONArray()
    state.value.forEach { j ->
      val record = JSONObject().put("id", j.id).put("title", j.title).put("source", j.source).put("playable", j.playable).put("start", j.start).put("end", j.end).put("headers", JSONObject(j.headers)).put("status", j.status).put("output", j.output ?: "").put("error", j.error ?: "").put("torrentIndex", j.torrentIndex ?: -1).put("audioOnly", j.audioOnly).put("audioFormat", j.audioFormat.name).put("posterUrl", j.posterUrl ?: "").put("recording", j.recording).put("options", JSONObject(kotlinx.serialization.json.Json.encodeToString(DownloadExportOptions.serializer(), j.options)))
      j.crop?.let { record.put("crop", JSONObject().put("x", it.x).put("y", it.y).put("w", it.width).put("h", it.height).put("rotation", it.rotation)) }
      array.put(record)
    }
    val temp = File(directory, "clip-jobs.json.tmp"); temp.writeText(array.toString())
    java.nio.file.Files.move(temp.toPath(), File(directory, "clip-jobs.json").toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
  }
}
