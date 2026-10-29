package xyz.block.trailblaze.toolcalls.commands

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import kotlin.test.Test
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import maestro.KeyCode
import maestro.orchestra.Command
import maestro.orchestra.PressKeyCommand
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.MaestroTrailblazeAgent
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.commands.PressKeyTrailblazeTool.PressKeyCode

/**
 * Contract tests for `pressKey`: BACK reaches the device on Android, and fails on iOS (which has
 * no system back) instead of reporting a press that never happened.
 */
class PressKeyTrailblazeToolTest {

  private class CommandCapturingAgent(
    driverType: TrailblazeDriverType,
  ) : MaestroTrailblazeAgent(
    trailblazeLogger = TrailblazeLogger.createNoOp(),
    trailblazeDeviceInfoProvider = {
      TrailblazeDeviceInfo(
        trailblazeDeviceId = TrailblazeDeviceId(
          instanceId = "fixture-device",
          trailblazeDevicePlatform = driverType.platform,
        ),
        trailblazeDriverType = driverType,
        widthPixels = 1080,
        heightPixels = 1920,
      )
    },
    sessionProvider = TrailblazeSessionProvider {
      TrailblazeSession(sessionId = SessionId("fixture-session"), startTime = Clock.System.now())
    },
  ) {
    val dispatched = mutableListOf<Command>()

    override suspend fun executeMaestroCommands(
      commands: List<Command>,
      traceId: TraceId?,
    ): TrailblazeToolResult {
      dispatched += commands
      return TrailblazeToolResult.Success()
    }
  }

  private fun contextFor(agent: CommandCapturingAgent) = TrailblazeToolExecutionContext(
    screenState = null,
    traceId = null,
    trailblazeDeviceInfo = agent.trailblazeDeviceInfoProvider(),
    sessionProvider = agent.sessionProvider,
    trailblazeLogger = agent.trailblazeLogger,
    memory = AgentMemory(),
    maestroTrailblazeAgent = agent,
  )

  @Test
  fun `BACK on Android presses the system back key`() {
    runBlocking {
      val agent = CommandCapturingAgent(TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION)

      val result = PressKeyTrailblazeTool(PressKeyCode.BACK).execute(contextFor(agent))

      assertThat(result).isInstanceOf(TrailblazeToolResult.Success::class)
      assertThat((agent.dispatched.single() as PressKeyCommand).code).isEqualTo(KeyCode.BACK)
    }
  }

  @Test
  fun `BACK on iOS fails without dispatching and points at the app's back control`() {
    runBlocking {
      val agent = CommandCapturingAgent(TrailblazeDriverType.IOS_HOST)

      val result = PressKeyTrailblazeTool(PressKeyCode.BACK).execute(contextFor(agent))

      assertThat(result).isInstanceOf(TrailblazeToolResult.Error::class)
      assertThat((result as TrailblazeToolResult.Error).errorMessage).contains("back or close control")
      assertThat(agent.dispatched).isEmpty()
    }
  }

  @Test
  fun `other keys on iOS still dispatch`() {
    runBlocking {
      val agent = CommandCapturingAgent(TrailblazeDriverType.IOS_HOST)

      val result = PressKeyTrailblazeTool(PressKeyCode.ENTER).execute(contextFor(agent))

      assertThat(result).isInstanceOf(TrailblazeToolResult.Success::class)
      assertThat((agent.dispatched.single() as PressKeyCommand).code).isEqualTo(KeyCode.ENTER)
    }
  }
}
