package xyz.block.trailblaze.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import xyz.block.trailblaze.cli.CliReportGenerator
import xyz.block.trailblaze.cli.CliRunDeviceResolution
import xyz.block.trailblaze.cli.CliRunDeviceResolver
import xyz.block.trailblaze.cli.DaemonClient
import xyz.block.trailblaze.cli.DaemonSettingsBridge
import xyz.block.trailblaze.cli.TrailblazeCli
import xyz.block.trailblaze.desktop.TrailblazeDesktopAppConfig
import xyz.block.trailblaze.devices.TrailDeviceSelector
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.host.yaml.DesktopYamlRunner
import xyz.block.trailblaze.llm.RunYamlRequest
import xyz.block.trailblaze.llm.TrailblazeReferrer
import xyz.block.trailblaze.mcp.AgentImplementation
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.logs.server.TrailblazeMcpServer
import xyz.block.trailblaze.logs.server.endpoints.CliRunRequest
import xyz.block.trailblaze.logs.server.endpoints.CliRunResponse
import xyz.block.trailblaze.model.DesktopAppRunYamlParams
import xyz.block.trailblaze.model.DeviceConnectionStatus
import xyz.block.trailblaze.model.TrailExecutionResult
import xyz.block.trailblaze.model.TrailblazeConfig
import xyz.block.trailblaze.model.findById
import xyz.block.trailblaze.ui.images.NetworkImageLoader
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.yaml.TrailblazeYaml
import xyz.block.trailblaze.yaml.createTrailblazeYaml
import java.io.File
import java.util.concurrent.CountDownLatch

/**
 * Central Interface for The Trailblaze Desktop App
 */
