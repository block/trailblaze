package xyz.block.trailblaze.host.rules

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import org.junit.Rule
import org.junit.rules.RuleChain
import xyz.block.trailblaze.TrailblazeYamlUtil
import xyz.block.trailblaze.agent.TrailblazeElementComparator
import xyz.block.trailblaze.agent.TrailblazeRunner
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.exception.TrailblazeException
import xyz.block.trailblaze.host.HostMaestroTrailblazeAgent
import xyz.block.trailblaze.host.MaestroHostRunnerImpl
import xyz.block.trailblaze.host.devices.TrailblazeDeviceService
import xyz.block.trailblaze.host.devices.TrailblazeHostDeviceClassifier
import xyz.block.trailblaze.host.rules.TrailblazeHostLlmConfig.DEFAULT_TRAILBLAZE_LLM_MODEL
import xyz.block.trailblaze.http.DynamicLlmClient
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.model.TrailblazeConfig
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.recordings.TrailRecordings
import xyz.block.trailblaze.rules.RetryRule
import xyz.block.trailblaze.rules.TrailblazeLoggingRule
import xyz.block.trailblaze.rules.TrailblazeRunnerUtil
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.TrailblazeToolSet
import xyz.block.trailblaze.util.TemplatingUtil
import xyz.block.trailblaze.utils.Ext.asMaestroCommands
import xyz.block.trailblaze.yaml.TrailYamlItem
import xyz.block.trailblaze.yaml.TrailblazeYaml
import kotlin.reflect.KClass

abstract class BaseHostTrailblazeTest(
  trailblazeDriverType: TrailblazeDriverType,
  val config: TrailblazeConfig = TrailblazeConfig.DEFAULT,
  val trailblazeLlmModel: TrailblazeLlmModel = DEFAULT_TRAILBLAZE_LLM_MODEL,
  val dynamicLlmClient: DynamicLlmClient = TrailblazeHostDynamicLlmClientProvider(
    trailblazeLlmModel = trailblazeLlmModel,
    trailblazeDynamicLlmTokenProvider = TrailblazeHostDynamicLlmTokenProvider,
  ),
  systemPromptTemplate: String? = null,
  trailblazeToolSet: TrailblazeToolSet? = null,
  customToolClasses: Set<KClass<out TrailblazeTool>> = setOf(),
  maxRetries: Int = 0,
  appTarget: TrailblazeHostAppTarget? = null,
  protected val trailblazeDeviceId: TrailblazeDeviceId = TrailblazeDeviceService.listConnectedTrailblazeDevices()
    .firstOrNull { it.trailblazeDevicePlatform == trailblazeDriverType.platform }
    ?: error("No connected ${trailblazeDriverType.platform} device found")
) {

  val hostRunner by lazy {
    MaestroHostRunnerImpl(
      trailblazeDeviceId = trailblazeDeviceId,
      setOfMarkEnabled = config.setOfMarkEnabled,
      trailblazeLogger = loggingRule.logger,
      sessionProvider = { loggingRule.session ?: error("Session not available - ensure test is running") },
      appTarget = appTarget,
    )
  }

  /**
   * Makes sure the targeted app is closed
   */
  abstract fun ensureTargetAppIsStopped()

  val trailblazeDeviceClassifiers: List<TrailblazeDeviceClassifier> by lazy {
    TrailblazeHostDeviceClassifier(
      trailblazeDriverType = trailblazeDriverType,
      maestroDeviceInfoProvider = { hostRunner.connectedDevice.initialMaestroDeviceInfo },
    ).getDeviceClassifiers()
  }

  val trailblazeDeviceInfo: TrailblazeDeviceInfo by lazy {
    val initialMaestroDeviceInfo = hostRunner.connectedDevice.initialMaestroDeviceInfo
    TrailblazeDeviceInfo(
      trailblazeDeviceId = trailblazeDeviceId,
      trailblazeDriverType = trailblazeDriverType,
      widthPixels = initialMaestroDeviceInfo.widthPixels,
      heightPixels = initialMaestroDeviceInfo.heightPixels,
      classifiers = trailblazeDeviceClassifiers,
    )
  }

  val loggingRule: TrailblazeLoggingRule = HostTrailblazeLoggingRule(
    trailblazeDeviceInfoProvider = {
      trailblazeDeviceInfo
    },
  )

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
    )
  }

  val toolRepo = TrailblazeToolRepo(
    TrailblazeToolSet.DynamicTrailblazeToolSet(
      "Dynamic Initial Tool Set",
      (
          trailblazeToolSet?.toolClasses
            ?: TrailblazeToolSet.getLlmToolSet(config.setOfMarkEnabled).toolClasses
          ) + customToolClasses,
    ),
  )

  val trailblazeRunner: TrailblazeRunner by lazy {
    TrailblazeRunner(
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

  private val elementComparator by lazy {
    TrailblazeElementComparator(
      screenStateProvider = hostRunner.screenStateProvider,
      llmClient = dynamicLlmClient.createLlmClient(),
      trailblazeLlmModel = trailblazeLlmModel,
      toolRepo = toolRepo,
    )
  }

  private val trailblazeYaml = TrailblazeYaml(
    customTrailblazeToolClasses = customToolClasses,
  )

  private val trailblazeRunnerUtil by lazy {
    TrailblazeRunnerUtil(
      trailblazeRunner = trailblazeRunner,
      runTrailblazeTool = { trailblazeTools: List<TrailblazeTool> ->
        val result = trailblazeRunner.agent.runTrailblazeTools(
          trailblazeTools,
          null,
          screenState = hostRunner.screenStateProvider(),
          elementComparator = elementComparator,
        )
        when (val toolResult = result.result) {
          is TrailblazeToolResult.Success -> toolResult
          is TrailblazeToolResult.Error -> throw TrailblazeException(toolResult.errorMessage)
        }
      },
    )
  }

  /**
   * Suspend version of runTrail that checks for coroutine cancellation.
   * This allows proper cancellation propagation when running in a coroutine context.
   */
  private suspend fun runTrail(trailItems: List<TrailYamlItem>, useRecordedSteps: Boolean) {
    for (item in trailItems) {
      val itemResult = when (item) {
        is TrailYamlItem.MaestroTrailItem -> hostRunner.runMaestroCommands(
          item.maestro.maestroCommands.asMaestroCommands(),
          null,
        )

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
    println("Running from resource: $computedResourcePath")
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
