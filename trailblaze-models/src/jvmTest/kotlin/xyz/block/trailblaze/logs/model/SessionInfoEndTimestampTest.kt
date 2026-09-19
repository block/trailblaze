package xyz.block.trailblaze.logs.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.datetime.Instant
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.client.temp.OtherTrailblazeTool

/**
 * When a session is reported as having ended.
 *
 * [SessionInfo.durationMs] counts from the session mint, which is before its first log, so it
 * cannot be added to [SessionInfo.timestamp] to find the end. [SessionInfo.endTimestamp] is what
 * every reader anchors on instead, which makes "which log is the end" a load-bearing question
 * rather than a cosmetic one.
 */
class SessionInfoEndTimestampTest {

  private val session = SessionId("session-info-end-timestamp-test")

  @Test
  fun `a log written after the session closed does not become its end`() {
    // Logs keep arriving after a session ends — a recording's save-back appends its progress last.
    // Taking the newest log of any kind reports a completion the session never reached, and since
    // the start is derived as end minus duration, it moves that too.
    val logs = listOf(
      started(atMs = 1_000),
      ended(atMs = 6_000, durationMs = 30_000),
      toolLog(atMs = 66_000),
    )

    assertEquals(
      Instant.fromEpochMilliseconds(6_000),
      logs.getSessionInfo()?.endTimestamp,
    )
  }

  @Test
  fun `the end is the last status log that ended the session`() {
    // A session can close more than once in its log stream (a retried teardown, a re-ingested
    // shard). The latest one is the end.
    val logs = listOf(
      started(atMs = 1_000),
      ended(atMs = 6_000, durationMs = 30_000),
      ended(atMs = 9_000, durationMs = 33_000),
    )

    assertEquals(
      Instant.fromEpochMilliseconds(9_000),
      logs.getSessionInfo()?.endTimestamp,
    )
  }

  @Test
  fun `a session still running has no end at all`() {
    // Reporting one would invent a completion it never reached — and a consumer that trusts it
    // would draw a finished session out of an in-flight one.
    val logs = listOf(
      started(atMs = 1_000),
      toolLog(atMs = 4_000),
    )

    assertNull(logs.getSessionInfo()?.endTimestamp)
  }

  private fun started(atMs: Long) = TrailblazeLog.TrailblazeSessionStatusChangeLog(
    sessionStatus = SessionStatus.Started(
      trailConfig = null,
      trailFilePath = null,
      hasRecordedSteps = false,
      testMethodName = "run",
      testClassName = "Test",
      trailblazeDeviceInfo = TrailblazeDeviceInfo(
        trailblazeDeviceId = TrailblazeDeviceId("test-device", TrailblazeDevicePlatform.ANDROID),
        trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
        widthPixels = 100,
        heightPixels = 100,
        classifiers = emptyList(),
      ),
    ),
    session = session,
    timestamp = Instant.fromEpochMilliseconds(atMs),
    clock = TrailblazeClockDomain.HOST,
  )

  private fun ended(atMs: Long, durationMs: Long) = TrailblazeLog.TrailblazeSessionStatusChangeLog(
    sessionStatus = SessionStatus.Ended.Succeeded(durationMs = durationMs),
    session = session,
    timestamp = Instant.fromEpochMilliseconds(atMs),
    clock = TrailblazeClockDomain.HOST,
  )

  private fun toolLog(atMs: Long) = TrailblazeLog.TrailblazeToolLog(
    trailblazeTool = OtherTrailblazeTool(toolName = "tapOn"),
    toolName = "tapOn",
    successful = true,
    traceId = null,
    durationMs = 10,
    session = session,
    timestamp = Instant.fromEpochMilliseconds(atMs),
    deviceName = null,
    clock = TrailblazeClockDomain.HOST,
  )
}
