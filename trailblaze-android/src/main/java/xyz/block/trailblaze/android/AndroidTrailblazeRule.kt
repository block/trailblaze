package xyz.block.trailblaze.android

import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.message.AttachmentContent
import ai.koog.prompt.message.ContentPart
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.params.LLMParams
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import maestro.orchestra.Command
import org.junit.runner.Description
import xyz.block.trailblaze.AdbCommandUtil
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.AndroidAssetsUtil
import xyz.block.trailblaze.AndroidMaestroTrailblazeAgent
import xyz.block.trailblaze.MaestroTrailblazeAgent
import xyz.block.trailblaze.android.accessibility.AccessibilityTrailblazeAgent
import xyz.block.trailblaze.android.accessibility.OnDeviceAccessibilityServiceSetup
import xyz.block.trailblaze.TrailblazeAndroidLoggingRule
import xyz.block.trailblaze.TrailblazeYamlUtil
import xyz.block.trailblaze.agent.AgentUiActionExecutor
import xyz.block.trailblaze.agent.BlazeConfig
import xyz.block.trailblaze.agent.InnerLoopScreenAnalyzer
import xyz.block.trailblaze.agent.MultiAgentV3Runner
import xyz.block.trailblaze.agent.MultiAgentV3TestAgentRunner
import xyz.block.trailblaze.agent.TrailblazeElementComparator
import xyz.block.trailblaze.agent.TrailblazeRunner
import xyz.block.trailblaze.agent.blaze.PlannerLlmCall
import xyz.block.trailblaze.agent.blaze.PlannerToolCallResult
import xyz.block.trailblaze.agent.model.AgentTaskStatus
import xyz.block.trailblaze.android.agent.KoogLlmSamplingSource
import xyz.block.trailblaze.api.ImageFormatDetector
import xyz.block.trailblaze.logs.client.TrailblazeSessionManager
import xyz.block.trailblaze.mcp.AgentImplementation
import xyz.block.trailblaze.toolcalls.TrailblazeKoogTool.Companion.toTrailblazeToolDescriptor
import xyz.block.trailblaze.android.devices.TrailblazeAndroidOnDeviceClassifier
import xyz.block.trailblaze.android.uiautomator.AndroidOnDeviceUiAutomatorScreenState
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.TestAgentRunner
import xyz.block.trailblaze.config.McpServerConfig
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.exception.TrailblazeException
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.model.CustomTrailblazeTools
import xyz.block.trailblaze.model.TrailblazeConfig
import xyz.block.trailblaze.model.toTrailblazeToolRepo
import xyz.block.trailblaze.recordings.TrailRecordings
import xyz.block.trailblaze.rules.SimpleTestRuleChain
import xyz.block.trailblaze.rules.TrailblazeRule
import xyz.block.trailblaze.rules.TrailblazeRunnerUtil
import xyz.block.trailblaze.quickjs.tools.AndroidAssetBundleSource
import xyz.block.trailblaze.quickjs.tools.BundleSource
import xyz.block.trailblaze.quickjs.tools.LaunchedQuickJsToolRuntime
import xyz.block.trailblaze.quickjs.tools.QuickJsToolBundleLauncher
import xyz.block.trailblaze.scripting.bundle.AndroidAssetBundleJsSource
import xyz.block.trailblaze.scripting.bundle.BundleJsSource
import xyz.block.trailblaze.scripting.bundle.LaunchedBundleRuntime
import xyz.block.trailblaze.scripting.bundle.McpBundleRuntimeLauncher
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.yaml.DirectionStep
import xyz.block.trailblaze.yaml.TrailConfig
import xyz.block.trailblaze.yaml.TrailYamlItem
import xyz.block.trailblaze.yaml.createTrailblazeYaml
import xyz.block.trailblaze.util.Console

