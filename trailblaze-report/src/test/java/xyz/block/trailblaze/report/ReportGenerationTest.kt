package xyz.block.trailblaze.report

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.datetime.Instant
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.report.utils.LogsRepo

/**
 * Pins the observable contract of the shared report-generation core — the concurrency
 * guarantees of [overlapReports] and the session-scoping/cleanup behavior of
 * [withSessionScopedLogsRepo] — without touching the heavy report generators themselves
 * (the full pipeline is covered end-to-end by [GenerateReportCliCommandTest]).
 */
class ReportGenerationTest {

  // --- overlapReports ---

  @Test
  fun `overlapReports returns both artifacts when both legs succeed`() {
    val wasmFile = File("wasm.html")
    val interactiveFile = File("interactive.html")

    val artifacts = overlapReports(
      interactive = { interactiveFile },
      wasm = { wasmFile },
    )

    assertEquals(wasmFile, artifacts.wasmReport)
    assertEquals(interactiveFile, artifacts.interactiveReport)
  }

  @Test
  fun `overlapReports skips the interactive leg when null`() {
    val wasmFile = File("wasm.html")

    val artifacts = overlapReports(interactive = null, wasm = { wasmFile })

    assertEquals(wasmFile, artifacts.wasmReport)
    assertNull(artifacts.interactiveReport)
  }

  @Test
  fun `overlapReports propagates a wasm failure only after the interactive leg completed`() {
    // The interactive leg's side effects must land even when the WASM leg throws — that's
    // what keeps the (primary) interactive artifact from being discarded by a WASM crash.
    // The latch guarantees the interactive leg can only complete AFTER the WASM leg has
    // already thrown, so a green run proves the failure was held until the join.
    val wasmThrew = CountDownLatch(1)
    var interactiveCompleted = false

    assertFailsWith<IllegalStateException> {
      overlapReports(
        interactive = {
          wasmThrew.await()
          interactiveCompleted = true
          File("interactive.html")
        },
        wasm = {
          wasmThrew.countDown()
          error("wasm build failed")
        },
      )
    }

    assertTrue(
      interactiveCompleted,
      "the interactive leg must have been joined before the WASM failure propagated",
    )
  }

  @Test
  fun `overlapReports resolves an interactive failure to null and keeps the wasm artifact`() {
    val wasmFile = File("wasm.html")

    val artifacts = overlapReports(
      interactive = { error("bun exploded") },
      wasm = { wasmFile },
    )

    assertEquals(wasmFile, artifacts.wasmReport)
    assertNull(artifacts.interactiveReport)
  }

  // --- WasmReportRequest ---

  @Test
  fun `WasmReportRequest requires a template or a ui project dir`() {
    assertFailsWith<IllegalArgumentException> {
      WasmReportRequest(
        outputFile = File("report.html"),
        templateFile = null,
        trailblazeUiProjectDir = null,
      )
    }
  }

  // --- withSessionScopedLogsRepo ---

  @Test
  fun `null sessionIds passes the original repo through unscoped`() {
    val logsDir = Files.createTempDirectory("report-generation-test-").toFile()
    try {
      val logsRepo = LogsRepo(logsDir, watchFileSystem = false)
      withSessionScopedLogsRepo(logsRepo, sessionIds = null) { scoped ->
        assertSame(logsRepo, scoped)
      }
      logsRepo.close()
    } finally {
      logsDir.deleteRecursively()
    }
  }

