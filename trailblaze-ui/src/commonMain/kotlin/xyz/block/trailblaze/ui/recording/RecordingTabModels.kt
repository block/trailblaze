@file:OptIn(ExperimentalMaterial3Api::class)

package xyz.block.trailblaze.ui.recording

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import xyz.block.trailblaze.config.ToolYamlConfig
import xyz.block.trailblaze.devices.TrailblazeConnectedDeviceSummary
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.recording.DeviceScreenStream
import xyz.block.trailblaze.recording.InteractionToolFactory

/**
 * Data + UI primitives for the recording tab that have no JVM-specific dependencies. Lives in
 * `trailblaze-ui/commonMain` so it compiles to wasmJs alongside JVM. The orchestration
 * composable that wires this together with Maestro/Playwright drivers + Compose Desktop
 * device-management stays in `trailblaze-host` — that's where the JVM-only pieces actually
 * live (Maestro driver creation, OkHttp-backed LLM calls, classpath-discovered tool
 * registries). Once those backends grow multiplatform implementations, the orchestration
 * composable can also move.
 *
 * What's here:
 * - [RecordingTabState] — app-lifetime state holder.
 * - [RecordingDeviceConnection] — handle to the live device, plus the metadata downstream
 *   composables key off (driver type, fully-qualified id).
 * - [ConnectionState] — sealed lifecycle for the Connect button.
 * - [TargetDropdown] / [DeviceDropdown] — pure Compose Multiplatform widgets.
 * - [formatDeviceLabel] / [formatDeviceSubLabel] — display formatters.
 */

/**
 * Handle to a live recording connection. [stream] and [toolFactory] are the recorder's
 * platform-specific wiring (Maestro vs Playwright vs future drivers); [trailblazeDeviceId] +
 * [trailblazeDriverType] are surfaced so downstream code can route replays and Tool Palette
 * discovery without re-deriving them. [InteractionToolFactory] is itself in commonMain
 * (interface only) so this data class carries no JVM-only types.
 */
data class RecordingDeviceConnection(
  val stream: DeviceScreenStream,
  val toolFactory: InteractionToolFactory,
  val deviceLabel: String,
  /** ID for routing single-step replays through `TrailblazeDeviceManager.runYaml`. */
  val trailblazeDeviceId: TrailblazeDeviceId,
  /** Driver type, surfaced to the screen for Tool Palette filtering. */
  val trailblazeDriverType: TrailblazeDriverType,
)

/**
 * Connection lifecycle state. Idle → Connecting → Connected | Error. The Connecting/Error
 * leaves are terminal until the user clicks Connect again or picks a different device.
 */
sealed interface ConnectionState {
  data object Idle : ConnectionState
  data object Connecting : ConnectionState
  data class Connected(val connection: RecordingDeviceConnection) : ConnectionState
  data class Error(val message: String) : ConnectionState
}

/**
 * App-lifetime holder for recording-tab state — hoisted out of the composable so the user's
 * connection, recorder, and selected device survive a navigation away (Sessions tab, MCP tab,
 * anywhere else) and back. Without this hoist the tab's `remember { ... }` state is GC'd the
 * moment the tab leaves composition, which surfaces as "I came back and everything was gone".
 *
 * Constructed once per app boot in the `recordTab` factory. The factory's composable lambda
 * is re-invoked on every navigation, but the [RecordingTabState] instance is captured by the
 * closure and reused — the state outlives the tab's composition by design.
 *
 * ## Why `<R>` is generic
 *
 * `R` is the recorder type the host module supplies (today `InteractionRecorder` from
 * trailblaze-common/jvmAndAndroid). Keeping the type parameter unbound here lets this state
 * holder live in commonMain even though the actual recorder implementation hasn't lifted yet
 * (`toLogPayload` reflection is the lone blocker). Once the recorder moves, this can simplify
 * to `recorder: MutableState<InteractionRecorder?>` with no callers needing to change.
 *
 * Public constructor (rather than the previous `internal`) because the factory function lives
 * in a different module from this state holder.
 */
