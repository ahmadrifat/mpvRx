package app.gyrolet.mpvrx.data.network

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URLDecoder
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class StalkerClientTest {
  private lateinit var server: HttpServer
  private lateinit var endpoint: String
  private lateinit var client: StalkerClient
  private var linkCalls = 0
  private var linkReply = ""
  private var channelsReply = "{\"js\":{\"data\":[]}}"
  private val cookiesSeen = mutableListOf<String>()
  private val parameters = mutableListOf<Map<String, String>>()
  private fun obj(value: String) = Json.parseToJsonElement(value) as JsonObject

  @Before fun setup() {
    server = HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
    endpoint = "http://127.0.0.1:${server.address.port}/portal.php"
    linkReply = """{"js":{"cmd":"ffrt2 $endpoint/live.ts"}}"""
    server.createContext("/") { exchange ->
      val params = exchange.requestURI.rawQuery.orEmpty().split('&').filter { '=' in it }.associate {
        val (key,value) = it.split('=',limit=2)
        URLDecoder.decode(key,"UTF-8") to URLDecoder.decode(value,"UTF-8")
      }
      parameters.add(params)
      cookiesSeen.add(exchange.requestHeaders.getFirst("Cookie").orEmpty())
      val body = when (params["action"]) {
        "handshake" -> {
          exchange.responseHeaders.add("Set-Cookie", "PHPSESSID=fixture-session; Path=/; HttpOnly")
          """{"js":{"token":"fixture-token"}}"""
        }
        "get_profile" -> """{"js":{"blocked":0}}"""
        "get_genres" -> """{"js":[{"id":"12","title":"News"},{"id":8,"title":"Sports"}]}"""
        "get_all_channels" -> channelsReply
        "create_link" -> { linkCalls++; linkReply }
        else -> """{"js":{}}"""
      }.toByteArray()
      exchange.sendResponseHeaders(200,body.size.toLong())
      exchange.responseBody.use { it.write(body) }
    }
    server.start()
    client = StalkerClient(endpoint,"00:1A:79:00:00:01","fixture-agent",OkHttpClient())
  }
  @After fun cleanup() { server.stop(0) }

  @Test fun mapsGenreIdsToNamesRatherThanNumbers() = runBlocking {
    val genres = StalkerProtocol.genres(client.request("itv","get_genres","fixture-token"))
    assertEquals("News",StalkerProtocol.category(obj("""{"tv_genre_id":12}"""),genres))
    assertEquals("Sports",StalkerProtocol.category(obj("""{"tv_genre_id":"8"}"""),genres))
    assertNull(StalkerProtocol.category(obj("""{"tv_genre_id":"999"}"""),genres))
  }

  @Test fun acceptsWrappedGenresAndProvidedCategoryNames() {
    val genres = StalkerProtocol.genres(Json.parseToJsonElement("""{"data":[{"id":12,"name":"News"}]}"""))
    assertEquals("News",genres["12"])
    assertEquals("Movies",StalkerProtocol.category(obj("""{"genre_title":"Movies"}"""),emptyMap()))
  }

  @Test fun retainsMacAlongsideSessionCookiesAndScopesCookiesToHost() = runBlocking {
    client.request("stb","handshake")
    client.request("stb","get_profile","fixture-token")
    assertTrue(cookiesSeen.last().contains("mac=00%3A1A%3A79%3A00%3A00%3A01"))
    assertTrue(cookiesSeen.last().contains("PHPSESSID=fixture-session"))
    assertFalse(client.headers("fixture-token","http://example.invalid/live.ts").getValue("Cookie").contains("PHPSESSID"))
    val another = StalkerClient(endpoint,"00:1A:79:00:00:02","fixture-agent",OkHttpClient())
    assertFalse(another.headers("").getValue("Cookie").contains("PHPSESSID"))
  }

  @Test fun staticStreamDoesNotCallCreateLink() = runBlocking {
    val url = client.resolve(obj("""{"cmd":"ffmpeg $endpoint/direct.ts","use_http_tmp_link":0}"""),"fixture-token")
    assertEquals("$endpoint/direct.ts",url)
    assertEquals(0,linkCalls)
  }

  @Test fun temporaryStreamResolvesFfrt2AndSendsExpectedParameters() = runBlocking {
    val url = client.resolve(obj("""{"cmd":"ffmpeg http://localhost/ch/123","use_http_tmp_link":1}"""),"fixture-token")
    assertEquals("$endpoint/live.ts",url)
    assertEquals(1,linkCalls)
    assertEquals("undefined",parameters.last()["forced_storage"])
    assertEquals("ffmpeg http://localhost/ch/123",parameters.last()["cmd"])
  }

  @Test fun directUrlStillResolvesWhenLoadBalancingIsRequired() = runBlocking {
    client.resolve(obj("""{"cmd":"$endpoint/direct.ts","use_load_balancing":"1"}"""),"fixture-token")
    assertEquals(1,linkCalls)
  }

  @Test fun refreshesExpiredStaticPlayTokenWithoutCallingCreateLink() = runBlocking {
    channelsReply = """{"js":{"data":[{"id":123,"cmd":"ffmpeg https://media.invalid/live.php?stream=123&play_token=fresh","use_http_tmp_link":"0"}]}}"""
    val url = client.resolve(obj("""{"id":"123","cmd":"https://media.invalid/live.php?stream=123&play_token=stale","use_http_tmp_link":0}"""),"fixture-token")
    assertTrue(url.contains("play_token=fresh"))
    assertTrue(url.contains("stream=123"))
    assertEquals(0,linkCalls)
    assertEquals("get_all_channels",parameters.last()["action"])
  }

  @Test fun supportsQuotedCommandsAndRejectsPortalPlaceholders() {
    assertEquals("https://example.invalid/live.ts",StalkerProtocol.playbackUrl("ffrt2 \"https://example.invalid/live.ts\"","https://portal.invalid/portal.php","00:00:00:00:00:00"))
    assertNull(StalkerProtocol.playbackUrl("ffmpeg http:///ch/123",endpoint,"00:00:00:00:00:00"))
    assertNull(StalkerProtocol.playbackUrl("ffmpeg http://localhost/ch/123","http://portal.invalid/portal.php","00:00:00:00:00:00"))
    assertEquals("http://portal.invalid/stalker_portal/c/",StalkerProtocol.referer("http://portal.invalid/stalker_portal/server/load.php"))
  }

  @Test fun connectionLimitDoesNotFallBackToUnsignedCommand() = runBlocking {
    linkReply = """{"js":{"error":"limit","cmd":"$endpoint/live.ts"}}"""
    val error = runCatching { client.resolve(obj("""{"cmd":"$endpoint/direct.ts","use_http_tmp_link":1}"""),"fixture-token") }.exceptionOrNull()
    assertEquals("Portal connection limit reached",error?.message)
  }
}
