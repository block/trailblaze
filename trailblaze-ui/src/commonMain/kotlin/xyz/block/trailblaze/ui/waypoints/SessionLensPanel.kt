package xyz.block.trailblaze.ui.waypoints

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.SessionInfo
import xyz.block.trailblaze.ui.composables.SelectableText

/**
 * Renders the "session lens" surface for the Waypoints tab: a collapsible card that
 * overlays *observed* segments (raw transitions extracted from a single session log
 * directory) onto the waypoint set.
 *
 * v1 is intentionally a list view — the graph view ships in a follow-up.
 *
 * ## Session selection
 *
 * The panel reads its session list from the same `LogsRepo`-backed flow the Sessions tab
 * already uses (`SessionInfo` from `trailblaze-models` commonMain) — there's no
 * filesystem picker. The user picks from a dropdown of recorded sessions, the JVM
 * wrapper resolves the chosen [SessionId] to a directory via `LogsRepo.getSessionDir`,
 * and the extractor runs against that directory.
 *
 * Loading and file I/O live in the JVM tab wrapper; this composable just renders the
 * three states ([state]):
 *  - **Idle**: no session selected — dropdown is empty, no body content.
 *  - **Loading**: extractor is running on a background dispatcher.
 *  - **Loaded**: the [SessionLensResult] is in hand; render counts, diagnostics, segments.
 *
 * The dropdown's choices come from [availableSessions] — the same flow that powers the
 * Sessions tab. Sorted "most recent first" by the JVM wrapper before passing in, so the
 * panel doesn't re-sort.
 */
@Composable
fun SessionLensPanel(
  state: SessionLensState,
  availableSessions: List<SessionInfo>,
  selectedSessionId: SessionId?,
  onSessionSelected: (SessionId?) -> Unit,
  modifier: Modifier = Modifier,
) {
  // Auto-expand when a session transitions out of Idle (so the user sees the payload
  // without an extra click) and auto-collapse when it transitions back to Idle (so the
  // tab stays compact when no session is set). Keying on `is Idle` rather than the full
  // state means a user manual collapse during Loading→Loaded sticks, which feels right.
  var expanded by remember(state is SessionLensState.Idle) {
    mutableStateOf(state !is SessionLensState.Idle)
  }
  // Resizable body height for the segments list when state is Loaded. The drag handle
  // at the top of the card adjusts this; the LazyColumn inside the loaded body honors
  // it. Persisted across state transitions so the user's preferred size sticks while
  // they switch between sessions.
  var bodyHeight by remember { mutableStateOf(DEFAULT_BODY_HEIGHT) }

  Card(
    modifier = modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(
      containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
    ),
  ) {
    // Drag handle pill at the very top of the card — above the header / chevron — so it
    // reads as the resize affordance. Visible only when the panel is expanded; collapsing
    // the panel implicitly resets the visual to "no draggable surface here". A separate
    // divider between header and body is intentionally absent — that line was being
    // misread as a non-functional drag handle in the middle of the card.
    if (expanded) {
      DragHandle(
        onDragDelta = { deltaPx ->
          // Increment the desired body height by the gesture delta. `deltaPx` is in
          // pixels; LocalDensity isn't available here without taking a Composable
          // dependency, so approximate via 1px ≈ 1dp on typical Hi-DPI displays. The
          // Dp-vs-Px mismatch is noticeable on extreme zoom but the clamped range
          // keeps the result usable either way; precise scaling can land later.
          val target = (bodyHeight.value + deltaPx).coerceIn(
            MIN_BODY_HEIGHT.value,
            MAX_BODY_HEIGHT.value,
          )
          bodyHeight = target.dp
        },
      )
    }
    SessionLensHeader(
      state = state,
      expanded = expanded,
      onToggleExpanded = { expanded = !expanded },
      availableSessions = availableSessions,
      selectedSessionId = selectedSessionId,
      onSessionSelected = onSessionSelected,
    )
    if (expanded) {
      when (state) {
        SessionLensState.Idle -> SessionLensIdleBody(availableSessions.size)
        is SessionLensState.Loading -> SessionLensLoadingBody(state.sessionLabel)
        is SessionLensState.Loaded -> SessionLensLoadedBody(state.result, bodyHeight)
      }
    }
  }
}

/**
 * Default / min / max heights for the resizable Loaded-state body. Bounds keep a
 * stuck-mouse drag from making the panel disappear or eat the whole tab; the default
 * matches the prior fixed 360dp cap so users who don't drag get the same layout as
 * before.
 */
private val DEFAULT_BODY_HEIGHT = 360.dp
private val MIN_BODY_HEIGHT = 120.dp
private val MAX_BODY_HEIGHT = 720.dp

