package xyz.block.trailblaze.cli

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Rule

/**
 * Resolver-tier coverage for the per-terminal device-pin work landed in PR #3611.
 *
 * The unit-level pin store contract lives in [ShellDevicePinStoreTest]. This file
 * tests the layer above it — the CLI helpers in [CliInfrastructure] that wire the
 * pin file to picocli commands and to the resolver. Specifically:
 *
 *  - [readShellPinDevice] / [resolveCliTargetPin] / [writeShellDevicePinIfPossible]
 *    only run when the bash shim forwards `TRAILBLAZE_SHELL_PID`. The forwarding
 *    happens through [CliCallerContext.withCallerEnv]; tests pin the PID per-block.
 *  - The shell-pin tier in [resolveDeviceWithAutodetect] / [resolveCliTargetPin]
 *    sits **between** the env-var tier and the autodetect tier. The env tier
 *    must win, even when a file-pin exists for the same shell — this is the
 *    "manual escape hatch over the file-pin default" contract documented on
 *    [DEVICE_OPTION_DESCRIPTION].
 *  - The audit-log prefix `[ShellDevicePinStore]` is the grep key operators use
 *    to follow pin-lifecycle events. Every call site emits through the same
 *    `SHELL_PIN_LOG_PREFIX` const; the source-level test below pins that
 *    contract because the JVM `Console` impl caches its `out: PrintStream`
 *    reference at class load (so `System.setOut` does NOT redirect
 *    `Console.log` output — runtime capture of those audit lines is
 *    infeasible without reflection or a Console refactor).
 *
 * Filed as follow-up coverage from PR #3611 lead-dev review #5/#7 (test gaps
 * the original PR couldn't backfill because the test fixtures didn't exist yet).
 *
 * **Live-PID requirement.** The production helpers
 * ([writeShellDevicePinIfPossible] et al.) always use the default
 * [ShellDevicePinStore.LivenessProbe] (`ProcessHandle.of(pid).isAlive`),
 * which GCs entries whose PID isn't bound to a running process. Synthetic
 * PIDs (e.g. `99_991L`) get filtered out the moment they're written.
 * So these tests use [ProcessHandle.current].pid as the "shell PID" — that's
 * always alive for the duration of the test.
 *
 * **Daemon-free contract.** No test in this file spins up a daemon or runs
 * autodetect — every resolver entry-point we exercise short-circuits BEFORE
 * the autodetect tier (explicit flag, or env-var tier wins). The full
 * positive-eviction case (pin stale, autodetect returns a different device,
 * audit-log line fires) would require a live daemon for autodetect to surface
 * a non-DaemonUnreachable result, and is out of scope for this unit suite. The
 * source-text test below pins the same line wording the eviction site uses —
 * every pin-lifecycle call site references `SHELL_PIN_LOG_PREFIX`, so wording
 * drift surfaces here even without firing the runtime path.
 */
class CliInfrastructureResolverTest {

  /** Redirects `~/.trailblaze/` writes to a per-test tempdir; restores in @After. */
  @Rule
  @JvmField
  val userHome = UserHomeRule()

  /** The port the pin file is keyed on. Picked by [CliConfigHelper.resolveEffectiveHttpPort]. */
  private val port: Int get() = CliConfigHelper.resolveEffectiveHttpPort()

  /** Convenience: the pin file the helpers and resolver will read/write. */
  private fun pinFile(): File = ShellDevicePinStore.pinFileFor(port)

  /**
   * The test process's own PID. Stays alive for the duration of every test,
   * so the production helpers' default liveness probe
   * (`ProcessHandle.of(pid).isAlive`) won't GC entries keyed on it.
   *
   * Function (not property) to telegraph the per-call `ProcessHandle.current()`
   * lookup — `val get()` looks immutable and surprises readers.
   */
  private fun currentShellPid(): Long = ProcessHandle.current().pid()

  // ---------------------------------------------------------------------------
  // Test gap #1 — audit-log prefix shape for pin-lifecycle events
  //
  // `[ShellDevicePinStore]` is the grep key operators use across
  // `writeShellDevicePinIfPossible`, `clearShellDevicePinIfPossible`,
  // `clearShellDevicePinTargetIfPossible`, and the eviction site in
  // `resolveDeviceWithAutodetect`. All four call sites read the same
  // `SHELL_PIN_LOG_PREFIX` const — the static check below catches drift
  // because runtime capture of `Console.log` is infeasible (see class kdoc).
  // ---------------------------------------------------------------------------

