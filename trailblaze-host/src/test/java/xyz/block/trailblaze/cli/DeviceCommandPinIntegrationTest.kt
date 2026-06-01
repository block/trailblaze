package xyz.block.trailblaze.cli

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Rule

/**
 * Integration coverage for the per-terminal device-pin helpers that wire the
 * picocli `device {connect,disconnect,rebind}` commands and `--target=clear`
 * flow to [ShellDevicePinStore].
 *
 * **Why helper-level, not call()-level.** Each of [DeviceConnectCommand],
 * [DeviceDisconnectCommand], and [DeviceRebindCommand] enters
 * [cliWithDaemon] before reaching the pin write — so a true picocli `.call()`
 * test would need a live daemon to satisfy the MCP round-trip. The relevant
 * production code in each command's `.call()` is one line:
 *
 *  - `DeviceConnectCommand.call()`     → `writeShellDevicePinIfPossible(deviceIdString, resolvedTarget)`
 *  - `DeviceRebindCommand.call()`      → `writeShellDevicePinIfPossible(deviceIdString, newTarget)`
 *  - `DeviceDisconnectCommand.call()`  → `clearShellDevicePinIfPossible()`
 *  - `cliReusableWithDevice` / `SessionStartCommand` `--target=clear` branch
 *                                     → `clearShellDevicePinTargetIfPossible()`
 *  - `DeviceConnectCommand.call()` warning gate → `isInteractiveCaller()`
 *
 * These tests drive each helper directly under a controlled
 * [CliCallerContext.withCallerEnv] block, asserting the same observable on-disk
 * + stderr behavior the picocli paths would produce. The picocli-parsing layer
 * (positional args, `--target` / `-t` parsing, required-flag rules) is already
 * covered in [DeviceConnectCommandTest]; this file covers the file-pin side
 * effects that test file deliberately doesn't drive.
 *
 * Filed as follow-up coverage from PR #3611 lead-dev review #6/#8.
 *
 * **Live-PID requirement.** The production helpers always use the default
 * [ShellDevicePinStore.LivenessProbe] (`ProcessHandle.of(pid).isAlive`), which
 * filters out entries keyed on a dead PID during every mutate. So tests use
 * [ProcessHandle.current].pid as their "shell PID" wherever a single PID is
 * needed; the multi-shell test spawns a long-sleeping subprocess for its
 * second PID and tears it down in `@After` to keep the test from leaking
 * processes if it fails partway.
 *
 * The `user.home` redirect that isolates pin writes from the real
 * `~/.trailblaze/` lives in [UserHomeRule], shared with
 * [CliInfrastructureResolverTest].
 */
class DeviceCommandPinIntegrationTest {

  @Rule
  @JvmField
  val userHome = UserHomeRule()

  /**
   * Holds onto any subprocess this test spawned (e.g. for multi-PID coverage),
   * so we can reap it in `@After` even when the test throws partway.
   */
  private val spawnedProcesses = mutableListOf<Process>()

  @After
  fun reapSpawnedProcesses() {
    // Reap any subprocesses the test spawned. destroyForcibly + a short
    // waitFor so we don't leak zombies across the test class.
    spawnedProcesses.forEach { it.destroyForcibly() }
    spawnedProcesses.clear()
  }

  /** The port the pin file is keyed on. */
  private val port: Int get() = CliConfigHelper.resolveEffectiveHttpPort()

  private fun pinFile(): File = ShellDevicePinStore.pinFileFor(port)

  /**
   * PID of the test process itself — guaranteed alive for the duration.
   * Function (not property) to telegraph the per-call lookup; a `val get()`
   * looks immutable and surprises readers.
   */
  private fun currentShellPid(): Long = ProcessHandle.current().pid()

  /**
   * Spawn a long-sleeping subprocess and return its PID. Used by the multi-PID
   * test as a "second shell" — its PID is alive for the duration, so the
   * pin's `isPidAlive` GC doesn't filter it out, and `@After` reaps it.
   */
  private fun spawnSleeperPid(): Long {
    // 5 minutes is well beyond any reasonable test runtime; reaped in @After.
    val proc = ProcessBuilder("sleep", "300")
      .redirectOutput(ProcessBuilder.Redirect.DISCARD)
      .redirectError(ProcessBuilder.Redirect.DISCARD)
      .start()
    spawnedProcesses += proc
    return proc.pid()
  }

