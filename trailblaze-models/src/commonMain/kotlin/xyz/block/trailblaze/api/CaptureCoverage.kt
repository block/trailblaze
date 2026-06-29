package xyz.block.trailblaze.api

import kotlinx.serialization.Serializable

/**
 * How completely the captured accessibility (or analogous) hierarchy covers the screen at one
 * capture. Populated on Android captures by the on-device gate that watches for partial /
 * truncated trees; null on every other driver (the gate is Android-only).
 *
 * Intentionally written for programmatic consumers (dashboards, LLMs, CI graders) — the same
 * signal also shows up as `[capture-coverage]` log lines on Android, but parsing those out of
 * logcat is brittle. Carrying it as structured data alongside the screenshot + view hierarchy
 * lets consumers compute things like "3 of 30 captures looked truncated this session" without
 * touching the logs.
 *
 * [looksTruncated] is a heuristic — it flags a one-sided horizontal slice (content jammed
 * against one screen edge) or a high zero-bounds ratio (tree mid-commit). Treat it as "could be"
 * / "often indicates", not a hard verdict: some apps with intentionally sparse a11y will report
 * it legitimately, and the field is most useful as a trend signal across a run rather than a
 * single-capture alert.
 */
@Serializable
data class CaptureCoverage(
  /** Content-bearing nodes considered (visible, positioned, on-screen). */
  val contentNodes: Int,
  /** Content-bearing nodes that reported a zero-area / `(0,0,0,0)` box. */
  val zeroBoundsContentNodes: Int,
  /** Fraction of screen width spanned by the union of content-node boxes, in `[0,1]`. */
  val horizontalCoverage: Double,
  /** Fraction of screen height spanned by the union of content-node boxes, in `[0,1]`. */
  val verticalCoverage: Double,
  /** True when the captured content looks like a partial slice rather than a full screen. */
  val looksTruncated: Boolean,
  /** Human-readable explanation, suitable for a `[capture-coverage]` log line. */
  val reason: String,
)
