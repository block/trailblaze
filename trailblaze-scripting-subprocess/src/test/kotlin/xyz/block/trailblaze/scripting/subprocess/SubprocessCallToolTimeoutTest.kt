package xyz.block.trailblaze.scripting.subprocess

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isInstanceOf
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonObject
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.rules.Timeout
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
import xyz.block.trailblaze.scripting.callback.JsScriptingCallbackDispatcher
import xyz.block.trailblaze.toolcalls.ToolName
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test

/**
 * A subprocess that accepts a `tools/call` and never answers it has to end as a readable failure
 * naming the tool, not as a hang and not as a cancellation.
 *
 * The MCP SDK cannot do this for us: `RequestOptions.timeout` bounds only the send (0.13.0 wraps
 * `transport.send` in `withTimeout` and awaits the response outside it), and over stdio the send
 * returns immediately. So without a host-side bound around the whole call, this dispatch waits
 * forever — until some enclosing hop cancels the coroutine, which reaches the user as "the run
 * was cancelled" and points at the wrong layer.
 *
 * The budget is shrunk through the same `-Dtrailblaze.callback.timeoutMs` override the daemon
 * exposes, which proves the bound is the configured one rather than a hard-coded constant.
 */
class SubprocessCallToolTimeoutTest {

  /**
   * The fixture never answers and never exits, so a regression that drops the bound would park
   * this test forever and wedge the whole Gradle run. Far above the shrunk budget below, far
   * below the production default — a hang converts into a failed test instead.
   */
  @get:Rule val perTestHangGuard: Timeout = Timeout(120, TimeUnit.SECONDS)

  private val hangOnCallFixture: File by lazy {
    val url = requireNotNull(javaClass.getResource("/mcp-fixture/fixture-hangs-on-call.js")) {
      "Missing /mcp-fixture/fixture-hangs-on-call.js on classpath — Gradle copy tasks out of sync?"
    }
    File(url.toURI())
  }

  private val deviceInfo = TrailblazeDeviceInfo(
    trailblazeDeviceId = TrailblazeDeviceId("call-timeout-test", TrailblazeDevicePlatform.ANDROID),
    trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
    widthPixels = 1080,
    heightPixels = 2400,
  )

  private val spawnContext = McpSpawnContext(
    platform = TrailblazeDevicePlatform.ANDROID,
    driver = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
    widthPixels = 1080,
    heightPixels = 2400,
    sessionId = SessionId("call_timeout_test_session"),
  )

  @Test fun `a subprocess that never answers a call fails with a timeout naming the tool`() {
    assumeTrue(
      "bun must be on PATH to exercise the e2e subprocess runtime",
      runtimeAvailable(),
    )
    // Shrinks every derived hop: the subprocess's client fetch timeout and the outer `tools/call`
    // budget both read this property, so 1ms leaves only their fixed buffers (~4s) to wait out.
    System.setProperty(JsScriptingCallbackDispatcher.CALLBACK_TIMEOUT_MS_PROPERTY, "1")
    val expectedTimeoutMs = McpSubprocessSpawner.resolveOuterRequestTimeoutMs()
    try {
      runBlocking {
        val spawned = McpSubprocessSpawner.spawn(
          config = McpServerConfig(script = hangOnCallFixture.absolutePath),
          context = spawnContext,
          anchor = hangOnCallFixture.parentFile,
        )
        val session = McpSubprocessSession.connect(spawnedProcess = spawned)
        try {
          val tool = SubprocessTrailblazeTool(
            sessionProvider = { session },
            advertisedName = ToolName("hang_on_call"),
            args = JsonObject(emptyMap()),
          )

          val result = tool.execute(buildExecutionContext())

          // A transient transport failure, not a crash: the subprocess is alive and healthy, it
          // just withheld the answer. FatalError here would mean the dispatch path misread a
          // silent tool as a dead one.
          assertThat(result).isInstanceOf(TrailblazeToolResult.Error.ExceptionThrown::class)
          val message = (result as TrailblazeToolResult.Error.ExceptionThrown).errorMessage
          // WHICH tool went quiet — the detail a bare cancellation loses, and the reason this
          // message exists at all.
          assertThat(message).contains("'hang_on_call'")
          // The budget that expired, resolved from the override rather than restated, so a
          // reader can tell whether to raise it.
          assertThat(message).contains("${expectedTimeoutMs}ms")
          // Names the knob, and says the subprocess kept running so a reader doesn't hunt for a
          // crash that never happened.
          assertThat(message).contains(JsScriptingCallbackDispatcher.CALLBACK_TIMEOUT_MS_PROPERTY)
          assertThat(message).contains("still running")
        } finally {
          session.shutdown()
          // The fixture ignores stdin EOF's usual exit path while its interval keeps the loop
          // alive, so force the child down rather than leaking it into the rest of the run.
          if (!spawned.process.waitFor(5, TimeUnit.SECONDS)) spawned.process.destroyForcibly()
        }
      }
    } finally {
      System.clearProperty(JsScriptingCallbackDispatcher.CALLBACK_TIMEOUT_MS_PROPERTY)
    }
  }

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
    BunRuntimeDetector.cached
    true
  } catch (_: NoBunRuntimeException) {
    false
  }
}
