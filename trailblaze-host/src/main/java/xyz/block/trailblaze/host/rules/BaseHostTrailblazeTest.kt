package xyz.block.trailblaze.host.rules

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.message.AttachmentContent
import ai.koog.prompt.message.ContentPart
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.params.LLMParams
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Rule
import org.junit.rules.RuleChain
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.TrailblazeYamlUtil
import xyz.block.trailblaze.agent.BlazeConfig
import xyz.block.trailblaze.agent.DefaultProgressReporter
import xyz.block.trailblaze.agent.InnerLoopScreenAnalyzer
import xyz.block.trailblaze.agent.MultiAgentV3Runner
import xyz.block.trailblaze.agent.MultiAgentV3TestAgentRunner
import xyz.block.trailblaze.agent.TrailblazeElementComparator
import xyz.block.trailblaze.agent.TrailblazeRunner
import xyz.block.trailblaze.agent.blaze.PlannerLlmCall
import xyz.block.trailblaze.agent.blaze.PlannerToolCallResult
import xyz.block.trailblaze.api.ImageFormatDetector
import xyz.block.trailblaze.api.TestAgentRunner
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDriverType
import java.io.File
import xyz.block.trailblaze.exception.TrailblazeException
import xyz.block.trailblaze.yaml.createTrailblazeYaml
import xyz.block.trailblaze.host.HostMaestroTrailblazeAgent
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
import xyz.block.trailblaze.mcp.sampling.LocalLlmSamplingSource
import xyz.block.trailblaze.model.TrailblazeConfig
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.recordings.TrailRecordings
import xyz.block.trailblaze.rules.RetryRule
import xyz.block.trailblaze.rules.TrailblazeLoggingRule
import xyz.block.trailblaze.rules.TrailblazeRunnerUtil
import xyz.block.trailblaze.toolcalls.ToolName
import xyz.block.trailblaze.toolcalls.TrailblazeKoogTool.Companion.toTrailblazeToolDescriptor
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.TrailblazeToolSet
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.util.TemplatingUtil
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
  appTarget: TrailblazeHostAppTarget? = null,
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
      screenshotScalingConfig = trailblazeLlmModel.screenshotScalingConfig,
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

  val trailblazeAgent by lazy {
    HostMaestroTrailblazeAgent(
      maestroHostRunner = hostRunner,
      trailblazeLogger = loggingRule.logger,
      trailblazeDeviceInfoProvider = loggingRule.trailblazeDeviceInfoProvider,
      sessionProvider = { loggingRule.session ?: error("Session not available - ensure test is running") },
      nodeSelectorMode = config.nodeSelectorMode,
      trailblazeToolRepo = toolRepo,
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

  val toolRepo = if (trailblazeToolSet != null) {
    // Explicit tool set override — bypass dynamic catalog
    TrailblazeToolRepo(
      trailblazeToolSet = TrailblazeToolSet.DynamicTrailblazeToolSet(
        name = "Custom Tool Set",
        toolClasses = trailblazeToolSet.toolClasses + customToolClasses - excludedToolClasses,
        yamlToolNames = trailblazeToolSet.yamlToolNames + customYamlToolNames,
      ),
    )
  } else {
    TrailblazeToolRepo.withDynamicToolSets(
      customToolClasses = customToolClasses,
      customYamlToolNames = customYamlToolNames,
      excludedToolClasses = excludedToolClasses,
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
      agentMemory = (trailblazeAgent as? xyz.block.trailblaze.BaseTrailblazeAgent)?.memory ?: AgentMemory(),
    )

    val plannerLlmCall: PlannerLlmCall = { systemPrompt, userMessage, tools, traceId, screenshotBytes ->
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
        messages = listOf(
          Message.System(content = systemPrompt, metaInfo = metaInfo),
          userMsg,
        ),
        id = "host_test_planner",
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

    val session = loggingRule.session ?: error("Session not available - ensure test is running")
    val progressListener = loggingRule.logger.createProgressListener(session)
    val progressReporter = DefaultProgressReporter(progressListener)

    val availableToolsProvider = {
      toolRepo.getCurrentToolDescriptors().map { it.toTrailblazeToolDescriptor() }
    }

    val v3Runner = MultiAgentV3Runner.create(
      screenAnalyzer = screenAnalyzer,
      executor = executor,
      plannerLlmCall = plannerLlmCall,
      config = BlazeConfig.DEFAULT,
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
   * The title of the trail currently being executed (e.g. TestRail case name).
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
    )
  }

  /**
   * Suspend version of runTrail that checks for coroutine cancellation.
   * This allows proper cancellation propagation when running in a coroutine context.
   */
  private suspend fun runTrail(trailItems: List<TrailYamlItem>, useRecordedSteps: Boolean) {
    // Capture the trail title once so caseTitleProvider in the V3 runner can read it for every
    // step. We scan for the first ConfigTrailItem rather than relying on item order so the title
    // is available even if prompts appear before the config block in the YAML.
    currentCaseTitle = trailItems.filterIsInstance<TrailYamlItem.ConfigTrailItem>()
      .firstOrNull()?.config?.title

    resolveTrailContextFromEnv()?.let { trailblazeRunner.appendToSystemPrompt(it) }
    resolveSetupTrailIdFromEnv()?.let { setupTrailId ->
      val setupFile = File(setupTrailId)
      if (setupFile.exists()) {
        val setupItems = createTrailblazeYaml().decodeTrail(setupFile.readText())
        for (setupItem in setupItems) {
          val result =
            when (setupItem) {
              is TrailYamlItem.PromptsTrailItem ->
                trailblazeRunnerUtil.runPromptSuspend(
                  prompts = setupItem.promptSteps,
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
    for (item in trailItems) {
      val itemResult = when (item) {
        is TrailYamlItem.PromptsTrailItem ->
          trailblazeRunnerUtil.runPromptSuspend(
            prompts = item.promptSteps,
            useRecordedSteps = useRecordedSteps,
            selfHeal = resolveSelfHealFromEnvOrConfig(),
          )
        is TrailYamlItem.ToolTrailItem -> trailblazeRunnerUtil.runTrailblazeTool(item.tools.map { it.trailblazeTool })
        is TrailYamlItem.ConfigTrailItem -> item.config.context?.let { trailblazeRunner.appendToSystemPrompt(it) }
      }
      if (itemResult is TrailblazeToolResult.Error) {
        throw TrailblazeException(itemResult.errorMessage)
      }
    }
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
  ): SessionId {
    // Make sure the app is stopped before the test so the LLM doesn't get confused and think it's already running.
    if (forceStopApp) {
      ensureTargetAppIsStopped()
    }
    val trailItems: List<TrailYamlItem> = trailblazeYaml.decodeTrail(yaml)
    val trailConfig = trailblazeYaml.extractTrailConfig(trailItems)

    if (sendSessionStartLog) {
      val session = loggingRule.session
      if (session != null) {
        loggingRule.logger.log(
          session,
          TrailblazeLog.TrailblazeSessionStatusChangeLog(
            sessionStatus = SessionStatus.Started(
              trailConfig = trailConfig,
              trailFilePath = trailFilePath,
              testClassName = loggingRule.description?.className
                ?: this::class.java.simpleName.takeIf { it.isNotEmpty() }
                ?: "BaseHostTrailblazeTest",
              testMethodName = loggingRule.description?.methodName ?: "run",
              trailblazeDeviceInfo = loggingRule.trailblazeDeviceInfoProvider(),
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
    currentToolTraceId = traceId
    try {
      if (!trailblazeYaml.hasActionableSteps(trailItems)) {
        val trailName = trailConfig?.title ?: trailFilePath ?: "unknown"
        val trailUrl = trailConfig?.metadata?.get("testRailUrl")
        throw xyz.block.trailblaze.exception.TrailblazeException(
          "Trail '$trailName' has no executable steps — this would be a false positive pass. " +
            "Add prompts or tool steps to this trail file." +
            (trailUrl?.let { " $it" } ?: ""),
        )
      }
      runTrail(trailItems, useRecordedSteps)
    } finally {
      currentToolTraceId = null
    }
    return loggingRule.session?.sessionId ?: SessionId("unknown")
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
  ) = runBlocking {
    runTrailblazeYamlSuspend(
      yaml = yaml,
      trailblazeDeviceId = trailblazeDeviceId,
      trailFilePath = trailFilePath,
      traceId = traceId,
      forceStopApp = forceStopApp,
      useRecordedSteps = useRecordedSteps,
      sendSessionStartLog = sendSessionStartLog
    )
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