  @Test
  fun `scoped repo sees only the selected sessions and cleanup preserves real session data`() {
    val logsDir = Files.createTempDirectory("report-generation-test-").toFile()
    try {
      val selectedLog = File(File(logsDir, "session-a").apply { mkdirs() }, "log.json")
      selectedLog.writeText("{}")
      File(File(logsDir, "session-b").apply { mkdirs() }, "log.json").writeText("{}")

      val logsRepo = LogsRepo(logsDir, watchFileSystem = false)
      var scopedDir: File? = null
      withSessionScopedLogsRepo(
        logsRepo,
        sessionIds = listOf(SessionId("session-a"), SessionId("session-missing")),
      ) { scoped ->
        scopedDir = scoped.logsDir
        assertEquals(listOf(SessionId("session-a")), scoped.getSessionIds())
      }

      // The scoping dir (and its symlinks) is cleaned up; the real session data is NOT —
      // symlinks are removed link-by-link, never by a recursive delete that would follow them.
      assertTrue(!scopedDir!!.exists(), "the temp scoping dir should be deleted")
      assertTrue(selectedLog.exists(), "real session data must survive the scoped repo cleanup")
      logsRepo.close()
    } finally {
      logsDir.deleteRecursively()
    }
  }

  @Test
  fun `scoped repo serves caller-provided pre-parsed logs without re-reading the session files`() {
    val logsDir = Files.createTempDirectory("report-generation-test-").toFile()
    try {
      val sessionId = SessionId("session-a")
      val logsRepo = LogsRepo(logsDir, watchFileSystem = false)
      val log = TrailblazeLog.TrailblazeSessionStatusChangeLog(
        sessionStatus = SessionStatus.Ended.Succeeded(durationMs = 1_000L),
        session = sessionId,
        timestamp = Instant.fromEpochMilliseconds(1_700_000_000_000L),
      )
      logsRepo.saveLogToDisk(log)
      val preParsed = mapOf(sessionId to listOf<TrailblazeLog>(log))

      // Delete the on-disk record: the scoped repo must be able to serve the session
      // entirely from the handed-in parse (that's the WASM leg's parse-once guarantee).
      File(logsDir, sessionId.value).listFiles()!!.forEach { it.delete() }

      withSessionScopedLogsRepo(
        logsRepo,
        sessionIds = listOf(sessionId),
        preParsedLogs = preParsed,
      ) { scoped ->
        assertEquals(listOf<TrailblazeLog>(log), scoped.getCachedLogsForSession(sessionId))
      }
      logsRepo.close()
    } finally {
      logsDir.deleteRecursively()
    }
  }

  // --- SingleReadLogsRepoProvider ---

  @Test
  fun `shared snapshots keep the same descending-name session order as getSessionIds`() {
    val logsDir = Files.createTempDirectory("report-generation-test-").toFile()
    try {
      listOf("session-a", "session-c", "session-b").forEach { File(logsDir, it).mkdirs() }

      val provider = SingleReadLogsRepoProvider()
      val capturedOrder = provider.snapshots(logsDir).map { it.sessionId }

      // The interactive report embeds sessions in capture order, so the shared capture must
      // deliver the exact ordering the pre-snapshot path got from LogsRepo.getSessionIds.
      assertEquals(provider.get(logsDir).getSessionIds(), capturedOrder)
      assertEquals(
        listOf(SessionId("session-c"), SessionId("session-b"), SessionId("session-a")),
        capturedOrder,
      )
      provider.close()
    } finally {
      logsDir.deleteRecursively()
    }
  }

  @Test
  fun `scoping cleanup runs even when the block throws`() {
    val logsDir = Files.createTempDirectory("report-generation-test-").toFile()
    try {
      val sessionLog = File(File(logsDir, "session-a").apply { mkdirs() }, "log.json")
      sessionLog.writeText("{}")

      val logsRepo = LogsRepo(logsDir, watchFileSystem = false)
      var scopedDir: File? = null
      assertFailsWith<IllegalStateException> {
        withSessionScopedLogsRepo(
          logsRepo,
          sessionIds = listOf(SessionId("session-a")),
        ) { scoped ->
          scopedDir = scoped.logsDir
          error("report generation failed")
        }
      }

      assertTrue(!scopedDir!!.exists(), "the temp scoping dir should be deleted on failure too")
      assertTrue(sessionLog.exists(), "real session data must survive a failed scoped run")
      logsRepo.close()
    } finally {
      logsDir.deleteRecursively()
    }
  }
}
