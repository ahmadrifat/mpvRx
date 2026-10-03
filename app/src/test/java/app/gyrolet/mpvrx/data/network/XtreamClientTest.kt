package app.gyrolet.mpvrx.data.network

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class XtreamClientTest {
  private lateinit var server: HttpServer
  private lateinit var base: String
  private val client = XtreamClient(OkHttpClient(), Json { ignoreUnknownKeys = true })
  private var account = """{"user_info":{"auth":1,"status":"Active","exp_date":null,"active_cons":1,"max_connections":2}}"""
  private var playlist = "#EXTM3U\n#EXT-X-TARGETDURATION:10\n#EXTINF:10,\nsegment.ts\n"
  private var channels = """[{"stream_id":123,"name":"News","stream_icon":"","category_id":"5"}]"""

  @Before fun setup() {
    server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/") { exchange ->
      val body = if (exchange.requestURI.path.endsWith("get.php")) playlist else if (exchange.requestURI.rawQuery.orEmpty().contains("action=get_live_categories")) """[{"category_id":"5","category_name":"News channels"}]""" else if (exchange.requestURI.rawQuery.orEmpty().contains("action=get_live_streams")) channels else account
      val bytes = body.toByteArray()
      exchange.sendResponseHeaders(200, bytes.size.toLong())
      exchange.responseBody.use { it.write(bytes) }
    }
    server.start()
    base = "http://127.0.0.1:${server.address.port}"
  }
  @After fun cleanup() { server.stop(0) }

  @Test fun activeAccountReportsConnectionLimits() = runBlocking {
    val result = client.checkAccount(base, "user", "password").getOrThrow()
    assertTrue(result.contains("Status: Active"))
    assertTrue(result.contains("Connections: 1 / 2"))
  }
  @Test fun expiredAccountCannotBeImported() = runBlocking {
    account = """{"user_info":{"auth":1,"status":"Active","exp_date":"1"}}"""
    assertTrue(client.loadCatalog(base, "user", "password").exceptionOrNull()?.message.orEmpty().contains("expired"))
  }
  @Test fun invalidCredentialsFailBeforeCatalogImport() = runBlocking {
    account = """{"user_info":{"auth":0,"status":"Active"}}"""
    assertTrue(client.loadCatalog(base, "user", "password").isFailure)
  }
  @Test fun hlsManifestFallsBackToChannelsRatherThanSegments() = runBlocking {
    val result = client.loadCatalog(base, "user", "password").getOrThrow()
    assertEquals(1, result.playlist.items.size)
    assertEquals("News", result.playlist.items.single().title)
    assertEquals("News channels", result.playlist.items.single().groupTitle)
    assertTrue(result.playlist.items.single().url.endsWith("/live/user/password/123.m3u8"))
  }
  @Test fun expiredAccountDetailsRemainReadable() = runBlocking {
    account = """{"user_info":{"auth":1,"status":"Expired","exp_date":1,"active_cons":0,"max_connections":2}}"""
    assertTrue(client.checkAccount(base, "user", "password").getOrThrow().contains("Status: Expired"))
    assertTrue(client.loadCatalog(base, "user", "password").isFailure)
  }
  @Test fun alternativeM3uGroupsAreRead() {
    val parsed = app.gyrolet.mpvrx.utils.media.M3UParser.parseContent("#EXTM3U\n#EXTINF:-1,One\n#EXTGRP: News \nhttp://example.com/1.ts\n") as app.gyrolet.mpvrx.utils.media.M3UParseResult.Success
    assertEquals("News", parsed.items.single().groupTitle)
  }
  @Test fun emptyFallbackCatalogFailsClearly() = runBlocking {
    channels = "[]"
    assertTrue(client.loadCatalog(base, "user", "password").exceptionOrNull()?.message.orEmpty().contains("no available"))
  }
}
