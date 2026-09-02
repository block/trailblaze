package xyz.block.trailblaze.host.recording

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.Clock
import xyz.block.trailblaze.devices.TrailblazeConnectedDeviceSummary
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.devices.WebInstanceIds
import xyz.block.trailblaze.host.devices.HostIosDriverFactory
import xyz.block.trailblaze.host.devices.MaestroConnectedDevice
import xyz.block.trailblaze.host.devices.TrailblazeDeviceService
import xyz.block.trailblaze.host.devices.WebBrowserState
import xyz.block.trailblaze.host.recording.rpc.HostDeviceSessionManager
import xyz.block.trailblaze.host.rules.BasePlaywrightNativeTest
import xyz.block.trailblaze.llm.RunYamlRequest
import xyz.block.trailblaze.llm.TrailblazeReferrer
import xyz.block.trailblaze.logs.client.TrailblazeSessionManager
import xyz.block.trailblaze.mcp.android.ondevice.rpc.OnDeviceRpcClient
import xyz.block.trailblaze.model.TrailblazeConfig
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.playwright.PlaywrightElectronBrowserManager
import xyz.block.trailblaze.playwright.recording.AttachedPlaywrightDeviceScreenStream
import xyz.block.trailblaze.playwright.recording.PlaywrightDeviceScreenStream
import xyz.block.trailblaze.playwright.recording.PlaywrightInteractionToolFactory
import xyz.block.trailblaze.transport.AndroidWireTransport
import xyz.block.trailblaze.ui.TrailblazeDeviceManager
import xyz.block.trailblaze.ui.recording.ConnectionState
import xyz.block.trailblaze.ui.recording.RecordingDeviceConnection
import xyz.block.trailblaze.ui.recording.formatDeviceLabel
import xyz.block.trailblaze.util.AccessibilityServiceSetupUtils
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.util.HostAndroidDeviceConnectUtils
import java.io.IOException

/**
 * Establishes live connections to devices for the recording surface. Shared between the
 * desktop [xyz.block.trailblaze.ui.tabs.recording.RecordingTabComposable] and the HTTP
 * [xyz.block.trailblaze.host.recording.rpc.DeviceApiEndpoint] so both surfaces use
 * identical connection logic.
 *
 * Thread-safe: [connectToDevice] is a suspend function and all state mutations inside it
 * are confined to IO.
 */
class DeviceConnectionService(private val deviceManager: TrailblazeDeviceManager) {

  /**
   * Connects to [device] and returns the appropriate screen stream + tool factory wrapped
   * in a [ConnectionState]. Runs device creation on the IO dispatcher.
   *
   * [targetAppId] is the app target this connection is for. Callers that know it (the Run
   * dialog, which has the trail's declared target in hand) pass it so the connection binds
   * that app rather than whatever target the daemon happens to have selected; callers that
   * don't pass null and keep the selected target.
   *
   * Idempotent on the Playwright-native path (reuses a running browser). Android and iOS
   * paths are not idempotent — callers must ensure only one connection is live per device
   * at a time.
   */
  suspend fun connectToDevice(
    device: TrailblazeConnectedDeviceSummary,
    targetAppId: String? = null,
  ): ConnectionState = when (val bound = resolveBoundTarget(targetAppId)) {
    is BoundTarget.Unavailable -> ConnectionState.Error(bound.message)
    is BoundTarget.Resolved -> connectToDevice(device, bound)
  }

