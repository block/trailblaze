package xyz.block.trailblaze.host

import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.agent.DefaultProgressReporter
import xyz.block.trailblaze.agent.InnerLoopScreenAnalyzer
import xyz.block.trailblaze.agent.MultiAgentV3Runner
import xyz.block.trailblaze.agent.TrailConfig
import xyz.block.trailblaze.agent.TrailblazeElementComparator
import xyz.block.trailblaze.agent.TrailblazeRunner
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.TrailblazeAgent
import xyz.block.trailblaze.BaseTrailblazeAgent
import xyz.block.trailblaze.KoogRunnableAgent
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.exception.TrailblazeException
import xyz.block.trailblaze.exception.TrailblazeSessionCancelledException
import xyz.block.trailblaze.host.golden.SnapshotBaselineSource
import xyz.block.trailblaze.host.golden.SnapshotGoldenComparison
import xyz.block.trailblaze.host.ios.MobileDeviceUtils
import xyz.block.trailblaze.host.driver.HostDriverDescriptorRegistry
import xyz.block.trailblaze.host.driver.HostRunDeps
import xyz.block.trailblaze.host.rules.BaseHostTrailblazeTest
import xyz.block.trailblaze.host.rules.HostTrailblazeLoggingRule
import xyz.block.trailblaze.host.yaml.MultiDeviceTargetBinding
import xyz.block.trailblaze.host.yaml.RunOnHostParams
import xyz.block.trailblaze.http.DynamicLlmClient
import xyz.block.trailblaze.llm.RunYamlRequest
import xyz.block.trailblaze.llm.TrailblazeReferrer
import xyz.block.trailblaze.playwright.PlaywrightPageManager
import xyz.block.trailblaze.playwright.PlaywrightTrailblazeAgent
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.mcp.AgentImplementation
import xyz.block.trailblaze.mcp.agent.KoogTestAgentRunner
import xyz.block.trailblaze.api.TestAgentRunner
import xyz.block.trailblaze.mcp.android.ondevice.rpc.GetScreenStateRequest
import xyz.block.trailblaze.mcp.android.ondevice.rpc.OnDeviceRpcClient
import xyz.block.trailblaze.mcp.android.ondevice.rpc.RpcResult
import xyz.block.trailblaze.cli.CliConfigHelper
import xyz.block.trailblaze.host.devices.HostDeviceProfile
import xyz.block.trailblaze.host.devices.HostProbedDeviceClassifiers
import xyz.block.trailblaze.mcp.sampling.LocalLlmSamplingSource
import xyz.block.trailblaze.model.TrailblazeConfig
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.model.toSessionToolRepo
import xyz.block.trailblaze.playwright.tools.WebToolSetIds
import xyz.block.trailblaze.report.utils.TrailblazeYamlSessionRecording.generateUnifiedRecordedYaml
import xyz.block.trailblaze.yaml.toRecordingTrailConfig
import xyz.block.trailblaze.rules.TrailblazeRunnerUtil
import xyz.block.trailblaze.scripting.HostScriptedToolLauncher
import xyz.block.trailblaze.scripting.LaunchedScriptingRuntime
import xyz.block.trailblaze.toolcalls.ResolvedAgentToolbox
import xyz.block.trailblaze.toolcalls.SessionDeviceBindings
import xyz.block.trailblaze.toolcalls.renderMultiDevicePromptSection
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.isSuccess
import xyz.block.trailblaze.utils.ElementComparator
import xyz.block.trailblaze.toolcalls.TrailblazeKoogTool.Companion.toTrailblazeToolDescriptor
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.toolcalls.commands.SwitchDeviceTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.TrailblazeToolSet
import xyz.block.trailblaze.toolcalls.ResolvedToolSet
import xyz.block.trailblaze.toolcalls.TrailblazeToolSetCatalog
import xyz.block.trailblaze.tracing.TrailblazeTraceExporter
import xyz.block.trailblaze.ui.TrailblazeDeviceManager
import xyz.block.trailblaze.recordings.TrailRecordings
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.util.HostAndroidDeviceConnectUtils
import xyz.block.trailblaze.yaml.TrailArgBinder
import xyz.block.trailblaze.yaml.TrailYamlItem
import xyz.block.trailblaze.yaml.createTrailblazeYaml
import xyz.block.trailblaze.report.otel.SessionOtelExport
import xyz.block.trailblaze.report.trace.SessionTraceFile

object TrailblazeHostYamlRunner {

  init {
    // Install the analyzer-backed scripted-tool enrichment so the host-side `assertWaypoint`
    // tool can resolve the waypoint registry even in workspaces that carry meta-only
    // scripted-tool descriptors (whose trailmap loading would otherwise throw without
    // enrichment). The interface lives in trailblaze-common; the analyzer that builds it is
    // host-only, so this is the host's one-time install point. Mirrors what the retired
    // `resolveWaypointsForRun` did inline.
    xyz.block.trailblaze.waypoint.WaypointRegistryResolver.scriptedToolEnrichmentProvider = {
      xyz.block.trailblaze.scripting.AnalyzerScriptedToolEnrichment.resolveFromEnvironment()
    }
    // Point waypoint resolution at the SAME active-workspace dir that target/tool discovery uses
    // (`WorkspaceConfigDirHolder`), so a workspace selected in the desktop app / Trail Runner — which
    // installs it without changing the JVM cwd — resolves app waypoints too. The holder lives in
    // trailblaze-models (JVM-only); this host module bridges it into the common resolver. Ensure the
    // holder's own default delegation is installed first (idempotent).
    xyz.block.trailblaze.config.project.TrailblazeWorkspaceConfigBootstrap.ensureInstalled()
    xyz.block.trailblaze.waypoint.WaypointRegistryResolver.workspaceConfigDirProvider = {
      xyz.block.trailblaze.llm.config.WorkspaceConfigDirHolder.resolver()
    }
  }

  /**
   * Exports trace data after a session ends. Tries posting to the server first;
   * falls back to writing directly to the session logs directory on disk.
   *
   * Wrapped in [NonCancellable] so traces are saved even when the coroutine is
   * cancelled (e.g. trail timeout). Without this, the suspend call inside `finally`
   * would throw [CancellationException] and the trace would be silently lost.
   */
  private suspend fun exportAndSaveTrace(
    sessionId: SessionId,
    loggingRule: HostTrailblazeLoggingRule,
    noLogging: Boolean = false,
  ) {
    if (noLogging) return
    withContext(kotlinx.coroutines.NonCancellable) {
      TrailblazeTraceExporter.exportAndSave(
        sessionId = sessionId,
        client = loggingRule.trailblazeLogServerClient,
        isServerAvailable = true, // Host runner always has a server running
        writeToDisk = { traceJson ->
          // The rule's own repo, not a re-derived `<git root>/logs`: this fallback writes the
          // trace for a session whose logs the rule already put in its configured directory, so
          // deriving a second location here filed trace.json away from its own session.
          val sessionDir = loggingRule.logsRepo.getSessionDir(sessionId)
          sessionDir.mkdirs()
          SessionTraceFile.merge(File(sessionDir, SessionTraceFile.FILE_NAME), traceJson)
          SessionOtelExport.pushIfConfigured(sessionId.value, traceJson)
        },
      )
    }
  }

  /**
   * Device-filtered app id for [resolved]: the first of its target's declared candidates that is
   * actually installed on [ResolvedTarget.deviceId]. Null when none is (or when the probe fails),
   * so handlers fall back to `ctx.target?.appIds[0]` and the launch fails downstream with a
   * clearer message than an exception thrown here would give.
   *
   * Per RESOLVED TARGET, not per session: a multi-device session probes each bound device against
   * the target THAT device runs, so two displays running different apps each resolve their own.
   */
  private fun resolveInstalledAppId(resolved: xyz.block.trailblaze.model.ResolvedTarget): String? =
    runCatching {
      val installed = xyz.block.trailblaze.host.ios.MobileDeviceUtils.getInstalledAppIds(resolved.deviceId)
      // The .onFailure log below catches throws from `getAppIdIfInstalled` (and from
      // `getInstalledAppIds` itself on iOS), but Android's `AndroidHostAdbUtils.listInstalledPackages`
      // catches Exception and returns `emptyList()` — so an adb timeout, dead device, or any other
      // shell-out failure on Android surfaces here as "0 packages installed" with no throw to log.
      // Detect that distinguishable case (empty installed set despite the target declaring app-id
      // candidates) and log it explicitly so operators debugging "ctx.target.resolveAppId returned
      // undefined" on Android get the same signal that a throw would have produced.
      val candidates = resolved.target.getPossibleAppIdsForPlatform(resolved.platform).orEmpty()
      if (installed.isEmpty() && candidates.isNotEmpty()) {
        Console.log(
          "[TrailblazeHostYamlRunner] getInstalledAppIds returned 0 packages for " +
            "${resolved.deviceId} despite target declaring ${candidates.size} candidate(s) " +
            "[${candidates.joinToString()}] — likely a silent adb failure swallowed by " +
            "AndroidHostAdbUtils.listInstalledPackages. appId will be null.",
        )
      }
      resolved.target.getAppIdIfInstalled(resolved.platform, installed)
    }.onFailure { e ->
      // Soft-fail (caller falls back to `ctx.target?.appIds[0]`) but log the underlying
      // reason — otherwise operators debugging "ctx.target.resolveAppId returned undefined"
      // have no signal whether the cause is a missing target or a device disconnect
      // mid-call. NOTE: this branch does NOT fire for Android adb errors because
      // `listInstalledPackages` swallows them upstream — see the empty-list check above
      // for the Android coverage.
      Console.log(
        "[TrailblazeHostYamlRunner] getInstalledAppIds resolution failed for " +
          "${resolved.deviceId}: ${e::class.simpleName}: ${e.message}",
      )
    }.getOrNull()

  /**
   * Spawn target-declared subprocess MCP servers (if any) and register their tools into the
   * session's [TrailblazeToolRepo]. Shared across every runner path so subprocess MCP is wired
   * universally — one spawn model, one stderr-file convention, one teardown shape, independent
   * of driver. Must be called from inside [executeTrailSession]'s session lambda since it
   * needs the live [SessionId].
   *
   * Returns `null` when the target declares no launchable (`script:`) entries — callers can
   * skip appending to their cleanup-tracking list in that case (an empty list in the cleanup
   * lambda is already a no-op, so the return-value check is just an optimization + a hook for
   * progress-message suppression).
   *
   * Cleanup is the caller's responsibility: wrap `runtime.shutdownAll()` in the cleanup lambda
   * inside `withContext(NonCancellable)` so teardown completes even when the surrounding
   * coroutine is cancelled (trail timeout, user abort) — otherwise subprocess + stderr-file
   * handles leak.
   */
  internal suspend fun launchSubprocessMcpServersIfAny(
    targetTestApp: TrailblazeHostAppTarget?,
    config: TrailblazeConfig,
    sessionId: SessionId,
    deviceInfo: TrailblazeDeviceInfo,
    logsRepo: xyz.block.trailblaze.report.utils.LogsRepo,
    toolRepo: TrailblazeToolRepo,
    /** Extra session drivers beyond [deviceInfo]'s — see [HostScriptedToolLauncher.launch]. */
    additionalDriverTypes: Set<TrailblazeDriverType> = emptySet(),
    onProgressMessage: (String) -> Unit,
  ): LaunchedScriptingRuntime? = HostScriptedToolLauncher.launch(
    targetTestApp = targetTestApp,
    config = config,
    sessionId = sessionId,
    deviceInfo = deviceInfo,
    logsRepo = logsRepo,
    toolRepo = toolRepo,
    classLoader = javaClass.classLoader,
    logPrefix = "[TrailblazeHostYamlRunner]",
    additionalDriverTypes = additionalDriverTypes,
    onProgressMessage = onProgressMessage,
  )

