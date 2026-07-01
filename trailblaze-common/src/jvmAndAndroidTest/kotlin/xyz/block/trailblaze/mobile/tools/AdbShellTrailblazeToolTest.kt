package xyz.block.trailblaze.mobile.tools

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.hasLength
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotInstanceOf
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import org.junit.Test
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.HostLocalExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.yaml.TrailYamlItem
import xyz.block.trailblaze.yaml.createTrailblazeYaml

/**
 * Unit coverage for [AdbShellTrailblazeTool].
 *
 * Mirrors the shape of [AndroidSendBroadcastSerializationTest][xyz.block.trailblaze.yaml.AndroidSendBroadcastSerializationTest]:
 * exercises YAML round-trip plus the failure-mode branches of `execute` that don't
 * require a real `AndroidDeviceCommandExecutor` — non-Android platform, empty command,
 * missing executor. Happy-path execution is covered by the local-emulator integration
 * pass on the clock OSS example trail (`clock_android_launchApp.ts` composes `android_adbShell`
 * end-to-end), which is the load-bearing validation for the executor shell-out.
 *
 * The dual-mode contract (`requiresHost = false`, implements [ExecutableTrailblazeTool]
 * not [HostLocalExecutableTrailblazeTool]) is pinned by reflection so a future refactor
 * that accidentally adds the host-only marker fails this test instead of silently
 * routing all `android_adbShell` calls through the host RPC path even when on-device dispatch
 * is appropriate.
 */
class AdbShellTrailblazeToolTest {

  private val trailblazeYaml = createTrailblazeYaml(setOf(AdbShellTrailblazeTool::class))

  @Test
  fun `decodes minimal command from trail YAML`() {
    val yaml = """
      - tools:
          - android_adbShell:
              command:
                - am
                - force-stop
                - com.example.app
    """.trimIndent()

    val tool = (trailblazeYaml.decodeTrail(yaml).single() as TrailYamlItem.ToolTrailItem)
      .tools.single().trailblazeTool as AdbShellTrailblazeTool

    assertThat(tool.command).isEqualTo(listOf("am", "force-stop", "com.example.app"))
    assertThat(tool.runAs).isEqualTo(null)
  }

  @Test
  fun `decodes runAs override from trail YAML`() {
    val yaml = """
      - tools:
          - android_adbShell:
              command:
                - cat
                - /data/data/com.example.app/files/state.json
              runAs: com.example.app
    """.trimIndent()

    val tool = (trailblazeYaml.decodeTrail(yaml).single() as TrailYamlItem.ToolTrailItem)
      .tools.single().trailblazeTool as AdbShellTrailblazeTool

    assertThat(tool.command).isEqualTo(listOf("cat", "/data/data/com.example.app/files/state.json"))
    assertThat(tool.runAs).isEqualTo("com.example.app")
  }

  @Test
  fun `executeReturnsErrorOnNonAndroidPlatform`() = runBlocking {
    val tool = AdbShellTrailblazeTool(command = listOf("pm", "list", "packages"))
    val result = tool.execute(createContext(TrailblazeDevicePlatform.IOS))

    assertIs<TrailblazeToolResult.Error.ExceptionThrown>(result)
    assertThat(result.errorMessage).contains("only supported on Android")
  }

  @Test
  fun `executeReturnsErrorOnWebPlatform`() = runBlocking {
    val tool = AdbShellTrailblazeTool(command = listOf("pm", "list", "packages"))
    val result = tool.execute(createContext(TrailblazeDevicePlatform.WEB))

    assertIs<TrailblazeToolResult.Error.ExceptionThrown>(result)
    assertThat(result.errorMessage).contains("only supported on Android")
  }