  /**
   * Connects to [device] for an ALREADY-resolved target, so the resolution the caller checked is
   * the one the connection binds.
   *
   * A caller that resolves first (to record the binding, or to fail an unregistered target before
   * reusing a live connection) can't hand the answer back as an id: `Resolved(null)` means "this
   * daemon has no target selected", and re-resolving null asks the same question again — the
   * selection may have moved to a real app in between, so the connection would open for that app
   * while the caller recorded no binding at all.
   */
  suspend fun connectToDevice(
    device: TrailblazeConnectedDeviceSummary,
    bound: BoundTarget.Resolved,
  ): ConnectionState = try {
    val targetApp = bound.target
    when (device.platform) {
      // PLAYWRIGHT_ELECTRON reports platform WEB (it drives a Chromium-based Electron window over
      // CDP), so it lands in this branch alongside the launch-a-browser web driver. The two diverge
      // on connect: web LAUNCHES a fresh Chromium; electron ATTACHES to an already-running app. The
      // pure `webConnectStrategy` decision keeps that fork explicit and unit-testable.
      TrailblazeDevicePlatform.WEB -> when (webConnectStrategy(device.trailblazeDriverType)) {
        WebConnectStrategy.ATTACH_ELECTRON -> connectElectron(device)
        WebConnectStrategy.LAUNCH_CHROMIUM -> connectWeb(device, targetApp)
      }
      TrailblazeDevicePlatform.ANDROID -> connectAndroid(device, targetApp)
      TrailblazeDevicePlatform.IOS -> connectIos(device, targetApp)
      TrailblazeDevicePlatform.DESKTOP -> ConnectionState.Error(
        "Recording is not wired up for the Compose desktop driver yet. " +
          "Use the hidden `trailblaze desktop snapshot` command for one-shot captures.",
      )
    }
  } catch (e: Exception) {
    val msg = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
    ConnectionState.Error("Connection failed: $msg")
  }

