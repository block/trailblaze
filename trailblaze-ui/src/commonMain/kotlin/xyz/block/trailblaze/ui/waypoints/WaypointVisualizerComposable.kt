package xyz.block.trailblaze.ui.waypoints

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import xyz.block.trailblaze.api.DriverNodeMatch
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.api.waypoint.WaypointDefinition
import xyz.block.trailblaze.api.waypoint.WaypointSelectorEntry
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.ui.composables.SelectableText
import xyz.block.trailblaze.ui.models.AppIconProvider

/**
 * Multiplatform waypoint visualizer. Takes a pre-loaded list of [WaypointDisplayItem] and
 * renders a master/detail browser: filterable list on the left, full waypoint detail on
 * the right. Keeps no JVM-specific dependencies so the same composable backs the Desktop
 * tab today and the Compose Web (WASM) target as a fast follower.
 *
 * Loading concerns (filesystem walks, pack discovery) live in JVM-only callers; this
 * composable just renders whatever items it is given plus a small empty/error surface.
 */
/**
 * View modes for the Waypoints tab.
 *
 * - [Definitions] — the existing master/detail browser: filterable list on the left,
 *   waypoint detail with selector overlays on the right. Default mode.
 * - [Map] — the navigation graph view: waypoint nodes on a layered-DAG canvas, with
 *   authored shortcuts as solid edges and trailheads as dashed entry edges from a
 *   virtual "outside" anchor.
 *
 * Public so the desktop wrapper or a future WASM caller can pin an initial mode (e.g.
 * deep-link "?mode=map"). The toggle UI lives inside [WaypointVisualizer].
 */
enum class WaypointVisualizerMode { Definitions, Map }