class RecordingTabState<R> {
  // Expose `MutableState<T>` directly so the composable can `var x by state.field` and writes
  // are visible to anyone else who happens to read the same field. Compose tracks snapshot
  // dependencies through the MutableState getter on read, so navigating away and back picks
  // up whatever the current values are.
  val selectedDevice = mutableStateOf<TrailblazeConnectedDeviceSummary?>(null)
  val connectionState = mutableStateOf<ConnectionState>(ConnectionState.Idle)
  val recorder = mutableStateOf<R?>(null)

  /**
   * Trailhead the user picked for this session, or `null` for "current state — no trailhead."
   * Optional by design: a captured trail without a trailhead replays from wherever the device
   * happens to be (fragile but fine for solo iteration); picking one makes the trail
   * reproducible end-to-end via the Verify Trail button. Persists across tab switches like
   * the other state here, so an author who picked a trailhead at the start of authoring
   * doesn't have to re-pick it after popping over to Sessions.
   */
  val selectedTrailhead = mutableStateOf<ToolYamlConfig?>(null)

  /**
   * Per-instance parameter values for the picked [selectedTrailhead]. A trailhead is just a
   * tool, and tools take parameters; you might pin `account = …` for one trailhead and an
   * `email` for another. Stored separately from [selectedTrailhead] so the picker (which
   * holds a `ToolYamlConfig` schema describing what parameters *can* be set) and the
   * user-supplied values stay decoupled.
   *
   * Verify Trail / replay rendering use these by stitching the trailhead's tool name + this
   * map into a single-tool YAML at dispatch time. Reopening the picker pre-fills the form
   * with these values so re-editing works without losing what was configured.
   */
  val selectedTrailheadParams = mutableStateOf<Map<String, String>>(emptyMap())
}

/** Dropdown for selecting which target app the recording will run against. */
@Composable
fun TargetDropdown(
  targets: List<TrailblazeHostAppTarget>,
  selectedTarget: TrailblazeHostAppTarget?,
  onTargetSelected: (TrailblazeHostAppTarget) -> Unit,
  modifier: Modifier = Modifier,
) {
  var expanded by remember { mutableStateOf(false) }

  ExposedDropdownMenuBox(
    expanded = expanded,
    onExpandedChange = { expanded = it },
    modifier = modifier,
  ) {
    OutlinedTextField(
      value = selectedTarget?.displayName ?: "Select target",
      onValueChange = {},
      readOnly = true,
      singleLine = true,
      trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
      modifier = Modifier
        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
        .fillMaxWidth(),
    )
    ExposedDropdownMenu(
      expanded = expanded,
      onDismissRequest = { expanded = false },
    ) {
      targets.forEach { target ->
        DropdownMenuItem(
          text = {
            Text(
              text = target.displayName,
              style = MaterialTheme.typography.bodyMedium,
            )
          },
          onClick = {
            onTargetSelected(target)
            expanded = false
          },
        )
      }
    }
  }
}

