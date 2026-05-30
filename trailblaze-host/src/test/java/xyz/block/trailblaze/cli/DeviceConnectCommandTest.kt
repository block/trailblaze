package xyz.block.trailblaze.cli

import org.junit.Test
import picocli.CommandLine
import picocli.CommandLine.MissingParameterException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Picocli-level coverage for [DeviceConnectCommand] and [DeviceDisconnectCommand].
 *
 * Focuses on what only the picocli layer can pin without contacting a daemon:
 *
 *  - [DeviceConnectCommand] parses the positional `<platform>` and the new
 *    `--target` / `-t` option to the right fields, with sensible defaults.
 *  - The new `--target` option is *optional* (`target == null` when not passed),
 *    so existing callers of `trailblaze device connect android` continue to work
 *    unchanged.
 *  - [DeviceDisconnectCommand] has no positional / required args — `trailblaze
 *    device disconnect` parses cleanly with no arguments.
 *
 * The happy-path daemon dispatch (calling `ensureDevice` + `setSessionTargetForBoundDevice`,
 * emitting `export TRAILBLAZE_DEVICE=...` on stdout vs status messages on stderr) is
 * out-of-scope for this unit suite — it requires a daemon round-trip and is covered
 * by the integration runs that exercise the same MCP tool calls.
 */
class DeviceConnectCommandTest {

  // ---------------------------------------------------------------------------
  // DeviceConnectCommand
  // ---------------------------------------------------------------------------

  @Test
  fun `connect picocli leaves target null by default`() {
    // Critical for backwards compat — pre-existing scripts that do
    // `trailblaze device connect ANDROID` must keep working without supplying
    // a target. The daemon-side path treats `null` target as "keep whatever
    // target is currently bound for this device."
    val command = DeviceConnectCommand()
    CommandLine(command).parseArgs("ANDROID")
    assertEquals("ANDROID", command.platform)
    assertNull(command.target)
  }

  @Test
  fun `connect picocli parses --target long form`() {
    val command = DeviceConnectCommand()
    CommandLine(command).parseArgs("ANDROID", "--target", "square")
    assertEquals("ANDROID", command.platform)
    assertEquals("square", command.target)
  }

  @Test
  fun `connect picocli parses -t short form`() {
    val command = DeviceConnectCommand()
    CommandLine(command).parseArgs("android", "-t", "sampleapp")
    assertEquals("android", command.platform)
    assertEquals("sampleapp", command.target)
  }

  @Test
  fun `connect picocli accepts platform-with-instance specs`() {
    // The same spec format `ensureDevice` already supports — `<platform>/<instance>`.
    // Picocli should accept it as-is and pass through to the daemon resolver.
    val command = DeviceConnectCommand()
    CommandLine(command).parseArgs("android/emulator-5554", "--target", "square")
    assertEquals("android/emulator-5554", command.platform)
    assertEquals("square", command.target)
  }

  // ---------------------------------------------------------------------------
  // DeviceDisconnectCommand
  // ---------------------------------------------------------------------------

  @Test
  fun `disconnect parses with no arguments`() {
    // No positional / required options at the picocli layer — device resolution
    // happens in call() via resolveCliDevice (flag → env → MISUSE). A regression
    // that re-adds a required positional would fail here.
    val command = DeviceDisconnectCommand()
    CommandLine(command).parseArgs() // no args
    assertNull(command.device)
  }

  @Test
  fun `disconnect picocli parses --device long form`() {
    val command = DeviceDisconnectCommand()
    CommandLine(command).parseArgs("--device", "android/emulator-5554")
    assertEquals("android/emulator-5554", command.device)
  }

  @Test
  fun `disconnect picocli parses -d short form`() {
    val command = DeviceDisconnectCommand()
    CommandLine(command).parseArgs("-d", "ios/SIM-X")
    assertEquals("ios/SIM-X", command.device)
  }

