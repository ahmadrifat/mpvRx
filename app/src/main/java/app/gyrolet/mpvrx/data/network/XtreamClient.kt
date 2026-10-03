/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.data.network

import app.gyrolet.mpvrx.network.awaitResponse
import app.gyrolet.mpvrx.utils.media.M3UParseResult
import app.gyrolet.mpvrx.utils.media.M3UParser
import app.gyrolet.mpvrx.utils.media.M3UPlaylistItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream

data class XtreamCatalog(
  val serverUrl: String,
  val playlist: M3UParseResult.Success,
)

/** Loads the standard Xtream authentication response and M3U-plus account catalog. */
class XtreamClient(
  private val httpClient: OkHttpClient,
  private val json: Json,
) {
  suspend fun loadCatalog(
    rawServerUrl: String,
    username: String,
    password: String,
    output: String = "m3u8",
  ): Result<XtreamCatalog> =
    try {
      val serverUrl = normalizeServerUrl(rawServerUrl)
      require(username.isNotBlank()) { "Username is required" }
      require(password.isNotBlank()) { "Password is required" }
      require(output in setOf("ts", "m3u8")) { "Unsupported Xtream stream format" }

      validateAccount(serverUrl, username, password)
      val playlistUrl =
        endpoint(serverUrl, "get.php", username, password)
          .newBuilder()
          .addQueryParameter("type", "m3u_plus")
          .addQueryParameter("output", output)
          .build()
      val parseResult = M3UParser.parseFromUrl(playlistUrl.toString(), httpClient = httpClient)
      when (parseResult) {
        is M3UParseResult.Success -> Result.success(XtreamCatalog(serverUrl, parseResult))
        is M3UParseResult.Error -> Result.success(XtreamCatalog(serverUrl, loadApiCatalog(serverUrl, username, password, output)))
      }
    } catch (error: CancellationException) {
      throw error
    } catch (error: Exception) {
      Result.failure(error)
    }

  fun normalizeServerUrl(rawServerUrl: String): String {
    val trimmed = rawServerUrl.trim()
    require(trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) {
      "Server URL must start with http:// or https://"
    }
    val parsed = trimmed.toHttpUrlOrNull() ?: throw IllegalArgumentException("Invalid Xtream server URL")
    require(parsed.username.isEmpty() && parsed.password.isEmpty()) { "Enter credentials in their own fields" }
    require(parsed.query == null && parsed.fragment == null) { "Server URL must not contain a query or fragment" }

    val normalizedPath = parsed.encodedPath.trimEnd('/').ifEmpty { "/" }
    return parsed
      .newBuilder()
      .encodedPath(normalizedPath)
      .build()
      .toString()
      .removeSuffix("/")
  }

  suspend fun checkAccount(rawServerUrl: String, username: String, password: String): Result<String> = try {
    val info = validateAccount(normalizeServerUrl(rawServerUrl), username, password)
    val expiry = (info["exp_date"] as? JsonPrimitive)?.content?.toLongOrNull()?.takeIf { it > 0 }
    val date = expiry?.let { java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it * 1000)) } ?: "Not supplied / unlimited"
    Result.success("Status: Active\nExpires: $date\nConnections: ${(info["active_cons"] as? JsonPrimitive)?.content ?: "?"} / ${(info["max_connections"] as? JsonPrimitive)?.content ?: "?"}")
  } catch (error: CancellationException) { throw error } catch (error: Exception) { Result.failure(error) }

  private suspend fun loadApiCatalog(serverUrl: String, username: String, password: String, output: String): M3UParseResult.Success {
    val url = endpoint(serverUrl, "player_api.php", username, password).newBuilder().addQueryParameter("action", "get_live_streams").build()
    val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
    val channels = httpClient.newCall(request).awaitResponse().use { response ->
      require(response.isSuccessful) { "Xtream channel API returned HTTP ${response.code}" }
      json.parseToJsonElement(readBoundedUtf8(response.body.byteStream(), 32 * 1024 * 1024)) as? JsonArray
        ?: throw IllegalArgumentException("Xtream channel API did not return a catalog")
    }
    require(channels.size <= 100_000) { "Xtream catalog is too large" }
    val items = channels.mapNotNull { element ->
      val channel = element as? JsonObject ?: return@mapNotNull null
      val id = (channel["stream_id"] as? JsonPrimitive)?.content?.takeIf { it.all(Char::isDigit) && it.isNotEmpty() } ?: return@mapNotNull null
      val streamUrl = "$serverUrl/".toHttpUrlOrNull()!!.newBuilder().addPathSegment("live").addPathSegment(username).addPathSegment(password).addPathSegment("$id.$output").build().toString()
      M3UPlaylistItem(url = streamUrl, title = (channel["name"] as? JsonPrimitive)?.content ?: "Channel $id", tvgId = (channel["epg_channel_id"] as? JsonPrimitive)?.content, tvgLogo = (channel["stream_icon"] as? JsonPrimitive)?.content, groupTitle = (channel["category_id"] as? JsonPrimitive)?.content)
    }
    require(items.isNotEmpty()) { "Xtream account has no available live channels" }
    return M3UParseResult.Success("Xtream Live TV", items)
  }

  private suspend fun validateAccount(
    serverUrl: String,
    username: String,
    password: String,
  ): JsonObject {
    val request =
      Request
        .Builder()
        .url(endpoint(serverUrl, "player_api.php", username, password))
        .header("User-Agent", USER_AGENT)
        .build()
    httpClient.newCall(request).awaitResponse().use { response ->
      if (response.code == 401 || response.code == 403) {
        throw IllegalArgumentException("Invalid Xtream username or password")
      }
      if (!response.isSuccessful) {
        throw IllegalArgumentException("Xtream server returned HTTP ${response.code}")
      }

      val content = readBoundedUtf8(response.body.byteStream())
      val root =
        runCatching { json.parseToJsonElement(content).jsonObject }
          .getOrElse { throw IllegalArgumentException("Xtream server returned an invalid account response") }
      val userInfo =
        root["user_info"] as? JsonObject
          ?: throw IllegalArgumentException("Xtream server returned an invalid account response")
      val authenticated = (userInfo["auth"] as? JsonPrimitive)?.content.orEmpty()
      if (authenticated != "1" && !authenticated.equals("true", ignoreCase = true)) {
        throw IllegalArgumentException("Invalid Xtream username or password")
      }
      val status = (userInfo["status"] as? JsonPrimitive)?.content.orEmpty()
      if (status.isNotBlank() && !status.equals("active", ignoreCase = true)) {
        throw IllegalArgumentException("Xtream account is not active")
      }
      val expiry = (userInfo["exp_date"] as? JsonPrimitive)?.content?.toLongOrNull()
      require(expiry == null || expiry == 0L || expiry > System.currentTimeMillis() / 1000) { "Xtream account has expired" }
      return userInfo
    }
  }

  private fun endpoint(
    serverUrl: String,
    endpoint: String,
    username: String,
    password: String,
  ): HttpUrl =
    "$serverUrl/"
      .toHttpUrlOrNull()
      ?.newBuilder()
      ?.addPathSegment(endpoint)
      ?.addQueryParameter("username", username)
      ?.addQueryParameter("password", password)
      ?.build()
      ?: throw IllegalArgumentException("Invalid Xtream server URL")

  private suspend fun readBoundedUtf8(input: java.io.InputStream, limit: Int = MAX_AUTH_RESPONSE_BYTES): String =
    runInterruptible(Dispatchers.IO) {
      input.use { stream ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
          val count = stream.read(buffer)
          if (count < 0) break
          total += count
          if (total > limit) {
            throw IllegalArgumentException("Xtream account response is too large")
          }
          output.write(buffer, 0, count)
        }
        output.toString(Charsets.UTF_8.name())
      }
    }

  companion object {
    fun outputPreferenceKey(serverUrl: String, username: String): String {
      val normalized = serverUrl.trim().toHttpUrlOrNull()?.toString()?.trimEnd('/') ?: serverUrl.trim().trimEnd('/')
      val digest = java.security.MessageDigest.getInstance("SHA-256").digest("$normalized\n$username".toByteArray())
      return "xtream_output_" + digest.joinToString("") { "%02x".format(it) }
    }
    private const val USER_AGENT = "mpvRx/2.5"
    private const val MAX_AUTH_RESPONSE_BYTES = 512 * 1024
  }
}
