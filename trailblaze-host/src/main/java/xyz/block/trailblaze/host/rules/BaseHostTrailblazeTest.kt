package xyz.block.trailblaze.host.rules

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import org.junit.Rule
import org.junit.rules.RuleChain
import xyz.block.trailblaze.TrailblazeYamlUtil
import xyz.block.trailblaze.agent.DefaultProgressReporter
import xyz.block.trailblaze.agent.InnerLoopScreenAnalyzer
import xyz.block.trailblaze.agent.MultiAgentV3Runner
import xyz.block.trailblaze.agent.MultiAgentV3TestAgentRunner
import xyz.block.trailblaze.agent.TrailblazeElementComparator
import xyz.block.trailblaze.BaseTrailblazeAgent
import xyz.block.trailblaze.agent.TrailblazeRunner
import xyz.block.trailblaze.mcp.agent.KoogTestAgentRunner
import xyz.block.trailblaze.yaml.PromptStep
import xyz.block.trailblaze.api.TestAgentRunner
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDriverType
import java.io.File
import xyz.block.trailblaze.exception.TrailblazeException
import xyz.block.trailblaze.yaml.createTrailblazeYaml
import xyz.block.trailblaze.host.HostMaestroTrailblazeAgent
import xyz.block.trailblaze.host.HostYamlRunResult
import xyz.block.trailblaze.host.MaestroHostRunnerImpl
import xyz.block.trailblaze.agent.AgentUiActionExecutor
import xyz.block.trailblaze.host.devices.MaestroConnectedDevice
import xyz.block.trailblaze.host.devices.TrailblazeConnectedDevice
import xyz.block.trailblaze.host.devices.TrailblazeDeviceService
import xyz.block.trailblaze.host.devices.TrailblazeHostDeviceClassifier
import xyz.block.trailblaze.host.rules.TrailblazeHostLlmConfig.DEFAULT_TRAILBLAZE_LLM_MODEL
import xyz.block.trailblaze.http.DynamicLlmClient
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.client.TrailblazeSessionManager
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.mcp.AgentImplementation
import xyz.block.trailblaze.cli.CliConfigHelper
import xyz.block.trailblaze.mcp.sampling.LocalLlmSamplingSource
import xyz.block.trailblaze.model.TrailblazeConfig
import xyz.block.trailblaze.host.ios.MobileDeviceUtils
import xyz.block.trailblaze.model.ResolvedTarget
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.recordings.TrailRecordings
import xyz.block.trailblaze.rules.RetryRule
import xyz.block.trailblaze.rules.TrailblazeLoggingRule
import xyz.block.trailblaze.rules.TrailblazeRunnerUtil
import xyz.block.trailblaze.scripting.HostScriptedToolLauncher
import xyz.block.trailblaze.scripting.LaunchedScriptingRuntime
import xyz.block.trailblaze.toolcalls.EmptyTrailblazeToolSurface
import xyz.block.trailblaze.toolcalls.ToolName
import xyz.block.trailblaze.toolcalls.TrailblazeKoogTool.Companion.toTrailblazeToolDescriptor
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.TrailblazeToolSet
import xyz.block.trailblaze.toolcalls.TrailblazeToolSurface
import xyz.block.trailblaze.toolcalls.getExcludedToolSurfaceForDriver
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.util.TemplatingUtil
import xyz.block.trailblaze.yaml.TrailArgBinder
import xyz.block.trailblaze.yaml.TrailYamlItem
import xyz.block.trailblaze.yaml.TrailblazeYaml
import kotlin.reflect.KClass