@Composable
fun WaypointVisualizer(
  items: List<WaypointDisplayItem>,
  modifier: Modifier = Modifier,
  emptyState: @Composable () -> Unit = { DefaultEmptyState() },
  header: @Composable (() -> Unit)? = null,
  availableTargets: Set<TrailblazeHostAppTarget> = emptySet(),
  appIconProvider: AppIconProvider = AppIconProvider.DefaultAppIconProvider,
  /**
   * 1-based step indices, per waypoint id, where that waypoint matched in the currently
   * selected Session Lens session. Empty by default (no session selected) — the panel
   * renders no match badges. When non-empty, each waypoint card whose id appears as a
   * key gets a `matched @ 3, 9, 12` badge alongside the existing required/forbidden chips
   * (see [formatMatchedStepsLabel] for the exact label format).
   *
   * Step lists are taken as-is from the source — chronological order is the renderer's
   * responsibility to display, not to reorder.
   */
  matchedStepsByWaypoint: Map<String, List<Int>> = emptyMap(),
  /**
   * Authored shortcuts (`*.shortcut.yaml` files — `ToolYamlConfig` with a populated
   * `shortcut: { from, to }` block). Drawn as solid edges between waypoint nodes when
   * the user picks Map mode. Empty by default (today's repo has no authored shortcuts);
   * the rendering is wired so the moment any author commits a shortcut, it appears.
   */
  shortcuts: List<ShortcutDisplayItem> = emptyList(),
  /**
   * Authored trailheads (`*.trailhead.yaml` files — `ToolYamlConfig` with a populated
   * `trailhead: { to }` block). Drawn as dashed entry edges from a virtual "outside"
   * anchor into each trailhead's target waypoint when the user picks Map mode.
   */
  trailheads: List<TrailheadDisplayItem> = emptyList(),
  /** Initial mode the visualizer opens in. Default is the existing definitions browser. */
  initialMode: WaypointVisualizerMode = WaypointVisualizerMode.Definitions,
) {
  var query by remember { mutableStateOf("") }
  var selectedId: String? by remember(items) { mutableStateOf(items.firstOrNull()?.definition?.id) }
  var selectedTarget: String? by remember(items) { mutableStateOf(null) }
  var selectedPlatform: String? by remember(items, selectedTarget) { mutableStateOf(null) }

  // Pack/target prefix is the segment before the first '/'. Sorted list (not Map) because
  // Map.toSortedMap() is JVM-only — visualizer must compile for wasmJs.
  val targetCounts: List<Pair<String, Int>> = remember(items) {
    items.groupingBy { it.definition.id.substringBefore('/') }
      .eachCount()
      .toList()
      .sortedBy { it.first }
  }

  // Platform = second '/' segment within the selected target. Only meaningful for
  // three-part ids like `myapp/android/splash`; two-part `pack/local-name` ids
  // (clock-style) have no platform segment, so the dropdown stays hidden.
  val platformCounts: List<Pair<String, Int>> = remember(items, selectedTarget) {
    val target = selectedTarget ?: return@remember emptyList()
    items
      .filter { it.definition.id.substringBefore('/') == target }
      .mapNotNull { it.definition.platformSegmentOrNull() }
      .groupingBy { it }
      .eachCount()
      .toList()
      .sortedBy { it.first }
  }

  val filtered = remember(items, query, selectedTarget, selectedPlatform) {
    val byTarget = if (selectedTarget == null) {
      items
    } else {
      items.filter { it.definition.id.substringBefore('/') == selectedTarget }
    }
    val byPlatform = if (selectedPlatform == null) {
      byTarget
    } else {
      byTarget.filter { it.definition.platformSegmentOrNull() == selectedPlatform }
    }
    if (query.isBlank()) {
      byPlatform
    } else {
      val q = query.trim().lowercase()
      byPlatform.filter { item ->
        item.definition.id.lowercase().contains(q) ||
          (item.definition.description?.lowercase()?.contains(q) == true) ||
          (item.sourceLabel?.lowercase()?.contains(q) == true)
      }
    }
  }

  val selected = remember(filtered, selectedId) {
    filtered.firstOrNull { it.definition.id == selectedId } ?: filtered.firstOrNull()
  }

  // Map mode resolves the selection against the *full* item set, not the filter-scoped
  // [selected]. Otherwise typing a query that excludes the clicked node would cause the
  // map's onSelect callback to land on `selectedId`, but [selected] would resolve to a
  // different (filter-included) waypoint or null, and the visible highlight would bounce
  // back. Codex / Copilot both flagged this — the filter is a Definitions-mode concern,
  // not a Map-mode one.
  val mapSelected = remember(items, selectedId) {
    items.firstOrNull { it.definition.id == selectedId }
  }

  var mode by remember(initialMode) { mutableStateOf(initialMode) }

  Column(modifier = modifier.fillMaxSize()) {
    if (header != null) {
      header()
      HorizontalDivider()
    }
    if (items.isEmpty()) {
      Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        emptyState()
      }
      return@Column
    }
    WaypointModeToggle(
      mode = mode,
      onModeChange = { mode = it },
      shortcutCount = shortcuts.size,
      trailheadCount = trailheads.size,
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 12.dp, vertical = 8.dp),
    )
    HorizontalDivider()
    when (mode) {
      WaypointVisualizerMode.Map -> {
        WaypointMapCanvas(
          waypoints = items,
          shortcuts = shortcuts,
          trailheads = trailheads,
          selectedId = mapSelected?.definition?.id,
          onSelect = { selectedId = it },
          modifier = Modifier.fillMaxSize(),
        )
      }
      WaypointVisualizerMode.Definitions -> {
        Row(modifier = Modifier.fillMaxSize()) {
          WaypointListPanel(
            items = filtered,
            totalCount = items.size,
            query = query,
            onQueryChange = { query = it },
            targetCounts = targetCounts,
            selectedTarget = selectedTarget,
            onTargetChange = {
              selectedTarget = it
              selectedPlatform = null
            },
            platformCounts = platformCounts,
            selectedPlatform = selectedPlatform,
            onPlatformChange = { selectedPlatform = it },
            availableTargets = availableTargets,
            appIconProvider = appIconProvider,
            selectedId = selected?.definition?.id,
            onSelect = { selectedId = it },
            matchedStepsByWaypoint = matchedStepsByWaypoint,
            modifier = Modifier
              .width(320.dp)
              .fillMaxHeight()
              .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
          )
          if (selected != null) {
            WaypointDetailPanel(
              item = selected,
              matchedSteps = matchedStepsByWaypoint[selected.definition.id].orEmpty(),
              modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(),
            )
          } else {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
              SelectableText(
                text = "No waypoints match \"$query\".",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        }
      }
    }
  }
}

/**
 * Top-of-tab segmented toggle for switching between [WaypointVisualizerMode.Definitions]
 * and [WaypointVisualizerMode.Map]. Renders edge counts inline on the Map button so
 * authors get a glanceable signal of "how many shortcuts/trailheads are loaded today"
 * without switching modes — the issue's guidance was that today's authored set is empty
 * but should populate as authors create files.
 *
 * Implemented with two stacked [Surface]s rather than `SegmentedButton` because the
 * latter is part of a Material 3 incubator API that lands at different versions across
 * targets — Surface + clickable is universally available on JVM and WASM.
 */