  // ---------------------------------------------------------------------------
  // Test gap #4 — DeviceConnectCommand writes the pin via the helper
  // ---------------------------------------------------------------------------

  @Test
  fun `writeShellDevicePinIfPossible writes (device, target) for the forwarded shell PID`() {
    // [DeviceConnectCommand.call] step 5 calls `writeShellDevicePinIfPossible(
    // deviceIdString, resolvedTarget)` immediately after the daemon-side bind.
    // Driving the helper directly under the same `withCallerEnv` shape the
    // bash shim sets up exercises the exact production code path.
    val shellPid = currentShellPid()
    val device = "android/emulator-5554"
    val target = "sampleapp"

    CliCallerContext.withCallerEnv(mapOf("TRAILBLAZE_SHELL_PID" to shellPid.toString())) {
      writeShellDevicePinIfPossible(device, target)
    }

    val stored = ShellDevicePinStore.resolvePin(pinFile(), shellPid)
    val found = stored as? ShellDevicePinStore.PinLookup.Found
    assertNotNull(found, "Pin must be written for the forwarded shell PID")
    assertEquals(device, found.device)
    assertEquals(target, found.target)
  }

  @Test
  fun `writeShellDevicePinIfPossible writes device only when target is null (bare connect)`() {
    // `trailblaze device connect android` (no --target) sets `target = null` in
    // picocli land; [normalizeTargetId] returns null for that, and the call
    // site passes that null straight through. The pin must record a null target
    // so the resolver falls through to env / config / default on each action
    // command rather than re-applying a stale value.
    val shellPid = currentShellPid()
    val device = "ios/SIM-X"

    CliCallerContext.withCallerEnv(mapOf("TRAILBLAZE_SHELL_PID" to shellPid.toString())) {
      writeShellDevicePinIfPossible(device, null)
    }

    val found = ShellDevicePinStore.resolvePin(pinFile(), shellPid)
      as? ShellDevicePinStore.PinLookup.Found
    assertNotNull(found)
    assertEquals(device, found.device)
    assertNull(found.target)
  }

  // ---------------------------------------------------------------------------
  // Test gap #3 — DeviceRebindCommand writes the NEW target to the pin
  //
  // `DeviceRebindCommand.call()` ends with
  // `writeShellDevicePinIfPossible(deviceIdString, newTarget)`. The pin's
  // device-id half doesn't move (rebind is target-only), but the target field
  // must overwrite whatever was there.
  // ---------------------------------------------------------------------------

  @Test
  fun `writeShellDevicePinIfPossible overwrites existing target while preserving device`() {
    val shellPid = currentShellPid()
    // Initial pin: target A
    CliCallerContext.withCallerEnv(mapOf("TRAILBLAZE_SHELL_PID" to shellPid.toString())) {
      writeShellDevicePinIfPossible("android/emulator-5554", "default")
    }
    val first = ShellDevicePinStore.resolvePin(pinFile(), shellPid)
      as? ShellDevicePinStore.PinLookup.Found
    assertEquals("default", first?.target, "precondition: initial target must be 'default'")

    // Re-pin: same device, new target — mirrors `device rebind --target sampleapp`.
    CliCallerContext.withCallerEnv(mapOf("TRAILBLAZE_SHELL_PID" to shellPid.toString())) {
      writeShellDevicePinIfPossible("android/emulator-5554", "sampleapp")
    }

    val second = ShellDevicePinStore.resolvePin(pinFile(), shellPid)
      as? ShellDevicePinStore.PinLookup.Found
    assertNotNull(second)
    assertEquals(
      "android/emulator-5554",
      second.device,
      "rebind must leave the device binding alone",
    )
    assertEquals("sampleapp", second.target, "rebind must overwrite the target")
  }

  // ---------------------------------------------------------------------------
  // Test gap #2 — `--target=clear` end-to-end (file-pin side)
  //
  // The daemon-side wipe is exercised in the action-command paths via
  // `setSessionTargetForBoundDevice("")`; the file-pin side is exercised by
  // `clearShellDevicePinTargetIfPossible()`, called from BOTH
  // `cliReusableWithDevice` AND `SessionStartCommand` when `daemonCall.isClearRequest`
  // is true. We test the helper here because both call sites share its
  // implementation.
  // ---------------------------------------------------------------------------