abstract class BaseHostTrailblazeTest(
  private val trailblazeDriverType: TrailblazeDriverType,
  val config: TrailblazeConfig = TrailblazeConfig.DEFAULT,
  val trailblazeLlmModel: TrailblazeLlmModel = DEFAULT_TRAILBLAZE_LLM_MODEL,
  val dynamicLlmClient: DynamicLlmClient = TrailblazeHostDynamicLlmClientProvider(
    trailblazeLlmModel = trailblazeLlmModel,
    trailblazeDynamicLlmTokenProvider = TrailblazeHostDynamicLlmTokenProvider,
  ),
  protected val systemPromptTemplate: String? = null,
  trailblazeToolSet: TrailblazeToolSet? = null,
  customToolClasses: Set<KClass<out TrailblazeTool>> = setOf(),
  customYamlToolNames: Set<ToolName> = setOf(),
  excludedToolClasses: Set<KClass<out TrailblazeTool>> = setOf(),
  maxRetries: Int = 0,
  private val appTarget: TrailblazeHostAppTarget? = null,
  explicitDeviceId: TrailblazeDeviceId? = null,
) {

  /**
   * The resolved device ID for this test.
   * Uses the explicitly provided ID, or auto-detects from connected devices.
   * When multiple devices of the same platform are connected (multi-simulator mode),
   * uses the Gradle worker ID to distribute tests across them.
   */
  protected val trailblazeDeviceId: TrailblazeDeviceId = explicitDeviceId
    ?: resolveDeviceForTest(trailblazeDriverType)

  companion object {
    private const val TRAILBLAZE_SELF_HEAL_ENABLED_ENV = "TRAILBLAZE_SELF_HEAL_ENABLED"
    private const val TRAILBLAZE_TRAIL_CONTEXT_ENV = "TRAILBLAZE_TRAIL_CONTEXT"
    private const val TRAILBLAZE_SETUP_TRAIL_ID_ENV = "TRAILBLAZE_SETUP_TRAIL_ID"

    private fun resolveDeviceForTest(
      trailblazeDriverType: TrailblazeDriverType,
    ): TrailblazeDeviceId {
      val connectedDevices = TrailblazeDeviceService.listConnectedTrailblazeDevices()
        .filter { it.trailblazeDevicePlatform == trailblazeDriverType.platform }
        .sortedBy { it.instanceId }

      check(connectedDevices.isNotEmpty()) {
        "No connected ${trailblazeDriverType.platform} device found"
      }

      if (connectedDevices.size == 1) return connectedDevices.first()

      // Multiple devices detected — use Gradle worker ID to pick one
      val workerId = System.getProperty("org.gradle.test.worker")?.toIntOrNull()
      val index = if (workerId != null && workerId > 0) {
        (workerId - 1) % connectedDevices.size
      } else {
        0
      }
      return connectedDevices[index]
    }
  }

  val hostRunner: MaestroHostRunnerImpl by lazy {
    MaestroHostRunnerImpl(
      trailblazeDeviceId = trailblazeDeviceId,
      trailblazeLogger = loggingRule.logger,
      sessionProvider = { loggingRule.session ?: error("Session not available - ensure test is running") },
      appTarget = appTarget,
      deviceClassifiers = trailblazeDeviceClassifiers,
      // Tests deliberately pin to the resolved per-model config; live `EffectiveScreenshotScalingConfig`
      // re-reads aren't useful here because the test fixture's model isn't running through the
      // interactive desktop settings.
      screenshotScalingConfigProvider = { trailblazeLlmModel.screenshotScalingConfig },
    )
  }

  /**
   * Makes sure the targeted app is closed
   */
  abstract fun ensureTargetAppIsStopped()

  /**
   * The connected device, fetched independently to avoid circular dependency with hostRunner.
   * This must be lazy to avoid initialization during test class construction.
   */
  private val connectedDevice: TrailblazeConnectedDevice by lazy {
    TrailblazeDeviceService.getConnectedDevice(
      trailblazeDeviceId = trailblazeDeviceId,
      driverType = trailblazeDriverType,
      appTarget = appTarget,
    ) ?: error("No connected device matching $trailblazeDeviceId found.")
  }

  val trailblazeDeviceClassifiers: List<TrailblazeDeviceClassifier> by lazy {
    TrailblazeHostDeviceClassifier(
      trailblazeDriverType = trailblazeDriverType,
      maestroDeviceInfoProvider = {
        (connectedDevice as? MaestroConnectedDevice)?.initialMaestroDeviceInfo
          ?: error("Host-test device classification currently requires a Maestro-backed device; got ${connectedDevice::class.simpleName}")
      },
    ).getDeviceClassifiers()
  }

  val trailblazeDeviceInfo: TrailblazeDeviceInfo by lazy {
    TrailblazeDeviceInfo(
      trailblazeDeviceId = trailblazeDeviceId,
      trailblazeDriverType = trailblazeDriverType,
      widthPixels = connectedDevice.deviceWidth,
      heightPixels = connectedDevice.deviceHeight,
      classifiers = trailblazeDeviceClassifiers,
    )
  }

  val hostLoggingRule: HostTrailblazeLoggingRule = HostTrailblazeLoggingRule(
    trailblazeDeviceInfoProvider = {
      trailblazeDeviceInfo
    },
  )

  val loggingRule: TrailblazeLoggingRule = hostLoggingRule

  init {
    loggingRule.failureScreenStateProvider = { hostRunner.screenStateProvider() }
  }

  /**
   * RuleChain ensures RetryRule is the outermost rule, wrapping all other rules.
   * This allows the retry logic to properly retry the entire test including all rule setup/teardown.
   *
   * IMPORTANT: When a retry occurs, the test instance is NOT re-instantiated.
   * - The same test instance is reused across retry attempts
   * - Instance variables persist across retries (not reset)
   * - Rules in the chain (like loggingRule) ARE re-executed on each retry
   *
   * This works well for our tests because:
   * - We test external state (iOS app, device) not internal test state
   * - Each test method calls ensureTargetAppIsStopped() which cleans up app state
   * - The lazy properties (hostRunner, trailblazeAgent) are fine to persist
   *
   * See [RetryRule] documentation for full details on retry behavior.
   */
  @get:Rule
  val ruleChain: RuleChain = RuleChain
    .outerRule(RetryRule(maxRetries = maxRetries))
    .around(loggingRule)

  /**
   * The session's resolved target ([appTarget] paired with this run's device), or null when no
   * target was supplied. Surfaced to scripted tools as `ctx.target.{id, appIds, appId}` so a
   * host-Maestro TS tool can resolve the installed app id from the target — #2699. The two V3
   * on-device paths in [TrailblazeHostYamlRunner] already wire this; the host-Maestro path (iOS +
   * Android local-device) previously left it null, so the FIRST host-Maestro TS tool to read
   * `ctx.target` threw "requires a Trailblaze target context". Cheap (just wraps two refs); the
   * device probe is deferred to [resolvedAppIdForSession].
   */
  private val resolvedTargetForSession: ResolvedTarget? =
    appTarget?.let { ResolvedTarget(target = it, deviceId = trailblazeDeviceId) }

  /**
   * Device-resolved primary app id for [resolvedTargetForSession] — the declared candidate that's
   * actually installed on this device. Lazy so the `simctl listapps` / `adb` probe runs once per
   * session and only when a target-aware tool reads the envelope. Soft-fails to null (logged) so a
   * target with no installed candidate surfaces as `ctx.target.resolveAppId() === undefined` and the
   * launch fails downstream with a clearer message, mirroring the V3 path's contract.
   */
  private val resolvedAppIdForSession: String? by lazy {
    val resolved = resolvedTargetForSession ?: return@lazy null
    runCatching {
      val installed = MobileDeviceUtils.getInstalledAppIds(resolved.deviceId)
      // Android's AndroidHostAdbUtils.listInstalledPackages swallows adb failures and returns an
      // empty set instead of throwing, so the onFailure branch below never fires for them. Detect
      // the distinguishable "0 installed despite declared candidates" case and log it explicitly,
      // so a downstream "ctx.target.resolveAppId() === undefined" is debuggable on Android too
      // (mirrors the V1 resolution site in TrailblazeHostYamlRunner).
      if (installed.isEmpty() && resolved.appIds.isNotEmpty()) {
        Console.log(
          "[BaseHostTrailblazeTest] getInstalledAppIds returned 0 packages for ${resolved.deviceId} " +
            "despite target declaring [${resolved.appIds.joinToString()}] — appId will be null " +
            "(likely a silent adb failure).",
        )
      }
      resolved.target.getAppIdIfInstalled(resolved.platform, installed)
    }.onFailure { e ->
      Console.log(
        "[BaseHostTrailblazeTest] appId resolution failed for ${resolved.deviceId} " +
          "(declared [${resolved.appIds.joinToString()}]): ${e::class.simpleName}: ${e.message}",
      )
    }.getOrNull()
  }

  val trailblazeAgent by lazy {
    HostMaestroTrailblazeAgent(
      maestroHostRunner = hostRunner,
      trailblazeLogger = loggingRule.logger,
      trailblazeDeviceInfoProvider = loggingRule.trailblazeDeviceInfoProvider,
      sessionProvider = { loggingRule.session ?: error("Session not available - ensure test is running") },
      nodeSelectorMode = config.nodeSelectorMode,
      trailblazeToolRepo = toolRepo,
      resolvedTarget = resolvedTargetForSession,
      appId = resolvedAppIdForSession,
      // Lets host-side `requiresHost` tools (e.g. a capture-reading tool) resolve capture artifacts
      // written under this session's on-host log dir. Same wiring the Playwright/MCP set-sites use.
      // `hostLoggingRule` (not the common-typed `loggingRule`) is the one carrying `logsRepo`.
      sessionDirProvider = hostLoggingRule.logsRepo::getSessionDir,
    )
  }

  /**
   * Which agent implementation to use for this test.
   * Configurable via the `trailblaze.agent` system property for CI toggle.
   * Defaults to TRAILBLAZE_RUNNER (stable, battle-tested).
   */
  protected open val agentImplementation: AgentImplementation =
    System.getProperty("trailblaze.agent", AgentImplementation.DEFAULT_NAME)
      .let { AgentImplementation.valueOf(it) }

  /**
   * The active target's `excluded_tools:` surface (class / YAML / scripted) for this driver, when an
   * [appTarget] was supplied. Threaded into the repo below so a host JUnit/CLI run honors the SAME
   * scripted + YAML opt-outs the daemon and on-device paths do — not just the class-backed ones the
   * explicit [excludedToolClasses] param historically carried. Empty when no target was given.
   */
  private val targetExcludedSurface: TrailblazeToolSurface =
    appTarget?.getExcludedToolSurfaceForDriver(trailblazeDriverType) ?: EmptyTrailblazeToolSurface

  val toolRepo = if (trailblazeToolSet != null) {
    // Explicit tool set override — bypass dynamic catalog
    TrailblazeToolRepo(
      trailblazeToolSet = TrailblazeToolSet.DynamicTrailblazeToolSet(
        name = "Custom Tool Set",
        toolClasses = trailblazeToolSet.toolClasses + customToolClasses -
          (excludedToolClasses + targetExcludedSurface.toolClasses),
        yamlToolNames = trailblazeToolSet.yamlToolNames + customYamlToolNames -
          targetExcludedSurface.yamlToolNames,
        // Forward the explicit toolset's scripted tools too (they were silently dropped before)
        // and honor the target's scripted opt-outs, so this override branch stays symmetric with
        // the dynamic-catalog branch below.
        scriptedToolNames = trailblazeToolSet.scriptedToolNames - targetExcludedSurface.scriptedToolNames,
      ),
    )
  } else {
    TrailblazeToolRepo.withDynamicToolSets(
      customToolClasses = customToolClasses,
      customYamlToolNames = customYamlToolNames,
      // Union the explicit class opt-outs with the target's full surface, and forward the YAML +
      // scripted partitions too (via getExcludedToolSurfaceForDriver) so an `excluded_tools:
      // [openUrl]` target doesn't advertise openUrl in a host run.
      excludedToolClasses = excludedToolClasses + targetExcludedSurface.toolClasses,
      excludedYamlToolNames = targetExcludedSurface.yamlToolNames,
      excludedScriptedToolNames = targetExcludedSurface.scriptedToolNames,
      driverType = trailblazeDriverType,
    )
  }

  private val elementComparator by lazy {
    TrailblazeElementComparator(
      screenStateProvider = hostRunner.screenStateProvider,
      llmClient = dynamicLlmClient.createLlmClient(),
      trailblazeLlmModel = trailblazeLlmModel,
      toolRepo = toolRepo,
    )
  }

  val trailblazeRunner: TestAgentRunner by lazy {
    when (agentImplementation) {
      AgentImplementation.MULTI_AGENT_V3 -> createV3Runner()
      AgentImplementation.KOOG_STRATEGY_GRAPH -> createKoogRunner()
      else -> createLegacyRunner()
    }
  }

  private fun createLegacyRunner(): TrailblazeRunner {
    return TrailblazeRunner(
      screenStateProvider = hostRunner.screenStateProvider,
      agent = trailblazeAgent,
      llmClient = dynamicLlmClient.createLlmClient(),
      trailblazeLlmModel = trailblazeLlmModel,
      trailblazeToolRepo = toolRepo,
      systemPromptTemplate = systemPromptTemplate,
      trailblazeLogger = loggingRule.logger,
      sessionProvider = { loggingRule.session ?: error("Session not available - ensure test is running") },
    )
  }

  private fun createV3Runner(): MultiAgentV3TestAgentRunner {
    val llmClient = dynamicLlmClient.createLlmClient()
    val samplingSource = LocalLlmSamplingSource(
      llmClient = llmClient,
      llmModel = trailblazeLlmModel,
      logsRepo = hostLoggingRule.logsRepo,
      sessionIdProvider = { loggingRule.session?.sessionId },
      saveAnnotatedScreenshotsProvider = {
        CliConfigHelper.readConfig()?.saveAnnotatedScreenshots ?: true
      },
    )
    val screenAnalyzer = InnerLoopScreenAnalyzer(
      samplingSource = samplingSource,
      model = trailblazeLlmModel,
    )
    val executor = AgentUiActionExecutor(
      agent = trailblazeAgent,
      screenStateProvider = hostRunner.screenStateProvider,
      toolRepo = toolRepo,
      elementComparator = elementComparator,
    )

    val session = loggingRule.session ?: error("Session not available - ensure test is running")
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

    // sessionIdProvider is invoked once per tool/step. If we hit the fallback
    // path, we must return the *same* fallback ID across calls — otherwise
    // consecutive tool invocations in one test would write to different session
    // directories — and we should log the unexpected fallback exactly once.
    var cachedFallbackSessionId: SessionId? = null
    return MultiAgentV3TestAgentRunner(
      v3Runner = v3Runner,
      screenStateProvider = hostRunner.screenStateProvider,
      sessionIdProvider = {
        loggingRule.session?.sessionId ?: cachedFallbackSessionId ?: run {
          Console.error("⚠️ No active loggingRule session; generating fallback session ID")
          TrailblazeSessionManager.generateSessionId("host_test_fallback")
            .also { cachedFallbackSessionId = it }
        }
      },
      caseTitleProvider = { currentCaseTitle },
    )
  }

  /**
   * The title of the trail currently being executed (e.g. an external test-case name).
   *
   * Updated at the start of each [runTrail] call so [MultiAgentV3TestAgentRunner] can
   * forward it as [RecommendationContext.overallObjective] for every step. This lets the
   * inner agent recognise impossible objectives early rather than exhausting all retries.
   *
   * Thread-safety: JUnit creates a new [BaseHostTrailblazeTest] instance per `@Test`
   * method, so only one [runTrail] call can ever execute on a given instance at a time.
   * [Volatile] provides the visibility guarantee needed when the lazy [trailblazeRunner]
   * reads the field from the coroutine's execution thread.
   */
  @Volatile
  private var currentCaseTitle: String? = null

  private val trailblazeYaml = TrailblazeYaml.Default
  private var currentToolTraceId: TraceId? = null

  private val trailblazeRunnerUtil by lazy {
    TrailblazeRunnerUtil(
      trailblazeRunner = trailblazeRunner,
      runTrailblazeTool = { trailblazeTools: List<TrailblazeTool> ->
        trailblazeAgent.runTrailblazeTools(
          trailblazeTools,
          currentToolTraceId,
          screenState = hostRunner.screenStateProvider(),
          elementComparator = elementComparator,
          screenStateProvider = hostRunner.screenStateProvider,
        ).result
      },
      trailblazeLogger = loggingRule.logger,
      sessionProvider = { loggingRule.session ?: error("Session not available - ensure test is running") },
      sessionUpdater = { loggingRule.setSession(it) },
      // Replay each step's recorded tools inside one shared execution context + snapshot frame —
      // see AndroidTrailblazeRule's identical wiring for why (cross-tool device state survival).
      sharedToolBatch = { block -> trailblazeAgent.runInSharedToolBatch(block) },
    )
  }

  /**
   * Suspend version of runTrail that checks for coroutine cancellation.
   * This allows proper cancellation propagation when running in a coroutine context.
   *
   * Returns the last successful **tool** step's [TrailblazeToolResult.Success] — the "last
   * successful tool wins" payload the host runner threads up so `trailblaze tool <read-tool>`
   * surfaces the tool's real return value. Only [TrailYamlItem.ToolTrailItem] results count:
   * prompt / trailhead steps return a bare `Success()` with no payload, so tracking them would
   * let a trailing prompt clobber a real tool payload. Null when no tool step ran. Setup-trail
   * items are preamble and never contribute to the returned result.
   */
  private suspend fun runTrail(
    trailItems: List<TrailYamlItem>,
    useRecordedSteps: Boolean,
  ): TrailblazeToolResult.Success? {
    // Capture the trail title once so caseTitleProvider in the V3 runner can read it for every
    // step. We scan for the first ConfigTrailItem rather than relying on item order so the title
    // is available even if prompts appear before the config block in the YAML.
    currentCaseTitle = trailItems.filterIsInstance<TrailYamlItem.ConfigTrailItem>()
      .firstOrNull()?.config?.title

    resolveTrailContextFromEnv()?.let { trailblazeRunner.appendToSystemPrompt(it) }
    resolveSetupTrailIdFromEnv()?.let { setupTrailId ->
      val setupFile = File(setupTrailId)
      if (setupFile.exists()) {
        val setupClassifiers = loggingRule.trailblazeDeviceInfoProvider().classifiers
        val setupItems = createTrailblazeYaml()
          .decodeTrail(setupFile.readText(), deviceClassifiers = setupClassifiers)
        for (setupItem in setupItems) {
          val result =
            when (setupItem) {
              is TrailYamlItem.PromptsTrailItem ->
                trailblazeRunnerUtil.runPromptSuspend(
                  prompts = setupItem.promptSteps,
                  useRecordedSteps = true,
                  selfHeal = false,
                )
              is TrailYamlItem.TrailheadTrailItem ->
                trailblazeRunnerUtil.runPromptSuspend(
                  prompts = listOf(setupItem.trailhead.toPromptStep()),
                  useRecordedSteps = true,
                  selfHeal = false,
                )
              is TrailYamlItem.ToolTrailItem ->
                trailblazeRunnerUtil.runTrailblazeTool(
                  setupItem.tools.map { it.trailblazeTool }
                )
              is TrailYamlItem.ConfigTrailItem -> {
                setupItem.config.context?.let { trailblazeRunner.appendToSystemPrompt(it) }
                null // ConfigTrailItem has no tool result
              }
            }
          if (result is TrailblazeToolResult.Error) {
            throw TrailblazeException("Setup trail failed: ${result.errorMessage}")
          }
        }
      }
    }
    var lastSuccess: TrailblazeToolResult.Success? = null
    for (item in trailItems) {
      val itemResult = when (item) {
        is TrailYamlItem.PromptsTrailItem ->
          // Agent-agnostic: replays recorded steps deterministically and delegates only unrecorded
          // steps to the configured runner (legacy / V3 / KOOG). Default (TRAILBLAZE_RUNNER) unchanged.
          trailblazeRunnerUtil.runPromptSuspend(
            prompts = item.promptSteps,
            useRecordedSteps = useRecordedSteps,
            selfHeal = resolveSelfHealFromEnvOrConfig(),
          )
        is TrailYamlItem.TrailheadTrailItem ->
          trailblazeRunnerUtil.runPromptSuspend(
            prompts = listOf(item.trailhead.toPromptStep()),
            useRecordedSteps = true,
            selfHeal = resolveSelfHealFromEnvOrConfig(),
          )
        is TrailYamlItem.ToolTrailItem -> trailblazeRunnerUtil.runTrailblazeTool(item.tools.map { it.trailblazeTool })
        is TrailYamlItem.ConfigTrailItem -> item.config.context?.let { trailblazeRunner.appendToSystemPrompt(it) }
      }
      if (itemResult is TrailblazeToolResult.Error) {
        throw TrailblazeException(itemResult.errorMessage)
      }
      // Only tool steps carry a payload worth surfacing. Prompt / trailhead steps return a bare
      // Success(), so tracking them would let a trailing prompt overwrite a real tool payload.
      if (item is TrailYamlItem.ToolTrailItem && itemResult is TrailblazeToolResult.Success) {
        lastSuccess = itemResult
      }
    }
    return lastSuccess
  }

  private fun resolveTrailContextFromEnv(): String? =
    System.getenv(TRAILBLAZE_TRAIL_CONTEXT_ENV)
      ?: System.getProperty(TRAILBLAZE_TRAIL_CONTEXT_ENV)

  private fun resolveSetupTrailIdFromEnv(): String? =
    System.getenv(TRAILBLAZE_SETUP_TRAIL_ID_ENV)
      ?: System.getProperty(TRAILBLAZE_SETUP_TRAIL_ID_ENV)

  // A CI pipeline runner may set TRAILBLAZE_SELF_HEAL_ENABLED on the runner step's env when the
  // pipeline config opts in. Gradle's test task forwards the parent env to the forked test JVM by
  // default, so reading it here picks up the pipeline's intent even when the subclass hardcoded
  // TrailblazeConfig.DEFAULT. Env var wins over config because the pipeline layer is the one
  // intentionally overriding for a specific run.
  //
  // Companion resolver for CLI runs: TrailCommand.resolveEffectiveSelfHeal() — a 4-tier chain
  // (flag → env → config → default). Tests have no CLI flag to honor, so this resolver is
  // intentionally a 2-tier subset.
  private fun resolveSelfHealFromEnvOrConfig(): Boolean =
    System.getenv(TRAILBLAZE_SELF_HEAL_ENABLED_ENV)?.lowercase()?.toBooleanStrictOrNull()
      ?: config.selfHeal

  /**
   * Builds the [KOOG_STRATEGY_GRAPH][AgentImplementation.KOOG_STRATEGY_GRAPH] brain as a
   * [KoogTestAgentRunner] for this Maestro host path (local Android + iOS). It does NOT run the
   * prompt loop itself — the loop lives in [TrailblazeRunnerUtil.runPromptSuspend], which replays
   * recorded steps and only delegates unrecorded ones to this runner. [trailblazeAgent] is a
   * [BaseTrailblazeAgent] for every Maestro target; the system prompt is composed exactly as
   * [createLegacyRunner] composes it for this path.
   */
  private fun createKoogRunner(): KoogTestAgentRunner = KoogTestAgentRunner(
    agent = trailblazeAgent,
    toolRepo = toolRepo,
    screenStateProvider = hostRunner.screenStateProvider,
    elementComparator = elementComparator,
    llmClient = dynamicLlmClient.createLlmClient(),
    trailblazeLlmModel = trailblazeLlmModel,
    logger = loggingRule.logger,
    sessionProvider = { loggingRule.session ?: error("Session not available - ensure test is running") },
    maxLlmCalls = null,
    systemPromptTemplate = TrailblazeRunner.composeSystemPrompt(
      platformPrompt = systemPromptTemplate,
    ),
  )

  fun runTools(tools: List<TrailblazeTool>): TrailblazeToolResult = trailblazeRunnerUtil.runTrailblazeTool(tools)

  /**
   * Suspend version of runTrailblazeYaml that properly handles coroutine cancellation.
   * Use this when calling from a coroutine context (e.g., from the desktop app).
   */
  suspend fun runTrailblazeYamlSuspend(
    yaml: String,
    trailblazeDeviceId: TrailblazeDeviceId,
    trailFilePath: String?,
    traceId: TraceId? = null,
    forceStopApp: Boolean = true,
    useRecordedSteps: Boolean = true,
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
  ): HostYamlRunResult {
    // Make sure the app is stopped before the test so the LLM doesn't get confused and think it's already running.
    if (forceStopApp) {
      ensureTargetAppIsStopped()
    }
    // Resolve device classifiers BEFORE decoding so a v3 trail lowers with the
    // right closest-wins recording for this device. v1 inputs ignore the list.
    val classifiers = loggingRule.trailblazeDeviceInfoProvider().classifiers
    // decodeTrailOrToolEnvelope (superset of decodeTrail): a trail document decodes identically; a
    // bare `- <toolName>:` envelope (single-tool MCP/CLI dispatch on host Maestro / iOS-host)
    // additionally decodes to one ToolTrailItem. Host-runner single-tool dispatch now sends the bare
    // envelope, not the legacy `- tools:` list shape.
    val trailItems: List<TrailYamlItem> = trailblazeYaml.decodeTrailOrToolEnvelope(yaml, deviceClassifiers = classifiers)
    val trailConfig = trailblazeYaml.extractTrailConfig(trailItems)

    // Honor `config.skip:` before SessionStarted is logged — matches the CLI's pre-flight
    // `planTrailExecution` planner. This base test path is exercised directly by JUnit eval
    // tests (not through the CLI), so without this short-circuit a skip-marked trail would
    // run end-to-end here even though `trailblaze run` would skip it.
    trailblazeYaml.firstSkipReason(trailItems)?.let { skipReason ->
      Console.log(
        "[Trailblaze] Skipping trail" + (trailFilePath?.let { " ($it)" } ?: "") + ": $skipReason"
      )
      return HostYamlRunResult(loggingRule.session?.sessionId ?: SessionId("unknown"))
    }

    // Seed the agent's memory before any tool runs — same [AgentMemory.seedFrom] composition
    // as every other host runner path. The agent threads this memory into every tool execution
    // context, so `{{var}}` interpolation and scripted tools' `ctx.memory` both see the seeds.
    val resolvedInitialMemory = trailblazeAgent.memory.seedFrom(
      yamlDefaults = trailConfig?.memory,
      cliSeeds = initialMemorySeeds,
      cliSensitiveSeeds = initialMemorySensitiveSeeds,
    )
    trailblazeAgent.memory.seedArgs(TrailArgBinder.decodeProvided(initialArgs))
    val sensitiveMemoryKeys: Set<String> = trailblazeAgent.memory.sensitiveKeys.toSet()

    if (sendSessionStartLog) {
      val session = loggingRule.session
      if (session != null) {
        // A session that runs under a real JUnit harness carries a Description we can read the
        // class/method from. CLI / daemon runs don't (the runner builds an anonymous
        // `object : BaseHostTrailblazeTest`, whose simpleName is empty), so derive a readable
        // `Suite::test` identity from the trail path instead of stamping this base class's name
        // — that "BaseHostTrailblazeTest::run" subtitle in the Sessions list told the reader
        // nothing and looked like a leftover JUnit artifact.
        val derivedTestIdentity = trailFilePath?.let {
          TrailRecordings.deriveTestIdentityFromTrailPath(it, fallbackClassName = "Trailblaze")
        }
        loggingRule.logger.log(
          session,
          TrailblazeLog.TrailblazeSessionStatusChangeLog(
            sessionStatus = SessionStatus.Started(
              trailConfig = trailConfig,
              trailFilePath = trailFilePath,
              testClassName = loggingRule.description?.className
                ?: this::class.java.simpleName.takeIf { it.isNotEmpty() }
                ?: derivedTestIdentity?.className
                ?: "Trailblaze",
              testMethodName = loggingRule.description?.methodName
                ?: derivedTestIdentity?.methodName
                ?: "run",
              trailblazeDeviceInfo = loggingRule.trailblazeDeviceInfoProvider(),
              rawYaml = yaml,
              hasRecordedSteps = trailblazeYaml.hasRecordedSteps(trailItems),
              trailblazeDeviceId = trailblazeDeviceId,
              resolvedInitialMemory = resolvedInitialMemory,
              sensitiveMemoryKeys = sensitiveMemoryKeys,
              targetAppInfo = MobileDeviceUtils.resolveTargetAppInfo(
                target = appTarget,
                trailblazeDeviceId = trailblazeDeviceId,
                resolvedAppId = resolvedAppIdForSession,
              ),
            ),
            session = session.sessionId,
            timestamp = Clock.System.now(),
          ),
        )
      }
    }
    currentToolTraceId = traceId
    // Register this session's IN-PROCESS scripted tools (target.tools: + catalog) into the repo so
    // the agent loop, recorded-replay re-execution, AND Kotlin composition via `invokeFrameworkTool`
    // can resolve them by name. This is the host test rule's analog of the daemon's
    // `TrailblazeHostYamlRunner` launch and the on-device `AndroidTrailblazeRule` registration —
    // without it, a recorded composite launch tool that re-runs on replay and dispatches a
    // TypeScript sub-step by name via `invokeFrameworkTool` would hit "Unknown framework tool".
    // Subprocess scripted tools are intentionally NOT
    // launched here (`includeSubprocess = false`) — no host test needs one, and `target.tools:` isn't
    // platform-scoped, so launching them would fork the web sign-in subprocess on a mobile session.
    var launchedScripting: LaunchedScriptingRuntime? = null
    var lastToolResult: TrailblazeToolResult.Success? = null
    try {
      val session = loggingRule.session
      if (session != null) {
        launchedScripting = HostScriptedToolLauncher.launch(
          targetTestApp = appTarget,
          config = config,
          sessionId = session.sessionId,
          deviceInfo = loggingRule.trailblazeDeviceInfoProvider(),
          logsRepo = hostLoggingRule.logsRepo,
          toolRepo = toolRepo,
          classLoader = javaClass.classLoader,
          logPrefix = "[BaseHostTrailblazeTest]",
          includeSubprocess = false,
          onProgressMessage = { Console.log("[BaseHostTrailblazeTest] $it") },
        )
      }
      if (!trailblazeYaml.hasActionableSteps(trailItems)) {
        val trailName = trailConfig?.title ?: trailFilePath ?: "unknown"
        val trailUrl = trailConfig?.metadata?.get("testRailUrl")
        throw xyz.block.trailblaze.exception.TrailblazeException(
          "Trail '$trailName' has no executable steps — this would be a false positive pass. " +
            "Add prompts or tool steps to this trail file." +
            (trailUrl?.let { " $it" } ?: ""),
        )
      }
      lastToolResult = runTrail(trailItems, useRecordedSteps)
    } finally {
      // Free QuickJS engines + deregister the dynamic tools so a reused repo doesn't collide on a
      // later session. NonCancellable so teardown completes even on trail timeout / abort.
      launchedScripting?.let { runtime -> withContext(NonCancellable) { runtime.shutdownAll() } }
      currentToolTraceId = null
    }
    return HostYamlRunResult(
      sessionId = loggingRule.session?.sessionId ?: SessionId("unknown"),
      lastToolResult = lastToolResult,
    )
  }

  /**
   * Non-suspend version for backwards compatibility (e.g., JUnit tests).
   * Calls the suspend version using runBlocking.
   */
  fun runTrailblazeYaml(
    yaml: String,
    trailblazeDeviceId: TrailblazeDeviceId,
    trailFilePath: String?,
    traceId: TraceId? = null,
    sendSessionStartLog: Boolean,
    forceStopApp: Boolean = true,
    useRecordedSteps: Boolean = true,
    initialMemorySeeds: Map<String, String> = emptyMap(),
    initialMemorySensitiveSeeds: Map<String, String> = emptyMap(),
  ): SessionId = runBlocking {
    // JUnit callers only care about the session id; the tool-result payload is threaded
    // through the suspend variant for the `trailblaze tool` host path.
    runTrailblazeYamlSuspend(
      yaml = yaml,
      trailblazeDeviceId = trailblazeDeviceId,
      trailFilePath = trailFilePath,
      traceId = traceId,
      forceStopApp = forceStopApp,
      useRecordedSteps = useRecordedSteps,
      sendSessionStartLog = sendSessionStartLog,
      initialMemorySeeds = initialMemorySeeds,
      initialMemorySensitiveSeeds = initialMemorySensitiveSeeds,
    ).sessionId ?: SessionId("unknown")
  }

  fun runFromResource(
    path: String = TrailblazeYamlUtil.calculateTrailblazeYamlAssetPathFromStackTrace(
      TemplatingUtil::doesResourceExist,
    ),
    forceStopApp: Boolean = true,
    useRecordedSteps: Boolean = true,
  ) {
    val computedResourcePath: String = TrailRecordings.findBestTrailResourcePath(
      path = path,
      deviceClassifiers = trailblazeDeviceClassifiers,
      doesResourceExist = TemplatingUtil::doesResourceExist,
    ) ?: throw TrailblazeException("Resource not found: $path")
    Console.log("Running from resource: $computedResourcePath")
    val trailYamlFromResource: String = TemplatingUtil.getResourceAsText(computedResourcePath)
      ?: error("No YAML resource found at $computedResourcePath")
    runTrailblazeYaml(
      yaml = trailYamlFromResource,
      forceStopApp = forceStopApp,
      useRecordedSteps = useRecordedSteps,
      trailFilePath = computedResourcePath,
      sendSessionStartLog = true,
      trailblazeDeviceId = trailblazeDeviceId,
    )
  }
}