  /**
   * Common session lifecycle wrapper for trail execution.
   *
   * Handles session creation, standardized exception handling (cancellation,
   * failure screenshots, session end on error), trace export, and cleanup.
   * Each driver-specific method handles its own setup and passes execution
   * logic via [execute], eliminating duplicated try-catch-finally blocks.
   */
  internal suspend fun executeTrailSession(
    loggingRule: HostTrailblazeLoggingRule,
    overrideSessionId: SessionId?,
    testName: String,
    deviceLabel: String,
    sendSessionEndLog: Boolean,
    onProgressMessage: (String) -> Unit,
    screenshotProvider: () -> ScreenState,
    noLogging: Boolean = false,
    cleanup: suspend () -> Unit = {},
    execute: suspend (TrailblazeSession) -> SessionId?,
  ): SessionId? {
    val sessionManager = loggingRule.sessionManager
    val session = if (overrideSessionId != null) {
      sessionManager.createSessionWithId(overrideSessionId)
    } else {
      sessionManager.startSession(testName)
    }
    loggingRule.setSession(session)

    // A new session is a new recording. The JUnit path resets in `beforeTestExecution`; this path —
    // the CLI and the daemon — had no equivalent, and relied on the trace exporter clearing after it
    // wrote. That coupled "flushed" to "finished", so a mid-run flush renamed the trace. Resetting
    // here is the boundary that actually exists: without it a long-lived daemon files every run it
    // ever serves under one trace id. Conditional, because the recorder is process-wide and this
    // daemon runs trails concurrently — see [HostRunTraceRecording].
    if (!HostRunTraceRecording.begin()) {
      Console.log(
        "📊 Another run is already recording — $deviceLabel shares its trace. " +
          "Its spans land in both sessions' trace.json.",
      )
    }

    var executionFailure: Throwable? = null
    return try {
      execute(session)
    } catch (e: TrailblazeSessionCancelledException) {
      executionFailure = e
      Console.log("🚫 Session cancelled for $deviceLabel")
      onProgressMessage("Test session cancelled")
      // Re-throw so DesktopYamlRunner.runYaml sees the cancel rather than a
      // silent null return that the outer layer would interpret as Success.
      // TrailblazeSessionCancelledException extends Exception (not
      // CancellationException), so DesktopYamlRunner catches it explicitly
      // and sets TrailExecutionResult.Cancelled.
      throw e
    } catch (e: CancellationException) {
      executionFailure = e
      Console.log("🚫 Coroutine cancelled for $deviceLabel: ${e.message}")
      onProgressMessage("Test execution cancelled")
      throw e
    } catch (e: Exception) {
      executionFailure = e
      Console.log("❌ ${e::class.simpleName} in $deviceLabel: ${e.message}")
      onProgressMessage("Test execution failed: ${e.message}")
      loggingRule.captureFailureScreenshot(session, screenshotProvider)
      if (sendSessionEndLog) {
        loggingRule.endSession(session, isSuccess = false, exception = e)
      }
      // Re-throw so the failure propagates to DesktopYamlRunner.runYaml's outer catch,
      // which sets executionResult = Failed. Returning null here was the silent-failure
      // bug uncovered while debugging a cached-LLM-model issue: a thrown
      // IllegalStateException inside a Playwright run got swallowed here, the runner
      // saw null and reported Success up the stack, and MCP told the user "✓ Done"
      // while the page was actually blank.
      throw e
    } catch (t: Throwable) {
      executionFailure = t
      throw t
    } finally {
      exportAndSaveTrace(session.sessionId, loggingRule, noLogging = noLogging)
      // After the export, so the count still reflects this run while its spans are being drained.
      HostRunTraceRecording.end()
      loggingRule.setSession(null)
      try {
        cleanup()
      } catch (cleanupFailure: Throwable) {
        val primaryFailure = executionFailure
        if (primaryFailure == null) {
          throw cleanupFailure
        }
        if (primaryFailure !== cleanupFailure) primaryFailure.addSuppressed(cleanupFailure)
        Console.log(
          "Cleanup also failed for $deviceLabel: ${cleanupFailure.message}; preserving the trail failure"
        )
      }
    }
  }

  /**
   * Runs a Trailblaze YAML test on a specific host-connected device with the given LLM client.
   *
   * Returns a [HostYamlRunResult] so the local-device Maestro path (iOS_HOST / Android HOST) can
   * thread the last successful tool's result up to `DesktopYamlRunner` — that's what lets
   * `trailblaze tool <read-tool>` show the tool's real return value. The descriptor-backed
   * drivers (web / Compose / Revyl) carry a null `lastToolResult`: they surface their payloads to
   * the CLI through their own dispatch branches, not this one, so wrapping their session id is
   * enough.
   *
   * [logsDir] is a JVM-side parameter rather than a [RunOnHostParams] field because that class is
   * `commonMain` and can't carry a [File]. Every host path needs it: each builds its own
   * [HostTrailblazeLoggingRule], whose own resolution lands on `<git root>/logs` regardless of the
   * `logsDirectory` setting, and then reads that same directory back to generate the recording and
   * compare snapshot goldens. Null keeps that fallback.
   */
  suspend fun runHostYaml(
    dynamicLlmClient: DynamicLlmClient,
    runOnHostParams: RunOnHostParams,
    deviceManager: TrailblazeDeviceManager,
    logsDir: File? = null,
  ): HostYamlRunResult {
    val driverType = runOnHostParams.trailblazeDriverType

    // Drivers that have been converted run through their descriptor; the Maestro path below
    // carries the ones still waiting their turn. See HostDriverDescriptorRegistry.
    deviceManager.hostDriverDescriptors.forDriverOrNull(driverType)?.let { descriptor ->
      return descriptor.runYaml(
        deps = HostRunDeps(
          dynamicLlmClient = dynamicLlmClient,
          deviceManager = deviceManager,
          logsDir = logsDir,
        ),
        params = runOnHostParams,
      )
    }

    // A converted driver has no path below, so the Maestro fallback would run it on the wrong
    // driver rather than reporting that nothing is plugged in. `forDriver` names the driver and
    // the remedy.
    if (driverType in HostDriverDescriptorRegistry.convertedDriverTypes) {
      deviceManager.hostDriverDescriptors.forDriver(driverType)
    }

    return runMaestroHostYaml(dynamicLlmClient, runOnHostParams, deviceManager, logsDir)
  }

  /**
   * Original Maestro-based path for Android/iOS/web-playwright-host devices.
   *
   * Returns a [HostYamlRunResult] carrying the last successful tool's result so the local-device
   * Maestro path can surface a read tool's payload via `trailblaze tool` (iOS_HOST + Android HOST).
   */
  private suspend fun runMaestroHostYaml(
    dynamicLlmClient: DynamicLlmClient,
    runOnHostParams: RunOnHostParams,
    deviceManager: TrailblazeDeviceManager,
    logsDir: File?,
  ): HostYamlRunResult {

    val trailblazeDeviceId = runOnHostParams.runYamlRequest.trailblazeDeviceId
    val onProgressMessage = runOnHostParams.onProgressMessage

    // Skip force-stop for MCP requests - MCP maintains persistent connections
    // between tool calls and we don't want to kill the driver each time.
    val isMcpRequest = runOnHostParams.referrer == TrailblazeReferrer.MCP
    
    if (runOnHostParams.trailblazeDevicePlatform == TrailblazeDevicePlatform.ANDROID && !isMcpRequest) {
      HostAndroidDeviceConnectUtils.forceStopAllAndroidInstrumentationProcesses(
        trailblazeOnDeviceInstrumentationTargetTestApps = deviceManager.availableAppTargets
          // A declared in-process harness is an instrumentation process like the bundled
          // runner — a stale one blocks the host Maestro instrumentation just the same.
          .flatMap { it.allInstrumentationTargets() }
          .toSet(),
        deviceId = trailblazeDeviceId,
      )
    }

    onProgressMessage("Initializing $trailblazeDeviceId test runner...")

    val runYamlRequest = runOnHostParams.runYamlRequest

    val hostTbRunner = object : BaseHostTrailblazeTest(
      trailblazeDriverType = runOnHostParams.trailblazeDriverType,
      customToolClasses = runOnHostParams.targetTestApp
        ?.getCustomToolsForDriver(
          runOnHostParams.trailblazeDriverType,
        ) ?: emptySet(),
      excludedToolClasses = runOnHostParams.targetTestApp
        ?.getExcludedToolsForDriver(
          runOnHostParams.trailblazeDriverType,
        ) ?: emptySet(),
      dynamicLlmClient = dynamicLlmClient,
      trailblazeLlmModel = runYamlRequest.trailblazeLlmModel,
      config = runYamlRequest.config,
      appTarget = runOnHostParams.targetTestApp,
      explicitDeviceId = trailblazeDeviceId,
      logsDir = logsDir,
      noLogging = runOnHostParams.noLogging,
    ) {
      // Honor the agent implementation chosen for THIS run (CLI --agent / settings / request),
      // overriding BaseHostTrailblazeTest's JUnit-eval system-property default so
      // KOOG_STRATEGY_GRAPH takes effect on this local-device (Maestro) path — Android and iOS —
      // exactly like the web / Revyl / on-device paths. Default (TRAILBLAZE_RUNNER) is unchanged.
      override val agentImplementation: AgentImplementation = runYamlRequest.agentImplementation

      override fun ensureTargetAppIsStopped() {
        // Convert the YAML-ordered List to a Set for ensureAppsAreForceStopped, which takes
        // membership-style Set<String>.
        val possibleAppIds = runOnHostParams.targetTestApp
          ?.getPossibleAppIdsForPlatform(runOnHostParams.trailblazeDevicePlatform)
          ?.toSet()
          ?: emptySet()
        MobileDeviceUtils.ensureAppsAreForceStopped(
          possibleAppIds = possibleAppIds,
          trailblazeDeviceId = trailblazeDeviceId
        )
      }
    }

    // Store the test instance for forceful shutdown on cancellation. Host-native iOS drivers
    // have no Maestro driver — and dereferencing hostTbRunner.hostRunner would construct one —
    // so this is skipped for them.
    if (!runOnHostParams.trailblazeDriverType.hostNativeSimulatorDriver) {
      deviceManager.setActiveDriverForDevice(trailblazeDeviceId, hostTbRunner.hostRunner.loggingDriver)
    }

    onProgressMessage("Connecting to $trailblazeDeviceId device...")

    val keepDriverAlive = runOnHostParams.referrer == TrailblazeReferrer.MCP

    // Per-session subprocess MCP runtimes for inline scripted tools synthesized from the
    // target's `tools:` YAML. The launcher spawns each entry, runs the MCP handshake,
    // registers filtered tools into hostTbRunner.toolRepo, and hands back a teardown handle.
    // Launch must happen inside the executeTrailSession lambda — we need the SessionId for
    // the env-var contract and for the session-log directory; both are available there.
    //
    // Modeled as a mutable list of resources (empty when the target declares no `tools:`
    // with subprocess routing, populated once launch succeeds) so the cleanup lambda can
    // reference the collection directly without a forward-declared nullable var.
    val subprocessRuntimes = mutableListOf<LaunchedScriptingRuntime>()

    // Captured from inside the session lambda so it survives back out to the HostYamlRunResult
    // this method returns — executeTrailSession itself only hands back the SessionId.
    var lastToolResult: TrailblazeToolResult.Success? = null

    val sessionId = executeTrailSession(
      loggingRule = hostTbRunner.hostLoggingRule,
      overrideSessionId = runYamlRequest.config.overrideSessionId,
      testName = runYamlRequest.testName,
      deviceLabel = "maestro:${trailblazeDeviceId.instanceId}",
      sendSessionEndLog = runYamlRequest.config.sendSessionEndLog,
      onProgressMessage = onProgressMessage,
      screenshotProvider = hostTbRunner.screenStateProvider,
      noLogging = runOnHostParams.noLogging,
      cleanup = {
        // Shut down subprocess MCP servers before the driver goes away — they're tied to
        // this session's lifetime and every registration's sessionProvider closes over
        // them. Empty list when nothing was launched; no branch needed.
        //
        // Wrapped in `NonCancellable` so teardown completes even when the surrounding
        // coroutine is cancelled (trail timeout, user abort). Without this, cancellation
        // would prevent `session.shutdown()` from running and leak the subprocess +
        // stderr-capture file handle.
        withContext(NonCancellable) {
          subprocessRuntimes.forEach { it.shutdownAll() }
          // Detach the iOS baguette stream (no-op unless TRAILBLAZE_IOS_STREAM_SCREENSHOT
          // engaged) so the WebSocket + ffmpeg decoder don't outlive the session. The
          // if-started guard keeps cleanup from constructing the lazy hostRunner — which
          // deliberately throws on IOS_AXE and would otherwise fail every AXe run's cleanup.
          hostTbRunner.closeStreamScreenshotSourceIfStarted()
          // Let go of the device connection the classifiers fetched for themselves. Unconditional
          // because it is this run's own hold on the shared iOS driver and nothing else can reach
          // it — not even the MCP branch below, which deliberately keeps the driver alive by
          // holding the OTHER one, registered as the device's active driver.
          hostTbRunner.releaseConnectedDeviceIfOpened()
        }
        if (keepDriverAlive) {
          Console.log("🔗 MCP referrer detected - keeping driver alive for device: ${trailblazeDeviceId.instanceId}")
          deviceManager.clearCoroutineScopeForDevice(trailblazeDeviceId)
        } else {
          deviceManager.cancelSessionForDevice(trailblazeDeviceId)
        }
      },
    ) { session ->
      // Start session-scoped capture (e.g. the iOS Simulator log stream) the moment the
      // session exists, BEFORE any trail steps run. This Maestro host runner executes the
      // whole trail synchronously, so the daemon's post-run capture activation would otherwise
      // start capture only after the trail finished and record nothing. Guarded so a
      // capture-start failure never tears down the trail.
      runCatching { runOnHostParams.onSessionStarted(session.sessionId) }
        .onFailure {
          Console.log("[runMaestroHostYaml] onSessionStarted callback threw — continuing: ${it.message}")
        }
      // Spawn target-declared subprocess MCP servers + register their tools into the
      // session's repo. Runs before trail execution so the LLM's first tools/list reflects
      // the full registry. Fail-fast: if any spawn fails, executeTrailSession's catch path
      // reports it and the cleanup lambda tears down anything partial.
      launchSubprocessMcpServersIfAny(
        targetTestApp = runOnHostParams.targetTestApp,
        config = runYamlRequest.config,
        sessionId = session.sessionId,
        deviceInfo = hostTbRunner.trailblazeDeviceInfo,
        logsRepo = hostTbRunner.hostLoggingRule.logsRepo,
        toolRepo = hostTbRunner.toolRepo,
        onProgressMessage = onProgressMessage,
      )?.let { subprocessRuntimes += it }

      onProgressMessage("Executing YAML test...")
      Console.log("▶️ Starting runTrailblazeYamlSuspend for device: ${trailblazeDeviceId.instanceId}")
      val yamlRun = hostTbRunner.runTrailblazeYamlSuspend(
        yaml = runYamlRequest.yaml,
        forceStopApp = runOnHostParams.forceStopTargetApp,
        trailFilePath = runYamlRequest.trailFilePath,
        trailblazeDeviceId = trailblazeDeviceId,
        traceId = runYamlRequest.traceId,
        sendSessionStartLog = runYamlRequest.config.sendSessionStartLog,
        initialMemorySeeds = runYamlRequest.initialMemorySeeds,
        initialMemorySensitiveSeeds = runYamlRequest.initialMemorySensitiveSeeds,
        initialArgs = runYamlRequest.initialArgs,
      )
      // Surface the last successful tool's payload back out through HostYamlRunResult.
      lastToolResult = yamlRun.lastToolResult
      val sessionId = yamlRun.sessionId
      Console.log("✅ runTrailblazeYamlSuspend completed successfully for device: ${trailblazeDeviceId.instanceId}")
      onProgressMessage("Test execution completed successfully")

      if (runYamlRequest.config.sendSessionEndLog) {
        hostTbRunner.loggingRule.captureFinalScreenshot(session, hostTbRunner.screenStateProvider)
        hostTbRunner.loggingRule.endSession(session, isSuccess = true)
      }

      sessionId?.let {
        generateAndSaveRecording(
          sessionId = it,
          logsDir = hostTbRunner.hostLoggingRule.logsRepo.logsDir,
          customToolClasses = runOnHostParams.targetTestApp
            ?.getCustomToolsForDriver(runOnHostParams.trailblazeDriverType) ?: emptySet(),
        )
      }

      sessionId
    }
    return HostYamlRunResult(sessionId, lastToolResult)
  }

