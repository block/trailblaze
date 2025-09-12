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

abstract class BaseHostTrailblazeTest(
  trailblazeDriverType: TrailblazeDriverType,
  setOfMarkEnabled: Boolean = true,
) {

  val hostRunner by lazy {
    MaestroHostRunnerImpl(
      requestedPlatform = trailblazeDriverType.platform,
      setOfMarkEnabled = setOfMarkEnabled,
    )
  }

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

  val trailblazeRunner: TrailblazeRunner by lazy {
    TrailblazeRunner(
      screenStateProvider = hostRunner.screenStateProvider,
      agent = trailblazeAgent,
      llmClient = llmClient,
      trailblazeLlmModel = trailblazeLlmModel,
      trailblazeToolRepo = TrailblazeToolRepo(
        TrailblazeToolSet.getSetOfMarkToolSet(
          setOfMarkEnabled = true,
        ),
      ),
    )
  }

  private val elementComparator = TrailblazeElementComparator(
    screenStateProvider = hostRunner.screenStateProvider,
    llmClient = llmClient,
    trailblazeLlmModel = trailblazeLlmModel,
  )

  private val trailblazeYaml = TrailblazeYaml(
    customTrailblazeToolClasses = setOf(),
  )

  private val trailblazeRunnerUtil = TrailblazeRunnerUtil(
    trailblazeRunner = trailblazeRunner,
    runTrailblazeTool = { trailblazeTools: List<TrailblazeTool> ->
      val result =
        trailblazeRunner.agent.runTrailblazeTools(
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

  fun runTrail(trailItems: List<TrailYamlItem>, useRecordedSteps: Boolean) {
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

  fun runTrailblazeYaml(
    yaml: String,
    useRecordedSteps: Boolean = true,
  ) {
    val trailItems: List<TrailYamlItem> = trailblazeYaml.decodeTrail(yaml)
    return runTrail(trailItems, useRecordedSteps)
  }

  protected fun runFromResource() {
    val testMethodInfo = TestStackTraceUtil.getJUnit4TestMethodFromCurrentStacktrace()
    val resourcePath = "trails/${testMethodInfo.simpleClassName}/${testMethodInfo.methodName}.trail.yaml"
    val yamlContent: String = TemplatingUtil.getResourceAsText(resourcePath) ?: error("No YAML found for $resourcePath")
    runTrailblazeYaml(yamlContent)
  }
}
