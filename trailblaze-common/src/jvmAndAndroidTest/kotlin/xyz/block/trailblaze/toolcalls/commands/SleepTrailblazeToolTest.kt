package xyz.block.trailblaze.toolcalls.commands

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.TimeSource
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult

/**
 * `sleep` exists because every other wait in the framework is a settle that returns as soon as
 * the UI is quiet (#5279). So the assertion that matters is the wall-clock FLOOR: a test that
 * only asserted `Success` would pass against the very settle-based implementation this tool was
 * added to replace.
 */
class SleepTrailblazeToolTest {

  private val sleepMs = 400L

  @Test
  fun `consumes the full requested duration instead of returning early`() {
    val outerMark = TimeSource.Monotonic.markNow()
    val result = runBlocking { SleepTrailblazeTool(durationMs = sleepMs).execute(context()) }
    val outerElapsedMs = outerMark.elapsedNow().inWholeMilliseconds

    assertTrue(result is TrailblazeToolResult.Success, "expected Success but was $result")
    // The load-bearing assertion. A settle-based implementation returns in ~150ms on a static
    // screen; `WaitForAnimationToEnd` against a null driver returns in ~0ms. Both fail here.
    assertTrue(
      outerElapsedMs >= sleepMs,
      "sleep(${sleepMs}ms) returned after only ${outerElapsedMs}ms — it did not consume wall-clock time",
    )
  }

  @Test
  fun `success message reports measured elapsed time, bounded by an independent measurement`() {
    val outerMark = TimeSource.Monotonic.markNow()
    val result = runBlocking { SleepTrailblazeTool(durationMs = sleepMs).execute(context()) }
    val outerElapsedMs = outerMark.elapsedNow().inWholeMilliseconds

    val message = (result as TrailblazeToolResult.Success).message
    val reportedMs = Regex("^Slept (\\d+)ms").find(message.orEmpty())?.groupValues?.get(1)?.toLong()
      ?: error("message did not report an elapsed time in the documented shape: $message")

    // Brackets the reported number between the floor it must clear and a measurement taken
    // strictly outside it — so the message cannot be an arbitrary constant, and in particular
    // cannot be a duration that was never actually spent.
    assertTrue(reportedMs >= sleepMs, "reported ${reportedMs}ms is below the requested ${sleepMs}ms")
    assertTrue(
      reportedMs <= outerElapsedMs,
      "reported ${reportedMs}ms exceeds the ${outerElapsedMs}ms measured around the call, so it is not a real measurement",
    )
    assertTrue(
      message.orEmpty().contains("requested ${sleepMs}ms"),
      "message should also state what was requested, was: $message",
    )
  }

  @Test
  fun `negative duration fails loudly rather than silently returning success`() {
    // `delay` clamps a negative duration to zero, which would make this tool a silent no-op
    // reporting success — the exact defect class it exists to remove.
    val result = runBlocking { SleepTrailblazeTool(durationMs = -1).execute(context()) }
    assertTrue(
      result is TrailblazeToolResult.Error,
      "a negative duration must be an error, but was $result",
    )
  }

  @Test
  fun `duration below the floor fails loudly rather than passing as a no-op`() {
    // `durationMs: 5` is a units slip (5 seconds meant), and succeeds as a 5ms non-wait.
    val result = runBlocking {
      SleepTrailblazeTool(durationMs = SleepTrailblazeTool.MIN_DURATION_MS - 1).execute(context())
    }
    assertTrue(result is TrailblazeToolResult.Error, "a sub-floor duration must be an error, but was $result")
  }

  @Test
  fun `duration above the cap fails loudly instead of being silently clamped`() {
    // Clamping would return before the requested duration while reporting success, which is the
    // early-return defect this tool exists to remove — so the cap must reject, not coerce.
    val requested = SleepTrailblazeTool.MAX_DURATION_MS + 1
    val outerMark = TimeSource.Monotonic.markNow()
    val result = runBlocking { SleepTrailblazeTool(durationMs = requested).execute(context()) }
    val outerElapsedMs = outerMark.elapsedNow().inWholeMilliseconds

    assertTrue(result is TrailblazeToolResult.Error, "an over-cap duration must be an error, but was $result")
    assertTrue(
      outerElapsedMs < SleepTrailblazeTool.MAX_DURATION_MS,
      "rejection took ${outerElapsedMs}ms — it slept before failing",
    )
  }

  @Test
  fun `bounds bracket the default duration`() {
    assertTrue(
      SleepTrailblazeTool().durationMs in SleepTrailblazeTool.MIN_DURATION_MS..SleepTrailblazeTool.MAX_DURATION_MS,
      "the default duration is outside the range the tool accepts",
    )
    // The cap must stay under the 10-minute run-poll inactivity window, which a silent
    // host-local sleep cannot reset.
    assertTrue(
      SleepTrailblazeTool.MAX_DURATION_MS < 10 * 60 * 1000L,
      "the cap allows a sleep long enough to trip the run-poll inactivity watchdog",
    )
  }

  @Test
  fun `default duration is five seconds`() {
    assertEquals(5_000L, SleepTrailblazeTool().durationMs)
  }

  private fun context(): TrailblazeToolExecutionContext = TrailblazeToolExecutionContext(
    screenState = null,
    traceId = null,
    trailblazeDeviceInfo = TrailblazeDeviceInfo(
      trailblazeDeviceId = TrailblazeDeviceId(
        instanceId = "test-device",
        trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
      ),
      trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
      widthPixels = 1080,
      heightPixels = 1920,
    ),
    sessionProvider = TrailblazeSessionProvider {
      TrailblazeSession(sessionId = SessionId("test-session"), startTime = Clock.System.now())
    },
    trailblazeLogger = TrailblazeLogger.createNoOp(),
    memory = AgentMemory(),
  )
}
