/* SPDX-License-Identifier: AGPL-3.0-or-later */
package app.gyrolet.mpvrx.ui.player.clip

internal object StreamExportAvailability {
  fun isStream(original: String, playable: String = original, demuxer: String? = null): Boolean {
    val network = original.startsWith("http", true) || original.startsWith("mpvrx-stalker:") || original.startsWith("rtsp:") || original.startsWith("rtmp:")
    if (!network) return false
    return original.startsWith("mpvrx-stalker:") || original.startsWith("rtsp:") || original.startsWith("rtmp:") ||
      listOf(original, playable).any { source ->
        val path = runCatching { java.net.URI(source).path.orEmpty().lowercase() }.getOrDefault("")
        path.contains("/live/") || path.substringAfterLast('.') in setOf("hls", "ts", "m3u", "m3u8", "mpd", "ism", "isml")
      } || demuxer.orEmpty().lowercase().let { it.contains("hls") || it.contains("mpegts") || it.contains("dash") }
  }
}
