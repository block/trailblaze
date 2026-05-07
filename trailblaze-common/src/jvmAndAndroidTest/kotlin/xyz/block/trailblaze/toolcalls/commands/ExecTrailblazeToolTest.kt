package xyz.block.trailblaze.toolcalls.commands

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import org.junit.Assume.assumeFalse
import org.junit.Before
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
import xyz.block.trailblaze.toolcalls.HostLocalExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.util.isWindows

/**
 * Smoke coverage for [ExecTrailblazeTool] exercising each branch of `execute`:
 * happy-path success, non-zero exit, timeout kill, regex filtering on success, regex
 * bypass on failure, working-directory honoring, and the load-bearing argv-safety
 * contract that distinguishes `exec` from [RunCommandTrailblazeTool] (no shell
 * evaluation of argv elements, even when they contain shell metacharacters).
 *
 * Tests target POSIX semantics, so they are skipped on Windows. The implementation
 * itself is platform-agnostic — `ProcessBuilder(argv)` works the same on Windows for
 * argv that maps to a real executable.
 */
class ExecTrailblazeToolTest {

  @Before
  fun skipOnWindows() {
    assumeFalse("ExecTrailblazeTool POSIX-shape tests skip on Windows", isWindows())
  }

  @Test
  fun `happy path returns combined stdout in Success message`() = runBlocking {
    val result = ExecTrailblazeTool(argv = listOf("echo", "hello")).execute(createContext())

    assertThat(result).isInstanceOf(TrailblazeToolResult.Success::class)
    assertThat((result as TrailblazeToolResult.Success).message).isEqualTo("hello")
  }

  @Test
  fun `non-zero exit maps to ExceptionThrown with full output`() = runBlocking {
    val result = ExecTrailblazeTool(
      argv = listOf("sh", "-c", "echo something-went-wrong; exit 3"),
    ).execute(createContext())

    assertThat(result).isInstanceOf(TrailblazeToolResult.Error.ExceptionThrown::class)
    val err = result as TrailblazeToolResult.Error.ExceptionThrown
    assertThat(err.errorMessage).contains("exited with 3")
    assertThat(err.errorMessage).contains("something-went-wrong")
  }

  @Test
  fun `timeout kills subprocess and surfaces timeout error`() = runBlocking {
    val result = ExecTrailblazeTool(
      argv = listOf("sleep", "10"),
      timeoutSeconds = 1,
    ).execute(createContext())

    assertThat(result).isInstanceOf(TrailblazeToolResult.Error.ExceptionThrown::class)
    assertThat((result as TrailblazeToolResult.Error.ExceptionThrown).errorMessage)
      .contains("timed out after 1s")
  }

  @Test
  fun `outputFilterRegex drops non-matching lines on success`() = runBlocking {
    val result = ExecTrailblazeTool(
      argv = listOf("printf", "alpha\nbeta\ngamma\n"),
      outputFilterRegex = "^(alpha|gamma)$",
    ).execute(createContext())

    assertThat(result).isInstanceOf(TrailblazeToolResult.Success::class)
    val message = (result as TrailblazeToolResult.Success).message
    assertThat(message).isEqualTo("alpha\ngamma")
  }

  @Test
  fun `outputFilterRegex is bypassed on failure so debug context is preserved`() = runBlocking {
    val result = ExecTrailblazeTool(
      argv = listOf("sh", "-c", "echo critical-diagnostic; exit 1"),
      outputFilterRegex = "will-never-match",
    ).execute(createContext())

    assertThat(result).isInstanceOf(TrailblazeToolResult.Error.ExceptionThrown::class)
    val err = result as TrailblazeToolResult.Error.ExceptionThrown
    assertThat(err.errorMessage).contains("critical-diagnostic")
    assertThat(err.errorMessage).doesNotContain("will-never-match")
  }