  /**
   * Runs MULTI_AGENT_V3 on the host, driving the on-device accessibility agent via
   * [OnDeviceRpcClient] one tool call at a time.
   *
   * Caller is responsible for instrumentation setup (install APK, start server,
   * enable accessibility service) before calling this function.
   *
   * @param dynamicLlmClient LLM client for screen analysis and planning
   * @param onDeviceRpc Already-connected RPC client to the on-device server
   * @param runYamlRequest The original run request (used for config, model, trail YAML)
   * @param trailblazeDeviceId The Android device being tested
   * @param onProgressMessage Callback for progress messages
   * @param targetTestApp Optional app target (provides custom tool classes)
   * @return The host session ID on completion. Throws [TrailblazeException] for
   *   trails with no executable steps (false-positive guard). Failures and
   *   cancellations also propagate as exceptions — this function does NOT swallow
   *   exceptions and return null. See [executeTrailSession] re-throw semantics.
   */
  /**
   * The directory host-local tools resolve trail-relative paths against — see
   * [xyz.block.trailblaze.MaestroTrailblazeAgent.workingDirectory]. One definition because every
   * agent this file builds must anchor identically: a companion resolving a path differently from
   * the primary would make the same trail-relative `hostPath` valid on one device and missing on
   * another, for no reason a reader could see.
   */
  internal fun RunYamlRequest.trailDirectory(): File? = trailFilePath?.let { File(it).parentFile }

  suspend fun runHostV3WithAccessibilityYaml(
    dynamicLlmClient: DynamicLlmClient,
    onDeviceRpc: OnDeviceRpcClient,
    runYamlRequest: RunYamlRequest,
    trailblazeDeviceId: TrailblazeDeviceId,
    onProgressMessage: (String) -> Unit,
    targetTestApp: TrailblazeHostAppTarget?,
    /**
     * Same contract as the on-device-RPC runner's callback — fired exactly once after the session
     * is established so callers can attach session-scoped infrastructure (e.g. the network capture
     * bridge). Defaulted to a no-op so existing callers stay compatible.
     */
    onSessionStarted: (SessionId) -> Unit = {},
    /** Directory this run's session logs are written to. See [runHostYaml]. */
    logsDir: File? = null,
    /** The run's `--no-logging` flag: no session files, no trace export. See [runHostYaml]. */
    noLogging: Boolean = false,
  ): SessionId? {
    val driverType = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY
    val customToolClasses = targetTestApp
      ?.getCustomToolsForDriver(driverType)
      ?: emptySet()

    val trailblazeYaml = createTrailblazeYaml(
      customTrailblazeToolClasses = customToolClasses,
    )

    // Query the device's own description of itself up-front so a v3 trail can be lowered with the
    // right closest-wins recording for THIS device. v1 trails ignore the list
    // (they have a single recording per step), so this re-ordering is a no-op
    // for the existing format.
    // Held in a reference, not a value: the device's size changes under the run (`setOrientation`
    // swaps the axes, and orientation is derived from them), so the executor below refreshes it
    // from every screen state this device reports.
    val deviceProfile = AtomicReference(queryDeviceProfile(onDeviceRpc))
    val classifiers = deviceProfile.get().classifiers.ifEmpty {
      HostProbedDeviceClassifiers.forDevice(trailblazeDeviceId)
    }

    // Decode trail YAML to extract prompt steps for V3. Envelope-tolerant so single-tool MCP
    // dispatch decodes via decodeTools rather than the legacy list-shape parser.
    val trailItems = try {
      trailblazeYaml.decodeTrailOrToolEnvelope(runYamlRequest.yaml, deviceClassifiers = classifiers)
    } catch (e: Exception) {
      Console.log("❌ Failed to decode V3 trail YAML: ${e::class.simpleName}: ${e.message}")
      onProgressMessage("Failed to decode trail YAML: ${e.message}")
      // Re-throw so DesktopYamlRunner.runYaml's outer catch sets executionResult = Failed.
      // Returning null was the silent-failure pattern previously fixed for executeTrailSession.
      throw e
    }
    val trailConfig = trailblazeYaml.extractTrailConfig(trailItems)
    val toolItems = trailItems.filterIsInstance<TrailYamlItem.ToolTrailItem>()
    // The trailhead (if any) lowers to the leading step 0, ahead of the trail's prompts.
    val trailheadSteps = trailItems
      .filterIsInstance<TrailYamlItem.TrailheadTrailItem>()
      .map { it.trailhead.toPromptStep() }
    val promptSteps = trailheadSteps + trailItems
      .filterIsInstance<TrailYamlItem.PromptsTrailItem>()
      .flatMap { it.promptSteps }

    if (promptSteps.isEmpty()) {
      throw TrailblazeException(
        "Trail has no executable prompt steps — this would be a false positive pass. " +
          "Add steps to this trail file or the source test case.",
      )
    }

    // Set up host-side logging (session start/end logs are emitted here, not on-device)
    val loggingRule = HostTrailblazeLoggingRule(
      trailblazeDeviceInfoProvider = {
        val profile = deviceProfile.get()
        TrailblazeDeviceInfo(
          trailblazeDeviceId = trailblazeDeviceId,
          trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
          widthPixels = profile.widthPixels,
          heightPixels = profile.heightPixels,
          classifiers = classifiers,
        )
      },
      logsDir = logsDir,
      noLogging = noLogging,
    )

    val llmClient = dynamicLlmClient.createLlmClient()
    val trailblazeLlmModel = runYamlRequest.trailblazeLlmModel

    val samplingSource = LocalLlmSamplingSource(
      llmClient = llmClient,
      llmModel = trailblazeLlmModel,
      logsRepo = loggingRule.logsRepo,
      sessionIdProvider = { loggingRule.session?.sessionId },
      saveAnnotatedScreenshotsProvider = {
        CliConfigHelper.readConfig()?.saveAnnotatedScreenshots ?: true
      },
    )

    val screenAnalyzer = InnerLoopScreenAnalyzer(
      samplingSource = samplingSource,
      model = trailblazeLlmModel,
    )

    // Same composer the on-device rules and the daemon use, so this target advertises the same
    // tools here as it does on device. Reads the target's `excluded_tools:` itself.
    val toolRepo = targetTestApp.toSessionToolRepo(driverType = driverType)

    // Single AgentMemory shared between host-local tool execution contexts and the RPC
    // client's per-tool arg interpolation, so values written by host-local tools are visible
    // to subsequent RPC dispatches. AgentMemory is backed by a ConcurrentHashMap, so this
    // sharing is safe even if tool execution is ever parallelized.
    //
    // Seeded once before any tool runs via the [AgentMemory.seedFrom] composition: YAML
    // `config.memory:` defaults first, then CLI `--memory KEY=VAL` overrides, then CLI
    // `--secret KEY=VAL` (routed through `rememberSensitive` and excluded from the
    // returned snapshot). Later tiers win on a same-key collision.
    val sharedAgentMemory = AgentMemory()
    val resolvedInitialMemory = sharedAgentMemory.seedFrom(
      yamlDefaults = trailConfig?.memory,
      cliSeeds = runYamlRequest.initialMemorySeeds,
      cliSensitiveSeeds = runYamlRequest.initialMemorySensitiveSeeds,
    )
    sharedAgentMemory.seedArgs(TrailArgBinder.decodeProvided(runYamlRequest.initialArgs))
    val sensitiveMemoryKeys: Set<String> = sharedAgentMemory.sensitiveKeys.toSet()
    // Pre-resolve the session's target once (#2699 — closes the deferred wiring note on
    // ResolvedTarget and surfaces ctx.target.{id, appIds, appId} to scripted tools).
    // `by lazy` keeps the cost off sessions that never invoke a target-aware tool, and means
    // a multi-tool session pays the device query (`pm list packages` / `simctl listapps`)
    // exactly once instead of per-dispatch. The V1 site at
    // `runHostTrailblazeRunnerWithOnDeviceRpc` resolves eagerly because its agent ctor takes
    // a plain `String?`, not a thunk — see the comment there for the divergence.
    val resolvedTargetForSession: xyz.block.trailblaze.model.ResolvedTarget? =
      targetTestApp?.let { target ->
        xyz.block.trailblaze.model.ResolvedTarget(target = target, deviceId = trailblazeDeviceId)
      }
    val appIdForSessionLazy = lazy {
      val resolved = resolvedTargetForSession ?: return@lazy null
      // Compose the non-throwing primitives directly so a target with zero installed
      // candidates surfaces as `appId = null` rather than a thrown
      // IllegalStateException at envelope-build time. The throwing wrapper
      // `MobileDeviceUtils.findInstalledAppIdForTarget` is for production launch flows that
      // need a hard error; here we want a soft signal so authors can fall back to
      // `ctx.target.appIds[0]` and let the launch fail downstream with a clearer message.
      runCatching {
        val installed = MobileDeviceUtils.getInstalledAppIds(resolved.deviceId)
        resolved.target.getAppIdIfInstalled(resolved.platform, installed)
      }.getOrNull()
    }
    // Forward-declared so the context provider's screen-state lambda can reach the executor's
    // capture once it's constructed (the lambda only runs at tool-dispatch time, well after this
    // assignment). Host-local verification tools like `assertWaypoint` poll the live screen
    // through this provider.
    var executorRef: HostAccessibilityRpcClient? = null
    val executor = HostAccessibilityRpcClient(
      rpcClient = onDeviceRpc,
      toolRepo = toolRepo,
      runYamlRequestTemplate = runYamlRequest,
      sessionProvider = { loggingRule.session ?: error("Session not available") },
      toolExecutionContextProvider = { traceId ->
        TrailblazeToolExecutionContext(
          screenState = null,
          traceId = traceId,
          trailblazeDeviceInfo = loggingRule.trailblazeDeviceInfoProvider(),
          sessionProvider = { loggingRule.session ?: error("Session not available") },
          // Mirrors the `screenshotProvider` lambda below — the context's screenStateProvider is
          // synchronous, so we bridge the suspend capture with runBlocking. Safe for the same
          // reason: it runs on the dispatch thread, not the trail's coroutine, and the RPC capture
          // completes on its own connection.
          screenStateProvider = {
            runBlocking { executorRef?.captureScreenState() }
              ?: error("No screen state available")
          },
          trailblazeLogger = loggingRule.logger,
          memory = sharedAgentMemory,
          resolvedTarget = resolvedTargetForSession,
          appId = appIdForSessionLazy.value,
          // The trail file's directory lets host-local tools resolve repo-relative files (e.g. a
          // committed account.json) against the trail on disk rather than the daemon's CWD/env,
          // which a persistent daemon doesn't share with the per-run trail-source clone.
          workingDirectory = runYamlRequest.trailDirectory(),
          // Host-side `requiresHost` tools (e.g. a capture-reading tool) resolve capture artifacts
          // under this session's on-host log dir.
          sessionDirProvider = loggingRule.logsRepo::getSessionDir,
        )
      },
      memory = sharedAgentMemory,
      onScreenStateObserved = { response ->
        deviceProfile.updateAndGet { it.withMeasuredSizeFrom(response) }
      },
    )
    executorRef = executor

    val subprocessRuntimes = mutableListOf<LaunchedScriptingRuntime>()
    return executeTrailSession(
      loggingRule = loggingRule,
      overrideSessionId = runYamlRequest.config.overrideSessionId,
      testName = runYamlRequest.testName,
      deviceLabel = "v3-accessibility:${trailblazeDeviceId.instanceId}",
      sendSessionEndLog = runYamlRequest.config.sendSessionEndLog,
      onProgressMessage = onProgressMessage,
      screenshotProvider = {
        runBlocking { executor.captureScreenState() } ?: error("No screen state available")
      },
      noLogging = noLogging,
      cleanup = {
        withContext(NonCancellable) {
          subprocessRuntimes.forEach { it.shutdownAll() }
        }
        executor.close()
      },
    ) { session ->
      launchSubprocessMcpServersIfAny(
        targetTestApp = targetTestApp,
        config = runYamlRequest.config,
        sessionId = session.sessionId,
        deviceInfo = loggingRule.trailblazeDeviceInfoProvider(),
        logsRepo = loggingRule.logsRepo,
        toolRepo = toolRepo,
        onProgressMessage = onProgressMessage,
      )?.let { subprocessRuntimes += it }
      if (runYamlRequest.config.sendSessionStartLog) {
        val deviceInfo = loggingRule.trailblazeDeviceInfoProvider()
        // See ComposeRpc site — derive a readable Suite::test identity from the path.
        val derivedTestIdentity = runYamlRequest.trailFilePath?.let {
          TrailRecordings.deriveTestIdentityFromTrailPath(it, fallbackClassName = "HostAccessibilityV3")
        }
        loggingRule.logger.log(
          session,
          TrailblazeLog.TrailblazeSessionStatusChangeLog(
            sessionStatus = SessionStatus.Started(
              trailConfig = trailConfig,
              trailFilePath = runYamlRequest.trailFilePath,
              testClassName = derivedTestIdentity?.className ?: "HostAccessibilityV3",
              testMethodName = derivedTestIdentity?.methodName ?: "run",
              trailblazeDeviceInfo = deviceInfo,
              rawYaml = runYamlRequest.yaml,
              hasRecordedSteps = trailblazeYaml.hasRecordedSteps(trailItems),
              trailblazeDeviceId = trailblazeDeviceId,
              resolvedInitialMemory = resolvedInitialMemory,
              sensitiveMemoryKeys = sensitiveMemoryKeys,
              // Reading the lazy here resolves the app id at session start (one `pm list
              // packages` + one `dumpsys package`), so the recording carries the build under
              // test. Later target-aware tool dispatches reuse the same resolved value.
              targetAppInfo = MobileDeviceUtils.resolveTargetAppInfo(
                target = targetTestApp,
                trailblazeDeviceId = trailblazeDeviceId,
                resolvedAppId = appIdForSessionLazy.value,
              ),
            ),
            session = session.sessionId,
            timestamp = Clock.System.now(),
          ),
        )
      }

      // Fire the session-started callback BEFORE the planner runs so any session-scoped
      // out-of-band infrastructure (network capture bridge, etc.) is up before the first tool.
      try {
        onSessionStarted(session.sessionId)
      } catch (t: Throwable) {
        Console.log(
          "[runHostV3WithAccessibilityYaml] onSessionStarted callback threw — continuing: " +
            "${t::class.java.simpleName}: ${t.message}"
        )
      }

      val progressListener = loggingRule.logger.createProgressListener(session)
      val progressReporter = DefaultProgressReporter(progressListener)
      val availableToolsProvider = {
        toolRepo.getCurrentToolDescriptors().map { it.toTrailblazeToolDescriptor() }
      }

      val v3Runner = MultiAgentV3Runner.create(
        screenAnalyzer = screenAnalyzer,
        executor = executor,
        progressReporter = progressReporter,
        deviceId = trailblazeDeviceId,
        availableToolsProvider = availableToolsProvider,
      )

      onProgressMessage("Starting V3 runner on host with accessibility driver (${promptSteps.size} steps)...")

      // Execute pre-action tools (e.g. launchApp) before running V3 prompt steps.
      // Reuse the host's top-level session ID so pre-action logs land in the same
      // on-device session directory as the main V3 loop — matches the per-tool
      // dispatch path in HostAccessibilityRpcClient.execute().
      //
      // Pre-action failure short-circuits the trail: `launchApp` failing means the main V3
      // loop would otherwise run against the wrong app state, producing a confusing
      // mid-trail failure instead of a clean "couldn't launch the app under test" one.
      var preActionFailure: String? = null
      preActionLoop@ for (toolItem in toolItems) {
        for (toolWrapper in toolItem.tools) {
          // Bare tool-wrapper list (`- <toolName>:`), decoded on-device via
          // decodeTrailOrToolEnvelope → decodeTools — never the legacy list-shape trail parser.
          val toolYaml = trailblazeYaml.encodeTools(listOf(toolWrapper))
          val singleToolRequest = runYamlRequest.copy(
            yaml = toolYaml,
            agentImplementation = AgentImplementation.TRAILBLAZE_RUNNER,
            config = runYamlRequest.config.copy(
              overrideSessionId = session.sessionId,
              sendSessionStartLog = false,
              sendSessionEndLog = false,
            ),
          )
          // Pass the resolved TrailblazeTool so executePreAction can host-local-short-circuit
          // before RPC'ing to the device (#2749 follow-up: scripted tools that own host-side
          // QuickJS/subprocess handles can't be RPC'd as if they were on-device tools).
          val ok = executor.executePreAction(toolWrapper.trailblazeTool, singleToolRequest)
          if (!ok) {
            preActionFailure =
              "Pre-action '${toolWrapper.trailblazeTool::class.simpleName ?: "unknown"}' " +
                "failed on-device; aborting trail before V3 prompt steps run. See prior log lines " +
                "for the on-device error message."
            break@preActionLoop
          }
        }
      }

      // Recording-first with AI-level retry budget. AI_ONLY here caused every step to re-plan
      // via LLM even when the recording matched — why the V3 a11y step had been running 100%
      // AI-driven on main.
      //
      // Skip the V3 trail entirely if a pre-action failed — see preActionFailure above.
      val trailSuccess: Boolean
      val trailErrorMessage: String?
      if (preActionFailure != null) {
        onProgressMessage("V3 trail aborted: $preActionFailure")
        trailSuccess = false
        trailErrorMessage = preActionFailure
      } else {
        val result = v3Runner.trail(
          steps = promptSteps,
          config = TrailConfig.RECORDING_WITH_AI_RETRIES,
          sessionId = session.sessionId,
          caseTitle = trailConfig?.title,
        )
        trailSuccess = result.success
        trailErrorMessage = result.errorMessage
        onProgressMessage(
          if (result.success) "V3 trail completed successfully"
          else "V3 trail failed: ${result.errorMessage}",
        )
      }

      if (runYamlRequest.config.sendSessionEndLog) {
        val v3ScreenStateProvider = {
          runBlocking { executor.captureScreenState() } ?: error("No screen state available")
        }
        if (trailSuccess) {
          loggingRule.captureFinalScreenshot(session, v3ScreenStateProvider)
          loggingRule.endSession(session, isSuccess = true)
        } else {
          loggingRule.captureFailureScreenshot(session, v3ScreenStateProvider)
          loggingRule.endSession(
            session,
            isSuccess = false,
            exception = Exception(trailErrorMessage ?: "Trail execution failed"),
          )
        }
      }

      generateAndSaveRecording(
        sessionId = session.sessionId,
        logsDir = loggingRule.logsRepo.logsDir,
        customToolClasses = customToolClasses,
      )

      session.sessionId
    }
  }

