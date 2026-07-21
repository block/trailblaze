package xyz.block.trailblaze.host.rules

import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import xyz.block.trailblaze.agent.TrailblazeElementComparator
import xyz.block.trailblaze.agent.TrailblazeRunner
import xyz.block.trailblaze.mcp.agent.KoogTestAgentRunner
import xyz.block.trailblaze.api.TestAgentRunner
import xyz.block.trailblaze.mcp.AgentImplementation
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.exception.TrailblazeException
import xyz.block.trailblaze.host.rules.TrailblazeHostLlmConfig.DEFAULT_TRAILBLAZE_LLM_MODEL
import xyz.block.trailblaze.http.DynamicLlmClient
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.model.TrailblazeConfig
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.playwright.ElectronAppManager
import xyz.block.trailblaze.playwright.PlaywrightBrowserManager
import xyz.block.trailblaze.playwright.PlaywrightElectronBrowserManager
import xyz.block.trailblaze.playwright.PlaywrightNativeIdlingConfig
import xyz.block.trailblaze.playwright.PlaywrightPageManager
import xyz.block.trailblaze.playwright.PlaywrightTrailblazeAgent
import xyz.block.trailblaze.playwright.console.WebConsoleCapture
import xyz.block.trailblaze.playwright.network.WebNetworkCapture
import xyz.block.trailblaze.playwright.tools.PlaywrightDesktopLaunchGooseTool
import xyz.block.trailblaze.playwright.tools.WebToolSetIds
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.rules.TrailblazeLoggingRule
import xyz.block.trailblaze.rules.TrailblazeRunnerUtil
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.TrailblazeToolSet
import xyz.block.trailblaze.toolcalls.TrailblazeToolSetCatalog
import xyz.block.trailblaze.yaml.ElectronAppConfig
import xyz.block.trailblaze.yaml.TrailArgBinder
import xyz.block.trailblaze.yaml.TrailYamlItem
import xyz.block.trailblaze.yaml.TrailblazeYaml
import xyz.block.trailblaze.util.toPascalCaseIdentifier
import xyz.block.trailblaze.util.toSnakeCaseIdentifier
import kotlin.reflect.KClass

/**
 * Base test class for Playwright-based Electron desktop app testing.
 *
 * Mirrors [BasePlaywrightNativeTest] but connects to an Electron app via CDP instead
 * of launching a fresh browser. Uses [ElectronAppManager] for app lifecycle and
 * [PlaywrightElectronBrowserManager] for the Playwright connection.
 */
