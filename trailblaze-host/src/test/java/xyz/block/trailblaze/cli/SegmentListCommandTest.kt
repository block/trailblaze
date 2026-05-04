package xyz.block.trailblaze.cli

import kotlin.test.Test
import kotlin.test.assertTrue
import xyz.block.trailblaze.segment.SessionSegmentExtractor
import xyz.block.trailblaze.segment.TrailSegment

/**
 * Tests for [noSegmentsHint] — the four-branch user-facing diagnostic the CLI prints
 * when a session produces no segments. The branches map to the four root causes:
 *  1. No `TrailblazeLlmRequestLog` files at all (wrong directory).
 *  2. Request logs exist but none carry a `trailblazeNodeTree` (legacy session shape).
 *  3. Fewer than two steps matched a waypoint (no transition possible).
 *  4. All matched steps landed on the same waypoint (no boundary).
 *
 * The hint is the user's signal for "what to look at next" — pinned here so a future
 * refactor doesn't silently regress the diagnostic chain. The conditional `parseFailures`
 * and `stepsWithAmbiguousMatch` print branches in `SegmentListCommand.call()` are simple
 * `if (n > 0) print(line)` guards and aren't worth a CLI-output-capture harness; they're
 * pinned by code review.
 */
class SegmentListCommandTest {

  private val sessionPath = "/tmp/test-session"

  @Test
  fun `branch 1 - no request logs prints the missing-LlmRequestLog hint`() {
    val analysis = analysis(totalRequestLogs = 0)
    val hint = noSegmentsHint(analysis, sessionPath)
    assertTrue(
      hint.contains("no TrailblazeLlmRequestLog files"),
      "expected branch 1 hint, got: $hint",
    )
  }

  @Test
  fun `branch 2 - no node trees prints the legacy-session hint`() {
    // Request logs exist but none had a trailblazeNodeTree — older session shape.
    val analysis = analysis(
      totalRequestLogs = 5,
      stepsWithNodeTree = 0,
    )
    val hint = noSegmentsHint(analysis, sessionPath)
    assertTrue(
      hint.contains("predates the multi-agent"),
      "expected branch 2 hint, got: $hint",
    )
  }

  @Test
  fun `branch 3 - fewer than 2 matched waypoints prints the per-step debug hint with absolute path`() {
    val analysis = analysis(
      totalRequestLogs = 5,
      stepsWithNodeTree = 5,
      stepsWithMatchedWaypoint = 1,
    )
    val hint = noSegmentsHint(analysis, sessionPath)
    assertTrue(hint.contains("<2 steps matched"), "expected branch 3 hint, got: $hint")
    // The hint must contain the absolute session path so the suggested
    // `trailblaze waypoint locate` command resolves regardless of cwd.
    assertTrue(
      hint.contains(sessionPath),
      "branch 3 hint must include the session absolute path, got: $hint",
    )
  }

  @Test
  fun `branch 4 - all matched on same waypoint prints the no-transitions hint`() {
    // 3 matched steps but all on the same waypoint — no boundary between distinct ones.
    val analysis = analysis(
      totalRequestLogs = 5,
      stepsWithNodeTree = 5,
      stepsWithMatchedWaypoint = 3,
    )
    val hint = noSegmentsHint(analysis, sessionPath)
    assertTrue(
      hint.contains("every matched step landed on the same waypoint"),
      "expected branch 4 hint, got: $hint",
    )
  }

  @Test
  fun `branch precedence - branch 1 wins over branch 2 when both apply`() {
    // Defensive: if totalRequestLogs == 0 then stepsWithNodeTree must also be 0, but the
    // hint chain should pick the more-specific "no request logs" message rather than the
    // legacy-session one. Pinning the order so a future refactor doesn't accidentally
    // shuffle the `when` branches.
    val analysis = analysis(totalRequestLogs = 0, stepsWithNodeTree = 0)
    val hint = noSegmentsHint(analysis, sessionPath)
    assertTrue(
      hint.contains("no TrailblazeLlmRequestLog files"),
      "branch 1 should win over branch 2, got: $hint",
    )
  }

  // ---- Fixture helper ----

  private fun analysis(
    totalRequestLogs: Int = 0,
    stepsWithNodeTree: Int = 0,
    stepsWithMatchedWaypoint: Int = 0,
    stepsWithAmbiguousMatch: Int = 0,
    parseFailures: Int = 0,
    segments: List<TrailSegment> = emptyList(),
  ): SessionSegmentExtractor.Analysis = SessionSegmentExtractor.Analysis(
    totalRequestLogs = totalRequestLogs,
    stepsWithNodeTree = stepsWithNodeTree,
    stepsWithMatchedWaypoint = stepsWithMatchedWaypoint,
    stepsWithAmbiguousMatch = stepsWithAmbiguousMatch,
    parseFailures = parseFailures,
    segments = segments,
  )
}