  /**
   * Runs the legacy [TrailblazeRunner] on the host with tool execution delegated to an
   * on-device driver (accessibility or instrumentation) via RPC.
   *
   * The agent loop (LLM calls, tool selection) runs on the host JVM. Each individual tool
   * call is serialized as single-step trail YAML and sent to the device. The device
   * executes the tool via whichever driver is specified in the request's `driverType`.
   * Mirrors the [runHostV3WithAccessibilityYaml] pattern but using [TrailblazeRunner]
   * instead of [MultiAgentV3Runner].
   *
   * @return The host session ID on completion. Failures and cancellations propagate
   *   as exceptions — this function does NOT swallow exceptions and return null.
   *   See [executeTrailSession] re-throw semantics + the silent-failure fix.
   */
  suspend fun runHostTrailblazeRunnerWithOnDeviceRpc(
    dynamicLlmClient: DynamicLlmClient,
    onDeviceRpc: OnDeviceRpcClient,
    runYamlRequest: RunYamlRequest,
    trailblazeDeviceId: TrailblazeDeviceId,
    onProgressMessage: (String) -> Unit,
    targetTestApp: TrailblazeHostAppTarget?,
    /**
     * Fired exactly once after the session is established (after `executeTrailSession` resolves
     * the session id), BEFORE we start dispatching trail items. Caller hooks session-scoped
     * out-of-band infrastructure here — most importantly the host-driven Android network capture
     * bridge, which has to be polling /proc/net/unix before the launch tool's first network
     * call. Defaulted to a no-op so existing callers (CLI, MCP, tests) stay compatible.
     */
    onSessionStarted: (SessionId) -> Unit = {},
    /**
     * The multi-device configuration this session selected and bound, or null for a
     * single-device run — see [MultiDeviceSessionRpc]. The caller (DesktopYamlRunner) resolves
     * the trail's `config.devices:` configuration entry, binds the launch device to the FIRST
     * declared name (the start device), and hands over already-connected, warmed-up RPC clients
     * for the remaining names.
     */
    multiDeviceSession: MultiDeviceSessionRpc? = null,
    /** Directory this run's session logs are written to. See [runHostYaml]. */
    logsDir: File? = null,
    /** The run's `--no-logging` flag: no session files, no trace export. See [runHostYaml]. */
    noLogging: Boolean = false,
  ): SessionId? {
    val driverType = runYamlRequest.driverType
      ?: TrailblazeDriverType.DEFAULT_ANDROID

    // ---- Per-device targets ----
    // A configuration's named devices may each declare their own `target:` (a paired-display trail
    // whose two displays run different apps), resolved by the caller; a device that declares none
    // inherits the session target. Single-device sessions bind exactly one target: `targetTestApp`.
    val startDeviceTarget: TrailblazeHostAppTarget? =
      if (multiDeviceSession != null) multiDeviceSession.startDeviceTarget else targetTestApp
    val boundTargets: List<TrailblazeHostAppTarget> = MultiDeviceTargetBinding.boundTargets(
      startDeviceTarget = startDeviceTarget,
      companionTargets = multiDeviceSession?.companions?.values.orEmpty().map { it.targetTestApp },
    )
    val customToolClasses = MultiDeviceTargetBinding.customToolClasses(boundTargets, driverType)

    // A web companion brings a SECOND driver into the session: its recorded tools must decode,
    // its framework tool surface must register on the session repo, and web-only scripted tools
    // must spawn — all otherwise scoped to the launch device's single driver. This surface is
    // deliberately NOT merged into `customToolClasses`, which also feeds the Android RPC agents
    // (whose on-device decoder has no web classes).
    val hasWebCompanion = multiDeviceSession?.companions.orEmpty().values
      .any { it is CompanionDeviceConnection.WebBrowser }
    val webCompanionToolSurface: ResolvedToolSet? = if (!hasWebCompanion) null else {
      val framework = TrailblazeToolSetCatalog.resolveForDriver(
        TrailblazeDriverType.PLAYWRIGHT_NATIVE,
        WebToolSetIds.ALL,
      )
      framework.copy(
        toolClasses = framework.toolClasses +
          // Across every bound target, not just the start device's: a per-device `target:` can put
          // a second target in the session, and its web tools have to decode here too.
          MultiDeviceTargetBinding.customToolClasses(boundTargets, TrailblazeDriverType.PLAYWRIGHT_NATIVE),
      )
    }
    val decodeToolClasses = customToolClasses + webCompanionToolSurface?.toolClasses.orEmpty()

    val trailblazeYaml = createTrailblazeYaml(
      customTrailblazeToolClasses = decodeToolClasses,
    )

    // Query the device's own description of itself up-front so a v3 trail can be lowered with the
    // right closest-wins recording for THIS device. v1 trails ignore the list.
    // Held in a reference, not a value: the device's size changes under the run (`setOrientation`
    // swaps the axes, and orientation is derived from them), so the agent below refreshes it from
    // every screen state this device reports. One reference per device — a companion rotating must
    // not rewrite the launch device's size.
    val deviceProfile = AtomicReference(queryDeviceProfile(onDeviceRpc))
    val classifiers = deviceProfile.get().classifiers.ifEmpty {
      HostProbedDeviceClassifiers.forDevice(trailblazeDeviceId)
    }

    // Envelope-tolerant decode: single-tool MCP dispatch decodes via decodeTools, not the legacy
    // list-shape parser; trail documents are unaffected.
    val trailItems = try {
      trailblazeYaml.decodeTrailOrToolEnvelope(
        runYamlRequest.yaml,
        deviceClassifiers = classifiers,
        // A selected configuration's recording legs are keyed by its NAME and match exactly —
        // configuration names are invisible to the launch device's classifier chain.
        selectedDeviceConfiguration = multiDeviceSession?.configurationName,
      )
    } catch (e: Exception) {
      Console.log("❌ Failed to decode on-device-RPC trail YAML: ${e::class.simpleName}: ${e.message}")
      onProgressMessage("Failed to decode trail YAML: ${e.message}")
      // Re-throw so DesktopYamlRunner.runYaml's outer catch sets executionResult = Failed.
      // Returning null was the silent-failure pattern previously fixed for executeTrailSession.
      throw e
    }
    val trailConfig = trailblazeYaml.extractTrailConfig(trailItems)

    // Honor `config.skip:` before opening any session — matches the CLI's pre-flight
    // `planTrailExecution` planner. This site short-circuits even earlier than the
    // ComposeRpc/Revyl sites because `executeTrailSession` hasn't been called yet here,
    // so the on-device-RPC runner never establishes a session for a skip-marked trail.
    trailblazeYaml.firstSkipReason(trailItems)?.let { skipReason ->
      Console.log(
        "[Trailblaze] Skipping trail" +
          (runYamlRequest.trailFilePath?.let { " ($it)" } ?: "") + ": $skipReason"
      )
      return null
    }

    val loggingRule = HostTrailblazeLoggingRule(
      trailblazeDeviceInfoProvider = {
        val profile = deviceProfile.get()
        TrailblazeDeviceInfo(
          trailblazeDeviceId = trailblazeDeviceId,
          trailblazeDriverType = driverType,
          widthPixels = profile.widthPixels,
          heightPixels = profile.heightPixels,
          classifiers = classifiers,
        )
      },
      logsDir = logsDir,
      noLogging = noLogging,
    )

    val trailblazeLlmModel = runYamlRequest.trailblazeLlmModel
    val llmClient = dynamicLlmClient.createLlmClient()

    // Same composer the on-device rules and the daemon use, so this target advertises the same
    // tools here as it does on device. Reads the target's `excluded_tools:` itself.
    //
    // The repo is session-scoped while targets are per-device, so a configuration binding more than
    // one target composes: the start device's target is the base and every other bound target's
    // RESOLVED tool scope rides along as additions, keeping each bound device's recorded tools
    // dispatchable. See `MultiDeviceTargetBinding.companionToolAdditions` for why the additions are
    // whole scopes rather than direct custom tools, and for how exclusions land.
    //
    // A web companion widens the same seam along the other axis — a second DRIVER rather than a
    // second target — so its surface joins the additions instead of replacing them. A session with
    // both (a phone and a browser, each on its own target) needs the union or one of them silently
    // loses its recorded tools. The start target's `excluded_tools:` still wins over the whole
    // composition, web surface included.
    val companionToolAdditions = MultiDeviceTargetBinding.companionToolAdditions(
      boundTargets = boundTargets,
      startDeviceTarget = startDeviceTarget,
      driverType = driverType,
    )
    // `switchDevice` is session-bound, not target-declared: a multi-device session adds the
    // `multi_device` toolset to its own surface so the LLM can hand the session over, and no target
    // has to know it might be cast into a pair. Recorded handovers never needed this — they dispatch
    // through the runner-util — so this is purely what makes the tool ADVERTISED.
    val handoverToolSurface = MultiDeviceTargetBinding.handoverToolSurface(
      isMultiDeviceSession = multiDeviceSession != null,
    )
    val toolRepo = startDeviceTarget.toSessionToolRepo(
      driverType = driverType,
      additional = listOfNotNull(
        companionToolAdditions,
        webCompanionToolSurface,
        handoverToolSurface,
      ).let { surfaces ->
        ResolvedAgentToolbox(
          toolClasses = surfaces.flatMapTo(mutableSetOf()) { it.toolClasses },
          yamlToolNames = surfaces.flatMapTo(mutableSetOf()) { it.yamlToolNames },
          scriptedToolNames = surfaces.flatMapTo(mutableSetOf()) { it.scriptedToolNames },
        )
      },
    )

    // An AI-driven step in a multi-device session is now a supported shape: the model is told the
    // device roster and which one is active, and `switchDevice` is advertised to it, so a step
    // without a recording reaches a brain that can both see and address the cast. What remains
    // rejected lives in MultiDeviceSessionPreflight, which decides; this throws.
    //
    // Runs AFTER the repo is composed because one of those rules asks whether `switchDevice`
    // survived the start target's `excluded_tools:` — a question only the composed surface can
    // answer. Still ahead of the agent and the session, which is what "session start" means here.
    if (multiDeviceSession != null) {
      MultiDeviceSessionPreflight.rejectionReason(
        configurationName = multiDeviceSession.configurationName,
        selfHealEnabled = runYamlRequest.config.selfHeal,
        handoverToolAdvertised = SwitchDeviceTrailblazeTool::class in toolRepo.getRegisteredTrailblazeTools(),
        useRecordedSteps = runYamlRequest.useRecordedSteps,
        trailItems = trailItems,
        boundNames = setOf(multiDeviceSession.startDeviceName) + multiDeviceSession.companions.keys,
      )?.let { reason -> throw TrailblazeException(reason) }
    }

    // Pre-resolve the START device's target once — mirrors the V3 wiring in
    // `runHostV3WithAccessibilityYaml`. Surfaces `ctx.target.{id, appIds,
    // appId}` to in-process scripted-tool handlers (e.g. Square card-reader
    // broadcast tools) via the envelope writer. The agent threads these through
    // `MaestroTrailblazeAgent.buildExecutionContext`, which sets
    // `TrailblazeToolExecutionContext.resolvedTarget` / `.appId`, which
    // `TrailblazeContextEnvelope.buildMetaTrailblaze` reads into `_meta.trailblaze.target`.
    // Without this wiring the in-process handlers see `ctx.target` as undefined and the
    // first `ctx.target.resolveAppId()` call throws.
    //
    // The app-id resolution is computed eagerly (not `by lazy` as in the V3 site at
    // `runHostV3WithAccessibilityYaml`) because this path constructs a single
    // session-scoped `HostOnDeviceRpcTrailblazeAgent` whose constructor takes a plain
    // `String?` — there is no per-tool `toolExecutionContextProvider` lambda where a
    // `Lazy<String?>` could defer the device query. Threading a `() -> String?` through
    // the agent and into `MaestroTrailblazeAgent.buildExecutionContext` would gain only
    // the ~50ms `pm list packages` shell-out on sessions that don't touch a target-aware
    // tool, which isn't worth the surface-area change. The V3 site can be lazy because
    // its `HostAccessibilityRpcClient` builds the execution context per tool dispatch.
    // A target with zero installed candidates surfaces as `appId = null` rather
    // than a thrown IllegalStateException so handlers can fall back to
    // `ctx.target?.appIds[0]` and let the launch fail downstream with a clearer message.
    val resolvedTargetForSession: xyz.block.trailblaze.model.ResolvedTarget? =
      MultiDeviceTargetBinding.agentResolvedTarget(startDeviceTarget, trailblazeDeviceId)
    val appIdForSession: String? = resolvedTargetForSession?.let { resolveInstalledAppId(it) }

    val agent = HostOnDeviceRpcTrailblazeAgent(
      rpcClient = onDeviceRpc,
      runYamlRequestTemplate = runYamlRequest,
      trailblazeLogger = loggingRule.logger,
      trailblazeDeviceInfoProvider = loggingRule.trailblazeDeviceInfoProvider,
      sessionProvider = { loggingRule.session ?: error("Session not available") },
      customToolClasses = customToolClasses,
      requireAndroidAccessibilityServiceOnRewarm =
        runYamlRequest.driverType == TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
      trailblazeToolRepo = toolRepo,
      resolvedTarget = resolvedTargetForSession,
      appId = appIdForSession,
      // Host-side `requiresHost` tools (e.g. a capture-reading tool) resolve capture artifacts under
      // this session's on-host log dir through the context this agent builds.
      sessionDirProvider = loggingRule.logsRepo::getSessionDir,
      // The trail file's directory lets host-local tools resolve trail-relative files (e.g. a
      // committed WAV recording) against the trail on disk — same wiring as the V3 site in
      // `runHostV3WithAccessibilityYaml`. Without it, a relative hostPath resolves against the
      // daemon's CWD, which a CI trail-source clone in /tmp never matches.
      workingDirectory = runYamlRequest.trailDirectory(),
      onScreenStateObserved = { response ->
        deviceProfile.updateAndGet { it.withMeasuredSizeFrom(response) }
      },
    )

    // Seed the agent's memory before any tool runs — same [AgentMemory.seedFrom] composition
    // as the V3 site. The agent interpolates `{{var}}` tokens against this memory at the RPC
    // boundary AND pushes it to the device as each dispatch's `memorySnapshot`, so on-device
    // tools' `ctx.memory` reads the same seeded state.
    val resolvedInitialMemory = agent.memory.seedFrom(
      yamlDefaults = trailConfig?.memory,
      cliSeeds = runYamlRequest.initialMemorySeeds,
      cliSensitiveSeeds = runYamlRequest.initialMemorySensitiveSeeds,
    )
    // Seed args after memory; the host pushes them to the device via each dispatch's argsSnapshot.
    agent.memory.seedArgs(TrailArgBinder.decodeProvided(runYamlRequest.initialArgs))
    val sensitiveMemoryKeys: Set<String> = agent.memory.sensitiveKeys.toSet()

    // ---- Multi-device wiring (a selected `config.devices:` configuration) ----
    // One additional agent per named device, sharing the launch agent's logger, session, and
    // memory. `SessionDeviceBindings` is the single shared switch the `switchDevice`
    // tool flips (through the execution context every agent builds); everything below that
    // must follow the handover — screen capture, element comparison, recorded-tool dispatch —
    // reads the active device through it instead of binding the launch agent directly.
    val companionAgents: Map<String, BoundSessionAgent> =
      multiDeviceSession?.companions.orEmpty().mapValues { (_, companion) ->
        when (companion) {
          is CompanionDeviceConnection.AndroidRpc -> {
            // This device's own reference — see the launch device's. A companion on an X2 pair is
            // the display most likely to be rotated mid-trail, and sharing one reference across the
            // pair would report whichever device captured last.
            val companionProfile = AtomicReference(queryDeviceProfile(companion.rpcClient))
            val companionClassifiers = companionProfile.get().classifiers.ifEmpty {
              HostProbedDeviceClassifiers.forDevice(companion.trailblazeDeviceId)
            }
            // THIS device's target and app id, not the launch device's: `ctx.target` follows the
            // device a tool runs on, so the app id is probed against this device's installed
            // packages against this device's own target. A pair whose displays run different apps
            // resolves a different `ctx.target.resolveAppId()` on each.
            val companionResolvedTarget = MultiDeviceTargetBinding.agentResolvedTarget(
              target = companion.targetTestApp,
              deviceId = companion.trailblazeDeviceId,
            )
            val companionAgent = HostOnDeviceRpcTrailblazeAgent(
              rpcClient = companion.rpcClient,
              // The template's device id feeds per-device concerns like the stream-screenshot
              // source; every RPC dispatch already routes through the companion's own rpcClient.
              // `targetAppName` names THIS device's target so on-device surfaces agree with the host.
              runYamlRequestTemplate = runYamlRequest.copy(
                trailblazeDeviceId = companion.trailblazeDeviceId,
                targetAppName = companion.targetTestApp?.id ?: runYamlRequest.targetAppName,
              ),
              trailblazeLogger = loggingRule.logger,
              trailblazeDeviceInfoProvider = {
                val profile = companionProfile.get()
                TrailblazeDeviceInfo(
                  trailblazeDeviceId = companion.trailblazeDeviceId,
                  trailblazeDriverType = driverType,
                  widthPixels = profile.widthPixels,
                  heightPixels = profile.heightPixels,
                  classifiers = companionClassifiers,
                )
              },
              sessionProvider = { loggingRule.session ?: error("Session not available") },
              // The union across bound targets, like every other agent here: this set is the agent's
              // tool SERIALIZER registry (it builds the single-step YAML each dispatch sends to the
              // device), not an advertisement surface. Narrowing it to this device's own target would
              // only make a cross-target tool call fail to serialize; which app a tool acts on is
              // carried by `resolvedTarget`/`appId` below.
              customToolClasses = customToolClasses,
              requireAndroidAccessibilityServiceOnRewarm =
                runYamlRequest.driverType == TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
              trailblazeToolRepo = toolRepo,
              resolvedTarget = companionResolvedTarget,
              appId = companionResolvedTarget?.let { resolveInstalledAppId(it) },
              sessionDirProvider = loggingRule.logsRepo::getSessionDir,
              // Companions run the same trail file, so trail-relative paths anchor the same way.
              workingDirectory = runYamlRequest.trailDirectory(),
              // Shared with the primary agent so a `remember` on one device resolves on another.
              memory = agent.memory,
              onScreenStateObserved = { response ->
                companionProfile.updateAndGet { it.withMeasuredSizeFrom(response) }
              },
            )
            BoundSessionAgent(
              agent = companionAgent,
              screenStateProvider = companionAgent.screenStateProvider,
              captureScreenState = { companionAgent.captureScreenState() },
              closeStreamScreenshotSource = { companionAgent.closeStreamScreenshotSource() },
            )
          }
          is CompanionDeviceConnection.WebBrowser -> {
            val webDeviceInfoProvider = {
              TrailblazeDeviceInfo(
                trailblazeDeviceId = companion.trailblazeDeviceId,
                trailblazeDriverType = TrailblazeDriverType.PLAYWRIGHT_NATIVE,
                // Unmeasured, and left that way deliberately. The Android companions above report
                // their size — and keep it current — because one RPC they already make returns it;
                // a browser's viewport has no equivalent free read: `PlaywrightPageManager` exposes
                // only screen-state capture, and capturing here would settle the page and spend a
                // step's pending detail request per log entry (see `captureScreenState` below). A
                // resize tool can change the viewport mid-session, so there is no honest snapshot
                // to take here either.
                // Nothing web-side keys on these: recordings fold to the bare `web` classifier and
                // orientation is meaningless for a browser window.
                widthPixels = HostDeviceProfile.UNMEASURED,
                heightPixels = HostDeviceProfile.UNMEASURED,
                // The bare platform classifier (`web`) — same fold single-device web sessions get.
                classifiers = listOf(TrailblazeDevicePlatform.WEB.asTrailblazeDeviceClassifier()),
              )
            }
            val webAgent = PlaywrightTrailblazeAgent(
              browserManager = companion.pageManager,
              trailblazeLogger = loggingRule.logger,
              trailblazeDeviceInfoProvider = webDeviceInfoProvider,
              sessionProvider = { loggingRule.session ?: error("Session not available") },
              trailblazeToolRepo = toolRepo,
              sessionDirProvider = loggingRule.logsRepo::getSessionDir,
              // Shared with the primary agent so a `remember` on one device resolves on another.
              memory = agent.memory,
            )
            BoundSessionAgent(
              agent = webAgent,
              // Self-bridges onto the Playwright thread — safe from the session's routing thread.
              screenStateProvider = webAgent.screenStateProvider,
              // Logging capture, NOT the LLM-facing `getScreenState()` behind `screenStateProvider`.
              // That one settles the page and CONSUMES the browser's pending detail requests, which
              // are reserved for the next snapshot the agent asks for — so routing a screenshot hook
              // through it would silently spend a step's detail request on a log entry, and pay a
              // settle per screenshot. Bridged onto the Playwright thread for the same reason the
              // provider is.
              captureScreenState = {
                companion.pageManager.onPlaywrightThread {
                  companion.pageManager.captureScreenStateForLogging()
                }
              },
              // The browser's lifecycle (slot reuse vs owned launch) belongs to the caller that
              // provisioned it — see [CompanionDeviceConnection.WebBrowser].
              closeStreamScreenshotSource = {},
            )
          }
        }
      }
    val deviceBindings: SessionDeviceBindings? = if (multiDeviceSession == null) {
      null
    } else {
      // The launch device is bound under the configuration's FIRST declared name (the start
      // device — declaration order is the launch marker); the remaining declared names bind
      // the companion agents. `switchDevice` addresses all of them by these names.
      //
      // Every device on this path has been probed, so identity comes from the probed info rather
      // than being passed alongside it — one source, so the binding's agreement invariant holds
      // by construction.
      fun boundDevice(
        deviceInfo: TrailblazeDeviceInfo,
        name: String,
        targetId: String?,
      ) = SessionDeviceBindings.BoundDevice(
        trailblazeDeviceId = deviceInfo.trailblazeDeviceId,
        trailblazeDeviceInfo = deviceInfo,
        description = multiDeviceSession.deviceDescriptions[name],
        targetId = targetId,
      )
      SessionDeviceBindings(
        devices = linkedMapOf(
          multiDeviceSession.startDeviceName to boundDevice(
            deviceInfo = loggingRule.trailblazeDeviceInfoProvider(),
            name = multiDeviceSession.startDeviceName,
            targetId = multiDeviceSession.startDeviceTarget?.id,
          ),
        ).apply {
          companionAgents.forEach { (name, bound) ->
            put(
              name,
              boundDevice(
                deviceInfo = bound.agent.trailblazeDeviceInfoProvider(),
                name = name,
                targetId = multiDeviceSession.companions.getValue(name).targetTestApp?.id,
              ),
            )
          }
        },
      ).also { bindings ->
        agent.deviceBindings = bindings
        companionAgents.values.forEach { it.agent.deviceBindings = bindings }
      }
    }
    // `HostScriptedToolLauncher.launch` skips already-registered names, so the start device's
    // runtime wins a name both targets declare.
    val scriptedToolLaunchPlan: List<Pair<TrailblazeHostAppTarget?, () -> TrailblazeDeviceInfo>> =
      MultiDeviceTargetBinding.scriptedToolLaunchPlan(
        startDeviceTarget = startDeviceTarget,
        startDeviceContext = loggingRule.trailblazeDeviceInfoProvider,
        companions = multiDeviceSession?.companions.orEmpty().map { (name, companion) ->
          companion.targetTestApp to companionAgents.getValue(name).agent.trailblazeDeviceInfoProvider
        },
      )

    val primaryBound = BoundSessionAgent(
      agent = agent,
      screenStateProvider = agent.screenStateProvider,
      captureScreenState = { agent.captureScreenState() },
      closeStreamScreenshotSource = { agent.closeStreamScreenshotSource() },
    )
    val agentsByName: Map<String, BoundSessionAgent> =
      if (multiDeviceSession == null) emptyMap() else companionAgents + (multiDeviceSession.startDeviceName to primaryBound)
    fun activeAgent(): BoundSessionAgent =
      deviceBindings?.let { agentsByName.getValue(it.activeName) } ?: primaryBound
    // Screen capture must follow a `switchDevice` handover, so every screen-state consumer
    // below reads the ACTIVE device through this indirection. Identical to
    // `agent.screenStateProvider` for single-device sessions.
    val activeScreenStateProvider: () -> ScreenState = { activeAgent().screenStateProvider() }
    // Recorded-tool dispatch must follow the handover too (a step recorded after a `switchDevice`
    // runs on the switched-to device). Both brains take the [KoogRunnableAgent] interface, so a
    // thin router resolves the active agent per dispatch: tool execution, execution-context
    // construction (dynamic tools), and device info all follow a `switchDevice` handover.
    // Logger/session/memory delegate to the launch agent — they're shared across every bound
    // agent by construction. This same router is what AI-driven steps will dispatch through once
    // the LLM-facing wiring lands (multi-device sessions are recorded-only until then).
    val routingAgent: KoogRunnableAgent = if (deviceBindings == null) {
      agent
    } else {
      object : KoogRunnableAgent {
        override val trailblazeLogger get() = agent.trailblazeLogger
        override val trailblazeDeviceInfoProvider: () -> TrailblazeDeviceInfo =
          { activeAgent().agent.trailblazeDeviceInfoProvider() }
        override val sessionProvider get() = agent.sessionProvider
        override val memory get() = agent.memory
        override fun runTrailblazeTools(
          tools: List<TrailblazeTool>,
          traceId: TraceId?,
          screenState: ScreenState?,
          elementComparator: ElementComparator,
          screenStateProvider: (() -> ScreenState)?,
        ): TrailblazeAgent.RunTrailblazeToolsResult = activeAgent().agent.runTrailblazeTools(
          tools = tools,
          traceId = traceId,
          screenState = screenState,
          elementComparator = elementComparator,
          screenStateProvider = screenStateProvider,
        )
        override fun buildKoogToolExecutionContext(
          traceId: TraceId?,
          screenStateProvider: () -> ScreenState,
        ) = activeAgent().agent.buildKoogToolExecutionContext(
          traceId = traceId,
          screenStateProvider = screenStateProvider,
        )
      }
    }

    // Whether the roster also states the handover contract follows the repo's ACTUAL surface rather
    // than the multi-device condition that added it, so a target excluding `switchDevice` can't
    // leave the prompt describing a tool the model was never offered.
    //
    // Read per render, not captured: `addTrailblazeToolSet` can register after this point, and the
    // verify surface reads the same registration live (`TrailblazeToolRepo.handoverVerifyTools`).
    // A snapshot here would let a late registration advertise `switchDevice` on a verify step while
    // the prompt still told the model the session has no handover.
    val multiDevicePromptContextProvider: (() -> String?)? = deviceBindings?.let { bindings ->
      {
        bindings.renderMultiDevicePromptSection(
          handoverToolAdvertised = SwitchDeviceTrailblazeTool::class in toolRepo.getRegisteredTrailblazeTools(),
        )
      }
    }

    val elementComparator = TrailblazeElementComparator(
      screenStateProvider = activeScreenStateProvider,
      llmClient = llmClient,
      trailblazeLlmModel = trailblazeLlmModel,
      toolRepo = toolRepo,
    )

    // Brain selection (legacy or KOOG). This is the `preferHostAgent` host-driven path: the loop
    // runs on the host and dispatches tools to the device over RPC. Recordings replay uniformly via
    // the runner-util regardless of agent — only unrecorded steps reach the selected brain.
    val runner: TestAgentRunner =
      if (runYamlRequest.agentImplementation == AgentImplementation.KOOG_STRATEGY_GRAPH) {
        KoogTestAgentRunner(
          agent = routingAgent,
          toolRepo = toolRepo,
          screenStateProvider = activeScreenStateProvider,
          elementComparator = elementComparator,
          llmClient = llmClient,
          trailblazeLlmModel = trailblazeLlmModel,
          logger = loggingRule.logger,
          sessionProvider = { loggingRule.session ?: error("Session not available") },
          maxLlmCalls = runYamlRequest.maxLlmCalls,
          systemPromptTemplate = TrailblazeRunner.composeSystemPrompt(),
        ).apply {
          perStepSystemPromptContextProvider = multiDevicePromptContextProvider
        }
      } else {
        TrailblazeRunner(
          agent = routingAgent,
          screenStateProvider = activeScreenStateProvider,
          llmClient = llmClient,
          trailblazeLlmModel = trailblazeLlmModel,
          trailblazeToolRepo = toolRepo,
          trailblazeLogger = loggingRule.logger,
          sessionProvider = { loggingRule.session ?: error("Session not available") },
          maxSteps = runYamlRequest.maxLlmCalls ?: TrailblazeRunner.DEFAULT_MAX_STEPS,
        ).apply {
          perStepSystemPromptContextProvider = multiDevicePromptContextProvider
        }
      }

    // Per-tool screen capture for Maestro→accessibility migration. Read from env var
    // (`TRAILBLAZE_CAPTURE_SECONDARY_TREE=true`) since the host runner doesn't currently
    // surface the on-device instrumentation arg map. The same env var is also bridged to
    // the on-device APK via [BlockTrailblazeDesktopAppConfig.additionalInstrumentationArgs],
    // so both sides see the toggle from a single source of truth.
    val migrationCaptureEnabled =
      System.getenv("TRAILBLAZE_CAPTURE_SECONDARY_TREE")?.equals("true", ignoreCase = true) == true
    val onBeforeRecordedTool: (suspend (TrailblazeTool) -> Unit)? = if (migrationCaptureEnabled) {
      lambda@{ tool: TrailblazeTool ->
        // Only fire the capture for the selector-bearing tools a driver migration cares
        // about. Recordings include launch / custom flow / verify tools that a migration
        // pass doesn't touch — a snapshot per non-target tool would inflate session-log
        // size for no benefit.
        val isMigrationTarget = tool is xyz.block.trailblaze.toolcalls.commands.TapOnByElementSelector ||
          tool is xyz.block.trailblaze.toolcalls.commands.AssertVisibleBySelectorTrailblazeTool
        if (!isMigrationTarget) return@lambda
        try {
          val session = loggingRule.session ?: return@lambda
          // captureScreenState() goes through the on-device RPC; the on-device side reads
          // its own `trailblaze.captureSecondaryTree` arg and (when set) returns a screen
          // state with a true UiAutomator viewHierarchy alongside the accessibility-tree
          // trailblazeNodeTree. Both end up in the snapshot log. Suspended directly (not
          // wrapped in runBlocking) so single-thread dispatchers don't deadlock. Reads the
          // ACTIVE device so migration captures follow a switchDevice handover.
          val screen = activeAgent().captureScreenState()
          if (screen != null) {
            loggingRule.logger.logSnapshot(
              session = session,
              screenState = screen,
              displayName = "preTool: ${tool::class.simpleName ?: "unknown"}",
            )
          }
        } catch (e: kotlinx.coroutines.CancellationException) {
          // Cooperative cancellation: trail abort / timeout must propagate. The
          // outer try/catch in TrailblazeRunnerUtil rethrows this for the same reason.
          throw e
        } catch (e: Exception) {
          // Hook is observational; never let a capture failure kill the recording.
          Console.log("[migration-capture] pre-tool snapshot failed: ${e.message}")
        }
      }
    } else null

    // Post-tool capture is asserts-only. AssertVisibleBySelector waits up to ~30s for the
    // target to become visible; the pre-tool snapshot fires before that wait and often
    // catches a mid-transition frame where the asserted element isn't yet in the tree.
    // After the assert succeeds, the element IS on screen, and a post-tool snapshot
    // reliably has it — `migrate-trail` prefers `postTool: AssertVisibleBySelectorTrailblazeTool`
    // for assert-class tools and falls back to the pre-tool snapshot when no post exists.
    // Taps are intentionally excluded: a tap's post-state is the NEXT screen, where the
    // tapped target is no longer present — useless for resolving the original selector.
    val onAfterRecordedTool: (suspend (TrailblazeTool) -> Unit)? = if (migrationCaptureEnabled) {
      lambda@{ tool: TrailblazeTool ->
        if (tool !is xyz.block.trailblaze.toolcalls.commands.AssertVisibleBySelectorTrailblazeTool) {
          return@lambda
        }
        try {
          val session = loggingRule.session ?: return@lambda
          val screen = activeAgent().captureScreenState()
          if (screen != null) {
            loggingRule.logger.logSnapshot(
              session = session,
              screenState = screen,
              displayName = "postTool: ${tool::class.simpleName ?: "unknown"}",
            )
          }
        } catch (e: kotlinx.coroutines.CancellationException) {
          throw e
        } catch (e: Exception) {
          Console.log("[migration-capture] post-tool snapshot failed: ${e.message}")
        }
      }
    } else {
      null
    }

    val runnerUtil = TrailblazeRunnerUtil(
      trailblazeRunner = runner,
      runTrailblazeTool = { tools ->
        if (deviceBindings == null) {
          agent.runTrailblazeTools(
            tools = tools,
            traceId = runYamlRequest.traceId,
            // No eager capture: tools on this path execute ON DEVICE against the device's own
            // live tree, so a host-side screen state is only ever read by host-local dispatches
            // (subprocess MCP / `requires_host` scripted tools). Passing null defers to the
            // context's lazy capture-on-read — an eager `screenStateProvider()` here was a full
            // screenshot RPC (~0.5-1s) per recorded tool that recorded replay never consumed,
            // the single largest per-action cost in https://github.com/block/trailblaze/issues/210.
            screenState = null,
            elementComparator = elementComparator,
            screenStateProvider = agent.screenStateProvider,
          ).result
        } else {
          // Multi-device: dispatch one tool at a time, resolving the active agent per tool, so
          // a recorded `switchDevice` reroutes the REST of the same recording to the new device
          // (the handover takes effect from the next dispatch). Each recorded tool is its own
          // single-tool RunYamlRequest on this path anyway (see the ToolBatchScope note below),
          // so per-tool dispatch changes routing only, not batching semantics. `screenState =
          // null` for the same lazy-capture reason as the single-device branch.
          var result: TrailblazeToolResult = TrailblazeToolResult.Success()
          for (tool in tools) {
            result = activeAgent().agent.runTrailblazeTools(
              tools = listOf(tool),
              traceId = runYamlRequest.traceId,
              screenState = null,
              elementComparator = elementComparator,
              screenStateProvider = activeScreenStateProvider,
            ).result
            if (!result.isSuccess()) break
          }
          result
        }
      },
      trailblazeLogger = loggingRule.logger,
      sessionProvider = { loggingRule.session ?: error("Session not available") },
      sessionUpdater = { loggingRule.setSession(it) },
      onBeforeRecordedTool = onBeforeRecordedTool,
      onAfterRecordedTool = onAfterRecordedTool,
      // Deliberately NOT wired here, unlike the other host runners in this file:
      // 1. `agent.executeToolViaRpc` sends each recorded tool as its own single-tool
      //    `RunYamlRequest`, so the on-device `AndroidDeviceCommandExecutor` (and its
      //    clipboard cache) resets between tools on the DEVICE regardless of what this
      //    host-side context shares — there's no cross-tool device state for this bracket
      //    to preserve, unlike the in-process runners.
      // 2. When `migrationCaptureEnabled`, `onBeforeRecordedTool`/`onAfterRecordedTool`
      //    call `agent.captureScreenState()`, a suspend RPC call whose continuation is not
      //    guaranteed to resume on the entering thread. `ToolBatchScope` is thread-scoped
      //    (see its kdoc's THREAD_HOP note) and can't recover from that hop — it would leak
      //    the pushed SnapshotCache frame / installed ThreadLocal on the original thread.
    )

    val subprocessRuntimes = mutableListOf<LaunchedScriptingRuntime>()
    return executeTrailSession(
      loggingRule = loggingRule,
      overrideSessionId = runYamlRequest.config.overrideSessionId,
      testName = runYamlRequest.testName,
      deviceLabel = "rpc-runner:${trailblazeDeviceId.instanceId}",
      sendSessionEndLog = runYamlRequest.config.sendSessionEndLog,
      onProgressMessage = onProgressMessage,
      screenshotProvider = {
        runBlocking { activeAgent().captureScreenState() } ?: error("No screen state available")
      },
      noLogging = noLogging,
      cleanup = {
        withContext(NonCancellable) {
          subprocessRuntimes.forEach { it.shutdownAll() }
          // Detach from the shared H.264 tee (no-op unless TRAILBLAZE_ANDROID_STREAM_SCREENSHOT
          // engaged) so the underlying screenrecord doesn't outlive the session.
          agent.closeStreamScreenshotSource()
          companionAgents.values.forEach { it.closeStreamScreenshotSource() }
        }
      },
    ) { session ->
      scriptedToolLaunchPlan.forEach { (planTarget, deviceInfoProvider) ->
        launchSubprocessMcpServersIfAny(
          targetTestApp = planTarget,
          config = runYamlRequest.config,
          sessionId = session.sessionId,
          deviceInfo = deviceInfoProvider(),
          logsRepo = loggingRule.logsRepo,
          toolRepo = toolRepo,
          // A web companion's own subprocess tools (e.g. a web sign-in tool) would otherwise be
          // gated out by the launch device's Android driver and never spawn. A web companion
          // inheriting the session target contributes no plan entry of its own, so the gate has to
          // widen on the entries that DO exist; `HostScriptedToolLauncher.launch` skips names it
          // already registered, so widening every entry spawns each tool once.
          additionalDriverTypes = if (webCompanionToolSurface == null) {
            emptySet()
          } else {
            setOf(TrailblazeDriverType.PLAYWRIGHT_NATIVE)
          },
          onProgressMessage = onProgressMessage,
        )?.let { subprocessRuntimes += it }
      }
      if (runYamlRequest.config.sendSessionStartLog) {
        val deviceInfo = loggingRule.trailblazeDeviceInfoProvider()
        // See ComposeRpc site — derive a readable Suite::test identity from the path.
        val derivedTestIdentity = runYamlRequest.trailFilePath?.let {
          TrailRecordings.deriveTestIdentityFromTrailPath(it, fallbackClassName = "HostOnDeviceRpcRunner")
        }
        loggingRule.logger.log(
          session,
          TrailblazeLog.TrailblazeSessionStatusChangeLog(
            sessionStatus = SessionStatus.Started(
              trailConfig = trailConfig,
              trailFilePath = runYamlRequest.trailFilePath,
              testClassName = derivedTestIdentity?.className ?: "HostOnDeviceRpcRunner",
              testMethodName = derivedTestIdentity?.methodName ?: "run",
              trailblazeDeviceInfo = deviceInfo,
              rawYaml = runYamlRequest.yaml,
              hasRecordedSteps = trailblazeYaml.hasRecordedSteps(trailItems),
              trailblazeDeviceId = trailblazeDeviceId,
              resolvedInitialMemory = resolvedInitialMemory,
              sensitiveMemoryKeys = sensitiveMemoryKeys,
              // The START device's target: a session reports one target, and the start device is
              // the one it launched against. Per-device attribution is separate follow-up work.
              targetAppInfo = MobileDeviceUtils.resolveTargetAppInfo(
                target = startDeviceTarget,
                trailblazeDeviceId = trailblazeDeviceId,
                resolvedAppId = appIdForSession,
              ),
              // A configuration session's recording legs are keyed by the configuration NAME —
              // the save path reads it back from this log to key the merge.
              selectedDeviceConfiguration = multiDeviceSession?.configurationName,
            ),
            session = session.sessionId,
            timestamp = Clock.System.now(),
          ),
        )
      }

      onProgressMessage(
        "Starting TrailblazeRunner on host with ${driverType.name.lowercase()} driver via RPC (${trailItems.size} trail items)...",
      )

      requireActionableSteps(
        trailblazeYaml = trailblazeYaml,
        trailItems = trailItems,
        trailName = trailConfig?.title ?: runYamlRequest.trailFilePath,
      )

      // Fire the session-started callback BEFORE dispatching trail items but AFTER
      // [requireActionableSteps] above. Order matters and is intentional:
      // - AFTER requireActionableSteps: a YAML that fails the actionable-steps gate has no
      //   tools to run and would generate no out-of-band traffic, so spinning up session-scoped
      //   infrastructure (e.g. the network capture bridge) just to immediately tear it down
      //   would be wasted work and would confuse the operator with a phantom CONNECTED state
      //   on a session that never actually ran a tool.
      // - BEFORE the trail-item loop: the registered [AndroidNetworkCaptureActivator] has to be
      //   polling for the target's discovery side-channel before the first launch tool's first
      //   network call, otherwise it can miss a freshly-opened socket — see the activator's own
      //   stale-discovery resilience for the race the order avoids.
      // Errors are swallowed so a misbehaving listener can't take down the test.
      try {
        onSessionStarted(session.sessionId)
      } catch (t: Throwable) {
        Console.log(
          "[runHostTrailblazeRunnerWithOnDeviceRpc] onSessionStarted callback threw — " +
            "continuing test run: ${t::class.java.simpleName}: ${t.message}"
        )
      }

      for (item in trailItems) {
        val itemResult = when (item) {
          is TrailYamlItem.PromptsTrailItem ->
            // Agent-agnostic: replays recorded steps deterministically and delegates only
            // unrecorded steps to the selected runner (legacy / KOOG). Honor the request's
            // useRecordedSteps (like the Revyl path) so a forced live re-blaze isn't silently
            // replayed here. Default unchanged.
            runnerUtil.runPromptSuspend(
              prompts = item.promptSteps,
              useRecordedSteps = runYamlRequest.useRecordedSteps,
              selfHeal = runYamlRequest.config.selfHeal,
            )
          is TrailYamlItem.TrailheadTrailItem ->
            runnerUtil.runPromptSuspend(
              prompts = listOf(item.trailhead.toPromptStep()),
              useRecordedSteps = true,
              selfHeal = runYamlRequest.config.selfHeal,
            )
          is TrailYamlItem.ToolTrailItem ->
            runnerUtil.runTrailblazeTool(item.tools.map { it.trailblazeTool })
          is TrailYamlItem.ConfigTrailItem ->
            item.config.context?.let { runner.appendToSystemPrompt(it) }
        }
        if (itemResult is TrailblazeToolResult.Error) {
          throw TrailblazeException(itemResult.errorMessage)
        }
      }

      onProgressMessage("TrailblazeRunner accessibility execution completed successfully")

      if (runYamlRequest.config.sendSessionEndLog) {
        // Active device, not the launch device: a trail that ends on a companion should end its
        // session log with that companion's screen. Identical to `agent.screenStateProvider` for
        // single-device sessions.
        loggingRule.captureFinalScreenshot(session, activeScreenStateProvider)
        loggingRule.endSession(session, isSuccess = true)
      }

      generateAndSaveRecording(
        sessionId = session.sessionId,
        logsDir = loggingRule.logsRepo.logsDir,
        // Same superset the decode used, so a web companion's recorded steps round-trip.
        customToolClasses = decodeToolClasses,
      )

      session.sessionId
    }
  }

