package xyz.block.trailblaze.host.golden

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.client.temp.OtherTrailblazeTool
import xyz.block.trailblaze.logs.model.SessionId

/**
 * Covers the goldens-free snapshot comparison path: [SnapshotBaselineSource] resolution (dir /
 * zip / schema-skew JSON) and [SnapshotGoldenComparison.compareToBaseline] matching semantics.
 */
class SnapshotBaselineComparisonTest {

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

  /**
   * Writes a baseline snapshot log entry as raw JSON — the shape a previous run's session
   * directory carries — deliberately including a field no current schema declares, to prove
   * resolution survives version skew between the baseline's build and this one.
   */
  private fun writeBaselineSnapshotJson(
    sessionDir: File,
    fileName: String,
    displayName: String?,
    screenshotFile: String,
    timestamp: String,
  ) {
    val displayNameJson = if (displayName == null) "null" else "\"$displayName\""
    File(sessionDir, fileName).writeText(
      """
      {
        "class": "xyz.block.trailblaze.logs.client.TrailblazeLog.TrailblazeSnapshotLog",
        "displayName": $displayNameJson,
        "screenshotFile": "$screenshotFile",
        "timestamp": "$timestamp",
        "fieldFromAFutureSchema": {"nested": [1, 2, 3]}
      }
      """.trimIndent(),
    )
  }

  /** A non-snapshot log entry that resolution must ignore. */
  private fun writeOtherLogJson(sessionDir: File, fileName: String) {
    File(sessionDir, fileName).writeText(
      """
      {
        "class": "xyz.block.trailblaze.logs.client.TrailblazeLog.TrailblazeLlmRequestLog",
        "timestamp": "2026-01-01T00:00:00Z"
      }
      """.trimIndent(),
    )
  }

  private fun currentSnapshotLog(
    snapshotName: String,
    screenshotFileName: String,
    timestamp: String,
  ): TrailblazeLog.TrailblazeSnapshotLog = TrailblazeLog.TrailblazeSnapshotLog(
    displayName = snapshotName,
    screenshotFile = screenshotFileName,
    viewHierarchy = ViewHierarchyTreeNode(resourceId = "root"),
    deviceWidth = 100,
    deviceHeight = 100,
    session = SessionId("current-session"),
    timestamp = Instant.parse(timestamp),
  )

  /** A `takeSnapshot` capture with no display name — the shape that must pair by capture order. */
  private fun unnamedSnapshotLog(
    screenshotFileName: String,
    timestamp: String,
  ): TrailblazeLog.TrailblazeSnapshotLog = TrailblazeLog.TrailblazeSnapshotLog(
    displayName = null,
    screenshotFile = screenshotFileName,
    viewHierarchy = ViewHierarchyTreeNode(resourceId = "root"),
    deviceWidth = 100,
    deviceHeight = 100,
    session = SessionId("current-session"),
    timestamp = Instant.parse(timestamp),
  )

  /**
   * The record of the ASK: a `takeSnapshot` tool call. It lands whether or not the capture behind
   * it produced an image, which is what makes it usable as the denominator.
   */
  private fun takeSnapshotToolLog(
    screenName: String,
    timestamp: String,
  ): TrailblazeLog.TrailblazeToolLog = TrailblazeLog.TrailblazeToolLog(
    trailblazeTool = OtherTrailblazeTool(
      toolName = "takeSnapshot",
      raw = JsonObject(mapOf("screenName" to JsonPrimitive(screenName))),
    ),
    toolName = "takeSnapshot",
    successful = true,
    traceId = null,
    durationMs = 1,
    session = SessionId("current-session"),
    timestamp = Instant.parse(timestamp),
  )

