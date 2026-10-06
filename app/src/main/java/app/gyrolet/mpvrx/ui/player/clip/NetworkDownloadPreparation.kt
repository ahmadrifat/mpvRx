/* SPDX-License-Identifier: AGPL-3.0-or-later */
package app.gyrolet.mpvrx.ui.player.clip

import android.content.Context
import app.gyrolet.mpvrx.domain.download.YtdlpDownloadEngine
import app.gyrolet.mpvrx.network.SharedHttpClient
import app.gyrolet.mpvrx.network.awaitResponse
import app.gyrolet.mpvrx.ui.player.PlaybackItem
import app.gyrolet.mpvrx.ui.player.ytdlp.YtdlpManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Transport evidence is separate from whether the timeline has a known end. */
internal object NetworkMediaFacts {
  fun transport(mime: String?, bytes: ByteArray): String? {
    val text = bytes.toString(Charsets.UTF_8).trimStart('\uFEFF', ' ', '\r', '\n')
    if (text.startsWith("#EXTM3U")) return "hls"
    if (bytes.size >= 564 && (0 until 188).any { offset ->
        (offset until bytes.size step 188).take(8).all { bytes[it].toInt() and 255 == 0x47 }
      }) return "mpegts"
    return when (mime.orEmpty().substringBefore(';').trim().lowercase()) {
      "video/mp2t", "video/mpegts" -> "mpegts"
      "application/vnd.apple.mpegurl", "application/x-mpegurl", "audio/mpegurl", "audio/x-mpegurl" -> "hls"
      "application/dash+xml" -> "dash"
      else -> null
    }
  }
  fun hlsLive(text: String): Boolean? = when {
    !text.contains("#EXT-X-TARGETDURATION:") && !text.contains("#EXT-X-ENDLIST") -> null // master or IPTV channel list
    text.contains("#EXT-X-ENDLIST") -> false
    else -> true
  }
  fun hlsDuration(text: String): Double = Regex("#EXTINF:([0-9.]+)").findAll(text).sumOf { it.groupValues[1].toDoubleOrNull() ?: 0.0 }
}

internal data class PreparedNetworkDownload(
  val item: PlaybackItem,
  val duration: Double?,
  val live: Boolean?,
  val recordable: Boolean,
  val formats: List<SourceVideoFormat>,
  val options: DownloadExportOptions,
)