  @Test
  fun `audit-log prefix is shared across every pin-lifecycle call site in CliInfrastructure`() {
    // `[ShellDevicePinStore]` is the grep key operators use across every pin-
    // lifecycle event. The prefix is declared once as `SHELL_PIN_LOG_PREFIX`
    // and reused from five call sites in CliInfrastructure.kt. This static
    // check asserts each Console.log line that references the prefix uses
    // the same const reference (`$SHELL_PIN_LOG_PREFIX`), not a literal
    // string that could drift. We can't capture Console.log output at test
    // time because [Console]'s JVM impl caches its `out` PrintStream at
    // class-load (so `System.setOut` doesn't redirect), but the static check
    // catches the same regression — a refactor that hard-codes the literal
    // `"[ShellDevicePinStore]"` would silently pass a runtime test against
    // the prefix, but fails this source-level pin.
    val source = locateModuleSource(
      modulePath = "trailblaze-host",
      relativeInModule = "src/main/java/xyz/block/trailblaze/cli/CliInfrastructure.kt",
    )
    val body = source.readText()

    // The const itself — its value is the load-bearing wording.
    assertTrue(
      """const val SHELL_PIN_LOG_PREFIX = "[ShellDevicePinStore]"""" in body,
      "SHELL_PIN_LOG_PREFIX const must declare the canonical prefix wording",
    )

    // Every Console.log line referencing pin lifecycle must use the const,
    // not a literal. Pin the five expected reference sites.
    val expectedReferences = listOf(
      """Console.log("${'$'}SHELL_PIN_LOG_PREFIX failed to write pin for pid=""",
      """Console.log("${'$'}SHELL_PIN_LOG_PREFIX cleared target for pid=""",
      """Console.log("${'$'}SHELL_PIN_LOG_PREFIX failed to clear pin target for pid=""",
      """Console.log("${'$'}SHELL_PIN_LOG_PREFIX failed to clear pin for pid=""",
      """Console.log("${'$'}SHELL_PIN_LOG_PREFIX evicted pin: device=""",
    )
    for (ref in expectedReferences) {
      assertTrue(
        ref in body,
        "Expected pin-lifecycle log line referencing SHELL_PIN_LOG_PREFIX: \"$ref\". " +
          "If you intentionally renamed it, update this test in lockstep so the " +
          "operator-grep contract stays explicit.",
      )
    }
  }

  @Test
  fun `clearShellDevicePinTargetIfPossible preserves the device binding`() {
    // Atomic clear of target only — the device side of the pin must survive.
    // Without this, a `--target=clear` invocation would silently wipe the
    // user's device pin as a side-effect.
    val shellPid = currentShellPid()
    CliCallerContext.withCallerEnv(mapOf("TRAILBLAZE_SHELL_PID" to shellPid.toString())) {
      writeShellDevicePinIfPossible("android/emulator-5554", "sampleapp")
    }

    CliCallerContext.withCallerEnv(mapOf("TRAILBLAZE_SHELL_PID" to shellPid.toString())) {
      clearShellDevicePinTargetIfPossible()
    }

    val readBack = ShellDevicePinStore.resolvePin(pinFile(), shellPid)
    val found = readBack as? ShellDevicePinStore.PinLookup.Found
    assertEquals(
      "android/emulator-5554",
      found?.device,
      "device must survive a target-only clear",
    )
    assertNull(found?.target, "target must be nulled by clearShellDevicePinTargetIfPossible")
  }

  @Test
  fun `pin helpers are silent no-ops when TRAILBLAZE_SHELL_PID is unset`() {
    // Older bash wrappers (pre-PR #3611) don't forward the shell PID. Every
    // pin helper must skip silently in that case — never throw, never write
    // a partial file. Same contract as the env-tier short-circuit for
    // `TRAILBLAZE_DEVICE` (older shims see no env forwarding).
    //
    // Asserting "no Console.log line fires" is out of reach here: `Console`'s
    // JVM impl caches `out: PrintStream` at class load and `System.setOut`
    // doesn't redirect it, so a stdout-capture assertion would be trivially
    // true regardless of whether the helpers stayed silent. The load-bearing
    // contract is the file-state invariant — no pin file appears.
    val pinFileBefore = pinFile()
    val existedBefore = pinFileBefore.exists()

    // Empty caller env: no TRAILBLAZE_SHELL_PID at all.
    CliCallerContext.withCallerEnv(emptyMap()) {
      writeShellDevicePinIfPossible("android/emulator-5554", "sampleapp")
      clearShellDevicePinTargetIfPossible()
      clearShellDevicePinIfPossible()
    }

    // No pin file appeared. The helpers are inert when the wrapper can't tell
    // us which shell to pin to.
    assertEquals(existedBefore, pinFileBefore.exists(), "pin file must not be created")
  }

