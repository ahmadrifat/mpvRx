package app.gyrolet.mpvrx.domain.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomReleaseVersionTest {
  @Test fun followsModRevisionNumerically() {
    assertTrue(CustomReleaseVersion.isNewer("v2.7.2.7", "2.7.2.6"))
    assertTrue(CustomReleaseVersion.isNewer("v2.7.2.10", "2.7.2.9"))
    assertFalse(CustomReleaseVersion.isNewer("v2.7.2.6", "2.7.2.6"))
    assertFalse(CustomReleaseVersion.isNewer("v2.7.2.5", "2.7.2.6"))
  }

  @Test fun upstreamVersionTakesPriority() {
    assertTrue(CustomReleaseVersion.isNewer("v2.7.3.1", "2.7.2.99"))
    assertTrue(CustomReleaseVersion.isNewer("v3.0.0.1", "2.7.2.99"))
    assertFalse(CustomReleaseVersion.isNewer("v2.7.1.99", "2.7.2.6"))
  }

  @Test fun rejectsNonCustomAndInvalidTags() {
    listOf("v2.7.2", "preview-r900", "v2.7.2-cs.7", "v2.7.2.7-beta", "v2.7.2.99999999999").forEach {
      assertFalse(CustomReleaseVersion.isNewer(it, "2.7.2.6"))
    }
    assertFalse(CustomReleaseVersion.isNewer("v2.7.2.7", "invalid"))
  }
}
