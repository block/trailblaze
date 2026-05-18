package xyz.block.trailblaze.ui.devices

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.datetime.Clock
import kotlinx.coroutines.launch
import xyz.block.trailblaze.devices.TrailblazeConnectedDeviceSummary
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.host.rpc.TargetAppSummary
import xyz.block.trailblaze.ui.recording.GestureId
import xyz.block.trailblaze.ui.recording.InteractiveDeviceComposable
import xyz.block.trailblaze.ui.recording.ToolPaletteDialog
import xyz.block.trailblaze.ui.recording.WebGesture
import xyz.block.trailblaze.ui.recording.toSingleStepTrailYaml
import xyz.block.trailblaze.ui.recording.toTrailYaml
import xyz.block.trailblaze.ui.theme.TrailblazeTheme
import xyz.block.trailblaze.util.Console

/**
 * How long a replay-status indicator stays visible on its card before auto-clearing.
 * Long enough for the user to read the outcome ("✓" / "✗") and a short error message,
 * short enough that the panel doesn't accumulate stale chrome on repeated replays.
 */
private const val REPLAY_STATUS_DWELL_MS: Long = 2_500L

/** Page-level state: which phase of the device-viewer lifecycle the browser is in. */
private sealed interface DevicesPageState {
  /** Initial load — fetching the device list from the daemon. */
  data object Loading : DevicesPageState

  /** Device list loaded but no device selected yet. */
  data class Idle(val devices: List<TrailblazeConnectedDeviceSummary>) : DevicesPageState

  /** User picked a device; connecting now. */
  data class Connecting(
    val devices: List<TrailblazeConnectedDeviceSummary>,
    val selected: TrailblazeConnectedDeviceSummary,
  ) : DevicesPageState

  /** Live connection open — streaming frames. */
  data class Connected(
    val devices: List<TrailblazeConnectedDeviceSummary>,
    val selected: TrailblazeConnectedDeviceSummary,
    val stream: RemoteDeviceScreenStream,
  ) : DevicesPageState

  /** Something went wrong — show an error message with a retry button. */
  data class Error(
    val message: String,
    val devices: List<TrailblazeConnectedDeviceSummary> = emptyList(),
  ) : DevicesPageState
}

