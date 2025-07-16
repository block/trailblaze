package xyz.block.trailblaze.android

import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.llm.LLModel
import maestro.orchestra.Command
import org.junit.runner.Description
import xyz.block.trailblaze.AndroidMaestroTrailblazeAgent
import xyz.block.trailblaze.SimpleTestRuleChain
import xyz.block.trailblaze.TrailblazeAndroidLoggingRule
import xyz.block.trailblaze.agent.TrailblazeElementComparator
import xyz.block.trailblaze.agent.TrailblazeRunner
import xyz.block.trailblaze.agent.model.AgentTaskStatus
import xyz.block.trailblaze.agent.model.toTrailblazePrompt
import xyz.block.trailblaze.android.uiautomator.AndroidOnDeviceUiAutomatorScreenState
import xyz.block.trailblaze.api.TestAgentRunner
import xyz.block.trailblaze.exception.TrailblazeException
import xyz.block.trailblaze.maestro.MaestroYamlParser
import xyz.block.trailblaze.rules.TrailblazeRule
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.TrailblazeToolSet
import xyz.block.trailblaze.yaml.TrailYamlItem
import xyz.block.trailblaze.yaml.TrailYamlItem.PromptsTrailItem.PromptStep
import xyz.block.trailblaze.yaml.TrailblazeToolYamlWrapper
import xyz.block.trailblaze.yaml.TrailblazeYaml

/**
 * On-Device Android Trailblaze Rule Implementation.
 */
open class AndroidTrailblazeRule(
  val llmClient: LLMClient,
  val llmModel: LLModel,
) : SimpleTestRuleChain(
  TrailblazeAndroidLoggingRule(),
),
  TrailblazeRule {

  private val trailblazeAgent = AndroidMaestroTrailblazeAgent()
  private lateinit var trailblazeRunner: TestAgentRunner

  private val trailblazeToolRepo = TrailblazeToolRepo(
    TrailblazeToolSet.SetOfMarkTrailblazeToolSet,
  )

  private val screenStateProvider = {
    AndroidOnDeviceUiAutomatorScreenState(
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
    trailblazeRunner = TrailblazeRunner(
      trailblazeToolRepo = trailblazeToolRepo,
      llmModel = llmModel,
      llmClient = llmClient,
      screenStateProvider = screenStateProvider,
      agent = trailblazeAgent,
    )
  }

  private val trailblazeYaml = TrailblazeYaml(
    customTrailblazeToolClasses = setOf(),
  )
  override fun run(testYaml: String): Boolean {
    trailblazeAgent.clearMemory()
    val trailItems = trailblazeYaml.decodeTrail(testYaml)
    for (item in trailItems) {
      val itemResult = when (item) {
        is TrailYamlItem.MaestroTrailItem -> runMaestroCommands(item.maestro.maestroCommands)
        is TrailYamlItem.PromptsTrailItem -> runPrompt(item.promptSteps)
        is TrailYamlItem.ToolTrailItem -> runTrailblazeTool(item.tools)
      }
      if (itemResult is TrailblazeToolResult.Error) {
        throw TrailblazeException(itemResult.errorMessage)
      }
    }
    return true
  }

  private fun runTrailblazeTool(tools: List<TrailblazeToolYamlWrapper>): TrailblazeToolResult {
    val trailblazeTools = tools.map { it.trailblazeTool }
    val result = trailblazeAgent.runTrailblazeTools(
      tools = trailblazeTools,
      screenState = screenStateProvider(),
      elementComparator = elementComparator,
    )
    return result.second
  }

  private fun runMaestroCommands(maestroCommands: List<Command>): TrailblazeToolResult = trailblazeAgent.runMaestroCommands(maestroCommands)

  private fun runPrompt(prompts: List<PromptStep>): TrailblazeToolResult {
    for (prompt in prompts) {
      val promptResult: TrailblazeToolResult = if (prompt.recordable && prompt.recording?.tools?.isNotEmpty() == true) {
        runTrailblazeTool(prompt.recording!!.tools)
      } else {
        trailblazeRunner.run(prompt)
        TrailblazeToolResult.Success // TODO: What to actually return here?
      }
      if (promptResult is TrailblazeToolResult.Error) {
        return promptResult
      }
    }
    return TrailblazeToolResult.Success
  }

  /**
   * Run natural language instructions with the agent.
   */
  override fun prompt(objective: String): Boolean {
    val runnerResult = trailblazeRunner.run(objective.toTrailblazePrompt())
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
