/* SPDX-License-Identifier: AGPL-3.0-or-later */
package app.gyrolet.mpvrx.preferences

internal object PlayerControlLayout {
  /** Older saved layouts may omit Record even after the one-time migration. */
  fun withRecordingCompanion(groups: List<List<PlayerButton>>): List<List<PlayerButton>> {
    if (groups.flatten().contains(PlayerButton.RECORD)) return groups
    var inserted = false
    return groups.map { buttons -> buttons.flatMap { button ->
      if (!inserted && button == PlayerButton.DOWNLOAD) { inserted = true; listOf(button, PlayerButton.RECORD) } else listOf(button)
    } }
  }
}
