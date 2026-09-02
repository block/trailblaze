package xyz.block.trailblaze.cli

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Pins the two `run --bind` rejections that happen in the CLI itself, before any device or LLM
 * resolution: a malformed/duplicated bind entry, and `--bind` combined with a device fan-out.
 *
 * Both must exit MISUSE from `call()` rather than reaching the daemon, because the failure a
 * late rejection produces is a run that has already claimed devices.
 */
class TrailCommandDeviceBindMisuseTest {

  private fun trailFile(): File =
    File.createTempFile("trail-bind-misuse-", ".trail.yaml").apply {
      deleteOnExit()
      writeText("config: {}\ntrail:\n  - step: noop\n")
    }

  /**
   * `verbose = true` short-circuits [Console.enableQuietMode], which flips a JVM-global flag other
   * CLI tests in this JVM read — same rationale as `TrailCommandBareRunRejectionTest`.
   */
  private fun runCommand(configure: TrailCommand.() -> Unit): Pair<Int, String> {
    val cmd = TrailCommand().apply {
      trailFiles = listOf(trailFile())
      verbose = true
      configure()
    }
    return captureStderr { cmd.call() }
  }

  @Test
  fun `a malformed bind entry exits MISUSE`() {
    val (exit, stderr) = runCommand { deviceBinds = listOf("emulator-5562") }
    assertEquals(TrailblazeExitCode.MISUSE.code, exit)
    assertTrue(
      "--bind entry" in stderr && "NAME=DEVICE_ID" in stderr,
      "must name the offending entry and the expected shape; got: <<$stderr>>",
    )
  }

  @Test
  fun `a duplicated bind name exits MISUSE`() {
    val (exit, stderr) = runCommand {
      deviceBinds = listOf("buyer=emulator-5562", "buyer=emulator-5564")
    }
    assertEquals(TrailblazeExitCode.MISUSE.code, exit)
    assertTrue("buyer" in stderr, "must name the duplicated bind; got: <<$stderr>>")
  }

  @Test
  fun `bind plus all-devices exits MISUSE`() {
    val (exit, stderr) = runCommand {
      deviceBinds = listOf("buyer=emulator-5562")
      allDevices = true
    }
    assertEquals(TrailblazeExitCode.MISUSE.code, exit)
    assertTrue(
      stderr.lines().any { it == "✗ Trail run failed" },
      "must use the shared error envelope header; got: <<$stderr>>",
    )
    assertTrue(
      stderr.lines().any { it.startsWith("  reason:") && "--all-devices" in it },
      "the reason must say which fan-out flag conflicts; got: <<$stderr>>",
    )
  }

  @Test
  fun `bind plus several named devices exits MISUSE`() {
    val (exit, stderr) = runCommand {
      deviceBinds = listOf("buyer=emulator-5562")
      devices = listOf("emulator-5554", "emulator-5556")
    }
    assertEquals(TrailblazeExitCode.MISUSE.code, exit)
    assertTrue(
      stderr.lines().any { it.startsWith("  reason:") && "--device" in it },
      "the reason must say which fan-out flag conflicts; got: <<$stderr>>",
    )
  }

  @Test
  fun `bind with a single named device gets past the fan-out guard`() {
    // The control for the rejections above: one --device plus binds is the SUPPORTED shape (a cast
    // with one start device), so it must get PAST both guards. Without this, a guard that rejected
    // every --bind would satisfy all four tests above.
    //
    // Past the guard the next statement is `parent.getEffectivePort()`, and `parent` is a
    // picocli-injected lateinit this directly-constructed command never sets — so reaching it
    // throws UninitializedPropertyAccessException, before any device or daemon I/O. Catching only
    // that type is what makes this a proof: a broader catch would read a throw from the bind guard
    // itself (or from anything before it) as success.
    assertFailsWith<UninitializedPropertyAccessException>(
      "expected to reach `parent.getEffectivePort()`, which is the first statement past the bind " +
        "guards; any other outcome means the supported shape did not get through",
    ) {
      runCommand {
        deviceBinds = listOf("buyer=emulator-5562")
        devices = listOf("emulator-5554")
      }
    }
  }
}
