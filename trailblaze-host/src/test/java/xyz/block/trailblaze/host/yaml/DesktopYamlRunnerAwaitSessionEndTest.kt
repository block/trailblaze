package xyz.block.trailblaze.host.yaml

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import xyz.block.trailblaze.host.yaml.DesktopYamlRunner.Companion.StepProgress
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.yaml.DirectionStep
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * How the host waits on an on-device session: it reports each step the device starts, both while
 * the synchronous request is pending ([DesktopYamlRunner.reportingSteps]) and after its reply
 * ([DesktopYamlRunner.awaitSessionEnd]), and gives up only after a stretch with no new step, not
 * after a fixed total runtime.
 */
class DesktopYamlRunnerAwaitSessionEndTest {

  private val session = SessionId("on_device_trail_1234")

  private fun stepStart(prompt: String) =
    TrailblazeLog.ObjectiveStartLog(DirectionStep(prompt), session, Instant.fromEpochMilliseconds(0))

  private fun ended() = TrailblazeLog.TrailblazeSessionStatusChangeLog(
    SessionStatus.Ended.Succeeded(durationMs = 0),
    session,
    Instant.fromEpochMilliseconds(0),
  )

  /** The session's logs as the device has written them by each virtual time. */
  private fun TestScope.logsWrittenAt(vararg written: Pair<Long, TrailblazeLog>): () -> List<TrailblazeLog> =
    { written.filter { (atMs, _) -> atMs <= currentTime }.map { it.second } }

  @Test
  fun `a session that keeps starting steps is waited on well past the inactivity window`() = runTest {
    val progress = mutableListOf<String>()

    val ended = DesktopYamlRunner.awaitSessionEnd(
      readLogs = logsWrittenAt(
        0L to stepStart("Open the menu"),
        800L to stepStart("Send money"),
        1_600L to stepStart("Confirm the payment"),
        2_400L to ended(),
      ),
      stepProgress = StepProgress { progress += it },
      inactivityTimeoutMs = 1_000,
      pollIntervalMs = 100,
      nowMs = { currentTime },
    )

    assertTrue(ended)
    assertEquals(listOf("Step 1: Open the menu", "Step 2: Send money", "Step 3: Confirm the payment"), progress)
  }

  @Test
  fun `a step that hangs ends the wait one inactivity window after it started`() = runTest {
    val progress = mutableListOf<String>()

    val ended = DesktopYamlRunner.awaitSessionEnd(
      readLogs = logsWrittenAt(
        0L to stepStart("Open the menu"),
        800L to stepStart("Wait for a reply that never comes"),
      ),
      stepProgress = StepProgress { progress += it },
      inactivityTimeoutMs = 1_000,
      pollIntervalMs = 100,
      nowMs = { currentTime },
    )

    assertFalse(ended)
    assertEquals(1_800L, currentTime)
    assertEquals(listOf("Step 1: Open the menu", "Step 2: Wait for a reply that never comes"), progress)
  }

  @Test
  fun `a session that never starts a step times out without reporting progress`() = runTest {
    val progress = mutableListOf<String>()

    val ended = DesktopYamlRunner.awaitSessionEnd(
      readLogs = { emptyList() },
      stepProgress = StepProgress { progress += it },
      inactivityTimeoutMs = 1_000,
      pollIntervalMs = 100,
      nowMs = { currentTime },
    )

    assertFalse(ended)
    assertEquals(1_000L, currentTime)
    assertEquals(emptyList(), progress)
  }

  @Test
  fun `steps logged between two polls report the latest of them`() = runTest {
    val progress = mutableListOf<String>()

    val ended = DesktopYamlRunner.awaitSessionEnd(
      readLogs = logsWrittenAt(0L to stepStart("Open the menu"), 0L to stepStart("Close it"), 0L to ended()),
      stepProgress = StepProgress { progress += it },
      inactivityTimeoutMs = 1_000,
      pollIntervalMs = 100,
      nowMs = { currentTime },
    )

    assertTrue(ended)
    assertEquals(listOf("Step 2: Close it"), progress)
  }

  @Test
  fun `steps started while the synchronous request is pending are reported before its reply`() = runTest {
    val progress = mutableListOf<String>()
    val logs = logsWrittenAt(
      0L to stepStart("Open the menu"),
      800L to stepStart("Send money"),
      1_600L to stepStart("Confirm the payment"),
    )

    val reply = DesktopYamlRunner.reportingSteps(
      readStepStarts = { logs().filterIsInstance<TrailblazeLog.ObjectiveStartLog>() },
      stepProgress = StepProgress { progress += it },
      pollIntervalMs = 100,
    ) {
      delay(2_000)
      "reply"
    }

    assertEquals("reply", reply)
    assertEquals(listOf("Step 1: Open the menu", "Step 2: Send money", "Step 3: Confirm the payment"), progress)
  }

  @Test
  fun `the wait after the reply reports only steps the pending request did not`() = runTest {
    val progress = mutableListOf<String>()
    val stepProgress = StepProgress { progress += it }
    val logs = logsWrittenAt(
      0L to stepStart("Open the menu"),
      1_000L to stepStart("Send money"),
      1_500L to ended(),
    )

    DesktopYamlRunner.reportingSteps(
      readStepStarts = { logs().filterIsInstance<TrailblazeLog.ObjectiveStartLog>() },
      stepProgress = stepProgress,
      pollIntervalMs = 100,
    ) { delay(500) }
    val ended = DesktopYamlRunner.awaitSessionEnd(
      readLogs = logs,
      stepProgress = stepProgress,
      inactivityTimeoutMs = 1_000,
      pollIntervalMs = 100,
      nowMs = { currentTime },
    )

    assertTrue(ended)
    assertEquals(listOf("Step 1: Open the menu", "Step 2: Send money"), progress)
  }

  @Test
  fun `a failed read while the request is pending does not cancel the run`() = runTest {
    val reply = DesktopYamlRunner.reportingSteps(
      readStepStarts = { error("log directory unreadable") },
      stepProgress = StepProgress {},
      pollIntervalMs = 100,
    ) {
      delay(500)
      "reply"
    }

    assertEquals("reply", reply)
  }
}
