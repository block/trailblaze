package xyz.block.trailblaze.host.yaml

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.block.trailblaze.devices.TrailblazeConnectedDeviceSummary
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.exception.TrailblazeSessionCancelledException
import xyz.block.trailblaze.host.TrailblazeHostYamlRunner
import xyz.block.trailblaze.host.networkcapture.AndroidNetworkCaptureRegistry
import xyz.block.trailblaze.host.ios.MobileDeviceUtils
import xyz.block.trailblaze.http.DynamicLlmClient
import xyz.block.trailblaze.llm.RunYamlRequest
import xyz.block.trailblaze.llm.RunYamlResponse
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.llm.TrailblazeReferrer
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.logs.model.getSessionStatus
import xyz.block.trailblaze.mcp.AgentImplementation
import xyz.block.trailblaze.mcp.android.ondevice.rpc.OnDeviceRpcClient
import xyz.block.trailblaze.mcp.android.ondevice.rpc.RpcResult
import xyz.block.trailblaze.model.DesktopAppRunYamlParams
import xyz.block.trailblaze.model.DeviceConnectionStatus
import xyz.block.trailblaze.model.TrailExecutionResult
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.model.TrailblazeOnDeviceInstrumentationTarget
import xyz.block.trailblaze.ui.TrailblazeAnalytics
import xyz.block.trailblaze.ui.TrailblazeDeviceManager
import xyz.block.trailblaze.util.AccessibilityServiceSetupUtils
import xyz.block.trailblaze.util.HostAndroidDeviceConnectUtils
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.util.UiAutomationHandleErrors
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import java.io.File
import java.io.IOException

