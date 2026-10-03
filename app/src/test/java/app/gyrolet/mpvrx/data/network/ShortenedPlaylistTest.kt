/* SPDX-License-Identifier: AGPL-3.0-or-later */
package app.gyrolet.mpvrx.data.network

import app.gyrolet.mpvrx.utils.media.M3UParser
import app.gyrolet.mpvrx.utils.media.M3UParseResult
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class ShortenedPlaylistTest {
  @Test fun redirectsExposeDecodedXtreamCredentialsAndResolveRelativeChannels() = runBlocking {
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val base = "http://127.0.0.1:${server.address.port}"
    server.createContext("/short") { exchange ->
      exchange.responseHeaders.add("Location", "$base/second")
      exchange.sendResponseHeaders(302, -1); exchange.close()
    }
    server.createContext("/second") { exchange ->
      exchange.responseHeaders.add("Location", "$base/portal/get.php?username=user%2Bname&password=p%26ss&type=m3u_plus")
      exchange.sendResponseHeaders(307, -1); exchange.close()
    }
    server.createContext("/portal/get.php") { exchange ->
      val bytes = "#EXTM3U\n#EXTINF:-1 group-title=\"News\",One\nchannel.ts\n".toByteArray()
      exchange.sendResponseHeaders(200, bytes.size.toLong())
      exchange.responseBody.use { it.write(bytes) }
    }
    server.start()
    try {
      var resolved = ""
      val playlist = M3UParser.parseFromUrl("$base/short", httpClient = OkHttpClient(), onResolvedUrl = { resolved = it }) as M3UParseResult.Success
      val account = XtreamPlaylistLink.parse(resolved)!!
      assertEquals("$base/portal", account.server)
      assertEquals("user+name", account.username)
      assertEquals("p&ss", account.password)
      assertEquals("$base/portal/channel.ts", playlist.items.single().url)
    } finally { server.stop(0) }
  }

  @Test fun regularPlaylistAndIncompleteAccountsAreNotPromoted() {
    assertNull(XtreamPlaylistLink.parse("https://example.com/list.m3u?username=u&password=p"))
    assertNull(XtreamPlaylistLink.parse("https://example.com/get.php?username=u"))
  }
}
