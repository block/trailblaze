package xyz.block.trailblaze.host.playwright

import java.io.File
import java.util.UUID
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.devices.TrailblazeConnectedDeviceSummary
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.devices.WebInstanceIds
import xyz.block.trailblaze.host.HostYamlRunResult
import xyz.block.trailblaze.host.TrailblazeHostYamlRunner
import xyz.block.trailblaze.host.driver.DeviceListingVisibility
import xyz.block.trailblaze.host.driver.HostDeviceInventory
import xyz.block.trailblaze.host.driver.HostDriverDescriptor
import xyz.block.trailblaze.host.driver.HostRunDeps
import xyz.block.trailblaze.host.driver.HostScreenStateDeps
import xyz.block.trailblaze.host.rules.BasePlaywrightNativeTest
import xyz.block.trailblaze.host.yaml.RunOnHostParams
import xyz.block.trailblaze.http.DynamicLlmClient
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.playwright.PlaywrightPageManager
import xyz.block.trailblaze.scripting.LaunchedScriptingRuntime
import xyz.block.trailblaze.ui.TrailblazeDeviceManager
import xyz.block.trailblaze.util.Console

/**
 * Plugs the Playwright-native web driver into the host: Trailblaze launches (or adopts) its own
 * Chromium via Playwright and drives pages in it.
 *
 * Unlike every other driver so far, its canonical device needs no probe: the browser is
 * provisioned on demand, so `web/playwright-native` is always offered — running or not — and any
 * named instances the host's `WebBrowserManager` has live join it from the inventory.
 */
class PlaywrightNativeHostDriverDescriptor : HostDriverDescriptor {

  override val driverTypes: Set<TrailblazeDriverType> = setOf(TrailblazeDriverType.PLAYWRIGHT_NATIVE)

  override val listingVisibility = DeviceListingVisibility.LISTED

  /**
   * Every running browser the host reports, plus the always-available singleton when the running
   * set doesn't already include it — same dedupe-by-instance-id the device manager used to do
   * inline, kept so launching the default browser doesn't list it twice.
   */
  override suspend fun discoverDevices(inventory: HostDeviceInventory): List<TrailblazeConnectedDeviceSummary> {
    val running = inventory.runningWebBrowsers
    if (running.any { it.instanceId == WebInstanceIds.PLAYWRIGHT_NATIVE }) return running
    return running + TrailblazeConnectedDeviceSummary(
      trailblazeDriverType = TrailblazeDriverType.PLAYWRIGHT_NATIVE,
      instanceId = WebInstanceIds.PLAYWRIGHT_NATIVE,
      description = "Playwright Browser (Native)",
    )
  }

  override suspend fun runYaml(deps: HostRunDeps, params: RunOnHostParams): HostYamlRunResult =
    HostYamlRunResult(
      runPlaywrightNativeYaml(
        dynamicLlmClient = deps.dynamicLlmClient,
        runOnHostParams = params,
        deviceManager = deps.deviceManager,
        logsDir = deps.logsDir,
      ),
    )

  override suspend fun screenState(
    driverType: TrailblazeDriverType,
    deviceId: TrailblazeDeviceId,
    deps: HostScreenStateDeps,
  ): ScreenState? = MaestroDriverScreenStates.fromActiveDriver(deps, deviceId)

