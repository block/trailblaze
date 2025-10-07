package xyz.block.trailblaze.host.rules

import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import org.junit.Rule
import xyz.block.trailblaze.agent.TrailblazeElementComparator
import xyz.block.trailblaze.agent.TrailblazeRunner
import xyz.block.trailblaze.api.JvmOpenAiApiKeyUtil
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.exception.TrailblazeException
import xyz.block.trailblaze.host.HostMaestroTrailblazeAgent
import xyz.block.trailblaze.host.MaestroHostRunnerImpl
import xyz.block.trailblaze.host.devices.TrailblazeHostDeviceClassifier
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.llm.providers.OpenAITrailblazeLlmModelList
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.rules.TestStackTraceUtil
import xyz.block.trailblaze.rules.TrailblazeLoggingRule
import xyz.block.trailblaze.rules.TrailblazeRunnerUtil
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.TrailblazeToolSet
import xyz.block.trailblaze.util.TemplatingUtil
import xyz.block.trailblaze.yaml.TrailYamlItem
import xyz.block.trailblaze.yaml.TrailblazeYaml
import kotlin.reflect.KClass

abstract class BaseHostTrailblazeTest(
  trailblazeDriverType: TrailblazeDriverType,
  setOfMarkEnabled: Boolean = true,
  systemPromptTemplate: String? = null,
  trailblazeToolSet: TrailblazeToolSet? = null,
  customToolClasses: Set<KClass<out TrailblazeTool>> = setOf(),
) {

  val hostRunner by lazy {
    MaestroHostRunnerImpl(
      requestedPlatform = trailblazeDriverType.platform,
      setOfMarkEnabled = setOfMarkEnabled,
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
  )

  val trailblazeAgent by lazy {
    HostMaestroTrailblazeAgent(
      maestroHostRunner = hostRunner,
    )
  }

  val llmClient: LLMClient = OpenAILLMClient(JvmOpenAiApiKeyUtil.getApiKeyFromEnv())

  val trailblazeLlmModel: TrailblazeLlmModel = OpenAITrailblazeLlmModelList.OPENAI_GPT_4_1

  val toolRepo = TrailblazeToolRepo(
    TrailblazeToolSet.DynamicTrailblazeToolSet(
      "Dynamic Initial Tool Set",
      (
        trailblazeToolSet?.tools
          ?: TrailblazeToolSet.getSetOfMarkToolSet(setOfMarkEnabled).tools
        ) + customToolClasses,
    ),
  )

  val trailblazeRunner: TrailblazeRunner by lazy {
    TrailblazeRunner(
      screenStateProvider = hostRunner.screenStateProvider,
      agent = trailblazeAgent,
      llmClient = llmClient,
      trailblazeLlmModel = trailblazeLlmModel,
      trailblazeToolRepo = toolRepo,
      systemPromptTemplate = systemPromptTemplate,
    )
  }

  private val elementComparator = TrailblazeElementComparator(
    screenStateProvider = hostRunner.screenStateProvider,
    llmClient = llmClient,
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
      when (val toolResult = result.second) {
        is TrailblazeToolResult.Success -> toolResult
        is TrailblazeToolResult.Error -> throw TrailblazeException(toolResult.errorMessage)
      }
    },
  )

  private fun runTrail(trailItems: List<TrailYamlItem>, useRecordedSteps: Boolean) {
    for (item in trailItems) {
      val itemResult = when (item) {
        is TrailYamlItem.MaestroTrailItem -> hostRunner.runMaestroCommands(item.maestro.maestroCommands, null)
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
    TrailblazeLogger.sendStartLog(
      trailConfig = trailConfig,
      className = loggingRule.description?.className ?: this::class.java.simpleName,
      methodName = loggingRule.description?.methodName ?: "run",
      trailblazeDeviceInfo = loggingRule.trailblazeDeviceInfoProvider(),
      rawYaml = yaml,
    )
    return runTrail(trailItems, useRecordedSteps)
  }

  protected fun runFromResource(
    forceStopApp: Boolean = true,
    useRecordedSteps: Boolean = true,
  ) {
    val testMethodInfo = TestStackTraceUtil.getJUnit4TestMethodFromCurrentStacktrace()

    val possiblePaths = listOf(
      "trails/${testMethodInfo.simpleClassName}/${testMethodInfo.methodName}.trail.yaml",
      "trails/${testMethodInfo.packageName}/${testMethodInfo.simpleClassName}/${testMethodInfo.methodName}.trail.yaml",
    )
    val trailYamlFromResource: String = possiblePaths.firstNotNullOfOrNull { resourcePath ->
      TemplatingUtil.getResourceAsText(resourcePath)
    } ?: error("No YAML found for at $possiblePaths")
    runTrailblazeYaml(
      yaml = trailYamlFromResource,
      forceStopApp = forceStopApp,
      useRecordedSteps = useRecordedSteps,
    )
  }
}