  // ---------------------------------------------------------------------------
  // Test gap #8 — resolver tier order: file-pin loses to env var
  //
  // [resolveDeviceWithAutodetect] explicitly checks [resolveCliDevice] (which
  // handles flag → TRAILBLAZE_DEVICE env) BEFORE consulting the file-pin. The
  // env tier must win, even when a file-pin exists for the same shell PID.
  // Same shape for [resolveCliTargetPin] (env beats file-pin target).
  //
  // The resolver-level test (device tier) passes an unreachable daemon port
  // and relies on the early-exit pattern the existing
  // `resolveDeviceWithAutodetect short-circuits to flag` test established —
  // env-tier hit returns BEFORE the autodetect daemon round-trip, so no live
  // daemon is needed.
  // ---------------------------------------------------------------------------

  @Test
  fun `resolveDeviceWithAutodetect — TRAILBLAZE_DEVICE env beats a file-pin for the same shell`() {
    // Pin device A in the file for the test shell at the SAME port the
    // resolver will read from, then set TRAILBLAZE_DEVICE=B in the caller env
    // and confirm B wins. Writing the pin via the production helper would use
    // [CliConfigHelper.resolveEffectiveHttpPort] (typically 52525), but we
    // pass `port = 1` to the resolver to keep it daemon-free — those two
    // ports must match for the test to actually exercise a competing
    // file-pin. Without that, a regression that checks the file-pin tier
    // BEFORE the env tier would still pass on this test (port 1 has no pin),
    // defeating the regression guard. See Codex review on PR #3616.
    //
    // We use [ShellDevicePinStore.setPin] directly so we can pass the
    // resolver's port explicitly; the production write helper doesn't take
    // a port argument.
    val shellPid = currentShellPid()
    val testPort = 1
    ShellDevicePinStore.setPin(
      file = ShellDevicePinStore.pinFileFor(testPort),
      shellPid = shellPid,
      device = "android/emulator-5554",
    )

    val resolved = CliCallerContext.withCallerEnv(
      mapOf(
        "TRAILBLAZE_SHELL_PID" to shellPid.toString(),
        "TRAILBLAZE_DEVICE" to "ios/SIM-X",
      ),
    ) {
      // Unreachable port — if the resolver tried autodetect we'd hang or get
      // a non-Resolved result. Pinning that the env-tier short-circuit fires
      // first means the test never has to touch a daemon. The pin we wrote
      // above is at this same port, so the file-pin tier WOULD resolve if
      // the env tier didn't short-circuit first — that's what makes this a
      // real tier-order test.
      runBlocking { resolveDeviceWithAutodetect(flag = null, port = testPort, verb = "Test") }
    }

    assertEquals(
      DeviceResolution.Resolved("ios/SIM-X"),
      resolved,
      "TRAILBLAZE_DEVICE must win over the file-pin tier — env is the manual override",
    )
  }

  @Test
  fun `resolveDeviceWithAutodetect — file-pin tier returns synchronously without touching the daemon`() {
    // Regression guard for the perf win that motivated PR #3621. Before the fix,
    // the pin tier validated itself against a `device LIST` round-trip on every
    // call (one full one-shot MCP session — initialize handshake + tool registry
    // hydration + LIST), costing ~2s per pinned-terminal command. The fix is
    // load-bearing only as long as the pin tier short-circuits BEFORE the
    // autodetect daemon connect. Without this test, a future refactor that
    // re-adds eager validation (or wraps the read in a defensive ping) silently
    // re-regresses the latency.
    //
    // Shape: pin a device for this shell, then call the resolver with an
    // unreachable port. If the resolver tries to reach the daemon, the call
    // will either hang on TCP connect-timeout or return `InfraFailed`; only
    // the short-circuited path returns `Resolved(pin)` synchronously.
    val shellPid = currentShellPid()
    val testPort = 1 // unreachable
    ShellDevicePinStore.setPin(
      file = ShellDevicePinStore.pinFileFor(testPort),
      shellPid = shellPid,
      device = "android/emulator-5554",
    )

    val started = System.nanoTime()
    val resolved = CliCallerContext.withCallerEnv(
      mapOf("TRAILBLAZE_SHELL_PID" to shellPid.toString()),
    ) {
      runBlocking { resolveDeviceWithAutodetect(flag = null, port = testPort, verb = "Test") }
    }
    val elapsedMs = (System.nanoTime() - started) / 1_000_000

    assertEquals(
      DeviceResolution.Resolved("android/emulator-5554"),
      resolved,
      "file-pin tier must short-circuit to Resolved without contacting the daemon",
    )
    // Generous bound (autodetect daemon connect-timeout is 2000ms in
    // DaemonClient). If we ever exceed this, the daemon round-trip has crept
    // back into the pin tier.
    assertTrue(
      elapsedMs < 500,
      "pin tier must short-circuit synchronously (<500ms); took ${elapsedMs}ms — " +
        "did a daemon round-trip get re-added to the pin path?",
    )
  }

