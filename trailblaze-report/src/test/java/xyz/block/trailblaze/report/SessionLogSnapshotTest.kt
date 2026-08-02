package xyz.block.trailblaze.report

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.datetime.Instant
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.util.BunBinaryResolver

/**
 * Pins [SessionLogSnapshot]'s observable contract: the typed view matches what
 * [LogsRepo.getLogsForSession] returns (including cost enrichment), the raw view carries every
 * hex-prefixed record in chronological order (including ones that aren't decodable as
 * [TrailblazeLog]), and a captured snapshot is INDEPENDENT of the on-disk files — report
 * generation from a snapshot must succeed even after the log files are gone, which is what
 * proves the report path reads each file exactly once.
 */
class SessionLogSnapshotTest {

  private fun statusLog(sessionId: SessionId, status: SessionStatus, atMs: Long) =
    TrailblazeLog.TrailblazeSessionStatusChangeLog(
      sessionStatus = status,
      session = sessionId,
      timestamp = Instant.fromEpochMilliseconds(atMs),
    )

  private fun startedStatus(): SessionStatus.Started {
    val deviceId = TrailblazeDeviceId("web", TrailblazeDevicePlatform.WEB)
    return SessionStatus.Started(
      trailConfig = null,
      trailFilePath = "trails/example.trail.yaml",
      hasRecordedSteps = false,
      testMethodName = "snapshot",
      testClassName = "SessionLogSnapshotTest",
      trailblazeDeviceInfo = TrailblazeDeviceInfo(
        trailblazeDeviceId = deviceId,
        trailblazeDriverType = TrailblazeDriverType.PLAYWRIGHT_NATIVE,
        widthPixels = 1280,
        heightPixels = 720,
        classifiers = listOf(TrailblazeDevicePlatform.WEB.asTrailblazeDeviceClassifier()),
      ),
      trailblazeDeviceId = deviceId,
      rawYaml = "trail:\n  - step: Open the app",
    )
  }

  private fun writeSession(logsRepo: LogsRepo, sessionId: SessionId) {
    logsRepo.saveLogToDisk(statusLog(sessionId, startedStatus(), 1_700_000_000_000L))
    logsRepo.saveLogToDisk(
      statusLog(sessionId, SessionStatus.Ended.Succeeded(durationMs = 5_000L), 1_700_000_005_000L),
    )
  }

  @Test
  fun capture_typedViewMatchesTheRepoParse_andRawViewKeepsUndecodableRecords() {
    val tmp = Files.createTempDirectory("snapshot-test-").toFile()
    try {
      val writerRepo = LogsRepo(logsDir = tmp, watchFileSystem = false)
      val sessionId = SessionId("snapshotsession")
      writeSession(writerRepo, sessionId)
      val sessionDir = writerRepo.getSessionDir(sessionId)
      // Valid JSON but not decodable as TrailblazeLog: dropped from the typed view, kept in
      // the raw view (the interactive report embeds raw records as-is).
      File(sessionDir, "0zz_not-a-log.json").writeText("""{"custom":"record"}""")
      // Not hex-prefixed: not a log record at all, excluded from both views.
      File(sessionDir, "recording.trail.json").writeText("""{"ignored":true}""")
      writerRepo.close()

      // Fresh repo so nothing is cached — capture parses from disk.
      val logsRepo = LogsRepo(logsDir = tmp, watchFileSystem = false)
      val snapshot = SessionLogSnapshot.capture(logsRepo, sessionId)

      assertEquals(logsRepo.getLogsForSession(sessionId), snapshot.logs)
      assertEquals(2, snapshot.logs.size)

      // Raw view: all three hex-prefixed records, filename order, undecodable one included.
      assertEquals(3, snapshot.rawLogsJson.size)
      assertEquals(
        "record",
        snapshot.rawLogsJson.first { "custom" in it.jsonObject }
          .jsonObject["custom"]!!.jsonPrimitive.content,
      )
      logsRepo.close()
    } finally {
      tmp.deleteRecursively()
    }
  }

  /**
   * The device-farm ingest writes hex-hashed log filenames (`a1b2c3d4_…`) instead of the on-device
   * ordinal ones (`001_…`), so filename order says nothing about when a record happened. The raw
   * view feeds the interactive report's extractor, which folds ADJACENT records into steps — out of
   * order it renders a scrambled timeline, which is what a farm run's report showed.
   */
  @Test
  fun capture_rawViewIsChronological_whenFilenamesAreHexHashedRatherThanOrdinal() {
    val tmp = Files.createTempDirectory("snapshot-order-test-").toFile()
    try {
      val writerRepo = LogsRepo(logsDir = tmp, watchFileSystem = false)
      val sessionId = SessionId("hexnamedsession")
      val instants = listOf(1_700_000_001_000L, 1_700_000_002_000L, 1_700_000_003_000L)
      writerRepo.saveLogToDisk(statusLog(sessionId, startedStatus(), instants[0]))
      writerRepo.saveLogToDisk(statusLog(sessionId, startedStatus(), instants[1]))
      writerRepo.saveLogToDisk(
        statusLog(sessionId, SessionStatus.Ended.Succeeded(durationMs = 2_000L), instants[2]),
      )
      val sessionDir = writerRepo.getSessionDir(sessionId)
      writerRepo.close()

      // Re-stamp the ordinal names with hex ones whose lexicographic order is exactly REVERSED,
      // reproducing the farm layout in its worst case.
      val ordinal = sessionDir.listFiles()!!.filter { it.extension == "json" }.sortedBy { it.name }
      assertEquals(3, ordinal.size)
      listOf("cccccccc", "bbbbbbbb", "aaaaaaaa").forEachIndexed { i, hex ->
        assertTrue(ordinal[i].renameTo(File(sessionDir, "${hex}_${ordinal[i].name.substringAfter('_')}")))
      }

      val logsRepo = LogsRepo(logsDir = tmp, watchFileSystem = false)
      val snapshot = SessionLogSnapshot.capture(logsRepo, sessionId)

      assertEquals(3, snapshot.rawLogsJson.size)
      val rawTimestamps = snapshot.rawLogsJson.map {
        Instant.parse(it.jsonObject["timestamp"]!!.jsonPrimitive.content).toEpochMilliseconds()
      }
      assertEquals(instants, rawTimestamps)
      // Both views agree on order, so the report's meta and its timeline can't disagree.
      assertEquals(snapshot.logs.map { it.timestamp.toEpochMilliseconds() }, rawTimestamps)
      logsRepo.close()
    } finally {
      tmp.deleteRecursively()
    }
  }

