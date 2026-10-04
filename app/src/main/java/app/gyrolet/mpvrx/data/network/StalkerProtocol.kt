/* SPDX-License-Identifier: AGPL-3.0-or-later */
package app.gyrolet.mpvrx.data.network

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Portal commands are data, never executable shell commands. */
internal object StalkerProtocol {
  fun text(obj: JsonObject, key: String): String? =
    (obj[key] as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotEmpty() && it != "null" }

  fun rows(value: JsonElement): List<JsonObject> = when (value) {
    is JsonArray -> value.mapNotNull { it as? JsonObject }
    is JsonObject -> rows(value["data"] ?: JsonArray(emptyList()))
    else -> emptyList()
  }

  fun genres(value: JsonElement): Map<String, String> = rows(value).mapNotNull { row ->
    val id = text(row, "id") ?: text(row, "genre_id") ?: return@mapNotNull null
    val title = text(row, "title") ?: text(row, "name") ?: return@mapNotNull null
    id to title
  }.toMap()

  fun category(channel: JsonObject, genres: Map<String, String>): String? =
    (text(channel, "tv_genre_id") ?: text(channel, "genre_id"))?.let(genres::get)
      ?: text(channel, "tv_genre_name") ?: text(channel, "genre_title")

  private val commandUrl = Regex("""(?i)(https?://[^\s"']+|https?:///[^\s"']+)""")

  fun playbackUrl(command: String, endpoint: String, mac: String): String? {
    val expanded = command.replace("%mac%", mac, ignoreCase = true)
    val candidate = commandUrl.find(expanded)?.value ?: return null
    // http:///ch/... is a portal placeholder, not a stream hosted by 'ch'.
    if (candidate.startsWith("http:///", true) || candidate.startsWith("https:///", true)) return null
    val parsed = candidate.toHttpUrlOrNull() ?: return null
    val portal = endpoint.toHttpUrlOrNull() ?: return null
    if (parsed.host in setOf("localhost", "127.0.0.1", "::1", "0.0.0.0") && parsed.host != portal.host) return null
    return parsed.toString()
  }

  fun requiresLink(channel: JsonObject, endpoint: String, mac: String): Boolean {
    fun enabled(key: String) = text(channel, key) in setOf("1", "true")
    return enabled("use_http_tmp_link") || enabled("use_load_balancing") || enabled("force_ch_link_check") ||
      playbackUrl(text(channel, "cmd").orEmpty(), endpoint, mac) == null
  }

  fun referer(endpoint: String): String {
    val url = requireNotNull(endpoint.toHttpUrlOrNull())
    val root = url.encodedPath.removeSuffix("/server/load.php").removeSuffix("/portal.php").removeSuffix("/load.php").trimEnd('/')
    return url.newBuilder().encodedPath("$root/c/").query(null).build().toString()
  }
}