@Composable
private fun WaypointModeToggle(
  mode: WaypointVisualizerMode,
  onModeChange: (WaypointVisualizerMode) -> Unit,
  shortcutCount: Int,
  trailheadCount: Int,
  modifier: Modifier = Modifier,
) {
  Row(
    modifier = modifier,
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    WaypointModeChip(
      label = "Definitions",
      isSelected = mode == WaypointVisualizerMode.Definitions,
      onClick = { onModeChange(WaypointVisualizerMode.Definitions) },
    )
    val mapLabel = buildString {
      append("Map")
      if (shortcutCount > 0 || trailheadCount > 0) {
        append(" · ")
        append("$shortcutCount shortcut${if (shortcutCount == 1) "" else "s"}")
        append(", ")
        append("$trailheadCount trailhead${if (trailheadCount == 1) "" else "s"}")
      }
    }
    WaypointModeChip(
      label = mapLabel,
      isSelected = mode == WaypointVisualizerMode.Map,
      onClick = { onModeChange(WaypointVisualizerMode.Map) },
    )
  }
}

@Composable
private fun WaypointModeChip(label: String, isSelected: Boolean, onClick: () -> Unit) {
  val container = if (isSelected) {
    MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
  } else {
    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
  }
  val content = if (isSelected) {
    MaterialTheme.colorScheme.primary
  } else {
    MaterialTheme.colorScheme.onSurfaceVariant
  }
  Surface(
    color = container,
    contentColor = content,
    shape = MaterialTheme.shapes.small,
    modifier = Modifier.clickable(onClick = onClick),
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.labelMedium,
      fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
      modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
    )
  }
}

