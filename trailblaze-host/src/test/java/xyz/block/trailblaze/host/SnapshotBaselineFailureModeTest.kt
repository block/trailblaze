package xyz.block.trailblaze.host

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString
import kotlinx.datetime.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.exception.TrailblazeException
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.SessionId

/**
 * Snapshot comparison is best-effort by design — a snapshot with no reference is skipped so a new
 * trail can run before its golden exists. An explicitly requested `--snapshot-baseline` inverts
 * that: the caller asked for these captures to be checked, so every way the check can fail to
 * happen has to surface as a failed run rather than a run that compared nothing and passed.
 */
class SnapshotBaselineFailureModeTest {

  @get:Rule
  val tmp = TemporaryFolder()

  private val sessionId = SessionId("session-under-test")

  /** A logs dir holding one session with a single named snapshot capture. */
  private fun logsDirWithSnapshot(): File {
    val logsDir = tmp.newFolder("logs")
    val sessionDir = File(logsDir, sessionId.value).apply { mkdirs() }
    val image = BufferedImage(20, 20, BufferedImage.TYPE_INT_ARGB)
    image.createGraphics().apply {
      color = Color.BLUE
      fillRect(0, 0, 20, 20)
      dispose()
    }
    ImageIO.write(image, "PNG", File(sessionDir, "shot.png"))
    val log: TrailblazeLog = TrailblazeLog.TrailblazeSnapshotLog(
      displayName = "home",
      screenshotFile = "shot.png",
      viewHierarchy = ViewHierarchyTreeNode(resourceId = "root"),
      deviceWidth = 20,
      deviceHeight = 20,
      session = sessionId,
      timestamp = Instant.parse("2026-01-01T00:00:01Z"),
    )
    File(sessionDir, "001_snapshot.json").writeText(TrailblazeJsonInstance.encodeToString(log))
    return logsDir
  }

  @Test
  fun `a requested baseline fails when the run wrote no session logs`() {
    // What `--no-logging` leaves behind: nothing was captured, so there is nothing to compare.
    val emptyLogsDir = tmp.newFolder("empty-logs")

    val error = assertFailsWith<TrailblazeException> {
      TrailblazeHostYamlRunner.compareSnapshotsAgainstGoldens(
        sessionId = sessionId,
        logsDir = emptyLogsDir,
        snapshotBaselineRef = tmp.newFolder("some-baseline").absolutePath,
      )
    }
    assertTrue(
      error.message.orEmpty().contains("wrote no session logs"),
      "message should say why nothing was compared, was: ${error.message}",
    )
  }

  @Test
  fun `with no baseline requested a missing session directory stays a skip`() {
    val emptyLogsDir = tmp.newFolder("empty-logs-no-baseline")

    assertNull(
      TrailblazeHostYamlRunner.compareSnapshotsAgainstGoldens(
        sessionId = sessionId,
        logsDir = emptyLogsDir,
      ),
    )
  }

  @Test
  fun `a baseline that cannot be resolved fails the run rather than comparing nothing`() {
    val error = assertFailsWith<TrailblazeException> {
      TrailblazeHostYamlRunner.compareSnapshotsAgainstGoldens(
        sessionId = sessionId,
        logsDir = logsDirWithSnapshot(),
        snapshotBaselineRef = File(tmp.root, "never-published.zip").absolutePath,
      )
    }
    assertTrue(
      error.message.orEmpty().contains("could not be resolved"),
      "message should name the baseline that failed, was: ${error.message}",
    )
  }

  @Test
  fun `an out-of-range threshold fails before any comparison runs`() {
    val error = assertFailsWith<TrailblazeException> {
      TrailblazeHostYamlRunner.compareSnapshotsAgainstGoldens(
        sessionId = sessionId,
        logsDir = logsDirWithSnapshot(),
        snapshotBaselineRef = tmp.newFolder("threshold-baseline").absolutePath,
        snapshotBaselineThresholdPercent = 500.0,
      )
    }
    assertTrue(
      error.message.orEmpty().contains("between 0 and 100"),
      "the threshold, not the empty baseline dir, should be the reported problem, was: ${error.message}",
    )
  }
}
