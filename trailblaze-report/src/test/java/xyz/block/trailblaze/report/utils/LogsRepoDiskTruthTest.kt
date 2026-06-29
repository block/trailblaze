package xyz.block.trailblaze.report.utils

import kotlinx.datetime.Clock
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.SessionStatus
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the disk-truth read the CLI run handler relies on: when a session's terminal `Ended`
 * log lands on disk but the in-memory cache flow was snapshotted before it, the cached read
 * stays stale while the direct (disk) read surfaces `Ended`. The handler's post-completion
 * poll loop must use the disk read or it spins the full timeout on an already-finished run.
 */
class LogsRepoDiskTruthTest {

  private fun tempLogsDir(): File = Files.createTempDirectory("logs-repo-disk-truth").toFile()

  private fun writeEndedLogToDisk(logsDir: File, sessionId: SessionId) {
    val sessionDir = File(logsDir, sessionId.value).apply { mkdirs() }
    val log: TrailblazeLog = TrailblazeLog.TrailblazeSessionStatusChangeLog(
      sessionStatus = SessionStatus.Ended.Succeeded(durationMs = 1234L),
      session = sessionId,
      timestamp = Clock.System.now(),
    )
    // First hex char + .json so readLogFilesFromDisk picks it up.
    File(sessionDir, "0_TrailblazeSessionStatusChangeLog.json")
      .writeText(TrailblazeJsonInstance.encodeToString<TrailblazeLog>(log))
  }

  @Test
  fun `direct read surfaces on-disk Ended while the stale cache does not`() {
    val logsDir = tempLogsDir()
    // No watcher: the cache flow is frozen at its creation-time snapshot.
    val logsRepo = LogsRepo(logsDir, watchFileSystem = false)
    val sessionId = SessionId("session-finished-on-disk")

    // Prime the cache flow BEFORE the Ended log exists, so the cached read is stale (empty).
    File(logsDir, sessionId.value).mkdirs()
    val cachedBefore = logsRepo.getSessionInfo(sessionId)

    // Now the trail finishes: its terminal Ended log lands on disk.
    writeEndedLogToDisk(logsDir, sessionId)

    val directInfo = logsRepo.getSessionInfoDirect(sessionId)
    val cachedInfo = logsRepo.getSessionInfo(sessionId)

    assertTrue(
      directInfo?.latestStatus is SessionStatus.Ended,
      "disk read must surface the on-disk Ended status",
    )
    assertFalse(
      cachedInfo?.latestStatus is SessionStatus.Ended,
      "cached read must stay stale (the bug the handler hit): got ${cachedInfo?.latestStatus}",
    )
    // Sanity: the cache was genuinely primed empty before the Ended log arrived.
    assertFalse(cachedBefore?.latestStatus is SessionStatus.Ended)
  }
}
