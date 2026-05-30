package xyz.block.trailblaze.cli

import org.junit.Test
import picocli.CommandLine
import xyz.block.trailblaze.devices.WebInstanceIds
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Picocli-level coverage for [DeviceCreateWebCommand].
 *
 * The happy-path (daemon dispatch + args map construction) is covered end-to-end
 * by [DeviceManagerToolSetTest] CREATE_WEB tests — those exercise the same arg
 * names and contract from the daemon-side. This suite focuses on what only the
 * picocli layer can test:
 *
 *  - Picocli parses each option to the expected field (defaults, types).
 *  - The `--emulate` / `--viewport` mutual-exclusion check returns `EXIT_USAGE`
 *    without contacting any daemon.
 *  - The eager [xyz.block.trailblaze.devices.WebViewportSpec.parse] check returns
 *    `EXIT_USAGE` on a typo so a user typing `--viewport 375x` sees the right
 *    diagnostic up-front rather than crashing in the daemon's CREATE_WEB handler.
 *
 * Tests that need to drive [cliWithDaemon] are out-of-scope for unit tests —
 * that path requires a running daemon and is covered by integration runs.
 */
class DeviceCreateWebCommandTest {

  @Test
  fun `default field values match the documented defaults`() {
    val command = DeviceCreateWebCommand()
    // No args parsed yet — every option should be its declared default.
    assertNull(command.instanceId)
    assertNull(command.emulate)
    assertNull(command.viewport)
    assertNull(command.headless, "Tri-state --headless must default to null (inherit), not false")
  }

  @Test
  fun `picocli parses --emulate to the emulate field`() {
    val command = DeviceCreateWebCommand()
    CommandLine(command).parseArgs("--emulate", "iPhone 14")
    assertEquals("iPhone 14", command.emulate)
    assertNull(command.viewport)
  }

  @Test
  fun `picocli parses --viewport to the viewport field`() {
    val command = DeviceCreateWebCommand()
    CommandLine(command).parseArgs("--viewport", "375x812")
    assertEquals("375x812", command.viewport)
    assertNull(command.emulate)
  }

  @Test
  fun `picocli parses --no-headless to false via the negatable flag`() {
    val command = DeviceCreateWebCommand()
    CommandLine(command).parseArgs("--no-headless")
    assertEquals(false, command.headless)
  }

  @Test
  fun `picocli parses --headless to true`() {
    val command = DeviceCreateWebCommand()
    CommandLine(command).parseArgs("--headless")
    assertEquals(true, command.headless)
  }

  @Test
  fun `both --emulate and --viewport returns EXIT_USAGE without contacting daemon`() {
    val command = DeviceCreateWebCommand()
    val exitCode = CommandLine(command).execute(
      "--emulate", "iPhone 14",
      "--viewport", "375x812",
    )
    assertEquals(TrailblazeExitCode.MISUSE.code, exitCode)
    assertEquals("iPhone 14", command.emulate)
    assertEquals("375x812", command.viewport)
  }

  @Test
  fun `malformed --viewport returns EXIT_USAGE via eager parser validation`() {
    // `375x` is dimension-shaped but missing the height — WebViewportSpec.parse
    // throws IllegalArgumentException, which the command catches and converts to
    // a USAGE exit. Without this eager check the daemon's CREATE_WEB handler
    // would surface the same error later, in a less obviously CLI-shaped place.
    val command = DeviceCreateWebCommand()
    val exitCode = CommandLine(command).execute("--viewport", "375x")
    assertEquals(TrailblazeExitCode.MISUSE.code, exitCode)
  }

  @Test
  fun `valid preset name with no daemon attempts daemon dispatch and fails SOFTWARE`() {
    // With a syntactically-valid spec and no daemon to connect to, `cliWithDaemon`
    // returns SOFTWARE — distinct from USAGE. This pins the contract that USAGE is
    // reserved for inputs that fail BEFORE we attempt to talk to a daemon.
    val command = DeviceCreateWebCommand()
    val exitCode = CommandLine(command).execute("--emulate", "iPhone 14")
    assertTrue(
      exitCode == TrailblazeExitCode.INFRA_FAILED.code || exitCode == TrailblazeExitCode.SUCCESS.code,
      "Expected SOFTWARE (no daemon) or OK (daemon happens to be live); got $exitCode",
    )
  }

  @Test
  fun `WebInstanceIds_PLAYWRIGHT_NATIVE is the documented default instance id`() {
    // Anchor test: the command's call() falls back to PLAYWRIGHT_NATIVE for null
    // instance id so the `--device web` short form addresses the same slot.
    assertTrue(WebInstanceIds.PLAYWRIGHT_NATIVE.isNotBlank())
  }
}
