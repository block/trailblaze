package xyz.block.trailblaze.cli

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

/**
 * Coverage for the bash-wrapper env-forwarding contract added in PR #3611.
 *
 * The wrapper script (`scripts/trailblaze` under the OSS-layout root) exports
 * two env vars before dispatching to the JVM:
 *
 *  - `TRAILBLAZE_SHELL_PID` = `${TRAILBLAZE_SHELL_PID:-$PPID}` — pin the
 *    calling shell's PID so the JVM-side [ShellDevicePinStore] can key the
 *    file pin per-terminal. Respects any pre-set value so nested invocations
 *    inherit the outermost shell's identity.
 *  - `TRAILBLAZE_INTERACTIVE` = `1` when `[ -t 0 ]` (stdin is a tty), `0`
 *    otherwise. Gates the `device connect` non-tty warning that steers
 *    fresh-shell agent harnesses (Claude Code, Cursor, Codex, CI) toward
 *    `--device <id>` per call.
 *
 * Both vars feed [CliCallerContext.callerEnv] via the `/cli/exec` bridge in
 * `ipc_try_forward`. Without the wrapper exporting them, the JVM-side helpers
 * `writeShellDevicePinIfPossible` / `isInteractiveCaller` silently no-op —
 * the file-pin design degrades to "older wrapper" behavior, which is invisible
 * to anyone running `./trailblaze` from a checkout.
 *
 * **Why a subprocess test.** The bash logic is the wrapper's own contract, not
 * something the JVM is enforcing — a regression here lives in `.sh` source
 * and only surfaces at runtime when a user actually runs the wrapper. A
 * subprocess-driven test catches the regression at PR-check time.
 *
 * **Static-form regression guard.** The wrapper's bash logic is checked AS-IS
 * via [`wrapperPath`] — the same script that ships to users. We avoid copying
 * the prelude into a test fixture (which would silently drift from the real
 * script). The wrapper's [`tb_run` family is overridable](trailblaze:171-194)
 * with `if ! declare -f tb_run`, so the test pre-defines no-op overrides
 * before sourcing — keeps the wrapper from spawning a JVM during the test.
 *
 * Filed as follow-up coverage from PR #3611 lead-dev review #6.
 */
class TrailblazeWrapperEnvTest {

  /** Locate the wrapper script via the OSS-layout-aware walk-up. */
  private fun locateWrapperScript(): File =
    locateUnderOssRoot(relativeFromOssRoot = "scripts/trailblaze")

  /**
   * Run [bashScript] via `bash -c` with [extraEnv] forwarded, return
   * (exitCode, stdout, stderr). 30-second timeout so a hung subprocess fails
   * the test rather than the whole CI run.
   */
  private fun runBash(bashScript: String, extraEnv: Map<String, String>): Triple<Int, String, String> {
    val pb = ProcessBuilder("bash", "-c", bashScript)
    pb.environment().apply {
      // Reset to a controlled minimum so the test isn't perturbed by the
      // host's TRAILBLAZE_* env that might be left over from a previous run.
      // Keep PATH so curl/jq etc. resolve.
      remove("TRAILBLAZE_SHELL_PID")
      remove("TRAILBLAZE_INTERACTIVE")
      remove("TRAILBLAZE_DEVICE")
      remove("TRAILBLAZE_TARGET")
      remove("TRAILBLAZE_IPC")
      putAll(extraEnv)
    }
    val proc = pb.start()
    val ok = proc.waitFor(30, TimeUnit.SECONDS)
    if (!ok) {
      proc.destroyForcibly()
      // Give the SIGKILL a moment to land so the bufferedReader doesn't
      // wedge waiting on stream EOF from a not-yet-reaped process.
      proc.waitFor(5, TimeUnit.SECONDS)
      throw AssertionError("bash subprocess timed out — wrapper logic may be stuck")
    }
    val stdout = proc.inputStream.bufferedReader().readText()
    val stderr = proc.errorStream.bufferedReader().readText()
    return Triple(proc.exitValue(), stdout, stderr)
  }

