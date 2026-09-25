package xyz.block.trailblaze.cli

import org.junit.Rule
import picocli.CommandLine
import xyz.block.trailblaze.util.Console
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the contract that a CLI entry point which closes the internal [Console.log] channel also
 * reopens it.
 *
 * Quiet mode is one process-global flag. A one-shot CLI process can switch it on and never think
 * about it again — but the same code runs in the daemon and in this test JVM, where a command that
 * leaves the flag set silences everything scheduled after it. That is how the bug was found: an
 * assertion about console output was green when its class ran alone and red in a full run, and the
 * red landed on an innocent class.
 */
class CliQuietModeRestoreTest {

  @Rule
  @JvmField
  val quietMode = QuietModeRule()

  @Test
  fun `quietUnlessVerbose closes the channel for the body and reopens it after`() {
    var quietInside = false

    quietUnlessVerbose(verbose = false) { quietInside = Console.isQuietMode() }

    assertTrue(quietInside, "the body must run with the internal channel closed")
    assertFalse(Console.isQuietMode(), "and the channel must be reopened when the body returns")
  }

  @Test
  fun `quietUnlessVerbose leaves an already-quiet caller quiet`() {
    // A nested entry point finishing must not make a caller loud that was quiet before it —
    // a dispatched command calling a connection helper is exactly this shape.
    Console.enableQuietMode()
    try {
      quietUnlessVerbose(verbose = false) {}

      assertTrue(Console.isQuietMode(), "a caller's own quiet mode must survive the nested scope")
    } finally {
      Console.disableQuietMode()
    }
  }

  @Test
  fun `run restores quiet mode after a non-verbose misuse exit`() {
    // `verbose = false` is the case that used to leak. The misuse path needs no daemon and no
    // device, and it still runs through the same wrapper as a real invocation.
    val command = TrailCommand().apply {
      trailFiles = emptyList()
      verbose = false
    }

    val (exit, _) = captureStderr { command.call() }

    assertEquals(TrailblazeExitCode.MISUSE.code, exit, "precondition: the command took the misuse path")
    assertFalse(Console.isQuietMode(), "`run` must not decide how loud the rest of the JVM is")
  }

  @Test
  fun `session start restores quiet mode after a non-verbose misuse exit`() {
    val command = SessionStartCommand().apply {
      mode = "not-a-mode"
      verbose = false
    }

    val (exit, _) = captureStderr { command.call() }

    assertEquals(TrailblazeExitCode.MISUSE.code, exit, "precondition: the command took the misuse path")
    assertFalse(Console.isQuietMode(), "`session start` must not decide how loud the rest of the JVM is")
  }

  /**
   * The half the restore assertions cannot see. Delete the wrapper from an entry point and quiet
   * mode is never switched on, so every `assertFalse(isQuietMode())` above still passes — the leak
   * is gone and so is the feature. `report` is not [QuietUnlessVerbose], so the dispatch policy
   * cannot stand in for its own wrapper here.
   */
  @Test
  fun `a non-verbose report builds its app with the channel closed`() {
    var quietWhileBuildingApp: Boolean? = null

    val root = TrailblazeCliCommand(
      appProvider = {
        quietWhileBuildingApp = Console.isQuietMode()
        // Building a real desktop app here would load the workspace; the scope is already
        // observed, so stop.
        throw StopAfterAppProvider()
      },
      configProvider = { error("config must not be needed to reach the app-building step") },
    )

    runCatching { CommandLine(root).execute("report", "--id", "does-not-exist") }

    assertEquals(
      true,
      quietWhileBuildingApp,
      "report must build the app with the internal channel closed, or its workspace-loading " +
        "breadcrumbs land in the output a person asked for",
    )
    assertFalse(Console.isQuietMode(), "and the channel must be reopened afterwards")
  }

  private class StopAfterAppProvider : RuntimeException("stop: the quiet scope has been observed")
}
