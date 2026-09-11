package xyz.block.trailblaze.report.utils

import kotlinx.datetime.Instant
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.client.temp.OtherTrailblazeTool
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.logs.model.TrailblazeClockDomain
import xyz.block.trailblaze.report.SessionLogSnapshot
import xyz.block.trailblaze.util.Console
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The disk read every session consumer shares — the report's log list and storyboard, the CLI's
 * terminal state, the desktop session view — must hand back ONE timeline.
 *
 * The session here is the shape that broke: a tool ran after the session-start log, but its log was
 * stamped by a device whose clock trails the host's by 3 seconds, so its raw stamp predates the
 * start log. Every consumer treats this list as chronological, so an un-normalized read shows the
 * tool before the session began.
 */
class LogsRepoClockNormalizationTest {

  private val sessionId = SessionId("session-with-a-lagging-device-clock")

  @Test
  fun `a device-stamped log is returned on the host timeline, in order`() {
    val logsRepo = LogsRepo(tempLogsDir(), watchFileSystem = false)

    val logs = logsRepo.getLogsForSession(sessionId)

    assertEquals(
      listOf("TrailblazeSessionStatusChangeLog", "TrailblazeToolLog"),
      logs.map { it::class.simpleName },
      "the tool ran after the session started and must be ordered that way",
    )
    val tool = logs.last()
    assertEquals(11_000, tool.timestamp.toEpochMilliseconds(), "the device's 3s lag must be removed")
    assertEquals(
      TrailblazeClockDomain.HOST,
      tool.clock,
      "a shifted stamp must say which clock it is now on, or a second reader shifts it again",
    )
    assertEquals(
      11_500,
      tool.hostReceivedAt?.toEpochMilliseconds(),
      "the ingestion anchor stays as the provenance of the shift",
    )
  }

  @Test
  fun `the log a viewer holds still resolves to its file on disk`() {
    // "Reveal in Finder" hands back whatever the session view is holding, which is the normalized
    // log — while the file on disk still carries the device's own stamp. Matching only the raw
    // stamp silently found nothing.
    val logsRepo = LogsRepo(tempLogsDir(), watchFileSystem = false)
    val normalizedTool = logsRepo.getLogsForSession(sessionId).last()

    assertEquals(
      "1_TrailblazeToolLog.json",
      logsRepo.findLogFile(normalizedTool)?.name,
      "a normalized device log must still resolve to the file it was read from",
    )
  }

  @Test
  fun `a raw log read straight off disk still resolves to its file`() {
    // The other caller shape: a log that was never normalized must keep matching, or this fix
    // trades one silent miss for another.
    val logsDir = tempLogsDir()
    val logsRepo = LogsRepo(logsDir, watchFileSystem = false)
    val rawTool = toolLog(startMs = 8_000, clock = TrailblazeClockDomain.DEVICE, hostReceivedAtMs = 11_500)

    assertEquals("1_TrailblazeToolLog.json", logsRepo.findLogFile(rawTool)?.name)
  }

  @Test
  fun `an all-host session is unchanged`() {
    val logsDir = Files.createTempDirectory("logs-repo-clock-host-only").toFile()
    val sessionDir = File(logsDir, sessionId.value).apply { mkdirs() }
    write(sessionDir, "0_TrailblazeSessionStatusChangeLog.json", startedLog(atMs = 10_000))
    write(
      sessionDir,
      "1_TrailblazeToolLog.json",
      toolLog(startMs = 11_000, clock = TrailblazeClockDomain.HOST, hostReceivedAtMs = null),
    )

    val logs = LogsRepo(logsDir, watchFileSystem = false).getLogsForSession(sessionId)

    assertEquals(listOf(10_000L, 11_000L), logs.map { it.timestamp.toEpochMilliseconds() })
  }

  @Test
  fun `the offset is logged once per session, and again only when it changes`() {
    // A running session re-reads this list on every log file it writes; logging each read would
    // bury the daemon log, and logging never would leave a misplaced span undiagnosable.
    val logsDir = tempLogsDir()
    // Constructed inside the capture: the repo reads its sessions during construction, and that
    // read is the one that logs first.
    lateinit var logsRepo: LogsRepo
    val afterRepeatRead = captureClockLogLines {
      logsRepo = LogsRepo(logsDir, watchFileSystem = false)
      logsRepo.getLogsForSession(sessionId)
      logsRepo.getLogsForSession(sessionId)
    }
    assertEquals(1, afterRepeatRead.size, "a repeat read at the same offset must stay silent; got $afterRepeatRead")

    // A slower upload from the same device: one more anchor, same minimum. A running session
    // anchors tools continuously, so counting this as a change is what buries the daemon log.
    write(
      File(logsDir, sessionId.value),
      "2_TrailblazeToolLog.json",
      toolLog(startMs = 7_000, clock = TrailblazeClockDomain.DEVICE, hostReceivedAtMs = 11_500),
    )
    val afterExtraAnchor = captureClockLogLines { logsRepo.getLogsForSession(sessionId) }
    assertEquals(
      emptyList(),
      afterExtraAnchor,
      "another anchor at the same offset must stay silent",
    )

    // A faster upload lowers the minimum — the offset every reader applies really did move.
    write(
      File(logsDir, sessionId.value),
      "3_TrailblazeToolLog.json",
      toolLog(startMs = 9_000, clock = TrailblazeClockDomain.DEVICE, hostReceivedAtMs = 11_500),
    )
    val afterOffsetChange = captureClockLogLines { logsRepo.getLogsForSession(sessionId) }
    assertEquals(1, afterOffsetChange.size, "a changed offset must be reported; got $afterOffsetChange")
  }