  @Test
  fun `wrapper exports TRAILBLAZE_SHELL_PID from PPID when caller did not set one`() {
    assumeTrue("bash required for wrapper tests", File("/bin/bash").exists())
    val wrapper = locateWrapperScript()

    // Override tb_run* BEFORE sourcing so the wrapper's `if ! declare -f tb_run`
    // guard keeps our no-ops, preventing any JVM spawn. Set TRAILBLAZE_IPC=0
    // to skip the daemon-IPC fast path (which would call `curl`). With empty
    // args, the wrapper falls to its `else: tb_run_quiet --help` branch and
    // exits cleanly via our no-op.
    val script = """
      set -e
      tb_run() { :; }
      tb_run_quiet() { :; }
      tb_run_background() { :; }
      tb_run_background_quiet() { :; }
      tb_run_exec_quiet() { :; }
      export TRAILBLAZE_IPC=0
      # Run the wrapper in a subshell so its 'exit' doesn't kill us, but the
      # exported env DOES propagate back via stdout. Trick: source it in a
      # subshell and print the resulting env.
      ( source '${wrapper.absolutePath}' >/dev/null 2>&1 || true
        printf 'WRAPPER_SHELL_PID=%s\n' "${'$'}TRAILBLAZE_SHELL_PID"
        printf 'WRAPPER_INTERACTIVE=%s\n' "${'$'}TRAILBLAZE_INTERACTIVE"
      )
    """.trimIndent()

    val (exitCode, stdout, stderr) = runBash(script, emptyMap())
    // Assert exit BEFORE parsing — a non-zero exit (e.g. java missing from
    // PATH on a constrained CI agent) would otherwise surface as a confusing
    // "WRAPPER_SHELL_PID not in stdout" rather than the actual cause.
    assertEquals(0, exitCode, "bash harness must exit cleanly; stderr=\n$stderr")

    // Without a pre-set TRAILBLAZE_SHELL_PID, the wrapper expands `${TRAILBLAZE_SHELL_PID:-$PPID}`
    // to the PPID of the wrapper invocation — which is the PID of our `bash -c`
    // subprocess (since we sourced in a sub-shell, the source's PPID is the
    // outer bash -c). Whatever the exact integer is, it must be a positive
    // integer — that's the load-bearing contract for the JVM-side helpers.
    val pidLine = stdout.lineSequence().firstOrNull { it.startsWith("WRAPPER_SHELL_PID=") }
      ?: error("WRAPPER_SHELL_PID not in stdout:\n$stdout")
    val pid = pidLine.removePrefix("WRAPPER_SHELL_PID=").trim().toLongOrNull()
    assertTrue(
      pid != null && pid > 0,
      "TRAILBLAZE_SHELL_PID must default to a positive integer PPID; got '$pidLine'",
    )
  }

  @Test
  fun `wrapper respects a pre-set TRAILBLAZE_SHELL_PID (nested invocations)`() {
    assumeTrue("bash required for wrapper tests", File("/bin/bash").exists())
    val wrapper = locateWrapperScript()

    val preSetPid = "424242"
    val script = """
      set -e
      tb_run() { :; }
      tb_run_quiet() { :; }
      tb_run_background() { :; }
      tb_run_background_quiet() { :; }
      tb_run_exec_quiet() { :; }
      export TRAILBLAZE_IPC=0
      ( source '${wrapper.absolutePath}' >/dev/null 2>&1 || true
        printf 'WRAPPER_SHELL_PID=%s\n' "${'$'}TRAILBLAZE_SHELL_PID"
      )
    """.trimIndent()

    val (exitCode, stdout, stderr) = runBash(
      script,
      mapOf("TRAILBLAZE_SHELL_PID" to preSetPid),
    )
    assertEquals(0, exitCode, "bash harness must exit cleanly; stderr=\n$stderr")
    val pidLine = stdout.lineSequence().firstOrNull { it.startsWith("WRAPPER_SHELL_PID=") }
      ?: error("WRAPPER_SHELL_PID not in stdout:\n$stdout")
    assertEquals(
      "WRAPPER_SHELL_PID=$preSetPid",
      pidLine.trim(),
      "Pre-set TRAILBLAZE_SHELL_PID must survive a nested invocation",
    )
  }