  @Test
  fun capture_typedViewCarriesTheRepoCostEnrichment() {
    val tmp = Files.createTempDirectory("snapshot-test-").toFile()
    try {
      val writerRepo = LogsRepo(logsDir = tmp, watchFileSystem = false)
      val sessionId = SessionId("enrichedsession")
      writeSession(writerRepo, sessionId)
      writerRepo.close()

      // An enricher that observably rewrites every log on read (the production enricher
      // recalculates LLM costs the same way). The snapshot's typed view must carry it —
      // it's the same view LogsRepo serves everywhere else.
      val enrichedInstant = Instant.fromEpochMilliseconds(1_800_000_000_000L)
      val logsRepo = LogsRepo(
        logsDir = tmp,
        watchFileSystem = false,
        costEnricher = { log ->
          (log as? TrailblazeLog.TrailblazeSessionStatusChangeLog)?.copy(timestamp = enrichedInstant) ?: log
        },
      )
      val snapshot = SessionLogSnapshot.capture(logsRepo, sessionId)
      assertEquals(2, snapshot.logs.size)
      assertTrue(
        snapshot.logs.all { it.timestamp == enrichedInstant },
        "the typed view must be enriched exactly like LogsRepo's own reads",
      )
      logsRepo.close()
    } finally {
      tmp.deleteRecursively()
    }
  }

  /**
   * The disk-truth pin (companion to `LogsRepoDiskTruthTest`): a cached flow primed before a
   * session's logs land stays stale, and the CLI captures right after a terminal-status poll
   * that reads disk — so the snapshot must reflect the CURRENT files, never a lagging cache.
   */
  @Test
  fun capture_readsDiskTruthEvenWhenTheRepoCacheIsStale() {
    val tmp = Files.createTempDirectory("snapshot-test-").toFile()
    try {
      val logsRepo = LogsRepo(logsDir = tmp, watchFileSystem = false)
      val sessionId = SessionId("stalecachesession")

      // Prime the repo's cache flow while the session dir is still empty (stale = empty).
      File(tmp, sessionId.value).mkdirs()
      assertTrue(logsRepo.getCachedLogsForSession(sessionId).isEmpty())

      // Now the trail finishes: its logs (including the terminal Ended status) land on disk.
      val writerRepo = LogsRepo(logsDir = tmp, watchFileSystem = false)
      writeSession(writerRepo, sessionId)
      writerRepo.close()

      val snapshot = SessionLogSnapshot.capture(logsRepo, sessionId)
      assertEquals(2, snapshot.logs.size, "capture must surface the on-disk logs, not the stale cache")
      assertTrue(
        snapshot.logs.any {
          it is TrailblazeLog.TrailblazeSessionStatusChangeLog && it.sessionStatus is SessionStatus.Ended
        },
        "the on-disk terminal status must be visible in the snapshot",
      )
      logsRepo.close()
    } finally {
      tmp.deleteRecursively()
    }
  }

  /**
   * The core parse-once guarantee: once captured, report generation reads NOTHING from the
   * session's log files — deleting them before generating changes nothing. Skipped (vacuous
   * pass) when bun isn't resolvable, matching [RunReportGeneratorTest]'s end-to-end test.
   */
  @Test
  fun generateFromSnapshots_producesTheReportEvenAfterLogFilesAreDeleted() {
    val bun = BunBinaryResolver.resolveBunBinary() ?: return
    val tmp = Files.createTempDirectory("snapshot-e2e-").toFile()
    try {
      val logsRepo = LogsRepo(logsDir = tmp, watchFileSystem = false)
      val sessionId = SessionId("deletedlogsession")
      writeSession(logsRepo, sessionId)

      val snapshots = SessionLogSnapshot.captureAll(logsRepo, listOf(sessionId))
      // Delete every log record the snapshot was captured from.
      logsRepo.getSessionDir(sessionId).listFiles()!!
        .filter { it.extension == "json" }
        .forEach { assertTrue(it.delete()) }

      val report = RunReportGenerator(bunBinary = bun).generateFromSnapshots(logsRepo, snapshots)

      assertNotNull(report, "the report must be generated entirely from the snapshot")
      val html = report.readText()
      assertTrue(html.contains("\"status\":\"passed\""), "succeeded session maps to a passed badge")
      assertTrue(html.contains("Open the app"), "authored YAML captured at session start is embedded")
      logsRepo.close()
    } finally {
      tmp.deleteRecursively()
    }
  }
}
