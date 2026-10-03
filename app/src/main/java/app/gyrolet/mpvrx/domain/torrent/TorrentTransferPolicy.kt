/* SPDX-License-Identifier: AGPL-3.0-or-later */
package app.gyrolet.mpvrx.domain.torrent

/** Wi-Fi availability never wakes a paused job; only a deliberate action can do so. */
object TorrentTransferPolicy {
  fun shouldPause(attached: Boolean, manualBackground: Boolean, wifi: Boolean, complete: Boolean): Boolean = !complete && !attached && !manualBackground && !wifi
}
