package app.gyrolet.mpvrx.domain
import app.gyrolet.mpvrx.ui.player.clip.StreamExportAvailability
import org.junit.Assert.*
import org.junit.Test
class StreamExportAvailabilityTest {
  @Test fun missingAuthorUsesChannelOrBlank() {
    assertEquals("Channel", app.gyrolet.mpvrx.ui.player.clip.ExportFiles.author(" null ", null, "Channel"))
    assertEquals("", app.gyrolet.mpvrx.ui.player.clip.ExportFiles.author(null, "null", " "))
  }
  @Test fun cachedDurationDoesNotRemoveRecording() {
    listOf("m3u", "m3u8", "hls", "ts", "mpd", "ism").forEach { assertTrue(StreamExportAvailability.isStream("https://example.test/channel.$it?token=example")) }
    assertTrue(StreamExportAvailability.isStream("https://example.test/play", demuxer = "mpegts"))
    assertTrue(StreamExportAvailability.isStream("rtsp://example.test/live"))
    assertFalse(StreamExportAvailability.isStream("file:///storage/video.ts"))
    assertFalse(StreamExportAvailability.isStream("https://example.test/video.mp4"))
  }
}
