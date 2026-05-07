@file:OptIn(ExperimentalMaterial3Api::class)

package xyz.block.trailblaze.ui.tabs.recording

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.host.recording.discoverAvailableTools
import xyz.block.trailblaze.toolcalls.TrailblazeToolDescriptor
import xyz.block.trailblaze.ui.TrailblazeDeviceManager
import xyz.block.trailblaze.ui.recording.ToolParamForm
import xyz.block.trailblaze.ui.recording.buildSingleToolYaml

/**
 * "Trailhead picker" mode for [ToolPaletteDialog]. When non-null, the dialog re-themes itself
 * as a trailhead picker: the list is restricted to tools whose names appear in
 * [trailheadToolNames], and the buttons collapse to a single "Use as Trailhead" action that
 * fires [onPick] with the configured parameter values. A "Clear" entry shows up first when
 * [allowClear] is true so the author can drop back to "no trailhead" without leaving the
 * dialog.
 *
 * The parameter form stays visible (and editable) in this mode — a trailhead is literally
 * just a tool, and tools take parameters. A trailhead like `launch-with-account(account=…)`
 * needs to know *which* account to launch as, otherwise the picker is impossible to use
 * for any tool that takes per-instance configuration.
 *
 * Modeled as a discriminator on [ToolPaletteDialog] rather than a separate dialog because the
 * user's framing — "a trailhead is just a type of tool" — argues for one unified surface that
 * the author opens either to add a tool or to pick a trailhead. The shared search-and-list UI
 * means the gesture for finding any tool (type, scan, click) works identically for both.
 *
 * @param onPick Receives the picked tool name plus the user-supplied parameter values. Caller
 *   stitches them into a single-tool YAML (typically via [buildSingleToolYaml]) for replay /
 *   Verify Trail dispatch and stores them in `RecordingTabState.selectedTrailheadParams` so a
 *   later open of the picker can pre-fill the form.
 * @param initialParamValues Pre-fills the form on open. Threaded from the state holder so
 *   reopening the picker against an already-selected trailhead shows what was configured
 *   before, not an empty form.
 */
data class TrailheadPickerMode(
  val trailheadToolNames: Set<String>,
  val onPick: (toolName: String, paramValues: Map<String, String>) -> Unit,
  val onClear: () -> Unit,
  val allowClear: Boolean,
  val initialParamValues: Map<String, String> = emptyMap(),
  val initialToolName: String? = null,
)

/**
 * Modal "tool palette" — pick any tool the toolbox knows about for the current
 * (target, driver) combo, fill in its parameters, and either Run it on the device or Insert it
 * into the recording.
 *
 * Layout: search box at the top, a [LazyColumn] of matching tools below it (alphabetical, no
 * dropdown chrome), and the selected tool's form below the list. Filter narrows the list as
 * the author types — picking a tool is one click on the list row.
 *
 * Decisions worth knowing:
 * - Param widgets are typed by `TrailblazeToolParameterDescriptor.type` (String → TextField,
 *   Int/Long → numeric TextField, Boolean → "true/false" TextField). Enum dropdowns wait on
 *   the descriptor enrichment to add `validValues`; until then enums look like Strings, which
 *   is consistent with how the rest of the toolbox surfaces them.
 * - Required + optional params are rendered in two visually distinct sections so the author
 *   can ignore optional fields without scanning the type column. Optional fields with empty
 *   values are dropped from the emitted YAML so default behavior wins.
 * - YAML construction is `- toolName:\n    field: value`-shape — the same form the recording
 *   pipeline produces, so the parent feeds it to the same `decodeTools` / `runYaml` paths
 *   without a special branch.
 *
 * @param onInsert Called with `(toolName, yamlBlock)` for the chosen tool. Caller decodes the
 *   YAML into a `RecordedInteraction` and inserts it into the recorder. Kept narrow because
 *   the dialog has no recorder reference of its own — the parent owns that wiring.
 * @param onRun Called with `(toolName, fullTrailYaml)` where the YAML is wrapped as a one-step
 *   trail so `TrailblazeDeviceManager.runYaml` can execute it directly.
 * @param trailheadPickerMode When non-null, the dialog re-themes itself as a trailhead picker.
 *   See [TrailheadPickerMode] — this collapses the form and replaces the buttons.
 */
