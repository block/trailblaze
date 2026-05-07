package xyz.block.trailblaze.ui.tabs.recording

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import xyz.block.trailblaze.devices.TrailblazeConnectedDeviceSummary
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.devices.WebInstanceIds
import xyz.block.trailblaze.host.devices.MaestroConnectedDevice
import xyz.block.trailblaze.host.devices.TrailblazeDeviceService
import xyz.block.trailblaze.host.devices.WebBrowserState
import xyz.block.trailblaze.host.rules.BasePlaywrightNativeTest
import xyz.block.trailblaze.host.recording.MaestroDeviceScreenStream
import xyz.block.trailblaze.host.recording.MaestroInteractionToolFactory
import xyz.block.trailblaze.host.recording.OnDeviceRpcDeviceScreenStream
import xyz.block.trailblaze.host.recording.OnDeviceRpcScreenStateProvider
import xyz.block.trailblaze.logs.client.TrailblazeSessionManager
import xyz.block.trailblaze.mcp.android.ondevice.rpc.OnDeviceRpcClient
import xyz.block.trailblaze.util.AccessibilityServiceSetupUtils
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.util.HostAndroidDeviceConnectUtils
import java.io.IOException
import xyz.block.trailblaze.logs.client.LogEmitter
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.playwright.recording.PlaywrightDeviceScreenStream
import xyz.block.trailblaze.playwright.recording.PlaywrightInteractionToolFactory
import xyz.block.trailblaze.recording.InteractionRecorder
import xyz.block.trailblaze.host.recording.RecordingLlmService
import xyz.block.trailblaze.llm.RunYamlRequest
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.llm.TrailblazeReferrer
import xyz.block.trailblaze.llm.providers.TrailblazeDynamicLlmTokenProvider
import xyz.block.trailblaze.model.TrailblazeConfig
import xyz.block.trailblaze.config.ToolYamlConfig
import xyz.block.trailblaze.config.ToolYamlLoader
import xyz.block.trailblaze.config.TrailheadMetadata
import xyz.block.trailblaze.toolcalls.ToolName
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.toolName
import xyz.block.trailblaze.ui.TrailblazeDeviceManager
import xyz.block.trailblaze.ui.recording.ConnectionState
import xyz.block.trailblaze.ui.recording.DeviceDropdown
import xyz.block.trailblaze.ui.recording.RecordingDeviceConnection
import xyz.block.trailblaze.ui.recording.RecordingTabState
import xyz.block.trailblaze.ui.recording.TargetDropdown
import xyz.block.trailblaze.ui.recording.formatDeviceLabel

// `RecordingTabState`, `RecordingDeviceConnection`, `ConnectionState`, and the dropdowns
// moved to `xyz.block.trailblaze.ui.recording.RecordingTabModels` in trailblaze-ui/commonMain
// so they compile to wasmJs alongside JVM. The orchestration composable below stays here
// because it reaches into JVM-only `TrailblazeDeviceManager`, `RecordingLlmService`, and
// `connectToDevice` (Maestro/Playwright drivers).

/**
 * Top-level composable for the Record tab. Provides a device dropdown/switcher
 * and manages the lifecycle of the [DeviceScreenStream] and [InteractionRecorder].
 */
