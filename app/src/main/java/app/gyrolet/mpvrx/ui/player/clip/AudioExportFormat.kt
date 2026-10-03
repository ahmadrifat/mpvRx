/* SPDX-License-Identifier: AGPL-3.0-or-later */
package app.gyrolet.mpvrx.ui.player.clip

enum class AudioExportFormat(val extension: String, val mimeType: String, val encoder: String) {
  M4A("m4a", "audio/mp4", "aac"),
  MP3("mp3", "audio/mpeg", "libmp3lame"),
  WAV("wav", "audio/wav", "pcm_s16le"),
  AAC("aac", "audio/aac", "aac");

  companion object {
    fun fromStored(value: String?): AudioExportFormat = entries.firstOrNull { it.name == value } ?: M4A
  }
}