  @Test
  fun `disconnect without --device and without TRAILBLAZE_DEVICE returns MISUSE`() {
    // Critical safety pin: without ANY device identifier, disconnect must refuse
    // rather than fall through to "stop whatever the daemon currently has bound"
    // (which would be another shell's session in the multi-terminal case).
    if (!System.getenv("TRAILBLAZE_DEVICE").isNullOrBlank()) return
    val command = DeviceDisconnectCommand()
    CommandLine(command).parseArgs()
    val exitCode = command.call()
    assertEquals(TrailblazeExitCode.MISUSE.code, exitCode)
  }

  // ---------------------------------------------------------------------------
  // DeviceRebindCommand
  // ---------------------------------------------------------------------------

  @Test
  fun `rebind picocli parses --device long form and --target`() {
    val command = DeviceRebindCommand()
    CommandLine(command).parseArgs("--device", "android/emulator-5554", "--target", "sampleapp")
    assertEquals("android/emulator-5554", command.device)
    assertEquals("sampleapp", command.target)
  }

  @Test
  fun `rebind picocli parses -d -t short forms`() {
    val command = DeviceRebindCommand()
    CommandLine(command).parseArgs("-d", "ios/SIM-X", "-t", "default")
    assertEquals("ios/SIM-X", command.device)
    assertEquals("default", command.target)
  }

  @Test
  fun `rebind without --target throws MissingParameterException`() {
    // --target is required at the picocli layer; the production runner's
    // parameterExceptionHandler (`installTrailblazeExceptionHandlers`) maps
    // this exception to TrailblazeExitCode.MISUSE. We assert at the parse
    // boundary here, matching the same pattern session start/stop/end use
    // for their own required `--device` flag in SessionCommandRequiredDeviceTest.
    val cmd = DeviceRebindCommand()
    val cmdLine = CommandLine(cmd)
    val ex = assertFailsWith<MissingParameterException> {
      cmdLine.parseArgs("--device", "android")
    }
    assertTrue(
      ex.message?.contains("--target") == true,
      "Expected the error to call out --target specifically, got: ${ex.message}",
    )
  }

  @Test
  fun `rebind without --device or TRAILBLAZE_DEVICE returns MISUSE`() {
    // Same multi-terminal safety pin as `device disconnect`: without ANY
    // device identifier the call() path must refuse rather than blindly
    // rebind whatever the daemon currently has bound (which could be
    // another shell's session).
    if (!System.getenv("TRAILBLAZE_DEVICE").isNullOrBlank()) return
    val command = DeviceRebindCommand()
    CommandLine(command).parseArgs("--target", "sampleapp")
    val exitCode = command.call()
    assertEquals(TrailblazeExitCode.MISUSE.code, exitCode)
  }

  // ---------------------------------------------------------------------------
  // shellSingleQuote — escape contract for `eval $(...)` round-trip safety
  // ---------------------------------------------------------------------------

  @Test
  fun `shellSingleQuote wraps plain values in single quotes`() {
    assertEquals("'android/emulator-5554'", shellSingleQuote("android/emulator-5554"))
  }

  @Test
  fun `shellSingleQuote preserves whitespace inside the quotes`() {
    // The motivating case from the Codex review: named web slots like `iPhone 14`.
    // Without the quoting, `eval` would tokenize `web/iPhone` and `14` separately
    // and the env var assignment would truncate.
    assertEquals("'web/iPhone 14'", shellSingleQuote("web/iPhone 14"))
  }

  @Test
  fun `shellSingleQuote escapes embedded single quotes using the close-escape-reopen idiom`() {
    // POSIX-safe escape for a single quote inside single-quoted text: end the
    // current single-quoted chunk, emit a backslash-escaped quote, start a new
    // chunk. Result: 'foo'\''bar' reads as foo'bar to the shell.
    assertEquals("'can'\\''t'", shellSingleQuote("can't"))
  }

  @Test
  fun `shellSingleQuote handles empty strings`() {
    // Defensive: produces `''` rather than producing nothing (which would render
    // `export TRAILBLAZE_DEVICE=` with no value and be a syntax error on some shells).
    assertEquals("''", shellSingleQuote(""))
  }