@Composable
fun RecordingTabComposable(
  deviceManager: TrailblazeDeviceManager,
  currentTrailblazeLlmModelProvider: () -> TrailblazeLlmModel,
  llmTokenProvider: TrailblazeDynamicLlmTokenProvider,
  /**
   * Save handler — returns the absolute path the YAML was written to (or null on failure)
   * so the recording UI can display a confirmation panel with the actual destination.
   */
  onSaveTrail: (String) -> String?,
  /**
   * App-lifetime state holder. Owns `selectedDevice`, `connectionState`, and `recorder` so
   * navigating away from the tab and back doesn't blow away the user's recording. The
   * `recordTab` factory in `TrailblazeBuiltInTabs` constructs one instance per app boot.
   *
   * Parameterized on [InteractionRecorder] (the host-side recorder type — still
   * trailblaze-common/jvmAndAndroid because of `toLogPayload` reflection) while the state
   * holder itself lives in trailblaze-ui/commonMain. Once the recorder moves, the type
   * parameter goes away.
   */
  state: RecordingTabState<InteractionRecorder>,
) {
  val scope = rememberCoroutineScope()
  val deviceState by deviceManager.deviceStateFlow.collectAsState()
  val webBrowserState by deviceManager.webBrowserStateFlow.collectAsState()

  // Mirror what `trailblaze device list` shows. Direct caveat: the deviceManager's
  // `targetDeviceFilter` strips PLAYWRIGHT_NATIVE / PLAYWRIGHT_ELECTRON / COMPOSE from the
  // state flow unless the user has enabled "web mode" in settings (see TrailblazeDeviceManager
  // line ~138). DeviceListCommand works around this by post-filtering and explicitly re-adding
  // the playwright-native singleton plus any running browsers — we do the same thing here so
  // the recording tab's dropdown matches the CLI's `device list` output regardless of the web-
  // mode setting. Filter rules: drop Revyl cloud devices (require revyl CLI) and hidden
  // platforms (Compose desktop self-driver) — same defaults as `device list` without `--all`.
  val availableDevices: List<TrailblazeConnectedDeviceSummary> = remember(deviceState, webBrowserState) {
    val filtered = deviceState.devices.values
      .map { it.device }
      .filter {
        it.trailblazeDriverType != TrailblazeDriverType.REVYL_ANDROID &&
          it.trailblazeDriverType != TrailblazeDriverType.REVYL_IOS &&
          !it.platform.hidden
      }
    val seen = filtered.map { it.instanceId to it.platform }.toMutableSet()
    val withRunningBrowsers = filtered + deviceManager.webBrowserManager.getAllRunningBrowserSummaries()
      .filter { (it.instanceId to it.platform) !in seen }
      .also { added -> added.forEach { seen += it.instanceId to it.platform } }
    if (withRunningBrowsers.none { it.instanceId == WebInstanceIds.PLAYWRIGHT_NATIVE }) {
      withRunningBrowsers + TrailblazeConnectedDeviceSummary(
        trailblazeDriverType = TrailblazeDriverType.PLAYWRIGHT_NATIVE,
        instanceId = WebInstanceIds.PLAYWRIGHT_NATIVE,
        description = "Playwright Browser (Native)",
      )
    } else {
      withRunningBrowsers
    }
  }

  // All three fields are read/written through the hoisted state holder so the user's
  // connection + in-flight recording survive a navigation away from this tab and back.
  // Local `var x by ...` delegates keep the rest of the body unchanged.
  var selectedDevice by state.selectedDevice
  var connectionState by state.connectionState
  var recorder by state.recorder

  // Auto-select the first device if none selected, or clear if selected device is gone.
  // Compare by trailblazeDeviceId rather than full object equality: when a web slot
  // transitions from the synthesized "Playwright Browser (Native)" placeholder to the
  // post-launch entry returned by `getAllRunningBrowserSummaries` (different `description`),
  // the canonical (instanceId, platform) identity is unchanged but the data-class equality
  // check would incorrectly treat it as "device disappeared" and force the user to reselect
  // the dropdown after Connect — that's the flash-and-reset behavior we hit on first launch.
  LaunchedEffect(availableDevices, selectedDevice) {
    val currentSelection = selectedDevice
    when {
      currentSelection == null && availableDevices.isNotEmpty() -> {
        selectedDevice = availableDevices.first()
      }
      currentSelection != null -> {
        val match = availableDevices.firstOrNull { it.trailblazeDeviceId == currentSelection.trailblazeDeviceId }
        if (match == null) {
          selectedDevice = availableDevices.firstOrNull()
          connectionState = ConnectionState.Idle
          recorder = null
        } else if (match !== currentSelection) {
          // Same identity, fresher fields (e.g. description changed post-launch). Adopt the
          // updated entry without churning connection state — the user shouldn't notice.
          selectedDevice = match
        }
      }
    }
  }

  // No `DisposableEffect(Unit) { onDispose { ... = null } }`. The previous version cleared
  // connectionState and recorder on dispose, which is exactly the behavior the user was
  // hitting as "navigated away and everything was gone". State is owned by [RecordingTabState]
  // now and persists across tab switches.

  // The (target app, device) pair is what determines which tools are available at runYaml time —
  // two different target apps on the same Android device can expose entirely different toolsets
  // (custom MCP tools, target-scoped YAML tools, exclusion overrides). Surface BOTH selectors
  // here so the author can confirm the combo without bouncing out to the Devices tab. Target
  // writes go through the shared `settingsRepo`, so a change here propagates to the rest of
  // the app (Sessions tab Rerun, MCP, etc.) — that's the intended cross-tab behavior, not a leak.
  val serverState by deviceManager.settingsRepo.serverStateFlow.collectAsState()
  val selectedTargetApp = remember(serverState.appConfig.selectedTargetAppId, deviceManager.availableAppTargets) {
    deviceManager.availableAppTargets.firstOrNull {
      it.id == serverState.appConfig.selectedTargetAppId
    }
  }

  // Trailheads can be declared two ways:
  //   1. *.trailhead.yaml file with a `trailhead:` block. Walked from the classpath via
  //      `ToolYamlLoader.discoverShortcutsAndTrailheads()`.
  //   2. `@TrailblazeToolClass(trailheadTo = "...")` annotation on a class-backed tool. The
  //      annotation form lets a tool class self-declare without a sidecar YAML — preferred for
  //      class-backed implementations because the metadata stays attached to the code.
  // We merge both sources so trailheads show up in the picker regardless of which form they use.
  // Falls back to all discovered trailheads when the target isn't decided yet, since "no target →
  // no filter" is friendlier than "no target → empty list, looks broken."
  val driverType = selectedDevice?.trailblazeDriverType
  val targetScopedTrailheads = remember(selectedTargetApp, driverType) {
    val yamlTrailheads = ToolYamlLoader.discoverShortcutsAndTrailheads().values
      .filter { it.trailhead != null }
    val target = selectedTargetApp ?: return@remember yamlTrailheads
    val driver = driverType ?: return@remember yamlTrailheads

    // Combine yaml-only and class-backed tool names registered with this target so we can
    // filter both sources of trailheads against a single membership check.
    val classBackedTools = target.getCustomToolsForDriver(driver)
    val classBackedToolNames: Set<ToolName> = classBackedTools.map { it.toolName() }.toSet()
    val targetToolNames: Set<ToolName> =
      target.getCustomYamlToolNamesForDriver(driver) + classBackedToolNames

    // Synthesize a `ToolYamlConfig` for any class-backed tool whose annotation declares a
    // non-empty `trailheadTo`. Picker UI keeps speaking the same `ToolYamlConfig` shape, so a
    // class-only trailhead displays identically to a YAML-defined one (id + destination).
    val annotationTrailheads = classBackedTools.mapNotNull { kclass ->
      val annotation = kclass.annotations.firstOrNull { it is TrailblazeToolClass } as? TrailblazeToolClass
        ?: return@mapNotNull null
      val destination = annotation.trailheadTo.takeIf { it.isNotBlank() } ?: return@mapNotNull null
      ToolYamlConfig(
        id = annotation.name,
        toolClass = kclass.qualifiedName,
        trailhead = TrailheadMetadata(to = destination),
      )
    }

    // Dedupe by id — a class with both an annotation and a sidecar YAML should appear once.
    // Prefer the YAML entry (it carries description + parameter overrides); the annotation
    // entry is the fallback for classes without a YAML companion.
    val byId = LinkedHashMap<String, ToolYamlConfig>()
    yamlTrailheads.forEach { byId[it.id] = it }
    annotationTrailheads.forEach { byId.putIfAbsent(it.id, it) }

    byId.values.filter { ToolName(it.id) in targetToolNames }
  }
  var selectedTrailhead by state.selectedTrailhead
  var selectedTrailheadParams by state.selectedTrailheadParams

  // Clear the selected trailhead if the user changes target/driver and the previously-picked
  // trailhead is no longer in scope. Otherwise the dropdown shows a stale selection that
  // doesn't appear in its own option list — confusing, and the selection wouldn't actually
  // run against the new target. Compare by id (trailheads are data-class equal but the list
  // entries can be re-instantiated across recompositions). Param values clear alongside —
  // they're only meaningful for the trailhead they were configured against.
  LaunchedEffect(targetScopedTrailheads, selectedTrailhead) {
    val current = selectedTrailhead ?: return@LaunchedEffect
    if (targetScopedTrailheads.none { it.id == current.id }) {
      selectedTrailhead = null
      selectedTrailheadParams = emptyMap()
    }
  }

  Column(
    modifier = Modifier
      .fillMaxSize()
      .padding(16.dp),
  ) {
    // Target app selector row
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text(
        text = "Target:",
        style = MaterialTheme.typography.titleSmall,
      )
      if (deviceManager.availableAppTargets.isEmpty()) {
        Text(
          text = "No target apps configured.",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      } else {
        TargetDropdown(
          targets = deviceManager.availableAppTargets.toList(),
          selectedTarget = selectedTargetApp,
          onTargetSelected = { target ->
            // Push to the shared settings repo. No connection teardown — the target only
            // matters at runYaml time (it's read fresh from settings when Replay fires), so
            // an in-flight recording doesn't need to be reset just because the author flipped
            // to a different app.
            deviceManager.settingsRepo.targetAppSelected(target)
          },
          modifier = Modifier.weight(1f),
        )
      }
    }

    Spacer(Modifier.height(8.dp))

    // Trailhead is no longer a top-row selector — it lives inside the recording controls panel
    // on the right (`TrailheadSlot` in `RecordingScreenComposable`), framed as part of the
    // recording's setup rather than device configuration. Discovery + scoping still happens
    // here (so the slot doesn't have to know about target/driver), and the result is passed
    // through to the screen composable below.

    // Device selector row
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text(
        text = "Device:",
        style = MaterialTheme.typography.titleSmall,
      )

      if (availableDevices.isEmpty()) {
        Text(
          text = "No devices available. Connect a mobile device or launch a web browser from the Devices tab.",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      } else {
        // Connect-on-select. The user's mental model is "I picked the device, now show me
        // the device" — making them click a separate Connect button after picking adds a
        // round-trip the previous flow forced for no upside. Captured as a local fn so the
        // explicit Connect button (kept for the Error → retry path) can fire it too without
        // duplicating the recorder + session bootstrap.
        fun triggerConnect(device: TrailblazeConnectedDeviceSummary) {
          connectionState = ConnectionState.Connecting
          scope.launch {
            val result = connectToDevice(device, deviceManager, currentTrailblazeLlmModelProvider)
            connectionState = result
            if (result is ConnectionState.Connected) {
              val session = TrailblazeSession(
                sessionId = SessionId("recording-${Clock.System.now().toEpochMilliseconds()}"),
                startTime = Clock.System.now(),
              )
              recorder = InteractionRecorder(
                // Recording surface doesn't need server log delivery — the recorder's
                // own _interactions/logs lists are what feed the UI and the saved YAML.
                // A no-op emitter keeps the recorder's stateless-logging contract honored
                // without coupling to TrailblazeLogger (jvmAndAndroid-only because of
                // OkHttp interceptors in `logLlmRequest`).
                logEmitter = LogEmitter { /* no-op */ },
                session = session,
                scope = scope,
                toolFactory = result.connection.toolFactory,
              ).also {
                // Recording is implicit on Connect — every interaction with the device
                // through this surface gets captured by default. The author decides at
                // the end whether to Save Trail or Discard, rather than committing
                // upfront. See the philosophy thread: "the recording tab is a view of
                // the current session; Save promotes ephemeral to kept."
                it.startRecording()
              }
            }
          }
        }

        DeviceDropdown(
          devices = availableDevices,
          selectedDevice = selectedDevice,
          onDeviceSelected = { device ->
            if (device != selectedDevice) {
              selectedDevice = device
              recorder = null
              triggerConnect(device)
            }
          },
          modifier = Modifier.weight(1f),
        )

        val currentSelection = selectedDevice
        when (connectionState) {
          is ConnectionState.Idle, is ConnectionState.Error -> {
            // The auto-connect path covers the happy case; this button is the explicit
            // retry affordance after a failure (or for the very first open before any
            // device has been picked).
            Button(
              onClick = { currentSelection?.let { triggerConnect(it) } },
              enabled = currentSelection != null,
            ) {
              Text("Connect")
            }
          }
          is ConnectionState.Connecting -> {
            // Inline indicator stays as a small mirror of the main-area panel below; the
            // panel is the load-bearing one for "is something happening" feedback.
            CircularProgressIndicator(
              modifier = Modifier.padding(4.dp),
              strokeWidth = 2.dp,
            )
          }
          is ConnectionState.Connected -> {
            Text(
              text = "Connected",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.primary,
            )
          }
        }
      }
    }

    // Show error if any
    if (connectionState is ConnectionState.Error) {
      Spacer(Modifier.height(4.dp))
      Text(
        text = (connectionState as ConnectionState.Error).message,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
      )
    }

    Spacer(Modifier.height(12.dp))

    // Main content
    val connected = connectionState as? ConnectionState.Connected
    val currentRecorder = recorder
    if (connected != null && currentRecorder != null) {
      val currentLlmModel = currentTrailblazeLlmModelProvider()
      val llmService = remember(currentLlmModel, llmTokenProvider) {
        RecordingLlmService(
          trailblazeLlmModel = currentLlmModel,
          tokenProvider = llmTokenProvider,
        )
      }
      DisposableEffect(llmService) {
        onDispose { llmService.close() }
      }
      RecordingScreenComposable(
        stream = connected.connection.stream,
        recorder = currentRecorder,
        llmService = llmService,
        deviceManager = deviceManager,
        trailblazeDeviceId = connected.connection.trailblazeDeviceId,
        driverType = connected.connection.trailblazeDriverType,
        selectedTrailhead = selectedTrailhead,
        selectedTrailheadParams = selectedTrailheadParams,
        availableTrailheads = targetScopedTrailheads,
        onTrailheadSelected = { config, params ->
          selectedTrailhead = config
          selectedTrailheadParams = params
        },
        onSaveTrail = onSaveTrail,
        onConnectionLost = { error ->
          // Maestro's local XCUITest server (iOS) flaps occasionally — the underlying okhttp
          // call surfaces a ConnectException that, if uncaught, is fatal via
          // DesktopCoroutineExceptionHandler. Drop back to the device-picker / Connect state
          // so the user can retry without restarting the app. Recording state is already
          // cleared by the child composable.
          val message = (error.message ?: error::class.simpleName ?: "unknown error")
          connectionState = ConnectionState.Error("Lost device connection: $message — click Connect to retry.")
          recorder = null
        },
      )
    } else if (connectionState is ConnectionState.Connecting) {
      // Prominent connect-in-progress panel. The previous flow showed only a tiny
      // spinner up in the header row, which made the multi-second instrumentation /
      // XCUITest bootstrap feel like the app had stalled. Centered, large, and
      // explicit about which device is being connected so the user has a clear
      // signal that work is happening.
      Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
      ) {
        Column(
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          CircularProgressIndicator(
            modifier = Modifier.size(48.dp),
            strokeWidth = 4.dp,
          )
          Text(
            text = "Connecting to ${selectedDevice?.let { formatDeviceLabel(it) } ?: "device"}…",
            style = MaterialTheme.typography.titleMedium,
          )
          Text(
            text = "First connect installs the on-device runner — subsequent connects are near-instant.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    } else {
      Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
      ) {
        Text(
          text = if (availableDevices.isEmpty()) {
            "Connect a mobile device or launch a browser from the Devices tab to start recording."
          } else {
            "Pick a device above to start recording."
          },
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

/**
 * Connects to a device and returns the appropriate stream + tool factory.
 * Runs device creation on IO dispatcher since it can be slow.
 */
private suspend fun connectToDevice(
  device: TrailblazeConnectedDeviceSummary,
  deviceManager: TrailblazeDeviceManager,
  currentTrailblazeLlmModelProvider: () -> TrailblazeLlmModel,
): ConnectionState {
  return try {
    when (device.platform) {
      TrailblazeDevicePlatform.WEB -> {
        // The user picked a web slot. If it's already running, just grab its page manager.
        // Otherwise launch it on demand — the device list shows `Playwright Browser (Native)`
        // as an always-available virtual entry, and we want clicking Connect to "just work"
        // without making the user bounce out to the Devices tab to start the browser first.
        //
        // Force headless when we have to launch one ourselves. The whole point of the
        // recording surface is that the streamed frames ARE the user's window into the
        // device, and that path needs to be the only path under test. A headed browser
        // would let the user "cheat" by interacting with the real Chrome window, hiding
        // bugs in the frame-stream / coordinate-mapping layer we'll eventually expose
        // over HTTP for the WASM client. If a headed browser is already running for this
        // slot, we reuse it as-is — the launchBrowser call no-ops on already-running, so
        // this doesn't override anyone's existing setup.
        val instanceId = device.instanceId
        val pageManager = deviceManager.webBrowserManager.getPageManager(instanceId)
          ?: run {
            // Don't rely on launchBrowser's success-only callback — observe the slot's
            // state flow instead. The catch path inside launchBrowser sets state=Error
            // without invoking onComplete, so a callback-only await would hang forever
            // when (e.g.) the Playwright driver fails to install or Chromium fails to
            // start. Awaiting "Running OR Error" guarantees we make progress in both
            // outcomes and surface a real error to the user.
            val stateFlow = deviceManager.webBrowserManager.browserStateFlow(instanceId)
            deviceManager.webBrowserManager.launchBrowser(
              instanceId = instanceId,
              headless = true,
            )
            val terminal = withContext(Dispatchers.IO) {
              stateFlow.first { it is WebBrowserState.Running || it is WebBrowserState.Error }
            }
            if (terminal is WebBrowserState.Error) {
              return ConnectionState.Error("Failed to launch browser '$instanceId': ${terminal.message}")
            }
            deviceManager.webBrowserManager.getPageManager(instanceId)
              ?: return ConnectionState.Error("Failed to launch browser '$instanceId'")
          }
        val stream = PlaywrightDeviceScreenStream(pageManager)
        val toolFactory = PlaywrightInteractionToolFactory(stream)

        // Adopt the running browser as the device manager's "active Playwright-native test"
        // for this device id. Without this, a per-card Replay round-trips through
        // `runYaml` → `runPlaywrightNativeYaml`, finds no cached test, and constructs a
        // fresh `BasePlaywrightNativeTest` — which launches a NEW headed browser and
        // executes the YAML there while the recording surface (still wired to the original
        // pageManager) shows nothing. Mirror of the MCP bridge's WEB device handler
        // (`TrailblazeMcpBridgeImpl.executeToolViaRpc`): wrap the existing pageManager and
        // register it so subsequent `runYaml` calls flow through the same browser.
        // The test does NOT own the browser (existingBrowserManager non-null), so closing
        // the cache entry on disconnect won't kill the user's active page.
        val targetTestApp = deviceManager.getCurrentSelectedTargetApp()
        val customToolClasses =
          targetTestApp?.getCustomToolsForDriver(device.trailblazeDriverType) ?: emptySet()
        val configuredLlmModel = currentTrailblazeLlmModelProvider()
        val playwrightTest = BasePlaywrightNativeTest(
          trailblazeLlmModel = configuredLlmModel,
          customToolClasses = customToolClasses,
          appTarget = targetTestApp,
          trailblazeDeviceId = device.trailblazeDeviceId,
          existingBrowserManager = pageManager,
        )
        deviceManager.setActivePlaywrightNativeTest(device.trailblazeDeviceId, playwrightTest)

        ConnectionState.Connected(
          RecordingDeviceConnection(
            stream = stream,
            toolFactory = toolFactory,
            deviceLabel = formatDeviceLabel(device),
            trailblazeDeviceId = device.trailblazeDeviceId,
            trailblazeDriverType = device.trailblazeDriverType,
          )
        )
      }
      TrailblazeDevicePlatform.ANDROID -> {
        // Android takes the on-device RPC path: the accessibility / instrumentation drivers
        // already own the screenshot pipeline and accessibility tree on-device, so the host
        // just polls `GetScreenStateRequest` and dispatches taps via `RunYamlRequest` (the
        // same API production tests use). Going through Maestro from the host would fight
        // with the on-device runner for the `am instrument` slot — that was the wrong shape.
        val targetTestApp = deviceManager.getCurrentSelectedTargetApp()
          ?: return ConnectionState.Error(
            "No target app selected. Pick one in the Target dropdown before connecting.",
          )
        val instrumentationTarget = targetTestApp.getTrailblazeOnDeviceInstrumentationTarget()
        val needsAccessibility =
          device.trailblazeDriverType == TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY

        val rpcClient = OnDeviceRpcClient(
          trailblazeDeviceId = device.trailblazeDeviceId,
          sendProgressMessage = { Console.log("[Recording connect] $it") },
        )
        val screenStateProvider = OnDeviceRpcScreenStateProvider(
          rpc = rpcClient,
          requireAccessibilityService = needsAccessibility,
        )

        val (initialResponse, recordingSessionId) = withContext(Dispatchers.IO) {
          // Bootstrap the on-device server: install/reuse APK, ensure `am instrument` is
          // running, and set up the adb-forward port. Idempotent — if the runner is already
          // up, this returns near-instantly.
          HostAndroidDeviceConnectUtils.connectToInstrumentationAndInstallAppIfNotAvailable(
            sendProgressMessage = { Console.log("[Recording connect] $it") },
            deviceId = device.trailblazeDeviceId,
            trailblazeOnDeviceInstrumentationTarget = instrumentationTarget,
            // Match production's connect path so any user-set instrumentation args (e.g.
            // network capture toggles) carry through here too. The replay path through
            // `deviceManager.runYaml` already passes these; the recording connect needs the
            // same behavior or the on-device server starts with a different argv than what
            // a subsequent replay expects.
            additionalInstrumentationArgs = deviceManager.onDeviceInstrumentationArgsProvider(),
          )
          if (needsAccessibility) {
            // Accessibility driver needs the on-device service bound before any tap or
            // hierarchy fetch will work. Production's `connectAndEnsureReady` does the same.
            AccessibilityServiceSetupUtils.enableAccessibilityService(
              deviceId = device.trailblazeDeviceId,
              hostPackage = instrumentationTarget.testAppId,
              sendProgressMessage = { Console.log("[Recording connect] $it") },
            )
          }
          rpcClient.waitForReady(
            timeoutMs = 60_000L,
            requireAndroidAccessibilityService = needsAccessibility,
          )

          // First screen-state call gives us the device dimensions for the `DeviceScreenStream`
          // contract. Without this, the recorder builds with placeholder dimensions and every
          // tap hit-tests against the wrong viewport. Routing through the provider so this
          // call uses the same code path as every subsequent frame poll.
          val response = screenStateProvider.getScreenState(includeScreenshot = false)
            ?: throw IOException("GetScreenState failed at connect")
          response to TrailblazeSessionManager.generateSessionId("recording")
        }

        // Per-tap RunYamlRequest template — copied with new YAML on every gesture and sent
        // straight to the on-device handler via `rpc.rpcCall`. Bypasses
        // `deviceManager.runYaml`'s session-creation + log-await polling, which was adding
        // ~2s per tap in interactive recording. The on-device server still creates a session
        // (overrideSessionId = recordingSessionId pins them all together), but with
        // `awaitCompletion = true` the handler returns the moment the tool finishes.
        val settingsState = deviceManager.settingsRepo.serverStateFlow.value
        val runYamlRequestTemplate = RunYamlRequest(
          yaml = "",
          testName = "recording",
          useRecordedSteps = false,
          trailblazeLlmModel = currentTrailblazeLlmModelProvider(),
          targetAppName = settingsState.appConfig.selectedTargetAppId,
          trailFilePath = null,
          config = TrailblazeConfig(
            overrideSessionId = recordingSessionId,
            sendSessionStartLog = false,
            sendSessionEndLog = false,
            browserHeadless = !settingsState.appConfig.showWebBrowser,
            preferHostAgent = settingsState.appConfig.preferHostAgent,
            captureNetworkTraffic = settingsState.appConfig.captureNetworkTraffic,
          ),
          trailblazeDeviceId = device.trailblazeDeviceId,
          driverType = device.trailblazeDriverType,
          referrer = TrailblazeReferrer.RECORDING_TAB_REPLAY,
        )

        val stream = OnDeviceRpcDeviceScreenStream(
          rpc = rpcClient,
          provider = screenStateProvider,
          runYamlRequestTemplate = runYamlRequestTemplate,
          initialDeviceWidth = initialResponse.deviceWidth,
          initialDeviceHeight = initialResponse.deviceHeight,
        )
        val toolFactory = MaestroInteractionToolFactory(
          deviceWidth = stream.deviceWidth,
          deviceHeight = stream.deviceHeight,
        )
        ConnectionState.Connected(
          RecordingDeviceConnection(
            stream = stream,
            toolFactory = toolFactory,
            deviceLabel = formatDeviceLabel(device),
            trailblazeDeviceId = device.trailblazeDeviceId,
            trailblazeDriverType = device.trailblazeDriverType,
          ),
        )
      }
      TrailblazeDevicePlatform.IOS -> {
        val connectedDevice = withContext(Dispatchers.IO) {
          TrailblazeDeviceService.getConnectedDevice(device.trailblazeDeviceId, device.trailblazeDriverType)
        } ?: return ConnectionState.Error("Device not found: ${device.instanceId}")

        val maestroDevice = connectedDevice as? MaestroConnectedDevice
          ?: return ConnectionState.Error("Recording currently requires a Maestro-backed device; got ${connectedDevice::class.simpleName}")
        val driver = maestroDevice.getMaestroDriver()
        val stream = MaestroDeviceScreenStream(driver)
        val toolFactory = MaestroInteractionToolFactory(
          deviceWidth = stream.deviceWidth,
          deviceHeight = stream.deviceHeight,
        )
        ConnectionState.Connected(
          RecordingDeviceConnection(
            stream = stream,
            toolFactory = toolFactory,
            deviceLabel = formatDeviceLabel(device),
            trailblazeDeviceId = device.trailblazeDeviceId,
            trailblazeDriverType = device.trailblazeDriverType,
          )
        )
      }
      TrailblazeDevicePlatform.DESKTOP -> {
        // The Recording tab streams device frames + records interactions for trail
        // playback. Compose desktop has no equivalent recording flow today — the
        // Compose RPC server exposes screen-state and tool execution for ad hoc
        // demo use, not a continuous frame stream. Surface a clear "not yet
        // wired" error rather than crashing or recording a partial signal.
        return ConnectionState.Error(
          "Recording is not wired up for the Compose desktop driver yet. " +
            "Use the hidden `trailblaze desktop snapshot` command for one-shot captures.",
        )
      }
    }
  } catch (e: Exception) {
    // Include the exception class so typed-but-message-less exceptions (e.g. Maestro's
    // AndroidInstrumentationSetupFailure / AndroidDriverTimeoutException, gRPC
    // StatusRuntimeException with empty descriptions) surface useful detail in the UI
    // instead of "null" or a generic "Connection failed".
    val msg = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
    ConnectionState.Error("Connection failed: $msg")
  }
}