/**
 * Full-screen live device viewer page served at `http://localhost:52525/devices`.
 *
 * Flow:
 * 1. On mount: calls GetConnectedDevicesRequest, shows a dropdown of available devices.
 * 2. User selects a device: calls ConnectToDeviceRequest, transitions to [DevicesPageState.Connecting].
 * 3. Connected: polls frames via [RemoteDeviceScreenStream.frames] and renders them as a bitmap.
 * 4. User click on the frame: computes device coordinates relative to rendered size and
 *    dispatches [xyz.block.trailblaze.host.rpc.DeviceInteraction.Tap].
 * 5. Disconnect button: calls DisconnectDeviceRequest and returns to device selection.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebDevicesPage() {
  val client = remember { HostRpcClient() }
  var pageState by remember { mutableStateOf<DevicesPageState>(DevicesPageState.Loading) }
  var targetApps by remember { mutableStateOf<List<TargetAppSummary>>(emptyList()) }
  var currentTargetAppId by remember { mutableStateOf<String?>(null) }
  var toolPaletteOpen by remember { mutableStateOf(false) }
  var isRecording by remember { mutableStateOf(false) }
  // SnapshotStateList so card edits / removals trigger recomposition. Held across the page's
  // lifetime so disconnect+reconnect preserves the recording (matches the desktop tab).
  val recordedGestures = remember { mutableStateListOf<WebGesture>() }
  // Keyed by gesture.id (a monotonic per-session counter). Per-gesture status so the
  // Replay button can show running / success / failure without blocking the panel.
  val replayStatuses = remember { mutableStateMapOf<GestureId, ReplayStatus>() }
  // Monotonic counter sourced for every emitted WebGesture's id. Was previously a wall-clock
  // timestamp via Clock.System.now() — but rapid same-millisecond gestures collided, which
  // cross-wired LazyColumn card state and replay-status entries. A simple per-page counter
  // is collision-free regardless of capture rate.
  var nextGestureIdCounter by remember { mutableStateOf(0L) }
  val nextGestureId = { val v = nextGestureIdCounter; nextGestureIdCounter = v + 1; v }
  // Per-gesture auto-clear Jobs for replay statuses. When a user re-replays the same
  // gesture within the dwell window, we cancel the previous clear job so it can't
  // remove the new replay's status mid-running (Copilot caught this race).
  val replayClearJobs = remember { mutableMapOf<GestureId, kotlinx.coroutines.Job>() }
  val coroutineScope = rememberCoroutineScope()

  // Warn before leaving the page if there's an unsaved recording. The browser shows its
  // own generic confirm dialog whenever the beforeunload handler calls preventDefault() —
  // modern Chrome/Firefox/Safari all use the standard "Leave site? Changes you made may
  // not be saved" message and ignore any custom string, so we don't try to set returnValue.
  DisposableEffect(recordedGestures.isNotEmpty()) {
    val shouldWarn = recordedGestures.isNotEmpty()
    val handler: (org.w3c.dom.events.Event) -> Unit = { event ->
      if (shouldWarn) event.preventDefault()
    }
    if (shouldWarn) {
      kotlinx.browser.window.addEventListener("beforeunload", handler)
    }
    onDispose {
      kotlinx.browser.window.removeEventListener("beforeunload", handler)
    }
  }

  // Initial load: device list + available target apps.
  LaunchedEffect(Unit) {
    val devicesResponse = client.getConnectedDevices()
    pageState = if (devicesResponse == null) {
      DevicesPageState.Error("Could not reach Trailblaze daemon at localhost:52525. Is it running?")
    } else {
      DevicesPageState.Idle(devicesResponse.devices)
    }
    val appsResponse = client.getTargetApps()
    if (appsResponse != null) {
      targetApps = appsResponse.targetApps
      currentTargetAppId = appsResponse.currentTargetAppId
    }
  }

  TrailblazeTheme {
    Surface(
      modifier = Modifier.fillMaxSize(),
      color = MaterialTheme.colorScheme.background,
    ) {
      Column(modifier = Modifier.fillMaxSize()) {
        AppHeader(
          isRecording = isRecording,
          connectedDeviceLabel = (pageState as? DevicesPageState.Connected)?.selected?.description,
        )

        Column(
          modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
          // Target app selector — required for Android/iOS connect to succeed. Mirrors the
          // Target dropdown in the desktop recording tab.
          if (targetApps.isNotEmpty()) {
            TargetAppSelectorRow(
              targetApps = targetApps,
              currentTargetAppId = currentTargetAppId,
              onSelect = { app ->
                coroutineScope.launch {
                  val result = client.setCurrentTargetApp(app.id)
                  if (result?.success == true) {
                    currentTargetAppId = app.id
                  }
                }
              },
              modifier = Modifier.padding(bottom = 12.dp),
            )
          }

      when (val state = pageState) {
        is DevicesPageState.Loading -> {
          Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
              CircularProgressIndicator()
              Spacer(modifier = Modifier.height(12.dp))
              Text("Connecting to daemon...")
            }
          }
        }

        is DevicesPageState.Error -> {
          Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
              horizontalAlignment = Alignment.CenterHorizontally,
              modifier = Modifier.padding(16.dp),
            ) {
              Text(
                text = "Error",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.error,
              )
              Spacer(modifier = Modifier.height(8.dp))
              Text(
                text = state.message,
                style = MaterialTheme.typography.bodyMedium,
              )
              Spacer(modifier = Modifier.height(16.dp))
              Button(onClick = {
                pageState = DevicesPageState.Loading
                coroutineScope.launch {
                  val response = client.getConnectedDevices()
                  pageState = if (response == null) {
                    DevicesPageState.Error(
                      "Could not reach Trailblaze daemon at localhost:52525.",
                      state.devices,
                    )
                  } else {
                    DevicesPageState.Idle(response.devices)
                  }
                }
              }) {
                Text("Retry")
              }
            }
          }
        }

        is DevicesPageState.Idle -> {
          DeviceSelectorRow(
            devices = state.devices,
            selectedDevice = null,
            onDeviceSelected = { device ->
              pageState = DevicesPageState.Connecting(state.devices, device)
              coroutineScope.launch {
                val connectResponse = client.connectToDevice(device.trailblazeDeviceId)
                pageState = if (connectResponse != null) {
                  val stream = RemoteDeviceScreenStream(
                    client = client,
                    trailblazeDeviceId = device.trailblazeDeviceId,
                    deviceWidth = connectResponse.deviceWidth,
                    deviceHeight = connectResponse.deviceHeight,
                  )
                  DevicesPageState.Connected(state.devices, device, stream)
                } else {
                  DevicesPageState.Error(
                    "Failed to connect to ${device.description}: ${client.lastErrorMessage ?: "unknown error"}",
                    state.devices,
                  )
                }
              }
            },
            onRefresh = {
              coroutineScope.launch {
                val response = client.getConnectedDevices()
                pageState = if (response == null) {
                  DevicesPageState.Error("Could not reach daemon.", state.devices)
                } else {
                  DevicesPageState.Idle(response.devices)
                }
              }
            },
          )
          if (state.devices.isEmpty()) {
            Spacer(modifier = Modifier.height(24.dp))
            Text(
              text = "No devices found. Connect a device and try again.",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }

        is DevicesPageState.Connecting -> {
          DeviceSelectorRow(
            devices = state.devices,
            selectedDevice = state.selected,
            onDeviceSelected = null,
            onRefresh = null,
          )
          Spacer(modifier = Modifier.height(24.dp))
          Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
              CircularProgressIndicator()
              Spacer(modifier = Modifier.height(12.dp))
              Text("Connecting to ${state.selected.description}...")
            }
          }
        }

        is DevicesPageState.Connected -> {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            DeviceSelectorRow(
              devices = state.devices,
              selectedDevice = state.selected,
              onDeviceSelected = { newDevice ->
                // Disconnect current first, then connect to new device
                val currentDeviceId = state.selected.trailblazeDeviceId
                pageState = DevicesPageState.Connecting(state.devices, newDevice)
                coroutineScope.launch {
                  client.disconnectDevice(currentDeviceId)
                  val connectResponse = client.connectToDevice(newDevice.trailblazeDeviceId)
                  pageState = if (connectResponse != null) {
                    val stream = RemoteDeviceScreenStream(
                      client = client,
                      trailblazeDeviceId = newDevice.trailblazeDeviceId,
                      deviceWidth = connectResponse.deviceWidth,
                      deviceHeight = connectResponse.deviceHeight,
                    )
                    DevicesPageState.Connected(state.devices, newDevice, stream)
                  } else {
                    DevicesPageState.Error(
                      "Failed to connect to ${newDevice.description}: ${client.lastErrorMessage ?: "unknown error"}",
                      state.devices,
                    )
                  }
                }
              },
              onRefresh = {
                val activeDeviceId = state.selected.trailblazeDeviceId
                coroutineScope.launch {
                  // Release the host-side stream before navigating away from Connected,
                  // otherwise the daemon keeps the session open until restart.
                  client.disconnectDevice(activeDeviceId)
                  val response = client.getConnectedDevices()
                  pageState = if (response == null) {
                    DevicesPageState.Error("Could not reach daemon.", state.devices)
                  } else {
                    DevicesPageState.Idle(response.devices)
                  }
                }
              },
              modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(8.dp))
            OutlinedButton(onClick = { toolPaletteOpen = true }) {
              Text("Tools")
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
              onClick = { isRecording = !isRecording },
              colors = if (isRecording) {
                ButtonDefaults.buttonColors(
                  containerColor = MaterialTheme.colorScheme.error,
                  contentColor = MaterialTheme.colorScheme.onError,
                )
              } else {
                ButtonDefaults.buttonColors()
              },
            ) {
              Text(if (isRecording) "Stop" else "Record")
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
              onClick = {
                val deviceId = state.selected.trailblazeDeviceId
                coroutineScope.launch {
                  client.disconnectDevice(deviceId)
                }
                pageState = DevicesPageState.Idle(state.devices)
              },
            ) {
              Text("Disconnect")
            }
          }
          Spacer(modifier = Modifier.height(8.dp))
          if (state.selected.platform == TrailblazeDevicePlatform.WEB) {
            WebUrlBar(
              onNavigate = { url ->
                coroutineScope.launch {
                  client.navigateWebUrl(state.selected.trailblazeDeviceId, url)
                }
              },
              modifier = Modifier.padding(bottom = 8.dp),
            )
          }
          if (toolPaletteOpen) {
            val deviceId = state.selected.trailblazeDeviceId
            val driverType = state.selected.trailblazeDriverType
            ToolPaletteDialog(
              loadTools = { client.getToolCatalog(driverType)?.tools ?: emptyList() },
              onDismissRequest = { toolPaletteOpen = false },
              onInsert = { toolName, singleToolYaml ->
                // Splice the tool-palette pick into the recording as a Custom step. The
                // user can replay or save it just like a gestured step, and it composes
                // uniformly with the other gesture variants in [toTrailYaml].
                recordedGestures.add(
                  WebGesture.Custom(
                    toolName = toolName,
                    yaml = singleToolYaml,
                    id = nextGestureId(),
                  ),
                )
                toolPaletteOpen = false
              },
              onRun = { toolName, yaml ->
                // Route through WebGesture.Custom.toSingleStepTrailYaml() so the dialog's
                // bare `toolName:\n  field: value` shape is normalized into the canonical
                // `- tools:\n    - toolName:\n        field: value` envelope the daemon's
                // runYaml parser expects. Without this the server silently runs an empty
                // trail (the malformed YAML parses to zero tools) and the device sees nothing.
                val gesture = WebGesture.Custom(
                  toolName = toolName,
                  yaml = yaml,
                  id = nextGestureId(),
                )
                coroutineScope.launch {
                  val result = client.runTrailYaml(deviceId, gesture.toSingleStepTrailYaml())
                  if (result?.success != true) {
                    Console.log(
                      "[WebDevicesPage] Tool Palette run of $toolName failed: " +
                        (client.lastErrorMessage ?: "unknown"),
                    )
                  }
                }
                toolPaletteOpen = false
              },
            )
          }
          Row(modifier = Modifier.weight(1f)) {
            InteractiveDeviceComposable(
              stream = state.stream,
              buffer = null,
              isRecording = isRecording,
              modifier = Modifier.weight(1f),
              onWebGesture = { gesture -> recordedGestures.add(gesture) },
              nextGestureId = nextGestureId,
              onConnectionLost = { e ->
                Console.log("[WebDevicesPage] Connection lost: ${e.message}")
                pageState = DevicesPageState.Error(
                  "Connection to ${state.selected.description} lost: ${e.message ?: "unknown"}",
                  state.devices,
                )
              },
            )
            if (isRecording || recordedGestures.isNotEmpty()) {
              Spacer(modifier = Modifier.width(12.dp))
              RecordedGesturePanel(
                gestures = recordedGestures,
                isRecording = isRecording,
                replayStatuses = replayStatuses,
                onReplay = { gesture ->
                  // Cancel any pending auto-clear from a previous replay of this same gesture
                  // — otherwise the old delay(2500) would fire mid-running and remove the
                  // status for the new attempt. Cancelling before scheduling a new one
                  // guarantees only one clear job is in flight per gesture id.
                  replayClearJobs.remove(gesture.id)?.cancel()
                  replayStatuses[gesture.id] = ReplayStatus.Running
                  coroutineScope.launch {
                    val result = client.runTrailYaml(
                      state.selected.trailblazeDeviceId,
                      gesture.toSingleStepTrailYaml(),
                    )
                    replayStatuses[gesture.id] = if (result?.success == true) {
                      ReplayStatus.Success
                    } else {
                      ReplayStatus.Failed(client.lastErrorMessage)
                    }
                    // Auto-clear after a short dwell so the indicator doesn't linger.
                    // Stored so a subsequent replay can cancel us before the delay fires.
                    val clearJob = coroutineScope.launch {
                      kotlinx.coroutines.delay(REPLAY_STATUS_DWELL_MS)
                      replayStatuses.remove(gesture.id)
                      replayClearJobs.remove(gesture.id)
                    }
                    replayClearJobs[gesture.id] = clearJob
                  }
                },
                onDelete = { gesture ->
                  // Cancel any pending clear job for this gesture so it doesn't try to
                  // remove a status for a now-deleted gesture (harmless but tidy).
                  replayClearJobs.remove(gesture.id)?.cancel()
                  recordedGestures.remove(gesture)
                  replayStatuses.remove(gesture.id)
                },
                onClear = {
                  // Cancel ALL pending clear jobs before wiping state.
                  replayClearJobs.values.forEach { it.cancel() }
                  replayClearJobs.clear()
                  recordedGestures.clear()
                  replayStatuses.clear()
                },
                onSaveTrail = {
                  // Filename-safe ISO 8601: `Instant.toString()` returns `2026-05-16T13:42:15.123Z`;
                  // strip the colons + millisecond decimal so Windows filesystems accept it,
                  // and keep the seconds-precision portion that the user actually reads.
                  val ts = Clock.System.now().toString()
                    .substringBefore('.')
                    .replace(':', '-')
                  downloadTrailYaml(
                    yaml = recordedGestures.toTrailYaml(),
                    filename = "recording-$ts.trail.yaml",
                  )
                },
                onRunAll = {
                  // Dispatch the whole recording as a single trail YAML — the daemon's runYaml
                  // path runs the steps in order and stops at the first failure, same as
                  // verifying a saved trail. Matches the desktop tab's Verify Trail behavior
                  // minus the trailhead step (Phase 3e+ will add trailhead pickers to the web).
                  coroutineScope.launch {
                    val result = client.runTrailYaml(
                      state.selected.trailblazeDeviceId,
                      recordedGestures.toTrailYaml(),
                    )
                    if (result?.success != true) {
                      Console.log(
                        "[WebDevicesPage] Run all failed: ${client.lastErrorMessage ?: "unknown"}",
                      )
                    }
                  }
                },
                modifier = Modifier
                  .width(360.dp)
                  .fillMaxHeight(),
              )
            }
          }
        }
      }
        } // close inner Column (target app + when block)
      } // close outer Column (AppHeader + inner Column)
    } // close Surface
  } // close TrailblazeTheme
} // close fun WebDevicesPage

/**
 * Top app bar — replaces the centered "Live Device Viewer" text with a left-aligned bar
 * carrying the page title plus contextual badges: the connected device name (when one is
 * live) and a pulsing red dot when the user is recording. Gives the page a real-product
 * feel instead of the bare-Compose centered-text MVP look.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppHeader(
  isRecording: Boolean,
  connectedDeviceLabel: String?,
) {
  val pulseAlpha = remember { Animatable(1f) }
  LaunchedEffect(isRecording) {
    if (isRecording) {
      // Slow alpha breathe — the dot "lives" so the recording state is unmissable without
      // being annoying. Loops forever while isRecording stays true.
      while (true) {
        pulseAlpha.animateTo(0.35f, animationSpec = tween(durationMillis = 700))
        pulseAlpha.animateTo(1f, animationSpec = tween(durationMillis = 700))
      }
    } else {
      pulseAlpha.snapTo(1f)
    }
  }
  TopAppBar(
    title = {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Trailblaze", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.width(8.dp))
        Text(
          text = "Live Device Viewer",
          style = MaterialTheme.typography.titleMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    },
    actions = {
      if (connectedDeviceLabel != null) {
        AssistChip(
          onClick = {},
          enabled = false,
          label = { Text(connectedDeviceLabel) },
          modifier = Modifier.padding(end = 8.dp),
        )
      }
      if (isRecording) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          modifier = Modifier.padding(end = 16.dp),
        ) {
          Canvas(modifier = Modifier.size(10.dp)) {
            drawCircle(color = Color(0xFFE53935).copy(alpha = pulseAlpha.value))
          }
          Spacer(modifier = Modifier.width(6.dp))
          Text(
            text = "Recording",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.error,
          )
        }
      }
    },
    colors = TopAppBarDefaults.topAppBarColors(
      containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ),
  )
}

/** Dropdown row for picking a device plus an optional Refresh button. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeviceSelectorRow(
  devices: List<TrailblazeConnectedDeviceSummary>,
  selectedDevice: TrailblazeConnectedDeviceSummary?,
  onDeviceSelected: ((TrailblazeConnectedDeviceSummary) -> Unit)?,
  onRefresh: (() -> Unit)?,
  modifier: Modifier = Modifier,
) {
  Row(
    modifier = modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
      expanded = expanded && onDeviceSelected != null,
      onExpandedChange = { if (onDeviceSelected != null) expanded = it },
      modifier = Modifier.weight(1f),
    ) {
      OutlinedTextField(
        value = selectedDevice?.let { "${it.description} (${it.instanceId})" }
          ?: if (devices.isEmpty()) "No devices available" else "Select a device",
        onValueChange = {},
        readOnly = true,
        singleLine = true,
        enabled = onDeviceSelected != null && devices.isNotEmpty(),
        trailingIcon = {
          if (onDeviceSelected != null) {
            ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
          }
        },
        modifier = Modifier
          .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
          .fillMaxWidth(),
      )
      if (onDeviceSelected != null) {
        ExposedDropdownMenu(
          expanded = expanded,
          onDismissRequest = { expanded = false },
        ) {
          devices.forEach { device ->
            DropdownMenuItem(
              text = {
                Column {
                  Text(
                    text = device.description,
                    style = MaterialTheme.typography.bodyMedium,
                  )
                  Text(
                    text = device.instanceId,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
              },
              onClick = {
                expanded = false
                onDeviceSelected(device)
              },
            )
          }
        }
      }
    }

    if (onRefresh != null) {
      Button(onClick = onRefresh) {
        Text("Refresh")
      }
    }
  }
}

/** Dropdown row for picking the daemon's current target app. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TargetAppSelectorRow(
  targetApps: List<TargetAppSummary>,
  currentTargetAppId: String?,
  onSelect: (TargetAppSummary) -> Unit,
  modifier: Modifier = Modifier,
) {
  val currentApp = targetApps.firstOrNull { it.id == currentTargetAppId }
  var expanded by remember { mutableStateOf(false) }

  ExposedDropdownMenuBox(
    expanded = expanded,
    onExpandedChange = { expanded = it },
    modifier = modifier.fillMaxWidth(),
  ) {
    OutlinedTextField(
      value = currentApp?.displayName ?: "Select a target app",
      onValueChange = {},
      readOnly = true,
      singleLine = true,
      label = { Text("Target app") },
      trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
      modifier = Modifier
        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
        .fillMaxWidth(),
    )
    ExposedDropdownMenu(
      expanded = expanded,
      onDismissRequest = { expanded = false },
    ) {
      targetApps.forEach { app ->
        DropdownMenuItem(
          text = {
            Column {
              Text(text = app.displayName, style = MaterialTheme.typography.bodyMedium)
              Text(
                text = app.id,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          },
          onClick = {
            expanded = false
            onSelect(app)
          },
        )
      }
    }
  }
}

/** URL bar shown when the connected device is a web/Playwright device. */
@Composable
private fun WebUrlBar(
  onNavigate: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  var url by remember { mutableStateOf("") }
  Row(
    modifier = modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    OutlinedTextField(
      value = url,
      onValueChange = { url = it },
      singleLine = true,
      label = { Text("URL") },
      placeholder = { Text("https://...") },
      modifier = Modifier.weight(1f),
    )
    Button(
      onClick = { if (url.isNotBlank()) onNavigate(url) },
      enabled = url.isNotBlank(),
    ) {
      Text("Go")
    }
  }
}