  /** Builds a baseline session dir with one named snapshot backed by [image]. */
  private fun baselineWith(
    name: String,
    image: BufferedImage,
    dirName: String = "baseline",
  ): SnapshotBaselineSource.ResolvedBaseline {
    val dir = tmp.newFolder(dirName)
    writePng(image, File(dir, "baseline-shot.png"))
    writeBaselineSnapshotJson(dir, "log1.json", name, "baseline-shot.png", "2026-01-01T00:00:01Z")
    return SnapshotBaselineSource.resolve(dir.absolutePath, tmp.newFolder("$dirName-work"))
  }

  // ---------------------------------------------------------------------------
  // Resolution
  // ---------------------------------------------------------------------------

  @Test
  fun `resolves a session directory, ignoring non-snapshot logs and surviving unknown fields`() {
    val dir = tmp.newFolder("baseline")
    writePng(solidImage(10, 10, Color.BLUE), File(dir, "shot-a.png"))
    writePng(solidImage(10, 10, Color.RED), File(dir, "shot-b.png"))
    writeBaselineSnapshotJson(dir, "log2.json", "settings-tab", "shot-b.png", "2026-01-01T00:00:02Z")
    writeBaselineSnapshotJson(dir, "log1.json", "home-tab", "shot-a.png", "2026-01-01T00:00:01Z")
    writeOtherLogJson(dir, "log0.json")

    val baseline = SnapshotBaselineSource.resolve(dir.absolutePath, tmp.newFolder("work"))

    assertEquals(setOf("home-tab", "settings-tab"), baseline.snapshotsByName.keys)
    assertEquals("shot-a.png", baseline.snapshotsByName.getValue("home-tab").single().name)
    assertEquals("shot-b.png", baseline.snapshotsByName.getValue("settings-tab").single().name)
  }

  // A screenshot file name embeds the session id and the capture's epoch millis, so it can never
  // recur in a later run. Keying unnamed snapshots on it leaves every one of them unmatched, and
  // the run passes having compared nothing — the silent no-op this feature exists to close.
  @Test
  fun `unnamed baseline snapshots share one key so they pair by capture order`() {
    val dir = tmp.newFolder("baseline")
    writePng(solidImage(10, 10, Color.BLUE), File(dir, "2026_run_a_1700000000001.png"))
    writePng(solidImage(10, 10, Color.RED), File(dir, "2026_run_a_1700000000002.png"))
    writeBaselineSnapshotJson(dir, "log1.json", null, "2026_run_a_1700000000001.png", "2026-01-01T00:00:01Z")
    writeBaselineSnapshotJson(dir, "log2.json", null, "2026_run_a_1700000000002.png", "2026-01-01T00:00:02Z")

    val baseline = SnapshotBaselineSource.resolve(dir.absolutePath, tmp.newFolder("work"))

    assertEquals(setOf("(unnamed snapshot)"), baseline.snapshotsByName.keys)
    assertEquals(
      listOf("2026_run_a_1700000000001.png", "2026_run_a_1700000000002.png"),
      baseline.snapshotsByName.getValue("(unnamed snapshot)").map { it.name },
    )
  }

  // Archive JSON is untrusted input; a `screenshotFile` that walks out of the session must not
  // drive a read of an arbitrary file (nor land in the generated diff artifact).
  @Test
  fun `a screenshotFile pointing outside the session directory is ignored`() {
    val root = tmp.newFolder("root")
    writePng(solidImage(10, 10, Color.BLUE), File(root, "secret.png"))
    val dir = File(root, "baseline").apply { mkdirs() }
    writePng(solidImage(10, 10, Color.BLUE), File(dir, "shot.png"))
    writeBaselineSnapshotJson(dir, "log1.json", "home-tab", "shot.png", "2026-01-01T00:00:01Z")
    writeBaselineSnapshotJson(dir, "log2.json", "escaped", "../secret.png", "2026-01-01T00:00:02Z")

    val baseline = SnapshotBaselineSource.resolve(dir.absolutePath, tmp.newFolder("work"))

    assertEquals(setOf("home-tab"), baseline.snapshotsByName.keys)
  }

