package xyz.block.trailblaze.mcp

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.ScreenshotScalingConfig
import xyz.block.trailblaze.devices.TrailblazeConnectedDeviceSummary
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.host.axe.AxeDeviceManager
import xyz.block.trailblaze.host.axe.IosAxeTrailblazeAgent
import xyz.block.trailblaze.host.devices.AxeConnectedDevice
import xyz.block.trailblaze.host.devices.HostIosDriverFactory
import xyz.block.trailblaze.host.devices.MaestroConnectedDevice
import xyz.block.trailblaze.host.devices.TrailblazeConnectedDevice
import xyz.block.trailblaze.host.devices.TrailblazeDeviceService
import xyz.block.trailblaze.host.devices.TrailblazeHostDeviceClassifier
import xyz.block.trailblaze.host.screenstate.AxeScreenState
import xyz.block.trailblaze.host.screenstate.HostMaestroDriverScreenState
import xyz.block.trailblaze.http.DynamicLlmClient
import xyz.block.trailblaze.llm.RunYamlRequest
import xyz.block.trailblaze.llm.RunYamlResponse
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.llm.TrailblazeReferrer
import xyz.block.trailblaze.logs.client.NoOpLogEmitter
import xyz.block.trailblaze.logs.client.ScreenStateLogger
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.model.TrailExecutionResult
import xyz.block.trailblaze.model.TrailblazeConfig
import xyz.block.trailblaze.model.findById
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.HostLocalExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.mcp.android.ondevice.rpc.GetScreenStateRequest
import xyz.block.trailblaze.mcp.android.ondevice.rpc.GetScreenStateResponse
import xyz.block.trailblaze.mcp.android.ondevice.rpc.OnDeviceRpcClient
import xyz.block.trailblaze.mcp.android.ondevice.rpc.RpcResult
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.model.TrailblazeOnDeviceInstrumentationTarget
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.ui.TrailblazeDeviceManager
import xyz.block.trailblaze.util.AccessibilityServiceSetupUtils
import xyz.block.trailblaze.compose.driver.rpc.ExecuteToolsRequest as ComposeExecuteToolsRequest
import xyz.block.trailblaze.compose.driver.rpc.GetScreenStateResponse as ComposeGetScreenStateResponse
import xyz.block.trailblaze.compose.driver.tools.ComposeToolSetIds
import xyz.block.trailblaze.devices.TrailblazeDevicePort
import xyz.block.trailblaze.host.networkcapture.AndroidNetworkCaptureRegistry
import xyz.block.trailblaze.host.rules.BasePlaywrightNativeTest
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.mcp.utils.HttpRequestUtils
import xyz.block.trailblaze.playwright.PlaywrightBrowserManager
import xyz.block.trailblaze.playwright.PlaywrightTrailblazeAgent
import xyz.block.trailblaze.playwright.network.WebNetworkCapture
import xyz.block.trailblaze.playwright.tools.WebToolSetIds
import xyz.block.trailblaze.revyl.tools.RevylToolSetIds
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.toolcalls.TrailblazeToolSetCatalog
import xyz.block.trailblaze.utils.NoOpElementComparator
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.util.HostAndroidDeviceConnectUtils
import xyz.block.trailblaze.yaml.createTrailblazeYaml
import xyz.block.trailblaze.yaml.models.TrailblazeYamlBuilder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.reflect.KClass
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private const val DEFAULT_DEVICE_WIDTH = 1080
private const val DEFAULT_DEVICE_HEIGHT = 2400