/**
 * On-Device Android Trailblaze Rule Implementation.
 *
 * Provides stateless logger with explicit session management:
 * - Access logger via `logger` property
 * - Access current session via `session` property
 * - Use `logger.log(session, log)` for all logging operations
 *
 * Supports multiple driver types via optional [agentOverride] and [screenStateProviderOverride]:
 * - When not provided, uses the default UiAutomator-based agent and screen state
 * - For accessibility mode, callers inject accessibility-specific implementations
 */
open class AndroidTrailblazeRule(
  val trailblazeLlmModel: TrailblazeLlmModel = AndroidLlmClientResolver.resolveModel(),
  val llmClient: LLMClient = AndroidLlmClientResolver.createClient(trailblazeLlmModel),
  val config: TrailblazeConfig = TrailblazeConfig.DEFAULT,
  private val trailblazeDeviceId: TrailblazeDeviceId = DEFAULT_JUNIT_TEST_ANDROID_ON_DEVICE_TRAILBLAZE_DEVICE_ID,
  val trailblazeLoggingRule: TrailblazeAndroidLoggingRule = TrailblazeAndroidLoggingRule(
    trailblazeDeviceIdProvider = { trailblazeDeviceId },
    trailblazeDeviceClassifiersProvider = { TrailblazeAndroidOnDeviceClassifier.getDeviceClassifiers() },
  ),
  customToolClasses: CustomTrailblazeTools? = null,
  agentOverride: MaestroTrailblazeAgent? = null,
  screenStateProviderOverride: (() -> ScreenState)? = null,
  /**
   * MCP bundle declarations for tools authored in TypeScript and compiled to a JS bundle
   * (PR A5 on-device path). Each entry should have `script:` set to a relative path that
   * resolves to a `.js` asset shipped in the APK — the launcher reads via
   * `android.content.res.AssetManager`. `command:` entries are silently skipped since they
   * aren't bundleable.
   *
   * Default empty so tests that don't exercise scripted MCP tools don't have to provide a
   * list. When non-empty, [runSuspend] launches the bundles at session start, registers
   * advertised tools into [trailblazeToolRepo], and tears them down after the trail ends.
   * Host-only tools (`_meta["trailblaze/requiresHost"]: true`) are dropped at
   * registration — on-device has no host agent, so advertising them would create a
   * silent-fail path the PR A5 scope devlog explicitly rules out.
   */
  private val mcpServers: List<McpServerConfig> = emptyList(),
  /**
   * Blaze configuration for V3 exploration mode.
   *
   * Defaults to [BlazeConfig.DEFAULT] — balanced settings for most exploration scenarios.
   * Only used when [AgentImplementation.MULTI_AGENT_V3] is active (via instrumentation arg
   * `-e trailblaze.agent MULTI_AGENT_V3`). The legacy [TrailblazeRunner] path ignores this
   * parameter entirely.
   *
   * Override with a custom [BlazeConfig] if you need to tune iteration counts, reflection
   * intervals, or subtask limits based on empirical OOM data from Device Farm runs.
   */
  private val blazeConfig: BlazeConfig = BlazeConfig.DEFAULT,
  /**
   * Resolver that maps each [McpServerConfig] to a [BundleJsSource] the launcher can read.
   * Default: treats the `script:` path as an Android asset path, reading via the test
   * instrumentation's AssetManager. Override for tests that want to hand in an inline JS
   * fixture or read from a non-default asset root.
   */
  private val bundleSourceResolver: (McpServerConfig) -> BundleJsSource = { entry ->
    AndroidAssetBundleJsSource(
      assetPath = requireNotNull(entry.script) {
        "mcpServers entry is missing `script:` — `command:` entries are host-only and " +
          "cannot bundle on-device."
      },
    )
  },
  /**
   * QuickJS tool bundle declarations for tools authored against `@trailblaze/tools` and
   * compiled to a JS bundle. Same `script:` convention as [mcpServers] — the launcher
   * reads each via [quickjsBundleSourceResolver] and registers advertised tools into the
   * session's tool repo through the [QuickJsToolBundleLauncher]. Host-only tools
   * (`_meta["trailblaze/requiresHost"]: true`) drop at registration so on-device sessions
   * never see them.
   *
   * Default empty so callers that don't exercise QuickJS-runtime tools don't have to pass
   * a list. The QuickJS runtime is **additive** to [mcpServers] — both can be non-empty in
   * the same rule and the two launchers run side-by-side, registering into the same repo.
   * The legacy [mcpServers] path will be retired once consumers have migrated; see the
   * `:trailblaze-quickjs-tools` README for context.
   */
  private val quickjsToolBundles: List<McpServerConfig> = emptyList(),
  /**
   * Resolver that maps each [quickjsToolBundles] entry to a [BundleSource] the QuickJS
   * launcher can read. Default treats the `script:` path as an Android asset, mirroring
   * [bundleSourceResolver] for the legacy MCP runtime. Override for tests that want to
   * hand in an inline JS fixture.
   */
  private val quickjsBundleSourceResolver: (McpServerConfig) -> BundleSource = { entry ->
    AndroidAssetBundleSource(
      assetPath = requireNotNull(entry.script) {
        "quickjsToolBundles entry is missing `script:` — `command:` entries can't bundle " +
          "into the on-device QuickJS runtime."
      },
    )
  },
  /**
   * Optional shared [AgentMemory] threaded into the constructed [AndroidMaestroTrailblazeAgent].
   * The on-device `RunYamlRequestHandler` uses this seam to populate the agent's memory from
   * the host's snapshot at request entry, and to read the post-execution state into the
   * response. Defaults to a fresh instance for the in-process / unit-test case.
   */
  agentMemoryOverride: AgentMemory? = null,
) : SimpleTestRuleChain(trailblazeLoggingRule),
  TrailblazeRule {

  private val agentMemory: AgentMemory = agentMemoryOverride ?: AgentMemory()

  /**
   * Selects the runtime agent based on
   * [TrailblazeAndroidLoggingRule.driverTypeOverride] (resolved from the
   * `trailblaze.driverType` instrumentation arg or trail config):
   *
   *  - [TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY] →
   *    [AccessibilityTrailblazeAgent] (live accessibility-tree resolution + coordinate
   *    gestures via [TrailblazeAccessibilityService]).
   *  - any other driver → [AndroidMaestroTrailblazeAgent] (UiAutomator-backed
   *    Maestro Orchestra).
   *
   * Cross-driver-portable trail recordings (carrying both a Maestro `selector` and an
   * `androidAccessibility` nodeSelector) work under both runtimes — the right path is
   * picked by [TapOnByElementSelector] based on [MaestroTrailblazeAgent.usesAccessibilityDriver].
   *
   * Resolved lazily so [trailblazeLoggingRule] is fully initialized before we read
   * [TrailblazeAndroidLoggingRule.driverTypeOverride].
   */
  val trailblazeAgent: MaestroTrailblazeAgent by lazy {
    agentOverride ?: when (trailblazeLoggingRule.driverTypeOverride) {
      TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY -> AccessibilityTrailblazeAgent(
        trailblazeLogger = trailblazeLoggingRule.logger,
        trailblazeDeviceInfoProvider = trailblazeLoggingRule.trailblazeDeviceInfoProvider,
        sessionProvider = {
          trailblazeLoggingRule.session ?: error("Session not available - ensure test is running")
        },
        // Match what the pre-merge AccessibilityAwareAndroidTrailblazeRule passed —
        // without these the on-device classifier signal flows in as `emptyList()` and
        // device-shaped element matching/filtering loses the dimension.
        deviceClassifiers = TrailblazeAndroidOnDeviceClassifier.getDeviceClassifiers(),
        memory = agentMemory,
      )
      else -> AndroidMaestroTrailblazeAgent(
        trailblazeLogger = trailblazeLoggingRule.logger,
        trailblazeDeviceInfoProvider = trailblazeLoggingRule.trailblazeDeviceInfoProvider,
        sessionProvider = {
          trailblazeLoggingRule.session ?: error("Session not available - ensure test is running")
        },
        nodeSelectorMode = config.nodeSelectorMode,
        memory = agentMemory,
        // Propagate the host bridge's capture toggle to the on-device agent so capture-aware
        // launch tools can flip their app's debug SharedPref gates in the pre-launch seeding step.
        captureNetworkTraffic = config.captureNetworkTraffic,
      )
    }
  }

  private val trailblazeToolRepo =
    customToolClasses?.toTrailblazeToolRepo() ?: TrailblazeToolRepo.withDynamicToolSets()

  private val screenStateProvider: () -> ScreenState = screenStateProviderOverride ?: {
    AndroidOnDeviceUiAutomatorScreenState(
      includeScreenshot = true,
      deviceClassifiers = trailblazeLoggingRule.trailblazeDeviceInfoProvider().classifiers,
    )
  }

  init {
    trailblazeLoggingRule.failureScreenStateProvider = screenStateProvider
  }

  private val elementComparator = TrailblazeElementComparator(
    screenStateProvider = screenStateProvider,
    llmClient = llmClient,
    trailblazeLlmModel = trailblazeLlmModel,
    toolRepo = trailblazeToolRepo,
  )

  override fun ruleCreation(description: Description) {
    super.ruleCreation(description)
  }

  /**
   * After the parent rule chain completes its setup (UiDevice, status-bar hiding, etc.),
   * enable the on-device [TrailblazeAccessibilityService] when the resolved driver is
   * [TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY]. Without this hook, the
   * accessibility-driver branch in [trailblazeAgent] constructs an [AccessibilityTrailblazeAgent]
   * whose first interaction throws `TrailblazeAccessibilityService is not running` —
   * the service is declared in this module's manifest and merged into the consumer APK,
   * but Android only starts it after the host enables it in `enabled_accessibility_services`.
   *
   * Setup must happen **after** any UiDevice/shell operations that could trigger UiAutomation
   * reconnections (which destroy a running accessibility service). The parent
   * [TrailblazeAndroidLoggingRule] does its UiDevice work in its own `beforeTestExecution`,
   * so calling super first and then enabling the service here is the correct order. Same
   * pattern as [BlockAndroidTrailblazeRule.beforeTestExecution].
   */
  override fun beforeTestExecution(description: Description) {
    super.beforeTestExecution(description)
    if (trailblazeLoggingRule.driverTypeOverride == TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY) {
      OnDeviceAccessibilityServiceSetup.ensureAccessibilityServiceReady()
    }
  }

  private val trailblazeYaml = createTrailblazeYaml(
    customTrailblazeToolClasses = customToolClasses?.allForSerializationTools() ?: setOf(),
  )

  /**
   * Agent implementation selected via instrumentation arg `-e trailblaze.agent`.
   * Defaults to [AgentImplementation.TRAILBLAZE_RUNNER] (legacy, stable).
   * Set to `MULTI_AGENT_V3` to opt into the multi-agent V3 architecture.
   */
  private val agentImplementation: AgentImplementation = InstrumentationArgUtil.agentImplementation()

  /**
   * Title of the trail currently executing (e.g. TestRail case name).
   *
   * Set at [runSuspend] entry before any step runs, then forwarded via
   * [caseTitleProvider] in the V3 runner so the inner agent receives the
   * test case title as overallObjective. This lets the agent detect impossible
   * steps early instead of exhausting retries.
   *
   * Thread-safety: JUnit creates a new rule instance per @Test method, so
   * only one [runSuspend] executes on a given instance at a time. @Volatile
   * provides the visibility guarantee when the lazy runner reads this field
   * from its coroutine thread.
   */
  @Volatile
  private var currentCaseTitle: String? = null

  private val trailblazeRunner: TestAgentRunner by lazy {
    when (agentImplementation) {
      AgentImplementation.MULTI_AGENT_V3 -> createV3Runner()
      else -> TrailblazeRunner(
        trailblazeToolRepo = trailblazeToolRepo,
        trailblazeLlmModel = trailblazeLlmModel,
        llmClient = llmClient,
        screenStateProvider = screenStateProvider,
        agent = trailblazeAgent,
        trailblazeLogger = trailblazeLoggingRule.logger,
        sessionProvider = { trailblazeLoggingRule.session ?: error("Session not available - ensure test is running") },
      )
    }
  }

  private fun createV3Runner(): MultiAgentV3TestAgentRunner {
    val samplingSource = KoogLlmSamplingSource(
      llmClient = llmClient,
      llmModel = trailblazeLlmModel,
    )
    val screenAnalyzer = InnerLoopScreenAnalyzer(
      samplingSource = samplingSource,
      model = trailblazeLlmModel,
    )
    val executor = AgentUiActionExecutor(
      agent = trailblazeAgent,
      screenStateProvider = screenStateProvider,
      toolRepo = trailblazeToolRepo,
      elementComparator = elementComparator,
    )
    val plannerLlmCall: PlannerLlmCall = { systemPrompt, userMessage, tools, _, screenshotBytes ->
      val metaInfo = RequestMetaInfo.create(kotlin.time.Clock.System)
      val userMsg = if (screenshotBytes != null && screenshotBytes.isNotEmpty()) {
        Message.User(
          parts = buildList {
            add(ContentPart.Text(userMessage))
            add(
              ContentPart.Image(
                content = AttachmentContent.Binary.Bytes(screenshotBytes),
                format = ImageFormatDetector.detectFormat(screenshotBytes).mimeSubtype,
              )
            )
          },
          metaInfo = metaInfo,
        )
      } else {
        Message.User(content = userMessage, metaInfo = metaInfo)
      }
      val koogPrompt = Prompt(
        messages = listOf(Message.System(content = systemPrompt, metaInfo = metaInfo), userMsg),
        id = "android_test_planner",
        params = LLMParams(toolChoice = LLMParams.ToolChoice.Required),
      )
      val responses = llmClient.execute(koogPrompt, trailblazeLlmModel.toKoogLlmModel(), tools)
      val toolCall = responses.filterIsInstance<Message.Tool.Call>().firstOrNull()
      val toolName = toolCall?.tool ?: tools.firstOrNull()?.name ?: "unknown"
      val toolArgsJson = toolCall?.content ?: "{}"
      val toolArgs = try {
        Json.parseToJsonElement(toolArgsJson) as? JsonObject ?: JsonObject(emptyMap())
      } catch (_: Exception) {
        JsonObject(emptyMap())
      }
      PlannerToolCallResult.fromRaw(toolName, toolArgs)
    }
    val v3Runner = MultiAgentV3Runner.create(
      screenAnalyzer = screenAnalyzer,
      executor = executor,
      plannerLlmCall = plannerLlmCall,
      config = blazeConfig,
      deviceId = trailblazeDeviceId,
      availableToolsProvider = { trailblazeToolRepo.getCurrentToolDescriptors().map { it.toTrailblazeToolDescriptor() } },
    )
    var cachedFallbackSessionId: xyz.block.trailblaze.logs.model.SessionId? = null
    return MultiAgentV3TestAgentRunner(
      v3Runner = v3Runner,
      screenStateProvider = screenStateProvider,
      sessionIdProvider = {
        trailblazeLoggingRule.session?.sessionId ?: cachedFallbackSessionId ?: run {
          Console.error("⚠️ No active loggingRule session; generating fallback session ID")
          TrailblazeSessionManager.generateSessionId("android_test_fallback")
            .also { cachedFallbackSessionId = it }
        }
      },
      caseTitleProvider = { currentCaseTitle },
    )
  }

  val trailblazeRunnerUtil by lazy {
    TrailblazeRunnerUtil(
      trailblazeRunner = trailblazeRunner,
      runTrailblazeTool = ::runTrailblazeTool,
      trailblazeLogger = trailblazeLoggingRule.logger,
      sessionProvider = { trailblazeLoggingRule.session ?: error("Session not available - ensure test is running") },
      sessionUpdater = { trailblazeLoggingRule.setSession(it) },
    )
  }

  suspend fun runSuspend(
    testYaml: String,
    trailFilePath: String?,
    useRecordedSteps: Boolean,
    sendSessionStartLog: Boolean,
  ) {
    val trailItems = trailblazeYaml.decodeTrail(testYaml)
    val trailConfig = trailblazeYaml.extractTrailConfig(trailItems)
    if (sendSessionStartLog) {
      val currentSession = trailblazeLoggingRule.session
        ?: error("Session not available when sendSessionStartLog=true. Ensure this rule is used as a @Rule in a JUnit test.")

      trailblazeLoggingRule.logger.log(
        currentSession,
        TrailblazeLog.TrailblazeSessionStatusChangeLog(
          sessionStatus = SessionStatus.Started(
            trailConfig = trailConfig,
            trailFilePath = trailFilePath,
            testClassName = trailblazeLoggingRule.description?.className ?: "AndroidTrailblazeRule",
            testMethodName = trailblazeLoggingRule.description?.methodName ?: "run",
            trailblazeDeviceInfo = trailblazeLoggingRule.trailblazeDeviceInfoProvider(),
            rawYaml = testYaml,
            hasRecordedSteps = trailblazeYaml.hasRecordedSteps(trailItems),
            trailblazeDeviceId = trailblazeDeviceId,
          ),
          session = currentSession.sessionId,
          timestamp = Clock.System.now(),
        ),
      )
    }
    trailblazeAgent.clearMemory()

    // Extract title before the loop so V3's caseTitleProvider sees it on every step.
    // Scans eagerly (not item-order dependent) so the title is available even when
    // the config block appears after the first prompts block in the YAML.
    currentCaseTitle = trailItems.filterIsInstance<TrailYamlItem.ConfigTrailItem>()
      .firstOrNull()?.config?.title

    if (!trailblazeYaml.hasActionableSteps(trailItems)) {
      val trailName = trailConfig?.title ?: trailFilePath ?: "unknown"
      val trailUrl = trailConfig?.metadata?.get("testRailUrl")
      throw TrailblazeException(
        "Trail '$trailName' has no executable steps — this would be a false positive pass. " +
          "Add prompts or tool steps to this trail file." +
          (trailUrl?.let { " $it" } ?: ""),
      )
    }

    // PR A5: launch the target-declared MCP bundles at session start, so advertised tools
    // are registered into [trailblazeToolRepo] before the LLM selects a tool. Tear down in
    // the `finally` so subprocess teardown still runs even if a trail step throws — the
    // same invariant the host subprocess launcher uses in [TrailblazeHostYamlRunner]. Host
    // wraps in `withContext(NonCancellable)` there; here the trail's calling coroutine is
    // already scoped to the runner so a trail-level cancellation shouldn't strand the
    // bundle session, but `runCatching` on `shutdownAll` protects against that edge.
    //
    // Both launches are declared up-front (initialized to `null`) so the single `try/finally`
    // below covers them as a unit. If [QuickJsToolBundleLauncher.launchAll] throws after the
    // legacy MCP runtime started, control flows into `finally` and we still tear down the
    // MCP side — flagged during code review as a P1 leak path. Without the unified scope,
    // a QuickJS startup failure would strand MCP dynamic-tool registrations and the QuickJS
    // native allocation of any partial-startup state for the rest of the process's life.
    var launchedBundleRuntime: LaunchedBundleRuntime? = null
    var launchedQuickjsRuntime: LaunchedQuickJsToolRuntime? = null

    try {
      if (mcpServers.isNotEmpty()) {
        launchedBundleRuntime = McpBundleRuntimeLauncher.launchAll(
          mcpServers = mcpServers,
          deviceInfo = trailblazeLoggingRule.trailblazeDeviceInfoProvider(),
          sessionId = (trailblazeLoggingRule.session
            ?: error("Session not available for MCP bundle launch")).sessionId,
          toolRepo = trailblazeToolRepo,
          bundleSourceResolver = bundleSourceResolver,
          // Callback channel is wired unconditionally by the launcher — there's no daemon
          // HTTP server on-device, so `_meta.trailblaze.runtime = "ondevice"` tells the TS
          // SDK to dispatch `client.callTool(…)` through the in-process QuickJS binding
          // instead of fetch. See [McpBundleRuntimeLauncher.launchAll]'s kdoc.
        )
      }

      // Also launch the MCP-free QuickJS bundles (`:trailblaze-quickjs-tools`). Additive
      // to the legacy runtime above — both can register tools into the same
      // [trailblazeToolRepo] in the same session. Same fail-fast / register-then-teardown
      // invariant; the `finally` block below handles teardown order (QuickJS first, then
      // MCP) so a tool dispatched from a QuickJS bundle that composes with an MCP bundle's
      // tool still works during shutdown.
      if (quickjsToolBundles.isNotEmpty()) {
        launchedQuickjsRuntime = QuickJsToolBundleLauncher.launchAll(
          bundles = quickjsToolBundles,
          deviceInfo = trailblazeLoggingRule.trailblazeDeviceInfoProvider(),
          sessionId = (trailblazeLoggingRule.session
            ?: error("Session not available for QuickJS bundle launch")).sessionId,
          toolRepo = trailblazeToolRepo,
          bundleSourceResolver = quickjsBundleSourceResolver,
        )
      }

      trailItems.forEach { item ->
        val itemResult = when (item) {
          is TrailYamlItem.PromptsTrailItem ->
            trailblazeRunnerUtil.runPrompt(
              prompts = item.promptSteps,
              useRecordedSteps = useRecordedSteps,
              selfHeal = config.selfHeal,
            )
          is TrailYamlItem.ToolTrailItem -> runTrailblazeTool(item.tools.map { it.trailblazeTool })
          is TrailYamlItem.ConfigTrailItem -> handleConfig(item.config)
        }
        if (itemResult is TrailblazeToolResult.Error) {
          throw TrailblazeException(itemResult.errorMessage)
        }
      }
    } finally {
      // Tear QuickJS down before the legacy MCP runtime: a QuickJS bundle whose handler
      // composed with an MCP-bundle tool may still be on the call stack when shutdown
      // fires. Closing QuickJS first releases its dispatch lock so the cross-runtime call
      // returns/errors cleanly rather than seeing the underlying MCP transport vanish
      // mid-call. NonCancellable for the same reason as below: a cancelled trail must
      // still run teardown to completion.
      launchedQuickjsRuntime?.let { runtime ->
        withContext(NonCancellable) {
          runCatching { runtime.shutdownAll() }
        }
      }
      launchedBundleRuntime?.let { runtime ->
        // Wrap in `withContext(NonCancellable)` so a cancelled trail (timeout, abort,
        // user cancel) still runs the bundle teardown through to completion rather than
        // cancelling at the first suspension point inside `McpBundleSession.shutdown()`.
        // Without this, a cancelled run would leak the QuickJS native allocation plus
        // the dynamic-tool registrations into the next session's tool repo. Mirrors the
        // host-side pattern in `TrailblazeHostYamlRunner.launchSubprocessMcpServersIfAny`'s
        // caller.
        withContext(NonCancellable) {
          runCatching { runtime.shutdownAll() }
        }
      }
    }
  }

  override fun run(
    testYaml: String,
    trailFilePath: String?,
    useRecordedSteps: Boolean,
  ) = runBlocking {
    runSuspend(
      testYaml = testYaml,
      trailFilePath = trailFilePath,
      useRecordedSteps = useRecordedSteps,
      sendSessionStartLog = true,
    )
  }

  private fun runTrailblazeTool(trailblazeTools: List<TrailblazeTool>): TrailblazeToolResult =
    trailblazeAgent.runTrailblazeTools(
      tools = trailblazeTools,
      screenState = screenStateProvider(),
      elementComparator = elementComparator,
      screenStateProvider = screenStateProvider,
    ).result

  @Deprecated("Prefer the suspend version.")
  private fun runMaestroCommandsBlocking(maestroCommands: List<Command>): TrailblazeToolResult =
    runBlocking { runMaestroCommands(maestroCommands) }

  private suspend fun runMaestroCommands(maestroCommands: List<Command>): TrailblazeToolResult {
    return when (
      val maestroResult =
        trailblazeAgent.runMaestroCommands(
          maestroCommands = maestroCommands,
          traceId = null,
        )
    ) {
      is TrailblazeToolResult.Success -> maestroResult
      is TrailblazeToolResult.Error -> throw TrailblazeException(maestroResult.errorMessage)
    }
  }

  private fun handleConfig(config: TrailConfig): TrailblazeToolResult {
    config.context?.let { trailblazeRunner.appendToSystemPrompt(it) }
    return TrailblazeToolResult.Success()
  }

  /**
   * Run natural language instructions with the agent.
   */
  override fun prompt(objective: String): Boolean {
    val runnerResult = trailblazeRunner.run(DirectionStep(objective))
    return if (runnerResult is AgentTaskStatus.Success) {
      true
    } else {
      throw TrailblazeException(runnerResult.toString())
    }
  }

  /**
   * Run a Trailblaze tool with the agent.
   */
  override fun tool(vararg trailblazeTool: TrailblazeTool): TrailblazeToolResult {
    val result = trailblazeAgent.runTrailblazeTools(
      tools = trailblazeTool.toList(),
      elementComparator = elementComparator,
      screenStateProvider = screenStateProvider,
    ).result
    return if (result is TrailblazeToolResult.Success) {
      result
    } else {
      throw TrailblazeException(result.toString())
    }
  }

  /**
   * Run a Trailblaze tool with the agent.
   */
  override suspend fun maestroCommands(vararg maestroCommand: Command): TrailblazeToolResult {
    val runCommandsResult = trailblazeAgent.runMaestroCommands(
      maestroCommand.toList(),
      null,
    )
    return if (runCommandsResult is TrailblazeToolResult.Success) {
      runCommandsResult
    } else {
      throw TrailblazeException(runCommandsResult.toString())
    }
  }

  fun runFromAsset(
    yamlAssetPath: String = TrailblazeYamlUtil.calculateTrailblazeYamlAssetPathFromStackTrace(
      AndroidAssetsUtil::assetExists,
    ),
    forceStopApp: Boolean = true,
    useRecordedSteps: Boolean = true,
    targetAppId: String? = null,
  ) {
    val computedAssetPath: String = TrailRecordings.findBestTrailResourcePath(
      path = yamlAssetPath,
      deviceClassifiers = trailblazeLoggingRule.trailblazeDeviceInfoProvider().classifiers,
      doesResourceExist = AndroidAssetsUtil::assetExists,
    ) ?: throw TrailblazeException("Asset not found: $yamlAssetPath")
    Console.log("Running from asset: $computedAssetPath")
    if (forceStopApp && targetAppId != null) {
      AdbCommandUtil.forceStopApp(targetAppId)
    }
    val yamlContent = AndroidAssetsUtil.readAssetAsString(computedAssetPath)
    run(
      testYaml = yamlContent,
      useRecordedSteps = useRecordedSteps,
      trailFilePath = yamlAssetPath,
    )
  }

  companion object {
    /**
     * Only use this on-device when no deviceId is available (like in a connectedDebugAndroidTest)
     *
     * NOTE: It would be better to pass these values as instrumentation args if possible
     */
    @Deprecated("Only use this on-device when no deviceId is available (like in a connectedDebugAndroidTest)")
    val DEFAULT_JUNIT_TEST_ANDROID_ON_DEVICE_TRAILBLAZE_DEVICE_ID = TrailblazeDeviceId(
      instanceId = TrailblazeDriverType.DEFAULT_ANDROID.name,
      trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
    )
  }
}
