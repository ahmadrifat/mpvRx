/* SPDX-License-Identifier: AGPL-3.0-or-later */
package app.gyrolet.mpvrx.domain.download

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import app.gyrolet.mpvrx.utils.history.RecentlyPlayedOps
import app.gyrolet.mpvrx.utils.media.PlaybackStateOps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Renames completed outputs without changing their media format. */
object DownloadFileRename {
  fun displayName(context: Context, source: String): String {
    val uri = Uri.parse(source)
    if (uri.scheme != "content") return File(uri.path ?: source).name
    return context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
      if (it.moveToFirst()) it.getString(0) else null
    } ?: error("File not found")
  }

  suspend fun rename(context: Context, source: String, name: String, sidecars: List<File> = emptyList(), commit: suspend (String) -> Unit) = withContext(Dispatchers.IO) {
    val oldName = displayName(context, source)
    require(name.isNotBlank() && name !in listOf(".", "..") && name.none { it.isISOControl() || it in "/\\:*?\"<>|" }) { "Invalid file name" }
    require(name.substringAfterLast('.', "").equals(oldName.substringAfterLast('.', ""), true)) { "Keep the original file extension" }
    if (name == oldName) return@withContext
    val uri = Uri.parse(source)
    if (uri.scheme == "content") {
      val relativePath = context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.RELATIVE_PATH), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else null
      }
      val collection = uri.buildUpon().path(uri.path!!.substringBeforeLast('/')).build()
      val duplicate = context.contentResolver.query(collection, arrayOf(MediaStore.MediaColumns._ID),
        "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
        arrayOf(name, relativePath.orEmpty()), null)?.use { it.moveToFirst() } == true
      require(!duplicate) { "A file with this name already exists" }
      // MediaStore retains the same content URI after a display-name update.
      check(context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, name) }, null, null) > 0) { "Could not rename file" }
      try { commit(source) } catch (error: Throwable) {
        context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, oldName) }, null, null)
        throw error
      }
    } else {
      val old = File(uri.path ?: source)
      val target = File(old.parentFile, name)
      val moves = listOf(old to target) + sidecars.map { it to File(it.parentFile, name.substringBeforeLast('.') + it.name.removePrefix(oldName.substringBeforeLast('.'))) }
      require(moves.map { it.second.absolutePath }.distinct().size == moves.size && moves.none { it.second.exists() }) { "A file with this name already exists" }
      val moved = mutableListOf<Pair<File, File>>()
      try {
        moves.forEach { check(it.first.renameTo(it.second)) { "Could not rename file" }; moved.add(it) }
        commit(if (uri.scheme == "file") Uri.fromFile(target).toString() else target.absolutePath)
      } catch (error: Throwable) {
        moved.asReversed().forEach { check(it.second.renameTo(it.first)) { "Rename rollback failed" } }
        throw error
      }
      RecentlyPlayedOps.onVideoRenamed(old.absolutePath, target.absolutePath)
      PlaybackStateOps.onVideoRenamed(old.absolutePath, target.absolutePath)
      android.media.MediaScannerConnection.scanFile(context, arrayOf(old.absolutePath, target.absolutePath), null, null)
    }
    app.gyrolet.mpvrx.utils.media.MediaLibraryEvents.notifyChanged()
  }
}
