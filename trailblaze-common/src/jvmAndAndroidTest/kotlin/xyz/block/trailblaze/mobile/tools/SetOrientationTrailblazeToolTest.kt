package xyz.block.trailblaze.mobile.tools

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import kotlin.test.Test
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import maestro.device.DeviceOrientation
import maestro.orchestra.Command
import maestro.orchestra.SetOrientationCommand
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.MaestroTrailblazeAgent
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
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
import xyz.block.trailblaze.mobile.tools.SetOrientationTrailblazeTool.Orientation
import xyz.block.trailblaze.yaml.TrailYamlItem
import xyz.block.trailblaze.yaml.createTrailblazeYaml

/**
 * Contract tests for `mobile_setOrientation`: a recorded call decodes, reaches the device as Maestro's
 * rotation on drivers that can rotate, and fails on AXe (which can't) instead of reporting a
 * rotation that never happened.
 */
class SetOrientationTrailblazeToolTest {

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

  private fun rotate(driverType: TrailblazeDriverType, orientation: Orientation) = runBlocking {
    val agent = CommandCapturingAgent(driverType)
    SetOrientationTrailblazeTool(orientation).execute(contextFor(agent)) to agent.dispatched
  }

  @Test
  fun `a recorded call decodes, matching any case`() {
    val yaml = """
config: {}
trail:
  - step: recorded
    recording:
      ios:
        - mobile_setOrientation:
            orientation: landscape_left
    """.trimIndent()

    val tool = createTrailblazeYaml()
      .decodeTrail(yaml, deviceClassifiers = listOf(TrailblazeDeviceClassifier("ios")))
      .filterIsInstance<TrailYamlItem.PromptsTrailItem>().single()
      .promptSteps.single().recording!!.tools.single().trailblazeTool

    assertThat(tool).isEqualTo(SetOrientationTrailblazeTool(Orientation.LANDSCAPE_LEFT))
  }

  @Test
  fun `every orientation reaches iOS host Maestro as the same rotation`() {
    Orientation.entries.forEach { orientation ->
      val (result, dispatched) = rotate(TrailblazeDriverType.IOS_HOST, orientation)

      assertThat(result).isInstanceOf(TrailblazeToolResult.Success::class)
      assertThat((dispatched.single() as SetOrientationCommand).resolvedOrientation())
        .isEqualTo(DeviceOrientation.valueOf(orientation.name))
    }
  }

  @Test
  fun `Android accessibility rotates`() {
    val (result, dispatched) =
      rotate(TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY, Orientation.PORTRAIT)

    assertThat(result).isInstanceOf(TrailblazeToolResult.Success::class)
    assertThat((dispatched.single() as SetOrientationCommand).resolvedOrientation())
      .isEqualTo(DeviceOrientation.PORTRAIT)
  }

  @Test
  fun `AXe fails without dispatching and names the driver that can rotate`() {
    val (result, dispatched) = rotate(TrailblazeDriverType.IOS_AXE, Orientation.LANDSCAPE_LEFT)

    assertThat(result).isInstanceOf(TrailblazeToolResult.Error::class)
    assertThat((result as TrailblazeToolResult.Error).errorMessage).contains("IOS_HOST")
    assertThat(dispatched).isEmpty()
  }
}
