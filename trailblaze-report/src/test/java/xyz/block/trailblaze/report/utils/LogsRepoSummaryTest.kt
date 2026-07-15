package xyz.block.trailblaze.report.utils

import kotlinx.datetime.Clock
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.SessionStatus
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins [LogsRepo.getSessionInfoSummary], the cheap read the sessions-list poll uses: it must agree
 * with the full-parse [LogsRepo.getSessionInfoDirect] on the fields the list renders, while parsing
 * only the status-change logs (the full parse across every session on every poll exhausted the
 * daemon heap).
 */
class LogsRepoSummaryTest {

  private fun tempLogsDir(): File = Files.createTempDirectory("logs-repo-summary").toFile()

  private fun startedStatus() = SessionStatus.Started(
    trailConfig = null,
    trailFilePath = null,
    hasRecordedSteps = false,
    testMethodName = "test",
    testClassName = "LogsRepoSummaryTest",
    trailblazeDeviceInfo = TrailblazeDeviceInfo(
      trailblazeDeviceId = TrailblazeDeviceId("test-device", TrailblazeDevicePlatform.ANDROID),
      trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
      widthPixels = 100,
      heightPixels = 100,
    ),
  )

  @Test
  fun `summary agrees with the full parse on status and identity`() {
    val logsDir = tempLogsDir()
    val logsRepo = LogsRepo(logsDir, watchFileSystem = false)
    val sessionId = SessionId("summary-session")

    logsRepo.saveLogToDisk(
      TrailblazeLog.TrailblazeSessionStatusChangeLog(
        sessionStatus = startedStatus(),
        session = sessionId,
        timestamp = Clock.System.now(),
      ),
    )
    logsRepo.saveLogToDisk(
      TrailblazeLog.TrailblazeSessionStatusChangeLog(
        sessionStatus = SessionStatus.Ended.Succeeded(durationMs = 1234L),
        session = sessionId,
        timestamp = Clock.System.now(),
      ),
    )

    val direct = logsRepo.getSessionInfoDirect(sessionId)
    val summary = logsRepo.getSessionInfoSummary(sessionId)

    assertEquals(direct?.sessionId, summary?.sessionId)
    assertTrue(summary?.latestStatus is SessionStatus.Ended.Succeeded)
    assertEquals(direct?.latestStatus, summary?.latestStatus)
  }

  @Test
  fun `summary of a running session with recent activity stays Started`() {
    val logsDir = tempLogsDir()
    val logsRepo = LogsRepo(logsDir, watchFileSystem = false)
    val sessionId = SessionId("summary-running")

    logsRepo.saveLogToDisk(
      TrailblazeLog.TrailblazeSessionStatusChangeLog(
        sessionStatus = startedStatus(),
        session = sessionId,
        timestamp = Clock.System.now(),
      ),
    )

    // Fresh file mtimes = recent activity, so the abandonment heuristic must not fire.
    val summary = logsRepo.getSessionInfoSummary(sessionId)
    assertTrue(
      summary?.latestStatus is SessionStatus.Started,
      "recent-activity session must read as Started; got ${summary?.latestStatus}",
    )
  }

  @Test
  fun `summary is null for a session with no status logs`() {
    val logsDir = tempLogsDir()
    val logsRepo = LogsRepo(logsDir, watchFileSystem = false)
    val sessionId = SessionId("summary-empty")
    File(logsDir, sessionId.value).mkdirs()

    assertNull(logsRepo.getSessionInfoSummary(sessionId))
  }
}
