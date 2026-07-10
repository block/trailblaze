package xyz.block.trailblaze.ui.tabs.session

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import xyz.block.trailblaze.ui.recordings.ExistingTrail

/**
 * Pins [isAlreadyRecorded], which decides the session view's "Save Recording" vs "Update Recording"
 * label. It must treat an existing unified `trail.yaml` as "already recorded" — saving merges the
 * device into it rather than writing a new per-device file, so a fresh "Save" label would be wrong.
 */
class IsAlreadyRecordedTest {

  private fun existing(fileName: String, isUnifiedContent: Boolean = false) =
    ExistingTrail(
      absolutePath = "/trails/case/$fileName",
      relativePath = "case/$fileName",
      fileName = fileName,
      isUnifiedContent = isUnifiedContent,
    )

  @Test
  fun `no matching recording is not already recorded`() {
    val existing = listOf(existing("ios-iphone.trail.yaml"))
    assertFalse(isAlreadyRecorded(existing, expectedFileName = "android.trail.yaml"))
  }

  @Test
  fun `a matching per-device filename is already recorded`() {
    val existing = listOf(existing("ios-iphone.trail.yaml"), existing("android.trail.yaml"))
    assertTrue(isAlreadyRecorded(existing, expectedFileName = "android.trail.yaml"))
  }

  @Test
  fun `an existing bare unified trail_yaml is already recorded even without a filename match`() {
    // Saving merges this device into the unified file; the label must read "Update", not "Save".
    val existing = listOf(existing("trail.yaml"))
    assertTrue(isAlreadyRecorded(existing, expectedFileName = "android.trail.yaml"))
  }

  @Test
  fun `an existing named unified trail (content-detected) is already recorded`() {
    // A unified trail under a named `*.trail.yaml` is still a merge target — content signal wins.
    val existing = listOf(existing("case_5735240.trail.yaml", isUnifiedContent = true))
    assertTrue(isAlreadyRecorded(existing, expectedFileName = "android.trail.yaml"))
  }

  @Test
  fun `an empty recording list is not already recorded`() {
    assertFalse(isAlreadyRecorded(emptyList(), expectedFileName = "android.trail.yaml"))
  }
}
