package app.gyrolet.mpvrx.domain

import app.gyrolet.mpvrx.ui.player.clip.NetworkMediaFacts
import app.gyrolet.mpvrx.ui.player.clip.StreamExportAvailability
import app.gyrolet.mpvrx.domain.network.XtreamPlaybackUri
import org.junit.Assert.*
import org.junit.Test

class NetworkMediaFactsTest {
  @Test fun opaqueRedirectedTsIsRecordableWithoutAnExtension() {
    val sample = ByteArray(188 * 8) { 0 }
    for (i in sample.indices step 188) sample[i] = 0x47
    assertEquals("mpegts", NetworkMediaFacts.transport("application/octet-stream", sample))
    assertEquals("mpegts", NetworkMediaFacts.transport("video/mp2t; charset=binary", byteArrayOf()))
    assertTrue(StreamExportAvailability.isStream("content://playlist/channel", "http://example.test/play/opaque", "lavf,mpegts"))
    assertTrue(StreamExportAvailability.isStream("content://playlist/channel", "http://example.test/play/opaque", mime = "video/mp2t"))
    assertFalse(StreamExportAvailability.isStream("file:///storage/video.ts", demuxer = "mpegts"))
  }
  @Test fun livePortalReferencesDoNotDependOnHttpOriginalUri() {
    assertTrue(StreamExportAvailability.isStream("content://playlist/channel", "mpvrx-stalker://account/channel"))
    for (route in listOf(XtreamPlaybackUri.Route.LIVE, XtreamPlaybackUri.Route.BARE_LIVE)) {
      val uri = XtreamPlaybackUri.create(XtreamPlaybackUri.Reference("account-key-123456", route, "123", "ts"))
      assertTrue(StreamExportAvailability.isStream(uri))
    }
    val vod = XtreamPlaybackUri.create(XtreamPlaybackUri.Reference("account-key-123456", XtreamPlaybackUri.Route.MOVIE, "456", "mp4"))
    assertFalse(StreamExportAvailability.isStream(vod))
  }
  @Test fun hlsMasterLiveAndFiniteAreDifferent() {
    val master = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000\nchild\n"
    val rolling = "#EXTM3U\n#EXT-X-TARGETDURATION:10\n#EXTINF:9.7,\npart1\n#EXTINF:10.2,\npart2\n"
    assertNull(NetworkMediaFacts.hlsLive(master))
    assertNull(NetworkMediaFacts.hlsLive("#EXTM3U\n#EXTINF:-1,Channel\nhttp://example.test/channel\n"))
    assertEquals(true, NetworkMediaFacts.hlsLive(rolling))
    assertEquals(false, NetworkMediaFacts.hlsLive(rolling + "#EXT-X-ENDLIST\n"))
    assertEquals(19.9, NetworkMediaFacts.hlsDuration(rolling), .0001)
    assertEquals("hls", NetworkMediaFacts.transport(null, rolling.toByteArray()))
    assertNull(NetworkMediaFacts.transport("text/html", "<html>Expired account</html>".toByteArray()))
  }
}
