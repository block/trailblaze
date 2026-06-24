package xyz.block.trailblaze.android.accessibility

/**
 * Detects the "truncated / partial accessibility tree" failure mode: the screenshot shows a full
 * screen of content, but the captured accessibility hierarchy only contains a thin slice of it
 * (e.g. just the right-edge cluster), so selector taps and asserts report "element not found" for
 * things that are plainly visible.
 *
 * This is the on-device analog of the instrumentation "main screen = one node" collapse, but on the
 * accessibility path it shows up on heavily-Compose apps: Compose exposes its whole UI to the
 * accessibility framework through a single `AndroidComposeView` whose semantics tree is rebuilt
 * **asynchronously** on recomposition/relayout. Capture can land in the window where only a subset
 * of nodes have finished layout and report real bounds (often one edge), while the rest report
 * `(0,0,0,0)`. On a now-static screen no further accessibility event fires, so re-reading the
 * platform cache returns the same partial tree for the whole step — which is why a long
 * assert-visible poll keeps failing even though the screenshot is correct.
 *
 * The assessment is intentionally based on **content-bearing** nodes (those carrying `text` or
 * `contentDescription`) rather than every node. A full-screen Compose root container reports
 * full-screen bounds and would mask the truncation if we unioned container bounds; the matchable
 * content — the elements selectors actually target — is what clusters into a slice when the tree is
 * partial.
 *
 * Operates on plain [NodeBounds], not live `AccessibilityNodeInfo`, so the gate and the tests share
 * one detector.
 */
internal object HierarchyCoverageAssessor {

