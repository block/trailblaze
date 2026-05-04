package xyz.block.trailblaze.devices

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pins [TrailblazeDevicePlatform.fromString] suffix-stripping behaviour. The function is on
 * the hot path of every CLI invocation that resolves `--device <platform>` or
 * `--device <platform>/<instanceId>`, so the edge cases (case-insensitivity, suffix
 * stripping, malformed input) are worth locking down.
 */
class TrailblazeDevicePlatformTest {

  @Test
  fun `bare platform name resolves`() {
    assertEquals(TrailblazeDevicePlatform.WEB, TrailblazeDevicePlatform.fromString("web"))
    assertEquals(TrailblazeDevicePlatform.ANDROID, TrailblazeDevicePlatform.fromString("android"))
    assertEquals(TrailblazeDevicePlatform.IOS, TrailblazeDevicePlatform.fromString("ios"))
  }

  @Test
  fun `platform name is case-insensitive`() {
    assertEquals(TrailblazeDevicePlatform.WEB, TrailblazeDevicePlatform.fromString("WEB"))
    assertEquals(TrailblazeDevicePlatform.ANDROID, TrailblazeDevicePlatform.fromString("Android"))
    assertEquals(TrailblazeDevicePlatform.IOS, TrailblazeDevicePlatform.fromString("iOS"))
    assertEquals(TrailblazeDevicePlatform.DESKTOP, TrailblazeDevicePlatform.fromString("Desktop"))
  }

  @Test
  fun `instance-id suffix is stripped`() {
    // The user-facing CLI form is `--device <platform>/<instanceId>`. fromString must
    // resolve the platform regardless of which instance the user pointed at.
    assertEquals(TrailblazeDevicePlatform.WEB, TrailblazeDevicePlatform.fromString("web/checkout"))
    assertEquals(TrailblazeDevicePlatform.WEB, TrailblazeDevicePlatform.fromString("web/playwright-native"))
    assertEquals(TrailblazeDevicePlatform.ANDROID, TrailblazeDevicePlatform.fromString("android/emulator-5554"))
    assertEquals(TrailblazeDevicePlatform.IOS, TrailblazeDevicePlatform.fromString("ios/SIM-UUID-1234"))
  }

  @Test
  fun `instance-id suffix is stripped case-insensitively`() {
    assertEquals(TrailblazeDevicePlatform.ANDROID, TrailblazeDevicePlatform.fromString("ANDROID/emulator-5554"))
    assertEquals(TrailblazeDevicePlatform.WEB, TrailblazeDevicePlatform.fromString("Web/checkout"))
  }

  @Test
  fun `whitespace around platform is tolerated`() {
    // Real CLI input is already trimmed by picocli, but defensive — covers shell quirks.
    assertEquals(TrailblazeDevicePlatform.WEB, TrailblazeDevicePlatform.fromString(" web "))
    assertEquals(TrailblazeDevicePlatform.WEB, TrailblazeDevicePlatform.fromString(" web /checkout"))
  }

  @Test
  fun `unknown platform returns null`() {
    assertNull(TrailblazeDevicePlatform.fromString("blackberry"))
    assertNull(TrailblazeDevicePlatform.fromString("xbox/console-1"))
  }

  @Test
  fun `empty or slash-only input returns null`() {
    assertNull(TrailblazeDevicePlatform.fromString(""))
    assertNull(TrailblazeDevicePlatform.fromString("/"))
    assertNull(TrailblazeDevicePlatform.fromString("///"))
    assertNull(TrailblazeDevicePlatform.fromString("/foo"))
  }

  @Test
  fun `empty instance-id resolves the platform`() {
    // `web/` is malformed input but the platform segment is well-formed; resolve it
    // rather than failing — the call site that downstream-validates the instance ID
    // will report the missing piece with a better-targeted error.
    assertEquals(TrailblazeDevicePlatform.WEB, TrailblazeDevicePlatform.fromString("web/"))
    assertEquals(TrailblazeDevicePlatform.ANDROID, TrailblazeDevicePlatform.fromString("android/"))
  }
}
