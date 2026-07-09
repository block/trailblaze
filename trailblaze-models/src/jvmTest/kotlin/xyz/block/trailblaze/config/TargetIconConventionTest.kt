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
  fun iosConventionPath() {
    assertEquals(
      "assets/icons/ios_com.example.app.png",
      TargetIconConvention.iosIconPath("com.example.app"),
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
  fun hostFromUrlKeepsBracketedIpv6LiteralIntact() {
    // The naive `substringBefore(':')` used to cut inside the brackets (first `:` is part of the
    // address itself), yielding "[" instead of the address.
    assertEquals("[::1]", TargetIconConvention.hostFromUrl("https://[::1]:8080/x"))
    assertEquals("[::1]", TargetIconConvention.hostFromUrl("[::1]"))
    assertEquals("[2001:db8::1]", TargetIconConvention.hostFromUrl("http://[2001:db8::1]:80/path"))
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
  fun resolvePrefersAndroidThenIosThenWebThenNull() {
    assertEquals(
      "assets/icons/android_com.example.app.png",
      TargetIconConvention.resolveIconPath(explicitIcon = null, appId = "com.example.app"),
    )
    assertEquals(
      "assets/icons/ios_com.example.app.png",
      TargetIconConvention.resolveIconPath(explicitIcon = null, iosBundleId = "com.example.app"),
    )
    assertEquals(
      "assets/icons/favicon_example.com.png",
      TargetIconConvention.resolveIconPath(explicitIcon = null, startUrl = "https://example.com/x"),
    )
    assertNull(TargetIconConvention.resolveIconPath(explicitIcon = null))
  }

  @Test
  fun resolvePrefersAndroidOverIosWhenBothPresent() {
    assertEquals(
      "assets/icons/android_com.example.android.png",
      TargetIconConvention.resolveIconPath(
        explicitIcon = null,
        appId = "com.example.android",
        iosBundleId = "com.example.ios",
      ),
    )
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