@Composable
fun ToolPaletteDialog(
  deviceManager: TrailblazeDeviceManager,
  driverType: TrailblazeDriverType,
  /**
   * When non-null, Insert splices the new step at this index in the recording. When null,
   * Insert appends. Surfaced in the dialog title so the author knows where the tool will land
   * before clicking Insert — prevents accidentally appending to the end when they meant to
   * insert mid-list (or vice versa).
   */
  insertPosition: Int? = null,
  onDismissRequest: () -> Unit,
  onInsert: (toolName: String, singleToolYaml: String) -> Unit,
  onRun: (toolName: String, singleToolYaml: String) -> Unit,
  trailheadPickerMode: TrailheadPickerMode? = null,
) {
  var allTools by remember { mutableStateOf<List<TrailblazeToolDescriptor>>(emptyList()) }
  var loadError by remember { mutableStateOf<String?>(null) }
  var loading by remember { mutableStateOf(true) }
  var selectedTool by remember { mutableStateOf<TrailblazeToolDescriptor?>(null) }
  // paramValues survives tool selection — when the user switches tools we wipe to avoid leaking
  // values from a previous tool's namespace. Storing per-tool drafts is overkill for this surface.
  val paramValues = remember { mutableStateMapOf<String, String>() }
  var search by remember { mutableStateOf("") }

  // When opened in trailhead-picker mode against an already-selected trailhead, pre-fill the
  // form with whatever the author configured last time so re-opening doesn't lose state.
  // Wait until the tool list actually loads before trying to resolve the initial selection —
  // resolving against an empty list would no-op silently.
  LaunchedEffect(allTools, trailheadPickerMode?.initialToolName) {
    val initial = trailheadPickerMode?.initialToolName ?: return@LaunchedEffect
    if (allTools.isEmpty()) return@LaunchedEffect
    val match = allTools.firstOrNull { it.name == initial } ?: return@LaunchedEffect
    selectedTool = match
    paramValues.clear()
    paramValues.putAll(trailheadPickerMode.initialParamValues)
  }

  LaunchedEffect(deviceManager, driverType) {
    loading = true
    loadError = null
    try {
      allTools = discoverAvailableTools(deviceManager, driverType)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Throwable) {
      loadError = "Failed to load toolbox: ${e.message ?: e::class.simpleName}"
      allTools = emptyList()
    } finally {
      loading = false
    }
  }

  // Alphabetical so the list has predictable scroll position. `discoverAvailableTools` already
  // sorts, but we re-sort here in case the source ever stops doing so — this is the layer that
  // promises predictable order to the user. In trailhead-picker mode, restrict the source set
  // to the trailhead tool names the parent supplied; the search filter then narrows further.
  val filteredTools = remember(allTools, search, trailheadPickerMode) {
    val source = if (trailheadPickerMode != null) {
      allTools.filter { it.name in trailheadPickerMode.trailheadToolNames }
    } else {
      allTools
    }
    val term = search.trim().lowercase()
    val matched = if (term.isEmpty()) {
      source
    } else {
      source.filter { d ->
        d.name.lowercase().contains(term) ||
          d.description?.lowercase()?.contains(term) == true
      }
    }
    matched.sortedBy { it.name }
  }

  AlertDialog(
    onDismissRequest = onDismissRequest,
    title = {
      val titleText = if (trailheadPickerMode != null) {
        "Pick Trailhead"
      } else {
        val titleSuffix = insertPosition?.let { " — insert at #${it + 1}" } ?: ""
        "Tool Palette$titleSuffix"
      }
      Text(titleText)
    },
    text = {
      // Outer Column has no scroll: the LazyColumn (tool list) and the form section each
      // manage their own scrolling so a long form doesn't push the list off-screen and a long
      // list doesn't squeeze the form down to nothing. The wide+tall caps below give the
      // dialog room to breathe — earlier sizing was tight and the user noted the list felt
      // cramped (only ~3 rows visible at once).
      Column(
        modifier = Modifier
          .widthIn(min = 600.dp, max = 880.dp)
          .heightIn(min = 540.dp, max = 820.dp),
      ) {
        when {
          loading -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
              CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
              Spacer(Modifier.width(8.dp))
              Text("Loading available tools...", style = MaterialTheme.typography.bodySmall)
            }
          }
          loadError != null -> {
            Text(
              text = loadError!!,
              color = MaterialTheme.colorScheme.error,
              style = MaterialTheme.typography.bodySmall,
            )
          }
          allTools.isEmpty() -> {
            Text(
              text = "No tools available for this device.",
              style = MaterialTheme.typography.bodyMedium,
            )
          }
          else -> {
            // Search box — matches against tool name + description so an author who knows what
            // they want can type "scroll" or "back" and not scroll a 50-entry list.
            OutlinedTextField(
              value = search,
              onValueChange = { search = it },
              label = { Text("Search tools") },
              singleLine = true,
              modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))

            Text(
              text = "${filteredTools.size} tool${if (filteredTools.size == 1) "" else "s"}",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))

            // Lazy list of matching tools. In trailhead-picker mode the form is hidden, so the
            // list takes the whole available height — `weight(1f)` lets it grow into whatever
            // space the outer Column gives us. In normal Tool-Palette mode the list shares
            // height with the parameter form; we still give it weight so it grows with the
            // dialog, but `heightIn(max = ...)` caps it so the form below remains visible
            // even when the user has scrolled deep into a long list.
            LazyColumn(
              modifier = if (trailheadPickerMode != null) {
                Modifier.fillMaxWidth().weight(1f)
              } else {
                Modifier.fillMaxWidth().heightIn(max = 360.dp)
              },
            ) {
              items(items = filteredTools, key = { it.name }) { descriptor ->
                ToolListRow(
                  descriptor = descriptor,
                  isSelected = selectedTool?.name == descriptor.name,
                  onClick = {
                    selectedTool = descriptor
                    // Reset param drafts when tool changes — values from a different tool's
                    // namespace would silently land in fields that happen to share a name.
                    paramValues.clear()
                  },
                )
              }
              if (filteredTools.isEmpty()) {
                item(key = "empty") {
                  val emptyMessage = when {
                    // Trailhead mode + blank search = no trailheads exist for this target/driver
                    // at all. Distinct from "no match for what you typed" so the user knows
                    // typing won't help.
                    trailheadPickerMode != null && search.isBlank() ->
                      "No trailheads available for this target."
                    else -> "No tools match \"$search\"."
                  }
                  Text(
                    text = emptyMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(8.dp),
                  )
                }
              }
            }

            // The form section is shown in BOTH modes — a trailhead is just a tool, and tools
            // take parameters. Hiding the form in trailhead mode would prevent the user from
            // configuring per-instance values that the trailhead's underlying tool needs.
            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))

            // Form section. Its own verticalScroll so a tool with many optional params can
            // scroll without pushing the action buttons or the tool list off-screen.
            Column(
              modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            ) {
              val tool = selectedTool
              if (tool == null) {
                Text(
                  text = "Pick a tool from the list above to configure it.",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              } else {
                Text(
                  text = tool.name,
                  style = MaterialTheme.typography.titleSmall,
                )
                tool.description?.takeIf { it.isNotBlank() }?.let { desc ->
                  Spacer(Modifier.height(4.dp))
                  Text(
                    text = desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
                Spacer(Modifier.height(8.dp))

                ToolParamForm(
                  descriptor = tool,
                  paramValues = paramValues,
                  onValueChange = { name, value -> paramValues[name] = value },
                )

                Spacer(Modifier.height(12.dp))
                Text(
                  text = "YAML preview",
                  style = MaterialTheme.typography.labelMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                val yaml = remember(tool, paramValues.toMap()) {
                  buildSingleToolYaml(tool, paramValues)
                }
                Box(
                  modifier = Modifier
                    .fillMaxWidth()
                    .background(
                      MaterialTheme.colorScheme.surfaceVariant,
                      RoundedCornerShape(4.dp),
                    )
                    .padding(8.dp),
                ) {
                  Text(
                    text = yaml,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                  )
                }
              }
            }
          }
        }
      }
    },
    confirmButton = {
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val tool = selectedTool
        if (trailheadPickerMode != null) {
          // Trailhead picker collapses the action set to two: clear (optional) + commit. The
          // parent's `onPick` callback both updates `selectedTrailhead` state and dismisses
          // the dialog, so we don't fire `onDismissRequest` here ourselves.
          if (trailheadPickerMode.allowClear) {
            OutlinedButton(onClick = trailheadPickerMode.onClear) {
              Text("Clear Trailhead")
            }
          }
          Button(
            enabled = tool != null,
            onClick = {
              tool ?: return@Button
              // Snapshot the param map so the caller's stored copy isn't aliased to this
              // dialog's mutableStateMap (which dies when the dialog dismisses).
              trailheadPickerMode.onPick(tool.name, paramValues.toMap())
            },
          ) {
            Text("Use as Trailhead")
          }
        } else {
          OutlinedButton(
            enabled = tool != null,
            onClick = {
              tool ?: return@OutlinedButton
              val yaml = buildSingleToolYaml(tool, paramValues)
              onInsert(tool.name, yaml)
            },
          ) {
            Text("Insert")
          }
          Button(
            enabled = tool != null,
            onClick = {
              tool ?: return@Button
              val yaml = buildSingleToolYaml(tool, paramValues)
              onRun(tool.name, yaml)
            },
          ) {
            Text("Run on Device")
          }
        }
      }
    },
    dismissButton = {
      OutlinedButton(onClick = onDismissRequest) { Text("Cancel") }
    },
  )
}

