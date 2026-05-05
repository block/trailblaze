package xyz.block.trailblaze.ui.waypoints

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlightTakeoff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import coil3.compose.AsyncImage
import xyz.block.trailblaze.ui.composables.SelectableText
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Static graph view ("Map" mode) for the Waypoints tab.
 *
 * Renders the navigation graph as a layered DAG:
 *
 * - **Nodes** are waypoints, drawn as small cards with the captured example screenshot
 *   thumbnail and the waypoint id underneath. Same picture as the definition view, just
 *   smaller and positioned on a grid by [WaypointMapLayout].
 * - **Solid edges** are authored shortcuts (`*.shortcut.yaml` files — `ToolYamlConfig`
 *   with a populated `shortcut: { from, to }` block). Drawn `from → to` with an arrowhead.
 * - **Dashed entry edges** are trailheads (`*.trailhead.yaml` files — `ToolYamlConfig`
 *   with a populated `trailhead: { to }` block). Drawn from a virtual "outside" glyph at
 *   the top of the canvas into each trailhead's target waypoint, since trailheads
 *   bootstrap from any state and have no `from`.
 *
 * Today's repo has zero authored shortcuts or trailheads, so the canvas in practice
 * renders just the node grid. The edge rendering is wired so the moment an author commits
 * the first `*.shortcut.yaml` or `*.trailhead.yaml`, it appears on the map.
 *
 * No session overlay (observed segments) here — that's v2.1 per the issue's milestone
 * plan.
 *
 * ## Multiplatform constraints
 *
 * Lives in commonMain so the same composable backs the Desktop tab today and a future
 * WASM target. Specifically:
 *  - No `String.format` (use the `formatOneDecimal`-style helper precedent).
 *  - `LocalDensity` *is* available on both Desktop and WASM, so we use it to convert
 *    grid coordinates to pixels for the edge canvas.
 *  - Screenshot rendering reuses the [coil3.compose.AsyncImage] path that
 *    [WaypointExamplePanel] already established.
 */
