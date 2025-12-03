package xyz.block.trailblaze.host.rules

import org.junit.Rule
import org.junit.rules.RuleChain
import xyz.block.trailblaze.TrailblazeYamlUtil
import xyz.block.trailblaze.agent.TrailblazeElementComparator
import xyz.block.trailblaze.agent.TrailblazeRunner
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.exception.TrailblazeException
import xyz.block.trailblaze.host.HostMaestroTrailblazeAgent
import xyz.block.trailblaze.host.MaestroHostRunnerImpl
import xyz.block.trailblaze.host.devices.TrailblazeHostDeviceClassifier
import xyz.block.trailblaze.host.rules.TrailblazeHostLlmConfig.DEFAULT_TRAILBLAZE_LLM_MODEL
import xyz.block.trailblaze.http.DynamicLlmClient
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.model.TrailblazeConfig
import xyz.block.trailblaze.rules.RetryRule
import xyz.block.trailblaze.rules.TrailblazeLoggingRule
import xyz.block.trailblaze.rules.TrailblazeRunnerUtil
import xyz.block.trailblaze.session.TrailblazeSessionManager
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
  val sessionManager: TrailblazeSessionManager = TrailblazeSessionManager(),
  maxRetries: Int = 0,
) {

  val hostRunner by lazy {
    MaestroHostRunnerImpl(
      requestedPlatform = trailblazeDriverType.platform,
      setOfMarkEnabled = config.setOfMarkEnabled,
      trailblazeLogger = loggingRule.trailblazeLogger,
    )
  }

  /**
   * Makes sure the targeted app is closed
   */
  abstract fun ensureTargetAppIsStopped()

  val trailblazeDeviceInfo: TrailblazeDeviceInfo by lazy {
    val initialMaestroDeviceInfo = hostRunner.connectedDevice.initialMaestroDeviceInfo
    TrailblazeDeviceInfo(
      trailblazeDriverType = trailblazeDriverType,
      widthPixels = initialMaestroDeviceInfo.widthPixels,
      heightPixels = initialMaestroDeviceInfo.heightPixels,
      classifiers = TrailblazeHostDeviceClassifier(
        trailblazeDriverType = trailblazeDriverType,
        maestroDeviceInfoProvider = { hostRunner.connectedDevice.initialMaestroDeviceInfo },
      ).getDeviceClassifiers(),
    )
  }

  val loggingRule: TrailblazeLoggingRule = HostTrailblazeLoggingRule(
    trailblazeDeviceInfoProvider = { trailblazeDeviceInfo },
    sessionManager = sessionManager,
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
      trailblazeLogger = loggingRule.trailblazeLogger,
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
      trailblazeLogger = loggingRule.trailblazeLogger,
      sessionManager = sessionManager,
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
      trailblazeLogger = loggingRule.trailblazeLogger,
      sessionManager = sessionManager,
    )
  }

  private fun runTrail(trailItems: List<TrailYamlItem>, useRecordedSteps: Boolean) {
    for (item in trailItems) {
      val itemResult = when (item) {
        is TrailYamlItem.MaestroTrailItem -> hostRunner.runMaestroCommands(
          item.maestro.maestroCommands.asMaestroCommands(),
          null,
        )

        is TrailYamlItem.PromptsTrailItem -> trailblazeRunnerUtil.runPrompt(item.promptSteps, useRecordedSteps)
        is TrailYamlItem.ToolTrailItem -> trailblazeRunnerUtil.runTrailblazeTool(item.tools.map { it.trailblazeTool })
        is TrailYamlItem.ConfigTrailItem -> item.config.context?.let { trailblazeRunner.appendToSystemPrompt(it) }
      }
      if (itemResult is TrailblazeToolResult.Error) {
        throw TrailblazeException(itemResult.errorMessage)
      }
    }
  }

  fun runTools(tools: List<TrailblazeTool>): TrailblazeToolResult = trailblazeRunnerUtil.runTrailblazeTool(tools)

  fun runTrailblazeYaml(
    yaml: String,
    forceStopApp: Boolean = true,
    useRecordedSteps: Boolean = true,
  ) {
    // Make sure the app is stopped before the test so the LLM doesn't get confused and think it's already running.
    if (forceStopApp) {
      ensureTargetAppIsStopped()
    }
    val trailItems: List<TrailYamlItem> = trailblazeYaml.decodeTrail(yaml)
    val trailConfig = trailblazeYaml.extractTrailConfig(trailItems)
    loggingRule.trailblazeLogger.sendStartLog(
      trailConfig = trailConfig,
      className = loggingRule.description?.className
        ?: this::class.java.simpleName.takeIf { it.isNotEmpty() }
        ?: "BaseHostTrailblazeTest",
      methodName = loggingRule.description?.methodName ?: "run",
      trailblazeDeviceInfo = loggingRule.trailblazeDeviceInfoProvider(),
      rawYaml = yaml,
    )
    return runTrail(trailItems, useRecordedSteps)
  }

  fun runFromResource(
    path: String = TrailblazeYamlUtil.calculateTrailblazeYamlAssetPathFromStackTrace(
      TemplatingUtil::doesResourceExist,
    ),
    forceStopApp: Boolean = true,
    useRecordedSteps: Boolean = true,
  ) {
    val trailYamlFromResource: String = TemplatingUtil.getResourceAsText(path)
      ?: error("No YAML resource found at $path")
    runTrailblazeYaml(
      yaml = trailYamlFromResource,
      forceStopApp = forceStopApp,
      useRecordedSteps = useRecordedSteps,
    )
  }
}
