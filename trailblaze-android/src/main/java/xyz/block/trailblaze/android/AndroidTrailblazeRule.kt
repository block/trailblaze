package xyz.block.trailblaze.android

import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.llm.LLModel
import maestro.orchestra.Command
import org.junit.runner.Description
import xyz.block.trailblaze.AndroidMaestroTrailblazeAgent
import xyz.block.trailblaze.SimpleTestRuleChain
import xyz.block.trailblaze.TrailblazeAndroidLoggingRule
import xyz.block.trailblaze.agent.model.AgentTaskStatus
import xyz.block.trailblaze.agent.model.toTrailblazePrompt
import xyz.block.trailblaze.android.uiautomator.AndroidOnDeviceUiAutomatorScreenState
import xyz.block.trailblaze.api.TestAgentRunner
import xyz.block.trailblaze.exception.TrailblazeException
import xyz.block.trailblaze.openai.TrailblazeRunner
import xyz.block.trailblaze.rules.TrailblazeRule
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.TrailblazeToolSet

/**
 * On-Device Android Trailblaze Rule Implementation.
 */
class AndroidTrailblazeRule(
  val llmClient: LLMClient,
  val llmModel: LLModel,
) : SimpleTestRuleChain(
  TrailblazeAndroidLoggingRule(),
),
  TrailblazeRule {

  private val trailblazeAgent = AndroidMaestroTrailblazeAgent()
  private lateinit var trailblazeOpenAiRunner: TestAgentRunner

  val trailblazeToolRepo = TrailblazeToolRepo(
    TrailblazeToolSet.getSetOfMarkToolSet(
      setOfMarkEnabled = true,
    ),
  )

  override fun ruleCreation(description: Description) {
    super.ruleCreation(description)
    trailblazeOpenAiRunner = TrailblazeRunner(
      trailblazeToolRepo = trailblazeToolRepo,
      llmModel = llmModel,
      llmClient = llmClient,
      screenStateProvider = {
        AndroidOnDeviceUiAutomatorScreenState(
          filterViewHierarchy = true,
          setOfMarkEnabled = true,
        )
      },
      agent = trailblazeAgent,
    )
  }

  /**
   * Run natural language instructions with the agent.
   */
  override fun prompt(objective: String): Boolean {
    val trailblazeOpenAiRunnerResult = trailblazeOpenAiRunner.run(objective.toTrailblazePrompt())
    return if (trailblazeOpenAiRunnerResult is AgentTaskStatus.Success) {
      // Success!
      true
    } else {
      throw TrailblazeException(trailblazeOpenAiRunnerResult.toString())
    }
  }

  /**
   * Run a Trailblaze tool with the agent.
   */
  override fun tool(vararg trailblazeTool: TrailblazeTool): TrailblazeToolResult {
    val result = trailblazeAgent.runTrailblazeTools(trailblazeTool.toList()).second
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
    maestroCommand = AndroidMaestroYaml.parseYaml(maestroYaml).toTypedArray(),
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
