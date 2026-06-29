package xyz.block.trailblaze.report.models

import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.datetime.Instant
import org.junit.Test
import xyz.block.trailblaze.api.AgentDriverAction
import xyz.block.trailblaze.api.CaptureCoverage
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.SessionId

/**
 * Pins down [AccessibilityTruncationSummary.fromLogs] — the report-level roll-up of the Android
 * on-device "truncated accessibility tree" signal. The detector itself is tested separately in
 * [xyz.block.trailblaze.android.accessibility.HierarchyCoverageAssessorTest]; this file covers
 * the aggregation logic the report generator runs over a session's log entries.
 */
class AccessibilityTruncationSummaryTest {

  private val sessionId = SessionId("2026_06_26_a11y_test_session")
  private val t0 = Instant.parse("2026-06-26T12:00:00Z")

  /** A `looksTruncated = true` coverage with sensible numbers; verbatim from the gate's logger. */
  private fun truncated(reason: String = "content spans 17% of width, jammed against the right edge (left 82% empty) across 6 node(s)") =
    CaptureCoverage(
      contentNodes = 8,
      zeroBoundsContentNodes = 0,
      horizontalCoverage = 0.17,
      verticalCoverage = 0.92,
      looksTruncated = true,
      reason = reason,
    )

  private fun complete() = CaptureCoverage(
    contentNodes = 12,
    zeroBoundsContentNodes = 0,
    horizontalCoverage = 0.94,
    verticalCoverage = 0.88,
    looksTruncated = false,
    reason = "content spans 94% of width / 88% of height — looks complete",
  )

  private fun driverLog(
    coverage: CaptureCoverage?,
    timestamp: Instant = t0,
  ) = TrailblazeLog.AgentDriverLog(
    viewHierarchy = ViewHierarchyTreeNode(),
    screenshotFile = null,
    action = AgentDriverAction.TapPoint(x = 10, y = 20),
    captureCoverage = coverage,
    durationMs = 0,
    session = sessionId,
    timestamp = timestamp,
    deviceHeight = 2400,
    deviceWidth = 1080,
  )

  private fun snapshotLog(
    coverage: CaptureCoverage?,
    timestamp: Instant = t0,
  ) = TrailblazeLog.TrailblazeSnapshotLog(
    displayName = null,
    screenshotFile = "snap.png",
    viewHierarchy = ViewHierarchyTreeNode(),
    captureCoverage = coverage,
    deviceWidth = 1080,
    deviceHeight = 2400,
    session = sessionId,
    timestamp = timestamp,
  )

  @Test
  fun `returns null when no logs carry a coverage assessment`() {
    // No coverage anywhere — non-Android session or a pre-field run. Returning null beats
    // emitting "0 of 0" which a downstream dashboard would awkwardly turn into NaN.
    val logs = listOf(driverLog(coverage = null), snapshotLog(coverage = null))
    assertNull(AccessibilityTruncationSummary.fromLogs(logs))
  }

  @Test
  fun `counts truncated and total across both log types`() {
    val logs = listOf(
      driverLog(coverage = complete()),
      driverLog(coverage = truncated()),
      snapshotLog(coverage = complete()),
      snapshotLog(coverage = truncated()),
      driverLog(coverage = null), // not counted — no assessment
    )
    val summary = AccessibilityTruncationSummary.fromLogs(logs)
    assertNotNull(summary)
    assertEquals(4, summary.captures_total)
    assertEquals(2, summary.captures_truncated)
  }

  @Test
  fun `caps examples at the configured limit`() {
    val logs = (1..AccessibilityTruncationSummary.EXAMPLE_LIMIT + 3).map { i ->
      driverLog(
        coverage = truncated(reason = "truncated capture $i"),
        timestamp = t0.plus(kotlin.time.Duration.parse("${i}s")),
      )
    }
    val summary = AccessibilityTruncationSummary.fromLogs(logs)
    assertNotNull(summary)
    assertEquals(AccessibilityTruncationSummary.EXAMPLE_LIMIT + 3, summary.captures_total)
    assertEquals(AccessibilityTruncationSummary.EXAMPLE_LIMIT + 3, summary.captures_truncated)
    // The cap is the safety net for long-tail sessions where everything is flagged — without it
    // the JSON would carry one example per capture and bloat to MBs.
    assertEquals(AccessibilityTruncationSummary.EXAMPLE_LIMIT, summary.examples.size)
    // Examples land in session-log order — the earliest flagged captures are most diagnostic
    // (the rest tend to be more of the same).
    assertEquals("truncated capture 1", summary.examples.first().reason)
  }

  @Test
  fun `examples carry detector verdict fields verbatim`() {
    val t1 = t0.plus(kotlin.time.Duration.parse("1s"))
    val logs = listOf(driverLog(coverage = truncated(), timestamp = t1))
    val summary = AccessibilityTruncationSummary.fromLogs(logs)
    assertNotNull(summary)
    val example = summary.examples.single()
    assertEquals(t1.toEpochMilliseconds(), example.timestamp_ms)
    assertEquals(
      "content spans 17% of width, jammed against the right edge (left 82% empty) across 6 node(s)",
      example.reason,
    )
    assertEquals(0.17, example.horizontal_coverage, 0.0001)
    assertEquals(0.92, example.vertical_coverage, 0.0001)
  }

  @Test
  fun `complete-only session reports zero truncated`() {
    val logs = listOf(driverLog(coverage = complete()), driverLog(coverage = complete()))
    val summary = AccessibilityTruncationSummary.fromLogs(logs)
    assertNotNull(summary)
    assertEquals(2, summary.captures_total)
    assertEquals(0, summary.captures_truncated)
    assertEquals(emptyList<AccessibilityTruncationSummary.TruncationExample>(), summary.examples)
  }
}
