package xyz.block.trailblaze.host.golden

import com.dropbox.differ.Color
import com.dropbox.differ.Image
import com.dropbox.differ.Mask
import com.dropbox.differ.SimpleImageComparator
import java.awt.Color as AwtColor
import java.awt.Font
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.util.Console

/**
 * Compares snapshots captured during a trail run against checked-in golden files.
 *
 * Goldens live alongside the trail file, named:
 *   `{device-classifier}.{snapshot-name}.golden.png`
 *
 * Example: `trails/desktop/desktop.home-tab.golden.png`
 *
 * Call [compare] after a trail completes to get per-snapshot diff results.
 */
object SnapshotGoldenComparison {

  /** Maximum color distance (Euclidean in RGBA space) to consider two pixels identical. */
  private const val MAX_DISTANCE = 0.1f

  @Serializable
  data class SnapshotDiffResult(
    val snapshotName: String,
    val goldenPath: String,
    val pixelDifferences: Int,
    val totalPixels: Int,
    val diffPercent: Double,
    val goldenFound: Boolean,
    val passed: Boolean,
    /** Threshold used: trail passes if diffPercent <= this value. */
    val thresholdPercent: Double,
    /** Path to the 3-panel diff image (golden | diff | actual), or null if comparison passed. */
    val diffImagePath: String? = null,
  )

  @Serializable
  data class GoldenComparisonResult(
    val sessionId: String,
    val results: List<SnapshotDiffResult>,
    val passed: Boolean,
  ) {
    val summary: String
      get() {
        val total = results.size
        val pass = results.count { it.passed }
        val noGolden = results.count { !it.goldenFound }
        return "Golden comparison: $pass/$total passed" +
          (if (noGolden > 0) ", $noGolden missing goldens (skipped)" else "")
      }
  }

  /**
   * Runs golden comparison for all snapshots in [sessionId].
   *
   * @param sessionId The session to compare.
   * @param sessionDir The session's log directory (e.g. `logs/2026_03_18_...`).
   * @param logs All log entries for the session (already parsed).
   * @param thresholdPercent Trail passes if diffPercent <= this value (default 0.5%).
   */
  fun compare(
    sessionId: SessionId,
    sessionDir: File,
    logs: List<TrailblazeLog>,
    thresholdPercent: Double = 2.0,
  ): GoldenComparisonResult {
    val startedStatus = logs
      .filterIsInstance<TrailblazeLog.TrailblazeSessionStatusChangeLog>()
      .mapNotNull { it.sessionStatus as? SessionStatus.Started }
      .firstOrNull()

    val trailFilePath = startedStatus?.trailFilePath
    val trailDir = trailFilePath?.let { File(it).parentFile }
    val deviceClassifier = startedStatus
      ?.trailblazeDeviceInfo
      ?.classifiers
      ?.firstOrNull()
      ?.classifier
      ?: "unknown"

    val snapshotLogs = logs.filterIsInstance<TrailblazeLog.TrailblazeSnapshotLog>()

    if (snapshotLogs.isEmpty()) {
      Console.log("[Golden] No snapshots found in session ${sessionId.value}")
      return GoldenComparisonResult(sessionId.value, emptyList(), passed = true)
    }

    if (trailDir == null) {
      Console.log("[Golden] No trail file path in session ${sessionId.value} — skipping comparison")
      return GoldenComparisonResult(sessionId.value, emptyList(), passed = true)
    }

    val comparator = SimpleImageComparator(maxDistance = MAX_DISTANCE)
    val results = snapshotLogs.map { snapshot ->
      val snapshotName = snapshot.displayName ?: snapshot.screenshotFile
      val goldenFile = File(trailDir, "$deviceClassifier.$snapshotName.golden.png")
      val screenshotFile = File(sessionDir, snapshot.screenshotFile)

      compareSnapshot(
        snapshotName = snapshotName,
        goldenFile = goldenFile,
        screenshotFile = screenshotFile,
        sessionDir = sessionDir,
        comparator = comparator,
        thresholdPercent = thresholdPercent,
      )
    }

    val allPassed = results.all { it.passed }
    return GoldenComparisonResult(
      sessionId = sessionId.value,
      results = results,
      passed = allPassed,
    )
  }

