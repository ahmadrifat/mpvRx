/* SPDX-License-Identifier: AGPL-3.0-or-later */
package app.gyrolet.mpvrx.data.network

import android.content.Context
import android.net.Uri
import app.gyrolet.mpvrx.network.SharedHttpClient
import app.gyrolet.mpvrx.network.awaitResponse
import app.gyrolet.mpvrx.utils.media.M3UParseResult
import app.gyrolet.mpvrx.utils.media.M3UPlaylistItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/** MAC-authenticated live-TV portals. Playback links are resolved when opened, never at import. */
object StalkerPortal {
  const val DEFAULT_USER_AGENT = "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3"
  private val client = SharedHttpClient.derive { connectTimeout(15, TimeUnit.SECONDS); readTimeout(30, TimeUnit.SECONDS); callTimeout(45, TimeUnit.SECONDS) }
  data class Account(val id: String, val name: String, val endpoint: String, val mac: String, val userAgent: String)
  data class Session(val account: Account, val token: String)
  data class Stream(val url: String, val headers: Map<String, String>)

  fun isReference(source: String) = source.startsWith("mpvrx-stalker://")
  private fun file(context: Context, id: String): File {
    require(id.matches(Regex("[a-f0-9-]{36}"))) { "Invalid portal reference" }
    return File(File(context.filesDir, "stalker").apply { mkdirs() }, "$id.json")
  }
  private fun load(context: Context, id: String): Pair<Account, JSONObject> {
    val saved = JSONObject(file(context, id).readText())
    return Account(id, saved.getString("name"), saved.getString("endpoint"), saved.getString("mac"), saved.getString("userAgent")) to saved
  }
  fun remove(context: Context, source: String) {
    if (source.startsWith("mpvrx-stalker-source://")) {
      Uri.parse(source).host?.let { id -> runCatching { file(context, id).delete() } }
    }
  }
  private fun headers(account: Account, token: String = "") = buildMap {
    put("User-Agent", account.userAgent)
    put("X-User-Agent", "Model: MAG254; Link: Ethernet")
    put("Cookie", "mac=${Uri.encode(account.mac)}; stb_lang=en; timezone=UTC")
    put("Referer", account.endpoint.substringBeforeLast('/') + "/")
    if (token.isNotBlank()) put("Authorization", "Bearer $token")
  }
  private suspend fun request(account: Account, type: String, action: String, token: String = "", params: Map<String, String> = emptyMap()): Any = withContext(Dispatchers.IO) {
    val url = account.endpoint.toHttpUrlOrNull()!!.newBuilder().addQueryParameter("type", type).addQueryParameter("action", action).addQueryParameter("JsHttpRequest", "1-xml")
    params.forEach { (key, value) -> url.addQueryParameter(key, value) }
    val builder = Request.Builder().url(url.build())
    headers(account, token).forEach { (key, value) -> builder.header(key, value) }
    client.newCall(builder.build()).awaitResponse().use { response ->
      require(response.isSuccessful) { "Portal returned HTTP ${response.code}" }
      val output = java.io.ByteArrayOutputStream()
      val input = response.body.byteStream()
      val buffer = ByteArray(8192)
      while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        require(output.size() + count <= 32 * 1024 * 1024) { "Portal response is too large" }
        output.write(buffer, 0, count)
      }
      val bytes = output.toByteArray()
      val root = runCatching { JSONObject(bytes.toString(Charsets.UTF_8)) }.getOrElse { throw IllegalArgumentException("Portal returned an invalid response") }
      root.opt("js") ?: throw IllegalArgumentException("Portal response has no data")
    }
  }
  private suspend fun authenticate(account: Account): Session {
    val handshake = request(account, "stb", "handshake", params = mapOf("token" to "")) as? JSONObject
    val token = handshake?.optString("token")?.takeIf { it.isNotBlank() } ?: error("Portal rejected this MAC address")
    request(account, "stb", "get_profile", token, mapOf("stb_type" to "MAG254", "client_type" to "STB", "hd" to "1", "video_out" to "hdmi", "ver" to "ImageDescription: 0.2.18-r23-254; PORTAL version: 5.6.1; API Version: JS API version: 343; STB API version: 146; Player Engine version: 0x58c"))
    return Session(account, token)
  }
  suspend fun create(context: Context, name: String, portal: String, mac: String, userAgent: String): Pair<String, M3UParseResult.Success> = withContext(Dispatchers.IO) {
    require(name.isNotBlank()) { "Playlist name is required" }
    require(mac.matches(Regex("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}"))) { "Enter a MAC address such as 00:1A:79:12:34:56" }
    val parsed = portal.trim().toHttpUrlOrNull() ?: error("Invalid portal URL")
    require(parsed.query == null && parsed.username.isEmpty() && parsed.password.isEmpty()) { "Enter a portal URL without credentials or query parameters" }
    val path = parsed.encodedPath.trimEnd('/').removeSuffix("/c").removeSuffix("/client/index.html")
    val base = parsed.newBuilder().encodedPath(path.ifEmpty { "/" }).build().toString().trimEnd('/')
    val endpoints = if (path.endsWith(".php")) listOf(base) else listOf("$base/server/load.php", "$base/portal.php")
    val id = UUID.randomUUID().toString()
    var failure: Exception? = null
    for (endpoint in endpoints) {
      val account = Account(id, name.trim(), endpoint, mac.uppercase(), userAgent.trim().ifEmpty { DEFAULT_USER_AGENT })
      try {
        val catalog = catalog(context, authenticate(account))
        return@withContext "mpvrx-stalker-source://$id" to catalog
      } catch (error: kotlinx.coroutines.CancellationException) { throw error } catch (error: Exception) { failure = error }
    }
    throw failure ?: IllegalArgumentException("Portal is unavailable")
  }
  suspend fun refresh(context: Context, source: String): M3UParseResult.Success = withContext(Dispatchers.IO) {
    val account = load(context, Uri.parse(source).host ?: error("Missing portal reference")).first
    catalog(context, authenticate(account))
  }
  suspend fun check(context: Context, name: String, portal: String, mac: String, userAgent: String): String {
    val (source, catalog) = create(context, name, portal, mac, userAgent)
    val id = Uri.parse(source).host!!
    return try {
      val account = load(context, id).first
      val session = authenticate(account)
      val details = try { request(account, "account_info", "get_main_info", session.token) as? JSONObject } catch (error: kotlinx.coroutines.CancellationException) { throw error } catch (_: Exception) { null }
      val expiry = details?.optString("end_date")?.takeIf { it.isNotBlank() } ?: details?.optString("expire_date")?.takeIf { it.isNotBlank() } ?: "Not supplied by portal"
      "Portal accessible: ${catalog.items.size} live channels\nExpiry: $expiry"
    } finally { withContext(Dispatchers.IO) { file(context, id).delete() } }
  }
  private suspend fun catalog(context: Context, session: Session): M3UParseResult.Success {
    val account = session.account
    val result = request(account, "itv", "get_all_channels", session.token)
    val channels = result as? JSONArray ?: (result as? JSONObject)?.optJSONArray("data") ?: error("Portal did not provide live channels")
    require(channels.length() in 1..100_000) { "Portal has no available live channels, or requires additional device credentials" }
    val commands = JSONObject()
    val items = (0 until channels.length()).mapNotNull { index ->
      val channel = channels.optJSONObject(index) ?: return@mapNotNull null
      val id = channel.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
      val cmd = channel.optString("cmd").takeIf { it.isNotBlank() } ?: return@mapNotNull null
      commands.put(id, cmd)
      M3UPlaylistItem("mpvrx-stalker://${account.id}/${Uri.encode(id)}", channel.optString("name", "Channel $id"), tvgLogo = channel.optString("logo").takeIf { it.isNotBlank() }, groupTitle = channel.optString("tv_genre_id"))
    }
    require(items.isNotEmpty()) { "Portal has no playable channels" }
    val saved = JSONObject().put("name", account.name).put("endpoint", account.endpoint).put("mac", account.mac).put("userAgent", account.userAgent).put("commands", commands)
    file(context, account.id).writeText(saved.toString())
    return M3UParseResult.Success(account.name, items)
  }
  suspend fun resolve(context: Context, reference: String): Result<Stream> = withContext(Dispatchers.IO) {
    try {
      val uri = Uri.parse(reference)
      val (account, saved) = load(context, uri.host ?: error("Missing portal reference"))
      val session = authenticate(account)
      val command = saved.getJSONObject("commands").getString(uri.lastPathSegment ?: error("Missing channel"))
      val result = request(account, "itv", "create_link", session.token, mapOf("cmd" to command, "series" to "", "forced_storage" to "0", "disable_ad" to "0", "download" to "0")) as? JSONObject ?: error("Portal could not create a playback link")
      val link = result.optString("cmd").trim().removePrefix("ffmpeg ").removePrefix("ffrt ").trim()
      val url = link.toHttpUrlOrNull() ?: error("Portal returned an unsupported playback link")
      Result.success(Stream(url.toString(), headers(account, session.token)))
    } catch (error: kotlinx.coroutines.CancellationException) { throw error } catch (error: Exception) { Result.failure(error) }
  }
}