  @Test
  fun `clearShellDevicePinTargetIfPossible — clear leaves device, nulls target`() {
    val shellPid = currentShellPid()
    CliCallerContext.withCallerEnv(mapOf("TRAILBLAZE_SHELL_PID" to shellPid.toString())) {
      writeShellDevicePinIfPossible("android/emulator-5554", "sampleapp")
    }

    CliCallerContext.withCallerEnv(mapOf("TRAILBLAZE_SHELL_PID" to shellPid.toString())) {
      clearShellDevicePinTargetIfPossible()
    }

    val found = ShellDevicePinStore.resolvePin(pinFile(), shellPid)
      as? ShellDevicePinStore.PinLookup.Found
    assertNotNull(found, "--target=clear must NOT wipe the device half of the pin")
    assertEquals("android/emulator-5554", found.device)
    assertNull(found.target, "--target=clear must null the target field")
  }

  @Test
  fun `clearShellDevicePinTargetIfPossible — no-op when no pin exists for this shell`() {
    // The action command emits the clear-the-file call defensively (before the
    // daemon-side clear) — so it must be safe to call from a shell that's never
    // pinned anything. The helper must not throw and must not create an entry.
    //
    // Note: the helper's success log (`Console.log("[ShellDevicePinStore]
    // cleared target for pid=X")`) fires unconditionally on the mutate path,
    // even when the entry was already missing. That's expected behavior — the
    // load-bearing contract is the file-state invariant, NOT log silence.
    // (Asserting log silence would also require reflectively swapping
    // `Console.out`, since `System.setOut` doesn't redirect Console's cached
    // reference. See [CliInfrastructureResolverTest]'s class kdoc.)
    val shellPid = currentShellPid()

    CliCallerContext.withCallerEnv(mapOf("TRAILBLAZE_SHELL_PID" to shellPid.toString())) {
      clearShellDevicePinTargetIfPossible()
    }

    val found = ShellDevicePinStore.resolvePin(pinFile(), shellPid)
    assertEquals(
      ShellDevicePinStore.PinLookup.NotFound,
      found,
      "No entry must be created for a no-op clear",
    )
  }

  // ---------------------------------------------------------------------------
  // Test gap #5 — DeviceDisconnectCommand clears the pin via the helper
  // ---------------------------------------------------------------------------

  @Test
  fun `clearShellDevicePinIfPossible removes the entry for the forwarded shell PID`() {
    // Set up a pin first.
    val shellPid = currentShellPid()
    CliCallerContext.withCallerEnv(mapOf("TRAILBLAZE_SHELL_PID" to shellPid.toString())) {
      writeShellDevicePinIfPossible("android/emulator-5554", "sampleapp")
    }
    val preCheck = ShellDevicePinStore.resolvePin(pinFile(), shellPid)
    assertTrue(preCheck is ShellDevicePinStore.PinLookup.Found, "precondition: pin must exist")

    // Clear it — the helper [DeviceDisconnectCommand.call] uses.
    CliCallerContext.withCallerEnv(mapOf("TRAILBLAZE_SHELL_PID" to shellPid.toString())) {
      clearShellDevicePinIfPossible()
    }

    val postCheck = ShellDevicePinStore.resolvePin(pinFile(), shellPid)
    assertEquals(
      ShellDevicePinStore.PinLookup.NotFound,
      postCheck,
      "disconnect must remove this terminal's entry",
    )
  }

  @Test
  fun `clearShellDevicePinIfPossible leaves other terminals' pins alone`() {
    // Multi-terminal safety pin: the disconnect helper must scope to the
    // calling shell's PID. A user disconnecting in terminal A must not wipe
    // the pin terminal B set for itself — exactly the contract the file's
    // per-PID keying exists to enforce. Spawn a sibling sleep process for
    // the second PID so `isPidAlive` doesn't GC the entry on the way.
    val pidA = currentShellPid()
    val pidB = spawnSleeperPid()
    check(pidA != pidB) { "spawned sleeper PID collided with the test PID" }

    CliCallerContext.withCallerEnv(mapOf("TRAILBLAZE_SHELL_PID" to pidA.toString())) {
      writeShellDevicePinIfPossible("android/emulator-5554", "sampleapp")
    }
    CliCallerContext.withCallerEnv(mapOf("TRAILBLAZE_SHELL_PID" to pidB.toString())) {
      writeShellDevicePinIfPossible("ios/SIM-X", "default")
    }

    CliCallerContext.withCallerEnv(mapOf("TRAILBLAZE_SHELL_PID" to pidA.toString())) {
      clearShellDevicePinIfPossible()
    }

    val aGone = ShellDevicePinStore.resolvePin(pinFile(), pidA)
    val bStill = ShellDevicePinStore.resolvePin(pinFile(), pidB)
    assertEquals(ShellDevicePinStore.PinLookup.NotFound, aGone, "A must be cleared")
    val foundB = bStill as? ShellDevicePinStore.PinLookup.Found
    assertNotNull(foundB, "B must survive A's disconnect")
    assertEquals("ios/SIM-X", foundB.device)
    assertEquals("default", foundB.target)
  }