  // `listFiles()` order is filesystem-dependent, so picking one of several sessions would compare
  // against an arbitrary run — a pass or fail the caller cannot account for.
  @Test
  fun `an archive holding several sessions is ambiguous, not an arbitrary pick`() {
    val root = tmp.newFolder("multi")
    listOf("session-a", "session-b").forEach { name ->
      val sessionDir = File(root, name).apply { mkdirs() }
      writePng(solidImage(10, 10, Color.BLUE), File(sessionDir, "shot.png"))
      writeBaselineSnapshotJson(sessionDir, "log.json", "home-tab", "shot.png", "2026-01-01T00:00:01Z")
    }

    val failure = assertFailsWith<IllegalStateException> {
      SnapshotBaselineSource.resolve(root.absolutePath, tmp.newFolder("work"))
    }
    assertTrue("session-a" in failure.message.orEmpty(), "names the candidates: ${failure.message}")
  }

  @Test
  fun `resolves a session zip with the CI layout - one top-level session directory`() {
    // Build <sessionId>/{log.json, shot.png} inside a zip, the layout the CI artifact store
    // publishes (zip -r from the logs dir parent).
    val staging = tmp.newFolder("staging")
    val sessionDir = File(staging, "2026_01_01_some_session").apply { mkdirs() }
    writePng(solidImage(10, 10, Color.BLUE), File(sessionDir, "shot.png"))
    writeBaselineSnapshotJson(sessionDir, "log.json", "home-tab", "shot.png", "2026-01-01T00:00:01Z")

    val zipFile = File(tmp.newFolder("zips"), "latest_success.zip")
    ZipOutputStream(zipFile.outputStream()).use { zip ->
      sessionDir.walkTopDown().filter { it.isFile }.forEach { file ->
        zip.putNextEntry(ZipEntry("${sessionDir.name}/${file.name}"))
        file.inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
      }
    }

    val baseline = SnapshotBaselineSource.resolve(zipFile.absolutePath, tmp.newFolder("work"))

    assertEquals(setOf("home-tab"), baseline.snapshotsByName.keys)
    assertTrue(baseline.snapshotsByName.getValue("home-tab").single().isFile)
  }

  @Test
  fun `unresolvable reference throws instead of resolving to nothing`() {
    assertFailsWith<IllegalStateException> {
      SnapshotBaselineSource.resolve(File(tmp.root, "does-not-exist").absolutePath, tmp.newFolder("work"))
    }
    // A directory with no session logs anywhere is equally unresolvable.
    assertFailsWith<IllegalStateException> {
      SnapshotBaselineSource.resolve(tmp.newFolder("empty").absolutePath, tmp.newFolder("work2"))
    }
  }

  // ---------------------------------------------------------------------------
  // Comparison
  // ---------------------------------------------------------------------------

  @Test
  fun `identical snapshot passes and writes no diff file`() {
    val image = solidImage(100, 100, Color.BLUE)
    val baseline = baselineWith("home-tab", image)

    val sessionDir = tmp.newFolder("session")
    writePng(image, File(sessionDir, "current.png"))

    val result = SnapshotGoldenComparison.compareToBaseline(
      sessionId = SessionId("current-session"),
      sessionDir = sessionDir,
      logs = listOf(currentSnapshotLog("home-tab", "current.png", "2026-01-02T00:00:01Z")),
      baseline = baseline,
    )

    assertTrue(result.passed)
    assertEquals("baseline", result.referenceLabel)
    with(result.results.single()) {
      assertTrue(passed)
      assertTrue(goldenFound)
      assertEquals(0, pixelDifferences)
      assertNull(diffImagePath)
    }
    assertFalse(File(sessionDir, "current.diff.png").exists())
  }

