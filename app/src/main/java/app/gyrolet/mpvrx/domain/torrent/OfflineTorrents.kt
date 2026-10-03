/* SPDX-License-Identifier: AGPL-3.0-or-later */
package app.gyrolet.mpvrx.domain.torrent

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import org.libtorrent4j.SessionManager
import org.libtorrent4j.TorrentHandle
import java.io.File

/** Owns retained downloads independently of the playback engine. */
object OfflineTorrents {
  data class Download(val id: String, val source: String, val index: Int, val title: String, val directory: String, val path: String, val size: Long, val downloaded: Long = 0, val status: String = "Paused") {
    val complete get() = downloaded >= size && size > 0 && File(path).isFile && File(path).length() == size
    val progress get() = if (size > 0) (downloaded.toFloat() / size).coerceIn(0f, 1f) else 0f
  }
  private data class Native(val session: SessionManager, val handle: TorrentHandle, var attached: Boolean = true)
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val natives = mutableMapOf<String, Native>()
  private val _downloads = MutableStateFlow<List<Download>>(emptyList())
  val downloads = _downloads.asStateFlow()
  private var root: File? = null
  private var monitor: Job? = null
  private var ticks = 0

  @Synchronized
  fun initialize(context: Context) {
    if (root != null) return
    root = File(context.filesDir, "offline_torrents").apply { mkdirs() }
    _downloads.value = root!!.listFiles().orEmpty().filter { it.isDirectory }.flatMap { directory -> directory.listFiles().orEmpty().filter { it.name.startsWith("download-") && it.extension == "json" } }.mapNotNull { record ->
      runCatching {
        val directory = record.parentFile!!
        val json = JSONObject(record.readText())
        val relative = json.getString("relativePath")
        val media = File(directory, relative).canonicalFile
        require(media.path.startsWith(directory.canonicalPath + File.separator))
        Download(json.getString("id"), json.getString("source"), json.getInt("index"), json.getString("title"), directory.absolutePath, media.absolutePath, json.getLong("size"), json.optLong("downloaded"), "Paused")
      }.getOrNull()
    }
  }
  @Synchronized
  fun directoryFor(context: Context, source: String): File {
    initialize(context)
    val previous = _downloads.value.firstOrNull { it.source == source || it.id.substringBefore('-') == canonicalInfoHash(source) || android.net.Uri.parse(source).path == File(it.directory, "source.torrent").absolutePath }
    if (previous != null) {
      val related = _downloads.value.filter { it.directory == previous.directory }
      require(related.none { natives[it.id]?.attached == true }) { "This torrent is already playing" }
      related.forEach { natives.remove(it.id)?.session?.stop() }
      _downloads.value = _downloads.value.map { if (it.directory == previous.directory && !it.complete) it.copy(status = "Paused") else it }
      return File(previous.directory)
    }
    return File(root, java.util.UUID.randomUUID().toString()).apply { mkdirs() }
  }
  @Synchronized
  fun attach(context: Context, result: TorrentStreamResult, directory: File, session: SessionManager, handle: TorrentHandle): String {
    initialize(context)
    val id = "${result.infoHash}-${result.selectedFile.index}"
    require(natives[id] == null) { "This torrent is already downloading" }
    val download = Download(id, result.source, result.selectedFile.index, result.selectedFile.name, directory.absolutePath, File(directory, result.selectedFile.path).absolutePath, result.selectedFile.size, status = "Downloading")
    _downloads.value = _downloads.value.filterNot { it.id == id } + download
    natives[id] = Native(session, handle)
    session.addListener(object : org.libtorrent4j.AlertListener {
      override fun types() = intArrayOf(org.libtorrent4j.alerts.AlertType.SAVE_RESUME_DATA.swig())
      override fun alert(alert: org.libtorrent4j.alerts.Alert<*>) {
        if (alert is org.libtorrent4j.alerts.SaveResumeDataAlert) {
          runCatching {
            val params = alert.params()
            File(directory, "resume.dat").writeBytes(org.libtorrent4j.AddTorrentParams.writeResumeDataBuf(params))
            File(directory, "source.torrent").writeBytes(org.libtorrent4j.Entry(org.libtorrent4j.swig.libtorrent.write_torrent_file(params.swig())).bencode())
          }
        }
      }
    })
    handle.saveResumeData(TorrentHandle.SAVE_INFO_DICT)
    persist(download)
    if (monitor?.isActive != true) monitor = scope.launch {
      while (isActive) {
        tick()
        delay(1000)
      }
    }
    TorrentDownloadService.start(context)
    return id
  }
  @Synchronized
  fun detach(id: String) { natives[id]?.attached = false }
  @Synchronized
  fun pauseBackground() {
    val detached = natives.filterValues { !it.attached }.keys.toSet()
    detached.forEach { natives.remove(it)?.session?.stop() }
    _downloads.value = _downloads.value.map { if (it.id in detached && !it.complete) it.copy(status = "Paused") else it }
  }

