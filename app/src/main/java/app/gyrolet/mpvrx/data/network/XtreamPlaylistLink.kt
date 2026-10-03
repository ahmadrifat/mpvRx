/* SPDX-License-Identifier: AGPL-3.0-or-later */
package app.gyrolet.mpvrx.data.network

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Credentials are deliberately never included in a generated diagnostic string. */
internal class XtreamPlaylistLink(val server: String, val username: String, val password: String) {
  companion object {
    fun parse(link: String): XtreamPlaylistLink? {
      val url = link.toHttpUrlOrNull() ?: return null
      if (!url.encodedPath.endsWith("/get.php")) return null
      val user = url.queryParameter("username")?.takeIf(String::isNotBlank) ?: return null
      val password = url.queryParameter("password")?.takeIf(String::isNotBlank) ?: return null
      val server = url.newBuilder().username("").password("").query(null).fragment(null)
        .encodedPath(url.encodedPath.removeSuffix("/get.php").ifEmpty { "/" })
        .build().toString().trimEnd('/')
      return XtreamPlaylistLink(server, user, password)
    }
  }
}