@Composable
private fun DefaultEmptyState() {
  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(8.dp),
    modifier = Modifier.padding(32.dp),
  ) {
    SelectableText(
      text = "No waypoints found",
      style = MaterialTheme.typography.titleMedium,
      fontWeight = FontWeight.SemiBold,
    )
    SelectableText(
      text = "Point this view at a directory containing *.waypoint.yaml files, " +
        "or load a pack that bundles them.",
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

@Composable
private fun WaypointListPanel(
  items: List<WaypointDisplayItem>,
  totalCount: Int,
  query: String,
  onQueryChange: (String) -> Unit,
  targetCounts: List<Pair<String, Int>>,
  selectedTarget: String?,
  onTargetChange: (String?) -> Unit,
  platformCounts: List<Pair<String, Int>>,
  selectedPlatform: String?,
  onPlatformChange: (String?) -> Unit,
  availableTargets: Set<TrailblazeHostAppTarget>,
  appIconProvider: AppIconProvider,
  selectedId: String?,
  onSelect: (String) -> Unit,
  matchedStepsByWaypoint: Map<String, List<Int>>,
  modifier: Modifier = Modifier,
) {
  Column(modifier = modifier) {
    if (targetCounts.size > 1) {
      TargetDropdown(
        targetCounts = targetCounts,
        selectedTarget = selectedTarget,
        onTargetChange = onTargetChange,
        availableTargets = availableTargets,
        appIconProvider = appIconProvider,
        modifier = Modifier
          .fillMaxWidth()
          .padding(start = 12.dp, end = 12.dp, top = 12.dp),
      )
    }
    if (selectedTarget != null && platformCounts.isNotEmpty()) {
      PlatformDropdown(
        platformCounts = platformCounts,
        selectedPlatform = selectedPlatform,
        onPlatformChange = onPlatformChange,
        modifier = Modifier
          .fillMaxWidth()
          .padding(start = 12.dp, end = 12.dp, top = 8.dp),
      )
    }
    OutlinedTextField(
      value = query,
      onValueChange = onQueryChange,
      placeholder = { Text("Search waypoints") },
      leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
      singleLine = true,
      modifier = Modifier
        .fillMaxWidth()
        .padding(12.dp),
    )
    val isFiltered = query.isNotBlank() || selectedTarget != null || selectedPlatform != null
    val countLabel = if (isFiltered) {
      "${items.size} of $totalCount"
    } else {
      "$totalCount waypoint${if (totalCount == 1) "" else "s"}"
    }
    Text(
      text = countLabel,
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
    HorizontalDivider()
    LazyColumn(modifier = Modifier.fillMaxSize()) {
      items(items, key = { it.definition.id }) { item ->
        WaypointListRow(
          item = item,
          isSelected = item.definition.id == selectedId,
          onClick = { onSelect(item.definition.id) },
          matchedSteps = matchedStepsByWaypoint[item.definition.id].orEmpty(),
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TargetDropdown(
  targetCounts: List<Pair<String, Int>>,
  selectedTarget: String?,
  onTargetChange: (String?) -> Unit,
  availableTargets: Set<TrailblazeHostAppTarget>,
  appIconProvider: AppIconProvider,
  modifier: Modifier = Modifier,
) {
  var expanded by remember { mutableStateOf(false) }
  val targetById = remember(availableTargets) {
    availableTargets.associateBy { it.id }
  }
  val totalAcrossTargets = remember(targetCounts) { targetCounts.sumOf { it.second } }
  val selectedHost = selectedTarget?.let { targetById[it] }

  ExposedDropdownMenuBox(
    expanded = expanded,
    onExpandedChange = { expanded = !expanded },
    modifier = modifier,
  ) {
    TextField(
      readOnly = true,
      value = selectedHost?.displayName
        ?: selectedTarget
        ?: "All targets",
      onValueChange = {},
      label = { Text("Target") },
      leadingIcon = { selectedHost?.let { appIconProvider.getIcon(it) } },
      trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
      modifier = Modifier
        .fillMaxWidth()
        .menuAnchor(),
    )
    ExposedDropdownMenu(
      expanded = expanded,
      onDismissRequest = { expanded = false },
    ) {
      DropdownMenuItem(
        text = { Text("All targets ($totalAcrossTargets)") },
        onClick = {
          onTargetChange(null)
          expanded = false
        },
      )
      targetCounts.forEach { (id, count) ->
        val host = targetById[id]
        DropdownMenuItem(
          leadingIcon = { host?.let { appIconProvider.getIcon(it) } },
          text = { Text("${host?.displayName ?: id} ($count)") },
          onClick = {
            onTargetChange(id)
            expanded = false
          },
        )
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlatformDropdown(
  platformCounts: List<Pair<String, Int>>,
  selectedPlatform: String?,
  onPlatformChange: (String?) -> Unit,
  modifier: Modifier = Modifier,
) {
  var expanded by remember { mutableStateOf(false) }
  val totalAcrossPlatforms = remember(platformCounts) { platformCounts.sumOf { it.second } }

  ExposedDropdownMenuBox(
    expanded = expanded,
    onExpandedChange = { expanded = !expanded },
    modifier = modifier,
  ) {
    TextField(
      readOnly = true,
      value = selectedPlatform ?: "All platforms",
      onValueChange = {},
      label = { Text("Platform") },
      trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
      modifier = Modifier
        .fillMaxWidth()
        .menuAnchor(),
    )
    ExposedDropdownMenu(
      expanded = expanded,
      onDismissRequest = { expanded = false },
    ) {
      DropdownMenuItem(
        text = { Text("All platforms ($totalAcrossPlatforms)") },
        onClick = {
          onPlatformChange(null)
          expanded = false
        },
      )
      platformCounts.forEach { (platform, count) ->
        DropdownMenuItem(
          text = { Text("$platform ($count)") },
          onClick = {
            onPlatformChange(platform)
            expanded = false
          },
        )
      }
    }
  }
}

@Composable
private fun WaypointListRow(
  item: WaypointDisplayItem,
  isSelected: Boolean,
  onClick: () -> Unit,
  matchedSteps: List<Int>,
) {
  val def = item.definition
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .clickable(onClick = onClick)
      .background(
        if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
        else Color.Transparent,
      )
      .padding(horizontal = 16.dp, vertical = 10.dp),
  ) {
    Text(
      text = def.id,
      style = MaterialTheme.typography.bodyMedium,
      fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
      fontFamily = FontFamily.Monospace,
      color = if (isSelected) MaterialTheme.colorScheme.primary
      else MaterialTheme.colorScheme.onSurface,
      maxLines = 1,
    )
    if (!def.description.isNullOrBlank()) {
      Spacer(Modifier.height(2.dp))
      Text(
        text = def.description!!,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
      )
    }
    Spacer(Modifier.height(4.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
      WaypointCountChip(label = "required", count = def.required.size, accent = false)
      Spacer(Modifier.width(6.dp))
      WaypointCountChip(label = "forbidden", count = def.forbidden.size, accent = true)
      if (matchedSteps.isNotEmpty()) {
        Spacer(Modifier.width(6.dp))
        MatchedStepsBadge(matchedSteps)
      }
    }
  }
}

@Composable
private fun WaypointCountChip(label: String, count: Int, accent: Boolean) {
  val color = if (accent) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
  Box(
    modifier = Modifier
      .background(
        color = color.copy(alpha = 0.12f),
        shape = MaterialTheme.shapes.small,
      )
      .padding(horizontal = 8.dp, vertical = 2.dp),
  ) {
    Text(
      text = "$count $label",
      style = MaterialTheme.typography.labelSmall,
      color = color,
      fontWeight = FontWeight.Medium,
    )
  }
}

/**
 * Session-lens "matched at steps …" badge for the waypoint card. Distinct visual treatment
 * from [WaypointCountChip] (filled tertiary-tinted background instead of subtle alpha) so
 * it reads as session-overlay information rather than a static count.
 *
 * Empty step lists must not reach this composable — callers gate on `isNotEmpty`.
 */
@Composable
private fun MatchedStepsBadge(steps: List<Int>) {
  val color = MaterialTheme.colorScheme.tertiary
  val label = remember(steps) { formatMatchedStepsLabel(steps) }
  Box(
    modifier = Modifier
      .background(
        color = color.copy(alpha = 0.18f),
        shape = MaterialTheme.shapes.small,
      )
      .padding(horizontal = 8.dp, vertical = 2.dp),
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.labelSmall,
      color = color,
      fontWeight = FontWeight.SemiBold,
      fontFamily = FontFamily.Monospace,
    )
  }
}

/**
 * Maximum step indices we render literally inside the badge before collapsing the tail
 * into a "+N more" suffix. Picked so the badge stays inside the 320dp list column without
 * wrapping for typical step counts (single-digit common case, double-digit fits, the
 * occasional 50-step session truncates instead of stretching the row).
 */
internal const val MATCHED_STEPS_BADGE_LIMIT: Int = 5

/**
 * Renders [steps] as a compact badge label like `matched @ 3, 9, 12`. Truncates after
 * [MATCHED_STEPS_BADGE_LIMIT] indices with a `+N more` suffix. Pulled out of the
 * composable so unit tests can pin the exact label format without instantiating Compose.
 *
 * Intentionally tolerates an empty list (returns "matched @ –") rather than throwing —
 * callers should gate on `isNotEmpty` before invoking the composable, but the formatter
 * stays robust so a future call site can rely on it without extra plumbing.
 */
internal fun formatMatchedStepsLabel(steps: List<Int>): String {
  if (steps.isEmpty()) return "matched @ –"
  val shown = steps.take(MATCHED_STEPS_BADGE_LIMIT).joinToString(", ")
  val overflow = steps.size - MATCHED_STEPS_BADGE_LIMIT
  return if (overflow > 0) "matched @ $shown +$overflow more" else "matched @ $shown"
}

@Composable
private fun WaypointDetailPanel(
  item: WaypointDisplayItem,
  matchedSteps: List<Int>,
  modifier: Modifier = Modifier,
) {
  val def = item.definition
  val example = item.example
  val overlays = remember(example, def) {
    example?.let { resolveOverlays(it.tree, def.required, def.forbidden) } ?: emptyList()
  }
  var highlighted by remember(item.definition.id) {
    mutableStateOf<WaypointSelectorOverlay?>(null)
  }

  if (example != null) {
    Row(modifier = modifier.fillMaxSize()) {
      Column(
        modifier = Modifier
          .weight(1f)
          .fillMaxHeight()
          .padding(start = 16.dp, top = 16.dp, end = 8.dp, bottom = 16.dp),
      ) {
        WaypointExamplePanel(
          example = example,
          overlays = overlays,
          highlightedOverlay = highlighted,
        )
      }
      Column(
        modifier = Modifier
          .weight(1.4f)
          .fillMaxHeight()
          .verticalScroll(rememberScrollState())
          .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        WaypointDetailHeader(item, matchedSteps)
        WaypointSelectorSections(
          def = def,
          overlays = overlays,
          highlighted = highlighted,
          onHighlightChange = { highlighted = it },
        )
      }
    }
  } else {
    Column(
      modifier = modifier
        .verticalScroll(rememberScrollState())
        .padding(24.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      WaypointDetailHeader(item, matchedSteps)
      WaypointSelectorSections(
        def = def,
        overlays = emptyList(),
        highlighted = null,
        onHighlightChange = {},
      )
    }
  }
}

/**
 * Renders the "Required" + "Forbidden" selector card stacks for a waypoint. Pulled out
 * of [WaypointDetailPanel] so the example-vs-no-example branches don't have to repeat
 * the two `SelectorEntrySection` calls — a future selector-section parameter addition
 * only needs to land in one place.
 */
@Composable
private fun WaypointSelectorSections(
  def: WaypointDefinition,
  overlays: List<WaypointSelectorOverlay>,
  highlighted: WaypointSelectorOverlay?,
  onHighlightChange: (WaypointSelectorOverlay?) -> Unit,
) {
  SelectorEntrySection(
    title = "Required",
    icon = Icons.Filled.CheckCircle,
    iconTint = WAYPOINT_MATCH_COLOR_OK,
    entries = def.required,
    kind = SelectorEntryKind.REQUIRED,
    overlays = overlays,
    highlighted = highlighted,
    onHighlightChange = onHighlightChange,
  )
  SelectorEntrySection(
    title = "Forbidden",
    icon = Icons.Filled.Block,
    iconTint = MaterialTheme.colorScheme.error,
    entries = def.forbidden,
    kind = SelectorEntryKind.FORBIDDEN,
    overlays = overlays,
    highlighted = highlighted,
    onHighlightChange = onHighlightChange,
  )
}

@Composable
private fun WaypointDetailHeader(item: WaypointDisplayItem, matchedSteps: List<Int>) {
  val def = item.definition
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    SelectableText(
      text = def.id,
      style = MaterialTheme.typography.headlineSmall,
      fontWeight = FontWeight.Bold,
    )
    if (!def.description.isNullOrBlank()) {
      SelectableText(
        text = def.description!!,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    if (!item.sourceLabel.isNullOrBlank()) {
      SelectableText(
        text = "source: ${item.sourceLabel}",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.outline,
      )
    }
    if (matchedSteps.isNotEmpty()) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        MatchedStepsBadge(matchedSteps)
      }
    }
  }
}

private enum class SelectorEntryKind { REQUIRED, FORBIDDEN }

private fun SelectorEntryKind.toOverlayKind(): WaypointSelectorKind = when (this) {
  SelectorEntryKind.REQUIRED -> WaypointSelectorKind.REQUIRED
  SelectorEntryKind.FORBIDDEN -> WaypointSelectorKind.FORBIDDEN
}

@Composable
private fun SelectorEntrySection(
  title: String,
  icon: ImageVector,
  iconTint: Color,
  entries: List<WaypointSelectorEntry>,
  kind: SelectorEntryKind,
  overlays: List<WaypointSelectorOverlay>,
  highlighted: WaypointSelectorOverlay?,
  onHighlightChange: (WaypointSelectorOverlay?) -> Unit,
) {
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Icon(icon, contentDescription = null, tint = iconTint)
      Spacer(Modifier.width(8.dp))
      Text(
        text = "$title (${entries.size})",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
      )
    }
    if (entries.isEmpty()) {
      SelectableText(
        text = "(none)",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    } else {
      val overlayKind = kind.toOverlayKind()
      entries.forEachIndexed { index, entry ->
        val overlay = overlays.firstOrNull {
          it.kind == overlayKind && it.entryIndex == index
        }
        val isHighlighted = highlighted != null &&
          highlighted.kind == overlayKind &&
          highlighted.entryIndex == index
        SelectorEntryCard(
          index = index + 1,
          entry = entry,
          kind = kind,
          overlay = overlay,
          isHighlighted = isHighlighted,
          onClick = if (overlay != null) {
            { onHighlightChange(if (isHighlighted) null else overlay) }
          } else null,
        )
      }
    }
  }
}

@Composable
private fun SelectorEntryCard(
  index: Int,
  entry: WaypointSelectorEntry,
  kind: SelectorEntryKind,
  overlay: WaypointSelectorOverlay?,
  isHighlighted: Boolean,
  onClick: (() -> Unit)?,
) {
  val baseContainer = when (kind) {
    SelectorEntryKind.REQUIRED -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    SelectorEntryKind.FORBIDDEN -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
  }
  val container = if (isHighlighted) {
    MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
  } else {
    baseContainer
  }
  val cardModifier = Modifier
    .fillMaxWidth()
    .let { if (onClick != null) it.clickable(onClick = onClick) else it }
  Card(
    modifier = cardModifier,
    colors = CardDefaults.cardColors(containerColor = container),
  ) {
    Column(
      modifier = Modifier.padding(12.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Text(
          text = "#$index",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.outline,
          fontWeight = FontWeight.Medium,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
          if (overlay != null) {
            MatchStatusBadge(overlay = overlay)
            Spacer(Modifier.width(6.dp))
          }
          DriverBadge(entry.selector)
          if (kind == SelectorEntryKind.REQUIRED && entry.minCount > 1) {
            Spacer(Modifier.width(6.dp))
            WaypointCountChip(label = "min", count = entry.minCount, accent = false)
          }
        }
      }
      val description = entry.description?.takeIf { it.isNotBlank() }
        ?: entry.selector.description().ifBlank { "(no description)" }
      SelectableText(
        text = description,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Medium,
      )
      SelectorBody(entry.selector)
    }
  }
}

@Composable
private fun MatchStatusBadge(overlay: WaypointSelectorOverlay) {
  // Pure label/color resolution lives in WaypointExamplePanel.kt so it can be tested
  // without a Compose harness — see `matchStatusBadgeStyle`.
  val (label, color) = matchStatusBadgeStyle(overlay)
  Box(
    modifier = Modifier
      .background(color = color.copy(alpha = 0.18f), shape = MaterialTheme.shapes.small)
      .padding(horizontal = 8.dp, vertical = 2.dp),
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.labelSmall,
      color = color,
      fontWeight = FontWeight.Bold,
      fontFamily = FontFamily.Monospace,
    )
  }
}

@Composable
private fun DriverBadge(selector: TrailblazeNodeSelector) {
  val label = when (selector.driverMatch) {
    is DriverNodeMatch.AndroidAccessibility -> "android-a11y"
    is DriverNodeMatch.AndroidMaestro -> "android-maestro"
    is DriverNodeMatch.IosMaestro -> "ios-maestro"
    is DriverNodeMatch.IosAxe -> "ios-axe"
    is DriverNodeMatch.Web -> "web"
    is DriverNodeMatch.Compose -> "compose"
    null -> "structural"
  }
  Box(
    modifier = Modifier
      .background(
        color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.15f),
        shape = MaterialTheme.shapes.small,
      )
      .padding(horizontal = 8.dp, vertical = 2.dp),
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.labelSmall,
      fontFamily = FontFamily.Monospace,
      color = MaterialTheme.colorScheme.tertiary,
    )
  }
}

@Composable
private fun SelectorBody(selector: TrailblazeNodeSelector) {
  val rows = remember(selector) { selector.toFieldRows() }
  if (rows.isEmpty()) {
    SelectableText(
      text = "(empty selector)",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    return
  }
  Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
    rows.forEach { (label, value) ->
      Row {
        Text(
          text = "$label:",
          style = MaterialTheme.typography.bodySmall,
          fontFamily = FontFamily.Monospace,
          color = MaterialTheme.colorScheme.primary,
          fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.width(6.dp))
        SelectableText(
          text = value,
          style = MaterialTheme.typography.bodySmall,
        )
      }
    }
  }
}

private fun TrailblazeNodeSelector.toFieldRows(): List<Pair<String, String>> {
  val rows = mutableListOf<Pair<String, String>>()
  when (val match = driverMatch) {
    is DriverNodeMatch.AndroidAccessibility -> {
      match.classNameRegex?.let { rows += "className~" to it }
      match.resourceIdRegex?.let { rows += "resourceId~" to it }
      match.uniqueId?.let { rows += "uniqueId" to it }
      match.textRegex?.let { rows += "text~" to it }
      match.contentDescriptionRegex?.let { rows += "contentDescription~" to it }
      match.hintTextRegex?.let { rows += "hintText~" to it }
      match.labeledByTextRegex?.let { rows += "labeledByText~" to it }
      match.stateDescriptionRegex?.let { rows += "stateDescription~" to it }
      match.paneTitleRegex?.let { rows += "paneTitle~" to it }
      match.isEnabled?.let { rows += "isEnabled" to it.toString() }
      match.isClickable?.let { rows += "isClickable" to it.toString() }
      match.isCheckable?.let { rows += "isCheckable" to it.toString() }
      match.isChecked?.let { rows += "isChecked" to it.toString() }
      match.isSelected?.let { rows += "isSelected" to it.toString() }
      match.isFocused?.let { rows += "isFocused" to it.toString() }
      match.isEditable?.let { rows += "isEditable" to it.toString() }
      match.isScrollable?.let { rows += "isScrollable" to it.toString() }
      match.isPassword?.let { rows += "isPassword" to it.toString() }
      match.isHeading?.let { rows += "isHeading" to it.toString() }
      match.isMultiLine?.let { rows += "isMultiLine" to it.toString() }
      match.inputType?.let { rows += "inputType" to it.toString() }
      match.collectionItemRowIndex?.let { rows += "collectionItemRowIndex" to it.toString() }
      match.collectionItemColumnIndex?.let { rows += "collectionItemColumnIndex" to it.toString() }
    }
    is DriverNodeMatch.AndroidMaestro -> {
      match.textRegex?.let { rows += "text~" to it }
      match.resourceIdRegex?.let { rows += "resourceId~" to it }
      match.accessibilityTextRegex?.let { rows += "accessibilityText~" to it }
      match.classNameRegex?.let { rows += "className~" to it }
      match.hintTextRegex?.let { rows += "hintText~" to it }
      match.clickable?.let { rows += "clickable" to it.toString() }
      match.enabled?.let { rows += "enabled" to it.toString() }
      match.focused?.let { rows += "focused" to it.toString() }
      match.checked?.let { rows += "checked" to it.toString() }
      match.selected?.let { rows += "selected" to it.toString() }
    }
    is DriverNodeMatch.Web -> {
      match.ariaRole?.let { rows += "ariaRole" to it }
      match.ariaNameRegex?.let { rows += "ariaName~" to it }
      match.ariaDescriptorRegex?.let { rows += "ariaDescriptor~" to it }
      match.headingLevel?.let { rows += "headingLevel" to it.toString() }
      match.cssSelector?.let { rows += "cssSelector" to it }
      match.dataTestId?.let { rows += "dataTestId" to it }
      match.nthIndex?.let { rows += "nthIndex" to it.toString() }
    }
    is DriverNodeMatch.Compose -> {
      match.testTag?.let { rows += "testTag" to it }
      match.role?.let { rows += "role" to it }
      match.textRegex?.let { rows += "text~" to it }
      match.editableTextRegex?.let { rows += "editableText~" to it }
      match.contentDescriptionRegex?.let { rows += "contentDescription~" to it }
      match.toggleableState?.let { rows += "toggleableState" to it }
      match.isEnabled?.let { rows += "isEnabled" to it.toString() }
      match.isFocused?.let { rows += "isFocused" to it.toString() }
      match.isSelected?.let { rows += "isSelected" to it.toString() }
      match.isPassword?.let { rows += "isPassword" to it.toString() }
    }
    is DriverNodeMatch.IosMaestro -> {
      match.textRegex?.let { rows += "text~" to it }
      match.resourceIdRegex?.let { rows += "resourceId~" to it }
      match.accessibilityTextRegex?.let { rows += "accessibilityText~" to it }
      match.classNameRegex?.let { rows += "className~" to it }
      match.hintTextRegex?.let { rows += "hintText~" to it }
      match.focused?.let { rows += "focused" to it.toString() }
      match.selected?.let { rows += "selected" to it.toString() }
    }
    is DriverNodeMatch.IosAxe -> {
      match.roleRegex?.let { rows += "role~" to it }
      match.subroleRegex?.let { rows += "subrole~" to it }
      match.labelRegex?.let { rows += "label~" to it }
      match.valueRegex?.let { rows += "value~" to it }
      match.uniqueId?.let { rows += "uniqueId" to it }
      match.typeRegex?.let { rows += "type~" to it }
      match.titleRegex?.let { rows += "title~" to it }
      match.customAction?.let { rows += "customAction" to it }
      match.enabled?.let { rows += "enabled" to it.toString() }
    }
    null -> Unit
  }
  index?.let { rows += "index" to it.toString() }
  below?.let { rows += "below" to it.description() }
  above?.let { rows += "above" to it.description() }
  leftOf?.let { rows += "leftOf" to it.description() }
  rightOf?.let { rows += "rightOf" to it.description() }
  childOf?.let { rows += "childOf" to it.description() }
  containsChild?.let { rows += "containsChild" to it.description() }
  containsDescendants?.takeIf { it.isNotEmpty() }?.let { descs ->
    rows += "containsDescendants" to descs.joinToString("; ") { it.description() }
  }
  return rows
}

/**
 * Pulls the second segment from a slash-separated id (e.g. `"myapp/android/splash"` →
 * `"android"`). Returns null for ids with fewer than three segments — the platform
 * dropdown only makes sense for three-part `<target>/<platform>/<local-name>` ids, not
 * two-part `<pack>/<local-name>` shapes like `clock/alarm-tab`.
 *
 * `internal` so the unit tests in `:trailblaze-ui` jvmTest can pin the parsing rules
 * without going through a Compose harness.
 */
internal fun WaypointDefinition.platformSegmentOrNull(): String? {
  val parts = id.split('/')
  return if (parts.size >= 3) parts[1] else null
}