  // ---------------------------------------------------------------------------
  // Test gap #6 — isInteractiveCaller gates the non-tty warning
  //
  // The `Note: this terminal looks non-interactive...` line in
  // [DeviceConnectCommand.call] sits behind `if (!isInteractiveCaller())`.
  // The helper reads `TRAILBLAZE_INTERACTIVE` from the caller env and returns
  // true ONLY when the value is exactly `"1"` (after trim). Test the pure
  // function directly — the boolean is what gates the stderr line; the line
  // itself is a static string we already pin in CLI help baselines.
  // ---------------------------------------------------------------------------

  @Test
  fun `isInteractiveCaller — returns true when TRAILBLAZE_INTERACTIVE is exactly "1"`() {
    val result = CliCallerContext.withCallerEnv(mapOf("TRAILBLAZE_INTERACTIVE" to "1")) {
      isInteractiveCaller()
    }
    assertTrue(result, "TRAILBLAZE_INTERACTIVE=1 → interactive (warning suppressed)")
  }

  @Test
  fun `isInteractiveCaller — returns false when TRAILBLAZE_INTERACTIVE is "0" (agent harness)`() {
    val result = CliCallerContext.withCallerEnv(mapOf("TRAILBLAZE_INTERACTIVE" to "0")) {
      isInteractiveCaller()
    }
    assertFalse(result, "TRAILBLAZE_INTERACTIVE=0 → non-interactive (warning fires)")
  }

  @Test
  fun `isInteractiveCaller — returns false when TRAILBLAZE_INTERACTIVE is unset (older wrapper)`() {
    // Older bash wrappers (pre-PR #3611) don't forward TRAILBLAZE_INTERACTIVE.
    // The default-to-false contract documented on [isInteractiveCaller] keeps
    // agent harnesses safe: a missed warning to a real human is mild noise; a
    // missed warning to an agent is a re-pin loop.
    val result = CliCallerContext.withCallerEnv(emptyMap()) {
      isInteractiveCaller()
    }
    assertFalse(result, "unset TRAILBLAZE_INTERACTIVE must default to false")
  }

  @Test
  fun `isInteractiveCaller — trims whitespace before comparing to "1"`() {
    // The bash wrapper exports `TRAILBLAZE_INTERACTIVE=1` cleanly, but a future
    // forwarding path that pads the value (e.g. via `jq -r`) should still be
    // accepted. The helper trims before comparing.
    val padded = CliCallerContext.withCallerEnv(mapOf("TRAILBLAZE_INTERACTIVE" to "  1  ")) {
      isInteractiveCaller()
    }
    assertTrue(padded, "padded \"1\" must still resolve as interactive after trim()")
  }

  @Test
  fun `isInteractiveCaller — any value other than "1" reads as non-interactive`() {
    // Pin the strict-equals contract: only "1" (after trim) qualifies. A future
    // refactor that broadened the predicate to e.g. `value.isNotBlank()` would
    // pass the true/false/unset cases above but fail this one — and would
    // silently re-classify "0" as interactive, suppressing the agent warning
    // exactly when it's most needed.
    val cases = listOf("0", "true", "yes", "interactive", "")
    for (value in cases) {
      val result = CliCallerContext.withCallerEnv(mapOf("TRAILBLAZE_INTERACTIVE" to value)) {
        isInteractiveCaller()
      }
      assertFalse(result, "TRAILBLAZE_INTERACTIVE=\"$value\" must read as non-interactive")
    }
  }

