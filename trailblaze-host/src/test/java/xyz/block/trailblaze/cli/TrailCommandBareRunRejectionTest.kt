package xyz.block.trailblaze.cli

import picocli.CommandLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the contract that `trailblaze run` with no positional argument is a USAGE error,
 * not a silent fan-out across `<workspace>/trails/`.
 *
 * Pre-fix behavior: a bare `trailblaze run` defaulted to `<workspace>/trails/`, expanded
 * recursively, and started executing every `*.trail.yaml` / `blaze.yaml` it could find.
 * Curious "what does this do?" taps would emit ~60 directory-walk warnings and then start
 * running real tests on whichever device happened to be connected — a footgun the first
 * time anyone hit the CLI.
 *
 * Two complementary tests pin the same contract from different angles:
 *  - The direct-construction case ([`bare run exits MISUSE with the structured envelope`])
 *    calls `cmd.call()` against a hand-built `TrailCommand`, which never goes through
 *    picocli. This exercises the early-return logic in isolation and is the canonical
 *    unit-level shape. It depends on `wasInvokedViaTrailAlias()` being hardened against
 *    an uninitialized `commandSpec`.
 *  - The picocli-dispatch case ([`bare run via CommandLine execute also exits MISUSE`])
 *    drives the same code through `CommandLine.execute("run")` on the real root command
 *    tree. A future refactor that wraps `call()` behind picocli's dispatch (e.g. adding
 *    an `IExitCodeExceptionMapper` or routing through a different handler) could regress
 *    the integration path without the unit test noticing; the second case guards that.
 */
class TrailCommandBareRunRejectionTest {

  @Test
  fun `bare run exits MISUSE with the structured envelope`() {
    // `verbose = true` short-circuits the [Console.enableQuietMode] call inside
    // [TrailCommand.call] — that mutator flips a JVM-global state flag that other
    // CLI tests in the same JVM rely on, so leaving the default `false` here would
    // make test order observable. The verbose flag has no effect on the bare-args
    // rejection path itself.
    val cmd = TrailCommand().apply {
      trailFiles = emptyList()
      verbose = true
    }
    val (exit, stderr) = captureStderr { cmd.call() }
    assertEquals(
      TrailblazeExitCode.MISUSE.code,
      exit,
      "Bare `trailblaze run` must exit MISUSE (3); anything else means the early " +
        "rejection regressed and the command will silently fan out across trails/ again",
    )
    assertEnvelopeLines(stderr)
  }

  @Test
  fun `bare run via CommandLine execute also exits MISUSE`() {
    // Dispatches through the full root → subcommand picocli chain, the same way an
    // end-user invocation of `trailblaze run` lands. If a future change moves the
    // early-return behind a picocli handler (or maps the exit code to something
    // else en route), this assertion fires loudly.
    val root = CommandLine(
      TrailblazeCliCommand(
        appProvider = { error("appProvider must not be invoked when bare-args rejection fires") },
        configProvider = { error("configProvider must not be invoked when bare-args rejection fires") },
      ),
    ).setCaseInsensitiveEnumValuesAllowed(true)
    // `--verbose` short-circuits the JVM-global `Console.enableQuietMode` flip the
    // same way the direct-construction test does. See the sibling test's comment for
    // the rationale.
    val (exit, stderr) = captureStderr { root.execute("run", "--verbose") }
    assertEquals(
      TrailblazeExitCode.MISUSE.code,
      exit,
      "Bare `trailblaze run` via picocli dispatch must also exit MISUSE (3); a drift " +
        "between the direct-call and picocli-dispatch exit codes means the rejection " +
        "is short-circuiting one path but not the other",
    )
    assertEnvelopeLines(stderr)
  }

  /**
   * Pin the structured envelope shape — header + reason + hint — so a future refactor
   * can't collapse one [Console.error] call without us noticing. Use line-level matching
   * (not substring of the whole blob) because the three lines are emitted as three
   * separate Console.error calls; merging two of them into one line would still satisfy
   * a flat-substring check.
   *
   * `verb = "Trail run"` matches every other [reportCliError] callsite in `TrailCommand`
   * — `✗ Trail run failed` is the shared envelope header. Diverging on the verb would
   * break grep-style downstream automations that key on the canonical phrase.
   */
  private fun assertEnvelopeLines(stderr: String) {
    val lines = stderr.lines()
    assertTrue(
      lines.any { it == "✗ Trail run failed" },
      "Stderr must contain the envelope header line `✗ Trail run failed` " +
        "(matching the verb used at every other reportCliError site in this file); " +
        "got: <<$stderr>>",
    )
    assertTrue(
      lines.any { it.startsWith("  reason: no trail file or directory specified") },
      "Stderr must contain the `reason:` line; got: <<$stderr>>",
    )
    assertTrue(
      lines.any { it.startsWith("  hint:") && "trails/" in it },
      "Stderr must contain a `hint:` line pointing the user at a directory fan-out " +
        "(e.g. `trailblaze run trails/`); got: <<$stderr>>",
    )
  }
}