abstract class TrailblazeDesktopApp(
  protected val desktopAppConfig: TrailblazeDesktopAppConfig,
) {

  abstract val desktopYamlRunner: DesktopYamlRunner

  abstract val trailblazeMcpServer: TrailblazeMcpServer

  abstract val deviceManager: TrailblazeDeviceManager

  /**
   * Starts the desktop app (Compose UI / tray icon) and, unless [daemonAlreadyRunning], the
   * daemon HTTP server.
   *
   * @param daemonAlreadyRunning true when the daemon for this port is already running — owned by
   *   another process this instance attaches to, or started in-process before the UI (the
   *   `trailblaze mcp` legacy STDIO path). When false, this process must win the port bind or
   *   exit instead of lingering as a duplicate tray icon.
   */
  abstract fun startTrailblazeDesktopApp(headless: Boolean = false, daemonAlreadyRunning: Boolean = false)

  /**
   * Creates the CLI report generator used by `trailblaze run`.
   *
   * The base implementation uses [CliReportGenerator] backed by `trailblaze-report`.
   * Subclasses can override this to return a customized report generator.
   */
  open fun createCliReportGenerator(): CliReportGenerator = CliReportGenerator()

  /** Shortcut to the port manager owned by the settings repo. */
  val portManager: TrailblazePortManager
    get() = desktopAppConfig.trailblazeSettingsRepo.portManager

  /**
   * Applies CLI port overrides as **transient, in-memory** values.
   *
   * The overrides are NOT written to the settings file, so multiple
   * concurrent instances (each started with different `-p` flags or
   * `TRAILBLAZE_PORT` env vars) won't race on the shared config.
   */
  fun applyPortOverrides(httpPort: Int, httpsPort: Int) {
    portManager.setRuntimeOverrides(httpPort, httpsPort)
    // Update the global server URL used by NetworkImageLoader for screenshot loading
    NetworkImageLoader.currentServerBaseUrl = portManager.serverUrl
    // And the callback-endpoint URL subprocess MCP tools hit. Must mirror port-override changes
    // or subprocesses spawned after an override would see the pre-override URL.
    xyz.block.trailblaze.scripting.callback.JsScriptingCallbackBaseUrl.set(portManager.serverUrl)
  }

  /**
   * Ensures the MCP server is running.
   *
   * This is called automatically when operations that need the server are performed
   * (e.g., OAuth callbacks, log collection). If the server is already running
   * (either from this app instance or a daemon), this is a no-op.
   *
   * @return `true` if this call started a new server (this process owns the daemon),
   *   `false` if a daemon was already running (another process owns it).
   */
  open fun ensureServerRunning(): Boolean {
    val serverPort = portManager.httpPort
    val serverHttpsPort = portManager.httpsPort
    val daemon = DaemonClient(port = serverPort)
    if (daemon.isRunningBlocking()) {
      return false // Server already running — we don't own it
    }

    Console.log("Starting Trailblaze server...")
    installRunHandler()
    try {
      runBlocking {
        trailblazeMcpServer.startStreamableHttpMcpServer(
          port = serverPort,
          httpsPort = serverHttpsPort,
          wait = false,
          additionalRouteRegistration = {
            xyz.block.trailblaze.health.DeviceHealthEndpoint.register(routing = this)
          },
        )
      }
    } catch (e: Exception) {
      // Lost the bind race with another process between the isRunning check and the bind.
      // If a rival daemon answers, this is the documented "another process owns it" outcome.
      if (daemon.waitForDaemon(maxWaitMs = RIVAL_DAEMON_WAIT_MS)) {
        Console.log("Daemon already running on port $serverPort — attaching to it.")
        return false
      }
      throw e
    }
    // Advertise the live daemon URL to subprocess MCP plumbing so session spawns can
    // populate `_meta.trailblaze.baseUrl`. Set AFTER the server binds so the URL is
    // actually reachable when a consumer reads the holder.
    xyz.block.trailblaze.scripting.callback.JsScriptingCallbackBaseUrl.set(portManager.serverUrl)

    // Wait for server to be ready
    var attempts = 0
    while (!daemon.isRunningBlocking() && attempts < 30) {
      Thread.sleep(200)
      attempts++
    }

    if (daemon.isRunningBlocking()) {
      Console.log("Server started on port $serverPort")
      return true
    } else {
      Console.error("Warning: Server may not have started properly")
      return false
    }
  }

  /**
   * Installs the `/cli/run` handler on the MCP server so that CLI processes
   * can delegate trail execution to a running daemon.
   *
   * Called automatically by [ensureServerRunning] and [startTrailblazeDesktopApp].
   */
  fun installRunHandler() {
    trailblazeMcpServer.onRunRequest = { request, onProgress -> handleCliRunRequest(request, onProgress) }
    // CLI-via-daemon IPC fast path. Runs whitelisted subcommands
    // in-process on the daemon thread, eliminating the CLI's JVM cold start.
    // Offloaded to Dispatchers.IO because `executeForDaemon` runs picocli which
    // blocks on `runBlocking` inside the cli*WithDevice helpers — we must not park a Ktor
    // HTTP server thread on that.
    trailblazeMcpServer.onCliExecRequest = { request ->
      withContext(Dispatchers.IO) { TrailblazeCli.executeForDaemon(request) }
    }
    // Forwarded `config` subcommands need to mutate the daemon's in-memory
    // settings — not the on-disk file directly — so the daemon's auto-save
    // doesn't clobber the change with its (now-stale) cached copy.
    DaemonSettingsBridge.settingsRepo = desktopAppConfig.trailblazeSettingsRepo
  }

  /**
   * Handles a CLI run request by resolving device/LLM, executing the trail,
   * and returning the result synchronously.
   */
  private suspend fun handleCliRunRequest(request: CliRunRequest, onProgress: (String) -> Unit = {}): CliRunResponse = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    // Determine YAML content
    val yamlContent = request.yamlContent
      ?: request.runYamlRequest?.yaml
      ?: return@withContext CliRunResponse(success = false, error = "No YAML content provided")

    // Resolve templates up front so device selection and execution read the same trail content.
    // Unconditional: bare submissions (no trailFilePath) resolve against the daemon's environment;
    // CLI-delegated ones arrive pre-resolved so this is a no-op — see [resolveSubmittedTrailYaml].
    val resolvedYaml = resolveSubmittedTrailYaml(yamlContent, request.trailFilePath)

    // Resolve driver type from request or trail config
    val trailConfig = try {
      TrailblazeYaml().extractTrailConfig(resolvedYaml)
    } catch (_: Exception) {
      null
    }
    val driverString = request.driverType ?: request.runYamlRequest?.driverType?.name ?: trailConfig?.driver
    val trailDriverType = driverString?.let { TrailblazeDriverType.fromString(it) }

    // Resolve device
    // Fully-resolved mode hands this request object straight to the runner, so the resolved
    // YAML must be copied in — otherwise execution would read the original unresolved text
    // while config parsing above read the resolved one. Fully-resolved submissions are bare
    // submissions too (DaemonClient's RunYamlRequest overload — the CLI's file-run delegate
    // uses raw yamlContent mode instead and pre-resolves); for a caller that did pre-resolve,
    // the copy is byte-identical.
    val resolvedRunRequest = request.runYamlRequest?.copy(yaml = resolvedYaml)
    val targetDevice = if (resolvedRunRequest != null) {
      // Fully resolved mode — find the device matching the request's device ID
      val devices = deviceManager.loadDevicesSuspend(applyDriverFilter = false)
      devices.find { it.trailblazeDeviceId == resolvedRunRequest.trailblazeDeviceId }
        ?: devices.firstOrNull()
        ?: return@withContext CliRunResponse(success = false, error = "No devices connected")
    } else {
      // Version-aware read of the trail's declared platforms (v1 `platform:`/`driver:` AND
      // unified classifier slots) — so a unified web trail routes to the browser here the same
      // way the CLI routes it, instead of landing on whatever device happens to be listed first.
      val trailPlatforms = TrailDeviceSelector.supportedPlatformsForTrail(createTrailblazeYaml(), resolvedYaml)
      val devices = deviceManager.loadDevicesSuspend(applyDriverFilter = false)
      val requestedDeviceId = request.deviceId?.takeIf { it.isNotBlank() }
      when (
        val resolution = CliRunDeviceResolver.resolve(devices, requestedDeviceId, trailDriverType, trailPlatforms)
      ) {
        is CliRunDeviceResolution.Selected -> {
          // Trace the pick like the CLI's [device-select] lines — this default path is hit by
          // callers that did NOT pre-resolve a device (deferred CLI runs, MCP/HTTP clients), so
          // the daemon log is the only place "which device ran this and why" is answerable.
          Console.log(
            "[device-select] /cli/run: deviceId=${requestedDeviceId ?: "<none>"} " +
              "driver=${trailDriverType ?: "<none>"} trailPlatforms=$trailPlatforms → " +
              resolution.device.platform.toFullyQualifiedDeviceId(resolution.device.instanceId),
          )
          resolution.device
        }
        is CliRunDeviceResolution.MultipleDevices -> {
          // Same fail-loud contract as the CLI: never silently pick one of several devices.
          val specs = resolution.candidates.map { it.platform.toFullyQualifiedDeviceId(it.instanceId) }
          Console.log("[device-select] /cli/run: ambiguous among $specs; requiring an explicit device")
          return@withContext CliRunResponse(
            success = false,
            error = "Multiple devices connected: ${specs.joinToString(", ")}. " +
              "Specify which device to run on (e.g. --device ${specs.first()} from the CLI).",
          )
        }
        is CliRunDeviceResolution.NoMatch -> {
          Console.log("[device-select] /cli/run: no device (${resolution.reason})")
          return@withContext CliRunResponse(success = false, error = resolution.reason)
        }
      }
    }

    // Resolve LLM model
    val llmModel = resolvedRunRequest?.trailblazeLlmModel ?: run {
      if (request.llmProvider != null || request.llmModel != null) {
        desktopAppConfig.resolveLlmModel(request.llmProvider, request.llmModel)
      } else {
        try {
          desktopAppConfig.getCurrentLlmModel()
        } catch (e: Exception) {
          null
        }
      }
    } ?: return@withContext CliRunResponse(success = false, error = "No LLM configured")

    // Derive test name
    val testName = request.testName
      ?: resolvedRunRequest?.testName
      ?: request.trailFilePath?.let { File(it).parentFile?.name }
      ?: "daemon-run"

    // Three-way: caller can force replay (`true`), force AI (`false`), or send `null`
    // to defer to daemon-side auto-detect via `hasRecordedSteps`. CLI's
    // `--no-use-recorded-steps` sends `false` and MUST be honored here — an earlier
    // version used `||` and silently re-resolved false back to true via auto-detect.
    val effectiveUseRecordedSteps = request.useRecordedSteps
      ?: TrailblazeYaml().hasRecordedSteps(resolvedYaml)

    val agentImpl = request.agentImplementation?.let {
      try { AgentImplementation.valueOf(it.uppercase()) } catch (_: IllegalArgumentException) { null }
    } ?: AgentImplementation.DEFAULT

    // Honor the CLI's --self-heal override when provided; fall back to the daemon's
    // persisted `trailblaze config self-heal` setting; otherwise stay opt-out.
    val effectiveSelfHeal =
      request.selfHeal
        ?: xyz.block.trailblaze.cli.CliConfigHelper.readConfig()?.selfHealEnabled
        ?: false

    // Pin a SessionId per delegated trail run so the post-completion status check
    // can target THIS run's session. See SessionId.pinnedFor for the parallel-safety
    // rationale (a reproduction surfaced cross-attribution without it).
    val pinnedSessionId = SessionId.pinnedFor(testName)

    val runYamlRequest = resolvedRunRequest ?: RunYamlRequest(
      testName = testName,
      yaml = resolvedYaml,
      trailFilePath = request.trailFilePath,
      targetAppName = trailConfig?.target,
      useRecordedSteps = effectiveUseRecordedSteps,
      trailblazeDeviceId = targetDevice.trailblazeDeviceId,
      trailblazeLlmModel = llmModel,
      driverType = trailDriverType,
      config = TrailblazeConfig(
        browserHeadless = !request.showBrowser,
        selfHeal = effectiveSelfHeal,
        overrideSessionId = pinnedSessionId,
        // Honor an explicit CLI --capture-network / --no-capture-network override
        // (true/false). When the request leaves it unset (null), fall back to the
        // daemon's saved app config (the desktop "Capture Network Traffic" toggle),
        // so a CLI invocation behaves the same as a desktop-app run started by the
        // same user. Using ?: (not ||) keeps an explicit --no-capture-network from
        // being overridden by an app config that defaults capture on.
        captureNetworkTraffic = request.captureNetworkTraffic
          ?: deviceManager.settingsRepo.serverStateFlow.value.appConfig.captureNetworkTraffic,
        // Honor the desktop app's "agent execution location" toggle (Settings →
        // preferHostAgent) so a delegated CLI run replays the same way the desktop
        // app and CI do. Without this the model default (true = host-driven via RPC)
        // always won, ignoring a user who chose on-device execution.
        preferHostAgent = deviceManager.settingsRepo.serverStateFlow.value.appConfig.preferHostAgent,
      ),
      // Use "cli" referrer (not "cli-daemon") so DesktopYamlRunner's shared-scope set
      // matches and parallel CLI delegations don't cancel each other.
      referrer = TrailblazeReferrer(id = "cli", display = "CLI"),
      agentImplementation = agentImpl,
      maxLlmCalls = request.maxLlmCalls,
      initialMemorySeeds = request.initialMemorySeeds,
      initialMemorySensitiveSeeds = request.initialMemorySensitiveSeeds,
      initialArgs = request.initialArgs,
    )

    // Execute and wait for completion
    val completionLatch = CountDownLatch(1)
    var success = false
    var errorMessage: String? = null

    val logsRepo = deviceManager.logsRepo

    val params = DesktopAppRunYamlParams(
      forceStopTargetApp = request.forceStopTargetApp,
      runYamlRequest = runYamlRequest,
      // `config.target` first; else fall back to the workspace-resolved target (rungs 2-3)
      // anchored at the RUN CALLER's cwd, not this daemon's launch dir — so a daemon-dispatched
      // run targets the same app that `config get target` reports from that cwd. Forwarded via
      // CliRunRequest.callerWorkspaceDir; null (older CLI / non-CLI submission) keeps the
      // daemon-anchored behavior. Precedence + caller-cwd threading are unit-tested via
      // resolveDaemonRunTargetApp (CliRunTargetResolutionTest).
      targetTestApp = resolveDaemonRunTargetApp(
        configTarget = trailConfig?.target,
        callerWorkspaceDir = request.callerWorkspaceDir,
        findTargetById = { desktopAppConfig.availableAppTargets.findById(it) },
        resolveForCallerCwd = { deviceManager.getCurrentSelectedTargetAppForCallerCwd(it) },
      ),
      noLogging = request.noLogging,
      captureVideo = request.captureVideo,
      captureLogcat = request.captureLogcat,
      captureIosLogs = request.captureIosLogs,
      onProgressMessage = { message ->
        Console.info(message)
        onProgress(message)
      },
      onConnectionStatus = { status ->
        if (status is DeviceConnectionStatus.DeviceConnectionError.ConnectionFailure) {
          errorMessage = status.errorMessage
          completionLatch.countDown()
        }
      },
        additionalInstrumentationArgs = desktopAppConfig.additionalInstrumentationArgs(),
      onComplete = { result ->
        when (result) {
          is TrailExecutionResult.Success -> success = true
          is TrailExecutionResult.Failed -> errorMessage = result.errorMessage
          is TrailExecutionResult.Cancelled -> errorMessage = "Cancelled"
        }
        completionLatch.countDown()
      },
    )

    desktopYamlRunner.runYaml(params)
    completionLatch.await()

    // When --no-logging is active, no session files are written so there is nothing to poll.
    // Return the success/failure result from the latch directly.
    if (request.noLogging) {
      return@withContext CliRunResponse(
        success = success && errorMessage == null,
        error = errorMessage,
      )
    }

    // Wait for THIS run's session logs to appear and reach a terminal state.
    // For on-device instrumentation, the RPC returns before the trail finishes executing
    // on-device, so we poll until the pinned session reaches Ended status. Looking at
    // sibling sessions in the repo would block forever when one of them hits
    // MaxCallsLimit / Timeout while ours has already cleanly Ended.
    // Read disk-truth (getSessionInfoDirect), not the cached flow: this run's pinned session
    // has no active per-session watcher, so the cache never sees the on-disk Ended log.
    delay(3000)
    val maxWaitMs = 600_000L
    val pollIntervalMs = 500L
    val waitStart = System.currentTimeMillis()
    while (System.currentTimeMillis() - waitStart < maxWaitMs) {
      val sessionInfo = logsRepo.getSessionInfoDirect(pinnedSessionId)
      if (sessionInfo != null && sessionInfo.latestStatus is SessionStatus.Ended) break
      delay(pollIntervalMs)
    }
    // Short buffer after Ended for trailing files (screenshots, etc.)
    delay(3000)

    // Reconcile against the pinned session's on-disk status (source of truth for pass/fail).
    // Inspect ONLY the pinned session — sibling sessions belong to parallel trail runs and have
    // no bearing on this one's success. A session that ended Succeeded must not be demoted by a
    // post-run connect/teardown ConnectionFailure (the V1 on-device-RPC dead-server case).
    val pinnedSessionInfo = logsRepo.getSessionInfoDirect(pinnedSessionId)
    val reconciled = reconcileRunOutcome(
      latchSuccess = success,
      latchError = errorMessage,
      diskStatus = pinnedSessionInfo?.latestStatus,
      sessionDescription = pinnedSessionId.toString(),
    )
    success = reconciled.success
    errorMessage = reconciled.error

    // Recording generation for on-device runs is handled by the CLI after
    // receiving the response, to avoid blocking the HTTP server thread.
    // The CLI calls generateRecordingForSession() which polls for trailing
    // log files (TrailblazeToolLog) that arrive after session Ended status.

    // Extract device classifiers from the pinned session for recording filename
    val classifiers = pinnedSessionInfo?.trailblazeDeviceInfo?.classifiers?.map { it.classifier }
      ?: emptyList()

    CliRunResponse(
      success = success,
      sessionId = pinnedSessionId.value,
      error = errorMessage,
      deviceClassifiers = classifiers,
    )
  }

}
