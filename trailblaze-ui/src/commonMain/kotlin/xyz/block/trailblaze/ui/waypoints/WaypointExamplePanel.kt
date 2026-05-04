package xyz.block.trailblaze.ui.waypoints

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.TrailblazeNodeSelectorResolver
import xyz.block.trailblaze.api.TrailblazeNodeSelectorResolver.ResolveResult
import xyz.block.trailblaze.api.waypoint.WaypointSelectorEntry

/**
 * Resolved overlay record for a single selector entry. Mirrors a `WaypointSelectorEntry`
 * after running it through [TrailblazeNodeSelectorResolver] against the example tree.
 *
 * - [REQUIRED] entries draw green when at least [minCount] nodes match **and** at least
 *   one of those matches carries a drawable [TrailblazeNode.Bounds]. A match against a
 *   bounds-less node (e.g. a structural Compose semantic with no positional rect) is not
 *   considered satisfied — the visualizer needs something to render so the user can
 *   confirm the match. See [matchStatusBadgeStyle] for the dedicated "MATCH (no bounds)"
 *   label that surfaces this case distinctly from a true NO MATCH.
 * - [FORBIDDEN] entries draw red when **any** node matches (the matcher's "fail on first
 *   forbidden hit" semantics) and don't render at all when no nodes match.
 *
 * [matchedCount] is the raw count from the resolver; [matchedBounds] is the drawable
 * subset (matched nodes whose [TrailblazeNode.bounds] is non-null). The two can diverge
 * when a selector matches structurally but the matched nodes carry no positional rect.
 */
internal data class WaypointSelectorOverlay(
  val kind: WaypointSelectorKind,
  val entryIndex: Int,
  val description: String,
  val minCount: Int,
  val matchedBounds: List<TrailblazeNode.Bounds>,
  val matchedCount: Int,
)

internal enum class WaypointSelectorKind { REQUIRED, FORBIDDEN }

internal fun WaypointSelectorOverlay.isSatisfied(): Boolean = when (kind) {
  // Bounds-less matches don't count as satisfied for REQUIRED — see kdoc on
  // WaypointSelectorOverlay. The badge surfaces this case as "MATCH (no bounds)" via
  // `matchStatusBadgeStyle` so the user can tell it apart from a real NO MATCH.
  WaypointSelectorKind.REQUIRED -> matchedCount >= minCount && matchedBounds.isNotEmpty()
  WaypointSelectorKind.FORBIDDEN -> matchedCount == 0
}

internal fun resolveOverlays(
  tree: TrailblazeNode,
  required: List<WaypointSelectorEntry>,
  forbidden: List<WaypointSelectorEntry>,
): List<WaypointSelectorOverlay> = buildList {
  required.forEachIndexed { index, entry ->
    add(buildOverlay(tree, entry, WaypointSelectorKind.REQUIRED, index))
  }
  forbidden.forEachIndexed { index, entry ->
    add(buildOverlay(tree, entry, WaypointSelectorKind.FORBIDDEN, index))
  }
}

private fun buildOverlay(
  tree: TrailblazeNode,
  entry: WaypointSelectorEntry,
  kind: WaypointSelectorKind,
  entryIndex: Int,
): WaypointSelectorOverlay {
  val matched = when (val r = TrailblazeNodeSelectorResolver.resolve(tree, entry.selector)) {
    is ResolveResult.SingleMatch -> listOf(r.node)
    is ResolveResult.MultipleMatches -> r.nodes
    is ResolveResult.NoMatch -> emptyList()
  }
  val bounds = matched.mapNotNull { it.bounds }
  return WaypointSelectorOverlay(
    kind = kind,
    entryIndex = entryIndex,
    description = entry.description?.takeIf { it.isNotBlank() }
      ?: entry.selector.description().ifBlank { "(selector ${entryIndex + 1})" },
    minCount = entry.minCount,
    matchedBounds = bounds,
    matchedCount = matched.size,
  )
}

/**
 * Renders the example screenshot with selector-match overlays. The bounding boxes are
 * drawn in screen coordinates of the captured device, scaled to the rendered image's
 * actual size — same approach the existing `TrailblazeNodeInspector` uses for live trees.
 *
 * Color mapping:
 *   - green ring  → required selector with matchCount ≥ minCount
 *   - red ring    → required selector that's missing (matchCount < minCount), or
 *                   forbidden selector that's present
 *   - yellow ring → required selector matched > minCount times (ambiguous)
 *
 * [highlightedOverlay] thickens the corresponding ring + drops the alpha on the others
 * so a single selector can be inspected without losing the full picture.
 */
