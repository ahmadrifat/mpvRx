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
  data class Download(val id: String, val source: String, val index: Int, val title: String, val directory: String, val path: String, val size: Long, val downloaded: Long = 0, val status: String = "Paused", val manualBackground: Boolean = false, val speedBytesPerSec: Long = 0) {
    val complete get() = downloaded >= size && size > 0 && File(path).isFile && File(path).length() == size
    val progress get() = if (size > 0) (downloaded.toFloat() / size).coerceIn(0f, 1f) else 0f
  }
  private data class Native(val session: SessionManager, val handle: TorrentHandle, var attached: Boolean = true, var paused: Boolean = false)
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val natives = mutableMapOf<String, Native>()
  private val _downloads = MutableStateFlow<List<Download>>(emptyList())
  val downloads = _downloads.asStateFlow()
  private val metadataDirectories = mutableMapOf<String, File>()
  private var root: File? = null
  private var monitor: Job? = null
  private var ticks = 0
  private var application: Context? = null
  private var connection: android.net.ConnectivityManager? = null
  private val resuming = mutableSetOf<String>()
  private fun wifi(): Boolean {
    val cm = connection ?: return false
    val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
    return caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
  }
  @Synchronized private fun enforceNetwork() {
    if (!wifi()) _downloads.value.filter { TorrentTransferPolicy.shouldPause(natives[it.id]?.attached != false, it.manualBackground, false, it.complete) }.forEach { pause(it.id, "Paused — Wi-Fi required") }
  }
  @Synchronized fun pause(id: String, reason: String = "Paused") {
    natives[id]?.let { it.handle.saveResumeData(TorrentHandle.SAVE_INFO_DICT); it.handle.pause(); it.paused = true }
    _downloads.value = _downloads.value.map { if (it.id == id && !it.complete) it.copy(status = reason, manualBackground = false, speedBytesPerSec = 0).also(::persist) else it }
  }
  @Synchronized fun find(source: String, index: Int?): Download? {
    val hash = canonicalInfoHash(source)
    return _downloads.value.firstOrNull { (it.source == source || (hash != null && it.id.substringBefore('-').equals(hash, true))) && (index == null || it.index == index) }
  }
  fun deleteByHash(hash: String, index: Int? = null): Boolean = synchronized(this) {
    val selected = _downloads.value.filter { it.id.substringBefore('-').equals(hash, true) && (index == null || it.index == index) }
    if (selected.any { chosen -> _downloads.value.any { it.id != chosen.id && it.directory == chosen.directory && natives[it.id]?.attached == true } }) return@synchronized false
    selected.all { remove(it, true) }
  }

  @Synchronized
  fun initialize(context: Context) {
    if (root != null) return
    application = context.applicationContext
    connection = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
    connection!!.registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
      override fun onCapabilitiesChanged(network: android.net.Network, caps: android.net.NetworkCapabilities) { enforceNetwork() }
      override fun onLost(network: android.net.Network) { enforceNetwork() }
    })
    root = File(context.filesDir, "offline_torrents").apply { mkdirs() }
    _downloads.value = root!!.listFiles().orEmpty().filter { it.isDirectory }.flatMap { directory -> directory.listFiles().orEmpty().filter { it.name.startsWith("download-") && it.extension == "json" } }.mapNotNull { record ->
      runCatching {
        val control = record.parentFile!!
        val directory = runCatching { File(JSONObject(File(control, "directory.json").readText()).getString("path")) }.getOrDefault(control)
        metadataDirectories[directory.absolutePath] = control
        val json = JSONObject(record.readText())
        val relative = json.getString("relativePath")
        val media = File(directory, relative).canonicalFile
        require(media.path.startsWith(directory.canonicalPath + File.separator))
        Download(json.getString("id"), json.getString("source"), json.getInt("index"), json.getString("title"), directory.absolutePath, media.absolutePath, json.getLong("size"), json.optLong("downloaded"), if (json.optString("status").startsWith("Video deleted")) "Video deleted — torrent retained" else "Paused", json.optBoolean("manualBackground"))
      }.getOrNull()
    }
  }
  @Synchronized
  fun directoryFor(context: Context, source: String): File {
    initialize(context)
    val previous = _downloads.value.firstOrNull { it.source == source || it.id.substringBefore('-') == canonicalInfoHash(source) || android.net.Uri.parse(source).path == File(metadataDirectory(File(it.directory)), "source.torrent").absolutePath }
    if (previous != null) {
      val related = _downloads.value.filter { it.directory == previous.directory }
      require(related.none { natives[it.id]?.attached == true }) { "This torrent is already playing" }
      related.forEach { natives.remove(it.id)?.session?.stop() }
      _downloads.value = _downloads.value.map { if (it.directory == previous.directory && !it.complete) it.copy(status = "Paused") else it }
      return File(previous.directory)
    }
    val token = java.util.UUID.randomUUID().toString()
    val control = File(root, token).apply { check(mkdirs()) }
    val locations = org.koin.core.context.GlobalContext.get().get<app.gyrolet.mpvrx.domain.download.AppDownloadManager>().locations
    val directory = File(File(locations.root(), "Torrents"), token).apply { check(exists() || mkdirs()) }
    File(control, "directory.json").writeText(JSONObject().put("path", directory.absolutePath).toString())
    metadataDirectories[directory.absolutePath] = control
    return directory
  }
  @Synchronized
  fun attach(context: Context, result: TorrentStreamResult, directory: File, session: SessionManager, handle: TorrentHandle): String {
    initialize(context)
    val id = "${result.infoHash}-${result.selectedFile.index}"
    require(natives[id] == null) { "This torrent is already downloading" }
    val previous = _downloads.value.firstOrNull { it.id == id }
    val download = Download(id, result.source, result.selectedFile.index, result.selectedFile.name, directory.absolutePath, File(directory, result.selectedFile.path).absolutePath, result.selectedFile.size, downloaded = previous?.downloaded ?: 0, status = "Downloading", manualBackground = previous?.manualBackground ?: false)
    _downloads.value = _downloads.value.filterNot { it.id == id } + download
    natives[id] = Native(session, handle)
    session.addListener(object : org.libtorrent4j.AlertListener {
      override fun types() = intArrayOf(org.libtorrent4j.alerts.AlertType.SAVE_RESUME_DATA.swig())
      override fun alert(alert: org.libtorrent4j.alerts.Alert<*>) {
        if (alert is org.libtorrent4j.alerts.SaveResumeDataAlert) {
          runCatching {
            val params = alert.params()
            File(metadataDirectory(directory), "resume.dat").writeBytes(org.libtorrent4j.AddTorrentParams.writeResumeDataBuf(params))
            File(metadataDirectory(directory), "source.torrent").writeBytes(org.libtorrent4j.Entry(org.libtorrent4j.swig.libtorrent.write_torrent_file(params.swig())).bencode())
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
  fun detach(id: String) { natives[id]?.attached = false; enforceNetwork() }
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
      if (native.paused) return@map download
      try {
        val bytes = native.handle.fileProgress(TorrentHandle.PIECE_GRANULARITY).getOrNull(download.index) ?: 0L
        val done = bytes >= download.size && download.size > 0
        val updated = download.copy(downloaded = bytes, status = if (done) "Downloaded" else "Downloading", speedBytesPerSec = if (done) 0 else native.handle.status().downloadPayloadRate().toLong())
        if (done && !download.complete) application?.let { app.gyrolet.mpvrx.domain.download.AppDownloadManager.notifyCompletedMedia(it, File(updated.path)) }
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
    val json = JSONObject().put("id", download.id).put("source", download.source).put("index", download.index).put("title", download.title).put("relativePath", File(download.path).canonicalFile.relativeTo(directory.canonicalFile).path).put("size", download.size).put("downloaded", download.downloaded).put("manualBackground", download.manualBackground).put("status", download.status)
    val target = File(metadataDirectory(directory), "download-${download.index}.json")
    val temporary = File(metadataDirectory(directory), "download-${download.index}.json.tmp")
    temporary.writeText(json.toString())
    java.nio.file.Files.move(temporary.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
  }
  @Synchronized fun metadataDirectory(directory: File): File = metadataDirectories[directory.absolutePath] ?: directory

  suspend fun rename(context: Context, download: Download, name: String) {
    synchronized(this) {
      require(download.complete) { "Wait for the download to finish" }
      require(_downloads.value.none { it.directory == download.directory && natives[it.id]?.attached == true }) { "Stop torrent playback before renaming" }
      _downloads.value.filter { it.directory == download.directory }.forEach { natives.remove(it.id)?.session?.stop() }
    }
    app.gyrolet.mpvrx.domain.download.DownloadFileRename.rename(context, download.path, name) { path ->
      synchronized(this) {
        val updated = download.copy(path = path, title = name.substringBeforeLast('.'))
        // Rebuild storage remappings from persisted records if this torrent is opened again.
        File(metadataDirectory(File(download.directory)), "resume.dat").delete()
        persist(updated)
        _downloads.value = _downloads.value.map { if (it.id == download.id) updated else it }
      }
    }
  }

  suspend fun restoreFileMappings(handle: TorrentHandle, directory: File, info: org.libtorrent4j.TorrentInfo, session: SessionManager) {
    val renamed = synchronized(this) { _downloads.value.filter { it.directory == directory.absolutePath && info.files().filePath(it.index) != File(it.path).canonicalFile.relativeTo(directory.canonicalFile).path.replace('\\', '/') } }
    for (download in renamed) {
      val completion = CompletableDeferred<Unit>()
      val listener = object : org.libtorrent4j.AlertListener {
        override fun types() = intArrayOf(org.libtorrent4j.alerts.AlertType.FILE_RENAMED.swig(), org.libtorrent4j.alerts.AlertType.FILE_RENAME_FAILED.swig())
        override fun alert(alert: org.libtorrent4j.alerts.Alert<*>) {
          if (alert is org.libtorrent4j.alerts.FileRenamedAlert && alert.index() == download.index) completion.complete(Unit)
          if (alert is org.libtorrent4j.alerts.FileRenameFailedAlert && alert.getIndex() == download.index) completion.completeExceptionally(IllegalStateException("Could not restore torrent filename"))
        }
      }
      session.addListener(listener)
      try {
        handle.renameFile(download.index, File(download.path).canonicalFile.relativeTo(directory.canonicalFile).path.replace('\\', '/'))
        withTimeout(10_000) { completion.await() }
      } finally { session.removeListener(listener) }
    }
  }
  fun resume(context: Context, download: Download) {
    synchronized(this) {
      if (download.id in resuming) return
      val native = natives[download.id]
      _downloads.value = _downloads.value.map { if (it.id == download.id) it.copy(manualBackground = true, status = "Downloading").also(::persist) else it }
      if (native != null) { native.paused = false; native.handle.resume(); TorrentDownloadService.start(context); return }
      resuming.add(download.id)
    }
    scope.launch {
      val engine = TorrentStreamingEngine(context)
      try {
        val metadata = File(metadataDirectory(File(download.directory)), "source.torrent")
        val source = if (metadata.isFile) android.net.Uri.fromFile(metadata).toString() else download.source
        engine.startStream(TorrentStreamRequest(source, download.index))
        engine.stopStream()
      } catch (error: Exception) {
        synchronized(this@OfflineTorrents) { _downloads.value = _downloads.value.map { if (it.id == download.id) it.copy(status = "Resume failed") else it } }
      } finally { synchronized(this@OfflineTorrents) { resuming.remove(download.id) } }
    }
  }
  @Synchronized
  fun remove(download: Download, deleteMetadata: Boolean): Boolean {
    if (_downloads.value.any { it.id != download.id && it.directory == download.directory && natives[it.id]?.attached == true }) return false
    natives.remove(download.id)?.session?.stop()
    val directory = File(download.directory).canonicalFile
    require(metadataDirectory(directory).canonicalPath.startsWith(root!!.canonicalPath + File.separator))
    if (deleteMetadata) {
      val others = _downloads.value.filter { it.directory == download.directory && it.id != download.id }
      if (others.isEmpty()) {
        if (directory.exists() && !directory.deleteRecursively()) return false
        val control = metadataDirectory(directory)
        if (control != directory && control.exists() && !control.deleteRecursively()) return false
        metadataDirectories.remove(directory.absolutePath)
      } else {
        val media = File(download.path).canonicalFile
        require(media.path.startsWith(directory.path + File.separator))
        if (media.exists() && !media.delete()) return false
        File(metadataDirectory(directory), "download-${download.index}.json").delete()
        File(metadataDirectory(directory), "resume.dat").delete()
      }
      _downloads.value = _downloads.value.filterNot { it.id == download.id }
    } else {
      val media = File(download.path).canonicalFile
      require(media.path.startsWith(directory.path + File.separator))
      if (media.exists() && !media.delete()) return false
      File(metadataDirectory(directory), "resume.dat").delete()
      val updated = download.copy(downloaded = 0, status = "Video deleted — torrent retained")
      persist(updated)
      _downloads.value = _downloads.value.map { if (it.id == download.id) updated else it }
    }
    return true
  }
}
