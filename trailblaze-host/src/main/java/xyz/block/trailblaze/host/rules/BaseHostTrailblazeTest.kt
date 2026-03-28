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
import xyz.block.trailblaze.exception.TrailblazeException
import xyz.block.trailblaze.host.HostMaestroTrailblazeAgent
import xyz.block.trailblaze.host.MaestroHostRunnerImpl
import xyz.block.trailblaze.agent.AgentUiActionExecutor
import xyz.block.trailblaze.host.devices.TrailblazeConnectedDevice
import xyz.block.trailblaze.host.devices.TrailblazeDeviceService
import xyz.block.trailblaze.host.devices.TrailblazeHostDeviceClassifier
import xyz.block.trailblaze.host.rules.TrailblazeHostLlmConfig.DEFAULT_TRAILBLAZE_LLM_MODEL
import xyz.block.trailblaze.http.DynamicLlmClient
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.mcp.AgentImplementation
import xyz.block.trailblaze.mcp.sampling.LocalLlmSamplingSource
import xyz.block.trailblaze.model.TrailblazeConfig
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.recordings.TrailRecordings
import java.io.File
import xyz.block.trailblaze.rules.RetryRule
import xyz.block.trailblaze.rules.TrailblazeLoggingRule
import xyz.block.trailblaze.rules.TrailblazeRunnerUtil
import xyz.block.trailblaze.toolcalls.TrailblazeKoogTool.Companion.toTrailblazeToolDescriptor
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.TrailblazeToolSet
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.util.TemplatingUtil
import xyz.block.trailblaze.yaml.TrailConfig
import xyz.block.trailblaze.yaml.TrailYamlItem
import xyz.block.trailblaze.yaml.createTrailblazeYaml
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
  excludedToolClasses: Set<KClass<out TrailblazeTool>> = setOf(),
  /** All custom tool classes for YAML serialization/deserialization. Defaults to [customToolClasses]. */
  allSerializationToolClasses: Set<KClass<out TrailblazeTool>> = customToolClasses,
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
      setOfMarkEnabled = config.setOfMarkEnabled,
      trailblazeLogger = loggingRule.logger,
      sessionProvider = { loggingRule.session ?: error("Session not available - ensure test is running") },
      appTarget = appTarget,
      deviceClassifiers = trailblazeDeviceClassifiers,
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
      maestroDeviceInfoProvider = { connectedDevice.initialMaestroDeviceInfo },
    ).getDeviceClassifiers()
  }

  val trailblazeDeviceInfo: TrailblazeDeviceInfo by lazy {
    val initialMaestroDeviceInfo = connectedDevice.initialMaestroDeviceInfo
    TrailblazeDeviceInfo(
      trailblazeDeviceId = trailblazeDeviceId,
      trailblazeDriverType = trailblazeDriverType,
      widthPixels = initialMaestroDeviceInfo.widthPixels,
      heightPixels = initialMaestroDeviceInfo.heightPixels,
      classifiers = trailblazeDeviceClassifiers,
    )
  }

  val hostLoggingRule: HostTrailblazeLoggingRule = HostTrailblazeLoggingRule(
    trailblazeDeviceInfoProvider = {
      trailblazeDeviceInfo
    },
  )

  val loggingRule: TrailblazeLoggingRule = hostLoggingRule

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
        "Custom Tool Set",
        trailblazeToolSet.toolClasses + customToolClasses - excludedToolClasses,
      ),
    )
  } else {
    TrailblazeToolRepo.withDynamicToolSets(
      setOfMarkEnabled = config.setOfMarkEnabled,
      customToolClasses = customToolClasses,
      excludedToolClasses = excludedToolClasses,
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

    return MultiAgentV3TestAgentRunner(
      v3Runner = v3Runner,
      screenStateProvider = hostRunner.screenStateProvider,
      sessionIdProvider = { loggingRule.session?.sessionId ?: SessionId.generate() },
    )
  }

  private val trailblazeYaml = createTrailblazeYaml(
    customTrailblazeToolClasses = allSerializationToolClasses,
  )

  private val trailblazeRunnerUtil by lazy {
    TrailblazeRunnerUtil(
      trailblazeRunner = trailblazeRunner,
      runTrailblazeTool = { trailblazeTools: List<TrailblazeTool> ->
        val result = trailblazeAgent.runTrailblazeTools(
          trailblazeTools,
          null,
          screenState = hostRunner.screenStateProvider(),
          elementComparator = elementComparator,
          screenStateProvider = hostRunner.screenStateProvider,
        )
        when (val toolResult = result.result) {
          is TrailblazeToolResult.Success -> toolResult
          is TrailblazeToolResult.Error -> throw TrailblazeException(toolResult.errorMessage)
        }
      },
      trailblazeLogger = loggingRule.logger,
      sessionProvider = { loggingRule.session ?: error("Session not available - ensure test is running") },
    )
  }

  /**
   * Loads and executes prerequisite trails before the main trail.
   *
   * For each prerequisite ID, locates its trail directory (sibling to the current trail),
   * loads the NL definition file, decodes it, and runs it. Emits PrerequisiteStart/Complete
   * logs so the session detail page shows prerequisite execution.
   */
  private suspend fun runPrerequisiteTrails(
    prerequisiteIds: List<String>,
    trailFilePath: String?,
    currentTrailId: String,
    useRecordedSteps: Boolean,
    visited: MutableSet<String> = mutableSetOf(),
  ) {
    if (prerequisiteIds.isEmpty() || trailFilePath == null) return
    visited.add(currentTrailId)

    // Derive the trails root directory from the current trail's file path.
    // Trail files live at: <trailsDir>/<trailId>/blaze.yaml (or *.trail.yaml)
    val trailFile = File(trailFilePath)
    val trailDir = trailFile.parentFile ?: return
    val trailsRootDir = trailDir.parentFile ?: return

    val session = loggingRule.session

    for (prereqId in prerequisiteIds) {
      val prereqDir = File(trailsRootDir, prereqId)
      if (!prereqDir.isDirectory) {
        Console.log("⚠️ Prerequisite trail directory not found: $prereqId")
        continue
      }

      // Find the NL definition file (blaze.yaml or trailblaze.yaml)
      val prereqYamlFile = TrailRecordings.NL_DEFINITION_FILENAMES
        .map { File(prereqDir, it) }
        .firstOrNull { it.exists() }

      if (prereqYamlFile == null) {
        Console.log("⚠️ No NL definition file found for prerequisite: $prereqId")
        continue
      }

      val prereqYaml: String
      val prereqItems: List<TrailYamlItem>
      val prereqConfig: TrailConfig?
      try {
        prereqYaml = prereqYamlFile.readText()
        prereqItems = trailblazeYaml.decodeTrail(prereqYaml)
        prereqConfig = trailblazeYaml.extractTrailConfig(prereqItems)
      } catch (e: Exception) {
        Console.log("⚠️ Failed to load prerequisite '$prereqId': ${e.message}")
        continue
      }

      // Recursively run nested prerequisites before this one
      val nestedPrereqs = prereqConfig?.prerequisites.orEmpty().filter { it !in visited }
      if (nestedPrereqs.isNotEmpty()) {
        runPrerequisiteTrails(
          prerequisiteIds = nestedPrereqs,
          trailFilePath = prereqYamlFile.absolutePath,
          currentTrailId = prereqId,
          useRecordedSteps = useRecordedSteps,
          visited = visited,
        )
      }

      val prereqTitle = prereqConfig?.title
      val totalStepCount = prereqItems.filterIsInstance<TrailYamlItem.PromptsTrailItem>()
        .sumOf { it.promptSteps.size }

      // Emit prerequisite start log
      if (session != null) {
        loggingRule.logger.log(
          session,
          TrailblazeLog.PrerequisiteStartLog(
            prerequisiteTrailId = prereqId,
            prerequisiteTitle = prereqTitle,
            parentTrailId = currentTrailId,
            session = session.sessionId,
            timestamp = Clock.System.now(),
          ),
        )
      }

      Console.log("▶️ Running prerequisite: ${prereqTitle ?: prereqId} ($totalStepCount steps)")
      val startTime = Clock.System.now()
      var passed = true
      var failureReason: String? = null
      var stepsExecuted = 0

      try {
        for (item in prereqItems) {
          val itemResult = when (item) {
            is TrailYamlItem.PromptsTrailItem -> {
              trailblazeRunnerUtil.runPromptSuspend(item.promptSteps, useRecordedSteps)
            }
            is TrailYamlItem.ToolTrailItem -> trailblazeRunnerUtil.runTrailblazeTool(item.tools.map { it.trailblazeTool })
            is TrailYamlItem.ConfigTrailItem -> item.config.context?.let { trailblazeRunner.appendToSystemPrompt(it) }
          }
          if (itemResult is TrailblazeToolResult.Error) {
            throw TrailblazeException(itemResult.errorMessage)
          }
          if (item is TrailYamlItem.PromptsTrailItem) {
            stepsExecuted += item.promptSteps.size
          }
        }
      } catch (e: Exception) {
        passed = false
        failureReason = e.message
        Console.log("❌ Prerequisite '$prereqId' failed: ${e.message}")
      }

      val durationMs = (Clock.System.now() - startTime).inWholeMilliseconds

      // Emit prerequisite complete log
      if (session != null) {
        loggingRule.logger.log(
          session,
          TrailblazeLog.PrerequisiteCompleteLog(
            prerequisiteTrailId = prereqId,
            prerequisiteTitle = prereqTitle,
            parentTrailId = currentTrailId,
            passed = passed,
            stepsExecuted = stepsExecuted,
            durationMs = durationMs,
            failureReason = failureReason,
            session = session.sessionId,
            timestamp = Clock.System.now(),
          ),
        )
      }

      if (passed) {
        Console.log("✅ Prerequisite '$prereqId' passed (${durationMs}ms)")
      } else {
        throw TrailblazeException("Prerequisite trail '$prereqId' failed: $failureReason")
      }
    }
  }

  /**
   * Suspend version of runTrail that checks for coroutine cancellation.
   * This allows proper cancellation propagation when running in a coroutine context.
   */
  private suspend fun runTrail(trailItems: List<TrailYamlItem>, useRecordedSteps: Boolean) {
    for (item in trailItems) {
      val itemResult = when (item) {
        is TrailYamlItem.PromptsTrailItem -> trailblazeRunnerUtil.runPromptSuspend(item.promptSteps, useRecordedSteps)
        is TrailYamlItem.ToolTrailItem -> trailblazeRunnerUtil.runTrailblazeTool(item.tools.map { it.trailblazeTool })
        is TrailYamlItem.ConfigTrailItem -> item.config.context?.let { trailblazeRunner.appendToSystemPrompt(it) }
      }
      if (itemResult is TrailblazeToolResult.Error) {
        throw TrailblazeException(itemResult.errorMessage)
      }
    }
  }

  fun runTools(tools: List<TrailblazeTool>): TrailblazeToolResult = trailblazeRunnerUtil.runTrailblazeTool(tools)

  /**
   * Suspend version of runTrailblazeYaml that properly handles coroutine cancellation.
   * Use this when calling from a coroutine context (e.g., from the desktop app).
   */
  suspend fun runTrailblazeYamlSuspend(
    yaml: String,
    trailblazeDeviceId: TrailblazeDeviceId,
    trailFilePath: String?,
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

    // Execute prerequisite trails before the main trail
    val prerequisiteIds = trailConfig?.prerequisites.orEmpty()
    val currentTrailId = trailConfig?.id ?: trailFilePath?.let { File(it).parentFile?.name } ?: "unknown"
    runPrerequisiteTrails(prerequisiteIds, trailFilePath, currentTrailId, useRecordedSteps)

    // Run the main trail
    runTrail(trailItems, useRecordedSteps)
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
    sendSessionStartLog: Boolean,
    forceStopApp: Boolean = true,
    useRecordedSteps: Boolean = true,
  ) = runBlocking {
    runTrailblazeYamlSuspend(
      yaml = yaml,
      trailblazeDeviceId = trailblazeDeviceId,
      trailFilePath = trailFilePath,
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