  @Test
  fun `constructor rejects empty command list`() {
    // The only invariant the init block enforces: the list is non-empty. A 0-element
    // list would produce an empty `sh -c ""` that succeeds silently — almost certainly
    // an authoring slip rather than intent. Catch it at construction.
    val error = assertFailsWith<IllegalArgumentException> {
      AdbShellTrailblazeTool(command = emptyList())
    }
    assertThat(error.message ?: "").contains("non-empty")
  }

  @Test
  fun `executeReturnsErrorWhenExecutorIsMissing`() = runBlocking {
    val tool = AdbShellTrailblazeTool(command = listOf("pm", "list", "packages"))
    // Android platform, valid command, but no AndroidDeviceCommandExecutor wired in —
    // the same scenario unit tests hit (real executor lives behind dadb / instrumentation).
    val result = tool.execute(createContext(TrailblazeDevicePlatform.ANDROID))

    assertIs<TrailblazeToolResult.Error.ExceptionThrown>(result)
    assertThat(result.errorMessage).contains("AndroidDeviceCommandExecutor")
    // The missing-executor error includes the joined-and-quoted would-have-run command,
    // which doubles as confirmation that execute() ran the join end-to-end (not just at
    // the unit-test layer). A future refactor that bypassed the join would fail this.
    assertThat(result.errorMessage).contains("'pm' 'list' 'packages'")
  }

  @Test
  fun `execute() with runAs composes the same joined command (runAs is orthogonal to the join)`() = runBlocking {
    // The runAs branch in execute() dispatches to executeShellCommandAs after the same
    // join + sentinel-wrap pipeline. We can't directly mock the expect-class executor,
    // but we can still pin that constructing with both fields produces the expected
    // joined string visible in the missing-executor error path — same trace as the
    // non-runAs case, just with a different executor method downstream.
    val tool = AdbShellTrailblazeTool(
      command = listOf("cat", "/data/data/com.example/files/state.json"),
      runAs = "com.example",
    )
    val result = tool.execute(createContext(TrailblazeDevicePlatform.ANDROID))

    assertIs<TrailblazeToolResult.Error.ExceptionThrown>(result)
    assertThat(result.errorMessage).contains("'cat' '/data/data/com.example/files/state.json'")
  }

  @Test
  fun `execute() with long command truncates effectiveCommand to 200 chars in error message`() = runBlocking {
    // Each 1-char element becomes `'X'` after shell-escape (3 chars + space separator
    // = 4 chars per element). 80 elements gives a joined length well over 200, so
    // truncation must engage on the join-derived effectiveCommand path.
    val tool = AdbShellTrailblazeTool(command = List(80) { "X" })
    val result = tool.execute(createContext(TrailblazeDevicePlatform.ANDROID))

    assertIs<TrailblazeToolResult.Error.ExceptionThrown>(result)
    // Pull the would-have-run substring out and verify it's exactly 200 chars,
    // matching the `.take(200)` cap in the production code. Anchoring on the literal
    // prefix/suffix avoids brittleness if the rest of the error wording shifts.
    val msg = result.errorMessage
    val prefix = "would have run: '"
    val start = msg.indexOf(prefix) + prefix.length
    val end = msg.indexOf("')", start)
    assertThat(msg.substring(start, end)).hasLength(200)
  }