  /**
   * Asks the on-device agent to describe itself via a lightweight screen state probe: its
   * classifiers (e.g. ["android", "phone"]) and its pixel dimensions.
   *
   * One probe for both, because the response carries both. The dimensions matter beyond display —
   * `TrailblazeDeviceInfo` derives orientation from them, and scripted tools read them off
   * `ctx.device` — so this used to report every RPC-driven device as an implicitly-portrait 0x0
   * while the true size sat unread in the same response.
   *
   * Degrades rather than throws: an older agent without classifier support, or a failed RPC,
   * yields empty/[HostDeviceProfile.UNMEASURED] on the axis it couldn't answer. Callers supply
   * their own classifier fallback — which recovers classifiers but deliberately not size; see
   * [HostDeviceProfile.unknown] for why borrowing that probe's `wm size` would be worse than
   * reporting nothing.
   */
  private suspend fun queryDeviceProfile(
    onDeviceRpc: OnDeviceRpcClient,
  ): HostDeviceProfile {
    val probe = GetScreenStateRequest(
      includeScreenshot = false,
      includeAnnotatedScreenshot = false,
    )
    return when (val result = onDeviceRpc.rpcCall(probe)) {
      is RpcResult.Success -> HostDeviceProfile.fromScreenState(result.data)
      is RpcResult.Failure -> {
        // Names the RPC cause only. The caller's fallback logs what it recovers with, so
        // stating the recovery here too would be both duplicative and wrong.
        Console.log(
          "[Trailblaze] Failed to query device profile from on-device agent. " +
            "RPC failure: ${result.message}",
        )
        HostDeviceProfile.unknown()
      }
    }
  }

