package xyz.block.trailblaze.ui.tabs.session

import xyz.block.trailblaze.util.Console
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.toMutableStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Default expanded height of the session logs panel. */
private val DEFAULT_PANEL_HEIGHT = 300.dp

/** Smallest the panel can shrink to while still showing a row or two of logs. */
private val MIN_PANEL_HEIGHT = 120.dp

/** Largest the panel can grow to via the drag handle. */
private val MAX_PANEL_HEIGHT = 1200.dp

/**
 * Internal pairing of a [SessionLogSource] with its non-null/non-blank content, used by
 * the panel after filtering out [SessionLogEntry]s with no data. Kept private so the
 * public API surface stays as [SessionLogEntry] (which permits null content).
 */
private data class AvailableEntry(val source: SessionLogSource, val content: String)

/**
 * Collapsible, timeline-synced log panel hosted at the bottom of a session view.
 * Takes a list of [SessionLogEntry] (each pairing a [SessionLogSource] with the raw
 * content for that session) and renders one entry at a time. When more than one entry
 * has content, the header gains a tab strip so the user can switch between them (e.g.
 * Device Logs ↔ Network). Entries whose content is null/blank are filtered out of the
 * strip so an empty tab never appears.
 *
 * Features:
 * - Auto-scrolls to match the current timeline timestamp
 * - Filter box for searching logs (case-insensitive)
 * - Toggleable timestamp column
 * - Multi-line text selection
 * - Log-level color bar + level filter chips (only shown for sources that declare them)
 * - Highlights log lines within the active event's time range
 * - Virtualized list (LazyColumn) for performance with large files
 * - Resize handle at the top to grow/shrink the panel
 */
