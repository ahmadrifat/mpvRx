/* SPDX-License-Identifier: AGPL-3.0-or-later */
package app.gyrolet.mpvrx.ui.player.clip

import app.gyrolet.mpvrx.data.network.StalkerPortal
import app.gyrolet.mpvrx.data.network.proxy.XtreamStreamingProxy
import app.gyrolet.mpvrx.domain.network.XtreamPlaybackUri
import app.gyrolet.mpvrx.ui.player.PlaybackItem
import java.util.UUID

/** A portal registration owned by the export, released only when that export finishes. */
internal data class ExportSource(val uri: String, val close: () -> Unit = {}) {
  companion object {
    fun portal(item: PlaybackItem): ExportSource? {
      val reference = XtreamPlaybackUri.parse(item.playableUri)
      if (reference == null && !StalkerPortal.isReference(item.playableUri)) return null
      val proxy = XtreamStreamingProxy.getInstance()
      val id = "export-${UUID.randomUUID()}"
      val uri = if (reference != null) proxy.registerStream(id, reference, item.headers, item.mimeType ?: "application/octet-stream")
        else proxy.registerStalkerStream(id, item.playableUri)
      return ExportSource(uri) { proxy.unregisterStream(id) }
    }
  }
}
