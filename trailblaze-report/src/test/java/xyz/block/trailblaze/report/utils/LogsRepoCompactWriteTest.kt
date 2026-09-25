package xyz.block.trailblaze.report.utils

import kotlinx.datetime.Clock
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.SessionStatus
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Session logs are written without pretty-print indentation.
 *
 * These files are deep view-hierarchy trees with one short value per line, which is the worst
 * case for a 4-space indent — on a real session, whitespace was 92% of 140 MB of JSON. What the
 * indent buys (readability) is worth nothing here and everything on the config files that share
 * the same `Json` instance, hence a separate compact instance rather than flipping the shared one.
 */
class LogsRepoCompactWriteTest {

  private fun tempLogsDir(): File = Files.createTempDirectory("logs-repo-compact").toFile()

  /** A tree deep enough that indentation, not content, would dominate the file. */
  private fun nestedHierarchy(depth: Int): ViewHierarchyTreeNode {
    var node = ViewHierarchyTreeNode(nodeId = depth.toLong(), className = "Leaf", text = "leaf")
    for (level in depth - 1 downTo 0) {
      node = ViewHierarchyTreeNode(nodeId = level.toLong(), className = "ViewGroup", children = listOf(node))
    }
    return node
  }

  private fun snapshotLog(sessionId: SessionId) = TrailblazeLog.TrailblazeSnapshotLog(
    displayName = null,
    viewHierarchy = nestedHierarchy(depth = 25),
    screenshotFile = "shot.png",
    session = sessionId,
    timestamp = Clock.System.now(),
    deviceWidth = 1080,
    deviceHeight = 1920,
  )

  @Test
  fun `a written log carries no indentation`() {
    val logsRepo = LogsRepo(tempLogsDir(), watchFileSystem = false)
    val sessionId = SessionId("compact-write")

    val file = logsRepo.saveLogToDisk(snapshotLog(sessionId))

    val text = file.readText()
    assertFalse(
      text.contains("\n    "),
      "a session log must not be written with pretty-print indentation",
    )
    assertEquals(1, text.lines().size, "a compact log is a single line")
  }

  @Test
  fun `dropping the indent shrinks a deep tree several times over`() {
    // Guards the actual win, not just the absence of spaces: if someone restores the pretty
    // instance here, the byte count regresses and this fails even though the JSON still parses.
    val logsRepo = LogsRepo(tempLogsDir(), watchFileSystem = false)
    val sessionId = SessionId("compact-size")
    val log = snapshotLog(sessionId)

    val compactBytes = logsRepo.saveLogToDisk(log).length()
    val prettyBytes = TrailblazeJsonInstance.encodeToString<TrailblazeLog>(log).length.toLong()

    assertTrue(
      compactBytes * 3 < prettyBytes,
      "compact write must be at least 3x smaller than the pretty one " +
        "(compact=$compactBytes pretty=$prettyBytes)",
    )
  }

  @Test
  fun `a compact log round-trips through the reader unchanged`() {
    // Only whitespace changed, so nothing that reads a session log should notice.
    val logsDir = tempLogsDir()
    val logsRepo = LogsRepo(logsDir, watchFileSystem = false)
    val sessionId = SessionId("compact-round-trip")
    val original = snapshotLog(sessionId)

    val file = logsRepo.saveLogToDisk(original)
    val decoded = TrailblazeJsonInstance.decodeFromString<TrailblazeLog>(file.readText())

    assertEquals(original, decoded)
  }

  @Test
  fun `a pretty log already on disk still reads`() {
    // Sessions written by an older build stay readable — the change is write-side only.
    val logsDir = tempLogsDir()
    val logsRepo = LogsRepo(logsDir, watchFileSystem = false)
    val sessionId = SessionId("legacy-pretty")
    val sessionDir = File(logsDir, sessionId.value).apply { mkdirs() }
    val legacy: TrailblazeLog = TrailblazeLog.TrailblazeSessionStatusChangeLog(
      sessionStatus = SessionStatus.Ended.Succeeded(durationMs = 7L),
      session = sessionId,
      timestamp = Clock.System.now(),
    )
    File(sessionDir, "0_TrailblazeSessionStatusChangeLog.json")
      .writeText(TrailblazeJsonInstance.encodeToString<TrailblazeLog>(legacy))

    assertTrue(
      logsRepo.getSessionInfoDirect(sessionId)?.latestStatus is SessionStatus.Ended,
      "a pretty-printed log from an older build must still be readable",
    )
  }
}