/** Dropdown for selecting a device to record from. */
@Composable
fun DeviceDropdown(
  devices: List<TrailblazeConnectedDeviceSummary>,
  selectedDevice: TrailblazeConnectedDeviceSummary?,
  onDeviceSelected: (TrailblazeConnectedDeviceSummary) -> Unit,
  modifier: Modifier = Modifier,
) {
  var expanded by remember { mutableStateOf(false) }

  ExposedDropdownMenuBox(
    expanded = expanded,
    onExpandedChange = { expanded = it },
    modifier = modifier,
  ) {
    OutlinedTextField(
      value = selectedDevice?.let { formatDeviceLabel(it) } ?: "Select device",
      onValueChange = {},
      readOnly = true,
      singleLine = true,
      trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
      modifier = Modifier
        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
        .fillMaxWidth(),
    )
    ExposedDropdownMenu(
      expanded = expanded,
      onDismissRequest = { expanded = false },
    ) {
      devices.forEach { device ->
        DropdownMenuItem(
          text = {
            Column {
              Text(
                text = formatDeviceLabel(device),
                style = MaterialTheme.typography.bodyMedium,
              )
              Text(
                text = formatDeviceSubLabel(device),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          },
          onClick = {
            onDeviceSelected(device)
            expanded = false
          },
        )
      }
    }
  }
}

/**
 * Dashed-outline slot showing the recording's trailhead — or a "no trailhead" placeholder
 * inviting the author to pick one. Lives at the top of the recording controls panel rather
 * than in the device/target selector row, so the trailhead concept reads as part of the
 * recording's setup (where you start from) rather than as device configuration.
 *
 * The dashed outline is the visual hint that this is a configurable slot the author can
 * fill — borrowed from the same design idiom as drag-target areas. Empty state surfaces the
 * "make this trail reproducible" framing directly so the concept teaches itself; selected
 * state shows the trailhead id + destination waypoint so the author knows what they picked.
 *
 * Clicking anywhere on the slot fires [onClick]. The parent is expected to open the unified
 * Tool Palette dialog in trailhead-picker mode — "a trailhead is just a type of tool" — so
 * the gesture for picking a trailhead matches the gesture for adding any other tool to the
 * recording. This widget intentionally has no idea what dialog gets opened, which keeps it
 * portable to other authoring shells (e.g. a future MCP-driven surface).
 */
@Composable
fun TrailheadSlot(
  selectedTrailhead: ToolYamlConfig?,
  selectedTrailheadParams: Map<String, String>,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val outlineColor = MaterialTheme.colorScheme.outline
  val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant

  Column(
    modifier = modifier
      .fillMaxWidth()
      .clickable(onClick = onClick)
      // Dashed outline drawn manually — Compose's `Modifier.border` doesn't take a
      // `PathEffect`. Stroke + dash gap of 4px each gives a fine-grained dash that reads
      // as "configurable slot" without competing with the action buttons below.
      .drawBehind {
        drawRoundRect(
          color = outlineColor,
          cornerRadius = CornerRadius(8.dp.toPx(), 8.dp.toPx()),
          style = Stroke(
            width = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f), 0f),
          ),
        )
      }
      .padding(horizontal = 12.dp, vertical = 10.dp),
  ) {
    Text(
      text = "Trailhead",
      style = MaterialTheme.typography.labelSmall,
      color = onSurfaceVariant,
    )
    if (selectedTrailhead != null) {
      Text(
        text = selectedTrailhead.id,
        style = MaterialTheme.typography.bodyMedium,
      )
      selectedTrailhead.trailhead?.to?.let { destination ->
        Text(
          text = "→ $destination",
          style = MaterialTheme.typography.bodySmall,
          color = onSurfaceVariant,
        )
      }
      // Show user-supplied param values so the slot reflects the *configured* trailhead,
      // not just its id. Without this two trailhead picks with different params would
      // render identically — the slot would lose the per-instance configuration the user
      // just supplied.
      selectedTrailheadParams
        .filterValues { it.isNotBlank() }
        .forEach { (name, value) ->
          Text(
            text = "$name: $value",
            style = MaterialTheme.typography.bodySmall,
            color = onSurfaceVariant,
          )
        }
    } else {
      Text(
        text = "None — pick one to make this trail reproducible",
        style = MaterialTheme.typography.bodyMedium,
        color = onSurfaceVariant,
      )
    }
  }
}

/**
 * Primary label uses the canonical fully-qualified device id (e.g. `android/emulator-5554`,
 * `web/playwright-native`) — same string `trailblaze --device <id>` accepts on the CLI, so
 * what authors copy-paste from `trailblaze device list` matches what they pick here exactly.
 */
fun formatDeviceLabel(device: TrailblazeConnectedDeviceSummary): String =
  device.trailblazeDeviceId.toFullyQualifiedDeviceId()

/** Secondary line in the dropdown: human-readable description + driver-type tag. */
fun formatDeviceSubLabel(device: TrailblazeConnectedDeviceSummary): String {
  val description = device.description.takeIf { it.isNotBlank() }
  val driver = device.trailblazeDriverType.name
  return if (description != null) "$description • $driver" else driver
}