  @Test
  fun `non-default expectedExitCode match is treated as Success`(): Unit = runBlocking {
    val result = ExecTrailblazeTool(
      argv = listOf("sh", "-c", "exit 42"),
      expectedExitCode = 42,
    ).execute(createContext())

    assertThat(result).isInstanceOf(TrailblazeToolResult.Success::class)
  }

  @Test
  fun `workingDir is honored when running pwd`() = runBlocking {
    // `/tmp` exists on every POSIX host we target; on macOS it's a symlink to /private/tmp,
    // so we assert the suffix rather than an exact match.
    val result = ExecTrailblazeTool(
      argv = listOf("pwd"),
      workingDir = "/tmp",
    ).execute(createContext())

    assertThat(result).isInstanceOf(TrailblazeToolResult.Success::class)
    assertThat((result as TrailblazeToolResult.Success).message!!).contains("/tmp")
  }

  /**
   * The load-bearing reason `exec` exists alongside `runCommand`: argv elements MUST be
   * passed verbatim to the executable, never re-evaluated by a shell. This test pins the
   * contract by passing a single argv element that contains shell metacharacters
   * (`;`, `&&`, `$()`, backticks) — under shell evaluation those would be interpreted as
   * command terminators / substitutions. Under `exec`'s argv-form, they must round-trip
   * to stdout as literal characters.
   *
   * If this test ever fails, it means a future refactor accidentally routed `exec`
   * through a shell. Tool authors composing `exec` from scripted tools rely on this
   * contract — a templated parameter value can't smuggle a `; rm -rf /` past the
   * argv boundary.
   */
  @Test
  fun `argv elements are not shell-evaluated even when they contain metacharacters`() = runBlocking {
    val poisonous = "alpha; rm -rf / && echo \$(whoami) `date`"
    val result = ExecTrailblazeTool(argv = listOf("echo", poisonous)).execute(createContext())

    assertThat(result).isInstanceOf(TrailblazeToolResult.Success::class)
    val message = (result as TrailblazeToolResult.Success).message
    // The output must be EXACTLY the poisonous string — no command substitution, no
    // semicolon-chained execution, no backtick expansion. `echo` adds a trailing newline
    // which `RunCommandTrailblazeTool`/`ExecTrailblazeTool` strip via line-based read.
    assertThat(message).isEqualTo(poisonous)
  }

  @Test
  fun `empty argv returns ExceptionThrown with actionable message`() = runBlocking {
    val result = ExecTrailblazeTool(argv = emptyList()).execute(createContext())

    assertThat(result).isInstanceOf(TrailblazeToolResult.Error.ExceptionThrown::class)
    val err = result as TrailblazeToolResult.Error.ExceptionThrown
    assertThat(err.errorMessage).contains("non-empty argv")
  }

  /**
   * Pin the host-only contract. `ExecTrailblazeTool` MUST stay
   * [HostLocalExecutableTrailblazeTool] + `requiresHost = true` — flipping either
   * would break the routing in `HostAccessibilityRpcClient` (which short-circuits
   * host-locals to in-process dispatch instead of trying to RPC them to a device that
   * has no JVM to fork from).
   */
  @Test
  fun `is host-local executable and annotated requiresHost true`() {
    val tool = ExecTrailblazeTool(argv = listOf("echo", "x"))
    assertThat(tool).isInstanceOf(HostLocalExecutableTrailblazeTool::class)

    val annotation = ExecTrailblazeTool::class.java.getAnnotation(TrailblazeToolClass::class.java)!!
    assertThat(annotation.name).isEqualTo("exec")
    assertThat(annotation.requiresHost).isEqualTo(true)
    assertThat(annotation.isForLlm).isEqualTo(false)
  }

  private fun createContext(): TrailblazeToolExecutionContext = TrailblazeToolExecutionContext(
    screenState = null,
    traceId = null,
    trailblazeDeviceInfo = TrailblazeDeviceInfo(
      trailblazeDeviceId = TrailblazeDeviceId(
        instanceId = "test-device",
        trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
      ),
      trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
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
