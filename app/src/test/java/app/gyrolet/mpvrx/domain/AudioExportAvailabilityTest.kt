package app.gyrolet.mpvrx.domain

import app.gyrolet.mpvrx.ui.player.clip.AudioExportAvailability
import org.junit.Assert.*
import org.junit.Test

class AudioExportAvailabilityTest {
  @Test fun seekableLiveHlsWithGrowingDurationIsHidden() {
    assertFalse(AudioExportAvailability.allowed("https://example.com/channel.m3u8", 39.0, true, true))
  }
  @Test fun vodHlsAndLocalFilesRemainAvailable() {
    assertTrue(AudioExportAvailability.allowed("https://example.com/movie.m3u8", 3600.0, true, false))
    assertTrue(AudioExportAvailability.allowed("content://media/1", 120.0, true, null))
  }
  @Test fun liveIptvAndNonSeekableStreamsAreHidden() {
    assertFalse(AudioExportAvailability.allowed("mpvrx-stalker://account/channel", 19.0, true, null))
    assertFalse(AudioExportAvailability.allowed("https://example.com/live/u/p/1.ts", 29.0, true, null))
    assertFalse(AudioExportAvailability.allowed("https://example.com/stream", 29.0, false, null))
  }
  @Test fun unknownDurationDoesNotCreateAnInvalidFullLengthExport() {
    assertFalse(AudioExportAvailability.allowed("https://example.com/movie.mp4", null, true, null))
  }
}