  @Test
  fun `mismatched snapshot fails and writes a Baseline-labeled diff PNG`() {
    val baseline = baselineWith("home-tab", solidImage(100, 100, Color.RED))

    val sessionDir = tmp.newFolder("session")
    writePng(solidImage(100, 100, Color.BLUE), File(sessionDir, "current.png"))

    val result = SnapshotGoldenComparison.compareToBaseline(
      sessionId = SessionId("current-session"),
      sessionDir = sessionDir,
      logs = listOf(currentSnapshotLog("home-tab", "current.png", "2026-01-02T00:00:01Z")),
      baseline = baseline,
    )

    assertFalse(result.passed)
    with(result.results.single()) {
      assertFalse(passed)
      assertTrue(goldenFound)
      assertTrue(pixelDifferences > 0)
      assertNotNull(diffImagePath)
    }
    val diffFile = File(sessionDir, "current.diff.png")
    assertTrue(diffFile.exists(), "Expected 3-panel diff PNG in the current session dir")
    // 3 panels wide + label strip, same geometry as the golden-file mode.
    val diffImage = ImageIO.read(diffFile)
    assertEquals(100 * 3 + 4 * 2, diffImage.width)
    assertEquals(100 + 24, diffImage.height)
  }

  @Test
  fun `snapshot missing from the baseline is skipped, not failed`() {
    val baseline = baselineWith("home-tab", solidImage(100, 100, Color.BLUE))

    val sessionDir = tmp.newFolder("session")
    writePng(solidImage(100, 100, Color.BLUE), File(sessionDir, "current.png"))

    val result = SnapshotGoldenComparison.compareToBaseline(
      sessionId = SessionId("current-session"),
      sessionDir = sessionDir,
      logs = listOf(currentSnapshotLog("brand-new-screen", "current.png", "2026-01-02T00:00:01Z")),
      baseline = baseline,
    )

    assertTrue(result.passed)
    with(result.results.single()) {
      assertTrue(passed)
      assertFalse(goldenFound)
      assertNull(diffImagePath)
    }
  }

  @Test
  fun `repeated snapshot names match by occurrence`() {
    // Baseline snapshots "home-tab" twice: first BLUE, then RED.
    val dir = tmp.newFolder("baseline")
    writePng(solidImage(100, 100, Color.BLUE), File(dir, "first.png"))
    writePng(solidImage(100, 100, Color.RED), File(dir, "second.png"))
    writeBaselineSnapshotJson(dir, "log1.json", "home-tab", "first.png", "2026-01-01T00:00:01Z")
    writeBaselineSnapshotJson(dir, "log2.json", "home-tab", "second.png", "2026-01-01T00:00:02Z")
    val baseline = SnapshotBaselineSource.resolve(dir.absolutePath, tmp.newFolder("work"))

    // Current run snapshots "home-tab" twice: BLUE then BLUE — the second must be compared to
    // the baseline's second occurrence (RED) and fail, proving order-aware matching.
    val sessionDir = tmp.newFolder("session")
    writePng(solidImage(100, 100, Color.BLUE), File(sessionDir, "cur1.png"))
    writePng(solidImage(100, 100, Color.BLUE), File(sessionDir, "cur2.png"))

    val result = SnapshotGoldenComparison.compareToBaseline(
      sessionId = SessionId("current-session"),
      sessionDir = sessionDir,
      logs = listOf(
        currentSnapshotLog("home-tab", "cur1.png", "2026-01-02T00:00:01Z"),
        currentSnapshotLog("home-tab", "cur2.png", "2026-01-02T00:00:02Z"),
      ),
      baseline = baseline,
    )

    assertFalse(result.passed)
    assertEquals(2, result.results.size)
    assertTrue(result.results[0].passed, "First occurrence (BLUE vs BLUE) must pass")
    assertFalse(result.results[1].passed, "Second occurrence (BLUE vs RED) must fail")
  }

