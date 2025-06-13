package xyz.block.trailblaze.android

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
import xyz.block.trailblaze.openai.TrailblazeOpenAiRunner
import xyz.block.trailblaze.rules.TrailblazeRule
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult

/**
 * On-Device Android Trailblaze Rule Implementation.
 */
class AndroidTrailblazeRule(
  /**
   * Use this to handle custom [TrailblazeTool] that are not directly mapped to Maestro commands.
   */
  val customTrailblazeToolHandler: (TrailblazeToolExecutionContext) -> TrailblazeToolResult? = { null },
) : SimpleTestRuleChain(TrailblazeAndroidLoggingRule()),
  TrailblazeRule {

  private val trailblazeAgent = AndroidMaestroTrailblazeAgent(
    customTrailblazeToolHandler = customTrailblazeToolHandler,
  )
  private lateinit var trailblazeOpenAiRunner: TestAgentRunner

  val trailblazeToolRepo = TrailblazeToolRepo()

  override fun ruleCreation(description: Description) {
    super.ruleCreation(description)
    trailblazeOpenAiRunner = TrailblazeOpenAiRunner(
      trailblazeToolRepo = trailblazeToolRepo,
      openAiApiKey = InstrumentationArgUtil.getApiKeyFromInstrumentationArg(),
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