/** Per-gesture replay state, threaded into [GestureCard] for visual feedback. */
internal sealed interface ReplayStatus {
  object Running : ReplayStatus
  object Success : ReplayStatus
  data class Failed(val message: String?) : ReplayStatus
}

/**
 * Right-side panel showing the current recording's action cards. Each card displays the
 * gesture's YAML and exposes Replay + Delete. Footer has Clear + Save Trail.
 *
 * This is the wasmJs-side analogue of the desktop recording tab's right pane. When the
 * basic tool classes (TapOnPointTrailblazeTool, …) eventually move to commonMain, this can
 * be replaced with the shared ActionYamlCard list from `RecordingScreenComposable`.
 */
@Composable
private fun RecordedGesturePanel(
  gestures: List<WebGesture>,
  isRecording: Boolean,
  replayStatuses: Map<Long, ReplayStatus>,
  onReplay: (WebGesture) -> Unit,
  onDelete: (WebGesture) -> Unit,
  onClear: () -> Unit,
  onSaveTrail: () -> Unit,
  onRunAll: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Surface(
    modifier = modifier,
    color = MaterialTheme.colorScheme.surfaceContainer,
    shape = RoundedCornerShape(12.dp),
    tonalElevation = 1.dp,
  ) {
    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
      // Header — title on its own line so it never gets squeezed by the action buttons. The
      // recording indicator pill matches the AppHeader's pulsing-dot pattern so the recording
      // state reads the same wherever it appears.
      Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
          text = "Recording",
          style = MaterialTheme.typography.titleMedium,
          color = MaterialTheme.colorScheme.onSurface,
          modifier = Modifier.weight(1f),
        )
        AssistChip(
          onClick = {},
          enabled = false,
          label = {
            Text(
              text = "${gestures.size} step${if (gestures.size == 1) "" else "s"}",
              style = MaterialTheme.typography.labelMedium,
            )
          },
        )
      }
      Spacer(modifier = Modifier.height(8.dp))
      // Buttons stay visible-but-disabled when the recording is empty, so they're always
      // discoverable. Each button enables independently on `gestures.isNotEmpty()` —
      // single source of truth for the empty state, no Row-level visibility gate to
      // diverge from. Save Trail with empty gestures would download a bare `- tools:`
      // stub; Run All would dispatch an empty trail.
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        OutlinedButton(
          onClick = onClear,
          enabled = gestures.isNotEmpty(),
          modifier = Modifier.weight(1f),
        ) { Text("Clear") }
        OutlinedButton(
          onClick = onRunAll,
          enabled = gestures.isNotEmpty(),
          modifier = Modifier.weight(1f),
        ) { Text("Run All") }
        Button(
          onClick = onSaveTrail,
          enabled = gestures.isNotEmpty(),
          modifier = Modifier.weight(1f),
        ) { Text("Save Trail") }
      }
      Spacer(modifier = Modifier.height(8.dp))
      HorizontalDivider()
      if (gestures.isEmpty()) {
        Box(
          modifier = Modifier.fillMaxSize().padding(24.dp),
          contentAlignment = Alignment.Center,
        ) {
          Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
              text = if (isRecording) "🎬" else "👆",
              style = MaterialTheme.typography.displaySmall,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
              text = if (isRecording) {
                "Tap, swipe, or type on the device to record steps."
              } else {
                "Press Record above to start capturing gestures, or use Tools to pick a tool directly."
              },
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
          }
        }
      } else {
        Spacer(modifier = Modifier.height(8.dp))
        val listState = androidx.compose.foundation.lazy.rememberLazyListState()
        // Auto-scroll to the most recent card when a new gesture lands during recording.
        LaunchedEffect(gestures.size) {
          if (gestures.isNotEmpty()) {
            listState.animateScrollToItem(gestures.size - 1)
          }
        }
        LazyColumn(
          modifier = Modifier.fillMaxSize(),
          state = listState,
          verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          // `itemsIndexed` so `stepNumber` is O(1) per item. The previous
          // `gestures.indexOf(gesture)` was O(N) per item per recompose — 10k comparisons
          // per scroll at 100 cards.
          itemsIndexed(items = gestures, key = { _, it -> it.id }) { index, gesture ->
            GestureCard(
              gesture = gesture,
              stepNumber = index + 1,
              replayStatus = replayStatuses[gesture.id],
              onReplay = { onReplay(gesture) },
              onDelete = { onDelete(gesture) },
            )
          }
        }
      }
    }
  }
}

