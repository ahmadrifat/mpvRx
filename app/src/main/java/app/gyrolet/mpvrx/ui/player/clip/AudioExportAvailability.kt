/* SPDX-License-Identifier: AGPL-3.0-or-later */
package app.gyrolet.mpvrx.ui.player.clip

import app.gyrolet.mpvrx.domain.network.XtreamPlaybackUri

internal object AudioExportAvailability {
  fun allowed(source: String, duration: Double?, seekable: Boolean?, manifestLive: Boolean?): Boolean {
    if (duration == null || !duration.isFinite() || duration <= 0.05 || manifestLive == true) return false
    if (source.startsWith("mpvrx-stalker://") || source.startsWith("rtmp:") || source.startsWith("rtsp:")) return false
    val route = XtreamPlaybackUri.parse(source)?.route
    if (route == XtreamPlaybackUri.Route.LIVE || route == XtreamPlaybackUri.Route.BARE_LIVE) return false
    val network = source.startsWith("http://") || source.startsWith("https://")
    if (network && (seekable != true || runCatching { java.net.URI(source).path.orEmpty().contains("/live/") }.getOrDefault(false))) return false
    return true
  }
}