@Composable
internal fun WaypointMapCanvas(
  waypoints: List<WaypointDisplayItem>,
  shortcuts: List<ShortcutDisplayItem>,
  trailheads: List<TrailheadDisplayItem>,
  selectedId: String?,
  onSelect: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  val itemsById = remember(waypoints) { waypoints.associateBy { it.definition.id } }
  val layout = remember(waypoints, shortcuts, trailheads) {
    WaypointMapLayout.compute(
      waypointIds = waypoints.map { it.definition.id },
      shortcuts = shortcuts,
      trailheads = trailheads,
    )
  }
  val resolvedShortcuts = remember(shortcuts, itemsById) {
    shortcuts.filter { it.from in itemsById && it.to in itemsById }
  }
  val resolvedTrailheads = remember(trailheads, itemsById) {
    trailheads.filter { it.to in itemsById }
  }

  if (waypoints.isEmpty()) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      SelectableText(
        text = "No waypoints to draw on the map.",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    return
  }

  // Reserve a row above layer 0 for the trailhead "outside" glyph when trailheads are
  // authored, so the dashed entry edges have somewhere to originate. When no trailheads
  // exist, the row is omitted entirely (no glyph, no extra height) — the canvas sizes
  // tightly to the waypoint layers and the toggle's mode-button label carries the
  // "0 trailheads" affordance instead.
  val hasTrailheads = resolvedTrailheads.isNotEmpty()

  val totalWidthDp = remember(layout) {
    val cols = layout.maxColumns.coerceAtLeast(1)
    MAP_HORIZONTAL_PADDING * 2 + NODE_WIDTH * cols + NODE_HORIZONTAL_GAP * (cols - 1)
  }
  val totalHeightDp = remember(layout, hasTrailheads) {
    val layers = layout.layerCount.coerceAtLeast(1)
    val baseHeight = MAP_VERTICAL_PADDING * 2 +
      NODE_HEIGHT * layers +
      NODE_VERTICAL_GAP * (layers - 1)
    if (hasTrailheads) baseHeight + TRAILHEAD_ROW_HEIGHT else baseHeight
  }

  val density = LocalDensity.current
  val edgeColor = MaterialTheme.colorScheme.onSurface
  val trailheadEdgeColor = MaterialTheme.colorScheme.tertiary

  Box(
    modifier = modifier
      .horizontalScroll(rememberScrollState())
      .verticalScroll(rememberScrollState()),
  ) {
    Box(modifier = Modifier.size(width = totalWidthDp, height = totalHeightDp)) {
      // Edge layer — drawn first so node cards render above the lines. Same parent
      // sizing as the node layer, so px coordinates line up between the two.
      Canvas(modifier = Modifier.matchParentSize()) {
        // Authored shortcut edges (solid).
        resolvedShortcuts.forEach { shortcut ->
          val fromPos = layout.positions[shortcut.from] ?: return@forEach
          val toPos = layout.positions[shortcut.to] ?: return@forEach
          val fromCenter = nodeBottomCenterPx(fromPos, density, hasTrailheads)
          val toCenter = nodeTopCenterPx(toPos, density, hasTrailheads)
          drawEdge(
            start = fromCenter,
            end = toCenter,
            color = edgeColor,
            strokeWidthPx = with(density) { 1.5.dp.toPx() },
            dashIntervalPx = null,
          )
        }
        // Trailhead entry edges (dashed) from the virtual "outside" anchor down into
        // each trailhead's target waypoint. The "outside" anchor is a single point at
        // the horizontal center of the canvas.
        if (hasTrailheads) {
          val outsideAnchor = trailheadOutsideAnchorPx(totalWidthDp, density)
          resolvedTrailheads.forEach { trailhead ->
            val toPos = layout.positions[trailhead.to] ?: return@forEach
            val toCenter = nodeTopCenterPx(toPos, density, hasTrailheads = true)
            drawEdge(
              start = outsideAnchor,
              end = toCenter,
              color = trailheadEdgeColor,
              strokeWidthPx = with(density) { 1.5.dp.toPx() },
              dashIntervalPx = with(density) { 6.dp.toPx() },
            )
          }
        }
      }

      // Trailhead "outside" glyph at the top of the canvas, centered horizontally.
      // Always rendered when at least one authored trailhead exists; dropped entirely
      // otherwise so a graph with zero trailheads doesn't carry orphaned chrome.
      if (hasTrailheads) {
        TrailheadOutsideGlyph(
          totalWidthDp = totalWidthDp,
          modifier = Modifier.align(Alignment.TopStart),
        )
      }

      // Waypoint nodes positioned by grid coords.
      waypoints.forEach { wp ->
        val pos = layout.positions[wp.definition.id] ?: return@forEach
        val (xDp, yDp) = nodeTopLeftDp(pos, hasTrailheads)
        WaypointNodeCard(
          item = wp,
          isSelected = wp.definition.id == selectedId,
          onClick = { onSelect(wp.definition.id) },
          modifier = Modifier.offset(x = xDp, y = yDp),
        )
      }
    }
  }
}

/**
 * Outer padding around the whole canvas. Keeps node cards (and edge endpoints) clear of
 * the scroll container's edge so the first/last node aren't visually cropped.
 */
private val MAP_HORIZONTAL_PADDING = 24.dp
private val MAP_VERTICAL_PADDING = 16.dp

/**
 * Fixed node card dimensions. Width is screenshot-thumbnail-driven; height includes the
 * thumbnail (9:16-ish aspect) plus a one-line id label below. These sizes are quoted in
 * the issue body's v2.0 milestone ("~120×220dp each").
 */
private val NODE_WIDTH = 140.dp
private val NODE_HEIGHT = 240.dp

/**
 * Spacing between adjacent nodes. Horizontal gap leaves room for the id label without
 * collisions; vertical gap leaves room for the edge arrowhead between layers.
 */
private val NODE_HORIZONTAL_GAP = 28.dp
private val NODE_VERTICAL_GAP = 64.dp

/**
 * Vertical band above layer 0 reserved for the trailhead "outside" glyph and the
 * dashed entry edges from it. Rendered only when at least one authored trailhead
 * exists; otherwise it would be wasted space.
 */