/**
 * One row in the tool palette's lazy list. Highlighted background when the tool is the active
 * selection so the form below has a visual anchor; otherwise low-contrast so a long list
 * scans cleanly. Clickable across the full row so the click target matches the visual row.
 */
@Composable
private fun ToolListRow(
  descriptor: TrailblazeToolDescriptor,
  isSelected: Boolean,
  onClick: () -> Unit,
) {
  val background = if (isSelected) {
    MaterialTheme.colorScheme.primaryContainer
  } else {
    MaterialTheme.colorScheme.surface
  }
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .background(background, RoundedCornerShape(4.dp))
      .clickable(onClick = onClick)
      .padding(horizontal = 8.dp, vertical = 6.dp),
  ) {
    Text(
      text = descriptor.name,
      style = MaterialTheme.typography.bodyMedium,
    )
    descriptor.description?.let { desc ->
      Text(
        text = desc.lineSequence().firstOrNull()?.take(120) ?: "",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

// `ParameterField`, `EnumParameterDropdown`, and `buildSingleToolYaml` moved to
// `xyz.block.trailblaze.ui.recording.RecordingWidgets` in trailblaze-ui/commonMain so the
// same widgets compile to wasmJs alongside JVM. The dialog itself stays here because it
// reaches into JVM-only `TrailblazeDeviceManager` for tool discovery.