  // ---------------------------------------------------------------------------
  // evictShellPinIfMatches — lazy staleness handler called from the three
  // `ensureDevice` failure sites. Pins each branch of its strict-match guard:
  // the eviction must only fire when the pin points at the device the wrapper
  // just tried (and failed) to bind, so a `--device` flag failure can't
  // accidentally evict an unrelated pin.
  // ---------------------------------------------------------------------------

  @Test
  fun `evictShellPinIfMatches — evicts when pin matches the failed device and error reads as not-found`() {
    // Production-shape pin: device connect writes lowercased platform via
    // `toFullyQualifiedDeviceId()`. The match comparison uses ignoreCase=true so
    // a caller passing a mixed-case spec still triggers the right eviction.
    val shellPid = currentShellPid()
    CliCallerContext.withCallerEnv(mapOf("TRAILBLAZE_SHELL_PID" to shellPid.toString())) {
      writeShellDevicePinIfPossible("android/emulator-5554", null)
    }

    CliCallerContext.withCallerEnv(mapOf("TRAILBLAZE_SHELL_PID" to shellPid.toString())) {
      evictShellPinIfMatches(
        "Android/Emulator-5554",
        "Device 'android/emulator-5554' not found. Available: ios/SIM-X",
      )
    }

    assertEquals(
      ShellDevicePinStore.PinLookup.NotFound,
      ShellDevicePinStore.resolvePin(pinFile(), shellPid),
      "pin must be evicted when the failed device matches it and the error reads as not-found",
    )
  }

  @Test
  fun `evictShellPinIfMatches — leaves the pin alone on transient device-busy failures`() {
    // The reason gate: `ensureDevice` can return the daemon's "Error: … is
    // busy" block when another shell holds the same device. The pin is still
    // valid (the device exists, this terminal just couldn't claim it),
    // so eviction must skip — otherwise contention between two terminals
    // would wipe both pins on each lost race.
    //
    // Regression guard for the Codex/Copilot review on PR #3621.
    val shellPid = currentShellPid()
    CliCallerContext.withCallerEnv(mapOf("TRAILBLAZE_SHELL_PID" to shellPid.toString())) {
      writeShellDevicePinIfPossible("android/emulator-5554", "sampleapp")
    }

    CliCallerContext.withCallerEnv(mapOf("TRAILBLAZE_SHELL_PID" to shellPid.toString())) {
      evictShellPinIfMatches(
        "android/emulator-5554",
        "Error: Device android/emulator-5554 is busy on another session.",
      )
    }

    val found = ShellDevicePinStore.resolvePin(pinFile(), shellPid)
      as? ShellDevicePinStore.PinLookup.Found
    assertNotNull(found, "device-busy is transient — the pin must survive")
    assertEquals("android/emulator-5554", found.device)
    assertEquals("sampleapp", found.target)
  }

  @Test
  fun `evictShellPinIfMatches — leaves the pin alone on transient transport failures`() {
    // Mirrors the busy-error guard for "Error connecting to device: HTTP 503"
    // / "request timed out" — anything that doesn't read as the device being
    // gone is transient and the pin survives. Pinning this here keeps the
    // reason-gate contract explicit so a refactor that broadens the predicate
    // (e.g. "any error" or "starts with Error:") surfaces immediately.
    val shellPid = currentShellPid()
    CliCallerContext.withCallerEnv(mapOf("TRAILBLAZE_SHELL_PID" to shellPid.toString())) {
      writeShellDevicePinIfPossible("ios/SIM-X", null)
    }

    CliCallerContext.withCallerEnv(mapOf("TRAILBLAZE_SHELL_PID" to shellPid.toString())) {
      evictShellPinIfMatches(
        "ios/SIM-X",
        "Error connecting to device: HTTP 503: Service Unavailable",
      )
    }

    val found = ShellDevicePinStore.resolvePin(pinFile(), shellPid)
      as? ShellDevicePinStore.PinLookup.Found
    assertNotNull(found, "transport error is transient — the pin must survive")
    assertEquals("ios/SIM-X", found.device)
  }

