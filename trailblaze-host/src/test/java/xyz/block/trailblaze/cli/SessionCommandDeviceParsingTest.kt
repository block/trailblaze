package xyz.block.trailblaze.cli

import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test
import picocli.CommandLine

/**
 * Pins the picocli parsing contract for `--device` on session lifecycle commands.
 *
 * The historical contract was "`--device` is required at picocli parse time." That moved
 * with the env-var fallback work: `--device` is now optional at the parser level, and the
 * command's `call()` resolves the device via `resolveCliDevice(flag) -> $TRAILBLAZE_DEVICE
 * -> MISUSE`. The MISUSE-on-missing-device exit path is covered alongside the action
 * commands in [CliCommandValidationTest] (so the same conditional-skip pattern around
 * `TRAILBLAZE_DEVICE` applies uniformly). This file pins the parser-level invariants:
 * the flag accepts platform-only and platform/id forms, and is genuinely optional now.
 *
 * Renamed from `SessionCommandRequiredDeviceTest` once the "required" half of the
 * contract moved out of the parser; the class now pins the device-parsing surface
 * only — required-ness is enforced at `call()` time and covered by
 * [CliCommandValidationTest].
 */
class SessionCommandDeviceParsingTest {

  // ---------------------------------------------------------------------------
  // session start
  // ---------------------------------------------------------------------------

  @Test
  fun `session start parses without --device (resolver handles fallback)`() {
    val cmd = SessionStartCommand()
    CommandLine(cmd).parseArgs() // no args — must not throw at parse time
    assertNull(cmd.device)
  }

  @Test
  fun `session start parses successfully when --device is supplied`() {
    val cmd = SessionStartCommand()
    CommandLine(cmd).parseArgs("--device", "android")
    assertEquals("android", cmd.device)
  }

  @Test
  fun `session start accepts platform-id form for --device`() {
    val cmd = SessionStartCommand()
    CommandLine(cmd).parseArgs("--device", "ios/SOME-UUID")
    assertEquals("ios/SOME-UUID", cmd.device)
  }

  // ---------------------------------------------------------------------------
  // session stop
  // ---------------------------------------------------------------------------

  @Test
  fun `session stop parses without --device (resolver handles fallback)`() {
    val cmd = SessionStopCommand()
    CommandLine(cmd).parseArgs() // no args — must not throw at parse time
    assertNull(cmd.device)
  }

  @Test
  fun `session stop parses successfully when --device is supplied`() {
    val cmd = SessionStopCommand()
    CommandLine(cmd).parseArgs("--device", "android/emulator-5554")
    assertEquals("android/emulator-5554", cmd.device)
  }

  // ---------------------------------------------------------------------------
  // session end (deprecated alias)
  // ---------------------------------------------------------------------------

  @Test
  fun `session end parses without --device (resolver handles fallback)`() {
    val cmd = SessionEndCommand()
    CommandLine(cmd).parseArgs() // no args — must not throw at parse time
    assertNull(cmd.device)
  }

  @Test
  fun `session end parses successfully when --device is supplied`() {
    val cmd = SessionEndCommand()
    CommandLine(cmd).parseArgs("--device", "web")
    assertEquals("web", cmd.device)
  }
}