  /**
   * Pin the dual-mode contract. `AdbShellTrailblazeTool` MUST be a plain
   * [ExecutableTrailblazeTool] (no [HostLocalExecutableTrailblazeTool] marker) and the
   * `@TrailblazeToolClass` annotation MUST have `requiresHost = false` (the default).
   *
   * Either of those flipping would silently break on-device dispatch: a scripted tool
   * that composes `android_adbShell` and is dispatched by the on-device QuickJS runner would
   * fail to find `android_adbShell` in the on-device registry (because host-only tools are
   * filtered out at registration), even though the underlying
   * [xyz.block.trailblaze.device.AndroidDeviceCommandExecutor.executeShellCommand]
   * implementation works on both sides.
   */
  @Test
  fun `is dual-mode (plain ExecutableTrailblazeTool, requiresHost defaulted false)`() {
    val tool = AdbShellTrailblazeTool(command = listOf("pm", "list", "packages"))
    assertThat(tool).isInstanceOf(ExecutableTrailblazeTool::class)
    // Inverse check: must NOT be host-local. Without this assertion, accidentally adding
    // `HostLocalExecutableTrailblazeTool` to the implements list would silently regress
    // the dual-mode property; this test would still pass the ExecutableTrailblazeTool
    // check above (since HostLocalExecutableTrailblazeTool extends it).
    assertThat(tool).isNotInstanceOf(HostLocalExecutableTrailblazeTool::class)

    val annotation = AdbShellTrailblazeTool::class.java.getAnnotation(TrailblazeToolClass::class.java)!!
    assertThat(annotation.name).isEqualTo("android_adbShell")
    assertThat(annotation.requiresHost).isEqualTo(false)
    assertThat(annotation.surfaceToLlm).isEqualTo(false)
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // Exit-code sentinel parsing
  //
  // The underlying AndroidDeviceCommandExecutor.executeShellCommand returns only the
  // combined stdout — no exit-code channel. AdbShellTrailblazeTool wraps the joined
  // command with `; echo __TBZ_ADBSHELL_EXIT__$?` and parses the trailing sentinel
  // line out of the output. These tests pin the parser logic (pure function, no
  // executor needed) for every realistic shape: success, non-zero, missing sentinel,
  // multi-line output, output that incidentally contains the token mid-stream.
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  fun `wrapWithExitSentinel appends echo of dollar-question-mark using semicolon (not amp-amp)`() {
    val wrapped = AdbShellTrailblazeTool.wrapWithExitSentinel("am force-stop com.example")
    // Semicolon is load-bearing — `&& echo $?` would skip on non-zero exits and we'd
    // lose the exit code. `;` runs the echo unconditionally so we always get the
    // user command's `$?`.
    assertThat(wrapped).isEqualTo("am force-stop com.example; echo __TBZ_ADBSHELL_EXIT__\$?")
  }

  @Test
  fun `parseExitSentinel returns zero on success`() {
    val parsed = AdbShellTrailblazeTool.parseExitSentinel(
      "package:com.google.android.deskclock\n__TBZ_ADBSHELL_EXIT__0\n",
    )
    assertThat(parsed.output).isEqualTo("package:com.google.android.deskclock")
    assertThat(parsed.exitCode).isEqualTo(0)
  }

  @Test
  fun `parseExitSentinel returns non-zero exit code on failure`() {
    val parsed = AdbShellTrailblazeTool.parseExitSentinel(
      "Error: package 'bogus.pkg' not installed\n__TBZ_ADBSHELL_EXIT__1\n",
    )
    assertThat(parsed.output).isEqualTo("Error: package 'bogus.pkg' not installed")
    assertThat(parsed.exitCode).isEqualTo(1)
  }

  @Test
  fun `parseExitSentinel handles multi-digit exit codes (POSIX 0-255)`() {
    val parsed = AdbShellTrailblazeTool.parseExitSentinel(
      "Killed by signal\n__TBZ_ADBSHELL_EXIT__137\n",
    )
    assertThat(parsed.exitCode).isEqualTo(137)
  }

  @Test
  fun `parseExitSentinel handles output with no trailing newline before sentinel`() {
    val parsed = AdbShellTrailblazeTool.parseExitSentinel(
      "package:com.example\n__TBZ_ADBSHELL_EXIT__0",
    )
    assertThat(parsed.output).isEqualTo("package:com.example")
    assertThat(parsed.exitCode).isEqualTo(0)
  }

  @Test
  fun `parseExitSentinel returns sentinel-missing when output has no marker`() {
    val parsed = AdbShellTrailblazeTool.parseExitSentinel("just some output without the marker")
    assertThat(parsed.exitCode).isEqualTo(AdbShellTrailblazeTool.EXIT_CODE_SENTINEL_MISSING)
    // When the sentinel is missing the raw output is preserved verbatim so the caller
    // can surface it in an actionable error.
    assertThat(parsed.output).isEqualTo("just some output without the marker")
  }

  @Test
  fun `parseExitSentinel ignores token-substring earlier in output (only trailing line counts)`() {
    // A pathological case: the user's command stdout itself contains `__TBZ_ADBSHELL_EXIT__1`
    // somewhere in the middle. The regex anchor `\s*$` ensures we only match the trailing
    // sentinel line; an internal occurrence is treated as part of the command's output.
    val parsed = AdbShellTrailblazeTool.parseExitSentinel(
      "log line 1: __TBZ_ADBSHELL_EXIT__999 (this is from the user command, not our sentinel)\n" +
        "log line 2\n" +
        "__TBZ_ADBSHELL_EXIT__0\n",
    )
    // The trailing line is the real sentinel — exit code 0.
    assertThat(parsed.exitCode).isEqualTo(0)
    // The internal occurrence stays in the output.
    assertThat(parsed.output).contains("__TBZ_ADBSHELL_EXIT__999")
    assertThat(parsed.output).contains("log line 2")
  }

  @Test
  fun `parseExitSentinel preserves multi-line output verbatim before stripping the sentinel`() {
    val parsed = AdbShellTrailblazeTool.parseExitSentinel(
      "package:com.android.systemui\npackage:com.google.android.gms\npackage:com.example.app\n" +
        "__TBZ_ADBSHELL_EXIT__0\n",
    )
    assertThat(parsed.output).isEqualTo(
      "package:com.android.systemui\npackage:com.google.android.gms\npackage:com.example.app",
    )
    assertThat(parsed.exitCode).isEqualTo(0)
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // joinCommandAsShellString: POSIX single-quote escape, then space-join. The wrapping
  // single quotes make every interior shell metacharacter literal — `$`, backtick, `;`,
  // `&&`, newline, space, `*`, `~`. The only character that needs special handling is
  // the single quote itself, which gets the standard `'\''` dance (close-quote, escaped
  // literal, reopen-quote). Delegates to the shared `String.shellEscape()` helper.
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  fun `joinCommandAsShellString wraps a plain argument in single quotes`() {
    assertThat(AdbShellTrailblazeTool.joinCommandAsShellString(listOf("pm")))
      .isEqualTo("'pm'")
  }

  @Test
  fun `joinCommandAsShellString joins multiple arguments with spaces`() {
    assertThat(AdbShellTrailblazeTool.joinCommandAsShellString(listOf("pm", "list", "packages")))
      .isEqualTo("'pm' 'list' 'packages'")
  }

  @Test
  fun `joinCommandAsShellString escapes embedded single quote via close-escape-reopen dance`() {
    // The POSIX trick: `it's` becomes `'it'\''s'` — closes the wrapping quote, emits an
    // escaped literal single quote, reopens the wrapping quote. Functionally equivalent
    // to `it's` to the shell.
    assertThat(AdbShellTrailblazeTool.joinCommandAsShellString(listOf("it's")))
      .isEqualTo("'it'\\''s'")
  }

  @Test
  fun `joinCommandAsShellString preserves double quotes literally inside single-quote wrapper`() {
    assertThat(AdbShellTrailblazeTool.joinCommandAsShellString(listOf("say \"hi\"")))
      .isEqualTo("'say \"hi\"'")
  }

  @Test
  fun `joinCommandAsShellString preserves dollar sign literally (no parameter expansion)`() {
    assertThat(AdbShellTrailblazeTool.joinCommandAsShellString(listOf("\$HOME")))
      .isEqualTo("'\$HOME'")
  }

  @Test
  fun `joinCommandAsShellString preserves backtick literally (no command substitution)`() {
    assertThat(AdbShellTrailblazeTool.joinCommandAsShellString(listOf("`whoami`")))
      .isEqualTo("'`whoami`'")
  }

  @Test
  fun `joinCommandAsShellString preserves semicolon literally (no statement separator)`() {
    // Injection-safety probe: a naive concat would turn `; rm -rf ~` into a separate
    // statement. With single-quote wrapping, the semicolon is just a literal byte.
    assertThat(AdbShellTrailblazeTool.joinCommandAsShellString(listOf("; rm -rf ~")))
      .isEqualTo("'; rm -rf ~'")
  }

  @Test
  fun `joinCommandAsShellString preserves spaces inside a single argument`() {
    // The argument boundary is the list element, not whitespace — `hello world` as one
    // element produces one quoted token, not two.
    assertThat(AdbShellTrailblazeTool.joinCommandAsShellString(listOf("hello world")))
      .isEqualTo("'hello world'")
  }

  @Test
  fun `joinCommandAsShellString preserves newline literally`() {
    assertThat(AdbShellTrailblazeTool.joinCommandAsShellString(listOf("line1\nline2")))
      .isEqualTo("'line1\nline2'")
  }

  @Test
  fun `joinCommandAsShellString preserves glob characters literally (no expansion)`() {
    assertThat(AdbShellTrailblazeTool.joinCommandAsShellString(listOf("*.txt")))
      .isEqualTo("'*.txt'")
  }

  @Test
  fun `joinCommandAsShellString preserves empty-string element as empty single-quoted token`() {
    // A list with one empty element is a legal argv slot (think `sh -c ''`); render it
    // as `''` rather than dropping it, so the joined command preserves arity.
    assertThat(AdbShellTrailblazeTool.joinCommandAsShellString(listOf("")))
      .isEqualTo("''")
  }

  @Test
  fun `command composes with wrapWithExitSentinel (joined string flows through unchanged)`() {
    // Sanity check: command list → join → sentinel-wrap produces what we expect when
    // handed to the device shell. The sentinel parser doesn't care about the inner
    // structure.
    val joined = AdbShellTrailblazeTool.joinCommandAsShellString(listOf("am", "force-stop", "com.example"))
    val wrapped = AdbShellTrailblazeTool.wrapWithExitSentinel(joined)
    assertThat(wrapped).isEqualTo("'am' 'force-stop' 'com.example'; echo __TBZ_ADBSHELL_EXIT__\$?")
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // joinCommandRawArgv: the no-shell (on-device) render. Raw space-join, NO escaping.
  // This is the path taken when AndroidDeviceCommandExecutor.usesShellInterpreter is
  // false (UiAutomation → Runtime.exec). These tests pin the exact regression: shell-
  // escaping `su` made the program name the literal `'su'`, which the device could not
  // exec — so the raw render must NOT quote.
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  fun `joinCommandRawArgv joins tokens with spaces and does not quote`() {
    assertThat(AdbShellTrailblazeTool.joinCommandRawArgv(listOf("pm", "list", "packages")))
      .isEqualTo("pm list packages")
  }

  @Test
  fun `joinCommandRawArgv keeps su as the bare program name for a privileged package-disable`() {
    // Regression anchor. On the shell-less on-device transport, Runtime.exec execs the first
    // whitespace-delimited token as the program. The raw render must leave `su` unquoted; the
    // shell-escaped render (used only on the host transport) would produce `'su'`, which the
    // device tried to exec literally → "Cannot run program \"'su'\"" → the launch hung.
    // (Generic placeholder package id — the real authenticator is named in the app-specific tool.)
    val command = listOf("su", "root", "pm", "disable", "com.vendor.deviceauth")

    assertThat(AdbShellTrailblazeTool.joinCommandRawArgv(command))
      .isEqualTo("su root pm disable com.vendor.deviceauth")
    // Contrast: the shell render quotes every token — correct for `sh -c`, fatal for Runtime.exec.
    assertThat(AdbShellTrailblazeTool.joinCommandAsShellString(command))
      .isEqualTo("'su' 'root' 'pm' 'disable' 'com.vendor.deviceauth'")
  }

  @Test
  fun `whitespaceBearingTokens flags only the tokens that cannot survive the no-shell transport`() {
    // Clean argv → empty (every element is one token Runtime.exec won't re-split).
    assertThat(AdbShellTrailblazeTool.whitespaceBearingTokens(listOf("pm", "disable", "com.x")))
      .isEqualTo(emptyList())
    // An element with an embedded space (or tab) is the failure mode: Runtime.exec would re-split
    // it into two tokens with no shell. The on-device guard rejects these for both the runAs and
    // non-runAs branches so they fail loud identically instead of silently mis-executing.
    assertThat(AdbShellTrailblazeTool.whitespaceBearingTokens(listOf("pm", "list packages", "x\ty")))
      .isEqualTo(listOf("list packages", "x\ty"))
  }

  @Test
  fun `on-device shell timeout is a positive bound well under the session inactivity watchdog`() {
    // The watchdog abandons a silent session at ~13 min; the bound must fail fast before that so a
    // wedged exec surfaces as an error instead of a multi-minute hang.
    assertThat(AdbShellTrailblazeTool.ON_DEVICE_SHELL_TIMEOUT_MS).isEqualTo(60_000L)
  }

  @Test
  fun `round-trips command through YAML encode-then-decode`() {
    // Pins that building the tool in code, encoding to YAML, and decoding back yields
    // an equal tool. Catches any future serializer drift (e.g. emitting `command: null`
    // for an empty-but-present list) that would break recorded trail baselines.
    val original = AdbShellTrailblazeTool(command = listOf("am", "force-stop", "com.example.app"))
    val yamlInstance = trailblazeYaml.getInstance()
    val encoded = yamlInstance.encodeToString(AdbShellTrailblazeTool.serializer(), original)
    val decoded = yamlInstance.decodeFromString(AdbShellTrailblazeTool.serializer(), encoded)
    assertThat(decoded).isEqualTo(original)
  }

  @Test
  fun `round-trips command + runAs through YAML encode-then-decode`() {
    val original = AdbShellTrailblazeTool(
      command = listOf("cat", "/data/data/com.example/files/state.json"),
      runAs = "com.example",
    )
    val yamlInstance = trailblazeYaml.getInstance()
    val encoded = yamlInstance.encodeToString(AdbShellTrailblazeTool.serializer(), original)
    val decoded = yamlInstance.decodeFromString(AdbShellTrailblazeTool.serializer(), encoded)
    assertThat(decoded).isEqualTo(original)
  }

  private fun createContext(platform: TrailblazeDevicePlatform): TrailblazeToolExecutionContext {
    val driverType = when (platform) {
      TrailblazeDevicePlatform.ANDROID -> TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION
      TrailblazeDevicePlatform.IOS -> TrailblazeDriverType.IOS_HOST
      TrailblazeDevicePlatform.WEB -> TrailblazeDriverType.PLAYWRIGHT_NATIVE
      TrailblazeDevicePlatform.DESKTOP -> TrailblazeDriverType.COMPOSE
    }
    return TrailblazeToolExecutionContext(
      screenState = null,
      traceId = null,
      trailblazeDeviceInfo = TrailblazeDeviceInfo(
        trailblazeDeviceId = TrailblazeDeviceId(
          instanceId = "test-device",
          trailblazeDevicePlatform = platform,
        ),
        trailblazeDriverType = driverType,
        widthPixels = 1080,
        heightPixels = 1920,
      ),
      sessionProvider = TrailblazeSessionProvider {
        TrailblazeSession(sessionId = SessionId("test-session"), startTime = Clock.System.now())
      },
      trailblazeLogger = TrailblazeLogger.createNoOp(),
      memory = AgentMemory(),
    )
  }
}
