package xyz.block.trailblaze.mobile.tools

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
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
 * require a real `AndroidDeviceCommandExecutor` — non-Android platform, blank command,
 * missing executor. Happy-path execution is covered by the local-emulator integration
 * pass on the clock OSS example trail (`clock_android_launchApp.ts` composes `adbShell`
 * end-to-end), which is the load-bearing validation for the executor shell-out.
 *
 * The dual-mode contract (`requiresHost = false`, implements [ExecutableTrailblazeTool]
 * not [HostLocalExecutableTrailblazeTool]) is pinned by reflection so a future refactor
 * that accidentally adds the host-only marker fails this test instead of silently
 * routing all `adbShell` calls through the host RPC path even when on-device dispatch
 * is appropriate.
 */
class AdbShellTrailblazeToolTest {

  private val trailblazeYaml = createTrailblazeYaml(setOf(AdbShellTrailblazeTool::class))

  @Test
  fun `decodes minimal command from trail YAML`() {
    val yaml = """
      - tools:
          - adbShell:
              command: am force-stop com.example.app
    """.trimIndent()

    val tool = (trailblazeYaml.decodeTrail(yaml).single() as TrailYamlItem.ToolTrailItem)
      .tools.single().trailblazeTool as AdbShellTrailblazeTool

    assertThat(tool.command).isEqualTo("am force-stop com.example.app")
    assertThat(tool.runAs).isEqualTo(null)
  }

  @Test
  fun `decodes runAs override from trail YAML`() {
    val yaml = """
      - tools:
          - adbShell:
              command: cat /data/data/com.example.app/files/state.json
              runAs: com.example.app
    """.trimIndent()

    val tool = (trailblazeYaml.decodeTrail(yaml).single() as TrailYamlItem.ToolTrailItem)
      .tools.single().trailblazeTool as AdbShellTrailblazeTool

    assertThat(tool.command).isEqualTo("cat /data/data/com.example.app/files/state.json")
    assertThat(tool.runAs).isEqualTo("com.example.app")
  }

  @Test
  fun `executeReturnsErrorOnNonAndroidPlatform`() = runBlocking {
    val tool = AdbShellTrailblazeTool(command = "pm list packages")
    val result = tool.execute(createContext(TrailblazeDevicePlatform.IOS))

    assertIs<TrailblazeToolResult.Error.ExceptionThrown>(result)
    assertThat(result.errorMessage).contains("only supported on Android")
  }

  @Test
  fun `executeReturnsErrorOnWebPlatform`() = runBlocking {
    val tool = AdbShellTrailblazeTool(command = "pm list packages")
    val result = tool.execute(createContext(TrailblazeDevicePlatform.WEB))

    assertIs<TrailblazeToolResult.Error.ExceptionThrown>(result)
    assertThat(result.errorMessage).contains("only supported on Android")
  }

  @Test
  fun `executeReturnsErrorOnBlankCommand`() = runBlocking {
    val tool = AdbShellTrailblazeTool(command = "   ")
    val result = tool.execute(createContext(TrailblazeDevicePlatform.ANDROID))

    assertIs<TrailblazeToolResult.Error.ExceptionThrown>(result)
    assertThat(result.errorMessage).contains("non-blank command")
  }

  @Test
  fun `executeReturnsErrorWhenExecutorIsMissing`() = runBlocking {
    val tool = AdbShellTrailblazeTool(command = "pm list packages")
    // Android platform, valid command, but no AndroidDeviceCommandExecutor wired in —
    // the same scenario unit tests hit (real executor lives behind dadb / instrumentation).
    val result = tool.execute(createContext(TrailblazeDevicePlatform.ANDROID))

    assertIs<TrailblazeToolResult.Error.ExceptionThrown>(result)
    assertThat(result.errorMessage).contains("AndroidDeviceCommandExecutor")
  }

  /**
   * Pin the dual-mode contract. `AdbShellTrailblazeTool` MUST be a plain
   * [ExecutableTrailblazeTool] (no [HostLocalExecutableTrailblazeTool] marker) and the
   * `@TrailblazeToolClass` annotation MUST have `requiresHost = false` (the default).
   *
   * Either of those flipping would silently break on-device dispatch: a scripted tool
   * that composes `adbShell` and is dispatched by the on-device QuickJS runner would
   * fail to find `adbShell` in the on-device registry (because host-only tools are
   * filtered out at registration), even though the underlying
   * [xyz.block.trailblaze.device.AndroidDeviceCommandExecutor.executeShellCommand]
   * implementation works on both sides.
   */
  // ─────────────────────────────────────────────────────────────────────────────
  // Exit-code sentinel parsing
  //
  // The underlying AndroidDeviceCommandExecutor.executeShellCommand returns only the
  // combined stdout — no exit-code channel. AdbShellTrailblazeTool wraps the user's
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

  @Test
  fun `is dual-mode (plain ExecutableTrailblazeTool, requiresHost defaulted false)`() {
    val tool = AdbShellTrailblazeTool(command = "pm list packages")
    assertThat(tool).isInstanceOf(ExecutableTrailblazeTool::class)
    // Inverse check: must NOT be host-local. Without this assertion, accidentally adding
    // `HostLocalExecutableTrailblazeTool` to the implements list would silently regress
    // the dual-mode property; this test would still pass the ExecutableTrailblazeTool
    // check above (since HostLocalExecutableTrailblazeTool extends it).
    assertk.assertThat(tool !is HostLocalExecutableTrailblazeTool).isEqualTo(true)

    val annotation = AdbShellTrailblazeTool::class.java.getAnnotation(TrailblazeToolClass::class.java)!!
    assertThat(annotation.name).isEqualTo("adbShell")
    assertThat(annotation.requiresHost).isEqualTo(false)
    assertThat(annotation.isForLlm).isEqualTo(false)
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