  /**
   * Playwright-native path: launches a browser via [xyz.block.trailblaze.playwright.PlaywrightBrowserManager]
   * and runs the trail using [xyz.block.trailblaze.playwright.PlaywrightTrailblazeAgent] with
   * web-native tools.
   *
   * When `sendSessionEndLog` is false (e.g. MCP interactive authoring), the browser is kept
   * alive between calls by caching the [BasePlaywrightNativeTest] instance in the device manager.
   * This mirrors the Maestro path's session reuse behaviour.
   */
  private suspend fun runPlaywrightNativeYaml(
    dynamicLlmClient: DynamicLlmClient,
    runOnHostParams: RunOnHostParams,
    deviceManager: TrailblazeDeviceManager,
    logsDir: File?,
  ): SessionId? {
    val onProgressMessage = runOnHostParams.onProgressMessage
    val runYamlRequest = runOnHostParams.runYamlRequest

    val requestDeviceId = runYamlRequest.trailblazeDeviceId
    val keepBrowserAlive = !runYamlRequest.config.sendSessionEndLog

    // Try to reuse a cached Playwright test instance (only when keeping browser alive).
    // If the cached test was constructed for a different configuration than this request
    // (e.g. user ran `trailblaze config llm <provider>` after the daemon cached the
    // initial test, or the test was cached by a path that didn't know the configured logs
    // directory or this run's `--no-logging` flag), we evict it but keep the live browser —
    // otherwise the cached model/client/logs-repo sticks around for the daemon's lifetime and
    // silently runs every web tool with the wrong provider, files every session in the wrong
    // directory, or writes session files a `--no-logging` run asked it not to.
    val cachedTest =
      if (keepBrowserAlive) deviceManager.getActivePlaywrightNativeTest(requestDeviceId) else null
    val cacheResolution = resolvePlaywrightCacheReuse(
      cachedModel = cachedTest?.trailblazeLlmModel,
      cachedBrowserManager = cachedTest?.browserManager,
      cachedMaxLlmCalls = cachedTest?.maxLlmCalls,
      cachedLogsDir = cachedTest?.loggingRule?.logsRepo?.logsDir,
      cachedNoLogging = cachedTest?.loggingRule?.logsRepo?.readOnly ?: false,
      requestedModel = runYamlRequest.trailblazeLlmModel,
      requestedMaxLlmCalls = runYamlRequest.maxLlmCalls,
      requestedLogsDir = logsDir,
      requestedNoLogging = runOnHostParams.noLogging,
    )
    val existingTest =
      if (cacheResolution is PlaywrightCacheResolution.ReuseCachedTest) cachedTest else null
    val staleBrowserToReuse =
      (cacheResolution as? PlaywrightCacheResolution.RebuildWithCachedBrowser)?.browser
    val isReusingTest = existingTest != null
    val isRebuildingAroundCachedBrowser = staleBrowserToReuse != null

    // Stable device ID when reusing the same test or rebuilding-with-existing-browser
    // (same logical session — only the test around the browser is being rebuilt); unique
    // suffix only for genuinely fresh test runs.
    val trailblazeDeviceId =
      if (isReusingTest || isRebuildingAroundCachedBrowser) {
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
          "Configuration changed — rebuilding Playwright-native test around the running browser..."
        else -> "Initializing Playwright-native test runner..."
      },
    )