@Composable
internal fun WaypointExamplePanel(
  example: WaypointExample,
  overlays: List<WaypointSelectorOverlay>,
  modifier: Modifier = Modifier,
  highlightedOverlay: WaypointSelectorOverlay? = null,
) {
  val aspect = remember(example) {
    if (example.deviceHeight > 0) {
      example.deviceWidth.toFloat() / example.deviceHeight.toFloat()
    } else {
      0.5f
    }
  }
  Column(
    modifier = modifier.padding(8.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    OverlayLegend(overlays = overlays)
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .widthIn(max = 360.dp)
        .aspectRatio(aspect)
        .clip(MaterialTheme.shapes.medium),
    ) {
      // Track decode errors so a corrupt webp/png falls through to the same "(no screenshot)"
      // placeholder used when the bytes are missing entirely. Without this, coil3 swallows
      // the decoder exception and the user sees a silently blank box.
      var screenshotDecodeFailed by remember(example) { mutableStateOf(false) }
      val hasBytes = example.screenshotBytes != null && example.screenshotBytes.isNotEmpty()
      if (hasBytes && !screenshotDecodeFailed) {
        AsyncImage(
          model = example.screenshotBytes,
          contentDescription = "Waypoint example screenshot",
          contentScale = ContentScale.Fit,
          modifier = Modifier.fillMaxSize(),
          onError = { _ -> screenshotDecodeFailed = true },
        )
      } else {
        Box(
          modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceVariant),
          contentAlignment = Alignment.Center,
        ) {
          Text(
            text = if (screenshotDecodeFailed) "(screenshot failed to decode)" else "(no screenshot)",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
      Canvas(modifier = Modifier.fillMaxSize()) {
        if (example.deviceWidth <= 0 || example.deviceHeight <= 0) return@Canvas
        val scaleX = size.width / example.deviceWidth
        val scaleY = size.height / example.deviceHeight
        overlays.forEach { overlay ->
          val isHighlighted = highlightedOverlay != null &&
            highlightedOverlay.kind == overlay.kind &&
            highlightedOverlay.entryIndex == overlay.entryIndex
          val anySelected = highlightedOverlay != null
          overlay.matchedBounds.forEach { bounds ->
            drawOverlayRect(
              bounds = bounds,
              scaleX = scaleX,
              scaleY = scaleY,
              kind = overlay.kind,
              isSatisfied = overlay.isSatisfied(),
              isAmbiguous = overlay.kind == WaypointSelectorKind.REQUIRED &&
                overlay.matchedCount > overlay.minCount,
              isHighlighted = isHighlighted,
              isDimmed = anySelected && !isHighlighted,
            )
          }
        }
      }
    }
  }
}

@Composable
private fun OverlayLegend(overlays: List<WaypointSelectorOverlay>) {
  val requiredOk = overlays.count { it.kind == WaypointSelectorKind.REQUIRED && it.isSatisfied() }
  val requiredTotal = overlays.count { it.kind == WaypointSelectorKind.REQUIRED }
  val requiredAmbiguous = overlays.count {
    it.kind == WaypointSelectorKind.REQUIRED && it.matchedCount > it.minCount
  }
  val forbiddenPresent = overlays.count {
    it.kind == WaypointSelectorKind.FORBIDDEN && it.matchedCount > 0
  }
  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    LegendDot(color = WAYPOINT_MATCH_COLOR_OK, label = "required ok: $requiredOk/$requiredTotal")
    if (requiredAmbiguous > 0) {
      LegendDot(color = WAYPOINT_MATCH_COLOR_AMBIGUOUS, label = "ambiguous: $requiredAmbiguous")
    }
    if (forbiddenPresent > 0) {
      LegendDot(color = WAYPOINT_MATCH_COLOR_BAD, label = "forbidden present: $forbiddenPresent")
    }
  }
}

@Composable
private fun LegendDot(color: Color, label: String) {
  Row(verticalAlignment = Alignment.CenterVertically) {
    Box(
      modifier = Modifier
        .width(10.dp)
        .height(10.dp)
        .clip(MaterialTheme.shapes.extraSmall)
        .background(color),
    )
    Spacer(Modifier.width(6.dp))
    Text(
      text = label,
      style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      fontWeight = FontWeight.Medium,
    )
  }
}

// Match-status palette — single source of truth for both the canvas overlay rectangles
// in this file and the per-card status badge in the visualizer. `internal` so
// `WaypointVisualizerComposable.kt` can reuse the same values instead of redeclaring the
// hex literals (the previous duplication drifted out of sync trivially).
//
// These are intentionally fixed colors rather than `MaterialTheme.colorScheme.*` lookups
// because "satisfied / not satisfied / ambiguous" carries semantic weight (green / red /
// orange) that should not flip with light/dark theme. Revisit if/when the design system
// gains semantic tokens for status — until then a single shared palette is the right knob.
internal val WAYPOINT_MATCH_COLOR_OK = Color(0xFF2E7D32)
internal val WAYPOINT_MATCH_COLOR_BAD = Color(0xFFC62828)
internal val WAYPOINT_MATCH_COLOR_AMBIGUOUS = Color(0xFFEF6C00)

/**
 * Status-badge label + color for a single resolved [WaypointSelectorOverlay]. Pulled out
 * of the badge composable so it can be unit-tested without a Compose harness — the
 * 6-branch `when` over (kind, matchedCount, minCount) is exactly the kind of pure logic
 * that benefits from coverage.
 */
internal fun matchStatusBadgeStyle(overlay: WaypointSelectorOverlay): Pair<String, Color> = when {
  overlay.kind == WaypointSelectorKind.FORBIDDEN && overlay.matchedCount > 0 ->
    "PRESENT (${overlay.matchedCount})" to WAYPOINT_MATCH_COLOR_BAD
  overlay.kind == WaypointSelectorKind.FORBIDDEN ->
    "ABSENT" to WAYPOINT_MATCH_COLOR_OK
  overlay.matchedCount == 0 ->
    "NO MATCH" to WAYPOINT_MATCH_COLOR_BAD
  // A REQUIRED selector that matched nodes structurally but none of them carry bounds
  // we can draw — surface this distinctly so the user sees a problem rather than a
  // confidently green badge with no rectangles on the screenshot.
  overlay.kind == WaypointSelectorKind.REQUIRED &&
    overlay.matchedCount > 0 &&
    overlay.matchedBounds.isEmpty() ->
    "MATCH (no bounds)" to WAYPOINT_MATCH_COLOR_AMBIGUOUS
  overlay.matchedCount < overlay.minCount ->
    "${overlay.matchedCount}/${overlay.minCount}" to WAYPOINT_MATCH_COLOR_BAD
  overlay.matchedCount > overlay.minCount ->
    "${overlay.matchedCount} matches" to WAYPOINT_MATCH_COLOR_AMBIGUOUS
  else ->
    "MATCH" to WAYPOINT_MATCH_COLOR_OK
}

private fun DrawScope.drawOverlayRect(
  bounds: TrailblazeNode.Bounds,
  scaleX: Float,
  scaleY: Float,
  kind: WaypointSelectorKind,
  isSatisfied: Boolean,
  isAmbiguous: Boolean,
  isHighlighted: Boolean,
  isDimmed: Boolean,
) {
  val color = when {
    kind == WaypointSelectorKind.FORBIDDEN -> WAYPOINT_MATCH_COLOR_BAD
    !isSatisfied -> WAYPOINT_MATCH_COLOR_BAD
    isAmbiguous -> WAYPOINT_MATCH_COLOR_AMBIGUOUS
    else -> WAYPOINT_MATCH_COLOR_OK
  }
  val alpha = when {
    isDimmed -> 0.20f
    isHighlighted -> 1f
    else -> 0.85f
  }
  val stroke = if (isHighlighted) 4f else 2f
  val left = bounds.left * scaleX
  val top = bounds.top * scaleY
  val right = bounds.right * scaleX
  val bottom = bounds.bottom * scaleY
  drawRect(
    color = color.copy(alpha = alpha),
    topLeft = Offset(left, top),
    size = Size(right - left, bottom - top),
    style = Stroke(width = stroke),
  )
  drawRect(
    color = color.copy(alpha = alpha * 0.15f),
    topLeft = Offset(left, top),
    size = Size(right - left, bottom - top),
  )
}