class BasePlaywrightElectronTest(
  val electronAppConfig: ElectronAppConfig,
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
  /** Per-objective LLM call cap. See [BasePlaywrightNativeTest.maxLlmCalls] for semantics. */
  val maxLlmCalls: Int? = null,
) {

  /** Manages the Electron app process (if we launched it). */
  private val electronAppManager: ElectronAppManager = ElectronAppManager(electronAppConfig).also {
    it.start()
  }

  val browserManager: PlaywrightPageManager = PlaywrightElectronBrowserManager(
    cdpUrl = electronAppManager.cdpUrl,
    idlingConfig = idlingConfig,
    analyticsUrlPatterns = analyticsUrlPatterns,
  )

  val trailblazeDeviceInfo: TrailblazeDeviceInfo
    get() = TrailblazeDeviceInfo(
      trailblazeDeviceId = trailblazeDeviceId,
      trailblazeDriverType = TrailblazeDriverType.PLAYWRIGHT_ELECTRON,
      widthPixels = PlaywrightBrowserManager.DEFAULT_VIEWPORT_WIDTH,
      heightPixels = PlaywrightBrowserManager.DEFAULT_VIEWPORT_HEIGHT,
      classifiers = listOf(TrailblazeDevicePlatform.WEB.asTrailblazeDeviceClassifier()),
    )

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

  // PLAYWRIGHT_ELECTRON driver + shared WebToolSetIds works because `web_core.yaml` /
  // `web_verification.yaml` both list `playwright-electron` under `drivers:`. If either YAML
  // drops that entry, this resolution silently returns an empty set — `WebToolSetCatalogTest`
  // pins both drivers.
  private val resolvedWebToolSet = TrailblazeToolSetCatalog.resolveForDriver(
    driverType = TrailblazeDriverType.PLAYWRIGHT_ELECTRON,
    requestedIds = WebToolSetIds.ALL,
  )

  internal val toolRepo = TrailblazeToolRepo(
    TrailblazeToolSet.DynamicTrailblazeToolSet(
      name = "Playwright Electron Tool Set",
      toolClasses = resolvedWebToolSet.toolClasses + ELECTRON_BUILT_IN_TOOL_CLASSES + customToolClasses,
      yamlToolNames = resolvedWebToolSet.yamlToolNames,
    ),
    // Bind the repo to the web driver so the KOOG verify-step surface scopes to `web_verification`
    // (see TrailblazeToolRepo.verifyStepToolDescriptors / VERIFY_SCOPE_DRIVERS). Without this the
    // repo's driverType is null and verify scoping no-ops.
    driverType = TrailblazeDriverType.PLAYWRIGHT_ELECTRON,
  )

  private val trailblazeRunner: TrailblazeRunner by lazy {
    TrailblazeRunner(
      screenStateProvider = browserManager::getScreenState,
      agent = playwrightAgent,
      llmClient = dynamicLlmClient.createLlmClient(),
      trailblazeLlmModel = trailblazeLlmModel,
      trailblazeToolRepo = toolRepo,
      systemPromptTemplate = PLAYWRIGHT_ELECTRON_SYSTEM_PROMPT,
      trailblazeLogger = loggingRule.logger,
      sessionProvider = { loggingRule.session ?: error("Session not available - ensure test is running") },
      maxSteps = maxLlmCalls ?: TrailblazeRunner.DEFAULT_MAX_STEPS,
    )
  }

  // KOOG brain as a [TestAgentRunner], parallel to the legacy runner. Rides the same
  // [TrailblazeRunnerUtil.runPromptSuspend] loop, so recordings replay uniformly; only unrecorded
  // steps reach the Koog brain. Selected per-run in [runTrail].
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
      systemPromptTemplate = PLAYWRIGHT_ELECTRON_SYSTEM_PROMPT,
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
    // Shares one execution context + snapshot frame across the recording, matching the
    // batching pattern elsewhere. This agent's buildExecutionContext doesn't cache per-call
    // device state today, so the benefit here is reduced frame/ThreadLocal churn rather than
    // a clipboard-style state-survival fix.
    sharedToolBatch = { block -> playwrightAgent.runInSharedToolBatch(block) },
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
    agentImplementation: AgentImplementation = AgentImplementation.DEFAULT,
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
  ): SessionId = withContext(browserManager.playwrightDispatcher) {
    playwrightAgent.workingDirectory = trailFilePath?.let { java.io.File(it).absoluteFile.parentFile }

    // decodeTrailOrToolEnvelope (superset of decodeTrail): a trail document decodes identically; a
    // bare `- <toolName>:` envelope (single-tool MCP/CLI dispatch) additionally decodes to one
    // ToolTrailItem. Host-runner single-tool dispatch now sends the bare envelope, not `- tools:`.
    val trailItems: List<TrailYamlItem> = trailblazeYaml.decodeTrailOrToolEnvelope(
      yaml,
      deviceClassifiers = trailblazeDeviceInfo.classifiers,
    )
    val trailConfig = trailblazeYaml.extractTrailConfig(trailItems)

    // Honor `config.skip:` before SessionStarted is logged. Same rationale as the
    // BasePlaywrightNativeTest skip site — this Electron base test path is exercised by
    // the JUnit eval suite directly, so the CLI's pre-flight planner doesn't cover it.
    trailblazeYaml.firstSkipReason(trailItems)?.let { skipReason ->
      Console.log(
        "[Trailblaze] Skipping trail" + (trailFilePath?.let { " ($it)" } ?: "") + ": $skipReason"
      )
      return@withContext loggingRule.session?.sessionId ?: SessionId("unknown")
    }

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
                ?: "BasePlaywrightElectronTest",
              testMethodName = trailFilePath?.let { toSnakeCaseIdentifier(java.io.File(it).parentFile.name) }
                ?: "run",
              trailblazeDeviceInfo = trailblazeDeviceInfo,
              rawYaml = yaml,
              hasRecordedSteps = trailblazeYaml.hasRecordedSteps(trailItems),
              trailblazeDeviceId = trailblazeDeviceId,
              resolvedInitialMemory = resolvedInitialMemory,
              sensitiveMemoryKeys = sensitiveMemoryKeys,
            ),
            session = session.sessionId,
            timestamp = Clock.System.now(),
          ),
        )
      }
    }
    ensureWebNetworkCaptureStarted()
    ensureWebConsoleCaptureStarted()
    currentToolTraceId = traceId
    try {
      runTrail(trailItems, useRecordedSteps, agentImplementation, onStepProgress)
    } finally {
      currentToolTraceId = null
    }
    loggingRule.session?.sessionId ?: SessionId("unknown")
  }

  /**
   * See [BasePlaywrightNativeTest.ensureWebNetworkCaptureStarted] — same
   * contract, applied to the Electron CDP-managed BrowserContext so trails
   * exercising an Electron app can capture network traffic the same way.
   */
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
      Console.log("Auto-start of web network capture failed: ${e.message}")
    }
  }

  /**
   * See [BasePlaywrightNativeTest.ensureWebConsoleCaptureStarted] — same
   * always-on contract, applied to the Electron CDP-managed BrowserContext so
   * trails exercising an Electron app capture browser-console output to
   * `device.log` the same way.
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
    runCatching { WebNetworkCapture.stop(browserManager.currentPage.context()) }
    runCatching { WebConsoleCapture.stop(browserManager.currentPage.context()) }
    browserManager.close()
    electronAppManager.close()
  }

  companion object {
    /** Built-in tools specific to Electron desktop app testing. */
    val ELECTRON_BUILT_IN_TOOL_CLASSES: Set<KClass<out TrailblazeTool>> = setOf(
      PlaywrightDesktopLaunchGooseTool::class,
    )

    internal val PLAYWRIGHT_ELECTRON_SYSTEM_PROMPT = """
**You are managing a desktop Electron application using Playwright.**
This is a desktop Electron application, not a regular web browser.

You will be provided with the current screen state, including:
- A list of interactive page elements with element IDs
- A screenshot of the application window

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
- Use web_snapshot to refresh your view of the application when needed.
- After clicks that change the view, use web_snapshot to see the updated state.
    """.trimIndent()
  }
}