/**
 * Material 3 BottomSheet-style drag handle at the top of the card: a small horizontal
 * pill, centered, that captures vertical drag gestures and forwards the delta to
 * [onDragDelta]. Visually communicates "you can grab here to resize" and *actually*
 * does so — replacing the previous middle-of-card HorizontalDivider that read as a
 * handle but wasn't draggable.
 */
@Composable
private fun DragHandle(onDragDelta: (Float) -> Unit) {
  val draggableState = rememberDraggableState { delta -> onDragDelta(delta) }
  Box(
    modifier = Modifier
      .fillMaxWidth()
      .padding(vertical = 6.dp)
      .draggable(
        state = draggableState,
        orientation = Orientation.Vertical,
      ),
    contentAlignment = Alignment.Center,
  ) {
    Box(
      modifier = Modifier
        .size(width = 36.dp, height = 4.dp)
        .background(
          color = MaterialTheme.colorScheme.outline,
          shape = RoundedCornerShape(2.dp),
        ),
    )
  }
}

/**
 * State machine for the panel. Kept as a sealed interface so the JVM wrapper can hand
 * us a typed value rather than a tuple of nullables, and so future states (e.g. an
 * `Error(message)` branch for IOException from the extractor) can land additively.
 *
 * [SessionLensState.Loading] and [SessionLensState.Loaded] both carry a
 * `sessionLabel` / `result.sessionPath` for the header subtitle so users see *which*
 * session they're looking at while it loads, not just "loading…".
 */
sealed interface SessionLensState {
  data object Idle : SessionLensState
  data class Loading(val sessionLabel: String) : SessionLensState
  data class Loaded(val result: SessionLensResult) : SessionLensState
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionLensHeader(
  state: SessionLensState,
  expanded: Boolean,
  onToggleExpanded: () -> Unit,
  availableSessions: List<SessionInfo>,
  selectedSessionId: SessionId?,
  onSessionSelected: (SessionId?) -> Unit,
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 12.dp, vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(
      Icons.Filled.PlayCircleOutline,
      contentDescription = null,
      tint = MaterialTheme.colorScheme.primary,
      modifier = Modifier.padding(end = 8.dp),
    )
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = "Session lens",
        style = MaterialTheme.typography.titleSmall,
      )
      val subtitle = when (state) {
        SessionLensState.Idle -> if (availableSessions.isEmpty()) {
          "No recorded sessions available — run a `blaze` first to populate."
        } else {
          "Pick a session to overlay observed segments onto the waypoint set."
        }
        is SessionLensState.Loading -> "Loading ${state.sessionLabel}…"
        is SessionLensState.Loaded -> sessionSubtitle(state.result)
      }
      SelectableText(
        text = subtitle,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    SessionPickerDropdown(
      sessions = availableSessions,
      selectedSessionId = selectedSessionId,
      onSessionSelected = onSessionSelected,
    )
    if (state !is SessionLensState.Idle) {
      Spacer(Modifier.width(8.dp))
      TextButton(onClick = { onSessionSelected(null) }) { Text("Clear") }
    }
    Spacer(Modifier.width(4.dp))
    IconButton(onClick = onToggleExpanded) {
      Icon(
        if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
        contentDescription = if (expanded) "Collapse" else "Expand",
      )
    }
  }
}

/**
 * Bound on how many sessions we render in the dropdown. Recorded session lists can grow
 * into the hundreds; rendering them all balloons the menu and makes the recent ones
 * harder to find. The wrapper passes them recent-first, so the cap effectively shows
 * "the last 50 runs" — which is what users want for "look at observed segments in
 * something I just ran."
 */