  @Synchronized
  fun isRetained(directory: File) = _downloads.value.any { it.directory == directory.absolutePath }

  @Synchronized
  private fun tick() {
    ticks++
    _downloads.value = _downloads.value.map { download ->
      val native = natives[download.id] ?: return@map download
      try {
        val bytes = native.handle.fileProgress(TorrentHandle.PIECE_GRANULARITY).getOrNull(download.index) ?: 0L
        val done = bytes >= download.size && download.size > 0
        val updated = download.copy(downloaded = bytes, status = if (done) "Downloaded" else "Downloading")
        if (updated.downloaded != download.downloaded || updated.status != download.status) persist(updated)
        if (done) {
          native.handle.pause()
          if (!native.attached) { native.session.stop(); natives.remove(download.id) }
        }
        if (!done && ticks % 30 == 0) native.handle.saveResumeData(TorrentHandle.SAVE_INFO_DICT)
        updated
      } catch (_: Exception) {
        download.copy(status = "Interrupted — tap Resume")
      }
    }
  }
  private fun persist(download: Download) {
    val directory = File(download.directory)
    val json = JSONObject().put("id", download.id).put("source", download.source).put("index", download.index).put("title", download.title).put("relativePath", File(download.path).relativeTo(directory).path).put("size", download.size).put("downloaded", download.downloaded)
    val target = File(directory, "download-${download.index}.json")
    val temporary = File(directory, "download-${download.index}.json.tmp")
    temporary.writeText(json.toString())
    java.nio.file.Files.move(temporary.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
  }
  fun resume(context: Context, download: Download) {
    scope.launch {
      val engine = TorrentStreamingEngine(context)
      try {
        val metadata = File(download.directory, "source.torrent")
        val source = if (metadata.isFile) android.net.Uri.fromFile(metadata).toString() else download.source
        engine.startStream(TorrentStreamRequest(source, download.index))
        engine.stopStream()
      } catch (error: Exception) {
        synchronized(this@OfflineTorrents) { _downloads.value = _downloads.value.map { if (it.id == download.id) it.copy(status = "Resume failed") else it } }
      }
    }
  }
  @Synchronized
  fun remove(download: Download, deleteMetadata: Boolean): Boolean {
    if (natives[download.id]?.attached == true) return false
    if (_downloads.value.any { it.directory == download.directory && natives[it.id]?.attached == true }) return false
    natives.remove(download.id)?.session?.stop()
    val directory = File(download.directory).canonicalFile
    require(directory.path.startsWith(root!!.canonicalPath + File.separator))
    if (deleteMetadata) {
      val others = _downloads.value.filter { it.directory == download.directory && it.id != download.id }
      if (others.isEmpty()) {
        if (directory.exists() && !directory.deleteRecursively()) return false
      } else {
        val media = File(download.path).canonicalFile
        require(media.path.startsWith(directory.path + File.separator))
        if (media.exists() && !media.delete()) return false
        File(directory, "download-${download.index}.json").delete()
        File(directory, "resume.dat").delete()
      }
      _downloads.value = _downloads.value.filterNot { it.id == download.id }
    } else {
      val media = File(download.path).canonicalFile
      require(media.path.startsWith(directory.path + File.separator))
      if (media.exists() && !media.delete()) return false
      File(directory, "resume.dat").delete()
      val updated = download.copy(downloaded = 0, status = "Video deleted — torrent retained")
      persist(updated)
      _downloads.value = _downloads.value.map { if (it.id == download.id) updated else it }
    }
    return true
  }
}
