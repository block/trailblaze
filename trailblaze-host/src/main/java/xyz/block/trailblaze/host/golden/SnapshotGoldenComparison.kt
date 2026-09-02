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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
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
  internal const val MAX_DISTANCE = 0.1f

  /** `@TrailblazeToolClass` name of the snapshot tool, as it lands in a tool log. */
  private const val TAKE_SNAPSHOT_TOOL_NAME = "takeSnapshot"

  /**
   * Screen names `takeSnapshot` was asked to capture that produced no [TrailblazeLog.TrailblazeSnapshotLog].
   *
   * The tool reports `Success` when the driver hands it no image, so the only record a failed
   * capture leaves is a tool call with nothing behind it. Pairing the calls against the snapshots
   * separates "this trail never asked for a snapshot" — which has nothing to compare and is fine —
   * from "it asked and the capture didn't happen", which a baseline gate must not pass: the screen
   * the caller asked to be checked was never checked.
   */
  internal fun uncapturedSnapshotNames(logs: List<TrailblazeLog>): List<String> {
    val unclaimed = logs.filterIsInstance<TrailblazeLog.TrailblazeSnapshotLog>()
      .groupingBy { it.displayName ?: SnapshotBaselineSource.UNNAMED_SNAPSHOT_KEY }
      .eachCount()
      .toMutableMap()
    return logs.filterIsInstance<TrailblazeLog.TrailblazeToolLog>()
      .filter { it.toolName == TAKE_SNAPSHOT_TOOL_NAME }
      .map { toolLog ->
        (toolLog.trailblazeTool.raw["screenName"] as? JsonPrimitive)?.contentOrNull
          ?: SnapshotBaselineSource.UNNAMED_SNAPSHOT_KEY
      }
      .filter { name ->
        val remaining = unclaimed.getOrDefault(name, 0)
        if (remaining > 0) {
          unclaimed[name] = remaining - 1
          false
        } else {
          true
        }
      }
  }

  /** Wraps a [BufferedImage] as a differ [Image] for [SimpleImageComparator]. */
  internal fun asDifferImage(image: BufferedImage): Image = BufferedImageWrapper(image)

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
    /**
     * What the run was compared against: "golden" for checked-in `*.golden.png` files,
     * "baseline" for a previous run's snapshots (see [SnapshotBaselineSource]).
     */
    val referenceLabel: String = "golden",
  ) {
    val summary: String
      get() {
        val total = results.size
        val pass = results.count { it.passed }
        val noReference = results.count { !it.goldenFound }
        return "${referenceLabel.replaceFirstChar { it.uppercase() }} comparison: $pass/$total passed" +
          (if (noReference > 0) ", $noReference missing ${referenceLabel}s (skipped)" else "")
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

  /**
   * Compares each snapshot in the current session against the SAME-NAMED snapshot from a
   * previously-captured baseline run (see [SnapshotBaselineSource]). This is the goldens-free
   * mode: the reference images come from a prior run's session artifacts (e.g. CI's
   * `latest_success.zip`), not from files checked into the repo.
   *
   * Matching is by snapshot name and occurrence: a trail that snapshots `home-tab` twice
   * compares its first capture to the baseline's first `home-tab`, second to second. A snapshot
   * missing from the baseline is skipped (not a failure) — same policy as a missing golden —
   * so a trail can add new snapshots before any baseline containing them exists.
   */
  fun compareToBaseline(
    sessionId: SessionId,
    sessionDir: File,
    logs: List<TrailblazeLog>,
    baseline: SnapshotBaselineSource.ResolvedBaseline,
    thresholdPercent: Double = 2.0,
  ): GoldenComparisonResult {
    val snapshotLogs = logs
      .filterIsInstance<TrailblazeLog.TrailblazeSnapshotLog>()
      .sortedBy { it.timestamp }

    val uncaptured = uncapturedSnapshotNames(logs)

    if (snapshotLogs.isEmpty() && uncaptured.isEmpty()) {
      Console.log("[Baseline] No snapshots found in session ${sessionId.value}")
      return GoldenComparisonResult(sessionId.value, emptyList(), passed = true, referenceLabel = "baseline")
    }

    val comparator = SimpleImageComparator(maxDistance = MAX_DISTANCE)
    val occurrenceByName = mutableMapOf<String, Int>()
    val results = snapshotLogs.map { snapshot ->
      // Unnamed captures share one bucket on both sides so they pair by occurrence; keying on the
      // screenshot file name would give each an identity that can never recur (see
      // [SnapshotBaselineSource.UNNAMED_SNAPSHOT_KEY]) and skip every one of them.
      val snapshotName = snapshot.displayName ?: SnapshotBaselineSource.UNNAMED_SNAPSHOT_KEY
      val occurrence = occurrenceByName.getOrDefault(snapshotName, 0)
      occurrenceByName[snapshotName] = occurrence + 1

      val baselineFile = baseline.snapshotsByName[snapshotName]?.getOrNull(occurrence)
      val screenshotFile = File(sessionDir, snapshot.screenshotFile)

      if (baselineFile != null && !baselineFile.isFile) {
        // The baseline's own logs named this image, so its absence is a broken artifact — not the
        // "no reference yet" case a skip is for. Skipping here would pass a comparison that never
        // ran against a baseline the caller explicitly asked for.
        Console.log(
          "[Baseline] Baseline declares '$snapshotName' (occurrence ${occurrence + 1}) but its image " +
            "is missing from ${baseline.sourceDescription}",
        )
        SnapshotDiffResult(
          snapshotName = snapshotName,
          goldenPath = baselineFile.absolutePath,
          pixelDifferences = -1,
          totalPixels = 0,
          diffPercent = 100.0,
          goldenFound = true,
          passed = false,
          thresholdPercent = thresholdPercent,
        )
      } else if (baselineFile == null) {
        Console.log(
          "[Baseline] No baseline snapshot named '$snapshotName' (occurrence ${occurrence + 1}) " +
            "in ${baseline.sourceDescription} — skipping",
        )
        SnapshotDiffResult(
          snapshotName = snapshotName,
          goldenPath = "${baseline.sourceDescription}!$snapshotName",
          pixelDifferences = 0,
          totalPixels = 0,
          diffPercent = 0.0,
          goldenFound = false,
          passed = true,
          thresholdPercent = thresholdPercent,
        )
      } else {
        compareSnapshot(
          snapshotName = snapshotName,
          goldenFile = baselineFile,
          screenshotFile = screenshotFile,
          sessionDir = sessionDir,
          comparator = comparator,
          thresholdPercent = thresholdPercent,
          referenceLabel = "Baseline",
        )
      }
    }

    val captureFailures = uncaptured.map { name ->
      Console.log("[Baseline] ❌ '$name': takeSnapshot captured no image, so nothing was compared")
      SnapshotDiffResult(
        snapshotName = name,
        goldenPath = "${baseline.sourceDescription}!$name",
        pixelDifferences = -1,
        totalPixels = 0,
        diffPercent = 100.0,
        // The reference side isn't what's missing here — the run's own capture is. Reporting this
        // as a missing baseline would file it under the skipped tally, which is the pass this
        // check exists to deny.
        goldenFound = true,
        passed = false,
        thresholdPercent = thresholdPercent,
      )
    }

    val allResults = results + captureFailures
    return GoldenComparisonResult(
      sessionId = sessionId.value,
      results = allResults,
      passed = allResults.all { it.passed },
      referenceLabel = "baseline",
    )
  }

  private fun compareSnapshot(
    snapshotName: String,
    goldenFile: File,
    screenshotFile: File,
    sessionDir: File,
    comparator: SimpleImageComparator,
    thresholdPercent: Double,
    referenceLabel: String = "Golden",
  ): SnapshotDiffResult {
    val logTag = "[$referenceLabel]"
    if (!goldenFile.exists()) {
      Console.log("$logTag No $referenceLabel found for '$snapshotName' at ${goldenFile.absolutePath} — skipping")
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
      Console.log("$logTag Screenshot file missing for '$snapshotName': ${screenshotFile.absolutePath}")
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
      val golden = ImageIO.read(goldenFile) ?: error("Could not decode $referenceLabel: ${goldenFile.name}")
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
        "$logTag $status '$snapshotName': %.2f%% diff (%d/%d pixels)".format(
          diffPercent, result.pixelDifferences, result.pixelCount
        )
      )

      val diffImagePath = if (!passed) {
        try {
          val diffFile = writeDiffImage(golden, screenshot, screenshotFile, referenceLabel)
          Console.log("$logTag Diff image saved: ${diffFile.absolutePath}")
          diffFile.absolutePath
        } catch (e: Exception) {
          Console.log("$logTag Could not write diff image: ${e.message}")
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
      Console.log("$logTag Error comparing '$snapshotName': ${e.message}")
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
   * Writes a 3-panel comparison image (reference | Diff | Actual) alongside [screenshotFile].
   * The left panel is labeled with [referenceLabel] ("Golden" or "Baseline").
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
    referenceLabel: String = "Golden",
  ): File = writeDiffImageTo(
    golden = golden,
    actual = actual,
    outFile = File(screenshotFile.parentFile, "${screenshotFile.nameWithoutExtension}.diff.png"),
    referenceLabel = referenceLabel,
  )

  /** Same 3-panel rendering, but to an explicit [outFile] (used by [SessionScreenshotDiff]). */
  internal fun writeDiffImageTo(
    golden: BufferedImage,
    actual: BufferedImage,
    outFile: File,
    referenceLabel: String = "Golden",
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
    g.drawString(referenceLabel, 4, 16)
    g.drawString("Diff", panelW + gap + 4, 16)
    g.drawString("Actual", panelW * 2 + gap * 2 + 4, 16)

    g.dispose()

    outFile.parentFile?.mkdirs()
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