@Composable
fun SessionLogsPanel(
  entries: List<SessionLogEntry>,
  currentTimestampMs: Long,
  sessionStartMs: Long,
  sessionEndMs: Long = 0L,
  activeEventStartMs: Long? = null,
  activeEventEndMs: Long? = null,
  modifier: Modifier = Modifier,
) {
  // Only entries with content for this session get a tab. With nothing to show, the panel
  // disappears entirely so completed sessions without device or network capture don't
  // render an empty bar at the bottom.
  val available: List<AvailableEntry> = remember(entries) {
    entries.mapNotNull { entry ->
      val content = entry.rawContent
      if (content.isNullOrBlank()) null else AvailableEntry(entry.source, content)
    }
  }
  if (available.isEmpty()) return

  var isExpanded by remember { mutableStateOf(false) }
  var panelHeight by remember { mutableStateOf(DEFAULT_PANEL_HEIGHT) }
  var filterText by remember { mutableStateOf("") }
  var showTimestamps by remember { mutableStateOf(true) }
  val density = LocalDensity.current
  val enabledLevels: SnapshotStateMap<LogLevel, Boolean> = remember {
    LogLevel.entries.map { it to true }.toMutableStateMap()
  }

  // Pick the first available source on the initial render and stick with it unless that
  // source disappears (e.g., a live session's network.ndjson appears partway through).
  // Using the source id as the key means a re-ordering of `sources` doesn't reset selection.
  var selectedSourceId by remember { mutableStateOf(available.first().source.id) }
  val activeEntry = available.firstOrNull { it.source.id == selectedSourceId } ?: available.first()
  // Reconcile selection in an effect rather than during composition — Compose disallows
  // state writes during composition, and doing it inline can trigger recomposition loops.
  // The fallback above keeps this composition coherent; the effect updates the stored
  // selection on the next frame so subsequent recompositions see the new id.
  LaunchedEffect(available, selectedSourceId) {
    if (available.none { it.source.id == selectedSourceId }) {
      selectedSourceId = available.first().source.id
    }
  }
  val source: SessionLogSource = activeEntry.source
  val rawLogContent: String = activeEntry.content

  val parsed = remember(source, rawLogContent) { source.parse(rawLogContent) }
  val allParsedLines = parsed.lines
  val totalLineCount = parsed.totalRawLineCount
  val truncated = parsed.truncated
  val malformedCount = parsed.malformedLineCount

  // Sources without filterable levels (e.g. NetworkLogSource) hide the V/D/I/W/E/F chips,
  // so the user has no UI to clear a stale level toggle from a previous source. Bypass
  // level filtering entirely for those sources — otherwise a disabled INFO chip on Device
  // Logs would silently hide most/all rows after switching to Network with no recourse.
  val applyLevelFilter = source.filterableLevels.isNotEmpty()
  val displayLines by
    remember(allParsedLines, filterText, enabledLevels, applyLevelFilter) {
      derivedStateOf {
        allParsedLines.filter { parsed ->
          (!applyLevelFilter || enabledLevels[parsed.level] != false) &&
            (filterText.isBlank() || parsed.raw.contains(filterText, ignoreCase = true))
        }
      }
    }

  val listState = rememberLazyListState()
  val horizontalScrollState = rememberScrollState()

  // Offset between device clock and host clock, used to map host timestamps to device timestamps.
  // When no log line carries a parseable timestamp, fall back to a 0L offset and surface a one-shot
  // log so the developer knows why the panel won't follow the timeline.
  val deviceClockOffsetMs =
    remember(allParsedLines, sessionStartMs) {
      val firstDeviceMs = allParsedLines.firstNotNullOfOrNull { it.epochMs }
      if (firstDeviceMs != null && sessionStartMs > 0) {
        firstDeviceMs - sessionStartMs
      } else {
        if (allParsedLines.isNotEmpty() && firstDeviceMs == null) {
          Console.log(
            "${source.displayName}: no parseable timestamps in ${allParsedLines.size} lines — " +
              "timeline sync disabled (lines will not auto-scroll or highlight).",
          )
        }
        0L
      }
    }
  val timestampsAvailable = allParsedLines.any { it.epochMs != null }

  // Auto-scroll to match timeline position (only when not filtering).
  LaunchedEffect(currentTimestampMs, isExpanded, filterText) {
    if (!isExpanded || displayLines.isEmpty() || filterText.isNotBlank()) return@LaunchedEffect
    val deviceCurrentMs = currentTimestampMs + deviceClockOffsetMs
    // Find nearest log line by device timestamp
    var targetIndex = -1
    for ((index, parsed) in displayLines.withIndex()) {
      val epoch = parsed.epochMs ?: continue
      if (epoch <= deviceCurrentMs) targetIndex = index else break
    }
    if (targetIndex >= 0) {
      listState.animateScrollToItem(targetIndex)
    } else if (sessionEndMs > sessionStartMs) {
      // Proportional fallback using host-side session fraction
      val fraction =
        ((currentTimestampMs - sessionStartMs).toFloat() / (sessionEndMs - sessionStartMs))
          .coerceIn(0f, 1f)
      listState.animateScrollToItem(
        (fraction * (displayLines.size - 1)).toInt().coerceAtLeast(0)
      )
    }
  }

  // Map active event range to device clock for highlighting
  val highlightStartMs = activeEventStartMs?.let { it + deviceClockOffsetMs }
  val highlightEndMs = activeEventEndMs?.let { it + deviceClockOffsetMs }
  val highlightColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
  val zebraColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.03f)

  Column(modifier = modifier.fillMaxWidth()) {
    HorizontalDivider()

    // Header bar \u2014 single source: just the display name; multi-source: tab strip.
    Row(
      modifier =
        Modifier.fillMaxWidth()
          .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
          .padding(horizontal = 12.dp, vertical = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      // Expand/collapse chevron \u2014 clicking just the chevron toggles, so clicking a tab
      // doesn't unexpectedly collapse the panel mid-switch.
      Icon(
        imageVector = if (isExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
        contentDescription = if (isExpanded) "Collapse" else "Expand",
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.clickable { isExpanded = !isExpanded },
      )
      Spacer(modifier = Modifier.width(8.dp))
      if (available.size > 1) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          available.forEach { entry ->
            val isSelected = entry.source.id == selectedSourceId
            Box(
              modifier =
                Modifier.padding(end = 4.dp)
                  .clip(RoundedCornerShape(4.dp))
                  .background(
                    if (isSelected) {
                      MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                    } else {
                      Color.Transparent
                    },
                  )
                  .clickable {
                    selectedSourceId = entry.source.id
                    if (!isExpanded) isExpanded = true
                  }
                  .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
              Text(
                text = entry.source.displayName,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                color =
                  if (isSelected) {
                    MaterialTheme.colorScheme.primary
                  } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                  },
              )
            }
          }
        }
      } else {
        Text(
          text = source.displayName,
          style = MaterialTheme.typography.labelLarge,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.clickable { isExpanded = !isExpanded },
        )
      }
      Spacer(modifier = Modifier.width(8.dp))
      Text(
        text =
          buildString {
            append("$totalLineCount lines")
            if (truncated) append(" (showing last ${allParsedLines.size})")
            val filtered = displayLines.size != allParsedLines.size
            if (filtered) append(" \u2022 ${displayLines.size} shown")
            if (allParsedLines.isNotEmpty() && !timestampsAvailable) {
              append(" \u2022 no timestamps")
            }
            // Surface parse failures so 100 captured / 30 unparseable doesn't present as
            // silently missing data \u2014 pairs with NetworkLogSource's per-line Console log.
            if (malformedCount > 0) append(" \u2022 $malformedCount unparseable")
          },
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        modifier = Modifier.clickable { isExpanded = !isExpanded },
      )
    }

    // Collapsible content
    AnimatedVisibility(
      visible = isExpanded,
      enter = expandVertically(),
      exit = shrinkVertically(),
    ) {
      Column(modifier = Modifier.fillMaxWidth()) {
        // Drag handle: drag up to grow the panel, down to shrink. Dragging up gives a
        // negative delta (y-axis points down), so we subtract the delta to grow the panel.
        ResizeHandle(
          onDrag = { deltaPx ->
            val deltaDp = with(density) { deltaPx.toDp() }
            panelHeight = (panelHeight - deltaDp).coerceIn(MIN_PANEL_HEIGHT, MAX_PANEL_HEIGHT)
          },
        )
      Column(
        modifier =
          Modifier.fillMaxWidth()
            .height(panelHeight)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f)),
      ) {
        // Filter bar with timestamp toggle
        Row(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Icon(
            imageVector = Icons.Filled.FilterList,
            contentDescription = "Filter",
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
          )
          Spacer(modifier = Modifier.width(4.dp))
          OutlinedTextField(
            value = filterText,
            onValueChange = { filterText = it },
            placeholder = {
              Text("Filter logs...", style = MaterialTheme.typography.bodySmall)
            },
            modifier = Modifier.weight(1f).heightIn(max = 32.dp),
            textStyle =
              MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
              ),
            singleLine = true,
            colors =
              OutlinedTextFieldDefaults.colors(
                unfocusedBorderColor =
                  MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
              ),
          )
          Spacer(modifier = Modifier.width(4.dp))
          // Timestamp column toggle
          IconButton(
            onClick = { showTimestamps = !showTimestamps },
            modifier = Modifier.size(24.dp),
          ) {
            Icon(
              imageVector = Icons.Filled.Timer,
              contentDescription =
                if (showTimestamps) "Hide timestamps" else "Show timestamps",
              modifier = Modifier.size(14.dp),
              tint =
                if (showTimestamps) {
                  MaterialTheme.colorScheme.primary
                } else {
                  MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                },
            )
          }
        }

        // Log level filter chips — only show levels the source declares as filterable
        // AND are actually present in this session's data.
        val presentLevels =
          remember(source, allParsedLines) {
            if (source.filterableLevels.isEmpty()) {
              emptyList()
            } else {
              val found = allParsedLines.mapTo(mutableSetOf()) { it.level }
              source.filterableLevels.filter { it in found }
            }
          }
        if (presentLevels.isNotEmpty()) {
        Row(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          presentLevels.forEach { level ->
            val enabled = enabledLevels[level] != false
            val chipShape = RoundedCornerShape(4.dp)
            Box(
              modifier =
                Modifier.padding(end = 4.dp)
                  .clip(chipShape)
                  .background(
                    if (enabled) level.color.copy(alpha = 0.2f) else Color.Transparent,
                  )
                  .border(
                    width = 1.dp,
                    color =
                      if (enabled) level.color.copy(alpha = 0.5f)
                      else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                    shape = chipShape,
                  )
                  .clickable { enabledLevels[level] = !enabled }
                  .padding(horizontal = 6.dp, vertical = 2.dp),
              contentAlignment = Alignment.Center,
            ) {
              Text(
                text = level.label,
                style =
                  MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                  ),
                color =
                  if (enabled) level.color
                  else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                textAlign = TextAlign.Center,
              )
            }
          }
        }
        }

        // Virtualized log lines. SelectionContainer wraps the whole list so click-and-drag
        // selection spans multiple lines (Compose Multiplatform ≥ 1.6 handles LazyColumn
        // recycling correctly inside SelectionContainer).
        SelectionContainer(modifier = Modifier.fillMaxWidth().weight(1f)) {
          LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().horizontalScroll(horizontalScrollState),
          ) {
            itemsIndexed(displayLines) { index, parsed ->
              val isHighlighted =
                highlightStartMs != null &&
                  highlightEndMs != null &&
                  parsed.epochMs != null &&
                  parsed.epochMs in highlightStartMs..highlightEndMs
              val rowBg =
                when {
                  isHighlighted -> highlightColor
                  index % 2 == 1 -> zebraColor
                  else -> Color.Transparent
                }

              Row(
                modifier = Modifier.background(rowBg).padding(vertical = 0.5.dp),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                // Log level color bar — exclude from selection so copy doesn't pick up junk.
                DisableSelection {
                  Box(
                    modifier =
                      Modifier.width(3.dp)
                        .height(14.dp)
                        .background(parsed.level.color.copy(alpha = 0.8f)),
                  )
                  Spacer(modifier = Modifier.width(4.dp))
                }
                if (showTimestamps && parsed.timestampDisplay != null) {
                  Text(
                    text = parsed.timestampDisplay,
                    style =
                      MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        lineHeight = 14.sp,
                      ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.padding(end = 6.dp),
                  )
                }
                Text(
                  text = parsed.content,
                  style =
                    MaterialTheme.typography.bodySmall.copy(
                      fontFamily = FontFamily.Monospace,
                      fontSize = 11.sp,
                      lineHeight = 14.sp,
                    ),
                  color = parsed.level.textColor(),
                  maxLines = 1,
                  softWrap = false,
                )
              }
            }
          }
        }
      }
      }
    }
  }
}

/**
 * A 6dp-tall horizontal "grip" that the user can drag vertically to resize the device logs panel.
 * Reports raw pixel deltas; the caller converts to Dp and applies bounds.
 */
@Composable
private fun ResizeHandle(onDrag: (Float) -> Unit) {
  val draggableState = rememberDraggableState(onDelta = onDrag)
  Box(
    modifier =
      Modifier.fillMaxWidth()
        .height(6.dp)
        .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        .draggable(
          state = draggableState,
          orientation = Orientation.Vertical,
        ),
    contentAlignment = Alignment.Center,
  ) {
    // Subtle visual grip (a short bar) so the handle is discoverable.
    Box(
      modifier =
        Modifier.width(40.dp)
          .height(2.dp)
          .clip(RoundedCornerShape(1.dp))
          .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)),
    )
  }
}

/** Text color for each log level — errors are vivid, debug/verbose are dimmed. */
@Composable
private fun LogLevel.textColor(): Color =
  when (this) {
    LogLevel.ERROR,
    LogLevel.FATAL -> MaterialTheme.colorScheme.error
    LogLevel.WARN -> Color(0xFFFFA726)
    LogLevel.VERBOSE -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
    else -> MaterialTheme.colorScheme.onSurface
  }

