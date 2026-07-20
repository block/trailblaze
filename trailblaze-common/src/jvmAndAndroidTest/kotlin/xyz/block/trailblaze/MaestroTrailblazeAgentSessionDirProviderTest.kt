package xyz.block.trailblaze

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.datetime.Clock
import maestro.orchestra.Command
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.TrailblazeAgent
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.utils.ElementComparator

/**
 * Locks the contract that a [MaestroTrailblazeAgent] threads its `sessionDirProvider` into every
 * [xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext] it builds — the wiring that lets
 * host-side `requiresHost` tools (e.g. a capture-reading tool) resolve capture artifacts under the
 * session's on-host log dir.
 *
 * Regression guard for the gap where the mobile host runner paths constructed the context without a
 * `sessionDirProvider`, so the tool errored with "requires a sessionDirProvider" mid-trail.
 */
class MaestroTrailblazeAgentSessionDirProviderTest {

  private class TestAgent(
    sessionDirProvider: ((SessionId) -> File)?,
  ) : MaestroTrailblazeAgent(
    trailblazeLogger = TrailblazeLogger.createNoOp(),
    trailblazeDeviceInfoProvider = { DEVICE_INFO },
    sessionProvider = TrailblazeSessionProvider {
      TrailblazeSession(sessionId = SESSION_ID, startTime = Clock.System.now())
    },
    sessionDirProvider = sessionDirProvider,
  ) {
    override suspend fun executeMaestroCommands(
      commands: List<Command>,
      traceId: TraceId?,
    ): TrailblazeToolResult = TrailblazeToolResult.Success()

    override fun runTrailblazeTools(
      tools: List<TrailblazeTool>,
      traceId: TraceId?,
      screenState: ScreenState?,
      elementComparator: ElementComparator,
      screenStateProvider: (() -> ScreenState)?,
    ): TrailblazeAgent.RunTrailblazeToolsResult = error("unused in this test")

    /** Exposes the protected [buildExecutionContext] so the test can assert its output. */
    fun buildContextForTest() = buildExecutionContext(
      traceId = TraceId.generate(TraceId.Companion.TraceOrigin.TOOL),
      screenState = null,
      screenStateProvider = null,
    )
  }

  @Test
  fun `buildExecutionContext threads the sessionDirProvider through to the tool context`() {
    val expectedDir = File("/tmp/trailblaze-logs").resolve(SESSION_ID.value)
    val agent = TestAgent(sessionDirProvider = { session -> File("/tmp/trailblaze-logs").resolve(session.value) })

    val context = agent.buildContextForTest()

    assertEquals(expectedDir, context.sessionDirProvider?.invoke(SESSION_ID))
  }

  @Test
  fun `buildExecutionContext leaves sessionDirProvider null when the agent has none`() {
    val agent = TestAgent(sessionDirProvider = null)

    val context = agent.buildContextForTest()

    assertNull(context.sessionDirProvider)
  }

  private companion object {
    val SESSION_ID = SessionId("session-under-test")
    val DEVICE_INFO = TrailblazeDeviceInfo(
      trailblazeDeviceId = TrailblazeDeviceId(
        instanceId = "fake-instance-id",
        trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
      ),
      trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
      widthPixels = 1080,
      heightPixels = 1920,
    )
  }
}
