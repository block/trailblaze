package xyz.block.trailblaze.host

import java.io.File
import java.util.UUID
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
import xyz.block.trailblaze.compose.driver.ComposeTrailblazeAgent
import xyz.block.trailblaze.compose.driver.rpc.ComposeRpcClient
import xyz.block.trailblaze.compose.driver.rpc.ComposeRpcTrailblazeAgent
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.exception.TrailblazeException
import xyz.block.trailblaze.exception.TrailblazeSessionCancelledException
import xyz.block.trailblaze.host.golden.SnapshotGoldenComparison
import xyz.block.trailblaze.host.ios.MobileDeviceUtils
import xyz.block.trailblaze.host.revyl.RevylTrailblazeAgent
import xyz.block.trailblaze.revyl.RevylCliClient
import xyz.block.trailblaze.revyl.tools.RevylToolSetIds
import xyz.block.trailblaze.revyl.RevylScreenState
import xyz.block.trailblaze.revyl.RevylSession
import xyz.block.trailblaze.host.rules.BaseComposeTest
import xyz.block.trailblaze.host.rules.BaseHostTrailblazeTest
import xyz.block.trailblaze.host.rules.BasePlaywrightElectronTest
import xyz.block.trailblaze.host.rules.BasePlaywrightNativeTest
import xyz.block.trailblaze.host.rules.HostTrailblazeLoggingRule
import xyz.block.trailblaze.host.yaml.RunOnHostParams
import xyz.block.trailblaze.http.DynamicLlmClient
import xyz.block.trailblaze.llm.RunYamlRequest
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.llm.TrailblazeReferrer
import xyz.block.trailblaze.playwright.PlaywrightPageManager
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.mcp.AgentImplementation
import xyz.block.trailblaze.mcp.agent.KoogTestAgentRunner
import xyz.block.trailblaze.api.TestAgentRunner
import xyz.block.trailblaze.mcp.android.ondevice.rpc.GetScreenStateRequest
import xyz.block.trailblaze.mcp.android.ondevice.rpc.OnDeviceRpcClient
import xyz.block.trailblaze.mcp.android.ondevice.rpc.RpcResult
import xyz.block.trailblaze.cli.CliConfigHelper
import xyz.block.trailblaze.config.InlineScriptToolConfig
import xyz.block.trailblaze.config.ScriptedToolRuntime
import xyz.block.trailblaze.mcp.sampling.LocalLlmSamplingSource
import xyz.block.trailblaze.compose.driver.tools.ComposeToolSetIds
import xyz.block.trailblaze.model.TrailblazeConfig
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.playwright.tools.WebToolSetIds
import xyz.block.trailblaze.report.utils.TrailblazeYamlSessionRecording.generateRecordedYaml
import xyz.block.trailblaze.yaml.toRecordingTrailConfig
import xyz.block.trailblaze.rules.TrailblazeLoggingRule
import xyz.block.trailblaze.rules.TrailblazeRunnerUtil
import xyz.block.trailblaze.scripting.HostScriptedToolLauncher
import xyz.block.trailblaze.scripting.LaunchedScriptingRuntime
import xyz.block.trailblaze.llm.config.TrailblazeConfigPaths
import xyz.block.trailblaze.toolcalls.EmptyTrailblazeToolSurface
import xyz.block.trailblaze.toolcalls.ToolName
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeKoogTool.Companion.toTrailblazeToolDescriptor
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.toolcalls.getExcludedToolSurfaceForDriver
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.TrailblazeToolSet
import xyz.block.trailblaze.toolcalls.TrailblazeToolSetCatalog
import xyz.block.trailblaze.tracing.TrailblazeTraceExporter
import xyz.block.trailblaze.ui.TrailblazeDesktopUtil
import xyz.block.trailblaze.ui.TrailblazeDeviceManager
import xyz.block.trailblaze.recordings.TrailRecordings
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.util.GitUtils
import xyz.block.trailblaze.util.HostAndroidDeviceConnectUtils
import xyz.block.trailblaze.yaml.ElectronAppConfig
import xyz.block.trailblaze.yaml.TrailYamlItem
import xyz.block.trailblaze.yaml.createTrailblazeYaml

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
   * How [runPlaywrightNativeYaml] should treat the cached Playwright-native test
   * for a given device when a new run-yaml request arrives. Sealed so the three
   * states are exhaustive at the call site and the impossible "reuse the test
   * AND give back its browser" combination can't be constructed.
   *
   * See [resolvePlaywrightCacheReuse] for the decision logic.
   */
  internal sealed interface PlaywrightCacheResolution {
    /** Nothing cached — construct a fresh test around a fresh browser. */
    data object NoCachedTest : PlaywrightCacheResolution

    /** Cached test's model matches the request — use it as-is. */
    data object ReuseCachedTest : PlaywrightCacheResolution

    /**
     * Cached test's model doesn't match the request (e.g. user ran
     * `trailblaze config llm <provider>` after the daemon cached the initial
     * test). Discard the test but keep [browser] alive so URL / cookies /
     * in-flight forms survive the rebuild.
     */
    data class RebuildWithCachedBrowser(val browser: PlaywrightPageManager) :
      PlaywrightCacheResolution
  }

  /**
   * Decides how to handle a cached [BasePlaywrightNativeTest] for an incoming
   * run-yaml request. Pure function — no side effects, exhaustively covered by
   * `PlaywrightCacheReuseTest`.
   */
  internal fun resolvePlaywrightCacheReuse(
    cachedModel: TrailblazeLlmModel?,
    cachedBrowserManager: PlaywrightPageManager?,
    cachedMaxLlmCalls: Int?,
    requestedModel: TrailblazeLlmModel,
    requestedMaxLlmCalls: Int?,
  ): PlaywrightCacheResolution = when {
    cachedModel == null -> PlaywrightCacheResolution.NoCachedTest
    cachedModel == requestedModel && cachedMaxLlmCalls == requestedMaxLlmCalls ->
      PlaywrightCacheResolution.ReuseCachedTest
    cachedBrowserManager != null ->
      // Either the model OR the max-llm-calls cap changed. Both are baked into the lazy
      // TrailblazeRunner inside the cached test, so the test instance has to be rebuilt;
      // we keep the cached browser to avoid relaunching Chromium every time.
      PlaywrightCacheResolution.RebuildWithCachedBrowser(cachedBrowserManager)
    // Defensive: cached model exists but no browser to reuse — treat as no cache.
    // In practice cachedBrowserManager is always non-null when cachedModel is, but
    // pinning this branch keeps the function total instead of relying on caller invariants.
    else -> PlaywrightCacheResolution.NoCachedTest
  }

  /**
   * Resolved Playwright tool classes used for recording generation. Called from both the Native
   * and Electron paths; each passes its own driver type so the resolution is explicit at the
   * call site. Today the two drivers resolve to identical classes (pinned by
   * `WebToolSetCatalogTest`), but the parameter keeps this correct if the YAMLs ever diverge.
   */
  private fun resolveWebToolClasses(driverType: TrailblazeDriverType) = TrailblazeToolSetCatalog
    .resolveForDriver(driverType, WebToolSetIds.ALL)
    .toolClasses

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
    loggingRule: TrailblazeLoggingRule,
    noLogging: Boolean = false,
  ) {
    if (noLogging) return
    withContext(kotlinx.coroutines.NonCancellable) {
      TrailblazeTraceExporter.exportAndSave(
        sessionId = sessionId,
        client = loggingRule.trailblazeLogServerClient,
        isServerAvailable = true, // Host runner always has a server running
        writeToDisk = { traceJson ->
          val gitRoot = GitUtils.getGitRootViaCommand()
          val logsDir = if (gitRoot != null) File(gitRoot, "logs")
            else File(TrailblazeDesktopUtil.getDefaultAppDataDirectory(), "logs")
          val sessionDir = File(logsDir, sessionId.value)
          sessionDir.mkdirs()
          File(sessionDir, "trace.json").writeText(traceJson)
        },
      )
    }
  }

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
  private suspend fun launchSubprocessMcpServersIfAny(
    targetTestApp: TrailblazeHostAppTarget?,
    config: TrailblazeConfig,
    sessionId: SessionId,
    deviceInfo: TrailblazeDeviceInfo,
    logsRepo: xyz.block.trailblaze.report.utils.LogsRepo,
    toolRepo: TrailblazeToolRepo,
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
  private suspend fun executeTrailSession(
    loggingRule: TrailblazeLoggingRule,
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

    return try {
      execute(session)
    } catch (e: TrailblazeSessionCancelledException) {
      Console.log("🚫 Session cancelled for $deviceLabel")
      onProgressMessage("Test session cancelled")
      // Re-throw so DesktopYamlRunner.runYaml sees the cancel rather than a
      // silent null return that the outer layer would interpret as Success.
      // TrailblazeSessionCancelledException extends Exception (not
      // CancellationException), so DesktopYamlRunner catches it explicitly
      // and sets TrailExecutionResult.Cancelled.
      throw e
    } catch (e: CancellationException) {
      Console.log("🚫 Coroutine cancelled for $deviceLabel: ${e.message}")
      onProgressMessage("Test execution cancelled")
      throw e
    } catch (e: Exception) {
      Console.log("❌ ${e::class.simpleName} in $deviceLabel: ${e.message}")
      onProgressMessage("Test execution failed: ${e.message}")
      loggingRule.captureFailureScreenshot(session, screenshotProvider)
      if (sendSessionEndLog) {
        sessionManager.endSession(session, isSuccess = false, exception = e)
      }
      // Re-throw so the failure propagates to DesktopYamlRunner.runYaml's outer catch,
      // which sets executionResult = Failed. Returning null here was the silent-failure
      // bug uncovered while debugging a cached-LLM-model issue: a thrown
      // IllegalStateException inside a Playwright run got swallowed here, the runner
      // saw null and reported Success up the stack, and MCP told the user "✓ Done"
      // while the page was actually blank.
      throw e
    } finally {
      exportAndSaveTrace(session.sessionId, loggingRule, noLogging = noLogging)
      loggingRule.setSession(null)
      cleanup()
    }
  }

  /**
   * Runs a Trailblaze YAML test on a specific host-connected device with the given LLM client.
   *
   * Returns a [HostYamlRunResult] so the local-device Maestro path (iOS_HOST / Android HOST) can
   * thread the last successful tool's result up to `DesktopYamlRunner` — that's what lets
   * `trailblaze tool <read-tool>` show the tool's real return value. The web / Compose / Revyl
   * branches carry a null `lastToolResult`: they surface their payloads to the CLI through their
   * own dispatch branches, not this one, so wrapping their session id is enough.
   */
  suspend fun runHostYaml(
    dynamicLlmClient: DynamicLlmClient,
    runOnHostParams: RunOnHostParams,
    deviceManager: TrailblazeDeviceManager,
  ): HostYamlRunResult {
    return when (runOnHostParams.trailblazeDriverType) {
      TrailblazeDriverType.PLAYWRIGHT_NATIVE ->
        HostYamlRunResult(runPlaywrightNativeYaml(dynamicLlmClient, runOnHostParams, deviceManager))
      TrailblazeDriverType.PLAYWRIGHT_ELECTRON ->
        HostYamlRunResult(runPlaywrightElectronYaml(dynamicLlmClient, runOnHostParams, deviceManager))
      TrailblazeDriverType.COMPOSE ->
        HostYamlRunResult(runComposeYaml(dynamicLlmClient, runOnHostParams, deviceManager))
      TrailblazeDriverType.REVYL_ANDROID,
      TrailblazeDriverType.REVYL_IOS ->
        HostYamlRunResult(runRevylYaml(dynamicLlmClient, runOnHostParams, deviceManager))
      else ->
        runMaestroHostYaml(dynamicLlmClient, runOnHostParams, deviceManager)
    }
  }

  /**
   * Playwright-native path: launches a browser via [PlaywrightBrowserManager] and runs
   * the trail using [PlaywrightTrailblazeAgent] with web-native tools.
   *
   * When `sendSessionEndLog` is false (e.g. MCP interactive authoring), the browser is kept
   * alive between calls by caching the [BasePlaywrightNativeTest] instance in the device manager.
   * This mirrors the Maestro path's session reuse behaviour.
   */
  private suspend fun runPlaywrightNativeYaml(
    dynamicLlmClient: DynamicLlmClient,
    runOnHostParams: RunOnHostParams,
    deviceManager: TrailblazeDeviceManager,
  ): SessionId? {
    val onProgressMessage = runOnHostParams.onProgressMessage
    val runYamlRequest = runOnHostParams.runYamlRequest

    // Mirror the V1/Compose-RPC/Revyl runners — Playwright Native doesn't apply memory
    // seeding either, so loudly warn rather than silently dropping `config.memory:` /
    // `--memory` / `--secret`. Parses the YAML to extract `config.memory:` for an accurate
    // count; cheap one-off parse, same call the other runners make.
    val playwrightTrailConfig =
      runCatching { createTrailblazeYaml().extractTrailConfig(runYamlRequest.yaml) }.getOrNull()
    warnIfMemorySeedsDropped(
      runnerName = "Playwright Native runner",
      trailConfig = playwrightTrailConfig,
      runYamlRequest = runYamlRequest,
    )

    val requestDeviceId = runYamlRequest.trailblazeDeviceId
    val keepBrowserAlive = !runYamlRequest.config.sendSessionEndLog

    // Try to reuse a cached Playwright test instance (only when keeping browser alive).
    // If the cached test was constructed for a different LLM model than this request
    // (e.g. user ran `trailblaze config llm <provider>` after the daemon cached the
    // initial test), we evict it but keep the live browser — otherwise the cached
    // model/client sticks around for the daemon's lifetime and silently runs every
    // web tool with the wrong provider.
    val cachedTest =
      if (keepBrowserAlive) deviceManager.getActivePlaywrightNativeTest(requestDeviceId) else null
    val cacheResolution = resolvePlaywrightCacheReuse(
      cachedModel = cachedTest?.trailblazeLlmModel,
      cachedBrowserManager = cachedTest?.browserManager,
      cachedMaxLlmCalls = cachedTest?.maxLlmCalls,
      requestedModel = runYamlRequest.trailblazeLlmModel,
      requestedMaxLlmCalls = runYamlRequest.maxLlmCalls,
    )
    val existingTest =
      if (cacheResolution is PlaywrightCacheResolution.ReuseCachedTest) cachedTest else null
    val staleBrowserToReuse =
      (cacheResolution as? PlaywrightCacheResolution.RebuildWithCachedBrowser)?.browser
    val isReusingTest = existingTest != null
    val isRebuildingForModelChange = staleBrowserToReuse != null

    // Stable device ID when reusing the same test or rebuilding-with-existing-browser
    // (same logical session — only the LLM client is being swapped); unique suffix only
    // for genuinely fresh test runs.
    val trailblazeDeviceId =
      if (isReusingTest || isRebuildingForModelChange) {
        requestDeviceId
      } else {
        val sessionSuffix = UUID.randomUUID().toString().take(8)
        TrailblazeDeviceId(
          instanceId = "playwright-native-$sessionSuffix",
          trailblazeDevicePlatform = TrailblazeDevicePlatform.WEB,
        )
      }

    onProgressMessage(
      when {
        isReusingTest -> "Reusing Playwright-native browser session..."
        staleBrowserToReuse != null ->
          "LLM config changed — rebuilding Playwright-native test with current model..."
        else -> "Initializing Playwright-native test runner..."
      }
    )

    // If the request targets a web slot that already has a running browser
    // (provisioned via `device create web` or the desktop UI's Launch Browser),
    // reuse it as the rule's `existingBrowserManager`. Without this, the runner
    // would spin up a SECOND PlaywrightBrowserManager — bypassing the slot's
    // configured viewport / emulation profile and producing trail runs at the
    // default 1280x800. The cache-reuse path's `staleBrowserToReuse` covers the
    // intra-daemon model-change case; this covers the cross-command case.
    val adoptedSlotBrowser: PlaywrightPageManager? = if (staleBrowserToReuse == null) {
      deviceManager.webBrowserManager.getPageManager(requestDeviceId.instanceId)
    } else null
    val existingBrowserForRule = staleBrowserToReuse ?: adoptedSlotBrowser

    val playwrightTest = existingTest ?: BasePlaywrightNativeTest(
      customToolClasses = runOnHostParams.targetTestApp
        ?.getCustomToolsForDriver(runOnHostParams.trailblazeDriverType) ?: emptySet(),
      dynamicLlmClient = dynamicLlmClient,
      trailblazeLlmModel = runYamlRequest.trailblazeLlmModel,
      config = runYamlRequest.config,
      appTarget = runOnHostParams.targetTestApp,
      trailblazeDeviceId = trailblazeDeviceId,
      existingBrowserManager = existingBrowserForRule,
      maxLlmCalls = runYamlRequest.maxLlmCalls,
      // Capture publishes its per-session video-record dir under the un-suffixed request
      // device id; the manager must look it up under the same key (not the per-trail
      // suffixed `trailblazeDeviceId.instanceId` we use for session-cache identity).
      webBrowserRecordingKey = requestDeviceId.instanceId,
    )

    // Reset the browser session only when starting a new Trailblaze session.
    // In interactive blaze() mode each call is one step within the same session
    // (sendSessionStartLog=false), so we must NOT reset between steps — that would
    // navigate to about:blank and lose the current page state.
    // Must run on the Playwright thread to maintain thread affinity.
    if (isReusingTest && runYamlRequest.config.sendSessionStartLog) {
      withContext(playwrightTest.browserManager.playwrightDispatcher) {
        playwrightTest.browserManager.resetSession()
      }
    }

    // Cache the test instance for reuse across subsequent MCP calls
    if (keepBrowserAlive) {
      deviceManager.setActivePlaywrightNativeTest(requestDeviceId, playwrightTest)
    }

    onProgressMessage("Launching browser...")

    val subprocessRuntimes = mutableListOf<LaunchedScriptingRuntime>()
    return executeTrailSession(
      loggingRule = playwrightTest.loggingRule,
      overrideSessionId = runYamlRequest.config.overrideSessionId,
      testName = runYamlRequest.testName,
      deviceLabel = "playwright-native:${trailblazeDeviceId.instanceId}",
      sendSessionEndLog = runYamlRequest.config.sendSessionEndLog,
      onProgressMessage = onProgressMessage,
      screenshotProvider = playwrightTest.browserManager::getScreenState,
      noLogging = runOnHostParams.noLogging,
      cleanup = {
        withContext(NonCancellable) {
          subprocessRuntimes.forEach { it.shutdownAll() }
        }
        if (!keepBrowserAlive) {
          playwrightTest.close()
          deviceManager.cancelSessionForDevice(trailblazeDeviceId)
        }
      },
    ) { session ->
      launchSubprocessMcpServersIfAny(
        targetTestApp = runOnHostParams.targetTestApp,
        config = runYamlRequest.config,
        sessionId = session.sessionId,
        deviceInfo = playwrightTest.trailblazeDeviceInfo,
        logsRepo = playwrightTest.loggingRule.logsRepo,
        toolRepo = playwrightTest.toolRepo,
        onProgressMessage = onProgressMessage,
      )?.let { subprocessRuntimes += it }
      onProgressMessage("Executing YAML test...")
      Console.log("▶️ Starting Playwright-native runTrailblazeYamlSuspend for device: ${trailblazeDeviceId.instanceId}")
      val sessionId = playwrightTest.runTrailblazeYamlSuspend(
        yaml = runYamlRequest.yaml,
        trailFilePath = runYamlRequest.trailFilePath,
        trailblazeDeviceId = trailblazeDeviceId,
        traceId = runYamlRequest.traceId,
        useRecordedSteps = runYamlRequest.useRecordedSteps,
        sendSessionStartLog = runYamlRequest.config.sendSessionStartLog,
        // Routes prompt steps through the in-process Koog strategy-graph agent when the run
        // opted in (AgentImplementation.KOOG_STRATEGY_GRAPH); otherwise the legacy runner.
        agentImplementation = runYamlRequest.agentImplementation,
        onStepProgress = { step, total, text ->
          onProgressMessage("Step $step/$total: $text")
        },
      )
      Console.log("✅ Playwright-native runTrailblazeYamlSuspend completed for device: ${trailblazeDeviceId.instanceId}")
      onProgressMessage("Test execution completed successfully")

      if (runYamlRequest.config.sendSessionEndLog) {
        playwrightTest.loggingRule.captureFinalScreenshot(session, playwrightTest.browserManager::getScreenState)
        playwrightTest.loggingRule.sessionManager.endSession(session, isSuccess = true)
      }

      val customToolClasses = runOnHostParams.targetTestApp
        ?.getCustomToolsForDriver(runOnHostParams.trailblazeDriverType) ?: emptySet()
      generateAndSaveRecording(
        sessionId = sessionId,
        customToolClasses = resolveWebToolClasses(TrailblazeDriverType.PLAYWRIGHT_NATIVE) + customToolClasses,
      )

      sessionId
    }
  }

  /**
   * Playwright-electron path: connects to an Electron app via CDP and runs the trail
   * using [PlaywrightTrailblazeAgent] with web-native tools.
   *
   * Electron app configuration is resolved from:
   * 1. Trail YAML `config.electron` block
   * 2. Env vars (`TRAILBLAZE_ELECTRON_CDP_URL`, `TRAILBLAZE_ELECTRON_COMMAND`) as fallback
   */
  private suspend fun runPlaywrightElectronYaml(
    dynamicLlmClient: DynamicLlmClient,
    runOnHostParams: RunOnHostParams,
    deviceManager: TrailblazeDeviceManager,
  ): SessionId? {
    val onProgressMessage = runOnHostParams.onProgressMessage
    val runYamlRequest = runOnHostParams.runYamlRequest

    // See parallel comment in runPlaywrightNativeYaml.
    val electronTrailConfig =
      runCatching { createTrailblazeYaml().extractTrailConfig(runYamlRequest.yaml) }.getOrNull()
    warnIfMemorySeedsDropped(
      runnerName = "Playwright Electron runner",
      trailConfig = electronTrailConfig,
      runYamlRequest = runYamlRequest,
    )

    val requestDeviceId = runYamlRequest.trailblazeDeviceId
    val keepAlive = !runYamlRequest.config.sendSessionEndLog

    val existingTest =
      if (keepAlive) deviceManager.getActivePlaywrightElectronTest(requestDeviceId) else null
    val isReusingTest = existingTest != null

    val trailblazeDeviceId =
      if (isReusingTest) {
        requestDeviceId
      } else {
        val sessionSuffix = UUID.randomUUID().toString().take(8)
        TrailblazeDeviceId(
          instanceId = "playwright-electron-$sessionSuffix",
          trailblazeDevicePlatform = TrailblazeDevicePlatform.WEB,
        )
      }

    onProgressMessage(
      if (isReusingTest) "Reusing Playwright-electron session..."
      else "Initializing Playwright-electron test runner..."
    )

    // Resolve ElectronAppConfig from trail YAML or environment variables
    val electronConfig = resolveElectronAppConfig(runYamlRequest.yaml)

    val electronTest = existingTest ?: BasePlaywrightElectronTest(
      electronAppConfig = electronConfig,
      customToolClasses = runOnHostParams.targetTestApp
        ?.getCustomToolsForDriver(runOnHostParams.trailblazeDriverType) ?: emptySet(),
      dynamicLlmClient = dynamicLlmClient,
      trailblazeLlmModel = runYamlRequest.trailblazeLlmModel,
      config = runYamlRequest.config,
      appTarget = runOnHostParams.targetTestApp,
      trailblazeDeviceId = trailblazeDeviceId,
      maxLlmCalls = runYamlRequest.maxLlmCalls,
    )

    if (isReusingTest) {
      withContext(electronTest.browserManager.playwrightDispatcher) {
        electronTest.browserManager.resetSession()
      }
    }

    if (keepAlive) {
      deviceManager.setActivePlaywrightElectronTest(requestDeviceId, electronTest)
    }

    onProgressMessage("Connecting to Electron app...")

    val subprocessRuntimes = mutableListOf<LaunchedScriptingRuntime>()
    return executeTrailSession(
      loggingRule = electronTest.loggingRule,
      overrideSessionId = runYamlRequest.config.overrideSessionId,
      testName = runYamlRequest.testName,
      deviceLabel = "playwright-electron:${trailblazeDeviceId.instanceId}",
      sendSessionEndLog = runYamlRequest.config.sendSessionEndLog,
      onProgressMessage = onProgressMessage,
      screenshotProvider = electronTest.browserManager::getScreenState,
      noLogging = runOnHostParams.noLogging,
      cleanup = {
        withContext(NonCancellable) {
          subprocessRuntimes.forEach { it.shutdownAll() }
        }
        if (!keepAlive) {
          electronTest.close()
          deviceManager.cancelSessionForDevice(trailblazeDeviceId)
        }
      },
    ) { session ->
      launchSubprocessMcpServersIfAny(
        targetTestApp = runOnHostParams.targetTestApp,
        config = runYamlRequest.config,
        sessionId = session.sessionId,
        deviceInfo = electronTest.trailblazeDeviceInfo,
        logsRepo = electronTest.loggingRule.logsRepo,
        toolRepo = electronTest.toolRepo,
        onProgressMessage = onProgressMessage,
      )?.let { subprocessRuntimes += it }
      onProgressMessage("Executing YAML test...")
      Console.log("▶️ Starting Playwright-electron runTrailblazeYamlSuspend for device: ${trailblazeDeviceId.instanceId}")
      val sessionId = electronTest.runTrailblazeYamlSuspend(
        yaml = runYamlRequest.yaml,
        trailFilePath = runYamlRequest.trailFilePath,
        trailblazeDeviceId = trailblazeDeviceId,
        traceId = runYamlRequest.traceId,
        useRecordedSteps = runYamlRequest.useRecordedSteps,
        sendSessionStartLog = runYamlRequest.config.sendSessionStartLog,
        agentImplementation = runYamlRequest.agentImplementation,
        onStepProgress = { step, total, text ->
          onProgressMessage("Step $step/$total: $text")
        },
      )
      Console.log("✅ Playwright-electron runTrailblazeYamlSuspend completed for device: ${trailblazeDeviceId.instanceId}")
      onProgressMessage("Test execution completed successfully")

      if (runYamlRequest.config.sendSessionEndLog) {
        electronTest.loggingRule.captureFinalScreenshot(session, electronTest.browserManager::getScreenState)
        electronTest.loggingRule.sessionManager.endSession(session, isSuccess = true)
      }

      val customToolClasses = runOnHostParams.targetTestApp
        ?.getCustomToolsForDriver(runOnHostParams.trailblazeDriverType) ?: emptySet()
      generateAndSaveRecording(
        sessionId = sessionId,
        customToolClasses = resolveWebToolClasses(TrailblazeDriverType.PLAYWRIGHT_ELECTRON) +
          BasePlaywrightElectronTest.ELECTRON_BUILT_IN_TOOL_CLASSES + customToolClasses,
      )

      sessionId
    }
  }

  /**
   * Resolves [ElectronAppConfig] from the trail YAML config block, falling back to
   * environment variables if not specified in the YAML.
   */
  private fun resolveElectronAppConfig(yaml: String): ElectronAppConfig {
    val trailConfig = try {
      createTrailblazeYaml().extractTrailConfig(yaml)
    } catch (_: Exception) {
      null
    }

    // If the trail YAML has an electron config block, use it
    trailConfig?.electron?.let { return it }

    // Fall back to environment variables
    val cdpUrl = System.getenv("TRAILBLAZE_ELECTRON_CDP_URL")
    val command = System.getenv("TRAILBLAZE_ELECTRON_COMMAND")
    val args = System.getenv("TRAILBLAZE_ELECTRON_ARGS")
      ?.split(" ")
      ?.filter { it.isNotBlank() }
      ?: emptyList()
    val cdpPort = System.getenv("TRAILBLAZE_ELECTRON_CDP_PORT")?.toIntOrNull() ?: 9222
    val headless = System.getenv("TRAILBLAZE_ELECTRON_HEADLESS")?.toBoolean() ?: false

    return ElectronAppConfig(
      command = command,
      args = args,
      cdpUrl = cdpUrl,
      cdpPort = cdpPort,
      headless = headless,
    )
  }

  /**
   * Compose RPC path: connects to a running Compose app via [ComposeRpcClient] and runs
   * the trail using [ComposeRpcTrailblazeAgent] with Compose-native tools.
   *
   * The Compose app must already be running with an embedded [ComposeRpcServer] on the
   * configured port. No device discovery or instrumentation is needed — the CLI connects
   * directly over HTTP.
   */
  private suspend fun runComposeYaml(
    dynamicLlmClient: DynamicLlmClient,
    runOnHostParams: RunOnHostParams,
    deviceManager: TrailblazeDeviceManager,
  ): SessionId? {
    val onProgressMessage = runOnHostParams.onProgressMessage
    val runYamlRequest = runOnHostParams.runYamlRequest
    val port = runOnHostParams.composeRpcPort

    // Use the request's device ID so it matches the coroutine scope registered by
    // DesktopYamlRunner. Creating a new ID here would cause cancelSessionForDevice()
    // to miss the coroutine scope, making the cancel button ineffective.
    val trailblazeDeviceId = runYamlRequest.trailblazeDeviceId

    onProgressMessage("Connecting to Compose app on port $port...")

    val rpcClient = ComposeRpcClient("http://localhost:$port")

    // Wait for the Compose app's RPC server to be ready
    val serverReady = rpcClient.waitForServer(maxAttempts = 15, delayMs = 500)
    if (!serverReady) {
      onProgressMessage("Failed to connect to Compose app on port $port")
      rpcClient.close()
      throw TrailblazeException(
        "Could not connect to Compose RPC server on port $port. " +
          "Ensure your Compose app is running with ComposeRpcServer embedded."
      )
    }

    onProgressMessage("Connected to Compose RPC server")

    val viewportWidth = ComposeTrailblazeAgent.DEFAULT_VIEWPORT_WIDTH
    val viewportHeight = ComposeTrailblazeAgent.DEFAULT_VIEWPORT_HEIGHT

    val trailblazeDeviceInfo = TrailblazeDeviceInfo(
      trailblazeDeviceId = trailblazeDeviceId,
      trailblazeDriverType = TrailblazeDriverType.COMPOSE,
      widthPixels = viewportWidth,
      heightPixels = viewportHeight,
      classifiers = listOf(TrailblazeDeviceClassifier("desktop"), TrailblazeDeviceClassifier("compose")),
    )

    val composeToolSet = TrailblazeToolSetCatalog.resolveForDriver(
      driverType = TrailblazeDriverType.COMPOSE,
      requestedIds = ComposeToolSetIds.ALL,
    )
    val toolRepo = TrailblazeToolRepo(
      TrailblazeToolSet.DynamicTrailblazeToolSet(
        name = "Compose RPC Tool Set",
        toolClasses = composeToolSet.toolClasses,
        yamlToolNames = composeToolSet.yamlToolNames,
      ),
      // Bind the repo to the Compose driver so the KOOG tool surface matches it: COMPOSE is not in
      // KOOG_INSPECTION_DRIVERS, so the generic `requestDetailedViewHierarchy` inspection tool (which
      // a null-driver repo injects) stays off the surface — Compose's own `compose_request_details`
      // is the detail tool. KOOG-path only; the default runner's surface is unaffected.
      driverType = TrailblazeDriverType.COMPOSE,
    )

    // Wrap agent creation in try-catch so rpcClient is closed if setup fails before
    // the agent (which owns the client lifecycle) is constructed.
    val agent: ComposeRpcTrailblazeAgent
    val loggingRule: HostTrailblazeLoggingRule
    try {
      loggingRule = HostTrailblazeLoggingRule(
        trailblazeDeviceInfoProvider = { trailblazeDeviceInfo },
        noLogging = runOnHostParams.noLogging,
      )

      agent = ComposeRpcTrailblazeAgent(
        rpcClient = rpcClient,
        trailblazeLogger = loggingRule.logger,
        sessionProvider = {
          loggingRule.session ?: error("Session not available - ensure test is running")
        },
        trailblazeDeviceInfoProvider = { trailblazeDeviceInfo },
        // Thread the session tool repo through so framework-tool composition resolves by name and
        // so the KOOG strategy graph's dynamic-tool execution context is satisfied.
        trailblazeToolRepo = toolRepo,
      )
    } catch (e: Exception) {
      rpcClient.close()
      throw e
    }

    val screenStateProvider = agent.screenStateProvider

    val elementComparator = TrailblazeElementComparator(
      screenStateProvider = screenStateProvider,
      llmClient = dynamicLlmClient.createLlmClient(),
      trailblazeLlmModel = runYamlRequest.trailblazeLlmModel,
      toolRepo = toolRepo,
    )

    // Brain selection (legacy or KOOG). Recordings replay uniformly via the runner-util below
    // regardless of agent — only unrecorded steps reach the selected brain. Mirrors the Revyl /
    // on-device wiring; the default TRAILBLAZE_RUNNER path is unchanged.
    val trailblazeRunner: TestAgentRunner =
      if (runYamlRequest.agentImplementation == AgentImplementation.KOOG_STRATEGY_GRAPH) {
        KoogTestAgentRunner(
          agent = agent,
          toolRepo = toolRepo,
          screenStateProvider = screenStateProvider,
          elementComparator = elementComparator,
          llmClient = dynamicLlmClient.createLlmClient(),
          trailblazeLlmModel = runYamlRequest.trailblazeLlmModel,
          logger = loggingRule.logger,
          sessionProvider = { loggingRule.session ?: error("Session not available - ensure test is running") },
          maxLlmCalls = runYamlRequest.maxLlmCalls,
          // Use the same Compose-desktop system prompt the legacy runner uses (not the generic
          // mobile prompt) so the agent gets the Compose semantics-tree + takeSnapshot guidance.
          systemPromptTemplate = BaseComposeTest.COMPOSE_SYSTEM_PROMPT,
        )
      } else {
        TrailblazeRunner(
          screenStateProvider = screenStateProvider,
          agent = agent,
          llmClient = dynamicLlmClient.createLlmClient(),
          trailblazeLlmModel = runYamlRequest.trailblazeLlmModel,
          trailblazeToolRepo = toolRepo,
          systemPromptTemplate = BaseComposeTest.COMPOSE_SYSTEM_PROMPT,
          trailblazeLogger = loggingRule.logger,
          sessionProvider = {
            loggingRule.session ?: error("Session not available - ensure test is running")
          },
          maxSteps = runYamlRequest.maxLlmCalls ?: TrailblazeRunner.DEFAULT_MAX_STEPS,
        )
      }

    val trailblazeYaml = createTrailblazeYaml(
      customTrailblazeToolClasses = composeToolSet.toolClasses,
    )

    val trailblazeRunnerUtil = TrailblazeRunnerUtil(
      trailblazeRunner = trailblazeRunner,
      runTrailblazeTool = { trailblazeTools: List<TrailblazeTool> ->
        agent.runTrailblazeTools(
          trailblazeTools,
          runYamlRequest.traceId,
          screenState = screenStateProvider(),
          elementComparator = elementComparator,
          screenStateProvider = screenStateProvider,
        ).result
      },
      trailblazeLogger = loggingRule.logger,
      sessionProvider = {
        loggingRule.session ?: error("Session not available - ensure test is running")
      },
      sessionUpdater = { loggingRule.setSession(it) },
      // Shares one execution context + snapshot frame across the recording, matching the
      // batching pattern elsewhere. Unlike the Android/host-Maestro wiring, this agent's
      // buildExecutionContext doesn't cache per-call device state today, so the benefit here
      // is reduced frame/ThreadLocal churn rather than a clipboard-style state-survival fix.
      sharedToolBatch = { block -> agent.runInSharedToolBatch(block) },
    )

    val subprocessRuntimes = mutableListOf<LaunchedScriptingRuntime>()
    return executeTrailSession(
      loggingRule = loggingRule,
      overrideSessionId = runYamlRequest.config.overrideSessionId,
      testName = runYamlRequest.testName,
      deviceLabel = "compose-rpc:${trailblazeDeviceId.instanceId}",
      sendSessionEndLog = runYamlRequest.config.sendSessionEndLog,
      onProgressMessage = onProgressMessage,
      screenshotProvider = screenStateProvider,
      noLogging = runOnHostParams.noLogging,
      cleanup = {
        withContext(NonCancellable) {
          subprocessRuntimes.forEach { it.shutdownAll() }
        }
        agent.close()
        deviceManager.cancelSessionForDevice(trailblazeDeviceId)
      },
    ) { session ->
      launchSubprocessMcpServersIfAny(
        targetTestApp = runOnHostParams.targetTestApp,
        config = runYamlRequest.config,
        sessionId = session.sessionId,
        deviceInfo = trailblazeDeviceInfo,
        logsRepo = loggingRule.logsRepo,
        toolRepo = toolRepo,
        onProgressMessage = onProgressMessage,
      )?.let { subprocessRuntimes += it }
      onProgressMessage("Executing YAML test via Compose RPC...")
      Console.log("▶️ Starting Compose RPC execution for device: ${trailblazeDeviceId.instanceId}")

      val trailItems: List<TrailYamlItem> = trailblazeYaml.decodeTrail(
        runYamlRequest.yaml,
        deviceClassifiers = trailblazeDeviceInfo.classifiers,
      )
      val trailConfig = trailblazeYaml.extractTrailConfig(trailItems)
      warnIfMemorySeedsDropped("ComposeRpc runner", trailConfig, runYamlRequest)

      // Honor `config.skip:` before SessionStarted is logged — matches the CLI's pre-flight
      // `planTrailExecution` planner. Short-circuit here so the runner never opens a session,
      // runs the actionable-steps guard, or iterates trail items for a skip-marked trail.
      trailblazeYaml.firstSkipReason(trailItems)?.let { skipReason ->
        Console.log(
          "[Trailblaze] Skipping trail" +
            (runYamlRequest.trailFilePath?.let { " ($it)" } ?: "") + ": $skipReason"
        )
        return@executeTrailSession session.sessionId
      }

      if (runYamlRequest.config.sendSessionStartLog) {
        // CLI / daemon runs have no JUnit Description, so derive a readable Suite::test
        // identity from the trail path instead of a bare "ComposeRpc::run" (see
        // deriveTestIdentityFromTrailPath). The driver name stays the path-less fallback.
        val derivedTestIdentity = runYamlRequest.trailFilePath?.let {
          TrailRecordings.deriveTestIdentityFromTrailPath(it, fallbackClassName = "ComposeRpc")
        }
        loggingRule.logger.log(
          session,
          TrailblazeLog.TrailblazeSessionStatusChangeLog(
            sessionStatus = SessionStatus.Started(
              // Strip trailConfig.memory: this runner does NOT apply it (see the
              // warnIfMemorySeedsDropped call above), so persisting it into the session
              // log would produce a false-presence artifact for replay tools that read
              // SessionStarted.trailConfig and assume the values were seeded. The
              // separate resolvedInitialMemory field stays empty for the same reason.
              trailConfig = trailConfig?.copy(memory = null),
              trailFilePath = runYamlRequest.trailFilePath,
              testClassName = derivedTestIdentity?.className ?: "ComposeRpc",
              testMethodName = derivedTestIdentity?.methodName ?: "run",
              trailblazeDeviceInfo = trailblazeDeviceInfo,
              rawYaml = runYamlRequest.yaml,
              hasRecordedSteps = trailblazeYaml.hasRecordedSteps(trailItems),
              trailblazeDeviceId = trailblazeDeviceId,
            ),
            session = session.sessionId,
            timestamp = Clock.System.now(),
          ),
        )
      }

      requireActionableSteps(
        trailblazeYaml = trailblazeYaml,
        trailItems = trailItems,
        trailName = trailConfig?.title ?: runYamlRequest.trailFilePath,
        trailUrl = trailConfig?.metadata?.get("testRailUrl"),
      )

      for (item in trailItems) {
        val itemResult = when (item) {
          // Agent-agnostic: replays recorded steps deterministically and delegates only unrecorded
          // steps to the selected runner (legacy / KOOG). ComposeRpcTrailblazeAgent is now a
          // BaseTrailblazeAgent, so the KOOG strategy graph drives it through the same seam as the
          // other drivers. Default unchanged.
          is TrailYamlItem.PromptsTrailItem ->
            trailblazeRunnerUtil.runPromptSuspend(
              prompts = item.promptSteps,
              useRecordedSteps = runYamlRequest.useRecordedSteps,
              selfHeal = runYamlRequest.config.selfHeal,
            )
          is TrailYamlItem.TrailheadTrailItem ->
            trailblazeRunnerUtil.runPromptSuspend(
              prompts = listOf(item.trailhead.toPromptStep()),
              useRecordedSteps = true,
              selfHeal = runYamlRequest.config.selfHeal,
            )
          is TrailYamlItem.ToolTrailItem ->
            trailblazeRunnerUtil.runTrailblazeTool(item.tools.map { it.trailblazeTool })
          is TrailYamlItem.ConfigTrailItem ->
            item.config.context?.let { trailblazeRunner.appendToSystemPrompt(it) }
        }
        if (itemResult is TrailblazeToolResult.Error) {
          throw TrailblazeException(itemResult.errorMessage)
        }
      }

      Console.log("✅ Compose RPC execution completed for device: ${trailblazeDeviceId.instanceId}")
      onProgressMessage("Test execution completed successfully")

      generateAndSaveRecording(
        sessionId = session.sessionId,
        customToolClasses = composeToolSet.toolClasses,
      )

      // Run golden comparison before ending the session so failures are reflected in session status.
      val goldenResult = compareSnapshotsAgainstGoldens(session.sessionId)
      val goldenPassed = goldenResult?.passed != false

      if (runYamlRequest.config.sendSessionEndLog) {
        if (goldenPassed) {
          loggingRule.captureFinalScreenshot(session, screenStateProvider)
        } else {
          loggingRule.captureFailureScreenshot(session, screenStateProvider)
        }
        loggingRule.sessionManager.endSession(session, isSuccess = goldenPassed)
      }

      if (!goldenPassed) {
        val failures = goldenResult!!.results.filter { it.goldenFound && !it.passed }
        val msg = failures.joinToString("; ") {
          "'${it.snapshotName}' (${"%.2f".format(it.diffPercent)}% diff, threshold ${it.thresholdPercent}%)"
        }
        throw TrailblazeException("Golden snapshot comparison failed: $msg")
      }

      session.sessionId
    }
  }

  /**
   * Revyl cloud device path: provisions a device via [RevylCliClient] and runs
   * the trail using [RevylTrailblazeAgent] with standard mobile tools.
   *
   * The CLI handles device provisioning, app install, and AI-powered target
   * grounding. Screenshots come from [RevylScreenState].
   */
  private suspend fun runRevylYaml(
    dynamicLlmClient: DynamicLlmClient,
    runOnHostParams: RunOnHostParams,
    deviceManager: TrailblazeDeviceManager,
  ): SessionId? {
    val onProgressMessage = runOnHostParams.onProgressMessage
    val runYamlRequest = runOnHostParams.runYamlRequest
    val trailblazeDeviceId = runYamlRequest.trailblazeDeviceId
    val platform = if (runOnHostParams.trailblazeDriverType == TrailblazeDriverType.REVYL_ANDROID) "android" else "ios"

    val instanceId = trailblazeDeviceId.instanceId
    val deviceLabel = if (instanceId.startsWith("revyl-model:"))
      instanceId.removePrefix("revyl-model:") else "$platform (default)"

    if (System.getenv(RevylCliClient.REVYL_API_KEY_ENV).isNullOrBlank()) {
      onProgressMessage("Error: ${RevylCliClient.REVYL_API_KEY_ENV} is not set. Configure it in Settings → Environment Variables.")
      return null
    }

    onProgressMessage("Provisioning Revyl cloud $deviceLabel...")

    val cliClient: RevylCliClient
    val session: RevylSession
    try {
      cliClient = RevylCliClient()
      session = if (instanceId.startsWith("revyl-model:")) {
        val payload = instanceId.removePrefix("revyl-model:")
        val parts = payload.split("::", limit = 2)
        val modelName = parts[0]
        val osVer = parts.getOrNull(1)?.takeIf { it.isNotBlank() }
        if (osVer != null) {
          cliClient.startSession(platform = platform, deviceModel = modelName, osVersion = osVer)
        } else {
          Console.log("RevylYaml: device '$modelName' missing OS version — using platform default")
          cliClient.startSession(platform = platform)
        }
      } else {
        cliClient.startSession(platform = platform)
      }
    } catch (e: Exception) {
      Console.log("Revyl session provisioning failed: ${e::class.simpleName}: ${e.message}")
      onProgressMessage("Error: ${e.message}")
      return null
    }
    onProgressMessage("Revyl $deviceLabel ready — viewer: ${session.viewerUrl}")

    // Store the client for MCP screen state capture
    val isMcpRequest = !runYamlRequest.config.sendSessionEndLog
    if (isMcpRequest) {
      deviceManager.setActiveRevylCliClient(trailblazeDeviceId, cliClient)
    }

    // Outer try-finally guarantees the cloud device is stopped even if setup
    // (e.g. LLM client creation) fails before the inner execution try block.
    try {
      val trailblazeDeviceInfo = TrailblazeDeviceInfo(
        trailblazeDeviceId = trailblazeDeviceId,
        trailblazeDriverType = runOnHostParams.trailblazeDriverType,
        widthPixels = session.screenWidth.takeIf { it > 0 }
          ?: xyz.block.trailblaze.revyl.RevylDefaults.dimensionsForPlatform(platform).first,
        heightPixels = session.screenHeight.takeIf { it > 0 }
          ?: xyz.block.trailblaze.revyl.RevylDefaults.dimensionsForPlatform(platform).second,
        metadata = mapOf("revyl_viewer_url" to session.viewerUrl),
        classifiers = listOf(
          TrailblazeDeviceClassifier(platform),
          TrailblazeDeviceClassifier("revyl-cloud"),
        ),
      )

      val screenStateProvider: () -> ScreenState = {
        RevylScreenState(cliClient, platform, session.screenWidth, session.screenHeight)
      }

      val loggingRule = HostTrailblazeLoggingRule(
        trailblazeDeviceInfoProvider = { trailblazeDeviceInfo },
      )

      val revylToolSet = TrailblazeToolSetCatalog.resolveForDriver(
        driverType = runOnHostParams.trailblazeDriverType,
        requestedIds = RevylToolSetIds.ALL,
      )
      val toolRepo = TrailblazeToolRepo(
        TrailblazeToolSet.DynamicTrailblazeToolSet(
          name = "Revyl Native Tool Set",
          toolClasses = revylToolSet.toolClasses,
          yamlToolNames = revylToolSet.yamlToolNames,
        ),
        // Bind the repo to the Revyl driver so the KOOG verify-step surface scopes to
        // `revyl_verification` (see TrailblazeToolRepo.verifyStepToolDescriptors / VERIFY_SCOPE_DRIVERS).
        // Without this the repo's driverType is null and verify scoping no-ops. Also keeps the
        // generic `requestDetailedViewHierarchy` inspection tool off the Revyl KOOG surface (Revyl
        // is excluded from KOOG_INSPECTION_DRIVERS — its agent can't run that generic tool).
        driverType = runOnHostParams.trailblazeDriverType,
      )

      val agent = RevylTrailblazeAgent(
        cliClient = cliClient,
        platform = platform,
        trailblazeLogger = loggingRule.logger,
        trailblazeDeviceInfoProvider = { trailblazeDeviceInfo },
        sessionProvider = {
          loggingRule.session ?: error("Session not available - ensure test is running")
        },
        trailblazeToolRepo = toolRepo,
      )

      val elementComparator = TrailblazeElementComparator(
        screenStateProvider = screenStateProvider,
        llmClient = dynamicLlmClient.createLlmClient(),
        trailblazeLlmModel = runYamlRequest.trailblazeLlmModel,
        toolRepo = toolRepo,
      )

      // Brain selection (legacy or KOOG). Recordings replay uniformly via the runner-util below
      // regardless of agent — only unrecorded steps reach the selected brain.
      val trailblazeRunner: TestAgentRunner =
        if (runYamlRequest.agentImplementation == AgentImplementation.KOOG_STRATEGY_GRAPH) {
          KoogTestAgentRunner(
            agent = agent,
            toolRepo = toolRepo,
            screenStateProvider = screenStateProvider,
            elementComparator = elementComparator,
            llmClient = dynamicLlmClient.createLlmClient(),
            trailblazeLlmModel = runYamlRequest.trailblazeLlmModel,
            logger = loggingRule.logger,
            sessionProvider = { loggingRule.session ?: error("Session not available - ensure test is running") },
            maxLlmCalls = runYamlRequest.maxLlmCalls,
            systemPromptTemplate = TrailblazeRunner.composeSystemPrompt(),
          )
        } else {
          TrailblazeRunner(
            screenStateProvider = screenStateProvider,
            agent = agent,
            llmClient = dynamicLlmClient.createLlmClient(),
            trailblazeLlmModel = runYamlRequest.trailblazeLlmModel,
            trailblazeToolRepo = toolRepo,
            trailblazeLogger = loggingRule.logger,
            sessionProvider = {
              loggingRule.session ?: error("Session not available - ensure test is running")
            },
            maxSteps = runYamlRequest.maxLlmCalls ?: TrailblazeRunner.DEFAULT_MAX_STEPS,
          )
        }

      val trailblazeYaml = createTrailblazeYaml(
        customTrailblazeToolClasses = revylToolSet.toolClasses,
      )

      val trailblazeRunnerUtil = TrailblazeRunnerUtil(
        trailblazeRunner = trailblazeRunner,
        runTrailblazeTool = { trailblazeTools: List<TrailblazeTool> ->
          agent.runTrailblazeTools(
            trailblazeTools,
            runYamlRequest.traceId,
            screenState = screenStateProvider(),
            elementComparator = elementComparator,
            screenStateProvider = screenStateProvider,
          ).result
        },
        trailblazeLogger = loggingRule.logger,
        sessionProvider = {
          loggingRule.session ?: error("Session not available - ensure test is running")
        },
        sessionUpdater = { loggingRule.setSession(it) },
        // Shares one execution context + snapshot frame across the recording, matching the
        // batching pattern elsewhere. This agent's buildExecutionContext doesn't cache per-call
        // device state today either (Revyl's device state lives in the cloud device, dispatched
        // fresh per tool via cliClient) — the benefit here is reduced frame/ThreadLocal churn,
        // not a clipboard-style state-survival fix.
        sharedToolBatch = { block -> agent.runInSharedToolBatch(block) },
      )

      val subprocessRuntimes = mutableListOf<LaunchedScriptingRuntime>()
      return executeTrailSession(
        loggingRule = loggingRule,
        overrideSessionId = runYamlRequest.config.overrideSessionId,
        testName = runYamlRequest.testName,
        deviceLabel = "revyl:${trailblazeDeviceId.instanceId}",
        sendSessionEndLog = runYamlRequest.config.sendSessionEndLog,
        onProgressMessage = onProgressMessage,
        screenshotProvider = screenStateProvider,
        noLogging = runOnHostParams.noLogging,
        cleanup = {
          withContext(NonCancellable) {
            subprocessRuntimes.forEach { it.shutdownAll() }
          }
          deviceManager.cancelSessionForDevice(trailblazeDeviceId)
        },
      ) { session ->
        launchSubprocessMcpServersIfAny(
          targetTestApp = runOnHostParams.targetTestApp,
          config = runYamlRequest.config,
          sessionId = session.sessionId,
          deviceInfo = trailblazeDeviceInfo,
          logsRepo = loggingRule.logsRepo,
          toolRepo = toolRepo,
          onProgressMessage = onProgressMessage,
        )?.let { subprocessRuntimes += it }
        onProgressMessage("Executing YAML test via Revyl cloud device...")
        Console.log("▶️ Starting Revyl execution for device: ${trailblazeDeviceId.instanceId}")

        val trailItems: List<TrailYamlItem> = trailblazeYaml.decodeTrail(
          runYamlRequest.yaml,
          deviceClassifiers = trailblazeDeviceInfo.classifiers,
        )
        val trailConfig = trailblazeYaml.extractTrailConfig(trailItems)
        warnIfMemorySeedsDropped("Revyl runner", trailConfig, runYamlRequest)

        // Honor `config.skip:` before SessionStarted is logged — matches the CLI's pre-flight
        // `planTrailExecution` planner. See parallel comment at the ComposeRpc site.
        trailblazeYaml.firstSkipReason(trailItems)?.let { skipReason ->
          Console.log(
            "[Trailblaze] Skipping trail" +
              (runYamlRequest.trailFilePath?.let { " ($it)" } ?: "") + ": $skipReason"
          )
          return@executeTrailSession session.sessionId
        }

        if (runYamlRequest.config.sendSessionStartLog) {
          // See ComposeRpc site — derive a readable Suite::test identity from the path.
          val derivedTestIdentity = runYamlRequest.trailFilePath?.let {
            TrailRecordings.deriveTestIdentityFromTrailPath(it, fallbackClassName = "Revyl")
          }
          loggingRule.logger.log(
            session,
            TrailblazeLog.TrailblazeSessionStatusChangeLog(
              sessionStatus = SessionStatus.Started(
                // See parallel comment at the ComposeRpc site — strip trailConfig.memory
                // because Revyl doesn't apply it. Replay would otherwise read a
                // false-presence signal off this snapshot.
                trailConfig = trailConfig?.copy(memory = null),
                trailFilePath = runYamlRequest.trailFilePath,
                testClassName = derivedTestIdentity?.className ?: "Revyl",
                testMethodName = derivedTestIdentity?.methodName ?: "run",
                trailblazeDeviceInfo = trailblazeDeviceInfo,
                rawYaml = runYamlRequest.yaml,
                hasRecordedSteps = trailblazeYaml.hasRecordedSteps(trailItems),
                trailblazeDeviceId = trailblazeDeviceId,
              ),
              session = session.sessionId,
              timestamp = Clock.System.now(),
            ),
          )
        }

        requireActionableSteps(
          trailblazeYaml = trailblazeYaml,
          trailItems = trailItems,
          trailName = trailConfig?.title ?: runYamlRequest.trailFilePath,
          trailUrl = trailConfig?.metadata?.get("testRailUrl"),
        )

        for (item in trailItems) {
          val itemResult = when (item) {
            is TrailYamlItem.PromptsTrailItem ->
              // Agent-agnostic: replays recorded steps deterministically and delegates only
              // unrecorded steps to the selected runner (legacy / KOOG). Default unchanged.
              trailblazeRunnerUtil.runPromptSuspend(
                prompts = item.promptSteps,
                useRecordedSteps = runYamlRequest.useRecordedSteps,
                selfHeal = runYamlRequest.config.selfHeal,
              )
            is TrailYamlItem.TrailheadTrailItem ->
              trailblazeRunnerUtil.runPromptSuspend(
                prompts = listOf(item.trailhead.toPromptStep()),
                useRecordedSteps = true,
                selfHeal = runYamlRequest.config.selfHeal,
              )
            is TrailYamlItem.ToolTrailItem ->
              trailblazeRunnerUtil.runTrailblazeTool(item.tools.map { it.trailblazeTool })
            is TrailYamlItem.ConfigTrailItem ->
              item.config.context?.let { trailblazeRunner.appendToSystemPrompt(it) }
          }
          if (itemResult is TrailblazeToolResult.Error) {
            throw TrailblazeException(itemResult.errorMessage)
          }
        }

        Console.log("✅ Revyl execution completed for device: ${trailblazeDeviceId.instanceId}")
        onProgressMessage("Test execution completed successfully")

        if (runYamlRequest.config.sendSessionEndLog) {
          loggingRule.captureFinalScreenshot(session, screenStateProvider)
          loggingRule.sessionManager.endSession(session, isSuccess = true)
        }

        generateAndSaveRecording(
          sessionId = session.sessionId,
          customToolClasses = revylToolSet.toolClasses,
        )

        session.sessionId
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: TrailblazeSessionCancelledException) {
      // executeTrailSession already logged the cancel and ended the session.
      // Order matters: TSCE extends Exception (not CancellationException), so it
      // must be caught before the generic branch below — same constraint as
      // DesktopYamlRunner.runYaml's catch order.
      throw e
    } catch (e: Exception) {
      Console.log("Revyl setup failed for device: ${trailblazeDeviceId.instanceId} - ${e::class.simpleName}: ${e.message}")
      onProgressMessage("Error: ${e.message}")
      // Re-throw so DesktopYamlRunner.runYaml's outer catch sets executionResult = Failed.
      // Returning null was the silent-failure pattern previously fixed for executeTrailSession.
      throw e
    } finally {
      if (runYamlRequest.config.sendSessionEndLog) {
        deviceManager.removeActiveRevylCliClient(trailblazeDeviceId)
        try { cliClient.stopSession() } catch (_: Exception) { }
      }
    }
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
  ): HostYamlRunResult {

    val trailblazeDeviceId = runOnHostParams.runYamlRequest.trailblazeDeviceId
    val onProgressMessage = runOnHostParams.onProgressMessage

    // See parallel comment in runPlaywrightNativeYaml — Maestro host path also doesn't
    // apply memory seeding today, so warn loudly rather than silently dropping seeds.
    val maestroTrailConfig =
      runCatching { createTrailblazeYaml().extractTrailConfig(runOnHostParams.runYamlRequest.yaml) }
        .getOrNull()
    warnIfMemorySeedsDropped(
      runnerName = "Maestro host runner",
      trailConfig = maestroTrailConfig,
      runYamlRequest = runOnHostParams.runYamlRequest,
    )

    // Skip force-stop for MCP requests - MCP maintains persistent connections
    // between tool calls and we don't want to kill the driver each time.
    val isMcpRequest = runOnHostParams.referrer == TrailblazeReferrer.MCP
    
    if (runOnHostParams.trailblazeDevicePlatform == TrailblazeDevicePlatform.ANDROID && !isMcpRequest) {
      HostAndroidDeviceConnectUtils.forceStopAllAndroidInstrumentationProcesses(
        trailblazeOnDeviceInstrumentationTargetTestApps = deviceManager.availableAppTargets.map { it.getTrailblazeOnDeviceInstrumentationTarget() }
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

    // Store the test instance for forceful shutdown on cancellation
    deviceManager.setActiveDriverForDevice(trailblazeDeviceId, hostTbRunner.hostRunner.loggingDriver)

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
      loggingRule = hostTbRunner.loggingRule,
      overrideSessionId = runYamlRequest.config.overrideSessionId,
      testName = runYamlRequest.testName,
      deviceLabel = "maestro:${trailblazeDeviceId.instanceId}",
      sendSessionEndLog = runYamlRequest.config.sendSessionEndLog,
      onProgressMessage = onProgressMessage,
      screenshotProvider = hostTbRunner.hostRunner.screenStateProvider,
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
      )
      // Surface the last successful tool's payload back out through HostYamlRunResult.
      lastToolResult = yamlRun.lastToolResult
      val sessionId = yamlRun.sessionId
      Console.log("✅ runTrailblazeYamlSuspend completed successfully for device: ${trailblazeDeviceId.instanceId}")
      onProgressMessage("Test execution completed successfully")

      if (runYamlRequest.config.sendSessionEndLog) {
        hostTbRunner.loggingRule.captureFinalScreenshot(session, hostTbRunner.hostRunner.screenStateProvider)
        hostTbRunner.loggingRule.sessionManager.endSession(session, isSuccess = true)
      }

      sessionId?.let {
        generateAndSaveRecording(
          sessionId = it,
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
  ): SessionId? {
    val driverType = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY
    val customToolClasses = targetTestApp
      ?.getCustomToolsForDriver(driverType)
      ?: emptySet()
    // Full `excluded_tools:` surface (class / YAML / scripted) via the central accessor, so this
    // host-runner path drops scripted opt-outs (e.g. `openUrl`) too — not just class-backed ones.
    val excludedSurface = targetTestApp
      ?.getExcludedToolSurfaceForDriver(driverType)
      ?: EmptyTrailblazeToolSurface

    val trailblazeYaml = createTrailblazeYaml(
      customTrailblazeToolClasses = customToolClasses,
    )

    // Query device classifiers up-front so a v3 trail can be lowered with the
    // right closest-wins recording for THIS device. v1 trails ignore the list
    // (they have a single recording per step), so this re-ordering is a no-op
    // for the existing format.
    val classifiers = queryDeviceClassifiers(onDeviceRpc).ifEmpty {
      listOf(TrailblazeDevicePlatform.ANDROID.asTrailblazeDeviceClassifier())
    }

    // Decode trail YAML to extract prompt steps for V3
    val trailItems = try {
      trailblazeYaml.decodeTrail(runYamlRequest.yaml, deviceClassifiers = classifiers)
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
        TrailblazeDeviceInfo(
          trailblazeDeviceId = trailblazeDeviceId,
          trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
          widthPixels = 0,
          heightPixels = 0,
          classifiers = classifiers,
        )
      },
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

    val toolRepo = TrailblazeToolRepo.withDynamicToolSets(
      customToolClasses = customToolClasses,
      excludedToolClasses = excludedSurface.toolClasses,
      excludedYamlToolNames = excludedSurface.yamlToolNames,
      excludedScriptedToolNames = excludedSurface.scriptedToolNames,
      driverType = driverType,
    )

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
          workingDirectory = runYamlRequest.trailFilePath?.let { File(it).parentFile },
        )
      },
      memory = sharedAgentMemory,
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
          val toolYaml = trailblazeYaml.encodeToString(
            listOf(TrailYamlItem.ToolTrailItem(listOf(toolWrapper))),
          )
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
        val sessionManager = loggingRule.sessionManager
        val v3ScreenStateProvider = {
          runBlocking { executor.captureScreenState() } ?: error("No screen state available")
        }
        if (trailSuccess) {
          loggingRule.captureFinalScreenshot(session, v3ScreenStateProvider)
          sessionManager.endSession(session, isSuccess = true)
        } else {
          loggingRule.captureFailureScreenshot(session, v3ScreenStateProvider)
          sessionManager.endSession(
            session,
            isSuccess = false,
            exception = Exception(trailErrorMessage ?: "Trail execution failed"),
          )
        }
      }

      generateAndSaveRecording(
        sessionId = session.sessionId,
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
  ): SessionId? {
    val driverType = runYamlRequest.driverType
      ?: TrailblazeDriverType.DEFAULT_ANDROID
    val customToolClasses = targetTestApp
      ?.getCustomToolsForDriver(driverType)
      ?: emptySet()
    val excludedSurface = targetTestApp?.getExcludedToolSurfaceForDriver(driverType) ?: EmptyTrailblazeToolSurface

    val trailblazeYaml = createTrailblazeYaml(
      customTrailblazeToolClasses = customToolClasses,
    )

    // Query device classifiers up-front so a v3 trail can be lowered with the
    // right closest-wins recording for THIS device. v1 trails ignore the list.
    val classifiers = queryDeviceClassifiers(onDeviceRpc).ifEmpty {
      listOf(TrailblazeDevicePlatform.ANDROID.asTrailblazeDeviceClassifier())
    }

    val trailItems = try {
      trailblazeYaml.decodeTrail(runYamlRequest.yaml, deviceClassifiers = classifiers)
    } catch (e: Exception) {
      Console.log("❌ Failed to decode on-device-RPC trail YAML: ${e::class.simpleName}: ${e.message}")
      onProgressMessage("Failed to decode trail YAML: ${e.message}")
      // Re-throw so DesktopYamlRunner.runYaml's outer catch sets executionResult = Failed.
      // Returning null was the silent-failure pattern previously fixed for executeTrailSession.
      throw e
    }
    val trailConfig = trailblazeYaml.extractTrailConfig(trailItems)
    warnIfMemorySeedsDropped("V1 runHostTrailblazeRunnerWithOnDeviceRpc", trailConfig, runYamlRequest)

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
        TrailblazeDeviceInfo(
          trailblazeDeviceId = trailblazeDeviceId,
          trailblazeDriverType = driverType,
          widthPixels = 0,
          heightPixels = 0,
          classifiers = classifiers,
        )
      },
    )

    val trailblazeLlmModel = runYamlRequest.trailblazeLlmModel
    val llmClient = dynamicLlmClient.createLlmClient()

    val toolRepo = TrailblazeToolRepo.withDynamicToolSets(
      customToolClasses = customToolClasses,
      excludedToolClasses = excludedSurface.toolClasses,
      excludedYamlToolNames = excludedSurface.yamlToolNames,
      excludedScriptedToolNames = excludedSurface.scriptedToolNames,
      driverType = driverType,
    )

    // Pre-resolve the session's target once — mirrors the V3 wiring in
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
      targetTestApp?.let { target ->
        xyz.block.trailblaze.model.ResolvedTarget(target = target, deviceId = trailblazeDeviceId)
      }
    val appIdForSession: String? = resolvedTargetForSession?.let { resolved ->
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
    }

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
    )

    val elementComparator = TrailblazeElementComparator(
      screenStateProvider = agent.screenStateProvider,
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
          agent = agent,
          toolRepo = toolRepo,
          screenStateProvider = agent.screenStateProvider,
          elementComparator = elementComparator,
          llmClient = llmClient,
          trailblazeLlmModel = trailblazeLlmModel,
          logger = loggingRule.logger,
          sessionProvider = { loggingRule.session ?: error("Session not available") },
          maxLlmCalls = runYamlRequest.maxLlmCalls,
          systemPromptTemplate = TrailblazeRunner.composeSystemPrompt(),
        )
      } else {
        TrailblazeRunner(
          agent = agent,
          screenStateProvider = agent.screenStateProvider,
          llmClient = llmClient,
          trailblazeLlmModel = trailblazeLlmModel,
          trailblazeToolRepo = toolRepo,
          trailblazeLogger = loggingRule.logger,
          sessionProvider = { loggingRule.session ?: error("Session not available") },
          maxSteps = runYamlRequest.maxLlmCalls ?: TrailblazeRunner.DEFAULT_MAX_STEPS,
        )
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
          // wrapped in runBlocking) so single-thread dispatchers don't deadlock.
          val screen = agent.captureScreenState()
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
          val screen = agent.captureScreenState()
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
        agent.runTrailblazeTools(
          tools = tools,
          traceId = runYamlRequest.traceId,
          screenState = agent.screenStateProvider(),
          elementComparator = elementComparator,
          screenStateProvider = agent.screenStateProvider,
        ).result
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
        runBlocking { agent.captureScreenState() } ?: error("No screen state available")
      },
      cleanup = {
        withContext(NonCancellable) {
          subprocessRuntimes.forEach { it.shutdownAll() }
        }
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
          TrailRecordings.deriveTestIdentityFromTrailPath(it, fallbackClassName = "HostOnDeviceRpcRunner")
        }
        loggingRule.logger.log(
          session,
          TrailblazeLog.TrailblazeSessionStatusChangeLog(
            sessionStatus = SessionStatus.Started(
              // See parallel comment at the ComposeRpc site — strip trailConfig.memory
              // because the V1 RpcRunner path doesn't apply it (it constructs its own
              // AgentMemory inside the per-tool agent rather than going through
              // AgentMemory.seedFrom). Replay would otherwise read a false-presence signal.
              trailConfig = trailConfig?.copy(memory = null),
              trailFilePath = runYamlRequest.trailFilePath,
              testClassName = derivedTestIdentity?.className ?: "HostOnDeviceRpcRunner",
              testMethodName = derivedTestIdentity?.methodName ?: "run",
              trailblazeDeviceInfo = deviceInfo,
              rawYaml = runYamlRequest.yaml,
              hasRecordedSteps = trailblazeYaml.hasRecordedSteps(trailItems),
              trailblazeDeviceId = trailblazeDeviceId,
              targetAppInfo = MobileDeviceUtils.resolveTargetAppInfo(
                target = targetTestApp,
                trailblazeDeviceId = trailblazeDeviceId,
                resolvedAppId = appIdForSession,
              ),
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
        trailUrl = trailConfig?.metadata?.get("testRailUrl"),
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
        loggingRule.captureFinalScreenshot(session, agent.screenStateProvider)
        loggingRule.sessionManager.endSession(session, isSuccess = true)
      }

      generateAndSaveRecording(
        sessionId = session.sessionId,
        customToolClasses = customToolClasses,
      )

      session.sessionId
    }
  }

  /**
   * Queries the on-device agent for its device classifiers via a lightweight screen state probe.
   * Returns the classifiers (e.g., ["android", "phone"]) or an empty list if the device
   * doesn't provide them (older on-device agents without classifier support).
   */
  private suspend fun queryDeviceClassifiers(
    onDeviceRpc: OnDeviceRpcClient,
  ): List<TrailblazeDeviceClassifier> {
    val probe = GetScreenStateRequest(
      includeScreenshot = false,
      includeAnnotatedScreenshot = false,
    )
    return when (val result = onDeviceRpc.rpcCall(probe)) {
      is RpcResult.Success -> {
        result.data.deviceClassifiers?.map { TrailblazeDeviceClassifier(it) } ?: emptyList()
      }
      is RpcResult.Failure -> {
        Console.log(
          "[Trailblaze] Failed to query device classifiers from on-device agent; " +
            "falling back to default classifiers. RPC failure: ${result.message}",
        )
        emptyList()
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
  )

  /**
   * Compares each snapshot taken during [sessionId] against its checked-in golden file.
   * Goldens are resolved from the trail file's directory using the pattern:
   * `{device-classifier}.{snapshot-name}.golden.png`
   *
   * Missing goldens are silently skipped (not a failure) — this allows new trails to run
   * without goldens until they are deliberately captured and committed.
   */
  private fun compareSnapshotsAgainstGoldens(sessionId: SessionId): SnapshotGoldenComparison.GoldenComparisonResult? {
    return try {
      val gitRoot = GitUtils.getGitRootViaCommand() ?: return null
      val sessionDir = File(File(gitRoot, "logs"), sessionId.value)
      if (!sessionDir.exists()) return null

      val logs = sessionDir.listFiles()
        ?.filter { it.extension == "json" }
        ?.mapNotNull { file ->
          try { TrailblazeJsonInstance.decodeFromString<TrailblazeLog>(file.readText()) }
          catch (_: Exception) { null }
        }
        ?.sortedBy { it.timestamp }
        ?: return null

      val comparison = SnapshotGoldenComparison.compare(
        sessionId = sessionId,
        sessionDir = sessionDir,
        logs = logs,
      )

      Console.log("[Golden] ${comparison.summary}")
      comparison.results.forEach { r ->
        if (r.goldenFound && !r.passed) {
          Console.log(
            "[Golden] ❌ '${r.snapshotName}': ${"%.2f".format(r.diffPercent)}% diff" +
              " (${r.pixelDifferences}/${r.totalPixels} pixels," +
              " threshold ${r.thresholdPercent}%)"
          )
        }
      }

      comparison
    } catch (e: Exception) {
      Console.log("[Golden] Comparison error (non-fatal): ${e.message}")
      null
    }
  }

  /**
   * Emits a loud warning when the runner path can't apply memory seeding but the request
   * carries seeds anyway (from `config.memory:` YAML, `--memory KEY=VAL`, or `--secret
   * KEY=VAL`). Only the V3 accessibility runner wires composition through
   * [xyz.block.trailblaze.AgentMemory.seedFrom] today; V1 and Compose-RPC runners build
   * their `AgentMemory` inside the per-tool agent and would silently drop the seeds.
   * Surfacing a warning rather than failing means a YAML that uses `config.memory:` for
   * the V3 path still runs through CI's V1/Compose paths (with degraded behavior) instead
   * of bricking the whole pipeline; once V1/Compose are wired this warning goes away.
   */
  private fun warnIfMemorySeedsDropped(
    runnerName: String,
    trailConfig: xyz.block.trailblaze.yaml.TrailConfig?,
    runYamlRequest: RunYamlRequest,
  ) {
    val yamlCount = trailConfig?.memory?.size ?: 0
    val cliCount = runYamlRequest.initialMemorySeeds.size
    val secretCount = runYamlRequest.initialMemorySensitiveSeeds.size
    if (yamlCount == 0 && cliCount == 0 && secretCount == 0) return
    Console.error(
      "[memory] $runnerName received memory seeds ($yamlCount from config.memory:, " +
        "$cliCount from --memory, $secretCount from --secret) but this runner path " +
        "does not currently wire AgentMemory.seedFrom — seeds will be silently dropped. " +
        "Only the V3 accessibility runner (runHostV3WithAccessibilityYaml) applies them.",
    )
  }

  private fun requireActionableSteps(
    trailblazeYaml: xyz.block.trailblaze.yaml.TrailblazeYaml,
    trailItems: List<TrailYamlItem>,
    trailName: String?,
    trailUrl: String?,
  ) {
    if (!trailblazeYaml.hasActionableSteps(trailItems)) {
      throw TrailblazeException(
        "Trail '${trailName ?: "unknown"}' has no executable steps — this would be a false positive pass. " +
          "Add prompts or tool steps to this trail file." +
          (trailUrl?.let { " $it" } ?: ""),
      )
    }
  }

  private fun generateAndSaveRecording(
    sessionId: SessionId,
    customToolClasses: Set<kotlin.reflect.KClass<out xyz.block.trailblaze.toolcalls.TrailblazeTool>> = emptySet(),
  ): RecordingResult? {
    try {
      val gitRoot = GitUtils.getGitRootViaCommand()
      if (gitRoot == null) {
        Console.log("Could not determine git root, skipping recording generation")
        return null
      }
      val sessionDir = File(File(gitRoot, "logs"), sessionId.value)
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

      val recordingYaml = logs.generateRecordedYaml(
        sessionTrailConfig = sessionTrailConfig,
        customToolClasses = customToolClasses,
      )

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
      )
    } catch (e: Exception) {
      Console.log("Failed to generate recording: ${e.message}")
      return null
    }
  }
}
