/* SPDX-License-Identifier: AGPL-3.0-or-later */
package app.gyrolet.mpvrx

import android.content.Context
import android.net.Uri
import app.gyrolet.mpvrx.domain.download.*
import app.gyrolet.mpvrx.domain.torrent.*
import app.gyrolet.mpvrx.ui.player.PlaybackItem
import app.gyrolet.mpvrx.ui.player.clip.*
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.koin.core.context.GlobalContext

/** Exercises actual filesystem, MediaStore, registry persistence and libtorrent remapping. */
object DownloadRenameSmoke {
  suspend fun run(context: Context, source: File, audio: File) {
    val koin = GlobalContext.get()
    val dir = File(context.cacheDir, "rename-smoke").apply { deleteRecursively(); mkdirs() }
    val original = File(dir, "original.mp4").apply { source.copyTo(this, overwrite = true) }
    val subtitle = File(dir, "original.en.srt").apply { writeText("1\n00:00:00,000 --> 00:00:01,000\nTest") }
    var committed = ""
    DownloadFileRename.rename(context, original.path, "renamed.mp4", listOf(subtitle)) { committed = it }
    check(File(committed).readBytes().contentEquals(source.readBytes()))
    check(File(dir, "renamed.en.srt").isFile && !subtitle.exists())
    check(runCatching { DownloadFileRename.rename(context, committed, "renamed.mp3") {} }.isFailure)
    check(runCatching { DownloadFileRename.rename(context, committed, "rollback.mp4") { error("Simulated record-write failure") } }.isFailure)
    check(File(committed).isFile && !File(dir, "rollback.mp4").exists())

    val dao = koin.get<app.gyrolet.mpvrx.database.MpvRxDatabase>().downloadItemDao()
    val direct = File(dir, "direct.mp4").apply { source.copyTo(this, overwrite = true) }
    val entity = app.gyrolet.mpvrx.database.entities.DownloadItemEntity(url = "https://example.invalid/video", dirPath = dir.path, fileName = direct.name, title = "Direct", status = "SUCCESS", totalBytes = direct.length())
    val id = dao.insert(entity)
    koin.get<AppDownloadManager>().rename(AppDownload(entity.copy(id = id)), "direct-renamed.mp4")
    check(dao.findById(id)?.fileName == "direct-renamed.mp4" && File(dir, "direct-renamed.mp4").isFile)
    dao.delete(id)

    val ytdlp = File(dir, "web.mp4").apply { source.copyTo(this, overwrite = true) }
    val history = File(context.filesDir, "ytdlp-downloads.json")
    history.writeText("""[{"id":991,"url":"https://example.invalid/web","title":"Web","directory":"${dir.path}","state":"SUCCESS","outputFile":"${ytdlp.path}","artifactFiles":["${ytdlp.path}"]}]""")
    val engine = YtdlpDownloadEngine(context, koin.get())
    engine.rename(engine.jobs.value.single(), "web-renamed.mp4")
    check(YtdlpDownloadEngine(context, koin.get()).jobs.value.single().outputFile == File(dir, "web-renamed.mp4").path)

    val audioName = "audio-renamed-${System.nanoTime()}.m4a"
    val values = android.content.ContentValues().apply {
      put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "rename-smoke.m4a")
      put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "audio/mp4")
      put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Music/mpvRx")
      put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
    }
    val uri = context.contentResolver.insert(android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)!!
    try {
      context.contentResolver.openOutputStream(uri)!!.use { audio.inputStream().use { input -> input.copyTo(it) } }
      context.contentResolver.update(uri, android.content.ContentValues().apply { put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
      // Finish indexing the fixture before testing a rename. Otherwise the write-triggered
      // scanner can race the rename and restore the fixture's previous display name.
      val indexed = kotlinx.coroutines.CompletableDeferred<Unit>()
      val path = context.contentResolver.query(uri, arrayOf(android.provider.MediaStore.MediaColumns.DATA), null, null, null)!!.use { it.moveToFirst(); it.getString(0) }
      android.media.MediaScannerConnection.scanFile(context, arrayOf(path), null) { _, _ -> indexed.complete(Unit) }
      withTimeout(10_000) { indexed.await() }
      val jobId = ClipJobs.add(context, ClipRequest(PlaybackItem.fromUri(source.path), 0.0, 1.0, audioOnly = true))
      ClipJobs.update(jobId, status = "Completed", output = uri.toString())
      ClipJobs.rename(context, ClipJobs.jobs.value.first { it.id == jobId }, audioName)
      check(DownloadFileRename.displayName(context, uri.toString()) == audioName) { "Audio display name after rename: ${DownloadFileRename.displayName(context, uri.toString())}" }
      val saved = org.json.JSONArray(File(context.filesDir, "clip-jobs.json").readText())
      check((0 until saved.length()).map { saved.getJSONObject(it) }.first { it.getString("id") == jobId }.getString("output") == uri.toString())
      check(context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }.contentEquals(audio.readBytes()))
    } finally { context.contentResolver.delete(uri, null, null) }

    // A legacy entry is deliberately kept inside filesDir and must not be migrated.
    val legacy = File(context.filesDir, "offline_torrents/rename-legacy-smoke").apply { deleteRecursively(); mkdirs() }
    val old = File(legacy, "legacy.mp4").apply { source.copyTo(this, overwrite = true) }
    val bytes = old.readBytes()
    val pieces = bytes.toList().chunked(16384).flatMap { java.security.MessageDigest.getInstance("SHA-1").digest(it.toByteArray()).toList() }.toByteArray()
    val encoded = java.io.ByteArrayOutputStream().apply {
      write("d4:infod6:lengthi${bytes.size}e4:name10:legacy.mp412:piece lengthi16384e6:pieces${pieces.size}:".toByteArray())
      write(pieces); write("ee".toByteArray())
    }.toByteArray()
    val metadata = File(legacy, "source.torrent").apply { writeBytes(encoded) }
    val info = org.libtorrent4j.TorrentInfo(metadata)
    val hash = info.infoHash().toHex()
    val magnet = "magnet:?xt=urn:btih:$hash"
    val record = File(legacy, "download-0.json")
    record.writeText(JSONObject().put("id", "$hash-0").put("source", magnet).put("index", 0).put("title", "Legacy").put("relativePath", old.name).put("size", old.length()).put("downloaded", old.length()).toString())
    OfflineTorrents.initialize(context)
    val download = OfflineTorrents.find(magnet, 0) ?: error("Legacy torrent record was lost")
    check(download.complete && OfflineTorrents.directoryFor(context, magnet) == legacy)
    OfflineTorrents.rename(context, download, "legacy-renamed.mp4")
    val updated = OfflineTorrents.find(magnet, 0)!!
    check(updated.complete && updated.directory == legacy.path)
    check(JSONObject(record.readText()).getString("relativePath") == "legacy-renamed.mp4")
    val stream = TorrentStreamingEngine(context).startStream(TorrentStreamRequest(magnet, 0))
    check(Uri.parse(stream.localUrl).path == updated.path)

    // The native engine can reopen original torrent metadata with a renamed payload.
    val session = org.libtorrent4j.SessionManager()
    session.start()
    try {
      session.download(info, legacy, null, arrayOf(org.libtorrent4j.Priority.IGNORE), null, org.libtorrent4j.TorrentFlags.SEQUENTIAL_DOWNLOAD)
      val handle = withTimeout(10_000) {
        var found = session.find(info.infoHash())
        while (found == null || !found.isValid) { delay(50); found = session.find(info.infoHash()) }
        found
      }
      OfflineTorrents.restoreFileMappings(handle, legacy, info, session)
      check(File(updated.path).readBytes().contentEquals(bytes))
    } finally { session.stop() }

    val newDirectory = OfflineTorrents.directoryFor(context, "magnet:?xt=urn:btih:" + "a".repeat(40))
    check(newDirectory.parentFile.name == "Torrents")
    check(newDirectory.parentFile.parentFile == koin.get<AppDownloadManager>().locations.root())
    check(OfflineTorrents.metadataDirectory(newDirectory).path.startsWith(context.filesDir.path))
    check(OfflineTorrents.metadataDirectory(newDirectory) != newDirectory)
  }
}
