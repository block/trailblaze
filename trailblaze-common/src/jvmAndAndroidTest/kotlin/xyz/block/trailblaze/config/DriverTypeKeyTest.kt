package xyz.block.trailblaze.config

import org.junit.Test
import xyz.block.trailblaze.devices.TrailblazeDriverType
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DriverTypeKeyTest {

  @Test
  fun `android resolves to on-device android driver types`() {
    val result = DriverTypeKey.resolve("android")
    assertEquals(
      setOf(
        TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
        TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
      ),
      result,
    )
  }

  @Test
  fun `ios resolves to all iOS driver types`() {
    // The "ios" platform shorthand covers every iOS driver (except Revyl, which is
    // cloud-hosted). Toolsets that declare `drivers: [ios]` apply to both IOS_HOST
    // (Maestro/XCUITest) and IOS_AXE (direct AXe CLI).
    assertEquals(
      setOf(
        TrailblazeDriverType.IOS_HOST,
        TrailblazeDriverType.IOS_AXE,
      ),
      DriverTypeKey.resolve("ios"),
    )
  }

  @Test
  fun `ios-host resolves to IOS_HOST only`() {
    assertEquals(setOf(TrailblazeDriverType.IOS_HOST), DriverTypeKey.resolve("ios-host"))
  }

  @Test
  fun `ios-axe resolves to IOS_AXE only`() {
    assertEquals(setOf(TrailblazeDriverType.IOS_AXE), DriverTypeKey.resolve("ios-axe"))
  }

  @Test
  fun `web resolves to all web driver types`() {
    // COMPOSE moved off the WEB platform onto its own DESKTOP platform — see the
    // 2026-04-28 first-class-platform wiring (TrailblazeDriverType.COMPOSE.platform was
    // a WEB workaround). The "web" shorthand correspondingly drops COMPOSE; the new
    // "desktop" shorthand picks it up.
    assertEquals(
      setOf(
        TrailblazeDriverType.PLAYWRIGHT_NATIVE,
        TrailblazeDriverType.PLAYWRIGHT_ELECTRON,
      ),
      DriverTypeKey.resolve("web"),
    )
  }

  @Test
  fun `desktop resolves to compose driver`() {
    assertEquals(
      setOf(TrailblazeDriverType.COMPOSE),
      DriverTypeKey.resolve("desktop"),
    )
  }

  @Test
  fun `all resolves to every driver type`() {
    assertEquals(TrailblazeDriverType.entries.toSet(), DriverTypeKey.resolve("all"))
  }

  @Test
  fun `keys are case insensitive`() {
    assertEquals(DriverTypeKey.resolve("Android"), DriverTypeKey.resolve("android"))
    assertEquals(DriverTypeKey.resolve("IOS-HOST"), DriverTypeKey.resolve("ios-host"))
  }

  @Test
  fun `unknown key throws descriptive error`() {
    try {
      DriverTypeKey.resolve("unknown-driver")
      assertTrue(false, "Expected IllegalArgumentException")
    } catch (e: IllegalArgumentException) {
      assertTrue(e.message!!.contains("Unknown driver type key"))
      assertTrue(e.message!!.contains("unknown-driver"))
    }
  }

  @Test
  fun `resolveAll combines multiple keys`() {
    val result = DriverTypeKey.resolveAll(listOf("android", "ios-host"))
    assertEquals(
      TrailblazeDriverType.ANDROID_ON_DEVICE_DRIVER_TYPES + TrailblazeDriverType.IOS_HOST,
      result,
    )
  }
}
