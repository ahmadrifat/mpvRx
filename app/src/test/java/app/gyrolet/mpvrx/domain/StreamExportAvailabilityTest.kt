package app.gyrolet.mpvrx.domain
import app.gyrolet.mpvrx.ui.player.clip.StreamExportAvailability
import org.junit.Assert.*
import org.junit.Test
class StreamExportAvailabilityTest {
  @Test fun missingAuthorUsesChannelOrBlank() {
    assertEquals("Channel", app.gyrolet.mpvrx.ui.player.clip.ExportFiles.author(" null ", null, "Channel"))
    assertEquals("", app.gyrolet.mpvrx.ui.player.clip.ExportFiles.author(null, "null", " "))
  }
  @Test fun savedLayoutsReceiveExactlyOneRecordingControl() {
    val p = app.gyrolet.mpvrx.preferences.PlayerControlLayout
    val d = app.gyrolet.mpvrx.preferences.PlayerButton.DOWNLOAD
    val r = app.gyrolet.mpvrx.preferences.PlayerButton.RECORD
    assertEquals(listOf(listOf(d, r), emptyList<app.gyrolet.mpvrx.preferences.PlayerButton>()), p.withRecordingCompanion(listOf(listOf(d), emptyList())))
    assertEquals(listOf(listOf(d), listOf(r)), p.withRecordingCompanion(listOf(listOf(d), listOf(r))))
  }
  @Test fun cachedDurationDoesNotRemoveRecording() {
    listOf("m3u", "m3u8", "hls", "ts", "mpd", "ism").forEach { assertTrue(StreamExportAvailability.isStream("https://example.test/channel.$it?token=example")) }
    assertTrue(StreamExportAvailability.isStream("https://example.test/play", demuxer = "mpegts"))
    assertTrue(StreamExportAvailability.isStream("rtsp://example.test/live"))
    assertTrue(StreamExportAvailability.isStream("http://example.test/play/live.php?extension=ts"))
    assertTrue(StreamExportAvailability.isStream("http://example.test/play.php?extension=m3u8"))
    assertFalse(StreamExportAvailability.isStream("file:///storage/video.ts"))
    assertFalse(StreamExportAvailability.isStream("https://example.test/video.mp4"))
  }
}
