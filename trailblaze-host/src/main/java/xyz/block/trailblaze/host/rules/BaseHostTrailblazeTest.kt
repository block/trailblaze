package xyz.block.trailblaze.host.rules

import org.junit.Rule
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
  val trailblazeLlmModel: TrailblazeLlmModel = DEFAULT_TRAILBLAZE_LLM_MODEL,
  val dynamicLlmClient: DynamicLlmClient = TrailblazeHostDynamicLlmClientProvider(
    trailblazeLlmModel = trailblazeLlmModel,
    trailblazeDynamicLlmTokenProvider = TrailblazeHostDynamicLlmTokenProvider,
  ),
  setOfMarkEnabled: Boolean = true,
  systemPromptTemplate: String? = null,
  trailblazeToolSet: TrailblazeToolSet? = null,
  customToolClasses: Set<KClass<out TrailblazeTool>> = setOf(),
  val sessionManager: TrailblazeSessionManager = TrailblazeSessionManager(),
) {
  val hostRunner by lazy {
    MaestroHostRunnerImpl(
      requestedPlatform = trailblazeDriverType.platform,
      setOfMarkEnabled = setOfMarkEnabled,
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

  @get:Rule
  val loggingRule: TrailblazeLoggingRule = HostTrailblazeLoggingRule(
    trailblazeDeviceInfoProvider = { trailblazeDeviceInfo },
    sessionManager = sessionManager,
  )

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
          ?: TrailblazeToolSet.getSetOfMarkToolSet(setOfMarkEnabled).toolClasses
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

  private val elementComparator = TrailblazeElementComparator(
    screenStateProvider = hostRunner.screenStateProvider,
    llmClient = dynamicLlmClient.createLlmClient(),
    trailblazeLlmModel = trailblazeLlmModel,
    toolRepo = toolRepo,
  )

  private val trailblazeYaml = TrailblazeYaml(
    customTrailblazeToolClasses = customToolClasses,
  )

  private val trailblazeRunnerUtil = TrailblazeRunnerUtil(
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
      className = loggingRule.description?.className ?: this::class.java.simpleName,
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