  private fun compareSnapshot(
    snapshotName: String,
    goldenFile: File,
    screenshotFile: File,
    sessionDir: File,
    comparator: SimpleImageComparator,
    thresholdPercent: Double,
  ): SnapshotDiffResult {
    if (!goldenFile.exists()) {
      Console.log("[Golden] No golden found for '$snapshotName' at ${goldenFile.absolutePath} — skipping")
      return SnapshotDiffResult(
        snapshotName = snapshotName,
        goldenPath = goldenFile.absolutePath,
        pixelDifferences = 0,
        totalPixels = 0,
        diffPercent = 0.0,
        goldenFound = false,
        passed = true,
        thresholdPercent = thresholdPercent,
      )
    }

    if (!screenshotFile.exists()) {
      Console.log("[Golden] Screenshot file missing for '$snapshotName': ${screenshotFile.absolutePath}")
      return SnapshotDiffResult(
        snapshotName = snapshotName,
        goldenPath = goldenFile.absolutePath,
        pixelDifferences = -1,
        totalPixels = 0,
        diffPercent = 100.0,
        goldenFound = true,
        passed = false,
        thresholdPercent = thresholdPercent,
      )
    }

    return try {
      val golden = ImageIO.read(goldenFile) ?: error("Could not decode golden: ${goldenFile.name}")
      val screenshot = ImageIO.read(screenshotFile) ?: error("Could not decode screenshot: ${screenshotFile.name}")

      val mask = Mask(maxOf(golden.width, screenshot.width), maxOf(golden.height, screenshot.height))
      val result = comparator.compare(BufferedImageWrapper(golden), BufferedImageWrapper(screenshot), mask)

      val diffPercent = if (result.pixelCount > 0) {
        result.pixelDifferences.toDouble() / result.pixelCount * 100.0
      } else {
        0.0
      }
      val passed = diffPercent <= thresholdPercent

      val status = if (passed) "✅" else "❌"
      Console.log(
        "[Golden] $status '$snapshotName': %.2f%% diff (%d/%d pixels)".format(
          diffPercent, result.pixelDifferences, result.pixelCount
        )
      )

      val diffImagePath = if (!passed) {
        try {
          val diffFile = writeDiffImage(golden, screenshot, screenshotFile)
          Console.log("[Golden] Diff image saved: ${diffFile.absolutePath}")
          diffFile.absolutePath
        } catch (e: Exception) {
          Console.log("[Golden] Could not write diff image: ${e.message}")
          null
        }
      } else null

      SnapshotDiffResult(
        snapshotName = snapshotName,
        goldenPath = goldenFile.absolutePath,
        pixelDifferences = result.pixelDifferences,
        totalPixels = result.pixelCount,
        diffPercent = diffPercent,
        goldenFound = true,
        passed = passed,
        thresholdPercent = thresholdPercent,
        diffImagePath = diffImagePath,
      )
    } catch (e: Exception) {
      Console.log("[Golden] Error comparing '$snapshotName': ${e.message}")
      SnapshotDiffResult(
        snapshotName = snapshotName,
        goldenPath = goldenFile.absolutePath,
        pixelDifferences = -1,
        totalPixels = 0,
        diffPercent = 100.0,
        goldenFound = true,
        passed = false,
        thresholdPercent = thresholdPercent,
      )
    }
  }

  /**
   * Writes a 3-panel comparison image (Golden | Diff | Actual) alongside [screenshotFile].
   *
   * Pixels whose color distance exceeds [MAX_DISTANCE] are highlighted in red, matching the
   * comparator's threshold so the visualization is consistent with the pass/fail result.
   * Output is named `{screenshotFile.nameWithoutExtension}.diff.png` — using the screenshot
   * filename (auto-generated, unique per snapshot) avoids collisions when the same snapshot
   * name appears multiple times in a session.
   */
  private fun writeDiffImage(
    golden: BufferedImage,
    actual: BufferedImage,
    screenshotFile: File,
  ): File {
    val panelW = maxOf(golden.width, actual.width)
    val panelH = maxOf(golden.height, actual.height)
    val labelH = 24
    val gap = 4
    val totalW = panelW * 3 + gap * 2
    val totalH = panelH + labelH

    val out = BufferedImage(totalW, totalH, BufferedImage.TYPE_INT_ARGB)
    val g = out.createGraphics()

    // Background
    g.color = AwtColor(40, 40, 40)
    g.fillRect(0, 0, totalW, totalH)

    // Draw panels: golden (left), diff (centre), actual (right)
    g.drawImage(golden, 0, labelH, null)
    g.drawImage(actual, panelW * 2 + gap * 2, labelH, null)

    val goldenW = BufferedImageWrapper(golden)
    val actualW = BufferedImageWrapper(actual)

    // Build diff panel: red for pixels exceeding MAX_DISTANCE (matching the comparator threshold)
    val diffPanel = BufferedImage(panelW, panelH, BufferedImage.TYPE_INT_ARGB)
    for (x in 0 until panelW) {
      for (y in 0 until panelH) {
        val outOfBounds = x >= golden.width || y >= golden.height || x >= actual.width || y >= actual.height
        val differs = outOfBounds || colorDistance(goldenW.getPixel(x, y), actualW.getPixel(x, y)) > MAX_DISTANCE
        val srcPx = if (x < golden.width && y < golden.height) golden.getRGB(x, y) else 0
        diffPanel.setRGB(x, y, if (differs) 0xFFFF0000.toInt() else srcPx)
      }
    }
    g.drawImage(diffPanel, panelW + gap, labelH, null)

    // Labels
    g.color = AwtColor.WHITE
    g.font = Font("SansSerif", Font.BOLD, 13)
    g.drawString("Golden", 4, 16)
    g.drawString("Diff", panelW + gap + 4, 16)
    g.drawString("Actual", panelW * 2 + gap * 2 + 4, 16)

    g.dispose()

    val outFile = File(screenshotFile.parentFile, "${screenshotFile.nameWithoutExtension}.diff.png")
    ImageIO.write(out, "PNG", outFile)
    return outFile
  }

  /**
   * Euclidean color distance in RGBA space, matching differ's SimpleImageComparator.
   *
   * differ's [Color] stores r/g/b/a as normalised floats (0.0–1.0), so the distance
   * is computed directly without any additional scaling.
   */
  private fun colorDistance(a: Color, b: Color): Float {
    val dr = a.r - b.r
    val dg = a.g - b.g
    val db = a.b - b.b
    val da = a.a - b.a
    return kotlin.math.sqrt(dr * dr + dg * dg + db * db + da * da)
  }

  /** Wraps [BufferedImage] as a differ [Image]. */
  private class BufferedImageWrapper(private val image: BufferedImage) : Image {
    override val width: Int = image.width
    override val height: Int = image.height

    override fun getPixel(x: Int, y: Int): Color {
      val argb = image.getRGB(x, y)
      return Color(
        r = (argb shr 16) and 0xFF,
        g = (argb shr 8) and 0xFF,
        b = argb and 0xFF,
        a = (argb shr 24) and 0xFF,
      )
    }
  }
}