  @Test
  fun `shellSingleQuote passes shell metacharacters through unchanged inside quotes`() {
    // `$`, `*`, backticks, etc. are inert inside POSIX single quotes — no escape needed.
    // Verifies that the helper doesn't over-escape, which would break legitimate device
    // ids containing dollar signs (rare but possible).
    assertEquals("'foo\$bar`baz*'", shellSingleQuote("foo\$bar`baz*"))
  }

  // ---------------------------------------------------------------------------
  // printShellExport / printShellUnset — eval-line emission contract
  // ---------------------------------------------------------------------------

  @Test
  fun `printShellExport emits export VAR='quoted-value' to stdout`() {
    // The whole point of routing through this helper is that the eval-line goes
    // to stdout (where `eval $(…)` captures it) and uses single-quoted values
    // that survive whitespace + shell metacharacters. Future commands that copy
    // the pattern shouldn't have to rediscover the stdout/quoting contract.
    val out = captureStdout { printShellExport("TRAILBLAZE_DEVICE", "android/emulator-5554") }
    assertEquals("export TRAILBLAZE_DEVICE='android/emulator-5554'\n", out)
  }

  @Test
  fun `printShellExport quotes values containing whitespace`() {
    val out = captureStdout { printShellExport("TRAILBLAZE_DEVICE", "web/iPhone 14") }
    assertEquals("export TRAILBLAZE_DEVICE='web/iPhone 14'\n", out)
  }

  @Test
  fun `printShellUnset emits unset VAR to stdout`() {
    val out = captureStdout { printShellUnset("TRAILBLAZE_DEVICE") }
    assertEquals("unset TRAILBLAZE_DEVICE\n", out)
  }

  @Test
  fun `printShellExport allows empty value (clears via empty assignment)`() {
    // `export VAR=''` is a valid shell assignment that clears the variable to the
    // empty string — semantically distinct from `unset VAR` (which removes it
    // entirely). The helper permits both shapes so callers can pick the right one
    // for their intent.
    val out = captureStdout { printShellExport("TRAILBLAZE_DEVICE", "") }
    assertEquals("export TRAILBLAZE_DEVICE=''\n", out)
  }

  @Test
  fun `printShellExport rejects blank varName`() {
    // `export =value` would be a shell parse error inside `eval $(…)`. Treating
    // it as a contract violation (rather than silently emitting broken syntax)
    // keeps the failure mode loud and points at the caller, not the shell.
    val ex = kotlin.test.assertFailsWith<IllegalArgumentException> {
      printShellExport("", "any")
    }
    kotlin.test.assertTrue(
      ex.message?.contains("varName") == true,
      "Error message should mention varName; got: ${ex.message}",
    )
  }

  @Test
  fun `printShellUnset rejects blank varName`() {
    // Same contract as printShellExport — `unset ` (no name) is invalid shell.
    kotlin.test.assertFailsWith<IllegalArgumentException> {
      printShellUnset("   ")
    }
  }

  @Test
  fun `captureStdout restores stdout even when block throws`() {
    // Insurance against a `setOut(buf)`-then-throw regression: if the helper
    // ever loses its try/finally, a throwing test would permanently redirect
    // stdout and the next test's output (or productionConsole.log) would land in
    // the dead buffer instead of the console. Pin the restore semantics.
    val originalOut = System.out
    val ex = kotlin.test.assertFailsWith<RuntimeException> {
      captureStdout {
        println("about to throw")
        throw RuntimeException("deliberate")
      }
    }
    kotlin.test.assertEquals("deliberate", ex.message)
    kotlin.test.assertEquals(
      originalOut,
      System.out,
      "captureStdout must restore stdout in a finally block, even when the block throws",
    )
  }