  @Test
  fun `resolveCliTargetPin — TRAILBLAZE_TARGET env beats a file-pin target for the same shell`() {
    // Symmetric to the device test above: pin (device=A, target=A) in the file
    // for the test shell, then export TRAILBLAZE_TARGET=B and confirm B wins.
    val shellPid = currentShellPid()
    CliCallerContext.withCallerEnv(mapOf("TRAILBLAZE_SHELL_PID" to shellPid.toString())) {
      writeShellDevicePinIfPossible("android/emulator-5554", "default")
    }

    val resolved = CliCallerContext.withCallerEnv(
      mapOf(
        "TRAILBLAZE_SHELL_PID" to shellPid.toString(),
        "TRAILBLAZE_TARGET" to "sampleapp",
      ),
    ) {
      resolveCliTargetPin(flag = null)
    }

    assertEquals("sampleapp", resolved)
  }

  @Test
  fun `resolveCliTargetPin — file-pin target wins when no env or flag is set`() {
    // Negative complement to the env-wins test: with TRAILBLAZE_TARGET unset
    // (empty env map for that key), the file-pin target is the next tier and
    // should be returned. Pins the tier-order contract:
    //
    //   flag → TRAILBLAZE_TARGET env → file-pin target → null
    //
    // Without this test, a refactor that accidentally swaps the env and pin
    // tiers would still pass the env-wins test above (because env is set) but
    // would silently break the file-pin tier — exactly the regression PR #3611
    // was filed against (target lost on daemon restart).
    val shellPid = currentShellPid()
    CliCallerContext.withCallerEnv(mapOf("TRAILBLAZE_SHELL_PID" to shellPid.toString())) {
      writeShellDevicePinIfPossible("android/emulator-5554", "sampleapp")
    }

    val resolved = CliCallerContext.withCallerEnv(
      mapOf("TRAILBLAZE_SHELL_PID" to shellPid.toString()),
    ) {
      resolveCliTargetPin(flag = null)
    }

    assertEquals("sampleapp", resolved)
  }

  @Test
  fun `resolveCliTargetPin — explicit --target=clear short-circuits even with file-pin target`() {
    // The `clear` sentinel must wipe the resolved pin REGARDLESS of what's in
    // the file. Without this short-circuit, a user typing `--target=clear`
    // would have their stated intent silently re-established by the file tier
    // — the exact opposite of clear. Pinning this here so a refactor that
    // moves the sentinel check below the file-tier read surfaces immediately.
    //
    // Also covers case-insensitivity + trim: production normalizes via
    // [normalizeTargetId] (`trim().lowercase()`) before the sentinel check,
    // so `Clear`, `CLEAR`, `"  clear  "` all hit the short-circuit too. Pin
    // them explicitly so a refactor that moves the sentinel check BEFORE
    // normalization surfaces here.
    val shellPid = currentShellPid()
    CliCallerContext.withCallerEnv(mapOf("TRAILBLAZE_SHELL_PID" to shellPid.toString())) {
      writeShellDevicePinIfPossible("android/emulator-5554", "sampleapp")
    }

    val clearVariants = listOf("clear", "Clear", "CLEAR", "  clear  ")
    for (variant in clearVariants) {
      val resolved = CliCallerContext.withCallerEnv(
        mapOf("TRAILBLAZE_SHELL_PID" to shellPid.toString()),
      ) {
        resolveCliTargetPin(flag = variant)
      }
      assertNull(
        resolved,
        "--target=\"$variant\" must short-circuit to null, ignoring any tier below",
      )
    }
  }

  @Test
  fun `readShellPinDevice — returns null when no shell PID is forwarded`() {
    // Coverage symmetric to the helper-write contract: the resolver tier must
    // ALSO skip silently when the wrapper didn't forward TRAILBLAZE_SHELL_PID,
    // even if the pin file happens to have an entry from a different shell.
    // Without this, a fresh-shell harness invocation would resolve to whatever
    // pin some other shell happened to leave behind — exactly the security/
    // correctness problem the PID-scoped pin design exists to avoid.
    val shellPid = currentShellPid()
    CliCallerContext.withCallerEnv(mapOf("TRAILBLAZE_SHELL_PID" to shellPid.toString())) {
      writeShellDevicePinIfPossible("android/emulator-5554", null)
    }

    val resolved = CliCallerContext.withCallerEnv(emptyMap()) {
      readShellPinDevice(port)
    }
    assertNull(resolved, "No PID forwarded → resolver must skip the file-pin tier entirely")
  }
}
