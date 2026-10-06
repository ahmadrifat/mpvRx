/* SPDX-License-Identifier: AGPL-3.0-or-later */
package app.gyrolet.mpvrx.ui.player.clip

internal object StreamExportAvailability {
  fun isStream(original: String, playable: String = original, demuxer: String? = null, mime: String? = null): Boolean {
    val sources = listOf(original, playable)
    val network = sources.any { source -> listOf("http://", "https://", "mpvrx-stalker:", "mpvrx-xtream:", "rtsp:", "rtmp:", "rtmps:", "udp:", "srt:").any { source.startsWith(it, true) } }
    if (!network) return false
    return sources.any { source ->
        val route = app.gyrolet.mpvrx.domain.network.XtreamPlaybackUri.parse(source)?.route
        source.startsWith("mpvrx-stalker:") || route == app.gyrolet.mpvrx.domain.network.XtreamPlaybackUri.Route.LIVE || route == app.gyrolet.mpvrx.domain.network.XtreamPlaybackUri.Route.BARE_LIVE ||
          listOf("rtsp:", "rtmp:", "rtmps:", "udp:", "srt:").any { source.startsWith(it, true) }
      } || sources.any { source ->
        val path = runCatching { java.net.URI(source).path.orEmpty().lowercase() }.getOrDefault("")
        val query = runCatching { java.net.URI(source).rawQuery.orEmpty().lowercase() }.getOrDefault("")
        path.contains("/live/") || path.endsWith("/live.php") || Regex("(?:^|&)(?:extension|format)=(?:ts|hls|m3u8|m3u|mpd)(?:&|$)").containsMatchIn(query) || path.substringAfterLast('.') in setOf("hls", "ts", "m3u", "m3u8", "mpd", "ism", "isml")
      } || demuxer.orEmpty().lowercase().let { it.contains("hls") || it.contains("mpegts") || it == "ts" || it.contains("dash") } ||
      mime.orEmpty().substringBefore(';').lowercase() in setOf("video/mp2t", "video/mpegts", "application/vnd.apple.mpegurl", "application/x-mpegurl", "audio/mpegurl", "audio/x-mpegurl", "application/dash+xml")
  }
}