  @Test
  fun `threshold controls pass-fail for small diffs`() {
    // 4 of 100x100 pixels differ = 0.04% diff.
    val baselineImage = solidImage(100, 100, Color.BLUE)
    val currentImage = solidImage(100, 100, Color.BLUE).also { img ->
      for (x in 0 until 2) for (y in 0 until 2) img.setRGB(x, y, Color.RED.rgb)
    }
    val baseline = baselineWith("home-tab", baselineImage)

    val sessionDir = tmp.newFolder("session")
    writePng(currentImage, File(sessionDir, "current.png"))
    val logs = listOf(currentSnapshotLog("home-tab", "current.png", "2026-01-02T00:00:01Z"))

    val lenient = SnapshotGoldenComparison.compareToBaseline(
      sessionId = SessionId("current-session"),
      sessionDir = sessionDir,
      logs = logs,
      baseline = baseline,
      thresholdPercent = 1.0,
    )
    assertTrue(lenient.passed, "0.04% diff must pass a 1% threshold")

    val strict = SnapshotGoldenComparison.compareToBaseline(
      sessionId = SessionId("current-session"),
      sessionDir = sessionDir,
      logs = logs,
      baseline = baseline,
      thresholdPercent = 0.0,
    )
    assertFalse(strict.passed, "0.04% diff must fail a 0% threshold")
  }

  // The baseline's own logs named this image, so its absence is a broken artifact — not the "no
  // reference yet" case a skip is for. Skipping would pass a comparison that never ran.
  @Test
  fun `a baseline that declares an image it does not carry fails instead of skipping`() {
    val dir = tmp.newFolder("baseline")
    writeBaselineSnapshotJson(dir, "log1.json", "home-tab", "missing-shot.png", "2026-01-01T00:00:01Z")
    // A second, present snapshot keeps the session resolvable.
    writePng(solidImage(10, 10, Color.BLUE), File(dir, "shot.png"))
    writeBaselineSnapshotJson(dir, "log2.json", "settings-tab", "shot.png", "2026-01-01T00:00:02Z")
    val baseline = SnapshotBaselineSource.resolve(dir.absolutePath, tmp.newFolder("work"))

    val sessionDir = tmp.newFolder("session")
    writePng(solidImage(10, 10, Color.BLUE), File(sessionDir, "current.png"))

    val result = SnapshotGoldenComparison.compareToBaseline(
      sessionId = SessionId("current-session"),
      sessionDir = sessionDir,
      logs = listOf(currentSnapshotLog("home-tab", "current.png", "2026-01-02T00:00:01Z")),
      baseline = baseline,
    )

    assertFalse(result.passed, "a declared-but-absent baseline image must not pass")
    assertTrue(result.results.single().goldenFound, "the baseline named it, so it is not a missing reference")
  }

  // `takeSnapshot` returns Success even when the driver hands it no image, so a run whose captures
  // all failed reaches the gate with an empty snapshot list and used to pass having compared
  // nothing — the exact silent green this feature exists to prevent.
  @Test
  fun `a takeSnapshot call that captured no image fails the baseline gate`() {
    val baseline = baselineWith("home-tab", solidImage(100, 100, Color.BLUE))

    val result = SnapshotGoldenComparison.compareToBaseline(
      sessionId = SessionId("current-session"),
      sessionDir = tmp.newFolder("session"),
      logs = listOf(takeSnapshotToolLog("home-tab", "2026-01-02T00:00:01Z")),
      baseline = baseline,
    )

    assertFalse(result.passed, "an uncaptured screen must not pass a gate that was asked to check it")
    with(result.results.single()) {
      assertFalse(passed)
      assertEquals("home-tab", snapshotName)
      assertTrue(goldenFound, "the reference exists; it is the run's own capture that is missing")
    }
  }

