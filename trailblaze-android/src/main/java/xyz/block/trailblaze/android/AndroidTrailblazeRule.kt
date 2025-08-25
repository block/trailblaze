package xyz.block.trailblaze.android

import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.llm.LLModel
import maestro.orchestra.Command
import org.junit.runner.Description
import xyz.block.trailblaze.AndroidMaestroTrailblazeAgent
import xyz.block.trailblaze.TrailblazeAndroidLoggingRule
import xyz.block.trailblaze.agent.TrailblazeElementComparator
import xyz.block.trailblaze.agent.TrailblazeRunner
import xyz.block.trailblaze.agent.model.AgentTaskStatus
import xyz.block.trailblaze.android.uiautomator.AndroidOnDeviceUiAutomatorScreenState
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.TestAgentRunner
import xyz.block.trailblaze.exception.TrailblazeException
import xyz.block.trailblaze.maestro.MaestroYamlParser
import xyz.block.trailblaze.rules.SimpleTestRuleChain
import xyz.block.trailblaze.rules.TrailblazeRule
import xyz.block.trailblaze.rules.TrailblazeRunnerUtil
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.TrailblazeToolSet.Companion.SetOfMarkTrailblazeToolSet
import xyz.block.trailblaze.toolcalls.TrailblazeToolSet.DynamicToolSet
import xyz.block.trailblaze.yaml.PromptStep
import xyz.block.trailblaze.yaml.TrailConfig
import xyz.block.trailblaze.yaml.TrailYamlItem
import xyz.block.trailblaze.yaml.TrailblazeYaml
import kotlin.reflect.KClass

/**
 * On-Device Android Trailblaze Rule Implementation.
 */
open class AndroidTrailblazeRule(
  val llmClient: LLMClient,
  val llmModel: LLModel,
  additionalRules: List<TrailblazeAndroidLoggingRule> = listOf(
    TrailblazeAndroidLoggingRule(),
  ),
  customToolClasses: Set<KClass<out TrailblazeTool>> = setOf(),
) : SimpleTestRuleChain(
  *additionalRules.toTypedArray(),
),
  TrailblazeRule {

  private val trailblazeAgent = AndroidMaestroTrailblazeAgent()

  private val toolSet = SetOfMarkTrailblazeToolSet + DynamicToolSet(customToolClasses)
  private val trailblazeToolRepo = TrailblazeToolRepo(toolSet)

  private val screenStateProvider: () -> ScreenState = {
    AndroidOnDeviceUiAutomatorScreenState(
      includeScreenshot = true,
      filterViewHierarchy = true,
      setOfMarkEnabled = true,
    )
  }

  private val elementComparator = TrailblazeElementComparator(
    screenStateProvider = screenStateProvider,
    llmClient = llmClient,
    llmModel = llmModel,
  )

  override fun ruleCreation(description: Description) {
    super.ruleCreation(description)
  }

  private val trailblazeYaml = TrailblazeYaml(
    customTrailblazeToolClasses = customToolClasses,
  )

  private val trailblazeRunner: TestAgentRunner by lazy {
    TrailblazeRunner(
      trailblazeToolRepo = trailblazeToolRepo,
      llmModel = llmModel,
      llmClient = llmClient,
      screenStateProvider = screenStateProvider,
      agent = trailblazeAgent,
    )
  }

  val trailblazeRunnerUtil by lazy {
    TrailblazeRunnerUtil(
      trailblazeRunner = trailblazeRunner,
      runTrailblazeTool = ::runTrailblazeTool,
    )
  }

  override fun run(
    testYaml: String,
    useRecordedSteps: Boolean,
  ): Boolean {
    trailblazeAgent.clearMemory()
    val trailItems = trailblazeYaml.decodeTrail(testYaml)
    for (item in trailItems) {
      val itemResult = when (item) {
        is TrailYamlItem.MaestroTrailItem -> runMaestroCommands(item.maestro.maestroCommands)
        is TrailYamlItem.PromptsTrailItem -> trailblazeRunnerUtil.runPrompt(item.promptSteps, useRecordedSteps)
        is TrailYamlItem.ToolTrailItem -> runTrailblazeTool(item.tools.map { it.trailblazeTool })
        is TrailYamlItem.ConfigTrailItem -> handleConfig(item.config)
      }
      if (itemResult is TrailblazeToolResult.Error) {
        throw TrailblazeException(itemResult.errorMessage)
      }
    }
    return true
  }

  private fun runTrailblazeTool(trailblazeTools: List<TrailblazeTool>): TrailblazeToolResult {
    val result = trailblazeAgent.runTrailblazeTools(
      tools = trailblazeTools,
      screenState = screenStateProvider(),
      elementComparator = elementComparator,
    )
    return when (val toolResult = result.second) {
      is TrailblazeToolResult.Success -> toolResult
      is TrailblazeToolResult.Error -> throw TrailblazeException(toolResult.errorMessage)
    }
  }

  private fun runMaestroCommands(maestroCommands: List<Command>): TrailblazeToolResult = when (
    val maestroResult =
      trailblazeAgent.runMaestroCommands(
        maestroCommands = maestroCommands,
        llmResponseId = null,
      )
  ) {
    is TrailblazeToolResult.Success -> maestroResult
    is TrailblazeToolResult.Error -> throw TrailblazeException(maestroResult.errorMessage)
  }

  private fun handleConfig(config: TrailConfig): TrailblazeToolResult {
    config.context?.let { trailblazeRunner.appendToSystemPrompt(it) }
    return TrailblazeToolResult.Success
  }

  /**
   * Run natural language instructions with the agent.
   */
  override fun prompt(objective: String): Boolean {
    val runnerResult = trailblazeRunner.run(PromptStep(objective))
    return if (runnerResult is AgentTaskStatus.Success) {
      // Success!
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
    ).second
    return if (result is TrailblazeToolResult.Success) {
      result
    } else {
      throw TrailblazeException(result.toString())
    }
  }

  /**
   * Run a Trailblaze tool with the agent.
   */
  override fun maestro(maestroYaml: String): TrailblazeToolResult = maestroCommands(
    maestroCommand = MaestroYamlParser.parseYaml(maestroYaml).toTypedArray(),
  )

  /**
   * Run a Trailblaze tool with the agent.
   */
  override fun maestroCommands(vararg maestroCommand: Command): TrailblazeToolResult {
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
}