/** Reads a bounded sample/manifest and metadata; never creates a playback session. */
internal object NetworkDownloadPreparation {
  private val http = SharedHttpClient.derive {
    connectTimeout(12, TimeUnit.SECONDS); readTimeout(12, TimeUnit.SECONDS); callTimeout(20, TimeUnit.SECONDS)
  }
  suspend fun prepare(context: Context, item: PlaybackItem, engine: YtdlpDownloadEngine): PreparedNetworkDownload = withTimeout(60_000) {
    withContext(Dispatchers.IO) {
      // requiresYtdlp is a URL heuristic. Opaque IPTV addresses also match it, so inspect
      // the response before treating an extensionless address as an extractor webpage.
      if (YtdlpManager.requiresYtdlp(item.originalUri) && isWebPage(item)) {
        val info = engine.inspectSource(item.originalUri, item.headers)
        val finite = info.live != true && info.duration != null
        require(finite || info.live == true) { "The source has not supplied a usable timeline" }
        val enriched = item.copy(title = info.title ?: item.title, artist = info.author, artworkUri = info.thumbnail,
          videoWidth = info.width, videoHeight = info.height)
        val format = info.formats.firstOrNull() ?: SourceVideoFormat("Source", null, "mp4")
        PreparedNetworkDownload(enriched, if (finite) info.duration else null, info.live, info.live == true,
          info.formats.ifEmpty { listOf(format) }, options(enriched, format))
      } else {
        val portal = ExportSource.portal(item)
        try {
          var source = portal?.uri ?: item.playableUri
          var transport: String? = null
          var live: Boolean? = null
          var duration: Double? = null
          var mime: String? = item.mimeType
          if (source.startsWith("http", true)) {
            // Follow redirects and a bounded number of HLS master playlists.
            for (level in 0..3) {
              val builder = Request.Builder().url(source)
              item.headers.forEach { (key, value) -> builder.header(key, value) }
              val next = http.newCall(builder.build()).awaitResponse().use { response ->
                require(response.isSuccessful) { "The source could not be opened" }
                val finalUrl = response.request.url
                mime = response.header("Content-Type")
                val input = response.body.byteStream()
                val bytes = if (mime.orEmpty().contains("mpegurl", true)) readSample(input, 128 * 1024) else readSample(input, 2048)
                transport = NetworkMediaFacts.transport(mime, bytes)
                if (transport == "hls") {
                  // A small first sample may have an octet-stream MIME. Read the rest of that manifest only.
                  val complete = if (bytes.size == 2048) bytes + readSample(input, 128 * 1024 - 2048) else bytes
                  val text = complete.toString(Charsets.UTF_8)
                  require(complete.size < 128 * 1024) { "The source playlist is too large to inspect" }
                  live = NetworkMediaFacts.hlsLive(text)
                  if (live == false) duration = NetworkMediaFacts.hlsDuration(text).takeIf { it > .05 }
                  if (live == null) {
                    val lines = text.lines()
                    val child = lines.indices.firstOrNull { lines[it].startsWith("#EXT-X-STREAM-INF:") }
                      ?.let { index -> lines.drop(index + 1).firstOrNull { it.isNotBlank() && !it.startsWith('#') } }
                    require(child != null) { "Select a channel from this playlist before downloading" }
                    finalUrl.resolve(child)?.toString()
                  } else null
                } else null
              }
              if (next == null) break
              require(level < 3) { "The source playlist could not be resolved" }
              source = next
            }
          }
          val providerLive = listOf(item.originalUri, item.playableUri).any {
            it.startsWith("mpvrx-stalker:") || app.gyrolet.mpvrx.domain.network.XtreamPlaybackUri.parse(it)?.route in
              setOf(app.gyrolet.mpvrx.domain.network.XtreamPlaybackUri.Route.LIVE, app.gyrolet.mpvrx.domain.network.XtreamPlaybackUri.Route.BARE_LIVE)
          }
          val recordable = providerLive || transport != null || StreamExportAvailability.isStream(item.originalUri, item.playableUri, mime = mime)
          if (providerLive) live = true
          // Never mistake MPEG-TS cache duration for an end time. Unknown streaming sources can be recorded.
          var width = 0; var height = 0
          if (!recordable || live == false) {
            val args = buildList {
              addAll(listOf("-v", "error", "-rw_timeout", "12000000", "-analyzeduration", "3000000", "-probesize", "2000000"))
              if (transport == "hls") addAll(FfmpegRuntime.remoteHlsOptions)
              if (item.headers.isNotEmpty()) addAll(listOf("-headers", item.headers.entries.joinToString("") { "${it.key}: ${it.value}\r\n" }))
              addAll(listOf("-show_entries", "format=duration,format_name:stream=codec_type,width,height", "-of", "json", source))
            }
            val result = withTimeout(20_000) { FfmpegRuntime.run(context, args, probe = true) }
            require(result.first == 0) { "Could not collect media information" }
            val json = JSONObject(result.second)
            duration = duration ?: json.optJSONObject("format")?.optString("duration")?.toDoubleOrNull()?.takeIf { it.isFinite() && it > .05 }
            val tracks = json.optJSONArray("streams")
            for (i in 0 until (tracks?.length() ?: 0)) {
              val track = tracks!!.getJSONObject(i)
              if (track.optString("codec_type") == "video") { width = track.optInt("width"); height = track.optInt("height"); break }
            }
          }
          require(recordable || duration != null) { "The source has not supplied a usable timeline" }
          val ext = if (transport == "hls" || transport == "mpegts") "ts" else
            item.playableUri.substringBefore('?').substringAfterLast('.', "mp4").lowercase().takeIf { it in setOf("mp4", "mkv", "webm", "mov", "avi", "ts") } ?: "mp4"
          val enriched = item.copy(mimeType = mime, videoWidth = width, videoHeight = height)
          val format = SourceVideoFormat("${ext.uppercase()} · ${if (height > 0) "${height}p" else "Source"}", null, ext)
          PreparedNetworkDownload(enriched, duration, live, recordable, listOf(format), options(enriched, format))
        } finally { portal?.close?.invoke() }
      }
    }
  }
  private fun readSample(input: java.io.InputStream, maximum: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (output.size() < maximum) {
      val count = input.read(buffer, 0, minOf(buffer.size, maximum - output.size()))
      if (count < 0) break
      output.write(buffer, 0, count)
    }
    return output.toByteArray()
  }
  private suspend fun isWebPage(item: PlaybackItem): Boolean {
    val builder = Request.Builder().url(item.playableUri)
    item.headers.forEach { (key, value) -> builder.header(key, value) }
    return http.newCall(builder.build()).awaitResponse().use { response ->
      require(response.isSuccessful) { "The source could not be opened" }
      val mime = response.header("Content-Type").orEmpty().lowercase()
      val sample = readSample(response.body.byteStream(), 2048)
      if (NetworkMediaFacts.transport(mime, sample) != null || mime.startsWith("video/") || mime.startsWith("audio/") || mime.startsWith("application/octet-stream")) false
      else mime.contains("html") || sample.toString(Charsets.UTF_8).trimStart().startsWith('<')
    }
  }
  private fun options(item: PlaybackItem, format: SourceVideoFormat) = DownloadExportOptions(
    fileName = item.title ?: "Download", author = ExportFiles.author(item.artist), thumbnail = item.artworkUri,
    sourceThumbnail = item.artworkUri, sourceExtension = format.extension, formatSelector = format.selector,
  )
}
