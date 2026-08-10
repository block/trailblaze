package xyz.block.trailblaze.toolcalls.commands

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
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

/**
 * `wait` lowers to a Maestro `WaitForAnimationToEnd`, whose timeout is a CEILING — the driver
 * returns as soon as the UI is event-quiet. The tool nevertheless reported
 * `"Waited $timeToWaitInSeconds seconds"` unconditionally, so an author reading the log saw a
 * duration that was never spent (#5279).
 *
 * The fake driver below settles far faster than the requested ceiling, which is what makes these
 * tests discriminating: the old hardcoded message passes any "did it succeed" assertion, and
 * fails these.
 */
class WaitForIdleSyncTrailblazeToolTest {

  private val settleMs = 50L
  private val ceilingSeconds = 30

  /** Returns after [settleMs], mimicking a driver that finds the UI already quiet. */
  private class FastSettlingAgent(private val settleMs: Long) : MaestroTrailblazeAgent(
    trailblazeLogger = TrailblazeLogger.createNoOp(),
    trailblazeDeviceInfoProvider = { deviceInfo() },
    sessionProvider = TrailblazeSessionProvider {
      TrailblazeSession(sessionId = SessionId("test-session"), startTime = Clock.System.now())
    },
  ) {
    override suspend fun executeMaestroCommands(
      commands: List<Command>,
      traceId: TraceId?,
    ): TrailblazeToolResult {
      delay(settleMs)
      return TrailblazeToolResult.Success()
    }
  }

  @Test
  fun `success message reports the real settle, not the requested ceiling`() {
    val result = runWait()
    val message = (result as TrailblazeToolResult.Success).message.orEmpty()

    val reportedMs = Regex("^Settled after (\\d+)ms").find(message)?.groupValues?.get(1)?.toLong()
      ?: error("message did not report a measured settle in the documented shape: $message")

    // The driver settled in ~50ms against a 30s ceiling. A message derived from the ceiling
    // would report 30000; a measured one reports something far smaller.
    assertTrue(reportedMs >= settleMs, "reported ${reportedMs}ms is below the ${settleMs}ms the driver took")
    assertTrue(
      reportedMs < ceilingSeconds * 1000L,
      "reported ${reportedMs}ms equals or exceeds the ${ceilingSeconds}s ceiling — the message is still the requested duration, not the measured one",
    )
  }

  @Test
  fun `message does not claim the requested duration was waited`() {
    val message = (runWait() as TrailblazeToolResult.Success).message.orEmpty()
    // Pins the specific false string this change removed.
    assertTrue(
      !message.contains("Waited $ceilingSeconds seconds"),
      "message still claims the full requested duration was waited: $message",
    )
  }

  private fun runWait(): TrailblazeToolResult {
    val agent = FastSettlingAgent(settleMs)
    return runBlocking {
      WaitForIdleSyncTrailblazeTool(timeToWaitInSeconds = ceilingSeconds).execute(context(agent))
    }
  }

  private fun context(agent: MaestroTrailblazeAgent) = TrailblazeToolExecutionContext(
    screenState = null,
    traceId = null,
    trailblazeDeviceInfo = deviceInfo(),
    sessionProvider = TrailblazeSessionProvider {
      TrailblazeSession(sessionId = SessionId("test-session"), startTime = Clock.System.now())
    },
    trailblazeLogger = TrailblazeLogger.createNoOp(),
    memory = AgentMemory(),
    maestroTrailblazeAgent = agent,
  )

  private companion object {
    fun deviceInfo() = TrailblazeDeviceInfo(
      trailblazeDeviceId = TrailblazeDeviceId(
        instanceId = "test-device",
        trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
      ),
      trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
      widthPixels = 1080,
      heightPixels = 1920,
    )
  }
}
