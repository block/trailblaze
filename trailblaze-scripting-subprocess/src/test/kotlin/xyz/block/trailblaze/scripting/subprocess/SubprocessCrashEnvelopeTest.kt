package xyz.block.trailblaze.scripting.subprocess

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isInstanceOf
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonObject
import org.junit.Assume.assumeTrue
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.config.McpServerConfig
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.toolcalls.ToolName
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test

/**
 * End-to-end coverage for the README claim under `sdks/typescript/README.md`
 * § "Error handling — how failures become session-log entries": when a subprocess MCP
 * server dies mid-dispatch, the resulting `TrailblazeToolResult.Error.FatalError` carries
 * the subprocess exit code AND a tail of its stderr in `errorMessage`.
 *
 * Existing coverage:
 *
 *  - [StderrCaptureTest] proves the tail-capture mechanism itself.
 *  - [MapDispatchFailureTest] proves the `buildCrashMessage` string assembly when fed
 *    synthetic inputs.
 *
 * Neither pins the wire-up: spawn a real subprocess, dispatch a tool that triggers a
 * non-zero exit + stderr emission, observe that `SubprocessTrailblazeTool.execute`'s
 * `mapDispatchFailure(... isAlive = false ...)` branch fires AND that the captured exit
 * code + stderr tail both land in the returned envelope. Without this test, a regression
 * that drops the exit code (or stops feeding the stderr tail in) would only surface
 * during a real post-incident debugging session.
 *
 * Skipped when bun/tsx isn't on PATH — matches [SubprocessRuntimeEndToEndTest]'s gate so
 * the suite stays green on environments without a Node-compatible runtime.
 */
class SubprocessCrashEnvelopeTest {

  private val crashFixture: File by lazy {
    val url = requireNotNull(javaClass.getResource("/mcp-fixture/fixture-crashes.js")) {
      "Missing /mcp-fixture/fixture-crashes.js on classpath — Gradle copy tasks out of sync?"
    }
    File(url.toURI())
  }

  private val deviceInfo = TrailblazeDeviceInfo(
    trailblazeDeviceId = TrailblazeDeviceId("crash-envelope-test", TrailblazeDevicePlatform.ANDROID),
    trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
    widthPixels = 1080,
    heightPixels = 2400,
  )

  private val spawnContext = McpSpawnContext(
    platform = TrailblazeDevicePlatform.ANDROID,
    driver = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
    widthPixels = 1080,
    heightPixels = 2400,
    sessionId = SessionId("crash_envelope_test_session"),
  )

  @Test fun `subprocess crash during dispatch surfaces as FatalError with exit code and stderr tail`() {
    runBlocking {
      assumeTrue(
        "bun or tsx must be on PATH to exercise the e2e subprocess runtime",
        runtimeAvailable(),
      )

      val spawned = McpSubprocessSpawner.spawn(
        config = McpServerConfig(script = crashFixture.absolutePath),
        context = spawnContext,
        anchor = crashFixture.parentFile,
      )
      val session = McpSubprocessSession.connect(spawnedProcess = spawned)
      try {
        val tool = SubprocessTrailblazeTool(
          sessionProvider = { session },
          advertisedName = ToolName("crash_on_call"),
          args = JsonObject(emptyMap()),
        )
        val result = tool.execute(buildExecutionContext())

        assertThat(result).isInstanceOf(TrailblazeToolResult.Error.FatalError::class)
        val fatal = result as TrailblazeToolResult.Error.FatalError
        val message = fatal.errorMessage

        // Tool name appears so a session-log reader knows WHICH dispatch died — without
        // this, the envelope is just "a subprocess died, somewhere".
        assertThat(message).contains("'crash_on_call'")
        // Distinctive non-zero exit code from the fixture's `process.exit(42)` — the
        // formatting is the `exit code: $N` shape pinned by `MapDispatchFailureTest`.
        // A regression that drops `exitCode` from `mapDispatchFailure`'s inputs would
        // surface as `exit code: (unavailable)` here.
        assertThat(message).contains("exit code: 42")
        // Both diagnostic stderr lines the fixture wrote must reach the envelope via
        // StderrCapture's ring buffer. Asserting on both defends against an off-by-one
        // that drops the first line, or a flush-ordering bug where the capture is
        // snapshotted before the process's final stderr writes drain.
        assertThat(message).contains("intentional crash diagnostic line one")
        assertThat(message).contains("intentional crash diagnostic line two")
        // The crash-message preamble — pinned alongside the exit code so a refactor that
        // accidentally swaps to the "still-alive transient transport failure" branch
        // (which would produce an ExceptionThrown without the preamble) trips here.
        assertThat(message).contains("Subprocess MCP server died")
      } finally {
        session.shutdown()
        // Subprocess already exited via `process.exit(42)`; this is just defense against
        // a hung child if the fixture ever regresses to a non-exiting shape.
        val exited = spawned.process.waitFor(5, TimeUnit.SECONDS)
        if (!exited) spawned.process.destroyForcibly()
      }
    }
  }

  // Note: duplicates `QuickJsTrailblazeToolTest.buildContext()` field-for-field (null
  // screenState/traceId, Clock.System.now session, no-op logger, fresh AgentMemory).
  // Extract a shared TrailblazeToolExecutionContext test factory once a third call site lands.
  private fun buildExecutionContext(): TrailblazeToolExecutionContext = TrailblazeToolExecutionContext(
    screenState = null,
    traceId = null,
    trailblazeDeviceInfo = deviceInfo,
    sessionProvider = TrailblazeSessionProvider {
      TrailblazeSession(sessionId = spawnContext.sessionId, startTime = Clock.System.now())
    },
    trailblazeLogger = TrailblazeLogger.createNoOp(),
    memory = AgentMemory(),
  )

  private fun runtimeAvailable(): Boolean = try {
    NodeRuntimeDetector.cached
    true
  } catch (_: NoCompatibleTsRuntimeException) {
    false
  }
}