  @Test
  fun `wrapper sets TRAILBLAZE_INTERACTIVE=0 when stdin is not a tty`() {
    assumeTrue("bash required for wrapper tests", File("/bin/bash").exists())
    val wrapper = locateWrapperScript()

    // ProcessBuilder gives us a stdin connected to the parent — definitely not
    // a tty in the gradle test runner. This is the typical agent-harness
    // configuration (Claude Code Bash tool, Cursor shell, Codex, CI). The
    // wrapper must detect non-tty and set TRAILBLAZE_INTERACTIVE=0 so the
    // JVM-side `device connect` flow fires the "fresh-shell harness" warning.
    val script = """
      set -e
      tb_run() { :; }
      tb_run_quiet() { :; }
      tb_run_background() { :; }
      tb_run_background_quiet() { :; }
      tb_run_exec_quiet() { :; }
      export TRAILBLAZE_IPC=0
      ( source '${wrapper.absolutePath}' >/dev/null 2>&1 || true
        printf 'WRAPPER_INTERACTIVE=%s\n' "${'$'}TRAILBLAZE_INTERACTIVE"
      )
    """.trimIndent()

    val (exitCode, stdout, stderr) = runBash(script, emptyMap())
    assertEquals(0, exitCode, "bash harness must exit cleanly; stderr=\n$stderr")
    val interactiveLine = stdout.lineSequence().firstOrNull { it.startsWith("WRAPPER_INTERACTIVE=") }
      ?: error("WRAPPER_INTERACTIVE not in stdout:\n$stdout")
    assertEquals(
      "WRAPPER_INTERACTIVE=0",
      interactiveLine.trim(),
      "Without a stdin tty, the wrapper must set TRAILBLAZE_INTERACTIVE=0",
    )
  }

  @Test
  fun `wrapper respects a pre-set TRAILBLAZE_INTERACTIVE (CI override)`() {
    assumeTrue("bash required for wrapper tests", File("/bin/bash").exists())
    val wrapper = locateWrapperScript()

    // A CI script or future override tool can pre-set TRAILBLAZE_INTERACTIVE
    // (e.g. force the warning on or off for an unattended-but-classified-as-
    // interactive run). The wrapper must keep that pre-set value intact —
    // mirroring the `${VAR:-default}` shape used for TRAILBLAZE_SHELL_PID.
    val script = """
      set -e
      tb_run() { :; }
      tb_run_quiet() { :; }
      tb_run_background() { :; }
      tb_run_background_quiet() { :; }
      tb_run_exec_quiet() { :; }
      export TRAILBLAZE_IPC=0
      ( source '${wrapper.absolutePath}' >/dev/null 2>&1 || true
        printf 'WRAPPER_INTERACTIVE=%s\n' "${'$'}TRAILBLAZE_INTERACTIVE"
      )
    """.trimIndent()

    val (exitCode, stdout, stderr) = runBash(
      script,
      mapOf("TRAILBLAZE_INTERACTIVE" to "1"),
    )
    assertEquals(0, exitCode, "bash harness must exit cleanly; stderr=\n$stderr")
    val interactiveLine = stdout.lineSequence().firstOrNull { it.startsWith("WRAPPER_INTERACTIVE=") }
      ?: error("WRAPPER_INTERACTIVE not in stdout:\n$stdout")
    assertEquals("WRAPPER_INTERACTIVE=1", interactiveLine.trim())
  }

  // ---------------------------------------------------------------------------
  // Static-form regression guard: the env-forwarding allowlist in
  // `ipc_try_forward` is the daemon-bridge half of the contract. If a future
  // refactor drops `TRAILBLAZE_SHELL_PID` or `TRAILBLAZE_INTERACTIVE` from the
  // jq-built env_json payload, the bash logic above still passes (it tests the
  // wrapper's prelude exports) — but the daemon's `CliCallerContext.callerEnv`
  // never sees the values, and every forwarded subcommand silently degrades to
  // older-wrapper behavior. This static check anchors the bridge.
  // ---------------------------------------------------------------------------

  @Test
  fun `wrapper allowlist in ipc_try_forward forwards TRAILBLAZE_SHELL_PID and TRAILBLAZE_INTERACTIVE`() {
    val wrapper = locateWrapperScript()
    val body = wrapper.readText()
    // Match the literal jq line that builds the env_json payload for each var.
    // Anchoring on the `+ {TRAILBLAZE_*: $v}` token is robust to formatting
    // changes around it (whitespace, comments) while still failing if the
    // entry is dropped from the allowlist.
    assertTrue(
      "+ {TRAILBLAZE_SHELL_PID: \$v}" in body,
      "ipc_try_forward must forward TRAILBLAZE_SHELL_PID through env_json — " +
        "without it, the JVM-side file-pin tier is invisible from the daemon path",
    )
    assertTrue(
      "+ {TRAILBLAZE_INTERACTIVE: \$v}" in body,
      "ipc_try_forward must forward TRAILBLAZE_INTERACTIVE through env_json — " +
        "without it, the non-tty warning never fires from a forwarded subcommand",
    )
  }
}
