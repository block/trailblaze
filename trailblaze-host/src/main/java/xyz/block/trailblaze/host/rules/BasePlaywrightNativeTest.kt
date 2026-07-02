package xyz.block.trailblaze.host.rules

import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import xyz.block.trailblaze.agent.TrailblazeElementComparator
import xyz.block.trailblaze.agent.TrailblazeRunner
import xyz.block.trailblaze.mcp.AgentImplementation
import xyz.block.trailblaze.mcp.agent.KoogStrategyGraphAgent
import xyz.block.trailblaze.capture.CaptureOptions
import xyz.block.trailblaze.capture.CaptureSession
import xyz.block.trailblaze.capture.video.PlaywrightVideoRecordDir
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.exception.TrailblazeException
import xyz.block.trailblaze.host.rules.TrailblazeHostLlmConfig.DEFAULT_TRAILBLAZE_LLM_MODEL
import xyz.block.trailblaze.mcp.agent.KoogTestAgentRunner
import xyz.block.trailblaze.api.TestAgentRunner
import xyz.block.trailblaze.http.DynamicLlmClient
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.model.TrailblazeConfig
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.playwright.PlaywrightBrowserManager
import xyz.block.trailblaze.playwright.PlaywrightNativeIdlingConfig
import xyz.block.trailblaze.playwright.PlaywrightPageManager
import xyz.block.trailblaze.playwright.PlaywrightTrailblazeAgent
import xyz.block.trailblaze.playwright.console.WebConsoleCapture
import xyz.block.trailblaze.playwright.network.WebNetworkCapture
import xyz.block.trailblaze.playwright.tools.WebToolSetIds
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.rules.TrailblazeLoggingRule
import xyz.block.trailblaze.rules.TrailblazeRunnerUtil
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.toolcalls.TrailblazeToolSet
import xyz.block.trailblaze.toolcalls.TrailblazeToolSetCatalog
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.yaml.TrailYamlItem
import xyz.block.trailblaze.yaml.TrailblazeYaml
import xyz.block.trailblaze.util.toPascalCaseIdentifier
import xyz.block.trailblaze.util.toSnakeCaseIdentifier
import kotlin.reflect.KClass

/**
 * Base test class for Playwright-native web testing.
 *
 * Parallel to [BaseHostTrailblazeTest] but uses [PlaywrightBrowserManager] and
 * [PlaywrightTrailblazeAgent] instead of Maestro-based components. No Maestro
 * driver, host runner, or connected device is needed.
 */
