package app.gyrolet.mpvrx.data.network

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Opt-in provider test. Credentials come from the environment, never fixtures. */
class StalkerLiveTest {
  @Test fun genreLookupAndRedirectedPlayback() = runBlocking {
    val portal = System.getenv("MPVRX_TEST_MAG_PORTAL")
    val mac = System.getenv("MPVRX_TEST_MAG_MAC")
    val channelId = System.getenv("MPVRX_TEST_MAG_CHANNEL")
    assumeTrue(!portal.isNullOrBlank() && !mac.isNullOrBlank() && !channelId.isNullOrBlank())
    val http = OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS)
      .readTimeout(20,TimeUnit.SECONDS).callTimeout(30,TimeUnit.SECONDS).build()
    val client = StalkerClient("${portal!!.trimEnd('/')}/server/load.php",mac!!,
      "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3",http)
    try {
      val handshake = client.request("stb","handshake",params=mapOf("token" to "")) as JsonObject
      val token = StalkerProtocol.text(handshake,"token") ?: error("Missing authentication token")
      client.request("stb","get_profile",token,mapOf("stb_type" to "MAG254","client_type" to "STB","hd" to "1","video_out" to "hdmi",
        "ver" to "ImageDescription: 0.2.18-r23-254; PORTAL version: 5.6.1; API Version: JS API version: 343; STB API version: 146; Player Engine version: 0x58c"))
      val genres = StalkerProtocol.genres(client.request("itv","get_genres",token))
      assertTrue("Portal supplied no genre names",genres.isNotEmpty())
      val channels = StalkerProtocol.rows(client.request("itv","get_all_channels",token))
      val channel = channels.firstOrNull { StalkerProtocol.text(it,"id")==channelId } ?: error("Test channel unavailable")
      assertNotNull("Channel genre could not be named",StalkerProtocol.category(channel,genres))
      val stream = client.resolve(channel,token)
      val request = Request.Builder().url(stream).apply { client.headers(token,stream).forEach { (key,value) -> header(key,value) } }.build()
      http.newCall(request).execute().use { response ->
        assertEquals("Live stream did not return HTTP 200",200,response.code)
        val input = response.body.byteStream()
        val bytes = ByteArray(2048)
        var count=0
        while (count < bytes.size) { val n=input.read(bytes,count,bytes.size-count); if (n<0) break; count+=n }
        assertTrue("Live stream did not supply a TS sample",count>376)
        assertTrue("Live stream sample has no MPEG-TS packet alignment",(0 until 188).any { offset ->
          (offset until count step 188).all { bytes[it].toInt() and 255 == 0x47 }
        })
      }
    } catch (error: Exception) {
      // Network exceptions can include credential-bearing URLs. Do not log them.
      throw AssertionError("Live MAG diagnostic failed (${error.javaClass.simpleName}); credentials redacted")
    }
  }
}
