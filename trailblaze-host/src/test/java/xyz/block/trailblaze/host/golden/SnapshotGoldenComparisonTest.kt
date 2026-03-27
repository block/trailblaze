package xyz.block.trailblaze.host.golden

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.Clock
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.SessionStatus

class SnapshotGoldenComparisonTest {

  @get:Rule
  val tmp = TemporaryFolder()

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private fun solidImage(width: Int, height: Int, color: Color): BufferedImage {
    val img = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    val g = img.createGraphics()
    g.color = color
    g.fillRect(0, 0, width, height)
    g.dispose()
    return img
  }

  private fun writePng(image: BufferedImage, file: File) {
    ImageIO.write(image, "PNG", file)
  }

  private fun makeSessionLogs(
    trailFilePath: String,
    screenshotFileName: String,
    snapshotName: String,
    deviceClassifier: String = "desktop",
  ): List<TrailblazeLog> {
    val sessionId = SessionId("test-session")
    val now = Clock.System.now()

    val startedLog = TrailblazeLog.TrailblazeSessionStatusChangeLog(
      sessionStatus = SessionStatus.Started(
        trailConfig = null,
        trailFilePath = trailFilePath,
        hasRecordedSteps = false,
        testMethodName = "test",
        testClassName = "SnapshotGoldenComparisonTest",
        trailblazeDeviceInfo = TrailblazeDeviceInfo(
          trailblazeDeviceId = TrailblazeDeviceId("test-device", TrailblazeDevicePlatform.WEB),
          trailblazeDriverType = TrailblazeDriverType.COMPOSE,
          widthPixels = 100,
          heightPixels = 100,
          classifiers = listOf(TrailblazeDeviceClassifier(deviceClassifier)),
        ),
      ),
      session = sessionId,
      timestamp = now,
    )

    val snapshotLog = TrailblazeLog.TrailblazeSnapshotLog(
      displayName = snapshotName,
      screenshotFile = screenshotFileName,
      viewHierarchy = ViewHierarchyTreeNode(resourceId = "root"),
      deviceWidth = 100,
      deviceHeight = 100,
      session = sessionId,
      timestamp = now,
    )

    return listOf(startedLog, snapshotLog)
  }

  // ---------------------------------------------------------------------------
  // Tests
  // ---------------------------------------------------------------------------

  @Test
  fun `identical images pass and produce no diff file`() {
    val trailDir = tmp.newFolder("trails")
    val sessionDir = tmp.newFolder("session")
    val snapshotName = "home-tab"
    val classifier = "desktop"

    val image = solidImage(100, 100, Color.BLUE)
    writePng(image, File(sessionDir, "screenshot.png"))
    writePng(image, File(trailDir, "$classifier.$snapshotName.golden.png"))

    val logs = makeSessionLogs(
      trailFilePath = File(trailDir, "home-tab.trail.yaml").absolutePath,
      screenshotFileName = "screenshot.png",
      snapshotName = snapshotName,
      deviceClassifier = classifier,
    )

    val result = SnapshotGoldenComparison.compare(
      sessionId = SessionId("test-session"),
      sessionDir = sessionDir,
      logs = logs,
    )

    assertTrue(result.passed)
    assertEquals(1, result.results.size)
    with(result.results[0]) {
      assertTrue(passed)
      assertTrue(goldenFound)
      assertEquals(0, pixelDifferences)
      assertNull(diffImagePath)
    }
    assertFalse(File(sessionDir, "screenshot.diff.png").exists())
  }

  @Test
  fun `different images fail and produce a diff PNG`() {
    val trailDir = tmp.newFolder("trails")
    val sessionDir = tmp.newFolder("session")
    val snapshotName = "home-tab"
    val classifier = "desktop"

    writePng(solidImage(100, 100, Color.BLUE), File(sessionDir, "screenshot.png"))
    writePng(solidImage(100, 100, Color.RED), File(trailDir, "$classifier.$snapshotName.golden.png"))

    val logs = makeSessionLogs(
      trailFilePath = File(trailDir, "home-tab.trail.yaml").absolutePath,
      screenshotFileName = "screenshot.png",
      snapshotName = snapshotName,
      deviceClassifier = classifier,
    )

    val result = SnapshotGoldenComparison.compare(
      sessionId = SessionId("test-session"),
      sessionDir = sessionDir,
      logs = logs,
    )

    assertFalse(result.passed)
    assertEquals(1, result.results.size)
    with(result.results[0]) {
      assertFalse(passed)
      assertTrue(goldenFound)
      assertTrue(pixelDifferences > 0)
      assertNotNull(diffImagePath)
    }

    val diffFile = File(sessionDir, "screenshot.diff.png")
    assertTrue(diffFile.exists(), "Expected diff PNG to be written to session dir")

    // Diff image should be 3 panels wide
    val diffImage = ImageIO.read(diffFile)
    assertEquals(100 * 3 + 4 * 2, diffImage.width)
    assertEquals(100 + 24, diffImage.height)

    // Diff panel (centre) should contain red pixels where images differ
    val labelH = 24
    val panelW = 100
    val gap = 4
    val diffPanelX = panelW + gap + panelW / 2  // centre of diff panel
    val diffPanelY = labelH + panelW / 2         // middle of image
    val pixel = diffImage.getRGB(diffPanelX, diffPanelY)
    val red = (pixel shr 16) and 0xFF
    val green = (pixel shr 8) and 0xFF
    val blue = pixel and 0xFF
    assertEquals(255, red, "Diff panel pixel should be red")
    assertEquals(0, green)
    assertEquals(0, blue)
  }

  @Test
  fun `missing golden is skipped and passes`() {
    val trailDir = tmp.newFolder("trails")
    val sessionDir = tmp.newFolder("session")

    writePng(solidImage(100, 100, Color.BLUE), File(sessionDir, "screenshot.png"))
    // No golden file written

    val logs = makeSessionLogs(
      trailFilePath = File(trailDir, "home-tab.trail.yaml").absolutePath,
      screenshotFileName = "screenshot.png",
      snapshotName = "home-tab",
    )

    val result = SnapshotGoldenComparison.compare(
      sessionId = SessionId("test-session"),
      sessionDir = sessionDir,
      logs = logs,
    )

    assertTrue(result.passed)
    with(result.results[0]) {
      assertTrue(passed)
      assertFalse(goldenFound)
      assertNull(diffImagePath)
    }
  }

  @Test
  fun `no snapshots in session returns passed`() {
    val result = SnapshotGoldenComparison.compare(
      sessionId = SessionId("test-session"),
      sessionDir = tmp.newFolder("session"),
      logs = emptyList(),
    )

    assertTrue(result.passed)
    assertTrue(result.results.isEmpty())
  }
}
