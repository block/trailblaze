package xyz.block.trailblaze.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Unit coverage for the pure [TargetIconConvention] filename + resolution helper. */
class TargetIconConventionTest {

  @Test
  fun androidConventionPath() {
    assertEquals(
      "assets/icons/android_com.example.app.png",
      TargetIconConvention.androidIconPath("com.example.app"),
    )
  }

  @Test
  fun webConventionPath() {
    assertEquals(
      "assets/icons/favicon_example.com.png",
      TargetIconConvention.webIconPath("example.com"),
    )
  }

  @Test
  fun hostFromUrlStripsSchemePathPortAndUserinfo() {
    assertEquals("example.com", TargetIconConvention.hostFromUrl("https://example.com/travel/flights"))
    assertEquals("example.com", TargetIconConvention.hostFromUrl("http://example.com"))
    assertEquals("example.com", TargetIconConvention.hostFromUrl("//example.com/path"))
    assertEquals("example.com", TargetIconConvention.hostFromUrl("example.com"))
    assertEquals("example.com", TargetIconConvention.hostFromUrl("https://user@example.com:8080/x?y#z"))
  }

  @Test
  fun hostFromUrlReturnsNullForBlank() {
    assertNull(TargetIconConvention.hostFromUrl(null))
    assertNull(TargetIconConvention.hostFromUrl("   "))
  }

  @Test
  fun explicitIconWinsOverConvention() {
    assertEquals(
      "assets/icons/custom.png",
      TargetIconConvention.resolveIconPath(
        explicitIcon = "assets/icons/custom.png",
        appId = "com.example.app",
        startUrl = "https://example.com",
      ),
    )
  }

  @Test
  fun resolvePrefersAndroidThenWebThenNull() {
    assertEquals(
      "assets/icons/android_com.example.app.png",
      TargetIconConvention.resolveIconPath(explicitIcon = null, appId = "com.example.app"),
    )
    assertEquals(
      "assets/icons/favicon_example.com.png",
      TargetIconConvention.resolveIconPath(explicitIcon = null, startUrl = "https://example.com/x"),
    )
    assertNull(TargetIconConvention.resolveIconPath(explicitIcon = null))
  }

  @Test
  fun iconThreadsFromTrailmapTargetToResolvedConfig() {
    val resolved = xyz.block.trailblaze.config.project.TrailmapTargetConfig(
      displayName = "Example App",
      icon = "assets/icons/android_com.example.app.png",
    ).toAppTargetYamlConfig(defaultId = "example", resolvedTools = emptyList())
    assertEquals("assets/icons/android_com.example.app.png", resolved.icon)
  }
}
