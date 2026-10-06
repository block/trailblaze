package xyz.block.trailblaze.host.rules

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import xyz.block.trailblaze.agent.TrailblazeElementComparator
import xyz.block.trailblaze.capture.CaptureOptions
import xyz.block.trailblaze.capture.CaptureSession
import xyz.block.trailblaze.capture.video.PlaywrightVideoRecordDir
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.exception.TrailblazeException
import xyz.block.trailblaze.host.devices.DeviceLocaleConfigurator
import xyz.block.trailblaze.host.recording.WebStreamScreenshotSupport
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
import xyz.block.trailblaze.yaml.TrailArgBinder
import xyz.block.trailblaze.yaml.TrailYamlItem
import xyz.block.trailblaze.yaml.TrailblazeYaml
import xyz.block.trailblaze.util.toPascalCaseIdentifier
import xyz.block.trailblaze.util.toSnakeCaseIdentifier
import java.io.File
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
   * Per-objective cap on LLM calls. Surfaced as the CLI's `--max-llm-calls` flag and threaded
   * through [xyz.block.trailblaze.llm.RunYamlRequest.maxLlmCalls] into this rule's constructor.
   * Null = the agent's built-in default
   * ([xyz.block.trailblaze.mcp.agent.KoogStrategyGraphAgent.DEFAULT_MAX_LLM_CALLS]).
   *
   * Tracked as a public `val` so the daemon's cache-reuse logic (in
   * [xyz.block.trailblaze.host.playwright.PlaywrightNativeHostDriverDescriptor.Companion.resolvePlaywrightCacheReuse])
   * can compare the request's cap against the cached test's cap and rebuild the test
   * when they differ — otherwise the lazy `agentRunner` field would freeze the
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
   * The browser slot as device discovery advertises it — see
   * [TrailblazeDeviceInfo.advertisedInstanceId]. Null is right for callers that already pass an
   * un-suffixed [trailblazeDeviceId] (the JUnit eval path).
   */
  val advertisedDeviceInstanceId: String? = null,
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
  /**
   * Whether to self-instrument session-video capture. Web self-instruments its own recording (the
   * host capture coordinator skips WEB), so this is the only place the CLI's `--capture-video`
   * opt-in can take effect for web runs. Defaults to false — video is opt-in.
   */
  val captureVideo: Boolean = false,
  /**
   * Directory session logs are written to. Null lets [HostTrailblazeLoggingRule] use its own
   * default resolution — correct for the JUnit-eval path, which has no app config to read. The
   * host runner passes the configured `logsDirectory` so this rule's own disk writes, and the
   * recording generation that reads them back, land where the setting points.
   */
  logsDir: File? = null,
  /**
   * When true, this rule installs the no-op logger and a read-only [xyz.block.trailblaze.report.utils.LogsRepo],
   * so the run writes no session files to disk. False lets the JUnit-eval path log normally; the host
   * runner passes the run's `--no-logging` flag.
   */
  noLogging: Boolean = false,
  /**
   * The run's `--device-classifier`, already validated by the runner as a refinement of `web`
   * (e.g. `[web, browser, es]`). It selects the trail's recording leg and labels the session, so
   * the same trail run in two languages reports as two devices. Empty reports plain `web`.
   */
  val deviceClassifierOverride: List<TrailblazeDeviceClassifier> = emptyList(),
  /**
   * The language this browser opens in when the trail declares none — the web counterpart of a
   * phone booted in its lane's language. CI sets it per lane through [WEB_LOCALE_ENV].
   */
  private val laneLocale: String? = System.getenv(WEB_LOCALE_ENV)?.takeIf { it.isNotBlank() },
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

  /**
   * Dedicated single thread for the trail/agent loop ([runTrailblazeYamlSuspend]).
   *
   * The loop needs a STABLE thread — coroutines must resume from LLM calls on the same
   * thread ([xyz.block.trailblaze.toolcalls.ToolBatchScope], snapshot-cache frames and the
   * execution-context ThreadLocal are all thread-scoped) — but it must NOT be the Playwright
   * thread. Tool dispatch is non-suspend, so a host-local scripted tool blocks the loop
   * thread for its whole runtime, and a `runtime: subprocess` tool composes other tools
   * through `/scripting/callback`, whose nested Playwright dispatch bridges onto
   * [PlaywrightPageManager.playwrightDispatcher] via [PlaywrightPageManager.onPlaywrightThread].
   * Running the loop ON the Playwright thread therefore parks the very thread the nested
   * call needs, deadlocking every such composition until the subprocess's callback timeout
   * aborts it — minutes of deadlock before any error. Every Playwright API touch bridges onto the
   * Playwright thread
   * per-call instead — the same model multi-device sessions already use (their loop runs
   * on the session's routing thread).
   */
  private val trailLoopExecutor: java.util.concurrent.ExecutorService =
    java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
      Thread(runnable, "playwright-trail-loop").apply { isDaemon = true }
    }
  private val trailLoopDispatcher = trailLoopExecutor.asCoroutineDispatcher()

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
        classifiers = deviceClassifierOverride.ifEmpty {
          listOf(TrailblazeDevicePlatform.WEB.asTrailblazeDeviceClassifier())
        },
        locale = (browserManager as? PlaywrightBrowserManager)?.contextLocale,
        advertisedInstanceId = advertisedDeviceInstanceId,
      )
    }

  val loggingRule: HostTrailblazeLoggingRule = HostTrailblazeLoggingRule(
    trailblazeDeviceInfoProvider = { trailblazeDeviceInfo },
    logsDir = logsDir,
    noLogging = noLogging,
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

  /**
   * Serves LLM-turn screenshots from the live CDP screencast when
   * `TRAILBLAZE_WEB_STREAM_SCREENSHOT[_AB]` is set; the delegate provider untouched otherwise.
   * Only the LLM-loop providers below use it — recorded-tool dispatch and the element
   * comparator keep the direct capture (their screenshots aren't per-turn LLM payloads).
   */
  private val webStreamScreenshots by lazy {
    WebStreamScreenshotSupport(
      deviceId = trailblazeDeviceId,
      pageManager = browserManager,
      delegateProvider = browserManager::getScreenState,
    )
  }

  // Stable lazy so trail `config.context` (appendToSystemPrompt) persists across steps. Rides the
  // [TrailblazeRunnerUtil.runPromptSuspend] loop, so recordings replay without the agent.
  private val agentRunner: KoogTestAgentRunner by lazy {
    KoogTestAgentRunner(
      agent = playwrightAgent,
      toolRepo = toolRepo,
      screenStateProvider = webStreamScreenshots.screenStateProvider,
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

  // The runner-util: deterministic replay + tool dispatch around the agent.
  private fun runnerUtilFor(runner: TestAgentRunner): TrailblazeRunnerUtil = TrailblazeRunnerUtil(
    trailblazeRunner = runner,
    // Only recorded tools and `tools:` blocks come through here — LLM steps dispatch through the
    // runner's own agent call — so they run as a Playwright test would (see runScripted). The
    // screen state is captured only if a tool reads it.
    runTrailblazeTool = { trailblazeTools: List<TrailblazeTool> ->
      playwrightAgent.runScripted {
        playwrightAgent.runTrailblazeTools(
          tools = trailblazeTools,
          traceId = currentToolTraceId,
          screenState = null,
          elementComparator = elementComparator,
          screenStateProvider = browserManager::getScreenState,
        ).result
      }
    },
    trailblazeLogger = loggingRule.logger,
    sessionProvider = { loggingRule.session ?: error("Session not available - ensure test is running") },
    sessionUpdater = { loggingRule.setSession(it) },
    // Shares one execution context + snapshot frame across the recording, matching the
    // batching pattern elsewhere. This agent's buildExecutionContext doesn't cache per-call
    // device state today, so the benefit here is reduced frame/ThreadLocal churn rather than
    // a clipboard-style state-survival fix.
    sharedToolBatch = { block -> playwrightAgent.runInSharedToolBatch(block) },
  )

  private val agentRunnerUtil by lazy { runnerUtilFor(agentRunner) }

  private suspend fun runTrail(
    trailItems: List<TrailYamlItem>,
    // `useRecordedSteps` is forwarded to `runPromptSuspend` to switch the agent loop
    // between recording-replay and live-LLM mode. Only LLM-driven tools settle
    // (PlaywrightPageManager.dispatchAndAwaitSettle); recorded ones run as a Playwright
    // test would — see runnerUtilFor.
    useRecordedSteps: Boolean,
    onStepProgress: ((stepIndex: Int, totalSteps: Int, stepText: String) -> Unit)? = null,
  ) {
    val activeRunner: TestAgentRunner = agentRunner
    val activeRunnerUtil = agentRunnerUtil
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
    useRecordedSteps: Boolean,
    sendSessionStartLog: Boolean,
    /**
     * CLI `--memory` / `--secret` seeds, composed with the trail's `config.memory:` block via
     * [xyz.block.trailblaze.AgentMemory.seedFrom] before any tool runs (later tiers win on a
     * same-key collision; sensitive values are redacted from logs and the Started snapshot).
     */
    initialMemorySeeds: Map<String, String> = emptyMap(),
    initialMemorySensitiveSeeds: Map<String, String> = emptyMap(),
    /**
     * CLI-bound `config.args:` values in [xyz.block.trailblaze.yaml.TrailArgBinder.encodeProvided]
     * wire form, seeded via [xyz.block.trailblaze.AgentMemory.seedArgs] right after the memory
     * tiers (string args may carry memory tokens, so memory must land first).
     */
    initialArgs: Map<String, String> = emptyMap(),
    onStepProgress: ((stepIndex: Int, totalSteps: Int, stepText: String) -> Unit)? = null,
    trailSourceUrl: String? = null,
  ): SessionId = withContext(trailLoopDispatcher) {
    // Run the agent loop on its own stable thread (NOT the Playwright thread — see
    // [trailLoopDispatcher]). After callLlm() suspends and resumes, the coroutine resumes
    // here (not on a random Dispatchers.IO thread), keeping thread-scoped tool state stable;
    // Playwright API calls bridge onto the Playwright thread per-call.

    // Set working directory to the trail file's own directory so that relative paths
    // in tools (e.g., navigate) resolve from the trail file's location.
    playwrightAgent.workingDirectory = trailFilePath?.let { java.io.File(it).absoluteFile.parentFile }

    // decodeTrailOrToolEnvelope (superset of decodeTrail): a trail document decodes identically; a
    // bare `- <toolName>:` envelope (single-tool MCP/CLI dispatch, e.g. `trailblaze tool`) additionally
    // decodes to one ToolTrailItem. Required because host-runner single-tool dispatch now sends the
    // bare envelope instead of the legacy `- tools:` list shape.
    val trailItems: List<TrailYamlItem> = trailblazeYaml.decodeTrailOrToolEnvelope(
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

    // A context's language is fixed when it is created, so it is set before the recording sync
    // below (which may rebuild the context too) and before the first navigation. Only when a
    // session starts: an interactive step continues the session's page, and rebuilding the context
    // would throw that page away.
    if (sendSessionStartLog) {
      (browserManager as? PlaywrightBrowserManager)?.applyContextLocale(
        resolveBrowserLocale(trailLocale = trailConfig?.locale, laneLocale = laneLocale),
      )
    }

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

    // Seed the agent's memory before any tool runs — same [AgentMemory.seedFrom] composition
    // as every other host runner path. The agent threads this memory into every tool execution
    // context, so `{{var}}` interpolation and scripted tools' `ctx.memory` both see the seeds.
    val resolvedInitialMemory = playwrightAgent.memory.seedFrom(
      yamlDefaults = trailConfig?.memory,
      cliSeeds = initialMemorySeeds,
      cliSensitiveSeeds = initialMemorySensitiveSeeds,
    )
    playwrightAgent.memory.seedArgs(TrailArgBinder.decodeProvided(initialArgs))
    val sensitiveMemoryKeys: Set<String> = playwrightAgent.memory.sensitiveKeys.toSet()

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
              trailSourceUrl = trailSourceUrl,
              resolvedInitialMemory = resolvedInitialMemory,
              sensitiveMemoryKeys = sensitiveMemoryKeys,
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
      throw xyz.block.trailblaze.exception.TrailblazeException(
        "Trail '$trailName' has no executable steps — this would be a false positive pass. " +
          "Add prompts or tool steps to this trail file.",
      )
    }
    try {
      runTrail(trailItems, useRecordedSteps, onStepProgress)
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
   * Owned-by-this-rule `CaptureSession` for the WEB platform — drives session-video
   * recording when nothing upstream has already started capture
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
    // A `--no-logging` run has nowhere to put capture artifacts: these writers create the session
    // directory themselves, bypassing the read-only repo that suppresses every other write.
    if (loggingRule.logsRepo.readOnly) return
    if (!captureVideo) return
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
    // A `--no-logging` run has nowhere to put capture artifacts: these writers create the session
    // directory themselves, bypassing the read-only repo that suppresses every other write.
    if (loggingRule.logsRepo.readOnly) return
    if (!config.captureNetworkTraffic) return
    val session = loggingRule.session ?: return
    val sessionDir = loggingRule.logsRepo.getSessionDir(session.sessionId)
    try {
      // Bridged: `currentPage` can lazily create the page, and listener registration can
      // send wire messages — both are Playwright API touches, and the trail loop no longer
      // runs on the Playwright thread.
      browserManager.onPlaywrightThread {
        WebNetworkCapture.start(
          ctx = browserManager.currentPage.context(),
          sessionId = session.sessionId.value,
          sessionDir = sessionDir,
          tracker = playwrightAgent.inflightRequestTracker,
        )
      }
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
    // A `--no-logging` run has nowhere to put capture artifacts: these writers create the session
    // directory themselves, bypassing the read-only repo that suppresses every other write.
    if (loggingRule.logsRepo.readOnly) return
    val session = loggingRule.session ?: return
    val sessionDir = loggingRule.logsRepo.getSessionDir(session.sessionId)
    try {
      // Bridged for the same reason as ensureWebNetworkCaptureStarted.
      browserManager.onPlaywrightThread {
        WebConsoleCapture.start(
          ctx = browserManager.currentPage.context(),
          sessionId = session.sessionId.value,
          sessionDir = sessionDir,
        )
      }
    } catch (e: Exception) {
      Console.log("Auto-start of web console capture failed: ${e.message}")
    }
  }

  fun close() {
    // Detach the screencast-sourced screenshot subscription (no-op when never engaged).
    runCatching { webStreamScreenshots.close() }
    // Always try to stop capture on test teardown — even when we don't own the
    // browser (MCP path) — so the BufferedWriter closes cleanly. No-op if
    // capture was never started. Bridged like the matching starts: `currentPage.context()`
    // touches Page objects, and close() runs on the JUnit thread.
    runCatching { browserManager.onPlaywrightThread { WebNetworkCapture.stop(browserManager.currentPage.context()) } }
    runCatching { browserManager.onPlaywrightThread { WebConsoleCapture.stop(browserManager.currentPage.context()) } }
    // Stop the owned video capture (if any) BEFORE tearing the browser down — the
    // stream's registered finalizer needs the manager alive to close the BrowserContext
    // and flush the in-progress `.webm` to disk. No-op when an outer runner owns the
    // capture lifecycle (CLI/daemon path).
    stopOwnedPlaywrightVideoCapture()
    if (ownsTheBrowser) {
      browserManager.close()
    }
    // Closing the dispatcher shuts down the executor it wraps.
    trailLoopDispatcher.close()
  }

  companion object {
    /** Env var naming the browser language for trails that declare no `locale:`, e.g. `es`. */
    const val WEB_LOCALE_ENV = "TRAILBLAZE_WEB_LOCALE"

    /**
     * The language a session's browser context opens in: the trail's resolved `locale:` for this
     * device, else the lane's, else null (the browser default). The trail wins for the same reason
     * a trail's locale wins over the language a phone booted in: it is the more specific request.
     */
    internal fun resolveBrowserLocale(trailLocale: String?, laneLocale: String?): String? =
      (trailLocale ?: laneLocale)?.let(DeviceLocaleConfigurator::normalizedLocale)

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
- The element list is refreshed after every action. After navigation or a click that changes the page,
  target elements from the new list; earlier IDs may no longer exist.
    """.trimIndent()
  }
}