    // If the request targets a web slot that already has a running browser
    // (provisioned via `device create web` or the desktop UI's Launch Browser),
    // reuse it as the rule's `existingBrowserManager`. Without this, the runner
    // would spin up a SECOND PlaywrightBrowserManager — bypassing the slot's
    // configured viewport / emulation profile and producing trail runs at the
    // default 1280x800. The cache-reuse path's `staleBrowserToReuse` covers the
    // intra-daemon rebuild case; this covers the cross-command case.
    val adoptedSlotBrowser: PlaywrightPageManager? = if (staleBrowserToReuse == null) {
      deviceManager.webBrowserManager.getPageManager(requestDeviceId.instanceId)
    } else {
      null
    }
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
      // The browser slot as discovery advertises it, for the same reason: the session must name
      // an id something can find again, not the per-trail suffixed one.
      advertisedDeviceInstanceId = requestDeviceId.instanceId,
      // Honor the CLI `--no-capture-video` opt-out — this rule self-instruments video.
      captureVideo = runOnHostParams.captureVideo,
      logsDir = logsDir,
      noLogging = runOnHostParams.noLogging,
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
    return TrailblazeHostYamlRunner.executeTrailSession(
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
      TrailblazeHostYamlRunner.launchSubprocessMcpServersIfAny(
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
        initialMemorySeeds = runYamlRequest.initialMemorySeeds,
        initialMemorySensitiveSeeds = runYamlRequest.initialMemorySensitiveSeeds,
        initialArgs = runYamlRequest.initialArgs,
        onStepProgress = { step, total, text ->
          onProgressMessage("Step $step/$total: $text")
        },
      )
      Console.log("✅ Playwright-native runTrailblazeYamlSuspend completed for device: ${trailblazeDeviceId.instanceId}")
      onProgressMessage("Test execution completed successfully")

      if (runYamlRequest.config.sendSessionEndLog) {
        playwrightTest.loggingRule.captureFinalScreenshot(session, playwrightTest.browserManager::getScreenState)
        playwrightTest.loggingRule.endSession(session, isSuccess = true)
      }

      val customToolClasses = runOnHostParams.targetTestApp
        ?.getCustomToolsForDriver(runOnHostParams.trailblazeDriverType) ?: emptySet()
      TrailblazeHostYamlRunner.generateAndSaveRecording(
        sessionId = sessionId,
        logsDir = playwrightTest.loggingRule.logsRepo.logsDir,
        customToolClasses = resolveWebToolClasses(TrailblazeDriverType.PLAYWRIGHT_NATIVE) + customToolClasses,
      )

      sessionId
    }
  }

  companion object {

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

      /** Cached test matches the request — use it as-is. */
      data object ReuseCachedTest : PlaywrightCacheResolution

      /**
       * Cached test was built for a different configuration than the request asks for — a
       * different model (e.g. the user ran `trailblaze config llm <provider>` after the daemon
       * cached the initial test), a different max-llm-calls cap, a different logs directory, or a
       * different `--no-logging` stance. Discard the test but keep [browser] alive so URL /
       * cookies / in-flight forms survive the rebuild.
       */
      data class RebuildWithCachedBrowser(val browser: PlaywrightPageManager) :
        PlaywrightCacheResolution
    }

    /**
     * Decides how to handle a cached [BasePlaywrightNativeTest] for an incoming
     * run-yaml request. Pure function — no side effects, exhaustively covered by
     * `PlaywrightCacheReuseTest`.
     *
     * A cached test carries its own [xyz.block.trailblaze.host.rules.HostTrailblazeLoggingRule],
     * and so its own logs directory and `--no-logging` stance. Cache-population paths outside the
     * run path (the MCP bridge, the recording screen's device connect) build that rule without a
     * directory and with logging on, so reusing such a test would silently put the session back
     * at the rule's own defaults even though the request knows better — hence [requestedLogsDir]
     * and [requestedNoLogging] participating in the match rather than only the LLM fields.
     */
    internal fun resolvePlaywrightCacheReuse(
      cachedModel: TrailblazeLlmModel?,
      cachedBrowserManager: PlaywrightPageManager?,
      cachedMaxLlmCalls: Int?,
      cachedLogsDir: File?,
      cachedNoLogging: Boolean,
      requestedModel: TrailblazeLlmModel,
      requestedMaxLlmCalls: Int?,
      requestedLogsDir: File?,
      requestedNoLogging: Boolean,
    ): PlaywrightCacheResolution = when {
      cachedModel == null -> PlaywrightCacheResolution.NoCachedTest
      cachedModel == requestedModel &&
        cachedMaxLlmCalls == requestedMaxLlmCalls &&
        cachedNoLogging == requestedNoLogging &&
        logsDirIsAcceptable(cachedLogsDir = cachedLogsDir, requestedLogsDir = requestedLogsDir) ->
        PlaywrightCacheResolution.ReuseCachedTest
      cachedBrowserManager != null ->
        // The model, the max-llm-calls cap, the logs directory, or the no-logging stance changed.
        // All four are baked into the cached test (the first two into its lazy TrailblazeRunner, the
        // last two into its logging rule's LogsRepo), so the test instance has to be rebuilt; we keep
        // the cached browser to avoid relaunching Chromium every time.
        PlaywrightCacheResolution.RebuildWithCachedBrowser(cachedBrowserManager)
      // Defensive: cached model exists but no browser to reuse — treat as no cache.
      // In practice cachedBrowserManager is always non-null when cachedModel is, but
      // pinning this branch keeps the function total instead of relying on caller invariants.
      else -> PlaywrightCacheResolution.NoCachedTest
    }

    /**
     * Whether a test cached at [cachedLogsDir] may serve a request for [requestedLogsDir].
     *
     * A null request expresses no opinion — the JUnit and no-app-config callers let the rule
     * resolve its own default — so any cached directory serves it. Compared by canonical path so
     * a symlinked or `..`-bearing spelling of the same directory doesn't force a pointless rebuild.
     */
    private fun logsDirIsAcceptable(cachedLogsDir: File?, requestedLogsDir: File?): Boolean = when {
      requestedLogsDir == null -> true
      cachedLogsDir == null -> false
      else -> canonicalOrAbsolutePath(cachedLogsDir) == canonicalOrAbsolutePath(requestedLogsDir)
    }

    private fun canonicalOrAbsolutePath(dir: File): String = try {
      dir.canonicalPath
    } catch (e: java.io.IOException) {
      dir.absolutePath
    }
  }
}
