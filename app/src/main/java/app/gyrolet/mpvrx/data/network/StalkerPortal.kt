/* SPDX-License-Identifier: AGPL-3.0-or-later */
package app.gyrolet.mpvrx.data.network

import android.content.Context
import android.net.Uri
import app.gyrolet.mpvrx.network.SharedHttpClient
import app.gyrolet.mpvrx.utils.media.M3UParseResult
import app.gyrolet.mpvrx.utils.media.M3UPlaylistItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/** MAC-authenticated live-TV portals. Playback links are resolved when opened, never at import. */
object StalkerPortal {
  const val DEFAULT_USER_AGENT = "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3"
  private val client = SharedHttpClient.derive { connectTimeout(15, TimeUnit.SECONDS); readTimeout(30, TimeUnit.SECONDS); callTimeout(45, TimeUnit.SECONDS) }
  data class Account(val id: String, val name: String, val endpoint: String, val mac: String, val userAgent: String)
  private data class Session(val account: Account, val token: String, val connection: StalkerClient)
  data class Stream(val url: String, val headers: Map<String, String>)

  fun configuration(context: Context, source: String): Account = load(context, Uri.parse(source).host ?: error("Portal configuration missing")).first
  suspend fun information(context: Context, source: String): String {
    val account = configuration(context, source)
    val session = authenticate(account)
    val details = try { request(session, "account_info", "get_main_info") as? JSONObject } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
    fun supplied(vararg names: String): String = names.firstNotNullOfOrNull { details?.optString(it)?.takeIf { v -> v.isNotBlank() && v != "null" } } ?: "Not provided"
    return "Portal access: Authenticated\nExpiry: ${supplied("end_date", "expire_date")}\nActive connections: ${supplied("active_cons")}\nConnection limit: ${supplied("max_connections")}"
  }
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
  private suspend fun request(session: Session, type: String, action: String, params: Map<String, String> = emptyMap()): Any {
    val value = session.connection.request(type, action, session.token, params)
    return when (value) {
      is JsonObject -> JSONObject(value.toString())
      is JsonArray -> JSONArray(value.toString())
      is JsonPrimitive -> value.content
      else -> error("Portal response has no data")
    }
  }
  private suspend fun authenticate(account: Account): Session {
    val connection = StalkerClient(account.endpoint, account.mac, account.userAgent, client)
    val handshake = connection.request("stb", "handshake", params = mapOf("token" to "")) as? JsonObject
    val token = handshake?.let { StalkerProtocol.text(it, "token") } ?: error("Portal rejected this MAC address")
    val session = Session(account, token, connection)
    val profile = request(session, "stb", "get_profile", mapOf("stb_type" to "MAG254", "client_type" to "STB", "hd" to "1", "video_out" to "hdmi", "ver" to "ImageDescription: 0.2.18-r23-254; PORTAL version: 5.6.1; API Version: JS API version: 343; STB API version: 146; Player Engine version: 0x58c"))
    require(profile is JSONObject) { "Portal rejected this MAC address" }
    require(profile.optInt("blocked", 0) != 1 && !profile.optBoolean("blocked", false)) { "This MAG account is blocked" }
    return session
  }
  suspend fun create(context: Context, name: String, portal: String, mac: String, userAgent: String): Pair<String, M3UParseResult.Success> = withContext(Dispatchers.IO) {
    require(name.isNotBlank()) { "Playlist name is required" }
    require(mac.matches(Regex("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}"))) { "Enter a MAC address such as 00:1A:79:12:34:56" }
    val parsed = portal.trim().toHttpUrlOrNull() ?: error("Invalid portal URL")
    require(parsed.query == null && parsed.username.isEmpty() && parsed.password.isEmpty()) { "Enter a portal URL without credentials or query parameters" }
    val path = parsed.encodedPath.trimEnd('/').removeSuffix("/c/index.html").removeSuffix("/client/index.html").removeSuffix("/c")
    val base = parsed.newBuilder().encodedPath(path.ifEmpty { "/" }).build().toString().trimEnd('/')
    val endpoints = if (path.endsWith(".php")) listOf(base) else listOf("$base/server/load.php", "$base/portal.php")
    val id = UUID.randomUUID().toString()
    var failure: Exception? = null
    for (endpoint in endpoints) {
      val account = Account(id, name.trim(), endpoint, mac.uppercase(), userAgent.trim().ifEmpty { DEFAULT_USER_AGENT })
      try {
        val session = authenticate(account)
        val info = try { request(session, "account_info", "get_main_info") as? JSONObject } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
        val expiryText = info?.optString("end_date")?.takeIf { it.isNotBlank() } ?: info?.optString("expire_date")
        val expiry = expiryText?.toLongOrNull()?.takeIf { it > 0 }?.times(1000) ?: expiryText?.let { text ->
          listOf("yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd", "dd.MM.yyyy", "MMM d, yyyy").firstNotNullOfOrNull { pattern ->
            runCatching { java.text.SimpleDateFormat(pattern, java.util.Locale.US).apply { isLenient = false }.parse(text)?.time }.getOrNull()
          }
        }
        require(expiry == null || expiry > System.currentTimeMillis()) { "This MAG account has expired" }
        val catalog = catalog(context, session)
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
      val details = try { request(session, "account_info", "get_main_info") as? JSONObject } catch (error: kotlinx.coroutines.CancellationException) { throw error } catch (_: Exception) { null }
      val expiry = details?.optString("end_date")?.takeIf { it.isNotBlank() } ?: details?.optString("expire_date")?.takeIf { it.isNotBlank() } ?: "Not supplied by portal"
      "Portal accessible: ${catalog.items.size} live channels\nExpiry: $expiry"
    } finally { withContext(Dispatchers.IO) { file(context, id).delete() } }
  }
  private suspend fun catalog(context: Context, session: Session): M3UParseResult.Success {
    val account = session.account
    val genreResult = try { session.connection.request("itv", "get_genres", session.token) }
      catch (e: kotlinx.coroutines.CancellationException) { throw e }
      catch (_: Exception) { JsonArray(emptyList()) }
    val genres = StalkerProtocol.genres(genreResult)
    val result = request(session, "itv", "get_all_channels")
    val channels = result as? JSONArray ?: (result as? JSONObject)?.optJSONArray("data") ?: error("Portal did not provide live channels")
    require(channels.length() in 1..100_000) { "Portal has no available live channels, or requires additional device credentials" }
    val commands = JSONObject()
    val playback = JSONObject()
    val items = (0 until channels.length()).mapNotNull { index ->
      val channel = channels.optJSONObject(index) ?: return@mapNotNull null
      val id = channel.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
      val cmd = channel.optString("cmd").takeIf { it.isNotBlank() } ?: return@mapNotNull null
      commands.put(id, cmd)
      val descriptor = JSONObject().put("id", id).put("cmd", cmd)
      listOf("use_http_tmp_link", "use_load_balancing", "force_ch_link_check").forEach { key ->
        if (channel.has(key)) descriptor.put(key, channel.opt(key))
      }
      playback.put(id, descriptor)
      M3UPlaylistItem("mpvrx-stalker://${account.id}/${Uri.encode(id)}", channel.optString("name", "Channel $id"), tvgLogo = channel.optString("logo").takeIf { it.isNotBlank() }, groupTitle = StalkerProtocol.category(Json.parseToJsonElement(channel.toString()) as JsonObject, genres))
    }
    require(items.isNotEmpty()) { "Portal has no playable channels" }
    val saved = JSONObject().put("name", account.name).put("endpoint", account.endpoint).put("mac", account.mac).put("userAgent", account.userAgent).put("commands", commands).put("playback", playback)
    file(context, account.id).writeText(saved.toString())
    return M3UParseResult.Success(account.name, items)
  }
  suspend fun resolve(context: Context, reference: String): Result<Stream> = withContext(Dispatchers.IO) {
    try {
      val uri = Uri.parse(reference)
      val (account, saved) = load(context, uri.host ?: error("Missing portal reference"))
      val session = authenticate(account)
      val channelId = uri.lastPathSegment ?: error("Missing channel")
      // Upgrade catalogs saved by older builds so temporary/static link flags
      // are available without asking users to delete and re-add their playlist.
      var descriptor = saved.optJSONObject("playback")?.optJSONObject(channelId)
      if (descriptor == null) {
        catalog(context, session)
        descriptor = load(context, account.id).second.optJSONObject("playback")?.optJSONObject(channelId)
      }
      val channel = descriptor ?: error("Channel is no longer available; refresh this playlist")
      val url = session.connection.resolve(Json.parseToJsonElement(channel.toString()) as JsonObject, session.token)
      Result.success(Stream(url, session.connection.headers(session.token, url)))
    } catch (error: kotlinx.coroutines.CancellationException) { throw error } catch (error: Exception) { Result.failure(error) }
  }
}