private val TRAILHEAD_ROW_HEIGHT = 64.dp

/** Top-left dp of a waypoint node given its grid position. */
private fun nodeTopLeftDp(
  pos: WaypointMapLayout.GridPosition,
  hasTrailheadRow: Boolean,
): Pair<Dp, Dp> {
  val x = MAP_HORIZONTAL_PADDING + (NODE_WIDTH + NODE_HORIZONTAL_GAP) * pos.column
  val baseY = MAP_VERTICAL_PADDING + (NODE_HEIGHT + NODE_VERTICAL_GAP) * pos.layer
  val y = if (hasTrailheadRow) baseY + TRAILHEAD_ROW_HEIGHT else baseY
  return x to y
}

/**
 * Pixel-space top-center of a node — the "incoming" anchor point for edges that
 * terminate on it. Computed from the same grid math as [nodeTopLeftDp] so edges and
 * node positions line up exactly.
 */
private fun nodeTopCenterPx(
  pos: WaypointMapLayout.GridPosition,
  density: Density,
  hasTrailheads: Boolean,
): Offset {
  val (xDp, yDp) = nodeTopLeftDp(pos, hasTrailheads)
  return with(density) {
    Offset(
      x = (xDp + NODE_WIDTH / 2).toPx(),
      y = yDp.toPx(),
    )
  }
}

/** Pixel-space bottom-center of a node — the "outgoing" anchor for outbound edges. */
private fun nodeBottomCenterPx(
  pos: WaypointMapLayout.GridPosition,
  density: Density,
  hasTrailheads: Boolean,
): Offset {
  val (xDp, yDp) = nodeTopLeftDp(pos, hasTrailheads)
  return with(density) {
    Offset(
      x = (xDp + NODE_WIDTH / 2).toPx(),
      y = (yDp + NODE_HEIGHT).toPx(),
    )
  }
}

/**
 * Pixel-space anchor for the virtual "outside" node — single point centered horizontally
 * within the [TRAILHEAD_ROW_HEIGHT] band. Used as the start point for every trailhead's
 * dashed entry edge.
 */
private fun trailheadOutsideAnchorPx(totalWidthDp: Dp, density: Density): Offset {
  return with(density) {
    Offset(
      x = (totalWidthDp / 2).toPx(),
      y = (MAP_VERTICAL_PADDING + TRAILHEAD_ROW_HEIGHT / 2).toPx(),
    )
  }
}

/**
 * Renders one waypoint as a click-able card with screenshot thumbnail and id label.
 * Selection bumps the border color so the user can see which node corresponds to the
 * detail panel below the canvas.
 */