  @Test
  fun `evictShellPinIfMatches — leaves the pin alone when the failed device differs`() {
    // The `if matches` guard is what stops a `--device` flag failure from
    // wiping an unrelated terminal pin. If a user has `android/emulator-5554`
    // pinned and runs `trailblaze snapshot --device ios/X` (which fails),
    // the pin must survive — the failure was about ios, not the pin.
    val shellPid = currentShellPid()
    CliCallerContext.withCallerEnv(mapOf("TRAILBLAZE_SHELL_PID" to shellPid.toString())) {
      writeShellDevicePinIfPossible("android/emulator-5554", null)
    }

    CliCallerContext.withCallerEnv(mapOf("TRAILBLAZE_SHELL_PID" to shellPid.toString())) {
      evictShellPinIfMatches("ios/SIM-X", "Device 'ios/SIM-X' not found. Available: android/emulator-5554")
    }

    val found = ShellDevicePinStore.resolvePin(pinFile(), shellPid)
      as? ShellDevicePinStore.PinLookup.Found
    assertNotNull(found, "pin must survive an unrelated --device failure")
    assertEquals("android/emulator-5554", found.device)
  }

  @Test
  fun `evictShellPinIfMatches — silent no-op when no pin exists for this shell`() {
    // The function reads the pin first; a missing entry returns null and the
    // function exits before reaching the write. Pinning that contract here so
    // a refactor that re-orders the checks (e.g. always writes a tombstone)
    // surfaces immediately.
    val shellPid = currentShellPid()
    val pinFileBefore = pinFile()
    val existedBefore = pinFileBefore.exists()

    CliCallerContext.withCallerEnv(mapOf("TRAILBLAZE_SHELL_PID" to shellPid.toString())) {
      evictShellPinIfMatches("android/emulator-5554", "Device not found. Available: …")
    }

    assertEquals(
      existedBefore,
      pinFileBefore.exists(),
      "no pin file should be created by an eviction that finds nothing",
    )
  }

  @Test
  fun `evictShellPinIfMatches — silent no-op when TRAILBLAZE_SHELL_PID is unset`() {
    // Older bash wrappers don't forward the shell PID; `readShellPinDevice`
    // returns null and the eviction must not touch the file. Same shape as
    // the other shell-pin helpers' inert-without-PID contract.
    val shellPid = currentShellPid()
    CliCallerContext.withCallerEnv(mapOf("TRAILBLAZE_SHELL_PID" to shellPid.toString())) {
      writeShellDevicePinIfPossible("android/emulator-5554", "sampleapp")
    }

    CliCallerContext.withCallerEnv(emptyMap()) {
      evictShellPinIfMatches("android/emulator-5554", "Device not found. Available: …")
    }

    val found = ShellDevicePinStore.resolvePin(pinFile(), shellPid)
      as? ShellDevicePinStore.PinLookup.Found
    assertNotNull(found, "pin must survive when the eviction can't identify the shell")
    assertEquals("android/emulator-5554", found.device)
    assertEquals("sampleapp", found.target)
  }

  // ---------------------------------------------------------------------------
  // looksLikeDeviceNotFound — pure reason-gate predicate, unit-tested
  // independently of the helper that consumes it. Drift between the daemon's
  // device-not-found wording and this predicate would silently disable the
  // self-eviction path, so these substring-shape pins are load-bearing.
  // ---------------------------------------------------------------------------

  @Test
  fun `looksLikeDeviceNotFound — matches the daemon's not-found wording`() {
    // Three representative shapes from CliMcpClient.connectToDevice — keep
    // these in sync with the source-of-truth strings emitted by the daemon.
    val variants = listOf(
      "Device 'android/emulator-5554' not found. Available: ios/SIM-X",
      "Device 'emulator-5554' not found. Run 'trailblaze device list' to see available devices.",
      "No mobile devices found. Connect an Android device/emulator or start an iOS simulator.",
      "Device NOT FOUND. Some other detail.",
    )
    for (v in variants) {
      assertTrue(looksLikeDeviceNotFound(v), "expected not-found match for: $v")
    }
  }

  @Test
  fun `looksLikeDeviceNotFound — skips busy and transport failures`() {
    val variants = listOf(
      "Error: Device android/emulator-5554 is busy on another session.",
      "Error connecting to device: HTTP 503: Service Unavailable",
      "Session reports an existing device but device(INFO) returned no platform. Reconnect explicitly with --device <platform>[/<instance>].",
      "",
    )
    for (v in variants) {
      assertFalse(looksLikeDeviceNotFound(v), "expected no-match for non-staleness error: $v")
    }
  }
}