/** Single recorded-gesture card: step badge + label, YAML preview, Replay + Delete buttons. */
@Composable
private fun GestureCard(
  gesture: WebGesture,
  stepNumber: Int,
  replayStatus: ReplayStatus?,
  onReplay: () -> Unit,
  onDelete: () -> Unit,
  modifier: Modifier = Modifier,
) {
  // Tint the card according to the in-flight replay state so the user can see at a glance
  // that the replay request is running / succeeded / failed.
  val containerColor = when (replayStatus) {
    is ReplayStatus.Running -> MaterialTheme.colorScheme.secondaryContainer
    is ReplayStatus.Success -> MaterialTheme.colorScheme.tertiaryContainer
    is ReplayStatus.Failed -> MaterialTheme.colorScheme.errorContainer
    null -> MaterialTheme.colorScheme.surface
  }
  Card(
    modifier = modifier.fillMaxWidth(),
    shape = RoundedCornerShape(8.dp),
    colors = CardDefaults.cardColors(containerColor = containerColor),
    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
  ) {
    Column(modifier = Modifier.padding(10.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        // Numbered badge — circular, accent-colored, matches the visual weight of the
        // gesture label so the timeline reads as "step 1: Tap" / "step 2: Swipe" at a glance.
        Box(
          modifier = Modifier
            .size(24.dp)
            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp)),
          contentAlignment = Alignment.Center,
        ) {
          Text(
            text = "$stepNumber",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onPrimary,
          )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
          text = gesture.displayLabel,
          style = MaterialTheme.typography.titleSmall,
          color = MaterialTheme.colorScheme.onSurface,
          modifier = Modifier.weight(1f),
        )
        if (replayStatus != null) {
          val statusText = when (replayStatus) {
            is ReplayStatus.Running -> "Running…"
            is ReplayStatus.Success -> "✓"
            is ReplayStatus.Failed -> "✗"
          }
          Text(
            text = statusText,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(end = 6.dp),
          )
        }
      }
      if (replayStatus is ReplayStatus.Failed && replayStatus.message != null) {
        Spacer(modifier = Modifier.height(2.dp))
        Text(
          text = replayStatus.message,
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.error,
        )
      }
      Spacer(modifier = Modifier.height(6.dp))
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(6.dp))
          .padding(horizontal = 8.dp, vertical = 6.dp),
      ) {
        Text(
          text = gesture.toYaml(),
          style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
          color = MaterialTheme.colorScheme.onSurface,
        )
      }
      Spacer(modifier = Modifier.height(6.dp))
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        OutlinedButton(
          onClick = onReplay,
          enabled = replayStatus !is ReplayStatus.Running,
          contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp),
          modifier = Modifier.weight(1f),
        ) {
          Text("Replay", style = MaterialTheme.typography.labelMedium)
        }
        OutlinedButton(
          onClick = onDelete,
          // Disable Delete while a replay is in-flight — matches the Replay button's guard
          // and avoids the confusing state where the gesture disappears from the UI but the
          // daemon is still executing the step server-side.
          enabled = replayStatus !is ReplayStatus.Running,
          contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp),
          modifier = Modifier.weight(1f),
        ) {
          Text("Delete", style = MaterialTheme.typography.labelMedium)
        }
      }
    }
  }
}
