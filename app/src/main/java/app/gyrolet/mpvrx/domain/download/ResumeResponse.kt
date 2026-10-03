/* SPDX-License-Identifier: AGPL-3.0-or-later */
package app.gyrolet.mpvrx.domain.download

object ResumeResponse {
  fun validRange(offset: Long, responseCode: Int, contentRange: String?): Boolean {
    if (offset == 0L) return responseCode in 200..299
    if (responseCode != 206) return false
    val values = Regex("bytes (\\d+)-(\\d+)/(\\d+|\\*)").matchEntire(contentRange.orEmpty()) ?: return false
    val start = values.groupValues[1].toLongOrNull() ?: return false
    val end = values.groupValues[2].toLongOrNull() ?: return false
    val total = values.groupValues[3].toLongOrNull()
    return start == offset && end >= start && (total == null || end < total)
  }
}