class TrailblazeMcpBridgeImpl(
  private val trailblazeDeviceManager: TrailblazeDeviceManager,
  /**
   * Optional custom executor for TrailblazeTools.
   * If not provided, tools will be converted to YAML and executed via runYaml().
   */
  private val trailblazeToolExecutor: (suspend (TrailblazeTool, TrailblazeDeviceId) -> String)? = null,
  /**
   * Optional LogsRepo for MCP-specific blocking execution.
   * Required for runYamlBlocking() to wait for completion.
   */
  private val logsRepo: LogsRepo? = null,
  /**
   * Builds a [DynamicLlmClient] for the given model. Used when the bridge eagerly
   * constructs a Playwright-native test instance during async WEB browser init so that
   * the cached test runs through the host app's own dynamic-client factory rather than
   * [BasePlaywrightNativeTest]'s default
   * ([xyz.block.trailblaze.host.rules.TrailblazeHostDynamicLlmClientProvider]), which
   * only knows about built-in providers (OpenAI, Anthropic, Google, Ollama, OpenRouter)
   * plus YAML-configured custom providers — not host-app-specific providers wired in by
   * downstream apps. Required so this trap can't recur silently for a future host app.
   *
   * Implementations must be synchronous: this is invoked from a plain `Thread` during
   * browser init, not a coroutine context.
   */
  private val dynamicLlmClientProvider: (TrailblazeLlmModel) -> DynamicLlmClient,
) : TrailblazeMcpBridge, HostLocalToolDispatchingBridge {

  /**
   * Tracks async Playwright browser initialization for WEB devices.
   *
   * [startTimeMs] — used to report elapsed time on repeated device(action=WEB) calls.
   * [progressMessage] — last status line from the installer (e.g. "67% chromium-...").
   *   Updated by the onBrowserInstallProgress callback during Chromium download.
   * [error] — set on failure so the client sees the reason instead of a generic message.
   *
   * Entry is removed from [webInitJobs] as soon as initialization completes (success or
   * failure) so that [getActivePlaywrightNativeTest] is the canonical "ready" signal.
   */
  private class WebInitState {
    val startTimeMs: Long = System.currentTimeMillis()
    @Volatile var progressMessage: String = "Checking Playwright drivers..."
  }

  private val webInitJobs = ConcurrentHashMap<String, WebInitState>()

  /** Persists failure messages after the init thread exits, so the MCP client sees them on the next poll. */
  private val webInitErrors = ConcurrentHashMap<String, String>()

  /**
   * Returns the effective device ID for the current execution context.
   *
   * Precedence: thread-local override (multi-session safe) → shared [selectedDeviceId] (STDIO fallback).
   */
  private fun getEffectiveDeviceId(): TrailblazeDeviceId? {
    return McpDeviceContext.currentDeviceId.get() ?: selectedDeviceId
  }

  private val _selectedDeviceIdFlow = MutableStateFlow<TrailblazeDeviceId?>(null)

  /**
   * Per-device cached screen states from the last tool execution.
   * Keyed by device instanceId. Allows multiple devices to have cached state simultaneously.
   */
  private val cachedScreenStates = ConcurrentHashMap<String, ScreenState>()

  /**
   * Per-device persistent connections for direct screen state capture.
   * Keyed by device instanceId. Each device maintains its own Maestro driver connection,
   * so switching devices doesn't destroy existing connections.
   */
  private val persistentDevices = ConcurrentHashMap<String, TrailblazeConnectedDevice>()

  /** Per-device locks to prevent two threads from simultaneously opening Maestro connections. */
  private val persistentDeviceLocks = ConcurrentHashMap<String, Any>()

  /**
   * Per-device latches for callers waiting on in-progress driver creation.
   * When a driver is being created in the background, callers (e.g., getDirectScreenStateProvider)
   * can wait on this latch instead of returning null immediately.
   */
  private val driverCreationLatches = ConcurrentHashMap<String, CountDownLatch>()

  /** Tracks when driver creation started for each device (epoch millis). */
  private val driverCreationStartTimes = ConcurrentHashMap<String, Long>()

  /**
   * Tracks which devices have their on-device agent verified as running.
   * Analogous to [persistentDevices] for HOST mode — used by [getDriverConnectionStatus]
   * to suppress "still initializing" messages once the agent is ready.
   */
  private val onDeviceAgentReady = ConcurrentHashMap.newKeySet<String>()

  /**
   * Tracks devices whose driver creation failed. Keyed by device instanceId,
   * value is the error message. Cleared when a new connection attempt starts.
   * Used by [getDriverConnectionStatus] to report failures instead of returning null.
   */
  private val driverCreationFailures = ConcurrentHashMap<String, String>()

  /**
   * Tracks the last driver type used to connect each device.
   * Used to detect driver type switches — when the configured driver type changes,
   * we must kill stale instrumentation processes before establishing a new connection.
   * Without this, leftover processes from a previous driver type block the new one
   * (only one instrumentation process can be active per device).
   */
  private val lastConnectedDriverType = ConcurrentHashMap<TrailblazeDeviceId, TrailblazeDriverType>()

  /**
   * Flow for reactively observing the selected device ID.
   */
  val selectedDeviceIdFlow: StateFlow<TrailblazeDeviceId?> = _selectedDeviceIdFlow.asStateFlow()

  /**
   * The currently selected device ID for MCP operations.
   */
  var selectedDeviceId: TrailblazeDeviceId?
    get() = _selectedDeviceIdFlow.value
    set(value) {
      _selectedDeviceIdFlow.value = value
    }

  override fun getAvailableAppTargets(): Set<TrailblazeHostAppTarget> {
    return trailblazeDeviceManager.availableAppTargets
  }

  override suspend fun selectDevice(trailblazeDeviceId: TrailblazeDeviceId): TrailblazeConnectedDeviceSummary {
    assertDeviceIsSelected(trailblazeDeviceId)

    // For WEB devices, kick off async Playwright browser initialization so that blaze()
    // can capture screen state once it completes — without blocking this device() call.
    //
    // Three states:
    //  1. Already ready   — getActivePlaywrightNativeTest() non-null → nothing to do
    //  2. Init in progress — webInitJobs contains the key → return immediately, let
    //                        getDriverConnectionStatus() report elapsed time to the client
    //  3. Not started     — launch a background thread and return immediately
    //
    // If a WebBrowserManager browser is already running (desktop "Launch Browser") the
    // background thread wraps it instead of launching a new one, so no download occurs.
    // Otherwise Playwright drivers/Chromium are downloaded once (~150 MB) and cached.
    val actualDriverType = trailblazeDeviceManager.getDeviceState(trailblazeDeviceId)?.device?.trailblazeDriverType
    if (trailblazeDeviceId.trailblazeDevicePlatform == TrailblazeDevicePlatform.WEB &&
      actualDriverType != TrailblazeDriverType.COMPOSE) {
      val webKey = trailblazeDeviceId.instanceId
      if (trailblazeDeviceManager.getActivePlaywrightNativeTest(trailblazeDeviceId) == null) {
        val job = WebInitState()
        if (webInitJobs.putIfAbsent(webKey, job) != null) {
          // Another thread already started init — skip.
        } else {
          // Clear any previous failure so a retry starts clean.
          webInitErrors.remove(webKey)
          val initThread = Thread {
            // Hoisted out of `try` so the cleanup path in `catch` can reach them and
            // close an orphaned Chromium if BasePlaywrightNativeTest construction
            // throws after we've created the browser. Without this, an init failure
            // would leak the process until daemon shutdown.
            var createdBrowser: PlaywrightBrowserManager? = null
            var registeredTest = false
            try {
              // Reuse an already-running browser for this instance ID (desktop app
              // "Launch Browser" or a prior MCP session) when available — no download
              // needed. Otherwise create a fresh browser, passing a progress callback
              // so the MCP client sees download status. Headless mode honors the
              // caller's recorded preference (e.g. CLI `--headless false`); when no
              // preference has been set, we default to headless = true.
              val existingBrowser = trailblazeDeviceManager.webBrowserManager.getPageManager(webKey)
              val headless = trailblazeDeviceManager.webBrowserManager.getHeadlessPreference(webKey) ?: true
              val browserManager = existingBrowser ?: PlaywrightBrowserManager(
                headless = headless,
                onBrowserInstallProgress = { percent, message ->
                  job.progressMessage = if (percent > 0) "[$percent%] $message" else message
                },
              ).also { createdBrowser = it }
              // If we just created a new browser, adopt it into WebBrowserManager so it
              // persists across MCP session boundaries. When cancelSessionForDevice is
              // called at session close, BasePlaywrightNativeTest.close() won't kill the
              // browser (ownsTheBrowser=false), and the next device(WEB) call for this
              // instance ID will reuse it via webBrowserManager.getPageManager(webKey)
              // rather than spawning a new instance.
              if (existingBrowser == null) {
                trailblazeDeviceManager.webBrowserManager.adoptManagedBrowser(
                  instanceId = webKey,
                  manager = browserManager as PlaywrightBrowserManager,
                  headless = headless,
                )
              }
              // Update progress to indicate we've moved past driver/browser download.
              job.progressMessage = "Launching browser..."
              // Resolve the configured LLM model + matching client so the cached test
              // doesn't fall back to BasePlaywrightNativeTest's OpenAI defaults — those
              // defaults break every web tool call when the user has configured a
              // provider that the default client doesn't know about.
              val configuredLlmModel = trailblazeDeviceManager.currentTrailblazeLlmModelProvider()
              // Honor the desktop-app "Capture Network Traffic" toggle on the
              // long-lived MCP test so host-local tool dispatches auto-capture.
              val mcpAppConfig =
                trailblazeDeviceManager.settingsRepo.serverStateFlow.value.appConfig
              val test = BasePlaywrightNativeTest(
                config = TrailblazeConfig(
                  captureNetworkTraffic = mcpAppConfig.captureNetworkTraffic,
                ),
                trailblazeDeviceId = trailblazeDeviceId,
                existingBrowserManager = browserManager,
                trailblazeLlmModel = configuredLlmModel,
                dynamicLlmClient = dynamicLlmClientProvider.invoke(configuredLlmModel),
              )
              trailblazeDeviceManager.setActivePlaywrightNativeTest(trailblazeDeviceId, test)
              registeredTest = true
              Console.log("[MCP Bridge] WEB browser ready for ${trailblazeDeviceId.instanceId}")
            } catch (e: Exception) {
              val msg = e.message ?: "Unknown error initializing Playwright browser"
              webInitErrors[webKey] = msg
              Console.log("[MCP Bridge] WEB browser init failed: $msg")
              // Clean up an orphaned browser: we created it but the wrapping test
              // never registered, so no MCP path can reach it. Closing the browser
              // and the slot lets the next device(WEB) call retry from scratch
              // instead of finding a half-broken adopted instance.
              val orphan = createdBrowser
              if (orphan != null && !registeredTest) {
                try {
                  orphan.close()
                } catch (closeErr: Exception) {
                  Console.log("[MCP Bridge] WEB browser cleanup failed (continuing): ${closeErr.message}")
                }
                trailblazeDeviceManager.webBrowserManager.closeBrowser(webKey)
              }
            } finally {
              webInitJobs.remove(webKey)
            }
          }
          initThread.isDaemon = true
          initThread.name = "web-browser-init-$webKey"
          initThread.start()
          Console.log("[MCP Bridge] WEB browser initialization started for ${trailblazeDeviceId.instanceId}")
        }
      }
    }

    // Only create a persistent Maestro HOST driver for host-side driver types.
    // On-device drivers (ACCESSIBILITY, INSTRUMENTATION) have their own on-device agent
    // that provides screen state via RPC — creating a Maestro HOST driver would kill
    // the running on-device service and break screen state capture.
    // WEB (PLAYWRIGHT_NATIVE) also skips this — it has its own Playwright-based connection
    // managed by BasePlaywrightNativeTest, not a Maestro driver.
    val configuredDriverType = getConfiguredDriverType(trailblazeDeviceId.trailblazeDevicePlatform)
    val needsPersistentDriver = configuredDriverType?.requiresHost != false &&
      trailblazeDeviceId.trailblazeDevicePlatform != TrailblazeDevicePlatform.WEB

    val key = trailblazeDeviceId.instanceId

    // Detect first connection or driver type switch.
    // All three Android driver types use an instrumentation process, and only one can
    // be active at a time. When connecting for the first time or switching driver types,
    // we must kill stale instrumentation processes to avoid conflicts (e.g., a leftover
    // accessibility driver blocking the HOST Maestro gRPC connection).
    val previousDriverType = lastConnectedDriverType[trailblazeDeviceId]
    val isFirstConnection = previousDriverType == null
    val isDriverTypeSwitch = previousDriverType != null && previousDriverType != configuredDriverType
    if (isFirstConnection || isDriverTypeSwitch) {
      Console.log("[MCP Bridge] Cleaning up stale instrumentation for $key (first=$isFirstConnection, switch=${isDriverTypeSwitch}, prev=$previousDriverType, new=$configuredDriverType)")
      // All Android driver types (HOST, ACCESSIBILITY, INSTRUMENTATION) use an
      // instrumentation process — HOST uses Maestro's instrumentation, while on-device
      // drivers use the Trailblaze runner. Only one can be active at a time, so we
      // must kill everything when first connecting or switching driver types.
      // Only applies to Android devices — iOS and Web don't use Android instrumentation.
      if (trailblazeDeviceId.trailblazeDevicePlatform == TrailblazeDevicePlatform.ANDROID) {
        HostAndroidDeviceConnectUtils.forceStopAllAndroidInstrumentationProcesses(
          trailblazeOnDeviceInstrumentationTargetTestApps = trailblazeDeviceManager.availableAppTargets
            .map { it.getTrailblazeOnDeviceInstrumentationTarget() }.toSet(),
          deviceId = trailblazeDeviceId,
        )
      }
      if (isDriverTypeSwitch) {
        // Close the old persistent Maestro driver if present
        Console.log("[MCP Bridge] Closing persistent HOST driver for $key (switching to $configuredDriverType)")
        closePersistentDevice(trailblazeDeviceId)
        // Clear on-device agent ready flag
        Console.log("[MCP Bridge] Clearing on-device agent ready flag for $key (switching to $configuredDriverType)")
        onDeviceAgentReady.remove(key)
      }
    }

    if (needsPersistentDriver && !persistentDevices.containsKey(key)) {
      // Reuse existing latch if driver creation is already in progress (e.g., preselectDeviceForSession
      // already started it). This prevents spawning redundant daemon threads.
      val existingLatch = driverCreationLatches[key]
      if (existingLatch != null) {
        // Another call already started driver creation — just wait on the existing latch
        if (!existingLatch.await(DEVICE_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
          recordDriverInitTimeout(key, trailblazeDeviceId, configuredDriverType)
        }
      } else {
        val latch = CountDownLatch(1)
        // putIfAbsent returns null if we won the race (we should start the thread),
        // or the existing latch if another thread beat us.
        val raceLatch = driverCreationLatches.putIfAbsent(key, latch)
        if (raceLatch != null) {
          // Another thread created the latch between our check and putIfAbsent — wait on theirs
          if (!raceLatch.await(DEVICE_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            recordDriverInitTimeout(key, trailblazeDeviceId, configuredDriverType)
          }
        } else {
          driverCreationStartTimes[key] = System.currentTimeMillis()
          driverCreationFailures.remove(key)
          val driverThread = Thread {
            try {
              synchronized(persistentDeviceLocks.computeIfAbsent(key) { Any() }) {
                if (!persistentDevices.containsKey(key)) {
                  when (val result = createPersistentDevice(trailblazeDeviceId)) {
                    is PersistentDeviceResult.Success -> {
                      persistentDevices[key] = result.device
                      driverCreationFailures.remove(key)
                    }
                    is PersistentDeviceResult.Failure -> {
                      driverCreationFailures[key] = "Driver initialization failed: ${result.reason}. " +
                        "Try reconnecting with device(action=${trailblazeDeviceId.trailblazeDevicePlatform.name})."
                    }
                  }
                }
              }
            } finally {
              latch.countDown()
              driverCreationLatches.remove(key)
              driverCreationStartTimes.remove(key)
            }
          }
          driverThread.name = "mcp-driver-init-$key"
          driverThread.isDaemon = true
          driverThread.start()

          if (!latch.await(DEVICE_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            recordDriverInitTimeout(key, trailblazeDeviceId, configuredDriverType)
          }
        }
      }
    } else if (!needsPersistentDriver && !onDeviceAgentReady.contains(key) &&
      trailblazeDeviceId.trailblazeDevicePlatform == TrailblazeDevicePlatform.ANDROID) {
      // On-device driver (ACCESSIBILITY, INSTRUMENTATION) — ensure the on-device agent is running.
      // This installs the test APK, starts instrumentation, and enables the accessibility service.
      // Analogous to creating a persistent Maestro driver for HOST mode above.
      // WEB devices are excluded: they don't use on-device agents.
      ensureOnDeviceAgentRunning(trailblazeDeviceId, configuredDriverType)
    }

    // Track the driver type used for this connection so we can detect switches later
    if (configuredDriverType != null) {
      lastConnectedDriverType[trailblazeDeviceId] = configuredDriverType
    }

    // First check the device state map (populated by loadDevices with UI-level filter).
    // Fall back to unfiltered available devices — in headless/MCP mode, the UI-level
    // targetDeviceFilter excludes virtual devices (Playwright, Compose) because
    // testingEnvironment is null, but MCP always needs to offer them.
    val resolved = trailblazeDeviceManager.getDeviceState(trailblazeDeviceId)?.device
      ?: getAvailableDevices().find { it.trailblazeDeviceId == trailblazeDeviceId }
    if (resolved != null) return resolved

    // Web devices are virtual: the init thread we just kicked off above provisions
    // the browser asynchronously, so a brand-new `web/<id>` won't appear in the
    // device state map or the available-devices list yet. Synthesize the summary
    // from the device ID so the caller gets a successful claim and can poll
    // `getDriverConnectionStatus` for readiness.
    if (trailblazeDeviceId.trailblazeDevicePlatform == TrailblazeDevicePlatform.WEB) {
      return TrailblazeConnectedDeviceSummary(
        trailblazeDriverType = TrailblazeDriverType.PLAYWRIGHT_NATIVE,
        instanceId = trailblazeDeviceId.instanceId,
        description = "Chrome Browser (${trailblazeDeviceId.instanceId})",
      )
    }

    error("Device $trailblazeDeviceId is not available.")
  }

  /**
   * Records a driver-init timeout in [driverCreationFailures] so that
   * [getDriverConnectionStatus] surfaces the real reason on the next poll, instead of the
   * old "continuing without it" pattern that left the daemon claiming the device while
   * silently bound to a not-ready driver. Also enriches the log line with platform and
   * driver type so on-call has something to debug from when this rare path fires.
   *
   * Re-checks [persistentDevices] before writing because the driver thread can race in
   * between [java.util.concurrent.CountDownLatch.await] returning false and this method
   * running — if the driver actually completed during that window, do not record a
   * spurious failure. Uses [java.util.concurrent.ConcurrentHashMap.putIfAbsent] so a
   * specific failure already recorded by [PersistentDeviceResult.Failure] wins over the
   * generic timeout message.
   */
  private fun recordDriverInitTimeout(
    key: String,
    trailblazeDeviceId: TrailblazeDeviceId,
    configuredDriverType: TrailblazeDriverType?,
  ) {
    val driverLabel = configuredDriverType?.name ?: "driver"
    val platform = trailblazeDeviceId.trailblazeDevicePlatform.name
    val message = "$driverLabel ($platform/$key) did not become ready within " +
      "${DEVICE_CONNECT_TIMEOUT_SECONDS}s. Reconnect with device(action=$platform) to retry."
    Console.log("[MCP Bridge] $message")
    if (!persistentDevices.containsKey(key)) {
      driverCreationFailures.putIfAbsent(key, message)
    }
  }

  companion object {
    /**
     * How long to wait for the Maestro driver during device connect. Sized to cover the
     * worst real first-connection case: an iOS XCTest setup against a device-farm simulator,
     * which can take 60+ seconds end-to-end. The previous 5s value was the source of the
     * "Device driver is still initializing" cliff users (and CI) hit on first connect — the
     * tool call would return success while the driver was still coming up, and then the
     * very next command would fail because nothing was actually ready.
     *
     * Subsequent connections short-circuit when [persistentDevices] already has the device,
     * so this timeout only applies to the first connect of a session — it does not slow down
     * steady-state operation.
     */
    private const val DEVICE_CONNECT_TIMEOUT_SECONDS = 120L

    /**
     * How long to wait for the on-device agent to start during device connect.
     * Longer than HOST mode because it may need to install APK, start instrumentation,
     * enable accessibility service, and verify the RPC server. If the agent still isn't
     * ready, the driver status provider reports progress.
     */
    private const val ON_DEVICE_AGENT_TIMEOUT_SECONDS = 30L

    /**
     * How long getDirectScreenStateProvider() waits for an in-progress driver creation
     * before returning null. Set to 0 — no wait.
     *
     * The wait is a BLOCKING call (CountDownLatch.await) inside a non-cooperative
     * runBlocking, so it cannot be cancelled by withTimeoutOrNull. Any non-zero value
     * directly delays blaze/verify/ask responses, causing Claude Code to time out and
     * close the STDIO connection with "Connection closed" (-32000).
     *
     * When the persistent device isn't ready yet, return null immediately and let
     * captureScreenState fall through to session-based capture as a backup.
     */
    private const val DRIVER_READY_WAIT_SECONDS = 0L
  }

  /**
   * Creates a persistent Maestro driver connection for the specified device.
   * This enables direct, fast screen state capture without session overhead.
   *
   * @return The connected device, or a sentinel null that will not be stored by computeIfAbsent.
   */
  /**
   * Result of attempting to create a persistent device connection.
   */
  private sealed class PersistentDeviceResult {
    data class Success(val device: TrailblazeConnectedDevice) : PersistentDeviceResult()
    data class Failure(val reason: String) : PersistentDeviceResult()
  }

  private fun createPersistentDevice(trailblazeDeviceId: TrailblazeDeviceId): PersistentDeviceResult {
    return try {
      val driverType = getConfiguredDriverType(trailblazeDeviceId.trailblazeDevicePlatform)
        ?: error("No configured driver type for ${trailblazeDeviceId.trailblazeDevicePlatform}")
      val connectedDevice = TrailblazeDeviceService.getConnectedDevice(
        trailblazeDeviceId = trailblazeDeviceId,
        driverType = driverType,
        appTarget = trailblazeDeviceManager.getCurrentSelectedTargetApp(),
      )
      if (connectedDevice != null) {
        PersistentDeviceResult.Success(connectedDevice)
      } else {
        PersistentDeviceResult.Failure("Device service returned null for $trailblazeDeviceId")
      }
    } catch (e: Exception) {
      Console.log("[MCP Bridge] Persistent device connection failed: ${e.message}")
      PersistentDeviceResult.Failure(e.message ?: "Unknown error")
    }
  }

  /**
   * Closes the persistent device connection for a specific device.
   */
  private fun closePersistentDevice(deviceId: TrailblazeDeviceId) {
    val key = deviceId.instanceId
    // Cancel any in-progress driver creation latch so waiters don't block forever
    driverCreationLatches.remove(key)?.countDown()
    // Synchronize on the same lock used by selectDevice() to prevent a race where
    // another thread creates a new connection between our remove and the lock cleanup.
    // We intentionally never remove from persistentDeviceLocks — they are tiny Any objects
    // and removing them would allow a concurrent selectDevice() to create a new lock,
    // bypassing mutual exclusion.
    val lock = persistentDeviceLocks.computeIfAbsent(key) { Any() }
    synchronized(lock) {
      persistentDevices.remove(key)?.let { device ->
        try {
          (device as? MaestroConnectedDevice)?.getMaestroDriver()?.close()
        } catch (e: Exception) {
          Console.log("[MCP Bridge] Exception closing persistent device $key (already closed?): ${e.message}")
        }
      }
    }
  }

  /**
   * Ensures the on-device agent is running for on-device driver types (ACCESSIBILITY, INSTRUMENTATION).
   *
   * This is the on-device counterpart to creating a persistent Maestro driver for HOST mode.
   * It installs the test APK, starts instrumentation, enables the accessibility service (if needed),
   * and verifies the RPC server is reachable.
   *
   * Uses the same latch/timeout pattern as HOST mode driver creation:
   * - Background thread does the setup
   * - Waits up to [ON_DEVICE_AGENT_TIMEOUT_SECONDS] for completion
   * - If still initializing, [getDriverConnectionStatus] reports progress
   */
  private fun ensureOnDeviceAgentRunning(
    trailblazeDeviceId: TrailblazeDeviceId,
    driverType: TrailblazeDriverType?,
  ) {
    val key = trailblazeDeviceId.instanceId

    // Reuse existing latch if setup is already in progress
    val existingLatch = driverCreationLatches[key]
    if (existingLatch != null) {
      if (!existingLatch.await(ON_DEVICE_AGENT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        Console.log(
          "[MCP Bridge] On-device agent still initializing for $key " +
              "after ${ON_DEVICE_AGENT_TIMEOUT_SECONDS}s — continuing without it"
        )
      }
      return
    }

    val latch = CountDownLatch(1)
    val raceLatch = driverCreationLatches.putIfAbsent(key, latch)
    if (raceLatch != null) {
      if (!raceLatch.await(ON_DEVICE_AGENT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        Console.log(
          "[MCP Bridge] On-device agent still initializing for $key " +
              "after ${ON_DEVICE_AGENT_TIMEOUT_SECONDS}s — continuing without it"
        )
      }
      return
    }

    driverCreationStartTimes[key] = System.currentTimeMillis()
    val agentThread = Thread {
      try {
        val appTarget = trailblazeDeviceManager.getCurrentSelectedTargetApp()
        val target = appTarget?.getTrailblazeOnDeviceInstrumentationTarget()
          ?: TrailblazeOnDeviceInstrumentationTarget.DEFAULT_ANDROID_ON_DEVICE

        Console.log("[MCP Bridge] Starting on-device agent for $key (driver=${driverType?.name})")

        // Install APK and start instrumentation (reuses existing agent if already running)
        // Pass LLM tokens so the on-device runner can create LLM clients (e.g., SSO token).
        runBlocking {
          HostAndroidDeviceConnectUtils.connectToInstrumentationAndInstallAppIfNotAvailable(
            sendProgressMessage = { Console.log("[MCP Bridge] [$key] $it") },
            deviceId = trailblazeDeviceId,
            trailblazeOnDeviceInstrumentationTarget = target,
            additionalInstrumentationArgs = trailblazeDeviceManager.onDeviceInstrumentationArgsProvider(),
          )
        }

        // Enable the service in ADB settings, then block on-device until it's connected.
        // The on-device check uses the reliable in-process TrailblazeAccessibilityService
        // singleton rather than host-side dumpsys parsing which is unreliable on API 35+.
        if (driverType == TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY) {
          AccessibilityServiceSetupUtils.enableAccessibilityService(
            deviceId = trailblazeDeviceId,
            hostPackage = target.testAppId,
            sendProgressMessage = { Console.log("[MCP Bridge] [$key] $it") },
          )
        }

        // Wait until the device can actually serve a GetScreenState call — the one readiness
        // check that guarantees everything downstream (HTTP server, accessibility service binding,
        // window population) is in place.
        runBlocking {
          val rpcClient = OnDeviceRpcClient(
            trailblazeDeviceId = trailblazeDeviceId,
            sendProgressMessage = { Console.log("[MCP Bridge] [$key] $it") },
          )
          try {
            rpcClient.waitForReady(
              requireAndroidAccessibilityService =
                driverType == TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
            )
          } finally {
            rpcClient.close()
          }
        }

        onDeviceAgentReady.add(key)
        Console.log("[MCP Bridge] On-device agent ready for $key")
      } catch (e: Exception) {
        Console.log("[MCP Bridge] On-device agent setup failed for $key: ${e.message}")
      } finally {
        latch.countDown()
        driverCreationLatches.remove(key)
        driverCreationStartTimes.remove(key)
      }
    }
    agentThread.name = "mcp-ondevice-init-$key"
    agentThread.isDaemon = true
    agentThread.start()

    if (!latch.await(ON_DEVICE_AGENT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
      Console.log(
        "[MCP Bridge] On-device agent still initializing for $key " +
            "after ${ON_DEVICE_AGENT_TIMEOUT_SECONDS}s — continuing without it"
      )
    }
  }

  override fun releasePersistentDeviceConnection(deviceId: TrailblazeDeviceId) {
    cachedScreenStates.remove(deviceId.instanceId)
    onDeviceAgentReady.remove(deviceId.instanceId)
    driverCreationFailures.remove(deviceId.instanceId)
    closePersistentDevice(deviceId)
    Console.log("[MCP Bridge] Released persistent connection for device ${deviceId.instanceId}")
  }

  override suspend fun runYaml(
    yaml: String,
    startNewSession: Boolean,
    agentImplementation: AgentImplementation,
  ): String = runYamlInternal(yaml, startNewSession, agentImplementation)

  /**
   * Internal runYaml that supports an optional [onComplete] callback.
   * The public [runYaml] override delegates here (without onComplete).
   */
  private suspend fun runYamlInternal(
    yaml: String,
    startNewSession: Boolean,
    agentImplementation: AgentImplementation = AgentImplementation.DEFAULT,
    traceId: TraceId? = null,
    onComplete: ((TrailExecutionResult) -> Unit)? = null,
  ): String {
    val deviceId = assertDeviceIsSelected()

    val sessionResolution = trailblazeDeviceManager.getOrCreateSessionResolution(
      trailblazeDeviceId = deviceId,
      forceNewSession = startNewSession,
      sessionIdPrefix = "yaml"
    )

    trailblazeDeviceManager.runYaml(
      yamlToRun = yaml,
      trailblazeDeviceId = deviceId,
      forceStopTargetApp = false,
      sendSessionStartLog = sessionResolution.isNewSession,
      sendSessionEndLog = false,
      existingSessionId = sessionResolution.sessionId,
      referrer = TrailblazeReferrer.MCP,
      agentImplementation = agentImplementation,
      traceId = traceId,
      onComplete = onComplete,
    )

    // Return the session ID used for this run so callers can monitor progress
    return sessionResolution.sessionId.value
  }

  override fun getCurrentlySelectedDeviceId(): TrailblazeDeviceId? {
    return getEffectiveDeviceId()
  }

  override suspend fun getCurrentScreenState(): ScreenState? {
    val trailblazeDeviceId = assertDeviceIsSelected()
    val key = trailblazeDeviceId.instanceId

    // Return cached state if available (from last tool execution)
    cachedScreenStates[key]?.let { return it }

    // Otherwise capture fresh state (creates a new session)
    return trailblazeDeviceManager.getCurrentScreenState(trailblazeDeviceId).also {
      if (it != null) cachedScreenStates[key] = it
    }
  }

  /**
   * Returns the cached screen state without capturing a new one.
   * Returns null if no cached state is available.
   */
  fun getCachedScreenState(): ScreenState? {
    val key = getEffectiveDeviceId()?.instanceId ?: return null
    return cachedScreenStates[key]
  }

  /**
   * Clears the cached screen state for the currently selected device.
   * Call this when you want to force a fresh capture on next request.
   */
  fun clearCachedScreenState() {
    val key = getEffectiveDeviceId()?.instanceId ?: return
    cachedScreenStates.remove(key)
  }

  override fun getDirectScreenStateProvider(skipScreenshot: Boolean): ((ScreenshotScalingConfig) -> ScreenState)? {
    val deviceId = getEffectiveDeviceId() ?: return null
    val key = deviceId.instanceId

    // Use persistent device connection if available (preferred - always ready)
    persistentDevices[key]?.let { device ->
      when (device) {
        is MaestroConnectedDevice -> {
          val driver = device.getMaestroDriver()
          return { scalingConfig ->
            HostMaestroDriverScreenState(
              maestroDriver = driver,
              screenshotScalingConfig = scalingConfig,
              skipScreenshot = skipScreenshot,
            )
          }
        }
        is AxeConnectedDevice -> {
          return { _ ->
            AxeScreenState(udid = device.udid, deviceWidth = device.deviceWidth, deviceHeight = device.deviceHeight)
          }
        }
      }
    }

    // If driver creation is in progress, wait for it rather than returning null.
    // This handles the common case where device(action=ANDROID) returned before the
    // Maestro driver was ready, and now blaze/ask/verify needs it.
    driverCreationLatches[key]?.let { latch ->
      Console.log("[MCP Bridge] Waiting for persistent device driver for $key...")
      if (latch.await(DRIVER_READY_WAIT_SECONDS, TimeUnit.SECONDS)) {
        // Driver creation finished — check if it succeeded
        persistentDevices[key]?.let { device ->
          Console.log("[MCP Bridge] Persistent device driver ready for $key")
          when (device) {
            is MaestroConnectedDevice -> {
              val driver = device.getMaestroDriver()
              return { scalingConfig ->
                HostMaestroDriverScreenState(
                  maestroDriver = driver,
                  screenshotScalingConfig = scalingConfig,
                  skipScreenshot = skipScreenshot,
                )
              }
            }
            is AxeConnectedDevice -> {
              return { _ ->
                AxeScreenState(udid = device.udid, deviceWidth = device.deviceWidth, deviceHeight = device.deviceHeight)
              }
            }
          }
        }
        // Driver creation finished but failed (createPersistentDevice returned null)
        Console.log("[MCP Bridge] Persistent device driver creation failed for $key")
      } else {
        Console.log("[MCP Bridge] Timed out waiting for persistent device driver for $key")
      }
    }

    // WEB: COMPOSE uses its own RPC server; Playwright uses the cached test.
    if (deviceId.trailblazeDevicePlatform == TrailblazeDevicePlatform.WEB) {
      val driverType = trailblazeDeviceManager.getDeviceState(deviceId)?.device?.trailblazeDriverType
      if (driverType == TrailblazeDriverType.COMPOSE) {
        return getComposeScreenStateProvider()
      }
      return getPlaywrightScreenStateProvider(deviceId)
    }

    // Fallback: use active driver from device manager (only available during YAML execution)
    val driver = trailblazeDeviceManager.getActiveDriverForDevice(deviceId) ?: return null

    return { scalingConfig ->
      HostMaestroDriverScreenState(
        maestroDriver = driver,
        screenshotScalingConfig = scalingConfig,
      )
    }
  }

  /**
   * Returns a screen state provider backed by the cached [BasePlaywrightNativeTest] for the
   * given WEB device, or null if no test is cached yet (e.g. before the first blaze call).
   *
   * Playwright API calls require thread affinity — all calls must happen on the dedicated
   * `playwright-browser` dispatcher thread. [runBlocking] with that dispatcher schedules
   * the capture on the correct thread and blocks the calling thread until it completes.
   */
  private fun getPlaywrightScreenStateProvider(
    deviceId: TrailblazeDeviceId,
  ): ((ScreenshotScalingConfig) -> ScreenState)? {
    val test = trailblazeDeviceManager.getActivePlaywrightNativeTest(deviceId) ?: return null
    return { _ ->
      runBlocking(test.browserManager.playwrightDispatcher) {
        test.browserManager.getScreenState()
      }
    }
  }

  /**
   * Checks if the currently selected device is using on-device instrumentation.
   */
  override fun isOnDeviceInstrumentation(): Boolean {
    val deviceId = getEffectiveDeviceId() ?: return false
    // Use the CONFIGURED driver type, not the device state's driver type.
    // The device state map is keyed by TrailblazeDeviceId (instanceId + platform) which
    // doesn't include driver type, so all three Android driver variants map to the same key
    // and the last one (HOST) overwrites the others. The configured driver type from settings
    // is the source of truth for which driver mode is active.
    val driverType = getConfiguredDriverType(deviceId.trailblazeDevicePlatform)
      ?: return false
    return driverType.platform == TrailblazeDevicePlatform.ANDROID && !driverType.requiresHost
  }

  override fun getDriverConnectionStatus(deviceId: TrailblazeDeviceId?): String? {
    val id = deviceId ?: getEffectiveDeviceId() ?: return null
    val key = id.instanceId

    // WEB: check Playwright browser initialization state separately from Maestro drivers.
    if (id.trailblazeDevicePlatform == TrailblazeDevicePlatform.WEB) {
      // Ready — test is cached, nothing more to report.
      if (trailblazeDeviceManager.getActivePlaywrightNativeTest(id) != null) return null
      // Initialization is in progress or failed.
      // Failure from a previous attempt — report it until the user retries.
      webInitErrors[key]?.let {
        return "Playwright browser installation failed: $it"
      }
      val job = webInitJobs[key]
      if (job != null) {
        val elapsedSeconds = (System.currentTimeMillis() - job.startTimeMs) / 1000
        return buildString {
          append("Playwright browser installing (${elapsedSeconds}s elapsed")
          // Downloads are sequential: Playwright driver (~7MB) then Chromium (~150MB).
          // Chromium install subprocess times out at 15 minutes.
          if (elapsedSeconds < 900) append(", timeout in ${900 - elapsedSeconds}s")
          append("): ${job.progressMessage}")
        }
      }
      return null
    }

    // Driver is ready — no status to report (HOST persistent driver or on-device agent)
    if (persistentDevices.containsKey(key) || onDeviceAgentReady.contains(key)) return null

    // Driver creation is in progress — report elapsed time
    val startTime = driverCreationStartTimes[key]
    if (startTime != null && driverCreationLatches.containsKey(key)) {
      val elapsedSeconds = (System.currentTimeMillis() - startTime) / 1000
      return "Device driver is still initializing (${elapsedSeconds}s elapsed). Try again shortly."
    }

    // Driver creation failed — report the error so the LLM doesn't retry device()
    driverCreationFailures[key]?.let { return it }

    return null
  }

  /**
   * Gets the driver type for the currently selected device.
   * Prefers the actual device state driver type (handles COMPOSE vs PLAYWRIGHT_NATIVE
   * distinction) over the configured driver type from settings.
   */
  override fun getDriverType(): TrailblazeDriverType? {
    val deviceId = getEffectiveDeviceId() ?: return null
    return trailblazeDeviceManager.getDeviceState(deviceId)?.device?.trailblazeDriverType
      ?: getConfiguredDriverType(deviceId.trailblazeDevicePlatform)
  }

  /**
   * Returns a screen state provider that fetches from the Compose RPC server.
   * This is used when the selected device is a COMPOSE driver.
   */
  private fun getComposeScreenStateProvider(): ((ScreenshotScalingConfig) -> ScreenState)? {
    return { _ ->
      ComposeRpcScreenStateAdapter(fetchComposeScreenState())
    }
  }

  /**
   * Fetches the current screen state from the Compose RPC server synchronously.
   */
  private fun fetchComposeScreenState(): ComposeGetScreenStateResponse {
    val httpUtils = HttpRequestUtils("http://localhost:${TrailblazeDevicePort.COMPOSE_DEFAULT_RPC_PORT}")
    return try {
      val json = runBlocking { httpUtils.postRequest("/rpc/GetScreenStateRequest", "{}") }
      TrailblazeJsonInstance.decodeFromString(json)
    } finally {
      httpUtils.close()
    }
  }

  /** Executes a COMPOSE tool by forwarding it to the Compose RPC server. */
  private suspend fun executeComposeToolViaRpc(tool: TrailblazeTool): String {
    val httpUtils = HttpRequestUtils("http://localhost:${TrailblazeDevicePort.COMPOSE_DEFAULT_RPC_PORT}")
    return try {
      val request = ComposeExecuteToolsRequest(tools = listOf(tool))
      val json = TrailblazeJsonInstance.encodeToString(request)
      httpUtils.postRequest("/rpc/ExecuteToolsRequest", json)
    } finally {
      httpUtils.close()
    }
  }

  /**
   * Returns Compose-specific tool classes when the Compose driver is active, null otherwise.
   *
   * The Compose driver uses a different tool set than native Android/iOS drivers — the inner
   * agent must use Compose-specific tools (click, type, scroll) instead.
   */
  override fun getInnerAgentToolClasses(): Set<KClass<out TrailblazeTool>>? {
    val deviceId = getEffectiveDeviceId() ?: return null
    val driverType = trailblazeDeviceManager.getDeviceState(deviceId)?.device?.trailblazeDriverType
    return when (driverType) {
      TrailblazeDriverType.COMPOSE -> TrailblazeToolSetCatalog.resolveForDriver(
        driverType, ComposeToolSetIds.ALL,
      ).toolClasses
      else -> null
    }
  }

  override fun getInnerAgentBuiltInToolClasses(): Set<kotlin.reflect.KClass<out xyz.block.trailblaze.toolcalls.TrailblazeTool>> {
    val driverType = getDriverType() ?: return emptySet()
    return when (driverType) {
      // Both Playwright drivers share the web YAML toolsets — returning emptySet for ELECTRON
      // would make the MCP server fall back to the Maestro tool set, which doesn't apply to a
      // Playwright-based Electron session.
      TrailblazeDriverType.PLAYWRIGHT_NATIVE,
      TrailblazeDriverType.PLAYWRIGHT_ELECTRON -> TrailblazeToolSetCatalog.resolveForDriver(
        driverType, WebToolSetIds.ALL,
      ).toolClasses
      TrailblazeDriverType.REVYL_ANDROID,
      TrailblazeDriverType.REVYL_IOS -> TrailblazeToolSetCatalog.resolveForDriver(
        driverType, RevylToolSetIds.ALL,
      ).toolClasses
      else -> emptySet()
    }
  }

  /**
   * Gets screen state via RPC for on-device instrumentation mode.
   * This calls the GetScreenStateRequest endpoint on the on-device agent.
   *
   * @param includeScreenshot Whether to include screenshot bytes
   * @param screenshotScalingConfig Configuration for scaling/compressing screenshots on-device
   * @return GetScreenStateResponse on success, null on failure
   */
  override suspend fun getScreenStateViaRpc(
    includeScreenshot: Boolean,
    screenshotScalingConfig: ScreenshotScalingConfig,
    includeAnnotatedScreenshot: Boolean,
    includeAllElements: Boolean,
  ): GetScreenStateResponse? {
    val deviceId = getEffectiveDeviceId() ?: return null

    if (!isOnDeviceInstrumentation()) {
      return null
    }

    val rpcClient = OnDeviceRpcClient(
      trailblazeDeviceId = deviceId,
      sendProgressMessage = { },
    )

    return try {
      val request = GetScreenStateRequest(
        includeScreenshot = includeScreenshot,
        screenshotMaxDimension1 = screenshotScalingConfig.maxDimension1,
        screenshotMaxDimension2 = screenshotScalingConfig.maxDimension2,
        screenshotImageFormat = screenshotScalingConfig.imageFormat,
        screenshotCompressionQuality = screenshotScalingConfig.compressionQuality,
        includeAnnotatedScreenshot = includeAnnotatedScreenshot,
        includeAllElements = includeAllElements,
      )

      when (val result: RpcResult<GetScreenStateResponse> = rpcClient.rpcCall(request)) {
        is RpcResult.Success -> result.data
        is RpcResult.Failure -> null
      }
    } finally {
      rpcClient.close()
    }
  }

  override suspend fun getAvailableDevices(): Set<TrailblazeConnectedDeviceSummary> {
    // Load all devices (unfiltered) — the UI-level targetDeviceFilter may not work correctly
    // in headless/MCP mode (e.g., testingEnvironment=null skips Playwright, but MCP always
    // needs to offer web). Instead, deduplicate here using the configured driver type per platform.
    val allDevices = trailblazeDeviceManager.loadDevicesSuspend(applyDriverFilter = false)
    return allDevices
      .groupBy { it.instanceId to it.platform }
      .map { (_, variants) ->
        val platform = variants.first().platform
        val configuredType = getConfiguredDriverType(platform)
        if (configuredType != null) {
          variants.find { it.trailblazeDriverType == configuredType } ?: variants.first()
        } else {
          variants.first()
        }
      }
      .toSet()
  }

  override suspend fun getInstalledAppIds(): Set<String> {
    val trailblazeDeviceId = assertDeviceIsSelected()
    return trailblazeDeviceManager.getInstalledAppIdsFlow(trailblazeDeviceId).value
  }

  override suspend fun executeTrailblazeTool(
    tool: TrailblazeTool,
    blocking: Boolean,
    traceId: TraceId?,
  ): String {
    val trailblazeDeviceId = assertDeviceIsSelected()

    // Use custom executor if provided, otherwise convert to YAML and run
    if (trailblazeToolExecutor != null) {
      cachedScreenStates.remove(trailblazeDeviceId.instanceId)
      return trailblazeToolExecutor.invoke(tool, trailblazeDeviceId)
    }

    // Default implementation: convert tool to YAML and run via runYaml()
    Console.log("Executing TrailblazeTool via YAML conversion: ${tool::class.simpleName}")

    val yaml = createTrailblazeYaml().encodeToString(
      TrailblazeYamlBuilder()
        .tools(listOf(tool))
        .build()
    )

    Console.log("Generated YAML:\n$yaml")

    // For Compose driver, send the tool directly to the Compose RPC server.
    if (trailblazeDeviceManager.getDeviceState(trailblazeDeviceId)?.device?.trailblazeDriverType == TrailblazeDriverType.COMPOSE) {
      val result = executeComposeToolViaRpc(tool)
      cachedScreenStates.remove(trailblazeDeviceId.instanceId)
      return result
    }

    // IOS_AXE driver: convert Maestro commands → AxeActions → dispatch via AxeCli.
    // Skips the Maestro/XCUITest yaml runner path entirely — the IosAxeTrailblazeAgent
    // class stays available for the host-test-rule path when we wire that up next.
    if (trailblazeDeviceManager.getDeviceState(trailblazeDeviceId)?.device?.trailblazeDriverType == TrailblazeDriverType.IOS_AXE) {
      val result = executeToolViaAxe(tool, trailblazeDeviceId)
      cachedScreenStates.remove(trailblazeDeviceId.instanceId)
      return result
    }

    // For on-device drivers, send the YAML directly via RPC and wait for completion.
    // The standard runYaml() path is fire-and-forget (launches a coroutine and returns
    // as soon as the session is created), so tool actions like taps return "executed"
    // before the on-device agent actually performs them.
    if (isOnDeviceInstrumentation()) {
      val result = executeToolViaRpc(tool, trailblazeDeviceId, yaml, blocking, traceId)
      cachedScreenStates.remove(trailblazeDeviceId.instanceId)
      return result
    }

    // HOST driver path: clear cache since screen will change
    cachedScreenStates.remove(trailblazeDeviceId.instanceId)

    if (blocking) {
      // Blocking mode: suspend until the Maestro YAML execution finishes.
      // Uses CompletableDeferred bridged to the DesktopYamlRunner's onComplete callback.
      val completion = CompletableDeferred<TrailExecutionResult>()
      runYamlInternal(
        yaml = yaml,
        startNewSession = false,
        traceId = traceId,
        onComplete = { completion.complete(it) },
      )
      val executionResult = withTimeout(300.seconds) { completion.await() }
      return when (executionResult) {
        is TrailExecutionResult.Success ->
          "Executed ${tool::class.simpleName} on device ${trailblazeDeviceId.instanceId}"
        is TrailExecutionResult.Failed ->
          error("Tool execution failed: ${executionResult.errorMessage}")
        is TrailExecutionResult.Cancelled ->
          error("Tool execution cancelled")
      }
    }

    val sessionId = runYamlInternal(yaml, startNewSession = false, traceId = traceId)
    return "Executed ${tool::class.simpleName} on device ${trailblazeDeviceId.instanceId} (session: $sessionId)"
  }

  override suspend fun executeHostLocalTool(
    tool: TrailblazeTool,
    toolRepo: TrailblazeToolRepo,
    traceId: TraceId?,
  ): String? {
    if (tool !is HostLocalExecutableTrailblazeTool) return null
    val deviceId = getEffectiveDeviceId() ?: return null
    return when (getDriverType()) {
      TrailblazeDriverType.PLAYWRIGHT_NATIVE,
      TrailblazeDriverType.PLAYWRIGHT_ELECTRON -> executeHostLocalPlaywrightTool(
        tool = tool,
        toolRepo = toolRepo,
        deviceId = deviceId,
        traceId = traceId,
      )
      else -> null
    }
  }

  /**
   * Routes an MCP tool call for an IOS_AXE-configured device to [IosAxeTrailblazeAgent.runTool].
   *
   * The bridge's job here is to wire up the device manager, agent, screen state, and execution
   * context. The agent handles all the tool-shape dispatch, supporting:
   *
   *   * [ExecutableTrailblazeTool] (e.g. `InputTextTrailblazeTool`) — runs directly; its
   *     internal `runMaestroCommands` calls land on our agent, which routes Maestro commands
   *     through `MaestroCommandToAxeActionConverter` → `AxeTrailRunner`.
   *   * [DelegatingTrailblazeTool] (e.g. `TapTrailblazeTool`) — expands to a list of
   *     `ExecutableTrailblazeTool`s via `toExecutableTrailblazeTools(ctx)`, then each one
   *     runs through the same path.
   *   * `MapsToMaestroCommands` is an `ExecutableTrailblazeTool` whose `execute` just calls
   *     `runMaestroCommands`, so it falls through the first branch.
   *
   * The context carries a fresh [AxeScreenState] so tools that need to read the current
   * tree (e.g. `tap ref=e964`) can find their target.
   *
   * **POC limitation:** session logging is not wired through this path — trails executed on
   * IOS_AXE while a session is active will not emit per-tool logs or screen states to the
   * session directory. A one-time stderr warning is emitted on first call so users notice
   * before their session directory comes up empty. Proper wire-up is tracked as a follow-up.
   */
  private suspend fun executeToolViaAxe(tool: TrailblazeTool, trailblazeDeviceId: TrailblazeDeviceId): String {
    val persistentDevice = persistentDevices[trailblazeDeviceId.instanceId] as? AxeConnectedDevice
      ?: error("IOS_AXE execution requires an AxeConnectedDevice in the persistent registry; got ${persistentDevices[trailblazeDeviceId.instanceId]?.let { it::class.simpleName }}")
    val deviceManager = AxeDeviceManager(
      udid = persistentDevice.udid,
      deviceWidth = persistentDevice.deviceWidth,
      deviceHeight = persistentDevice.deviceHeight,
    )
    val agent = buildAxeAgent(deviceManager, persistentDevice)
    val screenState = AxeScreenState(
      udid = persistentDevice.udid,
      deviceWidth = persistentDevice.deviceWidth,
      deviceHeight = persistentDevice.deviceHeight,
    )
    // POC limitation: the AXE path short-circuits runYamlInternal, which means the
    // TrailblazeLoggingRule + session wiring that IOS_HOST gets doesn't apply here.
    // If a session is active, its directory will NOT capture per-tool-call logs for
    // IOS_AXE actions. Surface that loudly once per daemon lifetime so nobody is
    // surprised by empty session artifacts after running against the AXE driver.
    warnIosAxeSessionLoggingGapOnce()
    Console.log("[IOS_AXE] Executing ${tool::class.simpleName} on ${persistentDevice.udid}")
    val ctx = TrailblazeToolExecutionContext(
      screenState = screenState,
      traceId = xyz.block.trailblaze.logs.model.TraceId.generate(
        origin = xyz.block.trailblaze.logs.model.TraceId.Companion.TraceOrigin.MCP,
      ),
      trailblazeDeviceInfo = axeDeviceInfo(persistentDevice),
      sessionProvider = { axeSession() },
      screenStateProvider = { deviceManager.getScreenState() },
      androidDeviceCommandExecutor = null,
      trailblazeLogger = noOpTrailblazeLogger(),
      memory = AgentMemory(),
      maestroTrailblazeAgent = agent,
      nodeSelectorMode = agent.nodeSelectorMode,
    )
    val result = agent.runTool(tool, ctx)
    return when (result) {
      is TrailblazeToolResult.Success ->
        "Executed ${tool::class.simpleName} via IOS_AXE on ${persistentDevice.udid}"
      is TrailblazeToolResult.Error -> error("IOS_AXE tool execution failed: ${result.errorMessage}")
    }
  }

  /** Builds a fresh [IosAxeTrailblazeAgent] around [deviceManager] for a single tool invocation. */
  private fun buildAxeAgent(
    deviceManager: AxeDeviceManager,
    device: AxeConnectedDevice,
  ): IosAxeTrailblazeAgent = IosAxeTrailblazeAgent(
    deviceManager = deviceManager,
    trailblazeLogger = noOpTrailblazeLogger(),
    trailblazeDeviceInfoProvider = { axeDeviceInfo(device) },
    sessionProvider = { axeSession() },
  )

  private fun axeDeviceInfo(device: AxeConnectedDevice): TrailblazeDeviceInfo = TrailblazeDeviceInfo(
    trailblazeDeviceId = device.trailblazeDeviceId,
    trailblazeDriverType = TrailblazeDriverType.IOS_AXE,
    widthPixels = device.deviceWidth,
    heightPixels = device.deviceHeight,
  )

  private fun axeSession(): TrailblazeSession =
    TrailblazeSession(sessionId = SessionId("axe"), startTime = Clock.System.now())

  private fun noOpTrailblazeLogger(): TrailblazeLogger =
    TrailblazeLogger(logEmitter = NoOpLogEmitter, screenStateLogger = ScreenStateLogger { "" })

  private suspend fun executeHostLocalPlaywrightTool(
    tool: HostLocalExecutableTrailblazeTool,
    toolRepo: TrailblazeToolRepo,
    deviceId: TrailblazeDeviceId,
    traceId: TraceId?,
  ): String {
    val test = trailblazeDeviceManager.getActivePlaywrightNativeTest(deviceId)
      ?: error("Playwright browser is not ready. Connect WEB first and wait for the browser to finish initializing.")
    val driverType = getDriverType() ?: TrailblazeDriverType.PLAYWRIGHT_NATIVE
    val deviceInfo = TrailblazeDeviceInfo(
      trailblazeDeviceId = deviceId,
      trailblazeDriverType = driverType,
      widthPixels = PlaywrightBrowserManager.DEFAULT_VIEWPORT_WIDTH,
      heightPixels = PlaywrightBrowserManager.DEFAULT_VIEWPORT_HEIGHT,
    )
    val syntheticSession = TrailblazeSession(
      sessionId = getActiveSessionId() ?: SessionId.sanitized("mcp_${deviceId.instanceId}"),
      startTime = Clock.System.now(),
    )
    val agent = PlaywrightTrailblazeAgent(
      browserManager = test.browserManager,
      trailblazeLogger = noOpTrailblazeLogger(),
      trailblazeDeviceInfoProvider = { deviceInfo },
      sessionProvider = { syntheticSession },
      trailblazeToolRepo = toolRepo,
      sessionDirProvider = logsRepo?.let { repo -> repo::getSessionDir },
    )
    // Honor "Capture Network Traffic" on the host-local MCP path. The test
    // doesn't go through runTrailblazeYamlSuspend here (each tool dispatches
    // individually with a synthetic session), so we start capture inline
    // using the same session id the agent will report. WebNetworkCapture.start
    // is idempotent, so calling it on every dispatch is safe — it short-
    // circuits when the existing capture matches the session.
    val resolvedLogsRepo = logsRepo
    if (test.config.captureNetworkTraffic && resolvedLogsRepo != null) {
      runCatching {
        WebNetworkCapture.start(
          ctx = test.browserManager.currentPage.context(),
          sessionId = syntheticSession.sessionId.value,
          sessionDir = resolvedLogsRepo.getSessionDir(syntheticSession.sessionId),
          tracker = agent.inflightRequestTracker,
        )
      }.onFailure {
        Console.log("MCP: Auto-start of web network capture failed: ${it.message}")
      }
    }
    val result = withContext(test.browserManager.playwrightDispatcher) {
      val screenState = test.browserManager.getScreenState()
      agent.runTrailblazeTools(
        tools = listOf(tool),
        traceId = traceId,
        screenState = screenState,
        elementComparator = NoOpElementComparator,
        screenStateProvider = test.browserManager::getScreenState,
      ).result
    }
    return when (result) {
      is TrailblazeToolResult.Success ->
        "Executed ${tool.advertisedToolName} on device ${deviceId.instanceId}"
      is TrailblazeToolResult.Error ->
        error("Host-local tool execution failed: ${result.errorMessage}")
    }
  }

  /** One-shot guard — we only want the IOS_AXE session-logging warning printed once per daemon. */
  private val iosAxeSessionLoggingWarningEmitted = java.util.concurrent.atomic.AtomicBoolean(false)

  /**
   * Prints a visible warning the first time someone runs an IOS_AXE tool through this bridge,
   * so users who have `trailblaze session start` active don't get silently-empty session
   * directories. Goes to stderr (not `Console.log`) so it bypasses quiet-mode suppression.
   */
  private fun warnIosAxeSessionLoggingGapOnce() {
    if (iosAxeSessionLoggingWarningEmitted.compareAndSet(false, true)) {
      System.err.println(
        "⚠️  [IOS_AXE] Session logging is NOT wired on the AXE driver path — if you have " +
          "`trailblaze session start` active, tool-call steps executed on IOS_AXE will NOT " +
          "be captured in the session directory. Proper wire-up is planned as a follow-up.",
      )
    }
  }

  /**
   * Executes a tool on the on-device agent via direct RPC, waiting for completion.
   */
  private suspend fun executeToolViaRpc(
    tool: TrailblazeTool,
    trailblazeDeviceId: TrailblazeDeviceId,
    yaml: String,
    blocking: Boolean = false,
    traceId: TraceId? = null,
  ): String {
    val rpcClient = OnDeviceRpcClient(
      trailblazeDeviceId = trailblazeDeviceId,
      sendProgressMessage = { Console.log("[executeToolViaRpc] $it") },
    )

    return try {
      val sessionResolution = trailblazeDeviceManager.getOrCreateSessionResolution(
        trailblazeDeviceId = trailblazeDeviceId,
        forceNewSession = false,
        sessionIdPrefix = "yaml",
      )

      val driverType = getConfiguredDriverType(trailblazeDeviceId.trailblazeDevicePlatform)
      // Honor "Capture Network Traffic" on the host-driven Android RPC path. The MCP bridge
      // doesn't go through BasePlaywrightNativeTest's `ensureWebNetworkCaptureStarted()`, so we
      // wire an analog here. The actual capture lives in an external module behind
      // [AndroidNetworkCaptureActivator] (registered by a downstream desktop app at startup);
      // default distributions leave the registry empty and this block is a no-op.
      val captureNetworkTraffic =
        trailblazeDeviceManager.settingsRepo.serverStateFlow.value.appConfig.captureNetworkTraffic
      val resolvedLogsRepoForAndroid = logsRepo
      if (
        captureNetworkTraffic &&
          trailblazeDeviceId.trailblazeDevicePlatform == TrailblazeDevicePlatform.ANDROID &&
          resolvedLogsRepoForAndroid != null
      ) {
        val activator = AndroidNetworkCaptureRegistry.activator
        if (activator != null) {
          runCatching {
            activator.start(
              sessionId = sessionResolution.sessionId.value,
              sessionDir = resolvedLogsRepoForAndroid.getSessionDir(sessionResolution.sessionId),
              deviceId = trailblazeDeviceId,
              targetAppId = getCurrentAppTargetId(),
            )
          }
            .onFailure {
              Console.log("MCP: Auto-start of Android network capture failed: ${it.message}")
            }
        }
      }
      val request = RunYamlRequest(
        yaml = yaml,
        testName = "tool_${tool::class.simpleName}",
        trailFilePath = null,
        targetAppName = getCurrentAppTargetId(),
        useRecordedSteps = false,
        trailblazeLlmModel = trailblazeDeviceManager.currentTrailblazeLlmModelProvider(),
        trailblazeDeviceId = trailblazeDeviceId,
        referrer = TrailblazeReferrer.MCP,
        traceId = traceId,
        driverType = driverType,
        // Map the caller's `blocking` flag onto the protocol-level wait. With `blocking=true`
        // (every current caller), the on-device handler holds the HTTP response until the job
        // terminates — that's also the RunYamlRequest default. With `blocking=false`, fire-and-forget:
        // the device returns the new sessionId immediately and the caller can move on.
        awaitCompletion = blocking,
        config = TrailblazeConfig(
          overrideSessionId = sessionResolution.sessionId,
          // Emit start only when this call created the session. This preserves host-managed
          // MCP sessions (no duplicate start logs) while still initializing direct tool-first sessions.
          sendSessionStartLog = sessionResolution.isNewSession,
          sendSessionEndLog = false,
          // Propagate the toggle so on-device launch tools can do their own capture-aware
          // setup — they may need to seed debug SharedPrefs that survive the launch tool's own
          // clearAppData / clearState=true cycle, and the host can't reach into that window
          // from outside. Today nothing on-device reads this; the host bridge is the only
          // consumer. Wired here so the next iteration can flip launch-tool behavior off this
          // signal without another hop.
          captureNetworkTraffic = captureNetworkTraffic,
        ),
      )

      Console.log("[executeToolViaRpc] Sending ${tool::class.simpleName} to on-device agent")
      // With `awaitCompletion = blocking`, rpcCall returns once the on-device job has reached
      // its terminal state (when blocking) or as soon as the session is created (when not).
      // Either way, no host-side log polling is needed — a previous version awaited the
      // resulting TrailblazeToolLog here under blocking, but by the time that ran every
      // on-device log was already on disk and `skipExisting=true` filtered them all out,
      // burning a fixed 120s timeout on every tool call.
      when (val result: RpcResult<RunYamlResponse> = rpcClient.rpcCall(request)) {
        is RpcResult.Success -> {
          val response = result.data
          // The wire-level `RpcResult.Success` only tells us the device responded — the
          // body's `success` field is the actual on-device outcome. A previous version
          // of this branch returned "Executed …" unconditionally on wire success, which
          // silently masked on-device failures (e.g. the LLM-init crash on `provider=none`,
          // tool exceptions, run timeouts) as success. The other host paths
          // ([HostOnDeviceRpcTrailblazeAgent.toToolResult],
          // [HostAccessibilityRpcClient.execute]) correctly inspect `success` — match that.
          //
          // - `success == true`: terminal success when `awaitCompletion=true` (i.e.
          //   `blocking=true` here). Report executed.
          // - `success == false`: terminal failure. Surface the on-device error message
          //   so the daemon log and the caller see the real cause instead of a phantom OK.
          // - `success == null`: fire-and-forget (`awaitCompletion=false`). Run is ongoing;
          //   the session id is the handle the caller subscribes to for terminal state.
          when (response.success) {
            true -> {
              Console.log("[executeToolViaRpc] On-device execution complete: ${response.sessionId}")
              "Executed ${tool::class.simpleName} on device ${trailblazeDeviceId.instanceId} (session: ${response.sessionId})"
            }
            false -> {
              val message = response.errorMessage?.takeUnless { it.isBlank() }
                ?: "unknown on-device error"
              Console.log("[executeToolViaRpc] On-device execution failed: $message")
              error("On-device execution of ${tool::class.simpleName} failed: $message")
            }
            null -> {
              Console.log("[executeToolViaRpc] On-device execution started: ${response.sessionId}")
              "Executed ${tool::class.simpleName} on device ${trailblazeDeviceId.instanceId} (session: ${response.sessionId})"
            }
          }
        }
        is RpcResult.Failure -> {
          Console.log("[executeToolViaRpc] RPC failed: ${result.message}")
          error("On-device tool execution failed: ${result.message}")
        }
      }
    } finally {
      rpcClient.close()
    }
  }

  override suspend fun endSession(): Boolean {
    val deviceId = assertDeviceIsSelected()
    // Clear cached screen state, persistent device, and on-device agent status for this device
    cachedScreenStates.remove(deviceId.instanceId)
    onDeviceAgentReady.remove(deviceId.instanceId)
    closePersistentDevice(deviceId)
    val activeSessionId = trailblazeDeviceManager.getCurrentSessionIdForDevice(deviceId)
    val endedSessionId = trailblazeDeviceManager.endSessionForDevice(deviceId)
    // Tear down the Android network capture bridge if one was started for this session. We use
    // the active sessionId captured *before* endSessionForDevice clears it; without that, the
    // activator can't match the running bridge to a session and would leave the worker thread
    // and adb-forward port leaked until the JVM exits.
    if (activeSessionId != null) {
      AndroidNetworkCaptureRegistry.activator?.let { activator ->
        runCatching { activator.stop(activeSessionId.value) }
          .onFailure {
            Console.log("MCP: Android network capture stop failed: ${it.message}")
          }
      }
    }
    return endedSessionId != null
  }

  override fun getActiveSessionId(): SessionId? {
    val deviceId = getEffectiveDeviceId() ?: return null
    return trailblazeDeviceManager.getCurrentSessionIdForDevice(deviceId)
  }

  override suspend fun ensureSessionAndGetId(testName: String?): SessionId? {
    val deviceId = getEffectiveDeviceId() ?: return null
    // Use "yaml" prefix to match runYaml() - ensures we monitor the same session
    val sessionResolution = trailblazeDeviceManager.getOrCreateSessionResolution(
      trailblazeDeviceId = deviceId,
      forceNewSession = false,
      sessionIdPrefix = "yaml"
    )

    // If this is a new session, emit a TrailblazeSessionStatusChangeLog
    // This is required for the desktop app to recognize the session
    if (sessionResolution.isNewSession) {
      emitSessionStartedLog(
        sessionId = sessionResolution.sessionId,
        deviceId = deviceId,
        testName = testName,
      )
    }

    return sessionResolution.sessionId
  }

  /**
   * Emits a [TrailblazeLog.TrailblazeSessionStatusChangeLog] with [SessionStatus.Started]
   * to initialize an MCP-initiated session. This enables the desktop app to display the session.
   */
  private fun emitSessionStartedLog(
    sessionId: SessionId,
    deviceId: TrailblazeDeviceId,
    testName: String? = null,
  ) {
    val repo = logsRepo ?: return

    // Get device info from the device state
    val deviceState = trailblazeDeviceManager.getDeviceState(deviceId)
    val device = deviceState?.device
    val driverType = device?.trailblazeDriverType
      ?: getConfiguredDriverType(deviceId.trailblazeDevicePlatform)
      ?: TrailblazeDriverType.DEFAULT_ANDROID

    // Try to get real device dimensions from the Maestro driver
    // Prefer persistent device, then fall back to device manager's active driver
    val maestroDriver = (persistentDevices[deviceId.instanceId] as? MaestroConnectedDevice)?.getMaestroDriver()
      ?: trailblazeDeviceManager.getActiveDriverForDevice(deviceId)

    // Get device dimensions from the driver if available, otherwise use defaults
    val (widthPixels, heightPixels) = if (maestroDriver != null) {
      try {
        val maestroDeviceInfo = maestroDriver.deviceInfo()
        maestroDeviceInfo.widthPixels to maestroDeviceInfo.heightPixels
      } catch (e: Exception) {
        Console.log("[MCP Bridge] Failed to get device info: ${e.message}, using defaults")
        DEFAULT_DEVICE_WIDTH to DEFAULT_DEVICE_HEIGHT
      }
    } else {
      DEFAULT_DEVICE_WIDTH to DEFAULT_DEVICE_HEIGHT // Actual dimensions captured in subsequent screen state logs
    }

    // Compute device classifiers using TrailblazeHostDeviceClassifier
    val classifiers = if (maestroDriver != null) {
      try {
        TrailblazeHostDeviceClassifier(
          trailblazeDriverType = driverType,
          maestroDeviceInfoProvider = { maestroDriver.deviceInfo() },
        ).getDeviceClassifiers()
      } catch (e: Exception) {
        Console.log("[MCP Bridge] Failed to compute classifiers: ${e.message}")
        emptyList()
      }
    } else {
      emptyList()
    }

    val deviceInfo = TrailblazeDeviceInfo(
      trailblazeDeviceId = deviceId,
      trailblazeDriverType = driverType,
      widthPixels = widthPixels,
      heightPixels = heightPixels,
      classifiers = classifiers,
    )

    val sessionStartedStatus = SessionStatus.Started(
      trailConfig = null, // MCP sessions don't have a trail config
      trailFilePath = null,
      hasRecordedSteps = false,
      testMethodName = testName ?: "mcp_session",
      testClassName = "MCP",
      trailblazeDeviceInfo = deviceInfo,
      trailblazeDeviceId = deviceId,
      rawYaml = null,
    )

    val log = TrailblazeLog.TrailblazeSessionStatusChangeLog(
      sessionStatus = sessionStartedStatus,
      session = sessionId,
      timestamp = Clock.System.now(),
    )

    repo.saveLogToDisk(log)
    Console.log("[MCP Bridge] Emitted TrailblazeSessionStatusChangeLog for new session: $sessionId")
  }

  override fun cancelAutomation(deviceId: TrailblazeDeviceId) {
    Console.log("MCP Bridge: Cancelling automation on device ${deviceId.instanceId}")
    trailblazeDeviceManager.cancelSessionForDevice(deviceId)
  }

  override suspend fun runYamlBlocking(
    yaml: String,
    objectives: List<String>,
    onProgress: (String) -> Unit,
    timeoutPerObjective: Duration,
    agentImplementation: AgentImplementation,
  ): RunYamlBlockingResult {
    val repo = logsRepo ?: return RunYamlBlockingResult.NotImplemented

    try {
      // Get/create session for this execution
      val sessionId = ensureSessionAndGetId()
        ?: return RunYamlBlockingResult.Error("No device selected")

      onProgress("[session] Using session: $sessionId")

      // Start YAML execution (fires background coroutine)
      runYaml(yaml, startNewSession = false, agentImplementation = agentImplementation)

      // Wait for each objective to complete
      for (objective in objectives) {
        onProgress("[objective] Waiting for: $objective")

        val completeLog = repo.awaitLog<TrailblazeLog.ObjectiveCompleteLog>(
          sessionId = sessionId,
          timeout = timeoutPerObjective,
          skipExisting = false,
        ) { it.promptStep.prompt == objective }

        if (completeLog == null) {
          return RunYamlBlockingResult.Timeout(objective)
        }

        onProgress("[objective] Completed: $objective")
      }

      return RunYamlBlockingResult.Success(objectives.size)
    } catch (e: Exception) {
      return RunYamlBlockingResult.Error(e.message ?: e::class.simpleName ?: "Unknown error")
    }
  }

  override fun selectAppTarget(appTargetId: String): String? {
    val matchingTarget = trailblazeDeviceManager.availableAppTargets.findById(appTargetId)
      ?: return null

    val previousTarget = trailblazeDeviceManager.settingsRepo.getCurrentSelectedTargetApp()
    trailblazeDeviceManager.settingsRepo.targetAppSelected(matchingTarget)

    // When the app target changes and either the old or new target has a custom iOS driver,
    // release the iOS persistent device connection so it gets recreated with the correct
    // driver wrapper (e.g., a downstream app-specific iOS driver with a custom
    // contentDescriptor).
    if (previousTarget?.id != appTargetId) {
      val iosDriverChanged = previousTarget?.hasCustomIosDriver == true || matchingTarget.hasCustomIosDriver
      if (iosDriverChanged) {
        // Always clear the HostIosDriverFactory singleton cache when iOS drivers change,
        // regardless of current device selection. A stale cached driver could be reused
        // later if an iOS device is selected after the app target switch.
        HostIosDriverFactory.clearCachedDriver()

        val deviceId = getEffectiveDeviceId()
        if (deviceId != null && deviceId.trailblazeDevicePlatform == TrailblazeDevicePlatform.IOS) {
          Console.log("[MCP Bridge] App target changed from ${previousTarget?.id} to $appTargetId (custom iOS driver involved) — releasing iOS persistent device for ${deviceId.instanceId}")
          releasePersistentDeviceConnection(deviceId)
        }
      }
    }

    return matchingTarget.displayName
  }

  override fun getCurrentAppTargetId(): String? {
    return trailblazeDeviceManager.settingsRepo.getCurrentSelectedTargetApp()?.id
  }

  override fun getConfiguredDriverType(platform: TrailblazeDevicePlatform): TrailblazeDriverType? {
    // WEB always maps to PLAYWRIGHT_NATIVE for MCP purposes. Even when WEB is stored in
    // selectedTrailblazeDriverTypes (e.g. via applyTestingEnvironment), returning it from
    // settings would make needsPersistentDriver=true (because requiresHost=true), which would
    // incorrectly trigger Maestro driver creation. Returning PLAYWRIGHT_NATIVE directly here
    // is a signal to skip persistent-device setup — WEB uses its own Playwright connection.
    if (platform == TrailblazeDevicePlatform.WEB) return TrailblazeDriverType.PLAYWRIGHT_NATIVE
    return trailblazeDeviceManager.settingsRepo.serverStateFlow.value
      .appConfig.selectedTrailblazeDriverTypes[platform]
  }

  override fun selectDeviceForSession(deviceId: TrailblazeDeviceId) {
    McpDeviceContext.currentDeviceId.set(deviceId)
  }

  override fun setWebBrowserHeadless(instanceId: String, headless: Boolean) {
    trailblazeDeviceManager.webBrowserManager.setHeadlessPreference(instanceId, headless)
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // Configuration access (for config MCP tool)
  // ─────────────────────────────────────────────────────────────────────────────

  override fun setConfiguredDriverType(
    platform: TrailblazeDevicePlatform,
    driverType: TrailblazeDriverType,
  ): String? {
    trailblazeDeviceManager.settingsRepo.updateAppConfig { config ->
      config.copy(
        selectedTrailblazeDriverTypes = config.selectedTrailblazeDriverTypes + (platform to driverType),
      )
    }
    return null
  }

  override fun getLlmConfig(): Pair<String, String>? {
    val config = trailblazeDeviceManager.settingsRepo.serverStateFlow.value.appConfig
    return config.llmProvider to config.llmModel
  }

  override fun setLlmConfig(provider: String?, model: String?): String? {
    val isProviderNone = provider.equals("none", ignoreCase = true)
    val isModelNone = model.equals("none", ignoreCase = true)
    trailblazeDeviceManager.settingsRepo.updateAppConfig { config ->
      config.copy(
        llmProvider = when {
          isProviderNone -> "none"
          provider != null -> provider
          else -> config.llmProvider
        },
        llmModel = when {
          // Clearing provider also clears model
          isProviderNone || isModelNone -> "none"
          model != null -> model
          else -> config.llmModel
        },
      )
    }
    return null
  }

  override fun getAgentImplementation(): AgentImplementation {
    return trailblazeDeviceManager.settingsRepo.serverStateFlow.value.appConfig.agentImplementation
  }

  override fun setAgentImplementation(implementation: AgentImplementation): String? {
    trailblazeDeviceManager.settingsRepo.updateAppConfig { config ->
      config.copy(agentImplementation = implementation)
    }
    return null
  }

  private suspend fun assertDeviceIsSelected(requestedDeviceId: TrailblazeDeviceId? = null): TrailblazeDeviceId {
    // If a specific device is requested, validate and select it
    if (requestedDeviceId != null) {
      // Check if already selected
      if (getEffectiveDeviceId() == requestedDeviceId) {
        return requestedDeviceId
      }

      // Verify the device is available. WEB instances are virtual — any instance ID
      // is valid because the bridge provisions a Playwright browser on demand for IDs
      // it hasn't seen yet (e.g. `--device web/foo` from the CLI). Mobile/desktop
      // device IDs must already be in the discovered list.
      val isAvailable = trailblazeDeviceManager.getDeviceState(requestedDeviceId) != null
          || getAvailableDevices().any { it.trailblazeDeviceId == requestedDeviceId }
          || requestedDeviceId.trailblazeDevicePlatform == TrailblazeDevicePlatform.WEB

      if (!isAvailable) {
        error("Device $requestedDeviceId is not available.")
      }

      selectedDeviceId = requestedDeviceId
      return requestedDeviceId
    }

    // No specific device requested - use currently selected if available.
    // Capture in a local val to avoid TOCTOU race (another thread could null it between check and use).
    val currentDeviceId = getEffectiveDeviceId()
    if (currentDeviceId != null) {
      return currentDeviceId
    }

    // No device selected - pick the first available one
    val firstDevice = getAvailableDevices().firstOrNull()
      ?: error("No devices are connected, please connect a device to continue.")

    return firstDevice.trailblazeDeviceId.also {
      selectedDeviceId = it
    }
  }
}
