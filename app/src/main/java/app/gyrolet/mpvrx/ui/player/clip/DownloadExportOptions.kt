/* SPDX-License-Identifier: AGPL-3.0-or-later */
package app.gyrolet.mpvrx.ui.player.clip

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import app.gyrolet.mpvrx.domain.download.DownloadLocations
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** Persisted with each job, rather than changing global download preferences. */
@Serializable
data class DownloadExportOptions(
  val fileName: String = "",
  val author: String = "",
  val directory: String? = null,
  val thumbnail: String? = null,
  val thumbnailCustom: Boolean = false,
  val formatSelector: String? = null,
  val sourceExtension: String = "mp4",
  val fullMedia: Boolean = false,
)

@Serializable
data class SourceVideoFormat(val label: String, val selector: String?, val extension: String)

object ExportFiles {
  suspend fun artwork(context: Context, options: DownloadExportOptions, fallback: String?): String? = try {
    thumbnail(context, options.thumbnail ?: fallback)
  } catch (error: kotlinx.coroutines.CancellationException) { throw error }
    catch (error: Exception) { if (options.thumbnailCustom) throw error else null }

  fun name(base: String, extension: String): String {
    val stem = base.trim().replace(Regex("\\.(mp4|mkv|webm|mov|ts|m4a|mp3|wav|aac)$", RegexOption.IGNORE_CASE), "")
    return "${DownloadLocations.sanitizeName(stem)}.$extension"
  }

  @Synchronized fun reserve(directory: File, name: String): File {
    check(directory.exists() || directory.mkdirs()) { "Could not create the save folder" }
    check(directory.isDirectory && directory.canWrite()) { "The save folder is not writable" }
    val stem = name.substringBeforeLast('.')
    val extension = name.substringAfterLast('.')
    var index = 1
    while (true) {
      val file = File(directory, if (index == 1) name else "$stem ($index).$extension")
      if (file.createNewFile()) return file
      index++
    }
  }

  /** Keep a private JPEG for history and optional supported cover-art embedding. */
  suspend fun thumbnail(context: Context, source: String?): String? = withContext(Dispatchers.IO) {
    if (source.isNullOrBlank()) return@withContext null
    val cached = File(source)
    if (cached.isFile && cached.parentFile == File(context.filesDir, "download-artwork")) return@withContext cached.path
    val directory = File(context.filesDir, "download-artwork").apply { mkdirs() }
    val raw = File.createTempFile("image-", ".tmp", context.cacheDir)
    var connection: HttpURLConnection? = null
    try {
      val input = when {
        source.startsWith("content://") -> context.contentResolver.openInputStream(Uri.parse(source))
        source.startsWith("http://") || source.startsWith("https://") -> {
          connection = URL(source).openConnection() as HttpURLConnection
          connection!!.connectTimeout = 10_000; connection!!.readTimeout = 10_000
          connection!!.inputStream
        }
        else -> File(Uri.parse(source).path ?: source).inputStream()
      } ?: error("Could not read thumbnail")
      input.use { stream -> raw.outputStream().use { out ->
        val buffer = ByteArray(8192); var total = 0
        while (true) {
          val count = stream.read(buffer); if (count < 0) break
          total += count; require(total <= 12 * 1024 * 1024) { "Thumbnail is too large" }
          out.write(buffer, 0, count)
        }
      } }
      val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
      BitmapFactory.decodeFile(raw.path, bounds)
      require(bounds.outWidth > 0 && bounds.outHeight > 0) { "The thumbnail is not an image" }
      val settings = BitmapFactory.Options().apply {
        inSampleSize = 1
        while (bounds.outWidth / inSampleSize > 1600 || bounds.outHeight / inSampleSize > 1600) inSampleSize *= 2
      }
      val bitmap = BitmapFactory.decodeFile(raw.path, settings) ?: error("Could not decode thumbnail")
      val target = File(directory, "${UUID.randomUUID()}.jpg")
      try { target.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) } }
      finally { bitmap.recycle() }
      target.path
    } finally { raw.delete(); connection?.disconnect() }
  }

  /** Metadata/cover remuxing never re-encodes the media tracks. */
  suspend fun decorate(context: Context, file: File, author: String, artwork: String?) {
    val extension = file.extension.lowercase()
    val cover = artwork?.let(::File)?.takeIf { it.isFile && extension in setOf("mp3", "m4a", "mp4") }
    if ((author.isBlank() && cover == null) || extension == "aac") return
    val output = File(file.parentFile, ".metadata-${UUID.randomUUID()}.$extension")
    try {
      val args = buildList {
        addAll(listOf("-hide_banner", "-nostdin", "-y", "-i", file.path))
        cover?.let { addAll(listOf("-i", it.path)) }
        addAll(listOf("-map", "0", "-c", "copy"))
        if (author.isNotBlank()) addAll(listOf("-metadata", "artist=$author", "-metadata", "author=$author"))
        if (cover != null) {
          addAll(listOf("-map", "1:v:0", "-disposition:v:${if (extension == "mp4") 1 else 0}", "attached_pic"))
          if (extension == "mp3") addAll(listOf("-id3v2_version", "3"))
        }
        add(output.path)
      }
      val result = FfmpegRuntime.run(context, args)
      check(result.first == 0 && output.length() > 0) { "Could not save the selected metadata" }
      java.nio.file.Files.move(output.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    } finally { output.delete() }
  }
}