  /**
   * An already-connected companion device for a multi-device session — see
   * [runHostTrailblazeRunnerWithOnDeviceRpc]. The caller owns connect/warm-up; the runner builds
   * a per-device agent around whichever transport the companion's platform uses.
   */
  sealed interface CompanionDeviceConnection {
    val trailblazeDeviceId: TrailblazeDeviceId

    /**
     * This device's EFFECTIVE target — its own `target:` override when the configuration declares
     * one, else the session target every device inherits. Already resolved by the caller, which
     * owns the target registry. Drives this device's `ctx.target` (app ids resolved against THIS
     * device's installed packages) and its custom tools.
     */
    val targetTestApp: TrailblazeHostAppTarget?

    /**
     * An Android companion driven over the on-device RPC transport. The caller runs the same
     * connect/readiness flow the launch device gets; the runner builds a
     * [HostOnDeviceRpcTrailblazeAgent] around the client.
     */
    data class AndroidRpc(
      override val trailblazeDeviceId: TrailblazeDeviceId,
      val rpcClient: OnDeviceRpcClient,
      override val targetTestApp: TrailblazeHostAppTarget? = null,
    ) : CompanionDeviceConnection

    /**
     * A web companion driven in-process against a host-owned Playwright browser (no device, no
     * RPC). The caller owns the browser's lifecycle — reuse of a daemon slot vs a fresh launch,
     * and closing an owned browser at run end; the runner builds a [PlaywrightTrailblazeAgent]
     * around the manager.
     *
     * [targetTestApp] is the target this browser's own tools resolve against; a browser installs
     * no app, so a per-device `target:` here only selects a tool scope. Null means it inherits the
     * session target.
     */
    data class WebBrowser(
      override val trailblazeDeviceId: TrailblazeDeviceId,
      val pageManager: PlaywrightPageManager,
      override val targetTestApp: TrailblazeHostAppTarget? = null,
    ) : CompanionDeviceConnection
  }