open class BasePlaywrightNativeTest(
  val config: TrailblazeConfig = TrailblazeConfig.DEFAULT,
  val trailblazeLlmModel: TrailblazeLlmModel = DEFAULT_TRAILBLAZE_LLM_MODEL,
  val dynamicLlmClient: DynamicLlmClient = TrailblazeHostDynamicLlmClientProvider(
    trailblazeLlmModel = trailblazeLlmModel,
    trailblazeDynamicLlmTokenProvider = TrailblazeHostDynamicLlmTokenProvider,
  ),
  customToolClasses: Set<KClass<out TrailblazeTool>> = setOf(),
  appTarget: TrailblazeHostAppTarget? = null,
  val trailblazeDeviceId: TrailblazeDeviceId,
  val idlingConfig: PlaywrightNativeIdlingConfig = PlaywrightNativeIdlingConfig(),
  val analyticsUrlPatterns: List<String> = emptyList(),
  val systemPromptTemplate: String = PLAYWRIGHT_NATIVE_SYSTEM_PROMPT,
  /**
   * Optional existing browser manager to reuse instead of launching a new browser.
   * When provided, no new browser is launched — all Playwright operations go through
   * the given manager. Used by the MCP WEB device path to connect to a browser that
   * was already launched (e.g. via WebBrowserManager in the desktop app).
   */
  existingBrowserManager: PlaywrightPageManager? = null,
  /**
   * Per-objective cap on LLM calls forwarded into [TrailblazeRunner.maxSteps]. Surfaced as
   * the CLI's `--max-llm-calls` flag and threaded through
   * [xyz.block.trailblaze.llm.RunYamlRequest.maxLlmCalls] into this rule's constructor.
   * Null = use the runner's built-in [TrailblazeRunner.DEFAULT_MAX_STEPS].
   *
   * Tracked as a public `val` so the daemon's cache-reuse logic (in
   * [xyz.block.trailblaze.host.TrailblazeHostYamlRunner.resolvePlaywrightCacheReuse])
   * can compare the request's cap against the cached test's cap and rebuild the test
   * when they differ — otherwise the lazy `trailblazeRunner` field would freeze the
   * cap at construction time and silently ignore later flag changes.
   */
  val maxLlmCalls: Int? = null,
  /**
   * Stable, un-suffixed Playwright browser identity used as the registry key for video
   * recording. Differs from [trailblazeDeviceId] because the runner appends a per-trail
   * UUID suffix to the latter for session-cache identity, whereas capture (in
   * `DesktopYamlRunner`) publishes the per-session video-record dir under the original
   * request id. Default mirrors that request id when callers don't override it.
   */
  val webBrowserRecordingKey: String = trailblazeDeviceId.instanceId,
  /**
   * Viewport / device-emulation spec used when this rule constructs its OWN browser
   * (no [existingBrowserManager] provided). Pass null for the desktop default.
   *
   * In the production daemon / desktop / MCP paths the browser is provisioned by
   * [WebBrowserManager], which already stores per-slot viewport intent on the
   * slot — those paths pass `existingBrowserManager` in and this field is unused.
   * The standalone JUnit-eval path (`PlaywrightNativeEvalTests`) constructs the
   * rule directly and is the load-bearing caller of this field.
   */
  viewportSpec: String? = null,
) {

  // When an existing browser is provided, the caller owns its lifecycle — close() will not
  // shut it down. When we create the browser ourselves, we own it and close() will shut it down.
  private val ownsTheBrowser: Boolean = existingBrowserManager == null

  val browserManager: PlaywrightPageManager = existingBrowserManager ?: PlaywrightBrowserManager(
    headless = config.browserHeadless,
    viewportSpec = viewportSpec,
    idlingConfig = idlingConfig,
    analyticsUrlPatterns = analyticsUrlPatterns,
    // Same key the capture stream publishes under in `PlaywrightVideoRecordDir`.
    deviceId = webBrowserRecordingKey,
  )

  val trailblazeDeviceInfo: TrailblazeDeviceInfo
    get() {
      // [resolvedViewport] is populated by [PlaywrightBrowserManager.init] for our own
      // manager and by the desktop / MCP launch path for an adopted manager. Cast
      // strictly: every BasePlaywrightNativeTest call site today supplies a
      // PlaywrightBrowserManager (`WebBrowserManager.getPageManager()` returns one,
      // and the inline construction at [browserManager] does too). If a future caller
      // wires in a different PlaywrightPageManager subtype, surface that here loudly
      // rather than silently degrading to a default viewport.
      val viewport = (browserManager as PlaywrightBrowserManager).resolvedViewport
      return TrailblazeDeviceInfo(
        trailblazeDeviceId = trailblazeDeviceId,
        trailblazeDriverType = TrailblazeDriverType.PLAYWRIGHT_NATIVE,
        widthPixels = viewport.width,
        heightPixels = viewport.height,
        classifiers = listOf(TrailblazeDevicePlatform.WEB.asTrailblazeDeviceClassifier()),
      )
    }

  val loggingRule: HostTrailblazeLoggingRule = HostTrailblazeLoggingRule(
    trailblazeDeviceInfoProvider = { trailblazeDeviceInfo },
  )

  private val playwrightAgent by lazy {
    PlaywrightTrailblazeAgent(
      browserManager = browserManager,
      trailblazeLogger = loggingRule.logger,
      trailblazeDeviceInfoProvider = { trailblazeDeviceInfo },
      sessionProvider = { loggingRule.session ?: error("Session not available - ensure test is running") },
      trailblazeToolRepo = toolRepo,
      sessionDirProvider = loggingRule.logsRepo::getSessionDir,
    )
  }

  private val resolvedWebToolSet = TrailblazeToolSetCatalog.resolveForDriver(
    driverType = TrailblazeDriverType.PLAYWRIGHT_NATIVE,
    requestedIds = WebToolSetIds.ALL,
  )

  internal val toolRepo = TrailblazeToolRepo(
    TrailblazeToolSet.DynamicTrailblazeToolSet(
      name = "Playwright Native Tool Set",
      toolClasses = resolvedWebToolSet.toolClasses + customToolClasses,
      yamlToolNames = resolvedWebToolSet.yamlToolNames,
    ),
    // Bind the repo to the web driver so the KOOG verify-step surface is driver-aware: a verify
    // block scopes to `web_verification` (see TrailblazeToolRepo.verifyStepToolDescriptors /
    // VERIFY_SCOPE_DRIVERS). Without this the repo's driverType is null and verify scoping no-ops.
    driverType = TrailblazeDriverType.PLAYWRIGHT_NATIVE,
  )

  private val trailblazeRunner: TrailblazeRunner by lazy {
    TrailblazeRunner(
      screenStateProvider = browserManager::getScreenState,
      agent = playwrightAgent,
      llmClient = dynamicLlmClient.createLlmClient(),
      trailblazeLlmModel = trailblazeLlmModel,
      trailblazeToolRepo = toolRepo,
      systemPromptTemplate = systemPromptTemplate,
      trailblazeLogger = loggingRule.logger,
      sessionProvider = { loggingRule.session ?: error("Session not available - ensure test is running") },
      maxSteps = maxLlmCalls ?: TrailblazeRunner.DEFAULT_MAX_STEPS,
    )
  }

  // KOOG brain as a [TestAgentRunner], parallel to the legacy runner above. Stable lazy so trail
  // `config.context` (appendToSystemPrompt) persists across steps. Selected per-run in [runTrail];
  // it rides the same [TrailblazeRunnerUtil.runPromptSuspend] loop, so recordings replay uniformly.
  private val koogRunner: KoogTestAgentRunner by lazy {
    KoogTestAgentRunner(
      agent = playwrightAgent,
      toolRepo = toolRepo,
      screenStateProvider = browserManager::getScreenState,
      elementComparator = elementComparator,
      llmClient = dynamicLlmClient.createLlmClient(),
      trailblazeLlmModel = trailblazeLlmModel,
      logger = loggingRule.logger,
      sessionProvider = { loggingRule.session ?: error("Session not available - ensure test is running") },
      maxLlmCalls = maxLlmCalls,
      systemPromptTemplate = systemPromptTemplate,
    )
  }

  private val elementComparator by lazy {
    TrailblazeElementComparator(
      screenStateProvider = browserManager::getScreenState,
      llmClient = dynamicLlmClient.createLlmClient(),
      trailblazeLlmModel = trailblazeLlmModel,
      toolRepo = toolRepo,
    )
  }

  private val trailblazeYaml = TrailblazeYaml.Default
  private var currentToolTraceId: TraceId? = null

  // The runner-util (deterministic replay + tool dispatch) is identical regardless of agent — only
  // the wrapped brain differs — so build one per runner. Recordings replay the same either way.
  private fun runnerUtilFor(runner: TestAgentRunner): TrailblazeRunnerUtil = TrailblazeRunnerUtil(
    trailblazeRunner = runner,
    runTrailblazeTool = { trailblazeTools: List<TrailblazeTool> ->
      playwrightAgent.runTrailblazeTools(
        tools = trailblazeTools,
        traceId = currentToolTraceId,
        screenState = browserManager.getScreenState(),
        elementComparator = elementComparator,
        screenStateProvider = browserManager::getScreenState,
      ).result
    },
    trailblazeLogger = loggingRule.logger,
    sessionProvider = { loggingRule.session ?: error("Session not available - ensure test is running") },
    sessionUpdater = { loggingRule.setSession(it) },
  )

  private val trailblazeRunnerUtil by lazy { runnerUtilFor(trailblazeRunner) }
  private val koogRunnerUtil by lazy { runnerUtilFor(koogRunner) }

  private suspend fun runTrail(
    trailItems: List<TrailYamlItem>,
    // `useRecordedSteps` is forwarded to `runPromptSuspend` to switch the agent loop
    // between recording-replay and live-LLM mode. As of the playwright-mcp settle
    // adoption, Playwright-layer settling (PlaywrightPageManager.dispatchAndAwaitSettle)
    // no longer branches on this flag — both modes settle via request-tracking.
    useRecordedSteps: Boolean,
    agentImplementation: AgentImplementation,
    onStepProgress: ((stepIndex: Int, totalSteps: Int, stepText: String) -> Unit)? = null,
  ) {
    // Pick the brain (legacy or KOOG); recordings are replayed identically by the runner-util either
    // way — only unrecorded steps reach the selected agent.
    val koog = agentImplementation == AgentImplementation.KOOG_STRATEGY_GRAPH
    val activeRunner: TestAgentRunner = if (koog) koogRunner else trailblazeRunner
    val activeRunnerUtil = if (koog) koogRunnerUtil else trailblazeRunnerUtil
    for (item in trailItems) {
      val itemResult = when (item) {
        is TrailYamlItem.PromptsTrailItem ->
          activeRunnerUtil.runPromptSuspend(
            prompts = item.promptSteps,
            useRecordedSteps = useRecordedSteps,
            selfHeal = config.selfHeal,
            onStepProgress = onStepProgress,
          )
        is TrailYamlItem.TrailheadTrailItem ->
          activeRunnerUtil.runPromptSuspend(
            prompts = listOf(item.trailhead.toPromptStep()),
            useRecordedSteps = true,
            selfHeal = config.selfHeal,
            onStepProgress = onStepProgress,
          )
        is TrailYamlItem.ToolTrailItem -> activeRunnerUtil.runTrailblazeTool(item.tools.map { it.trailblazeTool })
        is TrailYamlItem.ConfigTrailItem -> item.config.context?.let { activeRunner.appendToSystemPrompt(it) }
      }
      if (itemResult is TrailblazeToolResult.Error) {
        throw TrailblazeException(itemResult.errorMessage)
      }
    }
  }

  suspend fun runTrailblazeYamlSuspend(
    yaml: String,
    trailblazeDeviceId: TrailblazeDeviceId,
    trailFilePath: String?,
    traceId: TraceId? = null,
    useRecordedSteps: Boolean = true,
    sendSessionStartLog: Boolean,
    /**
     * Which agent owns the reasoning loop for prompt steps. Defaults to the framework default
     * ([AgentImplementation.TRAILBLAZE_RUNNER]). When [AgentImplementation.KOOG_STRATEGY_GRAPH],
     * prompt steps run through the in-process [KoogStrategyGraphAgent] instead of the legacy
     * [TrailblazeRunner]; tool / config items are unaffected.
     */
    agentImplementation: AgentImplementation = AgentImplementation.DEFAULT,
    onStepProgress: ((stepIndex: Int, totalSteps: Int, stepText: String) -> Unit)? = null,
  ): SessionId = withContext(browserManager.playwrightDispatcher) {
    // Run the entire agent loop on the Playwright thread to maintain thread affinity.
    // After callLlm() suspends and resumes, the coroutine resumes here (not on a
    // random Dispatchers.IO thread), so all Playwright API calls stay on the correct thread.

    // Set working directory to the trail file's own directory so that relative paths
    // in tools (e.g., navigate) resolve from the trail file's location.
    playwrightAgent.workingDirectory = trailFilePath?.let { java.io.File(it).absoluteFile.parentFile }

    // Self-instrument the video capture when nothing else has — the CLI/daemon path
    // (DesktopYamlRunner) publishes its own record dir before this rule's browser is
    // constructed, but the JUnit eval path drives this class directly and would
    // otherwise leave the WEB platform branch of `CaptureSession.fromOptions` cold.
    // No-op when capture is already registered for this device by an outer runner.
    ensurePlaywrightVideoCaptureStarted()

    // Capture publishes its per-session video dir before this rule's browser manager
    // is constructed, but the daemon's cache-reuse path keeps a long-lived manager
    // around across trails — its already-created BrowserContext won't have picked up
    // the freshly published recordVideoDir. Reconciling once at trail start picks
    // up the new dir (or drops recording on the next trail if capture is disabled).
    (browserManager as? PlaywrightBrowserManager)?.syncRecordingWithRegistry()

    val trailItems: List<TrailYamlItem> = trailblazeYaml.decodeTrail(
      yaml,
      deviceClassifiers = trailblazeDeviceInfo.classifiers,
    )
    val trailConfig = trailblazeYaml.extractTrailConfig(trailItems)

    // Honor `config.skip:` before SessionStarted is logged or web network capture is started —
    // matches the CLI's pre-flight `planTrailExecution` planner. The Playwright JUnit-eval
    // path drives this class directly (not through the CLI), so without this short-circuit a
    // skip-marked trail would run end-to-end here even though `trailblaze run` would skip it.
    trailblazeYaml.firstSkipReason(trailItems)?.let { skipReason ->
      Console.log(
        "[Trailblaze] Skipping trail" + (trailFilePath?.let { " ($it)" } ?: "") + ": $skipReason"
      )
      return@withContext loggingRule.session?.sessionId ?: SessionId("unknown")
    }

    if (sendSessionStartLog) {
      val session = loggingRule.session
      if (session != null) {
        loggingRule.logger.log(
          session,
          TrailblazeLog.TrailblazeSessionStatusChangeLog(
            sessionStatus = SessionStatus.Started(
              trailConfig = trailConfig,
              trailFilePath = trailFilePath,
              testClassName = trailConfig?.title?.let { toPascalCaseIdentifier(it) }
                ?: trailFilePath?.let { toPascalCaseIdentifier(java.io.File(it).parentFile.name) }
                ?: "BasePlaywrightNativeTest",
              testMethodName = trailFilePath?.let { toSnakeCaseIdentifier(java.io.File(it).parentFile.name) }
                ?: "run",
              trailblazeDeviceInfo = trailblazeDeviceInfo,
              rawYaml = yaml,
              hasRecordedSteps = trailblazeYaml.hasRecordedSteps(trailItems),
              trailblazeDeviceId = trailblazeDeviceId,
            ),
            session = session.sessionId,
            timestamp = Clock.System.now(),
          ),
        )
      }
    }
    // If the "Capture Network Traffic" toggle is on (desktop, --capture-network
    // CLI flag, or directly via TrailblazeConfig), start capture before the
    // trail runs so every request — including any that fire during the very
    // first navigate — lands in <session-dir>/network.ndjson.
    ensureWebNetworkCaptureStarted()
    ensureWebConsoleCaptureStarted()
    currentToolTraceId = traceId
    if (!trailblazeYaml.hasActionableSteps(trailItems)) {
      val trailName = trailConfig?.title ?: trailFilePath ?: "unknown"
      val trailUrl = trailConfig?.metadata?.get("testRailUrl")
      throw xyz.block.trailblaze.exception.TrailblazeException(
        "Trail '$trailName' has no executable steps — this would be a false positive pass. " +
          "Add prompts or tool steps to this trail file." +
          (trailUrl?.let { " $it" } ?: ""),
      )
    }
    try {
      runTrail(trailItems, useRecordedSteps, agentImplementation, onStepProgress)
    } finally {
      currentToolTraceId = null
    }
    loggingRule.session?.sessionId ?: SessionId("unknown")
  }

  /**
   * Idempotently starts the framework network capture for the current session
   * when [TrailblazeConfig.captureNetworkTraffic] is on. Safe to call
   * repeatedly — `WebNetworkCapture.start` short-circuits when the existing
   * capture matches the session, and rolls over cleanly when it doesn't.
   * No-op when the flag is off, no session is set, or no logs repo is wired.
   *
   * The MCP host-local tool dispatch path bypasses [runTrailblazeYamlSuspend]
   * (it runs tools individually with a synthetic session that doesn't go
   * through `loggingRule.session`), so that path inlines the equivalent
   * `WebNetworkCapture.start(...)` call against its synthetic session
   * directly — see `TrailblazeMcpBridgeImpl.executeHostLocalPlaywrightTool`.
   */
  /**
   * Owned-by-this-rule `CaptureSession` for the WEB platform — drives `video.mp4` +
   * `video_sprites.webp` generation when nothing upstream has already started capture
   * for this device id. The CLI/daemon path (`DesktopYamlRunner`) registers the record
   * dir before this rule's browser is constructed, so [PlaywrightVideoRecordDir] already
   * has an entry by the time we get here — that path leaves this field null and stops
   * capture itself. The JUnit eval path drives this class directly and would otherwise
   * skip the capture stream entirely; we self-instrument so both paths produce the same
   * artifacts.
   */
  private var ownedCaptureSession: CaptureSession? = null

  /**
   * Starts a [CaptureSession] writing directly into the per-trail session log dir when
   * no outer runner has already published a record dir for [webBrowserRecordingKey].
   * Idempotent — subsequent calls within the same session are no-ops.
   */
  private fun ensurePlaywrightVideoCaptureStarted() {
    if (ownedCaptureSession != null) return
    // Outer runner (DesktopYamlRunner via CLI/daemon) already wired capture for this
    // device. Don't double-instrument — they'll move artifacts at their own teardown.
    if (PlaywrightVideoRecordDir.getRecordDir(webBrowserRecordingKey) != null) return
    val session = loggingRule.session ?: return
    val sessionDir = loggingRule.logsRepo.getSessionDir(session.sessionId)
    val captureSession = CaptureSession.fromOptions(
      CaptureOptions(captureVideo = true),
      TrailblazeDevicePlatform.WEB,
    ) ?: return
    try {
      captureSession.startAll(sessionDir, webBrowserRecordingKey, appId = null)
      ownedCaptureSession = captureSession
    } catch (e: Exception) {
      Console.log("Auto-start of Playwright video capture failed: ${e.message}")
    }
  }

  /**
   * Idempotently stops the owned [CaptureSession] (if any) and clears the registry
   * entry. Safe to call multiple times — the second call sees a null session and
   * returns immediately. Must run **before** the browser context is torn down so the
   * stream's finalizer can flush the in-progress `.webm`.
   */
  private fun stopOwnedPlaywrightVideoCapture() {
    val captureSession = ownedCaptureSession ?: return
    ownedCaptureSession = null
    try {
      captureSession.stopAll()
    } catch (e: Exception) {
      Console.log("Stop of Playwright video capture failed: ${e.message}")
    }
  }

  private fun ensureWebNetworkCaptureStarted() {
    if (!config.captureNetworkTraffic) return
    val session = loggingRule.session ?: return
    val sessionDir = loggingRule.logsRepo.getSessionDir(session.sessionId)
    try {
      WebNetworkCapture.start(
        ctx = browserManager.currentPage.context(),
        sessionId = session.sessionId.value,
        sessionDir = sessionDir,
        tracker = playwrightAgent.inflightRequestTracker,
      )
    } catch (e: Exception) {
      // Don't let a capture-start failure tear down the trail — log and continue.
      Console.log("Auto-start of web network capture failed: ${e.message}")
    }
  }

  /**
   * Idempotently starts browser-console capture for the current session,
   * appending every `console.*` message to `<session-dir>/device.log` so the
   * report's Device Logs panel surfaces them — the web counterpart to Android
   * logcat / iOS system-log capture. Unlike network capture this is always-on
   * (console output is low-volume and there's no separate enable flag): the
   * parallel to the docs gallery's always-on `adb logcat`. Guarded so a
   * start failure never tears down the trail. No-op when no session is set.
   */
  private fun ensureWebConsoleCaptureStarted() {
    val session = loggingRule.session ?: return
    val sessionDir = loggingRule.logsRepo.getSessionDir(session.sessionId)
    try {
      WebConsoleCapture.start(
        ctx = browserManager.currentPage.context(),
        sessionId = session.sessionId.value,
        sessionDir = sessionDir,
      )
    } catch (e: Exception) {
      Console.log("Auto-start of web console capture failed: ${e.message}")
    }
  }

  fun close() {
    // Always try to stop capture on test teardown — even when we don't own the
    // browser (MCP path) — so the BufferedWriter closes cleanly. No-op if
    // capture was never started.
    runCatching { WebNetworkCapture.stop(browserManager.currentPage.context()) }
    runCatching { WebConsoleCapture.stop(browserManager.currentPage.context()) }
    // Stop the owned video capture (if any) BEFORE tearing the browser down — the
    // stream's registered finalizer needs the manager alive to close the BrowserContext
    // and flush the in-progress `.webm` to disk. No-op when an outer runner owns the
    // capture lifecycle (CLI/daemon path).
    stopOwnedPlaywrightVideoCapture()
    if (ownsTheBrowser) {
      browserManager.close()
    }
  }

  companion object {
    internal val PLAYWRIGHT_NATIVE_SYSTEM_PROMPT = """
**You are managing a {{device_description}} using Playwright.**

You will be provided with the current screen state, including:
- A list of interactive page elements with element IDs
- A screenshot of the browser viewport

## Page Elements

The page elements list shows meaningful elements on the page, each with a unique ID.
Format: `[eN] role "name"` — for example:
```
[e1] link "Home"
[e2] heading "Welcome"
[e3] textbox "Email"
[e4] button "Submit"
```

When calling tools, use the element ID (e.g., "e5") as the `ref` parameter to target
an element. You can also use ARIA descriptors (e.g., 'button "Submit"') if the element
is not in the list or you need more precision.

## Reasoning

Every tool accepts an optional `reasoning` parameter. ALWAYS include it to explain:
- Why you chose this specific action
- What you expect to happen as a result
This reasoning is logged for debugging and test reports.

When interpreting objectives, if an objective begins with the word "expect", "verify", "confirm", or
"assert" (case-insensitive), you should use the objective_status tool to report the result.

**NOTE:**
- Use web_snapshot to refresh your view of the page when needed.
- After navigation or clicks that change the page, use web_snapshot to see the updated state.
    """.trimIndent()
  }
}
