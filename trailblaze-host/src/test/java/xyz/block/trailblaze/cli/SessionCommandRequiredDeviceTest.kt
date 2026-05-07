package xyz.block.trailblaze.cli

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Test
import picocli.CommandLine
import picocli.CommandLine.MissingParameterException

/**
 * Locks in the contract that `--device` is required on every session lifecycle command.
 *
 * Why this exists: the help-text baseline test (`CliHelpBaselineTest`) verifies that the
 * rendered `--help` output marks `-d=<device>` as required syntactically (no brackets in
 * the usage line), but that's a check on the rendered string. These tests cover the
 * actual parser behavior — what happens when a user runs the command without the flag —
 * and the removal of the historical `cliDevicePlatform` config-default fallback that
 * `session start` used to honor.
 */
class SessionCommandRequiredDeviceTest {

  // ---------------------------------------------------------------------------
  // session start
  // ---------------------------------------------------------------------------

  @Test
  fun `session start without --device throws MissingParameterException`() {
    val cmd = SessionStartCommand()
    val cmdLine = CommandLine(cmd)

    val ex = assertFailsWith<MissingParameterException> { cmdLine.parseArgs() }
    assertTrue(
      ex.message?.contains("--device") == true,
      "Expected the error to call out --device specifically, got: ${ex.message}",
    )
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
  fun `session stop without --device throws MissingParameterException`() {
    val cmd = SessionStopCommand()
    val cmdLine = CommandLine(cmd)

    val ex = assertFailsWith<MissingParameterException> { cmdLine.parseArgs() }
    assertTrue(
      ex.message?.contains("--device") == true,
      "Expected the error to call out --device specifically, got: ${ex.message}",
    )
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
  fun `session end without --device throws MissingParameterException`() {
    val cmd = SessionEndCommand()
    val cmdLine = CommandLine(cmd)

    val ex = assertFailsWith<MissingParameterException> { cmdLine.parseArgs() }
    assertTrue(
      ex.message?.contains("--device") == true,
      "Expected the error to call out --device specifically, got: ${ex.message}",
    )
  }

  @Test
  fun `session end parses successfully when --device is supplied`() {
    val cmd = SessionEndCommand()
    CommandLine(cmd).parseArgs("--device", "web")
    assertEquals("web", cmd.device)
  }
}
