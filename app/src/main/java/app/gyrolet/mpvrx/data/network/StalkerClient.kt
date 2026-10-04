/* SPDX-License-Identifier: AGPL-3.0-or-later */
package app.gyrolet.mpvrx.data.network

import app.gyrolet.mpvrx.network.awaitResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder

/** A separate cookie jar per authentication session prevents mixing portal accounts. */
internal class StalkerClient(
  private val endpoint: String,
  private val mac: String,
  private val userAgent: String,
  baseClient: OkHttpClient,
) {
  private val cookies = mutableListOf<Cookie>()
  private val jar = object : CookieJar {
    override fun saveFromResponse(url: HttpUrl, received: List<Cookie>) = synchronized(cookies) {
      received.forEach { cookie ->
        cookies.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
        if (cookie.expiresAt > System.currentTimeMillis()) cookies.add(cookie)
      }
    }
    override fun loadForRequest(url: HttpUrl): List<Cookie> = synchronized(cookies) {
      cookies.removeAll { it.expiresAt <= System.currentTimeMillis() }
      cookies.filter { it.matches(url) }
    }
  }
  private val client = baseClient.newBuilder().cookieJar(CookieJar.NO_COOKIES)
    .addNetworkInterceptor { chain ->
      val outgoing = chain.request().newBuilder()
        .header("Cookie", headers("", chain.request().url.toString()).getValue("Cookie")).build()
      chain.proceed(outgoing).also { response ->
        jar.saveFromResponse(response.request.url, Cookie.parseAll(response.request.url, response.headers))
      }
    }.build()
  private val json = Json { ignoreUnknownKeys = true }

  suspend fun resolve(channel: JsonObject, token: String): String {
    var currentChannel = channel
    val storedUrl = StalkerProtocol.playbackUrl(StalkerProtocol.text(channel, "cmd").orEmpty(), endpoint, mac)?.toHttpUrlOrNull()
    // Some Xtream-backed MAG portals put expiring play_token values directly
    // into the catalog. Fetch that channel's current command for each playback.
    if (storedUrl?.queryParameter("play_token") != null && !StalkerProtocol.requiresLink(channel, endpoint, mac)) {
      val id = StalkerProtocol.text(channel, "id") ?: error("Channel ID is missing; refresh this playlist")
      currentChannel = StalkerProtocol.rows(request("itv", "get_all_channels", token))
        .firstOrNull { StalkerProtocol.text(it, "id") == id }
        ?: error("Channel is no longer available; refresh this playlist")
    }
    val command = StalkerProtocol.text(currentChannel, "cmd") ?: error("Channel has no playback command")
    if (!StalkerProtocol.requiresLink(currentChannel, endpoint, mac)) {
      return requireNotNull(StalkerProtocol.playbackUrl(command, endpoint, mac))
    }
    val result = request("itv", "create_link", token, mapOf(
      "cmd" to command, "series" to "", "forced_storage" to "undefined", "disable_ad" to "0", "download" to "0",
    ))
    val resolved = when (result) {
      is JsonObject -> {
        val errorCode = StalkerProtocol.text(result, "error")
        require(errorCode == null || errorCode in setOf("0", "false")) {
          when (errorCode) {
            "limit" -> "Portal connection limit reached"
            "access_denied" -> "Portal denied access to this channel"
            "nothing_to_play" -> "Portal has no stream for this channel"
            else -> "Portal could not create a playback link"
          }
        }
        StalkerProtocol.text(result, "cmd") ?: StalkerProtocol.text(result, "url")
      }
      is JsonPrimitive -> result.content
      else -> null
    }
    return StalkerProtocol.playbackUrl(resolved.orEmpty(), endpoint, mac)
      ?: error("Portal returned an unsupported or unresolved playback link")
  }

  fun headers(token: String, target: String = endpoint): Map<String, String> = buildMap {
    put("User-Agent", userAgent)
    put("X-User-Agent", "Model: MAG254; Link: Ethernet")
    val identity = "mac=${URLEncoder.encode(mac, Charsets.UTF_8.name())}; stb_lang=en; timezone=UTC"
    val extra = target.toHttpUrlOrNull()?.let(jar::loadForRequest).orEmpty()
      .filterNot { it.name in setOf("mac", "stb_lang", "timezone") }
      .joinToString("; ") { "${it.name}=${it.value}" }
    put("Cookie", if (extra.isEmpty()) identity else "$identity; $extra")
    put("Referer", StalkerProtocol.referer(endpoint))
    if (token.isNotBlank()) put("Authorization", "Bearer $token")
  }

  suspend fun request(type: String, action: String, token: String = "", params: Map<String, String> = emptyMap()): JsonElement = withContext(Dispatchers.IO) {
    val url = requireNotNull(endpoint.toHttpUrlOrNull()).newBuilder()
      .addQueryParameter("type", type).addQueryParameter("action", action).addQueryParameter("JsHttpRequest", "1-xml")
    params.forEach { (key, value) -> url.addQueryParameter(key, value) }
    val builder = Request.Builder().url(url.build())
    // We combine identity and session cookies ourselves. OkHttp's bridge would
    // otherwise replace Cookie with only the jar's cookies and lose the MAC.
    headers(token).forEach { (key, value) -> builder.header(key, value) }
    client.newCall(builder.build()).awaitResponse().use { response ->
        require(response.isSuccessful) { "Portal returned HTTP ${response.code}" }
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        val input = response.body.byteStream()
        while (true) {
          val count = input.read(buffer)
          if (count < 0) break
          require(output.size() + count <= 32 * 1024 * 1024) { "Portal response is too large" }
          output.write(buffer, 0, count)
        }
        val root = runCatching { json.parseToJsonElement(output.toString(Charsets.UTF_8.name())) as? JsonObject }.getOrNull()
          ?: error("Portal returned an invalid response")
        root["js"] ?: error("Portal response has no data")
      }
  }
}
