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
 * On a driver with no change detection (iOS / host / non-accessibility Android) `waitForChange`
 * lowers to a Maestro `WaitForAnimationToEnd`, whose timeout is a CEILING. It nevertheless reported
 * `"degraded to a ${timeoutMs}ms timed wait"` unconditionally — the same false-duration claim
 * fixed for `wait` (#5279).
 *
 * The fake driver settles far faster than the ceiling, which is what makes this discriminating.
 */
class WaitForChangeTrailblazeToolTest {

  private val settleMs = 50L
  private val timeoutMs = 8_000L

  /** Base [MaestroTrailblazeAgent.waitForTreeChange] returns null, so this exercises the fallback. */
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
  fun `unsupported-driver fallback reports the real settle, not the requested timeout`() {
    val message = (runFallback() as TrailblazeToolResult.Success).message.orEmpty()

    val reportedMs = Regex("returned after (\\d+)ms").find(message)?.groupValues?.get(1)?.toLong()
      ?: error("message did not report a measured settle in the documented shape: $message")

    assertTrue(reportedMs >= settleMs, "reported ${reportedMs}ms is below the ${settleMs}ms the driver took")
    assertTrue(
      reportedMs < timeoutMs,
      "reported ${reportedMs}ms equals or exceeds the ${timeoutMs}ms ceiling — the message is still the requested duration, not the measured one",
    )
  }

  @Test
  fun `fallback message does not claim the timeout was spent waiting`() {
    val message = (runFallback() as TrailblazeToolResult.Success).message.orEmpty()
    // Pins the specific false string this change removed.
    assertTrue(
      !message.contains("degraded to a ${timeoutMs}ms timed wait"),
      "message still claims the full timeout was spent waiting: $message",
    )
  }

  private fun runFallback(): TrailblazeToolResult = runBlocking {
    WaitForChangeTrailblazeTool(timeoutMs = timeoutMs).execute(context(FastSettlingAgent(settleMs)))
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
        trailblazeDevicePlatform = TrailblazeDevicePlatform.IOS,
      ),
      trailblazeDriverType = TrailblazeDriverType.IOS_HOST,
      widthPixels = 1170,
      heightPixels = 2532,
    )
  }
}
