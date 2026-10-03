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
  data class Job(val id: String, val title: String, val source: String, val playable: String, val start: Double, val end: Double, val crop: ClipCrop? = null, val headers: Map<String, String> = emptyMap(), val status: String = "Preparing", val progress: Float? = null, val output: String? = null, val error: String? = null, val torrentIndex: Int? = null) {
    val active get() = status in listOf("Preparing", "Downloading", "Clipping", "Saving")
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
        val job = Job(j.getString("id"), j.getString("title"), j.getString("source"), j.getString("playable"), j.getDouble("start"), j.getDouble("end"), c?.let { ClipCrop(it.getInt("x"), it.getInt("y"), it.getInt("w"), it.getInt("h"), it.getInt("rotation")) }, h.keys().asSequence().associateWith { h.getString(it) }, status = j.getString("status"), output = j.optString("output").takeIf(String::isNotBlank), error = j.optString("error").takeIf(String::isNotBlank), torrentIndex = j.optInt("torrentIndex", -1).takeIf { it >= 0 })
        if (job.active) job.copy(status = "Failed", error = "Clipping was interrupted. Tap Retry.") else job
      }
    }.getOrDefault(emptyList())
  }
  @Synchronized fun add(context: Context, request: ClipRequest): String {
    initialize(context)
    val id = java.util.UUID.randomUUID().toString()
    state.value = state.value + Job(id, request.item.title ?: "Video clip", request.item.originalUri, request.item.playableUri, request.startSeconds, request.endSeconds, request.crop, request.item.headers, torrentIndex = request.item.torrentFileIndex)
    save()
    return id
  }
  @Synchronized fun update(id: String, status: String? = null, progress: Float? = null, output: String? = null, error: String? = null) {
    state.value = state.value.map { if (it.id == id) it.copy(status = status ?: it.status, progress = progress, output = output ?: it.output, error = error) else it }
    if (status != null || System.currentTimeMillis() - lastWrite > 1500) save()
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
    if (ClipExportManager.export(context, ClipRequest(PlaybackItem(job.id, job.source, job.playable, title = job.title, headers = job.headers, torrentFileIndex = job.torrentIndex), job.start, job.end, job.crop))) {
      synchronized(this) { state.value = state.value.filterNot { it.id == job.id }; save() }
    }
  }
  private fun save() {
    lastWrite = System.currentTimeMillis()
    val array = JSONArray()
    state.value.forEach { j ->
      val record = JSONObject().put("id", j.id).put("title", j.title).put("source", j.source).put("playable", j.playable).put("start", j.start).put("end", j.end).put("headers", JSONObject(j.headers)).put("status", j.status).put("output", j.output ?: "").put("error", j.error ?: "").put("torrentIndex", j.torrentIndex ?: -1)
      j.crop?.let { record.put("crop", JSONObject().put("x", it.x).put("y", it.y).put("w", it.width).put("h", it.height).put("rotation", it.rotation)) }
      array.put(record)
    }
    val temp = File(directory, "clip-jobs.json.tmp"); temp.writeText(array.toString())
    java.nio.file.Files.move(temp.toPath(), File(directory, "clip-jobs.json").toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
  }
}