  /**
   * Flat, framework-free description of one captured node, enough to judge coverage. [hasText] is
   * true when the node carries non-blank `text` or `contentDescription` (i.e. it's matchable
   * content, not a bare layout container).
   */
  data class NodeBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val isVisibleToUser: Boolean,
    val hasText: Boolean,
  ) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val hasArea: Boolean get() = width > 0 && height > 0
  }

  data class Assessment(
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

  /**
   * Minimum content-bearing nodes before we'll judge a tree truncated. Splash/loading/empty screens
   * legitimately have one or two content nodes squished anywhere; refusing to judge below this floor
   * keeps the gate from holding on a genuinely sparse screen.
   */
  private const val MIN_CONTENT_NODES = 4

  /**
   * The union of content boxes must span less than this fraction of screen width (or height) to be a
   * truncation candidate. A full screen is ~1.0; a centered dialog is ~0.5 but symmetric (caught by
   * the edge-gap requirement below), a one-edge slice is well under.
   */
  private const val COVERAGE_MIN_FRACTION = 0.5

  /**
   * ...AND a contiguous empty band of at least this fraction must sit against one screen edge. This
   * is what distinguishes a one-sided slice ("everything is on the right, left 80% empty") from a
   * centered dialog (gaps split symmetrically, neither band large). Directly models the reported
   * `x=864..1080 on a 1080px screen` symptom: left gap ≈ 0.80.
   */
  private const val EDGE_GAP_MIN_FRACTION = 0.34

  /**
   * ...AND the content must be pushed flush against the *opposite* edge — the gap on the side the
   * content sits on is at most this fraction. This is the difference between a genuine slice
   * (content jammed to the right edge, left empty) and merely narrow off-center content (gaps on
   * both sides). The reported slice touches the right edge (right gap ≈ 0); narrow centered content
   * has a non-trivial gap on both sides and is left alone.
   *
   * Only the horizontal axis is judged: the reported failure is horizontal, and a vertical "slice"
   * is indistinguishable from legitimately top-anchored or short content (a search screen with just
   * a bar at the top), so gating on it would false-positive. Mid-commit trees that happen to be
   * vertical are still caught by [ZERO_BOUNDS_RATIO_MAX].
   */
  private const val NEAR_EDGE_MAX_FRACTION = 0.06

  /**
   * Secondary signal: if this fraction or more of content-bearing nodes report a zero-area box, the
   * tree is mid-commit regardless of where the positioned ones landed ("duplicate zero-bounds
   * nodes"). High threshold so a few legitimately-unpositioned nodes don't trip it.
   */
  private const val ZERO_BOUNDS_RATIO_MAX = 0.6

  fun assess(nodes: List<NodeBounds>, screenWidth: Int, screenHeight: Int): Assessment {
    if (screenWidth <= 0 || screenHeight <= 0) {
      return Assessment(0, 0, 0.0, 0.0, false, "unknown screen dimensions; skipping coverage check")
    }

    val content = nodes.filter { it.isVisibleToUser && it.hasText }
    val positioned = content.filter { it.hasArea }
    val zeroBounds = content.size - positioned.size

    if (content.size < MIN_CONTENT_NODES) {
      return Assessment(
        contentNodes = content.size,
        zeroBoundsContentNodes = zeroBounds,
        horizontalCoverage = 0.0,
        verticalCoverage = 0.0,
        looksTruncated = false,
        reason = "only ${content.size} content node(s) — too few to judge coverage",
      )
    }

    // High zero-bounds ratio is itself a "mid-commit" tell even if the positioned nodes spread out.
    val zeroRatio = zeroBounds.toDouble() / content.size
    if (zeroRatio >= ZERO_BOUNDS_RATIO_MAX) {
      return Assessment(
        contentNodes = content.size,
        zeroBoundsContentNodes = zeroBounds,
        horizontalCoverage = 0.0,
        verticalCoverage = 0.0,
        looksTruncated = true,
        reason = "${(zeroRatio * 100).toInt()}% of ${content.size} content nodes report zero " +
          "bounds (tree mid-commit)",
      )
    }

    if (positioned.isEmpty()) {
      return Assessment(content.size, zeroBounds, 0.0, 0.0, false, "no positioned content nodes")
    }

    // Union of positioned content boxes, clamped to the screen.
    val unionLeft = positioned.minOf { it.left }.coerceIn(0, screenWidth)
    val unionRight = positioned.maxOf { it.right }.coerceIn(0, screenWidth)
    val unionTop = positioned.minOf { it.top }.coerceIn(0, screenHeight)
    val unionBottom = positioned.maxOf { it.bottom }.coerceIn(0, screenHeight)

    val hCoverage = (unionRight - unionLeft).toDouble() / screenWidth
    val vCoverage = (unionBottom - unionTop).toDouble() / screenHeight

    val leftGap = unionLeft.toDouble() / screenWidth
    val rightGap = (screenWidth - unionRight).toDouble() / screenWidth

    // Only a RIGHT-edge slice is flagged: content jammed flush against the right edge with a large
    // empty band on the LEFT. We deliberately do NOT flag the mirror (content against the left
    // edge) because a left-aligned column of labels / form rows / list items — whose Compose `Text`
    // semantics bounds wrap the text rather than the full row — is the single most common normal
    // screen, and flagging it would make awaitTreeStable spin to the 1s cap on every ordinary
    // snapshot. Right-only-content with a wide empty left band is genuinely anomalous (it's the
    // reported `x=864..1080` symptom — e.g. a chat where only the right-aligned messages survived
    // a partial capture); a fully-rendered screen has left/center content too, so its coverage is
    // wide and it isn't flagged. Vertical slices stay ungated for the same false-positive reason;
    // mid-commit trees of any orientation are still caught by the zero-bounds-ratio signal above.
    val rightEdgeSlice = hCoverage < COVERAGE_MIN_FRACTION &&
      leftGap >= EDGE_GAP_MIN_FRACTION &&
      rightGap <= NEAR_EDGE_MAX_FRACTION

    val reason = if (rightEdgeSlice) {
      "content spans ${pct(hCoverage)} of width, jammed against the right edge " +
        "(left ${pct(leftGap)} empty) across ${positioned.size} node(s)"
    } else {
      "content spans ${pct(hCoverage)} of width / ${pct(vCoverage)} of height — looks complete"
    }

    return Assessment(
      contentNodes = content.size,
      zeroBoundsContentNodes = zeroBounds,
      horizontalCoverage = hCoverage,
      verticalCoverage = vCoverage,
      looksTruncated = rightEdgeSlice,
      reason = reason,
    )
  }

  private fun pct(fraction: Double): String = "${(fraction * 100).toInt()}%"
}
