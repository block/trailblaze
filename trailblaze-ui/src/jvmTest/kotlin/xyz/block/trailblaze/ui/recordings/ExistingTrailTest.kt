package xyz.block.trailblaze.ui.recordings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the filename-derived facts on [ExistingTrail] that drive recording UI: a bare `trail.yaml` is
 * a unified single-file trail with no filename-encoded platform/classifiers, a legacy
 * `<classifier>.trail.yaml` derives its platform + classifiers from the filename, and the NL
 * definition file (`trailblaze.yaml`) is neither.
 */
class ExistingTrailTest {

  private fun existing(fileName: String, isUnifiedContent: Boolean = false) =
    ExistingTrail(
      absolutePath = "/trails/case/$fileName",
      relativePath = "case/$fileName",
      fileName = fileName,
      isUnifiedContent = isUnifiedContent,
    )

  @Test
  fun `a bare trail_yaml is unified with no filename-derived platform or classifiers`() {
    val trail = existing("trail.yaml")
    assertTrue(trail.isUnifiedTrailFile)
    assertTrue(trail.isUnified)
    assertFalse(trail.isDefaultTrailFile)
    assertNull(trail.platform, "a unified trail encodes no platform in its filename")
    assertTrue(trail.classifiers.isEmpty(), "a unified trail encodes no classifiers in its filename")
  }

  @Test
  fun `a named file with unified content is unified despite its legacy-looking filename`() {
    // A unified trail stored under a named `*.trail.yaml` (content-detected upstream). Its filename
    // looks legacy, but the content signal must suppress the bogus filename-derived platform chips.
    val trail = existing("case_5735240.trail.yaml", isUnifiedContent = true)
    assertFalse(trail.isUnifiedTrailFile, "the filename check alone does not recognize it")
    assertTrue(trail.isUnified, "the content signal recognizes it")
    assertNull(trail.platform, "no filename-derived platform for a unified trail")
    assertTrue(trail.classifiers.isEmpty(), "no filename-derived classifiers for a unified trail")
  }

  @Test
  fun `a legacy per-device file derives platform and classifiers from its filename`() {
    val trail = existing("ios-iphone-portrait.trail.yaml")
    assertFalse(trail.isUnifiedTrailFile)
    assertFalse(trail.isDefaultTrailFile)
    assertEquals("ios", trail.platform?.classifier)
    assertEquals(listOf("iphone", "portrait"), trail.classifiers.map { it.classifier })
  }

  @Test
  fun `a single-classifier legacy file has a platform and no extra classifiers`() {
    val trail = existing("android.trail.yaml")
    assertFalse(trail.isUnifiedTrailFile)
    assertEquals("android", trail.platform?.classifier)
    assertTrue(trail.classifiers.isEmpty())
  }

  @Test
  fun `the natural-language definition file is neither unified nor platform-bearing`() {
    val trail = existing("trailblaze.yaml")
    assertTrue(trail.isDefaultTrailFile)
    assertFalse(trail.isUnifiedTrailFile)
    assertNull(trail.platform)
    assertTrue(trail.classifiers.isEmpty())
  }
}