  /**
   * Captures the stdout produced by [block]. Uses `System.setOut` so the helper
   * exercises the same path `println` (and hence [printShellExport]) takes in
   * production. The previous test-only `tracked*ToolCall` getters on `McpProxy`
   * were a workaround for this exact need; for the static `printShell*` helpers,
   * capturing stdout directly is cleaner.
   */
  private inline fun captureStdout(block: () -> Unit): String {
    val originalOut = System.out
    val buf = java.io.ByteArrayOutputStream()
    System.setOut(java.io.PrintStream(buf))
    try {
      block()
    } finally {
      System.setOut(originalOut)
    }
    return buf.toString()
  }

  // ---------------------------------------------------------------------------
  // resolveCliDevice — layered fallback for every action command's --device flag
  // ---------------------------------------------------------------------------

  @Test
  fun `resolveCliDevice returns explicit flag when non-blank`() {
    // The flag always wins. Tests / scripts / CI / agents that pass --device
    // explicitly stay deterministic even when TRAILBLAZE_DEVICE is set.
    val resolved = resolveCliDevice("android/emulator-5554")
    assertEquals("android/emulator-5554", resolved)
  }

  @Test
  fun `resolveCliDevice falls back to TRAILBLAZE_DEVICE env var when flag is null`() {
    // Skip when the test JVM happens to have TRAILBLAZE_DEVICE unset. We don't
    // mutate env in a unit test (it's process-global and bleeds across tests).
    // The fallback's correctness is verified by the conditional below; the
    // explicit-wins case is the load-bearing one we always pin.
    val envValue = System.getenv("TRAILBLAZE_DEVICE")
    if (envValue.isNullOrBlank()) {
      assertNull(resolveCliDevice(null))
    } else {
      assertEquals(envValue, resolveCliDevice(null))
    }
  }

  @Test
  fun `resolveCliDevice treats blank flag as unset and falls through to env`() {
    // A blank --device value (e.g. `--device ""`) shouldn't pin the shell to an
    // unusable empty string — fall through to the env var or null.
    val envValue = System.getenv("TRAILBLAZE_DEVICE")
    val resolved = resolveCliDevice("   ")
    if (envValue.isNullOrBlank()) {
      assertNull(resolved)
    } else {
      assertEquals(envValue, resolved)
    }
  }

  @Test
  fun `resolveDeviceWithAutodetect short-circuits to flag without touching daemon`() {
    // The full four-tier chain MUST treat the explicit flag as terminal — no
    // daemon round-trip when the user knows what they want. Critical because
    // autodetect makes a one-shot MCP call that, with a non-running daemon, would
    // either delay the command by ~30s (auto-start path) or fail outright. By
    // passing a non-existent daemon port and asserting we still get the flag back
    // synchronously, we prove the suspend resolver hits its first tier and
    // returns without ever calling [autodetectSingleConnectedDevice].
    //
    // Lives alongside the static `resolveCliDevice` tests because it covers the
    // same explicit-wins invariant at the next layer up — together they pin the
    // contract: any caller of either function gets the flag back, untouched.
    val resolved = kotlinx.coroutines.runBlocking {
      resolveDeviceWithAutodetect(
        flag = "ios/SIM-X",
        // Unreachable port — if the resolver tried to connect, the test would
        // hang or fall through to a [DeviceAutodetectResult.DaemonUnreachable]
        // branch that returns a non-Resolved variant. Either way we wouldn't
        // get the explicit flag back unwrapped.
        port = 1,
      )
    }
    assertEquals(DeviceResolution.Resolved("ios/SIM-X"), resolved)
  }

  @Test
  fun `DeviceResolution exitCodeFallback maps non-Resolved variants to right exit codes`() {
    // Pin the contract used by every action command's call() — daemon-unreachable
    // must surface as INFRA_FAILED (not MISUSE), and 0/multiple-device misuse
    // as MISUSE. A regression that swapped these would let a stopped daemon
    // exit as MISUSE — wrong per [TrailblazeExitCode] policy.
    assertEquals(TrailblazeExitCode.MISUSE.code, DeviceResolution.Misuse.exitCodeFallback())
    assertEquals(TrailblazeExitCode.INFRA_FAILED.code, DeviceResolution.InfraFailed.exitCodeFallback())
    // Resolved isn't expected to hit the fallback path, but pin SUCCESS so a
    // caller that does call it on a Resolved doesn't get a surprise INFRA.
    assertEquals(
      TrailblazeExitCode.SUCCESS.code,
      DeviceResolution.Resolved("android/emulator-5554").exitCodeFallback(),
    )
  }