@Composable
private fun WaypointNodeCard(
  item: WaypointDisplayItem,
  isSelected: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val example = item.example
  val borderColor = if (isSelected) {
    MaterialTheme.colorScheme.primary
  } else {
    MaterialTheme.colorScheme.outlineVariant
  }
  Card(
    modifier = modifier
      .size(width = NODE_WIDTH, height = NODE_HEIGHT)
      .border(
        width = if (isSelected) 2.dp else 1.dp,
        color = borderColor,
        shape = MaterialTheme.shapes.medium,
      )
      .clickable(onClick = onClick),
    colors = CardDefaults.cardColors(
      containerColor = MaterialTheme.colorScheme.surface,
    ),
  ) {
    Column(modifier = Modifier.fillMaxSize().padding(6.dp)) {
      // Track decode errors per-node so a corrupt screenshot blob doesn't blank out
      // every map node sharing the same coil3 codec — same pattern as
      // [WaypointExamplePanel].
      var decodeFailed by remember(example) { mutableStateOf(false) }
      val screenshotBytes = example?.screenshotBytes?.takeIf { it.isNotEmpty() }
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .aspectRatio(0.5625f) // 9:16 — matches typical phone screenshot framing
          .clip(MaterialTheme.shapes.small)
          .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
      ) {
        if (screenshotBytes != null && !decodeFailed) {
          AsyncImage(
            model = screenshotBytes,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
            onError = { _ -> decodeFailed = true },
          )
        } else {
          Text(
            text = if (decodeFailed) "(decode failed)" else "(no screenshot)",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
      Spacer(Modifier.height(4.dp))
      Text(
        text = item.definition.id,
        style = MaterialTheme.typography.labelSmall,
        fontFamily = FontFamily.Monospace,
        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 2,
      )
    }
  }
}

/**
 * The "outside" glyph at the top of the canvas — a single chip with a takeoff icon and
 * the word "trailheads", centered horizontally. Acts as the anchor every trailhead's
 * dashed entry edge originates from.
 */
@Composable
private fun TrailheadOutsideGlyph(totalWidthDp: Dp, modifier: Modifier = Modifier) {
  val chipWidth = 140.dp
  // Center the chip within the canvas width. The dashed edges line up with the chip's
  // bottom-center — the canvas math centers on totalWidthDp/2, so as long as this chip
  // is also centered horizontally the visuals connect.
  val xOffset = (totalWidthDp - chipWidth) / 2
  val yOffset = MAP_VERTICAL_PADDING + TRAILHEAD_ROW_HEIGHT / 2 - 14.dp
  Surface(
    modifier = modifier
      .offset(x = xOffset, y = yOffset)
      .width(chipWidth)
      .height(28.dp),
    color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.18f),
    contentColor = MaterialTheme.colorScheme.tertiary,
    shape = RoundedCornerShape(14.dp),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 10.dp).fillMaxSize(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.Center,
    ) {
      Icon(
        Icons.Filled.FlightTakeoff,
        contentDescription = null,
        modifier = Modifier.size(16.dp),
      )
      Spacer(Modifier.width(6.dp))
      Text(
        text = "trailheads",
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
      )
    }
  }
}

/**
 * Draws one edge from [start] to [end] as a line with an arrowhead at [end]. Pass a
 * non-null [dashIntervalPx] to render the line as a dashed pattern (for trailhead
 * entries); passing null produces a solid line (authored shortcuts).
 *
 * The arrowhead is a small filled triangle whose tip sits on the node boundary — the
 * caller already nudges [end] to the boundary point, so we don't need to inset further
 * here.
 */
private fun DrawScope.drawEdge(
  start: Offset,
  end: Offset,
  color: Color,
  strokeWidthPx: Float,
  dashIntervalPx: Float?,
) {
  // Line body. Dashed pattern handled via PathEffect when requested; falls back to a
  // plain drawLine for solid edges (cheaper, no path allocation).
  if (dashIntervalPx != null) {
    val pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
      intervals = floatArrayOf(dashIntervalPx, dashIntervalPx),
      phase = 0f,
    )
    drawLine(
      color = color,
      start = start,
      end = end,
      strokeWidth = strokeWidthPx,
      pathEffect = pathEffect,
    )
  } else {
    drawLine(
      color = color,
      start = start,
      end = end,
      strokeWidth = strokeWidthPx,
    )
  }
  // Arrowhead — filled triangle whose tip sits at [end], rotated to align with the
  // line direction. Size scales loosely with stroke width so thin/thick edges look
  // proportionate.
  val arrowLen = strokeWidthPx * 6f
  val arrowWidth = strokeWidthPx * 4f
  val angle = atan2(end.y - start.y, end.x - start.x).toDouble()
  val sinA = sin(angle).toFloat()
  val cosA = cos(angle).toFloat()
  val backX = end.x - cosA * arrowLen
  val backY = end.y - sinA * arrowLen
  val leftX = backX + sinA * (arrowWidth / 2)
  val leftY = backY - cosA * (arrowWidth / 2)
  val rightX = backX - sinA * (arrowWidth / 2)
  val rightY = backY + cosA * (arrowWidth / 2)
  val path = Path().apply {
    moveTo(end.x, end.y)
    lineTo(leftX, leftY)
    lineTo(rightX, rightY)
    close()
  }
  // Filled triangle — single drawPath call, no Stroke pass. The earlier version drew
  // the path twice (once stroked, once filled) which Copilot flagged as redundant
  // double-render with the same color. The kdoc above already promises a filled tri-
  // angle; the stroke pass added blur without adding visual signal.
  drawPath(path = path, color = color)
}
