package xyz.block.trailblaze.ui.waypoints

/**
 * Pure, testable layout algorithm for the waypoint map canvas. Takes the set of waypoint
 * ids plus the authored shortcut and trailhead edges, and returns each waypoint's grid
 * position (`(layer, column)`).
 *
 * Lives in commonMain with no Compose dependency so the layout can be exercised by plain
 * unit tests on JVM and reused as-is on WASM.
 *
 * ## Layout strategy
 *
 * The issue body picks "**layered DAG** for small graphs (≤30 waypoints), force-directed
 * if the graph grows past that." For v2.0 we implement just the layered-DAG path —
 * force-directed lands as a follow-up if and when waypoint counts demand it.
 *
 * **Layer assignment** is the longest-path / "as late as possible" walk: a waypoint's
 * layer is one greater than the deepest layer among any waypoint that has a shortcut
 * pointing to it. Trailhead targets sit at layer 0 alongside other roots — the virtual
 * "outside" node renders above the grid as a separate glyph; we don't push trailhead
 * targets into a phantom layer 1 just to make room for the trailhead arrow, because that
 * would also push every shortcut chain one row down for no visual benefit.
 *
 * **Cycle handling** combines a snapshot-based relaxation with an iteration cap. Each
 * iteration reads `from` layer values from a snapshot of the previous iteration so a
 * single round can only bump any node's layer by at most 1 — without that, an in-place
 * relaxation traverses the cycle multiple times per iteration and grows layers ~quadrat-
 * ically. With the snapshot the worst case is `waypointIds.size` for an SCC member,
 * keeping the canvas height linearly bounded. SCC members still land at non-canonical
 * layers — the proper SCC-condensation pass is a follow-up — but the rendered graph is
 * never grossly distorted by a cycle.
 *
 * **Column assignment** within each layer is alphabetic by waypoint id. Stable across
 * runs (no Map iteration order surprises on WASM), reproducible in tests, and gives
 * authors a layout-by-name that matches list-view ordering. Heuristic edge-crossing
 * minimization (barycenter, median) is a future enhancement when the visual quality
 * starts to matter.
 *
 * ## Input contract
 *
 * The shortcut list is allowed to reference waypoint ids that aren't in [waypointIds] —
 * those references are silently ignored (a shortcut with a `from` or `to` whose waypoint
 * has been deleted but the YAML still references it). The renderer does not currently
 * surface a warning for dangling references; the layout step just refuses to position a
 * node that doesn't exist. Adding a "this YAML references a missing waypoint" diagnostic
 * to the Map UI is a deferred follow-up.
 */
internal object WaypointMapLayout {

  /**
   * One waypoint's position in the rendered grid.
   *
   * - [layer] — 0-based row from top. Roots (waypoints with no incoming shortcut) are
   *   layer 0; each successive shortcut hop adds one.
   * - [column] — 0-based position within the layer, alphabetic by waypoint id.
   */
  data class GridPosition(val layer: Int, val column: Int)

  /**
   * Aggregate output: per-waypoint position plus a summary of total grid size that the
   * canvas renderer uses to pick its scrollable content dimensions.
   *
   * - [layerCount] — number of populated layers, i.e. `(max layer) + 1`. Zero when
   *   [waypointIds] is empty.
   * - [maxColumns] — the widest layer's column count, used to size the canvas width.
   *   Zero when [waypointIds] is empty.
   */
  data class Layout(
    val positions: Map<String, GridPosition>,
    val layerCount: Int,
    val maxColumns: Int,
  )

  /**
   * Computes [Layout] from the input graph. See class kdoc for the algorithm.
   *
   * Iteration cap is set to [waypointIds] size to bound work on cyclic inputs — see
   * "Cycle handling" in the class kdoc.
   */
  fun compute(
    waypointIds: List<String>,
    shortcuts: List<ShortcutDisplayItem>,
    @Suppress("UNUSED_PARAMETER") trailheads: List<TrailheadDisplayItem>,
  ): Layout {
    if (waypointIds.isEmpty()) {
      return Layout(positions = emptyMap(), layerCount = 0, maxColumns = 0)
    }
    val waypointSet = waypointIds.toSet()
    // Filter shortcuts to those whose endpoints both exist; dangling references can't
    // contribute to the layout. The renderer will report them separately.
    val resolvedShortcuts = shortcuts.filter { it.from in waypointSet && it.to in waypointSet }

    // Initialize every waypoint at layer 0. Roots (no incoming shortcut) and trailhead
    // targets stay there; nodes downstream of a chain get bumped by the iteration below.
    val layer = HashMap<String, Int>(waypointIds.size).apply {
      waypointIds.forEach { put(it, 0) }
    }

    // Snapshot-based longest-path relaxation. Each iteration reads `from` layers from a
    // snapshot of the previous iteration's state (not the live, mid-iteration values),
    // so a single iteration can grow any node's layer by at most 1. On an acyclic graph
    // this converges in `waypointIds.size` rounds (longest path has at most that many
    // edges); on a cyclic graph it bounds the SCC's layer values to `waypointIds.size`,
    // not the ~n² that an in-place Bellman-Ford-style relaxation would produce when
    // each iteration walks the cycle multiple times. Codex flagged the in-place version
    // as a runaway-canvas-height risk on cyclic authored shortcuts; the snapshot keeps
    // the canvas size linearly bounded in the worst case.
    val maxIterations = waypointIds.size
    var iter = 0
    var changed = true
    while (changed && iter < maxIterations) {
      iter++
      changed = false
      val snapshot = HashMap(layer)
      for (s in resolvedShortcuts) {
        val fromLayer = snapshot[s.from] ?: continue
        val toLayer = layer[s.to] ?: continue
        val candidate = fromLayer + 1
        if (candidate > toLayer) {
          layer[s.to] = candidate
          changed = true
        }
      }
    }

    // Group by layer, then assign columns by alphabetic waypoint id within each layer.
    // Sorted list (not Map) because Map.toSortedMap() is JVM-only — visualizer must
    // compile for wasmJs (same constraint already documented on the target dropdown).
    val byLayer: List<Pair<Int, List<String>>> = waypointIds
      .groupBy { layer[it] ?: 0 }
      .toList()
      .sortedBy { it.first }
      .map { (l, ids) -> l to ids.sorted() }

    val positions = HashMap<String, GridPosition>(waypointIds.size)
    var maxColumns = 0
    for ((l, idsInLayer) in byLayer) {
      idsInLayer.forEachIndexed { col, id ->
        positions[id] = GridPosition(layer = l, column = col)
      }
      if (idsInLayer.size > maxColumns) maxColumns = idsInLayer.size
    }

    val layerCount = (byLayer.maxOfOrNull { it.first } ?: 0) + 1
    return Layout(
      positions = positions,
      layerCount = layerCount,
      maxColumns = maxColumns,
    )
  }
}