  /**
   * One bound device's agent plus the per-transport capture/cleanup surface the session loop
   * needs. Multi-device sessions bind heterogeneous agents (Android RPC, in-process Playwright)
   * whose shared supertype [BaseTrailblazeAgent] doesn't carry screen capture or stream-source
   * teardown — this wrapper closes that gap without widening the agent interface.
   */
  private class BoundSessionAgent(
    val agent: BaseTrailblazeAgent,
    val screenStateProvider: () -> ScreenState,
    /** Suspend capture used by the snapshot/screenshot hooks; null when capture failed. */
    val captureScreenState: suspend () -> ScreenState?,
    val closeStreamScreenshotSource: () -> Unit,
  )

  /**
   * A resolved-and-bound multi-device configuration for one session — the runtime counterpart of
   * a `config.devices:` configuration entry (an entry carrying an inner `devices:` map of named
   * devices). The launch device IS the start device: [startDeviceName] is the configuration's
   * FIRST declared name and binds to the device the run launched against; [companions] carries
   * the remaining names in declaration order, each already connected and warmed up.
   */
  data class MultiDeviceSessionRpc(
    /** The selected configuration entry's name (e.g. `pos-pair`) — also the recordings' leg key. */
    val configurationName: String,
    /** The configuration's first declared device name; bound to the launch device. */
    val startDeviceName: String,
    /** The non-start named devices in declaration order. Never contains [startDeviceName]. */
    val companions: Map<String, CompanionDeviceConnection>,
    /**
     * The start device's EFFECTIVE target (its own `target:` override, else the session target).
     * Null means "no target", same as a session with none.
     *
     * Session-level surfaces — capture app ids, force-stop, the recorded `targetAppName`, the
     * session-start `targetAppInfo` — follow this one, because a session reports a single target
     * and the start device is the one it launched against. Per-device attribution in reports is
     * separate follow-up work.
     */
    val startDeviceTarget: TrailblazeHostAppTarget? = null,
    /** Human-readable role descriptions from the selected configuration, including start. */
    val deviceDescriptions: Map<String, String?>,
  ) {
    init {
      require(startDeviceName !in companions) {
        "start device '$startDeviceName' must not also appear in companions ${companions.keys}"
      }
    }
  }