  private suspend fun connectWeb(
    device: TrailblazeConnectedDeviceSummary,
    targetTestApp: TrailblazeHostAppTarget?,
  ): ConnectionState {
    val instanceId = device.instanceId
    val pageManager = deviceManager.webBrowserManager.getPageManager(instanceId)
      ?: run {
        val stateFlow = deviceManager.webBrowserManager.browserStateFlow(instanceId)
        val savedViewport = deviceManager.settingsRepo.serverStateFlow.value.appConfig.webViewport
        // Make the desktop's saved viewport authoritative for this slot — including
        // the "cleared back to default" case. Without this explicit write,
        // [WebBrowserManager.launchBrowser] only records non-null specs, so a slot
        // that earlier received `device create web --emulate "iPhone 14"` would
        // inherit that stale spec here even after the user cleared the desktop's
        // viewport field.
        deviceManager.webBrowserManager.setViewportSpec(
          instanceId = instanceId,
          viewportSpec = savedViewport,
        )
        deviceManager.webBrowserManager.launchBrowser(
          instanceId = instanceId,
          headless = true,
          viewportSpec = savedViewport,
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

    val customToolClasses =
      targetTestApp?.getCustomToolsForDriver(device.trailblazeDriverType) ?: emptySet()
    val playwrightTest = BasePlaywrightNativeTest(
      trailblazeLlmModel = deviceManager.currentTrailblazeLlmModelProvider(),
      customToolClasses = customToolClasses,
      appTarget = targetTestApp,
      trailblazeDeviceId = device.trailblazeDeviceId,
      existingBrowserManager = pageManager,
      // This test is cached for reuse by later runs, so build its rule against the app's
      // configured logs directory — the same one [deviceManager]'s own repo is pinned at.
      // Left unset, the rule would default to `<git root>/logs` and file every reusing
      // session there instead.
      logsDir = deviceManager.logsRepo.logsDir,
    )
    deviceManager.setActivePlaywrightNativeTest(device.trailblazeDeviceId, playwrightTest)

    return ConnectionState.Connected(
      RecordingDeviceConnection(
        stream = stream,
        toolFactory = toolFactory,
        deviceLabel = formatDeviceLabel(device),
        trailblazeDeviceId = device.trailblazeDeviceId,
        trailblazeDriverType = device.trailblazeDriverType,
      ),
    )
  }

  /**
   * Attach-only connect for a Playwright-Electron device: connects Playwright to an
   * already-running Electron app's CDP endpoint (e.g. an app launched with
   * `--remote-debugging-port=9222`) and wraps its live page in the same
   * [PlaywrightDeviceScreenStream] the launch-a-browser web path uses — so the existing
   * `/devices/api/stream` WEB fast path screencasts the Electron window with no changes to
   * the streaming/endpoint layer.
   *
   * Non-destructive: never launches the app and never calls `chromium().launch()`. A refused or
   * silent CDP endpoint surfaces a clear error instead of hanging — the connect handshake is
   * bounded by [PlaywrightElectronBrowserManager]'s connect timeout.
   *
   * The stream is an [AttachedPlaywrightDeviceScreenStream] (an [AutoCloseable]
   * [PlaywrightDeviceScreenStream]) so `HostDeviceSessionManager.remove()` disconnects this
   * externally-attached manager on device disconnect — otherwise each connect/disconnect cycle
   * would leak the manager's Playwright thread + CDP connection (nothing else owns it, unlike the
   * launch-a-browser path where `WebBrowserManager` owns the browser lifecycle).
   */
  private suspend fun connectElectron(device: TrailblazeConnectedDeviceSummary): ConnectionState {
    val cdpUrl = resolveElectronCdpUrl(
      cdpUrlEnv = System.getenv("TRAILBLAZE_ELECTRON_CDP_URL"),
      cdpPortEnv = System.getenv("TRAILBLAZE_ELECTRON_CDP_PORT"),
    )
    // The manager's init does the (timeout-bounded) blocking `connectOverCDP` on its own Playwright
    // thread; run its construction off the caller so it doesn't block the connect coroutine's thread.
    val pageManager = try {
      withContext(Dispatchers.IO) {
        PlaywrightElectronBrowserManager(cdpUrl = cdpUrl)
      }
    } catch (e: Exception) {
      val msg = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
      return ConnectionState.Error(
        "Failed to attach to the Electron app over CDP at $cdpUrl: $msg. " +
          "Start the app with remote debugging enabled (e.g. --remote-debugging-port=9222) " +
          "and confirm nothing else is holding the port.",
      )
    }

    val stream = AttachedPlaywrightDeviceScreenStream(pageManager)
    val toolFactory = PlaywrightInteractionToolFactory(stream)

    return ConnectionState.Connected(
      RecordingDeviceConnection(
        stream = stream,
        toolFactory = toolFactory,
        deviceLabel = formatDeviceLabel(device),
        trailblazeDeviceId = device.trailblazeDeviceId,
        trailblazeDriverType = device.trailblazeDriverType,
      ),
    )
  }

  private suspend fun connectAndroid(
    device: TrailblazeConnectedDeviceSummary,
    targetTestApp: TrailblazeHostAppTarget?,
  ): ConnectionState {
    if (targetTestApp == null) {
      return ConnectionState.Error(
        "No target app selected. Pick one in the Target dropdown before connecting.",
      )
    }
    // Driver-aware: ANDROID_TEST resolves the target's own in-process harness, which may be
    // undeclared — the actionable error beats instrumenting the bundled runner, which knows
    // nothing about the app's process.
    val instrumentationTarget =
      targetTestApp.getTrailblazeOnDeviceInstrumentationTargetForDriver(device.trailblazeDriverType)
        ?: return ConnectionState.Error(targetTestApp.missingInProcessHarnessMessage())
    val needsAccessibility =
      device.trailblazeDriverType == TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY

    val rpcClient = OnDeviceRpcClient(
      trailblazeDeviceId = device.trailblazeDeviceId,
      sendProgressMessage = { Console.log("[DeviceConnectionService] $it") },
      wireTransportMode = AndroidWireTransport.modeFor(device.trailblazeDriverType),
    )
    val screenStateProvider = OnDeviceRpcScreenStateProvider(
      rpc = rpcClient,
      requireAccessibilityService = needsAccessibility,
    )

    // Overall deadline for the whole connect dance (probe -> instrument -> install -> ready).
    // Each piece is individually bounded, but the sequence retries the dance against a device
    // whose on-device RPC channel is dead - a Run click would otherwise hang the HTTP response
    // (and the browser, which has no fetch timeout) for many minutes with no error.
    val connectResult = withTimeoutOrNull(ANDROID_CONNECT_OVERALL_TIMEOUT_MS) {
      withContext(Dispatchers.IO) {
        HostAndroidDeviceConnectUtils.connectToInstrumentationAndInstallAppIfNotAvailable(
          sendProgressMessage = { Console.log("[DeviceConnectionService] $it") },
          deviceId = device.trailblazeDeviceId,
          trailblazeOnDeviceInstrumentationTarget = instrumentationTarget,
          // The daemon's resolved HTTPS port — on a non-default port (TRAILBLAZE_PORT et al.)
          // the old defaulted value reversed tcp:52526 to a host port nothing listens on.
          httpsPort = deviceManager.settingsRepo.portManager.httpsPort,
          additionalInstrumentationArgs = deviceManager.onDeviceInstrumentationArgsProvider(),
          // The same target this connect resolved its own instrumentation from, so the harness
          // stopped and the harness launched cannot come from two different apps. Note for this
          // path specifically: on a machine with the in-process harness installed, connecting to
          // record will force-stop the app under test. See
          // HostAndroidDeviceConnectUtils.planConnectForceStop.
          additionalForceStopTargets = targetTestApp.allInstrumentationTargets().toSet(),
        )
        if (needsAccessibility) {
          AccessibilityServiceSetupUtils.enableAccessibilityService(
            deviceId = device.trailblazeDeviceId,
            hostPackage = instrumentationTarget.testAppId,
            sendProgressMessage = { Console.log("[DeviceConnectionService] $it") },
          )
        }
        rpcClient.waitForReady(
          timeoutMs = 60_000L,
          requireAndroidAccessibilityService = needsAccessibility,
        )

        val response = screenStateProvider.getScreenState(includeScreenshot = false)
          ?: throw IOException("GetScreenState failed at connect")
        response to TrailblazeSessionManager.generateSessionId("recording")
      }
    }
    if (connectResult == null) {
      val runnerPid = runCatching {
        withContext(Dispatchers.IO) { rpcClient.probeRunnerPid() }
      }.getOrDefault("UNKNOWN")
      return ConnectionState.Error(
        "Connecting to ${device.trailblazeDeviceId.instanceId} timed out after " +
          "${ANDROID_CONNECT_OVERALL_TIMEOUT_MS / 1000}s - the on-device RPC channel is not " +
          "answering (runner pid: $runnerPid). Restart the emulator (or the Trailblaze runner " +
          "app on it) and try again.",
      )
    }
    val (initialResponse, recordingSessionId) = connectResult

    val settingsState = deviceManager.settingsRepo.serverStateFlow.value
    val runYamlRequestTemplate = RunYamlRequest(
      yaml = "",
      testName = "recording",
      useRecordedSteps = false,
      trailblazeLlmModel = deviceManager.currentTrailblazeLlmModelProvider(),
      // The target this connection actually bound, so a replay records the app it ran against.
      // Reading the daemon's selection again would name a different app whenever the caller
      // asked for a specific target, or none at all under a workspace-default target.
      targetAppName = targetTestApp.id,
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
    return ConnectionState.Connected(
      RecordingDeviceConnection(
        stream = stream,
        toolFactory = toolFactory,
        deviceLabel = formatDeviceLabel(device),
        trailblazeDeviceId = device.trailblazeDeviceId,
        trailblazeDriverType = device.trailblazeDriverType,
      ),
    )
  }

  /**
   * [targetTestApp] decides which iOS driver this connection gets: a target declaring
   * `hasCustomIosDriver` wraps the base Maestro driver in its own subclass. Passing it is what stops
   * a recording connect from caching a plain unwrapped driver that a later run for a custom-driver
   * target would be handed - see `HostIosDriverFactory.driverWrapperKey`.
   */
  private suspend fun connectIos(
    device: TrailblazeConnectedDeviceSummary,
    targetTestApp: TrailblazeHostAppTarget?,
  ): ConnectionState {
    val connectedDevice = TrailblazeDeviceService.connectDevice(
      trailblazeDeviceId = device.trailblazeDeviceId,
      driverType = device.trailblazeDriverType,
      appTarget = targetTestApp,
    ) ?: return ConnectionState.Error("Device not found: ${device.instanceId}")

    val maestroDevice = connectedDevice as? MaestroConnectedDevice
      ?: return ConnectionState.Error(
        "Recording currently requires a Maestro-backed device; got ${connectedDevice::class.simpleName}",
      )
    val driver = maestroDevice.getMaestroDriver()
    // instanceId is the Simulator UDID on this iOS path — enables the AXe tree overlay.
    val stream = MaestroDeviceScreenStream(driver, iosUdid = maestroDevice.instanceId)
    val toolFactory = MaestroInteractionToolFactory(
      deviceWidth = stream.deviceWidth,
      deviceHeight = stream.deviceHeight,
    )
    return ConnectionState.Connected(
      RecordingDeviceConnection(
        stream = stream,
        toolFactory = toolFactory,
        deviceLabel = formatDeviceLabel(device),
        trailblazeDeviceId = device.trailblazeDeviceId,
        trailblazeDriverType = device.trailblazeDriverType,
      ),
    )
  }

  /** The concrete target a connect would bind, or why this daemon can't bind one. */
  sealed interface BoundTarget {
    /**
     * The target this connect binds. Null when the caller named none and this daemon has none
     * selected either - which the Android path then refuses and the web path treats as "no custom
     * tools".
     */
    data class Resolved(val target: TrailblazeHostAppTarget?) : BoundTarget

    /** The caller named a target this daemon doesn't have, with the message to report. */
    data class Unavailable(val message: String) : BoundTarget
  }

  /**
   * The concrete target a [connectToDevice] call for [targetAppId] would bind: the named one, or
   * whatever this daemon currently has selected when the caller names none.
   *
   * Exposed so a caller can resolve BEFORE it decides whether to reuse a live connection. A target
   * this daemon doesn't have has to fail even when the device is already connected, and the target
   * a live session is recorded against has to be a concrete one rather than "whatever was selected
   * at the time" - otherwise the recorded binding says nothing about which app that connection
   * actually installed.
   */
  fun resolveBoundTarget(targetAppId: String?): BoundTarget {
    val registered = deviceManager.availableAppTargets
    return when (val choice = resolveConnectTarget(targetAppId, registered.map { it.id }.toSet())) {
      is ConnectTarget.DaemonSelected -> BoundTarget.Resolved(deviceManager.getCurrentSelectedTargetApp())
      // Present by construction: resolveConnectTarget only reports Requested for a registered id.
      is ConnectTarget.Requested -> BoundTarget.Resolved(registered.first { it.id == choice.id })
      is ConnectTarget.Unregistered -> BoundTarget.Unavailable(
        "Target app '${choice.id}' is not registered in this daemon " +
          "(available: ${registered.map { it.id }.sorted()}). " +
          "Create the target, or restart Trail Runner to pick up edits.",
      )
    }
  }

  /** Which app target a connect binds, from what the caller asked for. */
  internal sealed interface ConnectTarget {
    /** The caller named no target: bind whatever the daemon has selected. */
    object DaemonSelected : ConnectTarget

    /** The caller named a registered target: bind exactly that one. */
    data class Requested(val id: String) : ConnectTarget

    /** The caller named a target this daemon doesn't have. */
    data class Unregistered(val id: String) : ConnectTarget
  }

  /** How a WEB-platform device establishes its Playwright connection. */
  internal enum class WebConnectStrategy {
    /** Launch a fresh headless Chromium (the `playwright-native` / named web slots). */
    LAUNCH_CHROMIUM,

    /** Attach to an already-running Electron app over CDP (the `playwright-electron` device). */
    ATTACH_ELECTRON,
  }

  // Internal: these are implementation details of the host connect path, not public API of this
  // (public) class — only sibling host code and this module's tests reach them.
  internal companion object {
    /**
     * Overall bound on the Android connect dance. Generous next to one healthy pass (~40s worst
     * case incl. an APK reinstall) but far below the multi-minute retry spiral a dead on-device
     * RPC channel produces.
     */
    const val ANDROID_CONNECT_OVERALL_TIMEOUT_MS = 120_000L

    /** Default CDP remote-debugging port for an Electron app, matching `ElectronAppConfig`. */
    const val DEFAULT_ELECTRON_CDP_PORT = 9222

    /**
     * Decides which app target a connect binds. An explicit id wins over the daemon-wide
     * selection - the caller that names one (the Run dialog, for a trail with a declared target)
     * knows which app this device is being connected for, and the selection can be a different
     * app entirely or move under it while the connect is in flight. An id that isn't registered
     * is [ConnectTarget.Unregistered] rather than a fall back to the selected target, matching
     * the run path's rule for a trail's `config.target`: binding a different app than the one
     * named installs and launches unrelated automation while still reporting the named target.
     * Blank is treated as absent so an empty form field doesn't fail the connect. Pure so the
     * fork is unit-testable without standing up a daemon.
     */
    fun resolveConnectTarget(requestedTargetAppId: String?, registeredIds: Set<String>): ConnectTarget {
      val requested = requestedTargetAppId?.trim()?.takeIf { it.isNotEmpty() }
        ?: return ConnectTarget.DaemonSelected
      return if (requested in registeredIds) ConnectTarget.Requested(requested) else ConnectTarget.Unregistered(requested)
    }

    /**
     * Decides how a WEB-platform device connects. Electron devices attach to a running app over
     * CDP (non-destructive); every other web driver launches its own Chromium. Pure so the fork
     * is unit-testable without standing up a device.
     */
    fun webConnectStrategy(driverType: TrailblazeDriverType): WebConnectStrategy =
      if (driverType == TrailblazeDriverType.PLAYWRIGHT_ELECTRON) {
        WebConnectStrategy.ATTACH_ELECTRON
      } else {
        WebConnectStrategy.LAUNCH_CHROMIUM
      }

    /**
     * Whether a connect for this device actually binds the target app - installs its instrumentation
     * runner (Android), builds the target's own driver wrapper (iOS), or loads its custom tools into
     * a fresh browser (web). Only those connections can be bound to a target and refused for
     * another; for the rest the stream is the same stream whichever target the caller named, so
     * refusing one would reject a request that disconnecting and reconnecting satisfies identically.
     * Pure so the rule is unit-testable without a device.
     *
     * iOS binds through the Maestro driver: a target declaring `hasCustomIosDriver` wraps the base
     * `IOSDriver` in its own subclass, so which target a connect names decides which driver it gets
     * (`HostIosDriverFactory.driverWrapperKey`). The host-native iOS drivers are the exception - they
     * talk to the simulator directly and never build a wrapper, so `hostNativeSimulatorDriver` is
     * the whole exemption and a new driver declaring it is automatically exempt here too.
     *
     * Deliberately coarse on one edge: this decides per platform + driver, so it can't see that two
     * targets which BOTH lack a custom iOS driver would have produced the identical connection.
     * Switching between two such targets on one iOS device is asked to disconnect first even though
     * nothing would change. Costing that honestly: the reconnect is a click, not a rebuild - the
     * driver cache keys on the wrapper, so both plain targets hit the same cached driver. Worth
     * revisiting only if a daemon that ships several plain iOS targets makes it a real annoyance.
     */
    fun bindsTargetApp(platform: TrailblazeDevicePlatform, driverType: TrailblazeDriverType): Boolean =
      when (platform) {
        TrailblazeDevicePlatform.ANDROID -> true
        TrailblazeDevicePlatform.WEB -> webConnectStrategy(driverType) == WebConnectStrategy.LAUNCH_CHROMIUM
        TrailblazeDevicePlatform.IOS -> !driverType.hostNativeSimulatorDriver
        TrailblazeDevicePlatform.DESKTOP -> false
      }

    /**
     * What a connect for this device and [target] is for, in the form the session registry compares.
     * The single place the two connect paths derive it, so they cannot disagree about when a device
     * is already taken. Pure so the rule is unit-testable without a device.
     *
     * The driver half is only ever non-null for an iOS connect that goes through Maestro, because
     * that is the only connection whose driver depends on the target. It matters because the target
     * half alone treats "no target" as a wildcard, and for iOS that wildcard is wrong: a targetless
     * connect built the plain driver, and `HostIosDriverFactory` will close and rebuild it when a
     * connect for a custom-wrapper target lands on the same device. Recording the driver makes that
     * pair a conflict the registry refuses, instead of a share that silently drives the app through
     * the wrong driver - or a rebuild that shuts down a live stream.
     *
     * Note the driver half is redundant whenever it is set: it equals the target half, since the
     * wrapper is keyed on the target's id. The two axes only ever differ on `null`, which is exactly
     * the case they exist to separate - a wildcard on the target, a real value on the driver.
     *
     * Which is why `buildsMaestroDriver` is set here too, rather than inferred from that `null`
     * downstream. A connect that never touches `HostIosDriverFactory` - Android, web, or a
     * host-native iOS driver like AXe - has no driver key because it has no driver of ours at all,
     * and that is a different thing from the plain base driver even though both read as no key.
     * Saying so keeps a wrapper-target holder from refusing an AXe connection it could not have
     * disturbed.
     */
    fun connectionBinding(
      platform: TrailblazeDevicePlatform,
      driverType: TrailblazeDriverType,
      target: TrailblazeHostAppTarget?,
    ): HostDeviceSessionManager.Binding {
      val buildsMaestroDriver = platform == TrailblazeDevicePlatform.IOS &&
        !driverType.hostNativeSimulatorDriver
      if (!bindsTargetApp(platform, driverType)) {
        return HostDeviceSessionManager.Binding(buildsMaestroDriver = buildsMaestroDriver)
      }
      return HostDeviceSessionManager.Binding(
        targetId = target?.id,
        driverKey = if (buildsMaestroDriver) HostIosDriverFactory.driverWrapperKey(target) else null,
        buildsMaestroDriver = buildsMaestroDriver,
      )
    }

    /**
     * Resolves the Electron CDP attach URL: an explicit `TRAILBLAZE_ELECTRON_CDP_URL` wins;
     * otherwise `http://localhost:<port>` where the port comes from `TRAILBLAZE_ELECTRON_CDP_PORT`
     * (default [DEFAULT_ELECTRON_CDP_PORT]). A blank URL, or a port that is missing / non-numeric /
     * outside the valid `1..65535` range, falls through to the default. Shared with
     * [xyz.block.trailblaze.ui.TrailblazeDeviceManager]'s CDP availability probe so the tile that
     * `/devices` offers and the URL this attaches to always agree. Pure — env values are passed in
     * — so it is unit-testable without touching the process environment.
     */
    fun resolveElectronCdpUrl(cdpUrlEnv: String?, cdpPortEnv: String?): String {
      cdpUrlEnv?.takeIf { it.isNotBlank() }?.let { return it.trim() }
      val port = cdpPortEnv?.trim()?.toIntOrNull()?.takeIf { it in 1..65535 } ?: DEFAULT_ELECTRON_CDP_PORT
      return "http://localhost:$port"
    }
  }
}
