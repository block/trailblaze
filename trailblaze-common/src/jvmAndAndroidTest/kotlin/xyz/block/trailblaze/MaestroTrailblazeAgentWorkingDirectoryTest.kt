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
 * Locks the contract that a [MaestroTrailblazeAgent] threads its `workingDirectory` into every
 * [xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext] it builds — the wiring that lets
 * host-local tools resolve trail-relative files (e.g. a WAV recording committed beside the trail)
 * against the trail on disk rather than the daemon's CWD.
 *
 * Regression guard for the gap where only the V3 accessibility path set the context's
 * `workingDirectory`, so an ANDROID_ONDEVICE_ACCESSIBILITY session through the RPC runner resolved
 * a relative `hostPath` against the CI agent's working directory and failed with
 * "hostPath does not exist on this host" despite the file sitting beside the trail.
 * Sibling of [MaestroTrailblazeAgentSessionDirProviderTest], which locked the same wiring for
 * `sessionDirProvider`.
 */
class MaestroTrailblazeAgentWorkingDirectoryTest {

  private class TestAgent(
    workingDirectory: File?,
  ) : MaestroTrailblazeAgent(
    trailblazeLogger = TrailblazeLogger.createNoOp(),
    trailblazeDeviceInfoProvider = { DEVICE_INFO },
    sessionProvider = TrailblazeSessionProvider {
      TrailblazeSession(sessionId = SESSION_ID, startTime = Clock.System.now())
    },
    workingDirectory = workingDirectory,
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
  fun `buildExecutionContext threads the workingDirectory through to the tool context`() {
    val trailDir = File("/tmp/trailblaze-trail-source-1234/trails/k1")
    val agent = TestAgent(workingDirectory = trailDir)

    val context = agent.buildContextForTest()

    assertEquals(trailDir, context.workingDirectory)
  }

  @Test
  fun `buildExecutionContext leaves workingDirectory null when the agent has none`() {
    val agent = TestAgent(workingDirectory = null)

    val context = agent.buildContextForTest()

    assertNull(context.workingDirectory)
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
