package app.gyrolet.mpvrx.domain

import app.gyrolet.mpvrx.domain.download.ResumeResponse
import app.gyrolet.mpvrx.domain.torrent.TorrentTransferPolicy
import org.junit.Assert.*
import org.junit.Test

class TransferPolicyTest {
  @Test fun backgroundMobilePausesButExplicitResumeOverridesIt() {
    assertTrue(TorrentTransferPolicy.shouldPause(false, false, false, false))
    assertFalse(TorrentTransferPolicy.shouldPause(false, true, false, false))
    assertFalse(TorrentTransferPolicy.shouldPause(true, false, false, false))
    assertFalse(TorrentTransferPolicy.shouldPause(false, false, true, false))
    assertFalse(TorrentTransferPolicy.shouldPause(false, false, false, true))
  }
  @Test fun resumedRangesMustStartAtTheExactSavedOffset() {
    assertTrue(ResumeResponse.validRange(100, 206, "bytes 100-199/200"))
    assertFalse(ResumeResponse.validRange(100, 206, "bytes 0-199/200"))
    assertFalse(ResumeResponse.validRange(100, 200, null))
    assertFalse(ResumeResponse.validRange(100, 206, "bytes 100-200/200"))
    assertFalse(ResumeResponse.validRange(100, 206, "bytes 100-99/200"))
  }
}