class DesktopYamlRunner(
  private val trailblazeDeviceManager: TrailblazeDeviceManager,
  private val trailblazeAnalytics: TrailblazeAnalytics,
  private val trailblazeHostAppTargetProvider: () -> TrailblazeHostAppTarget,
  private val dynamicLlmClientProvider: (TrailblazeLlmModel) -> DynamicLlmClient,
) {
  companion object {
    // Force early class loading of nested DeviceConnectionStatus classes
    // This prevents ClassNotFoundException in catch blocks which reference these types
    @Suppress("unused")
    private val connectionFailureClass = DeviceConnectionStatus.DeviceConnectionError.ConnectionFailure::class
    @Suppress("unused")
    private val startingConnectionClass = DeviceConnectionStatus.WithTargetDevice.StartingConnection::class
    @Suppress("unused")
    private val instrumentationRunningClass = DeviceConnectionStatus.WithTargetDevice.TrailblazeInstrumentationRunning::class

    /**
     * Pure decision: did [status] end in the on-device UiAutomation wedge that only a server
     * relaunch can clear? The wedge surfaces ONLY as the on-disk terminal status — the V1 RunYaml
     * RPC is fire-and-forget and the readiness probe can't see it — so this is the host's single
     * detection signal. Kept pure (no device, no RPC) so the relaunch decision is unit-testable.
     *
     * Matches strictly `Ended.Failed` carrying the non-recoverable stale-handle signature. Any
     * other failure (assertion, element-not-found) and any non-failed terminal status return false:
     * a relaunch only gives the NEXT trail a clean server, it never re-runs this trail, so an
     * over-broad match would waste a reinstall+`am instrument` per failing trail and could mask a
     * real on-device crash without ever turning a genuine failure green.
     */
    internal fun shouldRelaunchOnDeviceServer(status: SessionStatus?): Boolean =
      status is SessionStatus.Ended.Failed &&
        UiAutomationHandleErrors.isNonRecoverableStaleHandleSignature(status.exceptionMessage)
  }

  /**
   * Set when a trail's terminal status carries the non-recoverable UiAutomation wedge
   * (see [shouldRelaunchOnDeviceServer]); consumed by [connectAndEnsureReady] to force-restart the
   * shared on-device server before the NEXT trail, then cleared. This runner instance is the
   * daemon-scoped singleton every trail in a CI job routes through, so the flag survives from the
   * wedged trail to its successor.
   */
  @Volatile
  private var instrumentationWedged: Boolean = false

  /**
   * Shortens device description by removing UUID identifiers.
   * Example: "iPhone 16 Pro - iOS 18.4 - 55B5483E-EE63-4605-91DE-B061F19B9D1E" -> "iPhone 16 Pro - iOS 18.4"
   */
  private fun shortenDeviceDescription(description: String): String {
    // Match and remove UUID pattern (8-4-4-4-12 hex digits)
    val uuidPattern =
      Regex(" - [0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}$")
    return description.replace(uuidPattern, "")
  }

  /**
   * Executes a YAML test on the specified device, automatically choosing between
   * on-device instrumentation or host-based execution.
   *
   * This is a non-composable suspend function that can be called directly if you
   * don't need the composable wrapper.
   */
  fun runYaml(desktopAppRunYamlParams: DesktopAppRunYamlParams) {
    val targetTestApp = desktopAppRunYamlParams.targetTestApp
    val trailblazeDeviceId = desktopAppRunYamlParams.runYamlRequest.trailblazeDeviceId
    val forceStopTargetApp = desktopAppRunYamlParams.forceStopTargetApp
    val runYamlRequest = desktopAppRunYamlParams.runYamlRequest
    val onProgressMessage = desktopAppRunYamlParams.onProgressMessage
    val onConnectionStatus = desktopAppRunYamlParams.onConnectionStatus
    val additionalInstrumentationArgs = desktopAppRunYamlParams.additionalInstrumentationArgs
    val onComplete = desktopAppRunYamlParams.onComplete

    // MCP and CLI requests reuse the existing scope. The "create a new scope (which cancels
    // any existing one)" pattern is a UI-only ergonomic — when the user clicks Run while a
    // prior trail is mid-flight in the desktop app, replace it. For background flows that
    // come in concurrently (the benchmark fan-out runs three CLI invocations in parallel
    // against the same daemon, all sharing one device), creating-and-cancelling means each
    // new arrival cancels the still-running predecessor. A reproduction surfaced this: 2 of 3
    // trails passed and the 3rd reported "FAILED: Cancelled" with no Initializing log line.
    // Coroutines launched into a shared scope are independent — `scope.launch { … }` does
    // not block other launches in the same scope, so reuse is safe for parallel runs.
    val sharedScopeReferrers = setOf(TrailblazeReferrer.MCP.id, "cli")
    val coroutineScope = if (runYamlRequest.referrer.id in sharedScopeReferrers) {
      trailblazeDeviceManager.getOrCreateCoroutineScopeForDevice(trailblazeDeviceId)
    } else {
      trailblazeDeviceManager.createNewCoroutineScopeForDevice(trailblazeDeviceId)
    }

    coroutineScope.launch {
      Console.log("🚀 COROUTINE STARTED for device: ${trailblazeDeviceId.instanceId}")
      
      // Track the execution result to report in finally block
      var executionResult: TrailExecutionResult = TrailExecutionResult.Success

      // Try filtered first (correct driver selection for Android which has 3 driver variants
      // sharing the same device ID). Fall back to unfiltered for Compose/Playwright which are
      // only visible when testingEnvironment=WEB but should always be reachable via CLI.
      val connectedTrailblazeDevice = trailblazeDeviceManager.getDeviceState(trailblazeDeviceId)?.device
        ?: trailblazeDeviceManager.loadDevicesSuspend(applyDriverFilter = true).firstOrNull { it.trailblazeDeviceId == trailblazeDeviceId }
        ?: trailblazeDeviceManager.loadDevicesSuspend(applyDriverFilter = false).firstOrNull { it.trailblazeDeviceId == trailblazeDeviceId }

      if (connectedTrailblazeDevice == null) {
        onProgressMessage("Device with ID $trailblazeDeviceId not found")
        Console.log("❌ COROUTINE ENDING (device not found) for device: ${trailblazeDeviceId.instanceId}")
        executionResult = TrailExecutionResult.Failed("Device not found")
        onComplete?.invoke(executionResult)
        return@launch
      }

      // Wrap progress message callback to add device prefix
      val shortenedDescription = shortenDeviceDescription(trailblazeDeviceId.instanceId)
      val devicePrefix = "[$shortenedDescription]"
      val prefixedProgressMessage: (String) -> Unit = { message ->
        onProgressMessage("$devicePrefix $message")
      }

      if (forceStopTargetApp) {
        // Convert the YAML-ordered List to a Set for ensureAppsAreForceStopped, which takes
        // membership-style Set<String>.
        val possibleAppIds = targetTestApp
          ?.getPossibleAppIdsForPlatform(trailblazeDeviceId.trailblazeDevicePlatform)
          ?.toSet()
          ?: emptySet()
        MobileDeviceUtils.ensureAppsAreForceStopped(possibleAppIds, trailblazeDeviceId)
      }

      // Resolve driver type: request (CLI --driver / trail config) > app setting > connected device default.
      val appConfig = trailblazeDeviceManager.settingsRepo.serverStateFlow.value.appConfig
      val appSettingDriverType = appConfig.selectedTrailblazeDriverTypes[
        trailblazeDeviceId.trailblazeDevicePlatform
      ]
      val trailblazeDriverType = runYamlRequest.driverType
        ?: appSettingDriverType
        ?: connectedTrailblazeDevice.trailblazeDriverType

      // Per-session video / sprite / logcat capture used to be started here against a
      // temp dir and moved into the session log dir in the finally block. That worked
      // for the CLI/daemon path but bypassed every MCP-driven session — the `step`,
      // `ask`, `verify`, and individual-tool entry points create sessions through
      // `TrailblazeDeviceManager.getOrCreateSessionResolution` and never go through
      // this runner. Capture is now owned by [SessionCaptureCoordinator], which both
      // paths route through (CLI's `onSessionStarted` callback below starts it; MCP
      // starts it at session-resolution time). The coordinator writes artifacts
      // directly into the session log dir — no temp-dir + move dance.
      val appIdForCapture = targetTestApp
        ?.getPossibleAppIdsForPlatform(trailblazeDeviceId.trailblazeDevicePlatform)
        ?.firstOrNull()
      // Resolve per-run capture toggles in the same order the pre-coordinator flow did:
      // request-level overrides (CLI `--no-capture-video` / `--capture-logcat`) > daemon
      // appConfig toggles > built-in defaults. Passed to `coordinator.startForSession`
      // below so the user-visible CLI flag actually takes effect — without this, every
      // CLI run would record video even when the user opted out.
      val captureOptionsForRun = xyz.block.trailblaze.capture.CaptureOptions(
        captureVideo = desktopAppRunYamlParams.captureVideo ?: true,
        captureLogcat = desktopAppRunYamlParams.captureLogcat ?: appConfig.captureLogcat,
        captureIosLogs = desktopAppRunYamlParams.captureIosLogs ?: appConfig.captureIosLogs,
        spriteFrameFps = 2,
        spriteFrameHeight = 720,
        spriteQuality = 80,
      )

      var sessionId: SessionId? = null
      // Snapshot existing session IDs so we can find newly created ones on cancellation
      val preExistingSessionIds = trailblazeDeviceManager.logsRepo.getSessionIds().toSet()

      // Tracks the session ID under which we started the host-driven Android network capture
      // bridge (if any). The bridge starts inside `onSessionStarted` callbacks below — that's
      // the first moment we know the actual on-device session ID — so we can't pre-compute it
      // here. Stop is best-effort in the outer finally, keyed by whatever was captured.
      // Defined out here so the finally block sees it even if start() never ran.
      var capturedNetworkBridgeSessionId: String? = null

      try {
        trailblazeAnalytics.runTest(trailblazeDriverType, desktopAppRunYamlParams)
        prefixedProgressMessage(
          "Starting ${trailblazeDeviceId.trailblazeDevicePlatform.displayName} test on device ${trailblazeDeviceId.instanceId} with driver type $trailblazeDriverType",
        )

        val trailblazeHostAppTarget = trailblazeHostAppTargetProvider()

        // Capture-aware onSessionStarted callback shared across the three Android dispatch
        // branches (V3 / preferHostAgent / on-device YAML). Each branch knows the resolved
        // session id at a slightly different point in its flow; this lambda lets all three
        // converge on the same activator wiring without duplicating the `runCatching` /
        // `maybeStartAndroidNetworkCapture` plumbing.
        val captureSessionStarted: (SessionId) -> Unit = { sid ->
          // Idempotent — MCP path may have started this already via
          // getOrCreateSessionResolution with appConfig-derived options. The
          // coordinator's reserve-then-start makes the second call a no-op so we
          // don't double-spawn screenrecord. For runs that originated from the CLI,
          // `captureOptionsForRun` honors per-flag overrides (--no-capture-video,
          // --capture-logcat, --capture-ios-logs).
          trailblazeDeviceManager.sessionCaptureCoordinator.startForSession(
            sessionId = sid,
            deviceId = trailblazeDeviceId.instanceId,
            platform = trailblazeDeviceId.trailblazeDevicePlatform,
            options = captureOptionsForRun,
            appId = appIdForCapture,
          )
          capturedNetworkBridgeSessionId =
            maybeStartAndroidNetworkCapture(
              runYamlRequest = runYamlRequest,
              deviceId = trailblazeDeviceId,
              sessionIdOverride = sid,
              targetAppId = appIdForCapture,
              onProgressMessage = prefixedProgressMessage,
            )
        }

        sessionId = when {
          // Opt-in Koog strategy-graph agent. Top priority so it short-circuits the driver-based
          // routing below for every platform/driver when the run explicitly asks for it.
          //
          // The agent now drives the device IN-PROCESS for the WEB (Playwright-native) path:
          // [TrailblazeHostYamlRunner.runHostYaml] → `runPlaywrightNativeYaml` →
          // `BasePlaywrightNativeTest.runTrailblazeYamlSuspend` branches on
          // `runYamlRequest.agentImplementation` and, for KOOG_STRATEGY_GRAPH, runs prompt steps
          // through [KoogStrategyGraphAgent.createInProcess] against a Trailblaze-owned
          // `ToolRegistry`. Tool calls flow through the same `PlaywrightTrailblazeAgent` executor
          // (and therefore the same logging/session) the legacy runner uses — no MCP
          // self-connection, so none of the re-entrancy deadlock that pattern caused.
          //
          // This in-process host seam covers web (Playwright), Revyl (Android + iOS), Electron, and
          // the local-device Maestro iOS path via `runHostYaml`. Android ON-DEVICE drivers
          // (instrumentation/accessibility) are EXCLUDED from this branch on purpose: those need
          // the device attached via the on-device RPC server, which `runHostYaml`'s Maestro path
          // can't provide (it can't see the emulator the on-device setup registers). They instead
          // run the Koog agent ON THE DEVICE by default — the Koog agent now ships in
          // trailblaze-common, so the on-device RunYamlRequestHandler runs it in-process (see the
          // on-device branch below). `preferHostAgent` opts back into running the loop host-side and
          // dispatching each tool over RPC (`runHostTrailblazeRunnerWithOnDeviceRpc`).
          // Compose (the RPC driver) also rides this host seam now that ComposeRpcTrailblazeAgent is
          // a BaseTrailblazeAgent — `runComposeYaml` builds a KoogTestAgentRunner when KOOG is
          // selected. The earlier MCP-self-connection approach (and the deadlock it hit) is the
          // reason the in-process executor route exists; see [KoogStrategyGraphAgent].
          runYamlRequest.agentImplementation == AgentImplementation.KOOG_STRATEGY_GRAPH &&
            trailblazeDriverType !in TrailblazeDriverType.ANDROID_ON_DEVICE_DRIVER_TYPES -> {
            prefixedProgressMessage(
              "KOOG_STRATEGY_GRAPH selected — running the in-process Koog strategy-graph agent " +
                "(web, Revyl, Electron, and local Maestro iOS paths via runHostYaml).",
            )

            val hostSessionId = TrailblazeHostYamlRunner.runHostYaml(
              dynamicLlmClient = dynamicLlmClientProvider(runYamlRequest.trailblazeLlmModel),
              runOnHostParams = RunOnHostParams(
                runYamlRequest = runYamlRequest,
                device = connectedTrailblazeDevice,
                onProgressMessage = prefixedProgressMessage,
                forceStopTargetApp = forceStopTargetApp,
                targetTestApp = targetTestApp,
                additionalInstrumentationArgs = { emptyMap() },
                // Start capture (iOS log stream / Android logcat) the moment the Maestro
                // session is created, BEFORE the synchronous trail run — without this the
                // post-run activation below starts too late to record anything. Idempotent
                // with the post-run call, so non-Maestro host paths are unaffected.
                onSessionStarted = captureSessionStarted,
                composeRpcPort = desktopAppRunYamlParams.composeRpcPort,
                referrer = desktopAppRunYamlParams.runYamlRequest.referrer,
                noLogging = desktopAppRunYamlParams.noLogging,
              ),
              deviceManager = trailblazeDeviceManager,
            )

            // Mirror the neighboring branches' session/connection bookkeeping: fire the
            // capture activator for the resolved session and report instrumentation-running.
            hostSessionId?.let { captureSessionStarted(it) }
            onConnectionStatus(
              DeviceConnectionStatus.WithTargetDevice.TrailblazeInstrumentationRunning(
                trailblazeDeviceId = connectedTrailblazeDevice.trailblazeDeviceId,
              ),
            )
            hostSessionId
          }

          // V3 on host with accessibility driver: run planner/analyzer on the host JVM,
          // send individual tool calls to the on-device accessibility server via RPC.
          trailblazeDriverType == TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY &&
            runYamlRequest.agentImplementation == AgentImplementation.MULTI_AGENT_V3 -> {
            val trailblazeOnDeviceInstrumentationTarget = targetTestApp?.getTrailblazeOnDeviceInstrumentationTarget()
              ?: trailblazeHostAppTarget.getTrailblazeOnDeviceInstrumentationTarget()

            val onDeviceRpc = OnDeviceRpcClient(
              trailblazeDeviceId = trailblazeDeviceId,
              sendProgressMessage = prefixedProgressMessage,
              // Arm at the single chokepoint every synchronous on-device RPC flows through, so a
              // wedge surfacing on ANY path (including the `launchApp` pre-action, which the
              // session-status detection can't see) force-restarts the shared server next trail.
              onNonRecoverableWedge = { instrumentationWedged = true },
            )

            runV3WithAccessibilityOnHost(
              onDeviceRpc = onDeviceRpc,
              dynamicLlmClient = dynamicLlmClientProvider(runYamlRequest.trailblazeLlmModel),
              runYamlRequest = runYamlRequest.copy(driverType = trailblazeDriverType),
              connectedTrailblazeDevice = connectedTrailblazeDevice,
              trailblazeOnDeviceInstrumentationTarget = trailblazeOnDeviceInstrumentationTarget,
              onProgressMessage = prefixedProgressMessage,
              onConnectionStatus = onConnectionStatus,
              additionalInstrumentationArgs = additionalInstrumentationArgs,
              targetTestApp = targetTestApp,
              onSessionStarted = captureSessionStarted,
            )
          }

          // Host agent with on-device driver (accessibility or instrumentation): run the
          // agent loop on the host JVM, send individual tool calls to the device via RPC.
          // The device executes each tool using whichever driver is selected.
          //
          // This is the opt-in `preferHostAgent` path. KOOG_STRATEGY_GRAPH no longer forces it:
          // the Koog agent now ships in trailblaze-common and runs ON-DEVICE by default (next
          // branch). Set `preferHostAgent` to keep the Koog reasoning loop (and its growing
          // history) on the host instead of the device — useful when device memory pressure is a
          // concern; runHostTrailblazeRunnerWithOnDeviceRpc still runs the Koog graph host-side
          // when this path is taken.
          trailblazeDriverType in TrailblazeDriverType.ANDROID_ON_DEVICE_DRIVER_TYPES &&
            runYamlRequest.agentImplementation != AgentImplementation.MULTI_AGENT_V3 &&
            runYamlRequest.config.preferHostAgent -> {
            val trailblazeOnDeviceInstrumentationTarget = targetTestApp?.getTrailblazeOnDeviceInstrumentationTarget()
              ?: trailblazeHostAppTarget.getTrailblazeOnDeviceInstrumentationTarget()

            val onDeviceRpc = OnDeviceRpcClient(
              trailblazeDeviceId = trailblazeDeviceId,
              sendProgressMessage = prefixedProgressMessage,
              // Arm at the single chokepoint every synchronous on-device RPC flows through, so a
              // wedge surfacing on ANY path (including the `launchApp` pre-action, which the
              // session-status detection can't see) force-restarts the shared server next trail.
              onNonRecoverableWedge = { instrumentationWedged = true },
            )

            runHostAgentWithOnDeviceRpc(
              onDeviceRpc = onDeviceRpc,
              dynamicLlmClient = dynamicLlmClientProvider(runYamlRequest.trailblazeLlmModel),
              runYamlRequest = runYamlRequest.copy(driverType = trailblazeDriverType),
              connectedTrailblazeDevice = connectedTrailblazeDevice,
              trailblazeOnDeviceInstrumentationTarget = trailblazeOnDeviceInstrumentationTarget,
              onProgressMessage = prefixedProgressMessage,
              onConnectionStatus = onConnectionStatus,
              additionalInstrumentationArgs = additionalInstrumentationArgs,
              targetTestApp = targetTestApp,
              onSessionStarted = captureSessionStarted,
            )
          }

          // On-device agent: send entire YAML to device, agent loop runs on-device.
          // Used when preferHostAgent=false or for instrumentation driver fallback. This is the
          // default for KOOG_STRATEGY_GRAPH on Android on-device drivers: the request (carrying
          // agentImplementation) goes to the device's RunYamlRequestHandler, which runs the Koog
          // strategy-graph agent in-process via AndroidTrailblazeRule.
          trailblazeDriverType in TrailblazeDriverType.ANDROID_ON_DEVICE_DRIVER_TYPES -> {
            val trailblazeOnDeviceInstrumentationTarget = targetTestApp?.getTrailblazeOnDeviceInstrumentationTarget()
              ?: trailblazeHostAppTarget.getTrailblazeOnDeviceInstrumentationTarget()

            val onDeviceRpc = OnDeviceRpcClient(
              trailblazeDeviceId = trailblazeDeviceId,
              sendProgressMessage = prefixedProgressMessage,
              // Arm at the single chokepoint every synchronous on-device RPC flows through, so a
              // wedge surfacing on ANY path (including the `launchApp` pre-action, which the
              // session-status detection can't see) force-restarts the shared server next trail.
              onNonRecoverableWedge = { instrumentationWedged = true },
            )

            // Set driver type on request so the on-device server knows which driver to use
            val requestWithDriverType = runYamlRequest.copy(driverType = trailblazeDriverType)

            runYamlOnDevice(
              onDeviceRpc = onDeviceRpc,
              trailblazeConnectedDevice = connectedTrailblazeDevice,
              trailblazeOnDeviceInstrumentationTarget = trailblazeOnDeviceInstrumentationTarget,
              runYamlRequest = requestWithDriverType,
              onProgressMessage = prefixedProgressMessage,
              onConnectionStatus = onConnectionStatus,
              additionalInstrumentationArgs = additionalInstrumentationArgs,
              onSessionStarted = captureSessionStarted,
            )
          }

          else -> {
            val hostSessionId = TrailblazeHostYamlRunner.runHostYaml(
              dynamicLlmClient = dynamicLlmClientProvider(desktopAppRunYamlParams.runYamlRequest.trailblazeLlmModel),
              runOnHostParams = RunOnHostParams(
                runYamlRequest = runYamlRequest,
                device = connectedTrailblazeDevice,
                onProgressMessage = prefixedProgressMessage,
                forceStopTargetApp = forceStopTargetApp,
                targetTestApp = targetTestApp,
                additionalInstrumentationArgs = {
                  // Not required since this is "host", but is required "on-device"
                  emptyMap()
                },
                // Start session-scoped capture (iOS Simulator log stream → device.log, Android
                // logcat) the moment the Maestro session is created, BEFORE the synchronous trail
                // run. This default-agent branch previously started no capture at all for the
                // local Maestro paths — the finally block's stopForSession had nothing to stop —
                // so iOS logs never landed in the report. Coordinator skips WEB and is idempotent.
                onSessionStarted = captureSessionStarted,
                composeRpcPort = desktopAppRunYamlParams.composeRpcPort,
                referrer = desktopAppRunYamlParams.runYamlRequest.referrer,
                noLogging = desktopAppRunYamlParams.noLogging,
              ),
              deviceManager = trailblazeDeviceManager,
            )

            onConnectionStatus(
              DeviceConnectionStatus.WithTargetDevice.TrailblazeInstrumentationRunning(
                trailblazeDeviceId = connectedTrailblazeDevice.trailblazeDeviceId,
              ),
            )
            hostSessionId
          }
        }

        // Defensive: any branch that returns null without throwing (e.g.
        // runYamlOnDevice on RpcResult.Failure) still indicates the test did NOT
        // succeed. Without this, executionResult would stay at its Success default
        // and onComplete would lie to the caller — which is exactly the silent-
        // failure mode that hid the cached-LLM-model bug for so long.
        if (sessionId == null && executionResult is TrailExecutionResult.Success) {
          executionResult = TrailExecutionResult.Failed(
            "Test execution did not produce a session id (see daemon log for details)",
          )
        }
      } catch (e: CancellationException) {
        Console.log("⚠️ COROUTINE CANCELLED for device ${trailblazeDeviceId.instanceId}")
        executionResult = TrailExecutionResult.Cancelled
        // Don't re-throw yet — let the finally block save capture artifacts first.
        // CancellationException is re-thrown after cleanup below.
      } catch (e: TrailblazeSessionCancelledException) {
        // User-initiated session cancel. Distinct from coroutine cancellation
        // (TSCE extends Exception, not CancellationException) so we have to
        // catch it before the generic Exception branch — otherwise it would
        // be reported as Failed, not Cancelled.
        Console.log("🚫 Session cancelled by user for device ${trailblazeDeviceId.instanceId}")
        prefixedProgressMessage("Test session cancelled")
        executionResult = TrailExecutionResult.Cancelled
      } catch (e: Exception) {
        Console.log("⚠️ EXCEPTION in coroutine for device ${trailblazeDeviceId.instanceId}: ${e::class.simpleName} - ${e.message}")
        // Full stack trace to the daemon log so the throw site is diagnosable — the one-line
        // message alone hid which internal call actually failed (e.g. a decodeTrail deep in the
        // dispatch path vs. a device-connect IOException).
        Console.log(e.stackTraceToString())
        prefixedProgressMessage("Error: ${e.message}")
        executionResult = TrailExecutionResult.Failed(e.message)
        try {
          onConnectionStatus(
            DeviceConnectionStatus.DeviceConnectionError.ConnectionFailure(
              errorMessage = e.message ?: "Unknown error",
            ),
          )
        } catch (classLoadError: Throwable) {
          // Fallback if ConnectionFailure class fails to load (Kotlin multiplatform classloading issue)
          Console.log("⚠️ Failed to create ConnectionFailure instance: ${classLoadError::class.simpleName} - ${classLoadError.message}")
        }
      } finally {
        // Always stop capture and save artifacts — even on cancel/error, the video
        // recorded up to this point is valuable for debugging.
        // Clear the thread interrupt flag so capture stop methods (which use
        // Process.waitFor and Thread.sleep) don't throw InterruptedException.
        // Without this, xcrun/screenrecord get killed before finalizing the video.
        Thread.interrupted()
        // Tear down the Android network capture bridge if one was started for this session.
        // Mirrors `TrailblazeMcpBridgeImpl.endSession`'s wiring on the MCP side. Best-effort —
        // the activator's own stop() handles bridge cleanup, so any throw here is non-fatal.
        capturedNetworkBridgeSessionId?.let { sid ->
          AndroidNetworkCaptureRegistry.activator?.let { activator ->
            runCatching { activator.stop(sid) }
              .onFailure { Console.log("Android network capture stop failed for $sid: ${it.message}") }
          }
        }
        // On cancellation, sessionId may not have been set yet. Find the session
        // that was created during this test run by matching the device ID in the
        // session's first log file. This handles concurrent multi-device runs.
        val resolvedSessionId = sessionId
          ?: run {
            val newSessions = trailblazeDeviceManager.logsRepo.getSessionIds()
              .filter { it !in preExistingSessionIds }
            val deviceInstanceId = trailblazeDeviceId.instanceId
            // Match by checking the first log file for this device's instance ID
            newSessions.firstOrNull { sid ->
              val sessionDir = trailblazeDeviceManager.logsRepo.getSessionDir(sid)
              val firstLog = File(sessionDir, "001_TrailblazeSessionStatusChangeLog.json")
              firstLog.exists() && firstLog.readText().contains(deviceInstanceId)
            } ?: newSessions.firstOrNull()
          }
        // Stop capture for the session if we own it (i.e. the runner's
        // captureSessionStarted callback fired and started it). Idempotent — if the
        // MCP path or another caller already stopped it via endSessionForDevice,
        // the coordinator no-ops. Artifacts are already in the session log dir;
        // no temp-dir move needed.
        if (resolvedSessionId != null) {
          trailblazeDeviceManager.sessionCaptureCoordinator.stopForSession(resolvedSessionId)
        }
        Console.log("🏁 COROUTINE FINISHED (finally block) for device: ${trailblazeDeviceId.instanceId}")
        onComplete?.invoke(executionResult)
        // Re-throw CancellationException to properly propagate cancellation
        if (executionResult is TrailExecutionResult.Cancelled) {
          throw CancellationException("Test cancelled for device ${trailblazeDeviceId.instanceId}")
        }
      }
    }
  }

  /**
   * Connects on-device instrumentation, optionally enables the accessibility service, and
   * polls readiness. Single path for every host→device trail dispatcher in this file.
   *
   * ### Zombie-instrumentation recovery
   *
   * `connectToInstrumentationAndInstallAppIfNotAvailable` decides "already running" by checking
   * `isAppRunning(testAppId)` via ADB — that only confirms the OS process exists, not that the
   * HTTP server inside it is accepting connections. A process that's been killed gracelessly
   * (app crash, emulator hiccup, accessibility service rebind) can linger as a zombie: the PID
   * is alive, ADB reports it's running, but every probe times out because the server never
   * started or died. When that happens, `waitForReady` throws [IOException] after its budget
   * expires. Here we catch it and retry the whole setup with `forceRestart = true`, which
   * force-stops the process, reinstalls the test APK, and relaunches instrumentation — the
   * only thing that actually recovers a zombie. The common flow pays nothing for this fallback
   * (the first `waitForReady` succeeds), so the retry only fires on genuinely stuck devices.
   */
  private suspend fun connectAndEnsureReady(
    onDeviceRpc: OnDeviceRpcClient,
    trailblazeDeviceId: TrailblazeDeviceId,
    trailblazeOnDeviceInstrumentationTarget: TrailblazeOnDeviceInstrumentationTarget,
    additionalInstrumentationArgs: Map<String, String>,
    onProgressMessage: (String) -> Unit,
    enableAccessibility: Boolean,
    requireAndroidAccessibilityService: Boolean,
  ): DeviceConnectionStatus {
    // Step 1: connect (install/reuse) and enable accessibility if needed. Any IOException in
    // here is an infrastructure-level failure (ADB, instrumentation launch, APK install) that
    // a `forceRestart` retry would just repeat — so we let it propagate rather than hiding it
    // behind a misleading "readiness probe failed" log.
    suspend fun doConnectAndEnable(forceRestart: Boolean): DeviceConnectionStatus {
      val status = HostAndroidDeviceConnectUtils.connectToInstrumentationAndInstallAppIfNotAvailable(
        sendProgressMessage = onProgressMessage,
        deviceId = trailblazeDeviceId,
        trailblazeOnDeviceInstrumentationTarget = trailblazeOnDeviceInstrumentationTarget,
        additionalInstrumentationArgs = additionalInstrumentationArgs,
        forceRestart = forceRestart,
      )
      if (enableAccessibility) {
        // The on-device GetScreenState handler (triggered below by waitForReady) uses the
        // reliable in-process TrailblazeAccessibilityService singleton — dumpsys parsing is
        // unreliable on API 35+.
        AccessibilityServiceSetupUtils.enableAccessibilityService(
          deviceId = trailblazeDeviceId,
          hostPackage = trailblazeOnDeviceInstrumentationTarget.testAppId,
          sendProgressMessage = onProgressMessage,
        )
      }
      return status
    }

    // Consume a wedge flag set by a prior trail (see [instrumentationWedged]): the shared on-device
    // server is poisoned in a way the readiness probe can't see, so force-restart up front to hand
    // this trail a clean server. Cleared only after the relaunch + readiness probe both succeed, so
    // a failed relaunch keeps the flag armed for the next attempt.
    val recoverFromPriorWedge = instrumentationWedged
    val initialStatus = doConnectAndEnable(forceRestart = recoverFromPriorWedge)
    if (recoverFromPriorWedge) {
      onDeviceRpc.waitForReady(
        requireAndroidAccessibilityService = requireAndroidAccessibilityService,
      )
      instrumentationWedged = false
      return initialStatus
    }

    // Step 2: readiness probe. This is the specific failure mode we retry — the instrumentation
    // process is alive (so `isAppRunning` returned true and `forceRestart=false` reused it) but
    // the HTTP server inside it is stuck or dead. Force-restart reinstalls the APK and relaunches
    // instrumentation, which is the only thing that actually recovers a zombie. The common path
    // pays nothing for this fallback: the first `waitForReady` succeeds in ms on a warm device.
    return try {
      onDeviceRpc.waitForReady(
        requireAndroidAccessibilityService = requireAndroidAccessibilityService,
      )
      initialStatus
    } catch (e: IOException) {
      onProgressMessage(
        "Device readiness probe failed (${e.message}); force-restarting instrumentation and retrying once.",
      )
      val restartedStatus = doConnectAndEnable(forceRestart = true)
      onDeviceRpc.waitForReady(
        requireAndroidAccessibilityService = requireAndroidAccessibilityService,
      )
      restartedStatus
    }
  }

  /**
   * Connects instrumentation on-device and runs MULTI_AGENT_V3 on the host, using the
   * on-device accessibility driver for individual tool execution.
   *
   * Handles the same instrumentation setup as [runYamlOnDevice] but delegates execution
   * to [TrailblazeHostYamlRunner.runHostV3WithAccessibilityYaml] instead of forwarding
   * the full trail YAML to the device.
   */
  private suspend fun runV3WithAccessibilityOnHost(
    onDeviceRpc: OnDeviceRpcClient,
    dynamicLlmClient: DynamicLlmClient,
    runYamlRequest: RunYamlRequest,
    connectedTrailblazeDevice: TrailblazeConnectedDeviceSummary,
    trailblazeOnDeviceInstrumentationTarget: TrailblazeOnDeviceInstrumentationTarget,
    onProgressMessage: (String) -> Unit,
    onConnectionStatus: (DeviceConnectionStatus) -> Unit,
    additionalInstrumentationArgs: Map<String, String>,
    targetTestApp: TrailblazeHostAppTarget?,
    onSessionStarted: (SessionId) -> Unit = {},
  ): SessionId? {
    return withContext(Dispatchers.IO) {
      // V3 + on-host path always uses the accessibility driver on-device.
      val status = connectAndEnsureReady(
        onDeviceRpc = onDeviceRpc,
        trailblazeDeviceId = connectedTrailblazeDevice.trailblazeDeviceId,
        trailblazeOnDeviceInstrumentationTarget = trailblazeOnDeviceInstrumentationTarget,
        additionalInstrumentationArgs = additionalInstrumentationArgs,
        onProgressMessage = onProgressMessage,
        enableAccessibility = true,
        requireAndroidAccessibilityService = true,
      )

      withContext(Dispatchers.Default) {
        onConnectionStatus(status)

        // Same wedge recovery as the host-agent path: a mid-trail wedge is re-thrown (so the runner
        // never returns the session id), but the terminal Ended.Failed status is written to disk
        // first. Capture the live session id and arm the relaunch in a finally so the NEXT trail
        // force-restarts the shared on-device server whether this run returns or propagates.
        var v3SessionId: SessionId? = null
        try {
          TrailblazeHostYamlRunner.runHostV3WithAccessibilityYaml(
            dynamicLlmClient = dynamicLlmClient,
            onDeviceRpc = onDeviceRpc,
            runYamlRequest = runYamlRequest,
            trailblazeDeviceId = connectedTrailblazeDevice.trailblazeDeviceId,
            onProgressMessage = onProgressMessage,
            targetTestApp = targetTestApp,
            onSessionStarted = { sessionId ->
              v3SessionId = sessionId
              onSessionStarted(sessionId)
            },
          )
        } finally {
          armIfWedged(v3SessionId, onProgressMessage)
        }
      }
    }
  }

  /**
   * Runs the legacy TrailblazeRunner agent on the host with tool execution delegated to
   * an on-device driver (accessibility or instrumentation) via RPC.
   */
  private suspend fun runHostAgentWithOnDeviceRpc(
    onDeviceRpc: OnDeviceRpcClient,
    dynamicLlmClient: DynamicLlmClient,
    runYamlRequest: RunYamlRequest,
    connectedTrailblazeDevice: TrailblazeConnectedDeviceSummary,
    trailblazeOnDeviceInstrumentationTarget: TrailblazeOnDeviceInstrumentationTarget,
    onProgressMessage: (String) -> Unit,
    onConnectionStatus: (DeviceConnectionStatus) -> Unit,
    additionalInstrumentationArgs: Map<String, String>,
    targetTestApp: TrailblazeHostAppTarget?,
    onSessionStarted: (SessionId) -> Unit = {},
  ): SessionId? {
    return withContext(Dispatchers.IO) {
      val needsAccessibility =
        runYamlRequest.driverType == TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY
      val status = connectAndEnsureReady(
        onDeviceRpc = onDeviceRpc,
        trailblazeDeviceId = connectedTrailblazeDevice.trailblazeDeviceId,
        trailblazeOnDeviceInstrumentationTarget = trailblazeOnDeviceInstrumentationTarget,
        additionalInstrumentationArgs = additionalInstrumentationArgs,
        onProgressMessage = onProgressMessage,
        enableAccessibility = needsAccessibility,
        requireAndroidAccessibilityService = needsAccessibility,
      )

      withContext(Dispatchers.Default) {
        onConnectionStatus(status)

        // A mid-trail wedge on this path is re-thrown by `executeTrailSession`, so the runner
        // never returns the wedged session's id — but the terminal `Ended.Failed` status (with
        // the non-recoverable signature) is written to disk before the re-throw. Capture the live
        // session id from `onSessionStarted` and arm the relaunch in a finally so detection fires
        // whether the runner returns normally or propagates the wedge as an exception.
        var hostAgentSessionId: SessionId? = null
        try {
          TrailblazeHostYamlRunner.runHostTrailblazeRunnerWithOnDeviceRpc(
            dynamicLlmClient = dynamicLlmClient,
            onDeviceRpc = onDeviceRpc,
            runYamlRequest = runYamlRequest,
            trailblazeDeviceId = connectedTrailblazeDevice.trailblazeDeviceId,
            onProgressMessage = onProgressMessage,
            targetTestApp = targetTestApp,
            onSessionStarted = { sessionId ->
              hostAgentSessionId = sessionId
              onSessionStarted(sessionId)
            },
          )
        } finally {
          armIfWedged(hostAgentSessionId, onProgressMessage)
        }
      }
    }
  }

  /**
   * Reads [sessionId]'s terminal status from disk and, when it carries the non-recoverable
   * UiAutomation wedge (see [shouldRelaunchOnDeviceServer]), arms [instrumentationWedged] so the
   * next trail force-restarts the shared on-device server. Shared by the V1 on-device path (which
   * polls completion in [awaitOnDeviceSessionCompletion]) and the host-agent path (whose runner
   * re-throws a mid-trail wedge, so detection runs in its `finally` against the on-disk status).
   * No-op when [sessionId] is null or the status isn't the wedge signature.
   */
  private fun armIfWedged(sessionId: SessionId?, onProgressMessage: (String) -> Unit) {
    if (sessionId == null) return
    val status = trailblazeDeviceManager.logsRepo.getLogsForSession(sessionId).getSessionStatus()
    if (shouldRelaunchOnDeviceServer(status)) {
      instrumentationWedged = true
      onProgressMessage(
        "On-device UiAutomation wedged (non-recoverable); the on-device server will be " +
          "force-restarted before the next trail.",
      )
    }
  }

  /**
   * Executes YAML test on a device using instrumentation.
   */
  private suspend fun runYamlOnDevice(
    onDeviceRpc: OnDeviceRpcClient,
    trailblazeConnectedDevice: TrailblazeConnectedDeviceSummary,
    trailblazeOnDeviceInstrumentationTarget: TrailblazeOnDeviceInstrumentationTarget,
    runYamlRequest: RunYamlRequest,
    onConnectionStatus: (DeviceConnectionStatus) -> Unit,
    onProgressMessage: (String) -> Unit,
    additionalInstrumentationArgs: Map<String, String>,
    /**
     * Fired exactly once after the on-device RPC reports a successful start, BEFORE we begin
     * polling for completion. The session is live at this point — the on-device runner has
     * created the session directory and is about to execute the YAML. Callers use this to spin
     * up out-of-band session-scoped infrastructure that needs to run *while* the YAML executes
     * (e.g. an Android network-capture activator — it has to be polling its discovery
     * side-channel before the launch tool's first network call so it can attach to the
     * target's freshly-opened socket). Defaulted to a no-op so existing callers stay compatible.
     */
    onSessionStarted: (SessionId) -> Unit = {},
  ): SessionId? {
    return withContext(Dispatchers.IO) {
      val needsAccessibility =
        runYamlRequest.driverType == TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY
      val status = connectAndEnsureReady(
        onDeviceRpc = onDeviceRpc,
        trailblazeDeviceId = trailblazeConnectedDevice.trailblazeDeviceId,
        trailblazeOnDeviceInstrumentationTarget = trailblazeOnDeviceInstrumentationTarget,
        additionalInstrumentationArgs = additionalInstrumentationArgs,
        onProgressMessage = onProgressMessage,
        enableAccessibility = needsAccessibility,
        requireAndroidAccessibilityService = needsAccessibility,
      )

      withContext(Dispatchers.Default) {
        onConnectionStatus(status)
        when (val result: RpcResult<RunYamlResponse> = onDeviceRpc.rpcCall(runYamlRequest)) {
          is RpcResult.Failure -> {
            onProgressMessage("Failed to start YAML execution: ${result.message}${result.details?.let { " | $it" } ?: ""}")
            null
          }

          is RpcResult.Success -> {
            val runYamlResponse = result.data
            onProgressMessage("YAML test execution started for session: ${runYamlResponse.sessionId}")

            // Notify the caller that the session is live, BEFORE we block on completion. This is
            // the only window in which session-scoped out-of-band infrastructure (the network
            // capture bridge, mainly) can attach with the right session ID. Errors from the
            // callback are caught so a misbehaving listener can't crash the test run.
            try {
              onSessionStarted(runYamlResponse.sessionId)
            } catch (t: Throwable) {
              Console.log(
                "[runYamlOnDevice] onSessionStarted callback threw — continuing test run: " +
                  "${t::class.java.simpleName}: ${t.message}"
              )
            }

            // Wait for the on-device test to complete. The RPC returns immediately
            // (fire-and-forget), but we need to block until the session reaches an
            // Ended status so that logs are fully streamed before the process exits.
            awaitOnDeviceSessionCompletion(
              sessionId = runYamlResponse.sessionId,
              onProgressMessage = onProgressMessage,
            )

            runYamlResponse.sessionId
          }
        }
      }
    }
  }

  /**
   * Polls the logsRepo until the given session reaches a terminal [SessionStatus.Ended]
   * state, or until the timeout expires. This ensures on-device logs are fully received
   * before the coroutine completes and the process potentially exits.
   *
   * Reads logs directly from disk (not cached flows) so new files are detected immediately.
   */
  private suspend fun awaitOnDeviceSessionCompletion(
    sessionId: SessionId,
    onProgressMessage: (String) -> Unit,
    maxWaitMs: Long = 600_000,
    pollIntervalMs: Long = 1_000,
  ) {
    val logsRepo = trailblazeDeviceManager.logsRepo
    val startTime = System.currentTimeMillis()

    while (System.currentTimeMillis() - startTime < maxWaitMs) {
      val status = logsRepo.getLogsForSession(sessionId).getSessionStatus()
      if (status is SessionStatus.Ended) {
        // Gate: ONLY the non-recoverable UiAutomation wedge arms a relaunch — never an ordinary
        // failure, so a clean server is provisioned for the NEXT trail without re-running this one.
        armIfWedged(sessionId, onProgressMessage)
        return
      }
      delay(pollIntervalMs)
    }
    onProgressMessage("Warning: Timed out waiting for on-device session to complete")
  }

  /**
   * If [runYamlRequest]'s config has `captureNetworkTraffic=true` and the target is Android, ask
   * the registered [AndroidNetworkCaptureRegistry.activator] (optionally set by a downstream
   * desktop app at startup) to spin up a per-session bridge. Default distributions ship without
   * an activator and this is a no-op.
   *
   * Returns the session id under which the bridge was started (so [runYaml]'s outer `finally`
   * can stop it), or null when capture wasn't requested / wasn't applicable.
   *
   * The MCP-driven path has the equivalent wiring inside `TrailblazeMcpBridgeImpl.executeToolViaRpc`.
   * Both call sites need to exist because the desktop UI's "Run YAML" button takes the
   * [DesktopYamlRunner] path, NOT the MCP path — without this method, capture is silently
   * dropped for every desktop-driven Android session even though the toggle is on.
   */
  private fun maybeStartAndroidNetworkCapture(
    runYamlRequest: RunYamlRequest,
    deviceId: TrailblazeDeviceId,
    sessionIdOverride: SessionId,
    targetAppId: String?,
    onProgressMessage: (String) -> Unit,
  ): String? {
    if (!runYamlRequest.config.captureNetworkTraffic) return null
    if (deviceId.trailblazeDevicePlatform != TrailblazeDevicePlatform.ANDROID) return null
    val activator = AndroidNetworkCaptureRegistry.activator ?: return null
    val sessionDir = trailblazeDeviceManager.logsRepo.getSessionDir(sessionIdOverride)
    return runCatching {
        activator.start(
          sessionId = sessionIdOverride.value,
          sessionDir = sessionDir,
          deviceId = deviceId,
          targetAppId = targetAppId,
        )
        onProgressMessage(
          "Android network capture bridge started for session ${sessionIdOverride.value}",
        )
        sessionIdOverride.value
      }
      .onFailure {
        Console.log(
          "Auto-start of Android network capture failed for ${sessionIdOverride.value}: ${it.message}"
        )
      }
      .getOrNull()
  }

  // `stopCaptureAndMoveArtifacts` lived here. Removed in favor of
  // [SessionCaptureCoordinator.stopForSession], which writes artifacts directly into
  // the session log dir from the start so the temp-dir + move step is no longer needed.
}