  // Only the calls with nothing behind them count. A run where every capture landed must not be
  // failed by its own tool logs, and a trail that never asks for a snapshot has nothing to fail on.
  @Test
  fun `captured snapshots claim their tool calls, so only unmatched calls fail`() {
    val baseline = baselineWith("home-tab", solidImage(100, 100, Color.BLUE))

    val sessionDir = tmp.newFolder("session")
    writePng(solidImage(100, 100, Color.BLUE), File(sessionDir, "current.png"))

    val allCaptured = SnapshotGoldenComparison.compareToBaseline(
      sessionId = SessionId("current-session"),
      sessionDir = sessionDir,
      logs = listOf(
        takeSnapshotToolLog("home-tab", "2026-01-02T00:00:01Z"),
        currentSnapshotLog("home-tab", "current.png", "2026-01-02T00:00:01Z"),
      ),
      baseline = baseline,
    )
    assertTrue(allCaptured.passed, "a capture that landed must not be reported as missing")
    assertEquals(1, allCaptured.results.size)

    val neverAsked = SnapshotGoldenComparison.compareToBaseline(
      sessionId = SessionId("current-session"),
      sessionDir = sessionDir,
      logs = emptyList(),
      baseline = baseline,
    )
    assertTrue(neverAsked.passed, "a trail with no snapshots has nothing to compare")
    assertTrue(neverAsked.results.isEmpty())
  }

  // Two calls for the same screen, one capture: the second ask is the one left unclaimed. Counting
  // distinct names instead of occurrences would call this fully captured.
  @Test
  fun `a repeated screen name pairs one call per capture`() {
    val baseline = baselineWith("home-tab", solidImage(100, 100, Color.BLUE))

    val sessionDir = tmp.newFolder("session")
    writePng(solidImage(100, 100, Color.BLUE), File(sessionDir, "current.png"))

    val result = SnapshotGoldenComparison.compareToBaseline(
      sessionId = SessionId("current-session"),
      sessionDir = sessionDir,
      logs = listOf(
        takeSnapshotToolLog("home-tab", "2026-01-02T00:00:01Z"),
        currentSnapshotLog("home-tab", "current.png", "2026-01-02T00:00:01Z"),
        takeSnapshotToolLog("home-tab", "2026-01-02T00:00:02Z"),
      ),
      baseline = baseline,
    )

    assertFalse(result.passed)
    assertEquals(2, result.results.size, "one compared capture plus one uncaptured ask")
    assertTrue(result.results[0].passed)
    assertFalse(result.results[1].passed)
  }

  // Unnamed captures must pair on BOTH sides, or the current run's unnamed snapshots find nothing.
  @Test
  fun `unnamed current snapshots compare against the baseline's unnamed captures in order`() {
    val dir = tmp.newFolder("baseline")
    writePng(solidImage(100, 100, Color.BLUE), File(dir, "2026_run_a_1700000000001.png"))
    writePng(solidImage(100, 100, Color.RED), File(dir, "2026_run_a_1700000000002.png"))
    writeBaselineSnapshotJson(dir, "log1.json", null, "2026_run_a_1700000000001.png", "2026-01-01T00:00:01Z")
    writeBaselineSnapshotJson(dir, "log2.json", null, "2026_run_a_1700000000002.png", "2026-01-01T00:00:02Z")
    val baseline = SnapshotBaselineSource.resolve(dir.absolutePath, tmp.newFolder("work"))

    val sessionDir = tmp.newFolder("session")
    writePng(solidImage(100, 100, Color.BLUE), File(sessionDir, "2026_run_b_1800000000001.png"))
    writePng(solidImage(100, 100, Color.GREEN), File(sessionDir, "2026_run_b_1800000000002.png"))

    val result = SnapshotGoldenComparison.compareToBaseline(
      sessionId = SessionId("current-session"),
      sessionDir = sessionDir,
      logs = listOf(
        unnamedSnapshotLog("2026_run_b_1800000000001.png", "2026-01-02T00:00:01Z"),
        unnamedSnapshotLog("2026_run_b_1800000000002.png", "2026-01-02T00:00:02Z"),
      ),
      baseline = baseline,
    )

    assertTrue(result.results.all { it.goldenFound }, "both unnamed captures must find their baseline")
    assertTrue(result.results[0].passed, "blue vs blue")
    assertFalse(result.results[1].passed, "green vs red is a real difference")
  }
}
