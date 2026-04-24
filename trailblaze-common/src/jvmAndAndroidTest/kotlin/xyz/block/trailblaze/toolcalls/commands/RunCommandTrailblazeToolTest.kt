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
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.util.isWindows

/**
 * Smoke coverage for [RunCommandTrailblazeTool] exercising each branch of `execute`:
 * happy-path success, non-zero exit, timeout kill, regex filtering on success, regex
 * bypass on failure, and working-directory honoring.
 *
 * Tests target `sh -c`, so they are skipped on Windows — the tool itself supports
 * `cmd /c` but there is no Windows CI to validate against.
 */
class RunCommandTrailblazeToolTest {

  @Before
  fun skipOnWindows() {
    assumeFalse("RunCommandTrailblazeTool sh-based tests skip on Windows", isWindows())
  }

  @Test
  fun `happy path returns combined stdout in Success message`() = runBlocking {
    val result = RunCommandTrailblazeTool(command = "echo hello").execute(createContext())

    assertThat(result).isInstanceOf(TrailblazeToolResult.Success::class)
    assertThat((result as TrailblazeToolResult.Success).message).isEqualTo("hello")
  }

  @Test
  fun `non-zero exit maps to ExceptionThrown with full output`() = runBlocking {
    val result = RunCommandTrailblazeTool(
      command = "echo something-went-wrong; exit 3",
    ).execute(createContext())

    assertThat(result).isInstanceOf(TrailblazeToolResult.Error.ExceptionThrown::class)
    val err = result as TrailblazeToolResult.Error.ExceptionThrown
    assertThat(err.errorMessage).contains("exited with 3")
    assertThat(err.errorMessage).contains("something-went-wrong")
  }

  @Test
  fun `timeout kills subprocess and surfaces timeout error`() = runBlocking {
    val result = RunCommandTrailblazeTool(
      command = "sleep 10",
      timeoutSeconds = 1,
    ).execute(createContext())

    assertThat(result).isInstanceOf(TrailblazeToolResult.Error.ExceptionThrown::class)
    assertThat((result as TrailblazeToolResult.Error.ExceptionThrown).errorMessage)
      .contains("timed out after 1s")
  }

  @Test
  fun `outputFilterRegex drops non-matching lines on success`() = runBlocking {
    val result = RunCommandTrailblazeTool(
      command = "printf 'alpha\\nbeta\\ngamma\\n'",
      outputFilterRegex = "^(alpha|gamma)$",
    ).execute(createContext())

    assertThat(result).isInstanceOf(TrailblazeToolResult.Success::class)
    val message = (result as TrailblazeToolResult.Success).message
    assertThat(message).isEqualTo("alpha\ngamma")
  }

  @Test
  fun `outputFilterRegex is bypassed on failure so debug context is preserved`() = runBlocking {
    val result = RunCommandTrailblazeTool(
      command = "echo critical-diagnostic; exit 1",
      outputFilterRegex = "will-never-match",
    ).execute(createContext())

    assertThat(result).isInstanceOf(TrailblazeToolResult.Error.ExceptionThrown::class)
    val err = result as TrailblazeToolResult.Error.ExceptionThrown
    assertThat(err.errorMessage).contains("critical-diagnostic")
    assertThat(err.errorMessage).doesNotContain("will-never-match")
  }

  @Test
  fun `non-default expectedExitCode match is treated as Success`(): Unit = runBlocking {
    val result = RunCommandTrailblazeTool(
      command = "exit 42",
      expectedExitCode = 42,
    ).execute(createContext())

    assertThat(result).isInstanceOf(TrailblazeToolResult.Success::class)
  }

  @Test
  fun `workingDir is honored when running pwd`() = runBlocking {
    // `/tmp` exists on every POSIX host we target; on macOS it's a symlink to /private/tmp,
    // so we assert the suffix rather than an exact match.
    val result = RunCommandTrailblazeTool(
      command = "pwd",
      workingDir = "/tmp",
    ).execute(createContext())

    assertThat(result).isInstanceOf(TrailblazeToolResult.Success::class)
    assertThat((result as TrailblazeToolResult.Success).message!!).contains("/tmp")
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