  @Test
  fun `the session carries the offset forward, because normalizing spends the evidence`() {
    // The device-log panel places logcat lines — stamped by the device, never normalized — against
    // the host timeline. It reads this off SessionInfo because by the time anyone holds the logs,
    // they are marked `clock: host` and the offset can no longer be derived from them.
    val logsRepo = LogsRepo(tempLogsDir(), watchFileSystem = false)

    assertEquals(TrailblazeClockDomain.HOST, logsRepo.getLogsForSession(sessionId).last().clock)
    assertEquals(3_000, logsRepo.getSessionInfo(sessionId)?.deviceClockOffsetMs)
  }

  @Test
  fun `a repo seeded from a snapshot carries the offset the snapshot measured`() {
    // The report never re-reads: it seeds this repo with a snapshot's already-normalized logs, so
    // without seeding the offset alongside them the report's session view is the one reader left
    // with no way to place a device log stream.
    val logsDir = tempLogsDir()
    val snapshot = SessionLogSnapshot.capture(logsDir, sessionId)
    assertEquals(3_000, snapshot.deviceClockOffsetMs)

    val logsRepo = LogsRepo(
      logsDir = logsDir,
      watchFileSystem = false,
      preParsedLogs = mapOf(sessionId to snapshot.logs),
      preDerivedClockOffsetsMs = mapOf(sessionId to snapshot.deviceClockOffsetMs!!),
    )

    assertEquals(3_000, logsRepo.getSessionInfo(sessionId)?.deviceClockOffsetMs)
  }

  @Test
  fun `a device-stamped session with no ingestion anchor says so`() {
    // It reads raw, which is indistinguishable from a session that never needed normalizing
    // unless the missing anchors are called out.
    val logsDir = Files.createTempDirectory("logs-repo-clock-unanchored").toFile()
    val sessionDir = File(logsDir, sessionId.value).apply { mkdirs() }
    write(sessionDir, "0_TrailblazeSessionStatusChangeLog.json", startedLog(atMs = 10_000))
    write(
      sessionDir,
      "1_TrailblazeToolLog.json",
      toolLog(startMs = 8_000, clock = TrailblazeClockDomain.DEVICE, hostReceivedAtMs = null),
    )

    var logs: List<TrailblazeLog> = emptyList()
    val lines = captureClockLogLines {
      logs = LogsRepo(logsDir, watchFileSystem = false).getLogsForSession(sessionId)
    }

    assertEquals(8_000, logs.first().timestamp.toEpochMilliseconds(), "with no anchor it reads raw")
    assertTrue(
      lines.any { it.contains("NO ingestion anchor") },
      "the unanchored case must be reported; got $lines",
    )
  }

  /**
   * The `[log-clock]` lines [block] emits. `Console` caches `System.out` in a private field at
   * class-init time, so `System.setOut` alone would not capture it — same reflective swap
   * `ConsoleTest` uses.
   */
  private fun captureClockLogLines(block: () -> Unit): List<String> {
    val outField = Console::class.java.getDeclaredField("out").apply { isAccessible = true }
    val original = outField.get(Console) as PrintStream
    val captured = ByteArrayOutputStream()
    outField.set(Console, PrintStream(captured, true, Charsets.UTF_8))
    try {
      block()
    } finally {
      outField.set(Console, original)
    }
    return captured.toString(Charsets.UTF_8).lines().filter { it.startsWith("[log-clock]") }
  }

  /** A logs dir holding the skewed session described in this class's kdoc. */
  private fun tempLogsDir(): File {
    val logsDir = Files.createTempDirectory("logs-repo-clock-normalization").toFile()
    val sessionDir = File(logsDir, sessionId.value).apply { mkdirs() }
    write(sessionDir, "0_TrailblazeSessionStatusChangeLog.json", startedLog(atMs = 10_000))
    // Ran at host 11_000 for 500ms on a device 3s behind: stamped 8_000, received at 11_500.
    write(
      sessionDir,
      "1_TrailblazeToolLog.json",
      toolLog(startMs = 8_000, clock = TrailblazeClockDomain.DEVICE, hostReceivedAtMs = 11_500),
    )
    return logsDir
  }

  private fun write(sessionDir: File, fileName: String, log: TrailblazeLog) =
    File(sessionDir, fileName).writeText(TrailblazeJsonInstance.encodeToString<TrailblazeLog>(log))

  private fun startedLog(atMs: Long) = TrailblazeLog.TrailblazeSessionStatusChangeLog(
    sessionStatus = SessionStatus.Unknown,
    session = sessionId,
    timestamp = Instant.fromEpochMilliseconds(atMs),
    clock = TrailblazeClockDomain.HOST,
  )

  private fun toolLog(
    startMs: Long,
    clock: TrailblazeClockDomain,
    hostReceivedAtMs: Long?,
  ) = TrailblazeLog.TrailblazeToolLog(
    trailblazeTool = OtherTrailblazeTool(toolName = "tapOn"),
    toolName = "tapOn",
    successful = true,
    traceId = null,
    durationMs = 500,
    session = sessionId,
    timestamp = Instant.fromEpochMilliseconds(startMs),
    clock = clock,
    hostReceivedAt = hostReceivedAtMs?.let { Instant.fromEpochMilliseconds(it) },
  )
}
