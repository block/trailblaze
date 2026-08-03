package xyz.block.trailblaze.mobile.tools

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.hasLength
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotInstanceOf
import assertk.assertions.startsWith
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import org.junit.Test
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.device.decodeShellTrampoline
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
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

  /** The recorded step's single tool, as the adb-shell tool under test. */
  private fun decodeSingleTool(yaml: String): AdbShellTrailblazeTool =
    trailblazeYaml.decodeTrail(yaml, deviceClassifiers = listOf(TrailblazeDeviceClassifier("android")))
      .filterIsInstance<TrailYamlItem.PromptsTrailItem>().single()
      .promptSteps.single().recording!!.tools.single()
      .trailblazeTool as AdbShellTrailblazeTool

  @Test
  fun `decodes minimal command from trail YAML`() {
    val yaml = """
      config: {}
      trail:
        - step: recorded
          recording:
            android:
              - android_adbShell:
                  command:
                    - am
                    - force-stop
                    - com.example.app
    """.trimIndent()

    val tool = decodeSingleTool(yaml)

    assertThat(tool.command).isEqualTo(listOf("am", "force-stop", "com.example.app"))
    assertThat(tool.runAs).isEqualTo(null)
  }

  @Test
  fun `decodes runAs override from trail YAML`() {
    val yaml = """
      config: {}
      trail:
        - step: recorded
          recording:
            android:
              - android_adbShell:
                  command:
                    - cat
                    - /data/data/com.example.app/files/state.json
                  runAs: com.example.app
    """.trimIndent()

    val tool = decodeSingleTool(yaml)

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
  // command with `; printf '\n__TBZ_ADBSHELL_EXIT__%s\n' $?` and parses the trailing sentinel
  // line out of the output. These tests pin the parser logic (pure function, no
  // executor needed) for every realistic shape: success, non-zero, missing sentinel,
  // multi-line output, output that incidentally contains the token mid-stream.
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  fun `wrapWithExitSentinel appends printf of dollar-question-mark using semicolon (not amp-amp)`() {
    val wrapped = AdbShellTrailblazeTool.wrapWithExitSentinel("am force-stop com.example")
    // Semicolon is load-bearing — `&& printf` would skip on non-zero exits and we'd
    // lose the exit code. The leading `\n` in the printf format is equally load-bearing:
    // it forces the sentinel onto a fresh line even when the command's stdout doesn't
    // end in a newline, so the line-anchored parser can always find it. A single printf
    // (not `echo; echo $?`) keeps `$?` intact — an intermediate command would reset it.
    assertThat(wrapped)
      .isEqualTo("am force-stop com.example; printf '\\n__TBZ_ADBSHELL_EXIT__%s\\n' \$?")
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
  fun `parseExitSentinel handles stdout without its own trailing newline (wrap forces the sentinel onto a fresh line)`() {
    // A command like `printf foo` emits no trailing newline. The wrap's leading `\n`
    // (see wrapWithExitSentinel) is what puts the sentinel on its own line; the device
    // output then looks like this — regression for the glued-sentinel bug where
    // `foo__TBZ_ADBSHELL_EXIT__0` was misreported as sentinel-missing.
    val parsed = AdbShellTrailblazeTool.parseExitSentinel("foo\n__TBZ_ADBSHELL_EXIT__0\n")
    assertThat(parsed.output).isEqualTo("foo")
    assertThat(parsed.exitCode).isEqualTo(0)
  }

  @Test
  fun `parseExitSentinel tolerates the blank line the wrap adds when stdout already ends with a newline`() {
    // When the command's stdout DOES end with a newline, the wrap's leading `\n`
    // produces one empty line before the sentinel. It must be trimmed away, not
    // surfaced as command output.
    val parsed = AdbShellTrailblazeTool.parseExitSentinel("hello\n\n__TBZ_ADBSHELL_EXIT__0\n")
    assertThat(parsed.output).isEqualTo("hello")
    assertThat(parsed.exitCode).isEqualTo(0)
  }

  @Test
  fun `parseExitSentinel takes the LAST sentinel-shaped line when user output contains a full one`() {
    // Pathological: the user's command prints a line that is exactly sentinel-shaped.
    // The real sentinel is always the final thing the wrapped command emits, so the
    // last match wins and the earlier fake stays in the output verbatim.
    val parsed = AdbShellTrailblazeTool.parseExitSentinel(
      "__TBZ_ADBSHELL_EXIT__999\nreal output\n__TBZ_ADBSHELL_EXIT__0\n",
    )
    assertThat(parsed.exitCode).isEqualTo(0)
    assertThat(parsed.output).isEqualTo("__TBZ_ADBSHELL_EXIT__999\nreal output")
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
    assertThat(wrapped)
      .isEqualTo("'am' 'force-stop' 'com.example'; printf '\\n__TBZ_ADBSHELL_EXIT__%s\\n' \$?")
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // buildShellTrampolineCommand: the no-shell (on-device) render. The sentinel-wrapped
  // shell string rides the shared wrapShellPipelineForTransport trampoline as ONE
  // whitespace-free `sh -c` token, so it survives Runtime.exec's whitespace split and a
  // real device-side `sh` evaluates it — same shell semantics + exit sentinel as the
  // host transport. These tests decode the payload back out (via the shared
  // decodeShellTrampoline simulation) to pin that contract.
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  fun `on-device transport evaluates the same sentinel-wrapped command as the host transport`() {
    val joined = AdbShellTrailblazeTool.joinCommandAsShellString(listOf("am", "force-stop", "com.example"))

    val script = decodeShellTrampoline(AdbShellTrailblazeTool.buildShellTrampolineCommand(joined))

    // The device-side `sh` runs exactly what the host transport's `sh -c` gets — the same
    // sentinel-wrapped string — so a non-zero exit is observable on both transports. (The exact
    // sentinel literal is pinned by the wrapWithExitSentinel tests above.)
    assertThat(script).isEqualTo(AdbShellTrailblazeTool.wrapWithExitSentinel(joined))
  }

  @Test
  fun `su keeps its program-name role through the trampoline for a privileged package-disable`() {
    // Regression anchor for the case that forced the old raw-argv split: with no shell,
    // shell-escaping made the program name the literal `'su'` (→ "Cannot run program \"'su'\"").
    // With the trampoline a real `sh` evaluates the quoting, so `su` execs correctly AND the
    // exit sentinel reports failures the raw dispatch silently swallowed.
    // (Generic placeholder package id — the real authenticator is named in the app-specific tool.)
    val joined = AdbShellTrailblazeTool.joinCommandAsShellString(
      listOf("su", "root", "pm", "disable", "com.vendor.deviceauth"),
    )

    val script = decodeShellTrampoline(AdbShellTrailblazeTool.buildShellTrampolineCommand(joined))

    // `su` stays a quoted token inside the script (evaluated by the device-side `sh`, so the
    // quoting is honored instead of becoming part of the program name), and the script is the
    // same sentinel-wrapped string the host transport dispatches.
    assertThat(script).isEqualTo(AdbShellTrailblazeTool.wrapWithExitSentinel(joined))
    assertThat(script).startsWith("'su' 'root'")
  }

  @Test
  fun `whitespace-bearing tokens survive the trampoline intact`() {
    // The old raw-argv dispatch had to REJECT these (Runtime.exec re-splits on whitespace with no
    // shell to honor quoting). Inside the trampoline the whole script rides base64-packed, so the
    // embedded space is preserved and the device-side `sh` sees one quoted argv token.
    val joined = AdbShellTrailblazeTool.joinCommandAsShellString(listOf("log", "-t", "tag", "two words"))

    val script = decodeShellTrampoline(AdbShellTrailblazeTool.buildShellTrampolineCommand(joined))

    assertThat(script).isEqualTo(AdbShellTrailblazeTool.wrapWithExitSentinel(joined))
    assertThat(script).contains("'two words'")
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