private const val SESSION_DROPDOWN_LIMIT = 50

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionPickerDropdown(
  sessions: List<SessionInfo>,
  selectedSessionId: SessionId?,
  onSessionSelected: (SessionId?) -> Unit,
) {
  var expanded by remember { mutableStateOf(false) }
  val visibleSessions = remember(sessions) { sessions.take(SESSION_DROPDOWN_LIMIT) }
  val selected = remember(sessions, selectedSessionId) {
    sessions.firstOrNull { it.sessionId == selectedSessionId }
  }
  // Disable the menu instead of hiding it when no sessions are available — that way the
  // dropdown is consistently in the same place and the disabled affordance + subtitle
  // tell users *why* they can't pick.
  val enabled = sessions.isNotEmpty()
  val labelInField = selected?.let(::dropdownItemLabel) ?: if (enabled) "Pick session" else "No sessions"

  val truncatedCount = sessions.size - visibleSessions.size
  ExposedDropdownMenuBox(
    expanded = expanded && enabled,
    onExpandedChange = { if (enabled) expanded = !expanded },
    modifier = Modifier.width(260.dp),
  ) {
    OutlinedTextField(
      value = labelInField,
      onValueChange = { /* readonly — selection happens via DropdownMenuItem clicks */ },
      readOnly = true,
      enabled = enabled,
      singleLine = true,
      label = { Text("Session") },
      trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded && enabled) },
      modifier = Modifier
        // Compose 1.x material3 menuAnchor; the deprecation warning is repo-wide and
        // not worth chasing here.
        .menuAnchor()
        .fillMaxWidth(),
    )
    ExposedDropdownMenu(
      expanded = expanded && enabled,
      onDismissRequest = { expanded = false },
    ) {
      visibleSessions.forEach { session ->
        DropdownMenuItem(
          text = {
            Column {
              Text(
                text = dropdownItemLabel(session),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
              )
              Text(
                text = "${session.sessionId.value} · ${formatStatus(session.latestStatus)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          },
          onClick = {
            onSessionSelected(session.sessionId)
            expanded = false
          },
        )
      }
      if (truncatedCount > 0) {
        // Synthetic non-clickable footer flagging that the list is capped — keeps
        // long histories from overrunning the dropdown without hiding the fact.
        Text(
          text = "…and $truncatedCount older session(s) hidden",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
      }
    }
  }
}

private fun dropdownItemLabel(session: SessionInfo): String {
  // Display name is the trail title / file path / class:method / id fallback. Combine
  // with a millisecond timestamp suffix so two runs of the same trail are
  // distinguishable in the dropdown.
  val ts = session.timestamp.toEpochMilliseconds()
  return "${session.displayName} · $ts"
}

private fun formatStatus(status: xyz.block.trailblaze.logs.model.SessionStatus): String =
  status::class.simpleName ?: "Unknown"

/**
 * Concise, scannable subtitle for the loaded state. Shows the segment count when
 * positive, otherwise the most-actionable count (steps matched / parse failures /
 * ambiguous) so the user sees *why* there are no segments without expanding the body.
 */
private fun sessionSubtitle(result: SessionLensResult): String {
  val core = "${result.segments.size} segment(s) · ${result.totalRequestLogs} request log(s) · ${result.stepsWithMatchedWaypoint} matched"
  val tail = buildList {
    if (result.parseFailures > 0) add("${result.parseFailures} skipped")
    if (result.stepsWithAmbiguousMatch > 0) add("${result.stepsWithAmbiguousMatch} ambiguous")
  }
  return if (tail.isEmpty()) core else "$core · ${tail.joinToString(" · ")}"
}

@Composable
private fun SessionLensIdleBody(availableSessionCount: Int) {
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .padding(16.dp),
  ) {
    SelectableText(
      text = if (availableSessionCount == 0) {
        "No recorded sessions yet. Run `trailblaze blaze` against a device, then come " +
          "back to this tab and pick the session from the dropdown to see which transitions " +
          "between waypoints actually occurred during that run."
      } else {
        "$availableSessionCount recorded session(s) available. Pick one above to see " +
          "the transitions between waypoints that occurred during that run, with the tool " +
          "calls that drove each transition."
      },
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

@Composable
private fun SessionLensLoadingBody(sessionLabel: String) {
  Column(
    modifier = Modifier.fillMaxWidth().padding(16.dp),
  ) {
    SelectableText(
      text = "Extracting segments from $sessionLabel…",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

@Composable
private fun SessionLensLoadedBody(result: SessionLensResult, bodyHeight: Dp) {
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 12.dp, vertical = 8.dp),
  ) {
    SessionPathStripe(result.sessionPath)
    Spacer(Modifier.height(8.dp))
    SessionDiagnostics(result)
    Spacer(Modifier.height(8.dp))
    if (result.segments.isEmpty()) {
      EmptySegmentsHint(result)
    } else {
      // LazyColumn with a user-resizable cap on visible height — the segments scroll
      // inside the panel rather than stretching the whole tab. The cap is driven by the
      // top-of-card drag handle (defaults to 360dp; clamped to 120..720dp upstream).
      Box(modifier = Modifier.fillMaxWidth().height(bodyHeight)) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
          items(items = result.segments, key = { "${it.fromStep}-${it.toStep}-${it.from}-${it.to}" }) {
            SegmentRow(it)
          }
        }
      }
    }
  }
}

@Composable
private fun SessionPathStripe(path: String) {
  Surface(
    modifier = Modifier.fillMaxWidth(),
    color = MaterialTheme.colorScheme.surface,
    tonalElevation = 0.dp,
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = "session:",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Spacer(Modifier.width(6.dp))
      SelectableText(
        text = path,
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
      )
    }
  }
}

@Composable
private fun SessionDiagnostics(result: SessionLensResult) {
  Row(
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    DiagnosticChip("${result.totalRequestLogs} request log(s)")
    DiagnosticChip("${result.stepsWithNodeTree} with nodeTree")
    DiagnosticChip("${result.stepsWithMatchedWaypoint} matched")
    if (result.stepsWithAmbiguousMatch > 0) {
      DiagnosticChip(
        "${result.stepsWithAmbiguousMatch} ambiguous",
        warn = true,
      )
    }
    if (result.parseFailures > 0) {
      DiagnosticChip(
        "${result.parseFailures} parse failure(s)",
        warn = true,
      )
    }
  }
}

@Composable
private fun DiagnosticChip(label: String, warn: Boolean = false) {
  val containerColor = if (warn) {
    MaterialTheme.colorScheme.errorContainer
  } else {
    MaterialTheme.colorScheme.surfaceVariant
  }
  val contentColor = if (warn) {
    MaterialTheme.colorScheme.onErrorContainer
  } else {
    MaterialTheme.colorScheme.onSurfaceVariant
  }
  Surface(
    color = containerColor,
    contentColor = contentColor,
    shape = MaterialTheme.shapes.small,
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      if (warn) {
        Icon(
          Icons.Filled.Warning,
          contentDescription = null,
          tint = contentColor,
          modifier = Modifier.padding(end = 4.dp).height(14.dp),
        )
      }
      Text(text = label, style = MaterialTheme.typography.labelSmall)
    }
  }
}

@Composable
private fun EmptySegmentsHint(result: SessionLensResult) {
  // Mirrors the CLI's `noSegmentsHint` chain so the desktop diagnostic matches what
  // `trailblaze segment list` would print for the same session — same root causes, same
  // suggested fixes, same wording.
  val hint = when {
    result.totalRequestLogs == 0 ->
      "No TrailblazeLlmRequestLog files found in this session."
    result.stepsWithNodeTree == 0 ->
      "No step had a trailblazeNodeTree — this session predates the multi-agent " +
        "logging fix; only sessions captured after that change can be matched."
    result.stepsWithMatchedWaypoint < 2 ->
      "Fewer than 2 steps matched a waypoint, so there are no transitions to report. " +
        "Try `trailblaze waypoint locate` against this session to debug per-step."
    else ->
      "Every matched step landed on the same waypoint — no transitions occurred."
  }
  Text(
    text = "No segments observed.",
    style = MaterialTheme.typography.bodyMedium,
    fontWeight = FontWeight.SemiBold,
  )
  SelectableText(
    text = hint,
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
}

@Composable
private fun SegmentRow(segment: SegmentDisplayItem) {
  var expanded by remember { mutableStateOf(false) }
  Card(
    modifier = Modifier
      .fillMaxWidth()
      .clickable { expanded = !expanded },
    colors = CardDefaults.cardColors(
      containerColor = MaterialTheme.colorScheme.surface,
    ),
  ) {
    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            SelectableText(
              text = segment.from,
              style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
              fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.width(6.dp))
            Text(
              text = "→",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(6.dp))
            SelectableText(
              text = segment.to,
              style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
              fontWeight = FontWeight.SemiBold,
            )
          }
          Text(
            text = "steps ${segment.fromStep}→${segment.toStep} · ${formatDuration(segment.durationMs)}" +
              " · ${segment.triggers.size} trigger(s)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Icon(
          if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      if (expanded) {
        if (segment.triggers.isEmpty()) {
          SelectableText(
            text = "(no triggers recorded between these steps)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp, start = 16.dp),
          )
        } else {
          Column(modifier = Modifier.padding(top = 6.dp, start = 16.dp)) {
            segment.triggers.forEach { trigger ->
              SelectableText(
                text = "· $trigger",
                style = MaterialTheme.typography.bodySmall.copy(
                  fontFamily = FontFamily.Monospace,
                ),
              )
            }
          }
        }
      }
    }
  }
}

private fun formatDuration(durationMs: Long): String {
  val secs = durationMs / 1000.0
  // Whole-second display when under a minute; otherwise mm:ss.s — tight enough to be
  // glanceable in the row subtitle without wrapping.
  return if (secs < 60.0) {
    "${formatOneDecimal(secs)}s"
  } else {
    val m = (secs / 60).toInt()
    val s = secs - m * 60
    "${m}m ${formatOneDecimal(s)}s"
  }
}

/** Multiplatform-safe one-decimal formatter; commonMain has no `String.format`. */
private fun formatOneDecimal(value: Double): String {
  val tenths = (value * 10).toLong()
  val whole = tenths / 10
  val frac = tenths % 10
  return if (frac < 0) "$whole.${-frac}" else "$whole.$frac"
}