  /**
   * Result of recording generation, containing info needed to copy the recording
   * back to the trail source directory.
   */
  data class RecordingResult(
    val recordingFile: File,
    val deviceClassifiers: List<xyz.block.trailblaze.devices.TrailblazeDeviceClassifier>,
    val driverType: String?,
    /**
     * The multi-device configuration the session selected, or null for a single-device run.
     * When set, the recording's legs are keyed by this name rather than [deviceClassifiers].
     */
    val selectedDeviceConfiguration: String? = null,
  )

  /**
   * Env var naming a baseline run to diff `takeSnapshot` captures against when the run itself
   * carries no [RunOnHostParams.snapshotBaselineRef] — an http(s) URL to a session logs zip, a
   * local zip, or an extracted session directory. Set it on the process that executes the
   * comparison (the daemon / desktop app for delegated runs, the CLI for `--no-daemon` runs).
   */
  const val SNAPSHOT_BASELINE_ENV_VAR = "TRAILBLAZE_SNAPSHOT_BASELINE"

  /** Env-var counterpart of [RunOnHostParams.snapshotBaselineThresholdPercent]. */
  const val SNAPSHOT_BASELINE_THRESHOLD_ENV_VAR = "TRAILBLAZE_SNAPSHOT_BASELINE_THRESHOLD"

  /**
   * Compares each snapshot taken during [sessionId] against its reference image.
   *
   * Two modes:
   *  - **Baseline run** (when [snapshotBaselineRef] or `TRAILBLAZE_SNAPSHOT_BASELINE` is set):
   *    references come from a PREVIOUS run's session artifacts (e.g. CI's `latest_success.zip`),
   *    matched by snapshot name — no golden files in the repo. A baseline that was explicitly
   *    requested but cannot be resolved FAILS the run (via [TrailblazeException]) instead of
   *    silently comparing nothing.
   *  - **Checked-in goldens** (default): resolved from the trail file's directory using the
   *    pattern `{device-classifier}.{snapshot-name}.golden.png`.
   *
   * In both modes a snapshot with no reference is skipped (not a failure) — this allows new
   * trails/snapshots to run before their reference exists.
   */
  internal fun compareSnapshotsAgainstGoldens(
    sessionId: SessionId,
    logsDir: File,
    snapshotBaselineRef: String? = null,
    snapshotBaselineThresholdPercent: Double? = null,
  ): SnapshotGoldenComparison.GoldenComparisonResult? {
    val baselineRef = snapshotBaselineRef?.takeIf { it.isNotBlank() }
      ?: System.getenv(SNAPSHOT_BASELINE_ENV_VAR)?.takeIf { it.isNotBlank() }

    val comparison = try {
      // Plain child join, not LogsRepo.getSessionDir — that would mkdir and defeat this guard.
      val sessionDir = File(logsDir, sessionId.value)
      // No session directory means no snapshot logs and no images to compare — with `--no-logging`
      // that is the normal state, not an empty result. A requested baseline must say so rather
      // than pass having compared nothing.
      if (!sessionDir.exists()) {
        if (baselineRef != null) {
          throw TrailblazeException(
            "Snapshot baseline '$baselineRef' was requested but this run wrote no session logs " +
              "(${sessionDir.absolutePath}); snapshot comparison needs the run's captures, so it " +
              "cannot be combined with logging disabled",
          )
        }
        return null
      }

      val logs = sessionDir.listFiles()
        ?.filter { it.extension == "json" }
        ?.mapNotNull { file ->
          try { TrailblazeJsonInstance.decodeFromString<TrailblazeLog>(file.readText()) }
          catch (_: Exception) { null }
        }
        ?.sortedBy { it.timestamp }
        ?: return null

      if (baselineRef != null) {
        compareAgainstBaselineRun(sessionId, sessionDir, logs, baselineRef, snapshotBaselineThresholdPercent)
      } else {
        SnapshotGoldenComparison.compare(
          sessionId = sessionId,
          sessionDir = sessionDir,
          logs = logs,
        )
      }
    } catch (e: TrailblazeException) {
      // A baseline the caller explicitly asked for could not be resolved/compared — a silent
      // pass here is the false green this feature exists to prevent.
      throw e
    } catch (e: Exception) {
      // Same rule for anything else that goes wrong (temp-dir creation, image decode, IO): with a
      // baseline requested, "comparison error" must not degrade into "compared nothing, passed".
      if (baselineRef != null) {
        throw TrailblazeException(
          "Snapshot baseline '$baselineRef' comparison failed: ${e.message ?: e::class.simpleName}",
        )
      }
      Console.log("[Golden] Comparison error (non-fatal): ${e.message}")
      return null
    }

    val tag = "[${comparison.referenceLabel.replaceFirstChar { it.uppercase() }}]"
    Console.log("$tag ${comparison.summary}")
    comparison.results.forEach { r ->
      if (r.goldenFound && !r.passed) {
        Console.log(
          "$tag ❌ '${r.snapshotName}': ${"%.2f".format(r.diffPercent)}% diff" +
            " (${r.pixelDifferences}/${r.totalPixels} pixels," +
            " threshold ${r.thresholdPercent}%)"
        )
      }
    }
    return comparison
  }

  /**
   * Resolves [baselineRef] (download / unzip as needed) and diffs the session's snapshots
   * against it. Resolution failures throw [TrailblazeException] — the caller asked for this
   * comparison, so "could not fetch the baseline" must fail the run, not skip it.
   */
  /** Default when neither the request nor the environment names a threshold. */
  internal const val DEFAULT_SNAPSHOT_BASELINE_THRESHOLD = 2.0

  /**
   * The threshold to compare at: the request's value, else [SNAPSHOT_BASELINE_THRESHOLD_ENV_VAR],
   * else [DEFAULT_SNAPSHOT_BASELINE_THRESHOLD]. Both sources get the 0..100 check
   * `--snapshot-baseline-threshold` applies, and an unparseable env value fails rather than
   * silently defaulting: `-1` makes nothing pass, `500` makes nothing fail, and `2,0` reads as
   * neither the 2 the author meant nor an error they can see.
   */
  internal fun resolveBaselineThreshold(requested: Double?, envValue: String?): Double {
    fun validated(value: Double, source: String): Double {
      // NaN compares false against both bounds, so an unguarded range test lets it through and then
      // every `diffPercent > threshold` is false — the gate reports differences and passes anyway.
      if (!value.isFinite() || value < 0.0 || value > 100.0) {
        throw TrailblazeException("Snapshot baseline threshold from $source must be between 0 and 100 (got $value)")
      }
      return value
    }
    requested?.let { return validated(it, "the run request") }
    val env = envValue?.takeIf { it.isNotBlank() } ?: return DEFAULT_SNAPSHOT_BASELINE_THRESHOLD
    val parsed = env.toDoubleOrNull()
      ?: throw TrailblazeException("$SNAPSHOT_BASELINE_THRESHOLD_ENV_VAR is not a number: '$env'")
    return validated(parsed, SNAPSHOT_BASELINE_THRESHOLD_ENV_VAR)
  }

  private fun compareAgainstBaselineRun(
    sessionId: SessionId,
    sessionDir: File,
    logs: List<TrailblazeLog>,
    baselineRef: String,
    thresholdPercent: Double?,
  ): SnapshotGoldenComparison.GoldenComparisonResult {
    val resolvedThreshold = resolveBaselineThreshold(
      thresholdPercent,
      System.getenv(SNAPSHOT_BASELINE_THRESHOLD_ENV_VAR),
    )

    val workDir = Files.createTempDirectory("trailblaze-snapshot-baseline").toFile()
    try {
      val baseline = try {
        SnapshotBaselineSource.resolve(baselineRef, workDir)
      } catch (e: Exception) {
        throw TrailblazeException("Snapshot baseline '$baselineRef' could not be resolved: ${e.message}")
      }
      if (baseline.snapshotsByName.isEmpty()) {
        Console.log("[Baseline] Baseline session at '$baselineRef' contains no takeSnapshot captures")
      }
      Console.log("[Baseline] Comparing snapshots against ${baseline.sourceDescription}")
      return SnapshotGoldenComparison.compareToBaseline(
        sessionId = sessionId,
        sessionDir = sessionDir,
        logs = logs,
        baseline = baseline,
        thresholdPercent = resolvedThreshold,
      )
    } finally {
      // Diff images land in the CURRENT session's directory; the downloaded/extracted baseline
      // is no longer needed once the comparison has run.
      workDir.deleteRecursively()
    }
  }

  internal fun requireActionableSteps(
    trailblazeYaml: xyz.block.trailblaze.yaml.TrailblazeYaml,
    trailItems: List<TrailYamlItem>,
    trailName: String?,
  ) {
    if (!trailblazeYaml.hasActionableSteps(trailItems)) {
      throw TrailblazeException(
        "Trail '${trailName ?: "unknown"}' has no executable steps — this would be a false positive pass. " +
          "Add prompts or tool steps to this trail file.",
      )
    }
  }

  internal fun generateAndSaveRecording(
    sessionId: SessionId,
    logsDir: File,
    customToolClasses: Set<kotlin.reflect.KClass<out xyz.block.trailblaze.toolcalls.TrailblazeTool>> = emptySet(),
  ): RecordingResult? {
    try {
      // Plain child join, not LogsRepo.getSessionDir — that would mkdir and defeat this guard.
      val sessionDir = File(logsDir, sessionId.value)
      if (!sessionDir.exists()) {
        Console.log("Session directory not found at ${sessionDir.absolutePath}, skipping recording generation")
        return null
      }

      val logFiles = sessionDir.listFiles()?.filter { it.extension == "json" } ?: emptyList()
      val logs = logFiles.mapNotNull { file ->
        try {
          TrailblazeJsonInstance.decodeFromString<TrailblazeLog>(file.readText())
        } catch (_: Exception) {
          null
        }
      }.sortedBy { it.timestamp }

      if (logs.isEmpty()) {
        Console.log("No logs found for session, skipping recording generation")
        return null
      }

      // Extract session config from the Started status log to enrich the recording
      val startedStatus = logs
        .filterIsInstance<TrailblazeLog.TrailblazeSessionStatusChangeLog>()
        .map { it.sessionStatus }
        .filterIsInstance<xyz.block.trailblaze.logs.model.SessionStatus.Started>()
        .firstOrNull()

      val sessionTrailConfig = startedStatus?.toRecordingTrailConfig()

      // The on-disk intermediate is a unified trail document; the save-back step re-reads it and
      // merges this device's slot. Blank when the session has no device classifier to key the
      // recording on, or when the recording has a shape a trail can't hold — nothing to write.
      // A configuration session's legs are keyed by the configuration NAME (from the Started
      // log), matched exactly — never by the launch device's classifier chain.
      val recordingYaml = logs.generateUnifiedRecordedYaml(
        sessionTrailConfig = sessionTrailConfig,
        customToolClasses = customToolClasses,
        selectedDeviceConfiguration = startedStatus?.selectedDeviceConfiguration,
      )
      if (recordingYaml.isBlank()) {
        // info, not log: no recording artifact is written at all for this session, so the line has
        // to be at least as visible as the "Recording saved" line it stands in for.
        Console.info(
          "No recording written for session ${sessionId.value}: the run produced no unified " +
            "recording (no device classifier, or a shape the trail format can't hold).",
        )
        return null
      }

      // Save to session directory
      val sessionRecordingFile = File(sessionDir, "recording.trail.yaml")
      sessionRecordingFile.writeText(recordingYaml)
      Console.log("Recording saved to: ${sessionRecordingFile.absolutePath}")

      val classifiers = startedStatus?.trailblazeDeviceInfo?.classifiers ?: emptyList()
      val driverType = startedStatus?.trailblazeDeviceInfo?.trailblazeDriverType?.name

      return RecordingResult(
        recordingFile = sessionRecordingFile,
        deviceClassifiers = classifiers,
        driverType = driverType,
        selectedDeviceConfiguration = startedStatus?.selectedDeviceConfiguration,
      )
    } catch (e: Exception) {
      Console.log("Failed to generate recording: ${e.message}")
      return null
    }
  }
}