  // ---------------------------------------------------------------------------
  // autodetect classification — the 0 / 1 / 2+ branches of
  // [autodetectSingleConnectedDevice]'s decision tree are pinned via
  // [CliMcpClient.parseDeviceList] (the function the resolver delegates to for
  // classification). Daemon-less unit coverage of the contract that "1 entry
  // → Resolved, 0 → NoDevices, 2+ → Multiple" — the bit a regression could
  // most plausibly break (off-by-one comparing `< 1` vs `== 1` etc.).
  // ---------------------------------------------------------------------------

  @Test
  fun `parseDeviceList classifies a single-device response as one entry`() {
    // Daemon LIST output for one Android emulator. The autodetect path keys
    // off `parseDeviceList(...).size == 1` to emit Resolved — pin the parser
    // so a format tweak on the daemon side surfaces here, not at runtime.
    val content = "  - emulator-5554 (Android) - Google Pixel 6"
    val entries = CliMcpClient.parseDeviceList(content)
    assertEquals(1, entries.size)
    assertEquals("emulator-5554", entries.single().instanceId)
  }

  @Test
  fun `parseDeviceList returns empty list for no-devices response`() {
    // Empty body → autodetect's NoDevices branch. The parser must not invent
    // entries from blank input (a "0 devices" race would silently morph into
    // "Multiple" if a stray blank line slipped through).
    assertEquals(0, CliMcpClient.parseDeviceList("").size)
    assertEquals(0, CliMcpClient.parseDeviceList("\n\n   \n").size)
  }

  @Test
  fun `parseDeviceList recognizes multiple devices across platforms`() {
    // Two distinct platforms in one LIST response → autodetect's Multiple
    // branch. Pin that we recognize each platform tag (the parser uses
    // `contains("(Android)")` / `contains("(iOS)")` / `contains("(Web")`).
    val content = """
      - emulator-5554 (Android) - Google Pixel 6
      - SIM-ABC123 (iOS) - iPhone 15
    """.trimIndent()
    val entries = CliMcpClient.parseDeviceList(content)
    assertEquals(2, entries.size)
    assertEquals(setOf("emulator-5554", "SIM-ABC123"), entries.map { it.instanceId }.toSet())
  }

  // ---------------------------------------------------------------------------
  // Other action commands — --device is no longer picocli-required (resolver
  // handles fallback). Picocli should accept invocations without --device.
  // ---------------------------------------------------------------------------

  @Test
  fun `snapshot picocli accepts no --device flag`() {
    // The resolver in call() now does the env-var fallback / error, so picocli
    // must not pre-reject. A regression that re-adds required=true would fail
    // here with MissingParameterException before our call() ever runs.
    val command = SnapshotCommand()
    CommandLine(command).parseArgs() // intentionally no args
    assertNull(command.device)
  }

  @Test
  fun `tool picocli accepts no --device flag`() {
    // Uses the canonical `--step` rather than the deprecated `--objective` alias
    // so the test reflects the new vocabulary. `--objective` still parses (the
    // alias is intentionally kept for one release) — see ToolCommandStepFlagTest
    // for explicit alias coverage.
    val command = ToolCommand()
    CommandLine(command).parseArgs("--step", "tap")
    assertNull(command.device)
  }

  @Test
  fun `verify picocli accepts no --device flag`() {
    val command = VerifyCommand()
    CommandLine(command).parseArgs("the", "button", "is", "visible")
    assertNull(command.device)
  }

  @Test
  fun `ask picocli accepts no --device flag`() {
    val command = AskCommand()
    CommandLine(command).parseArgs("what", "is", "on", "screen")
    assertNull(command.device)
  }
}
