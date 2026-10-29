package xyz.block.trailblaze.mobile.tools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import maestro.orchestra.Command
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.MaestroTrailblazeAgent
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult

/** A cleared iOS app is a fresh install, so the notifications answer armed for it must go too. */
class ClearAppDataNotificationsAnswerTest {

  /** [answers] null: a driver with no answer of its own (ios-host). */
  private class RecordingAgent(
    private val answers: Boolean = true,
    private val result: TrailblazeToolResult = TrailblazeToolResult.Success(),
  ) : MaestroTrailblazeAgent(
    trailblazeLogger = TrailblazeLogger.createNoOp(),
    trailblazeDeviceInfoProvider = { device(TrailblazeDriverType.IOS_AXE) },
    sessionProvider = sessionProvider,
  ) {
    val commands = mutableListOf<Command>()
    val answered = mutableListOf<Pair<String, Boolean?>>()

    override suspend fun executeMaestroCommands(commands: List<Command>, traceId: TraceId?): TrailblazeToolResult {
      this.commands += commands
      return TrailblazeToolResult.Success()
    }

    override suspend fun answerNotificationsAlert(appId: String, allow: Boolean?, traceId: TraceId?): TrailblazeToolResult? {
      if (!answers) return null
      answered += appId to allow
      return result
    }
  }

  @Test
  fun `a driver that answers the alert itself stops answering the app's`() {
    val agent = RecordingAgent()

    assertNull(forget(TrailblazeDriverType.IOS_AXE, agent))
    assertEquals(listOf<Pair<String, Boolean?>>(APP_ID to null), agent.answered)
  }

  @Test
  fun `on ios-host the runner's simulator-wide answer is left alone`() {
    val agent = RecordingAgent(answers = false)

    assertNull(forget(TrailblazeDriverType.IOS_HOST, agent))
    assertEquals(emptyList(), agent.commands)
  }

  @Test
  fun `a driver that refuses fails the clear`() {
    val refusal = TrailblazeToolResult.Error.ExceptionThrown(errorMessage = "driver unreachable")

    assertEquals(refusal, forget(TrailblazeDriverType.IOS_AXE, RecordingAgent(result = refusal)))
  }

  private fun forget(driver: TrailblazeDriverType, agent: MaestroTrailblazeAgent): TrailblazeToolResult? = runBlocking {
    ClearAppDataTrailblazeTool(appId = APP_ID).forgetNotificationsAnswer(
      TrailblazeToolExecutionContext(
        screenState = null,
        traceId = null,
        trailblazeDeviceInfo = device(driver),
        sessionProvider = sessionProvider,
        trailblazeLogger = TrailblazeLogger.createNoOp(),
        memory = AgentMemory(),
        maestroTrailblazeAgent = agent,
      ),
    )
  }

  private companion object {
    const val APP_ID = "com.example.app"

    val sessionProvider = TrailblazeSessionProvider {
      TrailblazeSession(sessionId = SessionId("test-session"), startTime = Clock.System.now())
    }

    fun device(driver: TrailblazeDriverType) = TrailblazeDeviceInfo(
      trailblazeDeviceId = TrailblazeDeviceId(
        instanceId = "test-simulator",
        trailblazeDevicePlatform = TrailblazeDevicePlatform.IOS,
      ),
      trailblazeDriverType = driver,
      widthPixels = 1179,
      heightPixels = 2556,
    )
  }
}
