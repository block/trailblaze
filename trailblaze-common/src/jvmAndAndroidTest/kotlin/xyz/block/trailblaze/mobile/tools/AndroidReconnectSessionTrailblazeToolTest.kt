package xyz.block.trailblaze.mobile.tools

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import maestro.orchestra.Command
import org.junit.Test
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.MaestroTrailblazeAgent
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
import xyz.block.trailblaze.toolcalls.DeviceSessionReconnectSupport
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.utils.ElementComparator

/**
 * [AndroidReconnectSessionTrailblazeTool] owns no device logic of its own — it hands the reconnect
 * to the session agent's [DeviceSessionReconnectSupport]. These tests pin what it does own: which
 * sessions it refuses and how it reports the agent's outcome.
 */
class AndroidReconnectSessionTrailblazeToolTest {

  @Test fun `errors on a non-Android platform`() = runBlocking {
    val result = AndroidReconnectSessionTrailblazeTool.execute(
      createContext(TrailblazeDevicePlatform.IOS, agent = ReconnectCapableAgent()),
    )

    assertIs<TrailblazeToolResult.Error.ExceptionThrown>(result)
    assertThat(result.errorMessage).contains("only supported on Android")
  }

  @Test fun `errors when the session agent cannot reconnect`() = runBlocking {
    val result = AndroidReconnectSessionTrailblazeTool.execute(
      createContext(TrailblazeDevicePlatform.ANDROID, agent = PlainAgent()),
    )

    assertIs<TrailblazeToolResult.Error.ExceptionThrown>(result)
    assertThat(result.errorMessage).contains("host-driven")
  }

  @Test fun `errors when the context carries no agent at all`() = runBlocking {
    val result = AndroidReconnectSessionTrailblazeTool.execute(
      createContext(TrailblazeDevicePlatform.ANDROID, agent = null),
    )

    assertIs<TrailblazeToolResult.Error.ExceptionThrown>(result)
    assertThat(result.errorMessage).contains("host-driven")
  }

  @Test fun `asks the agent to reconnect and returns its summary`() = runBlocking {
    val agent = ReconnectCapableAgent(summary = "Session reconnected to x.")

    val result = AndroidReconnectSessionTrailblazeTool.execute(createContext(TrailblazeDevicePlatform.ANDROID, agent = agent))

    assertIs<TrailblazeToolResult.Success>(result)
    assertThat(result.message).isEqualTo("Session reconnected to x.")
    assertThat(agent.calls).isEqualTo(1)
  }

  @Test fun `reports the agent's failure as a tool error`() = runBlocking {
    val agent = ReconnectCapableAgent(failure = IllegalStateException("runner did not come back"))

    val result = AndroidReconnectSessionTrailblazeTool.execute(createContext(TrailblazeDevicePlatform.ANDROID, agent = agent))

    assertIs<TrailblazeToolResult.Error.ExceptionThrown>(result)
    assertThat(result.errorMessage).contains("runner did not come back")
  }

  /** An agent that cannot reconnect to its device — what an on-device runner's looks like. */
  private open class PlainAgent : MaestroTrailblazeAgent(
    trailblazeLogger = TrailblazeLogger.createNoOp(),
    trailblazeDeviceInfoProvider = { deviceInfo(TrailblazeDevicePlatform.ANDROID) },
    sessionProvider = SESSION_PROVIDER,
  ) {
    override suspend fun executeMaestroCommands(commands: List<Command>, traceId: TraceId?): TrailblazeToolResult =
      TrailblazeToolResult.Success()

    override fun runTrailblazeTools(
      tools: List<TrailblazeTool>,
      traceId: TraceId?,
      screenState: ScreenState?,
      elementComparator: ElementComparator,
      screenStateProvider: (() -> ScreenState)?,
    ): TrailblazeAgent.RunTrailblazeToolsResult = error("unused in this test")
  }

  private class ReconnectCapableAgent(
    private val summary: String = "ok",
    private val failure: Exception? = null,
  ) : PlainAgent(),
    DeviceSessionReconnectSupport {
    var calls = 0

    override suspend fun reconnectSession(): String {
      calls++
      failure?.let { throw it }
      return summary
    }
  }

  private fun createContext(
    platform: TrailblazeDevicePlatform,
    agent: MaestroTrailblazeAgent?,
  ): TrailblazeToolExecutionContext = TrailblazeToolExecutionContext(
    screenState = null,
    traceId = null,
    trailblazeDeviceInfo = deviceInfo(platform),
    sessionProvider = SESSION_PROVIDER,
    trailblazeLogger = TrailblazeLogger.createNoOp(),
    memory = AgentMemory(),
    maestroTrailblazeAgent = agent,
  )

  private companion object {
    val SESSION_PROVIDER = TrailblazeSessionProvider {
      TrailblazeSession(sessionId = SessionId("test-session"), startTime = Clock.System.now())
    }

    fun deviceInfo(platform: TrailblazeDevicePlatform) = TrailblazeDeviceInfo(
      trailblazeDeviceId = TrailblazeDeviceId(instanceId = "test-device", trailblazeDevicePlatform = platform),
      trailblazeDriverType = when (platform) {
        TrailblazeDevicePlatform.ANDROID -> TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION
        TrailblazeDevicePlatform.IOS -> TrailblazeDriverType.IOS_HOST
        TrailblazeDevicePlatform.WEB -> TrailblazeDriverType.PLAYWRIGHT_NATIVE
        TrailblazeDevicePlatform.DESKTOP -> TrailblazeDriverType.COMPOSE
      },
      widthPixels = 1080,
      heightPixels = 1920,
    )
  }
}
