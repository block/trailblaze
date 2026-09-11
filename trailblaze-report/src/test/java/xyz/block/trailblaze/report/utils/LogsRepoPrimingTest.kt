package xyz.block.trailblaze.report.utils

import kotlinx.datetime.Clock
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.SessionStatus
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins `primeSessionCache`, the opt-out a one-shot command uses: single-read mode parses AND
 * retains every session's full log set at construction, which a command walking sessions one at a
 * time pays for without ever reading twice.
 */
class LogsRepoPrimingTest {

  private fun logsDirWith(vararg sessionIds: String): File {
    val logsDir = Files.createTempDirectory("logs-repo-priming").toFile()
    sessionIds.forEach { id ->
      val log: TrailblazeLog = TrailblazeLog.TrailblazeSessionStatusChangeLog(
        sessionStatus = SessionStatus.Ended.Succeeded(durationMs = 1234L),
        session = SessionId(id),
        timestamp = Clock.System.now(),
      )
      File(logsDir, id).apply { mkdirs() }
        .resolve("0_TrailblazeSessionStatusChangeLog.json")
        .writeText(TrailblazeJsonInstance.encodeToString(log))
    }
    return logsDir
  }

  /** The default is what the report and the desktop app depend on, so it has to stay the default. */
  @Test
  fun `single-read mode parses every session up front by default`() {
    val logsRepo = LogsRepo(logsDirWith("session-a", "session-b"), watchFileSystem = false)

    assertEquals(2, logsRepo.sessionInfoFlow.value.size)
  }

  @Test
  fun `opting out of priming leaves construction with nothing parsed`() {
    val logsRepo = LogsRepo(
      logsDirWith("session-a", "session-b"),
      watchFileSystem = false,
      primeSessionCache = false,
    )

    assertEquals(emptyList(), logsRepo.sessionInfoFlow.value)
  }

  /** Listing and on-demand reads are pure disk work, so opting out costs the caller nothing. */
  @Test
  fun `an unprimed repo still lists its sessions and reads the one asked for`() {
    val logsRepo = LogsRepo(
      logsDirWith("session-a", "session-b"),
      watchFileSystem = false,
      primeSessionCache = false,
    )

    assertEquals(
      listOf("session-a", "session-b"),
      logsRepo.getSessionIds().map { it.value }.sorted(),
    )
    val info = logsRepo.getSessionInfoDirect(SessionId("session-b"))
    assertTrue(info?.latestStatus is SessionStatus.Ended.Succeeded)
    assertEquals(1, logsRepo.getLogsForSession(SessionId("session-b")).size)
  }
}
