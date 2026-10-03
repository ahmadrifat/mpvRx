/* SPDX-License-Identifier: AGPL-3.0-or-later */
package app.gyrolet.mpvrx.domain.update

/** Custom release tags use v<upstream major.minor.patch>.<mod revision>. */
internal object CustomReleaseVersion {
  private val pattern = Regex("""^v?(\d+)\.(\d+)\.(\d+)\.(\d+)$""")

  private fun parts(version: String): List<Int>? {
    val match = pattern.matchEntire(version) ?: return null
    return match.groupValues.drop(1).map { it.toIntOrNull() ?: return null }
  }

  fun isNewer(remote: String, current: String): Boolean {
    val remoteParts = parts(remote) ?: return false
    val currentParts = parts(current) ?: return false
    for (i in remoteParts.indices) {
      if (remoteParts[i] != currentParts[i]) return remoteParts[i] > currentParts[i]
    }
    return false
  }
}
