package xyz.block.trailblaze.host

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import maestro.orchestra.Command
import maestro.orchestra.InputTextCommand
import maestro.orchestra.LaunchAppCommand
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult

class HostMaestroTrailblazeAgentBatchTest {
  @Test
  fun keepsLaunchAndLaterTextInOneRunnerBatch() = runBlocking {
    val calls = mutableListOf<List<Command>>()
    val runner = object : MaestroHostRunner {
      override val screenStateProvider: () -> ScreenState = { error("unused") }
      override fun runMaestroYaml(yaml: String): TrailblazeToolResult = error("unused")
      override fun runFlowFile(flowFile: File): TrailblazeToolResult = error("unused")
      override fun runMaestroCommand(vararg commands: Command): TrailblazeToolResult = error("unused")
      override fun runMaestroCommands(commands: List<Command>, traceId: TraceId?): TrailblazeToolResult {
        calls += commands
        return TrailblazeToolResult.Success()
      }
    }
    val agent = HostMaestroTrailblazeAgent(
      maestroHostRunner = runner,
      trailblazeLogger = TrailblazeLogger.createNoOp(),
      trailblazeDeviceInfoProvider = {
        TrailblazeDeviceInfo(
          trailblazeDeviceId = TrailblazeDeviceId("fixture", TrailblazeDevicePlatform.IOS),
          trailblazeDriverType = TrailblazeDriverType.IOS_HOST,
          widthPixels = 390,
          heightPixels = 844,
        )
      },
      sessionProvider = TrailblazeSessionProvider {
        TrailblazeSession(SessionId("fixture"), Clock.System.now())
      },
    )
    val commands = listOf<Command>(
      LaunchAppCommand(appId = "com.example.app", launchArguments = mapOf("token" to "fixture-secret")),
      InputTextCommand(text = "fixture-secret"),
    )

    agent.runMaestroCommands(commands, null)

    assertEquals(listOf(commands), calls)
  }
}
