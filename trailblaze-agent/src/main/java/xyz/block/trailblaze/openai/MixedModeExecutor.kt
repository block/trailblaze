package xyz.block.trailblaze.openai

import net.objecthunter.exp4j.ExpressionBuilder
import xyz.block.trailblaze.agent.model.AgentTaskStatus
import xyz.block.trailblaze.agent.model.MixedModeTestCase
import xyz.block.trailblaze.agent.model.PromptStep
import xyz.block.trailblaze.agent.model.TestObjective.AssertEqualsCommand
import xyz.block.trailblaze.agent.model.TestObjective.AssertMathCommand
import xyz.block.trailblaze.agent.model.TestObjective.AssertNotEqualsCommand
import xyz.block.trailblaze.agent.model.TestObjective.AssertWithAiCommand
import xyz.block.trailblaze.agent.model.TestObjective.MaestroCommand
import xyz.block.trailblaze.agent.model.TestObjective.RememberNumberCommand
import xyz.block.trailblaze.agent.model.TestObjective.RememberTextCommand
import xyz.block.trailblaze.agent.model.TestObjective.RememberWithAiCommand
import xyz.block.trailblaze.agent.model.TestObjective.TrailblazeObjective.TrailblazeCommand
import xyz.block.trailblaze.agent.model.TestObjective.TrailblazeObjective.TrailblazePrompt
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.exception.TrailblazeException
import xyz.block.trailblaze.exception.TrailblazeToolExecutionException
import xyz.block.trailblaze.llm.LlmModel
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import kotlin.math.abs

/**
 * MixedModeExecutor provides functionality to execute YAML flows that contain
 * a mix of direct Trailblaze AI commands and Maestro commands.
 */
class MixedModeExecutor(
  openAiApiKey: String,
  llmModel: LlmModel? = null,
  private val screenStateProvider: () -> ScreenState,
  private val runYamlFlowFunction: (String) -> TrailblazeToolResult,
  private val runner: TrailblazeOpenAiRunner,
) {
  // Variable store for remember commands
  private val variables = mutableMapOf<String, String>()

  // Regex for detecting numbers
  private val numberRegex = Regex("[-+]?\\d*\\.?\\d+")

  private val elementComparator = TrailblazeElementComparator(
    screenStateProvider = screenStateProvider,
    openAiApiKey = openAiApiKey,
    llmModel = llmModel ?: LlmModel.GPT_4_1,
  )

  /**
   * Executes a mixed mode workflow where commands can be either Trailblaze AI commands
   * or direct Maestro commands.
   *
   * @param yamlContent The YAML content containing the mixed commands
   * @param executeSteps If true, execute static Trailblaze steps; if false, run the objective as a Trailblaze command
   */
  fun runMixedMode(
    yamlContent: String,
    executeSteps: Boolean = true,
  ) {
    val testCase = MixedModeTestCase(yamlContent, executeSteps)
    testCase.objectives.forEach { objective ->
      when (objective) {
        is AssertEqualsCommand -> handleAssertEqualsCommand(objective)
        is AssertMathCommand -> handleAssertMathCommand(objective)
        is AssertNotEqualsCommand -> handleAssertNotEqualsCommand(objective)
        is AssertWithAiCommand -> handleAssertWithAICommand(objective)
        is MaestroCommand -> handleMaestroCommand(objective)
        is RememberNumberCommand -> handleRememberNumberCommand(objective)
        is RememberTextCommand -> handleRememberTextCommand(objective)
        is RememberWithAiCommand -> handleRememberWithAICommand(objective)
        is TrailblazePrompt -> handleTrailblazePrompt(objective)
        is TrailblazeCommand -> handleTrailblazeCommand(objective)
        else -> throw TrailblazeException("Unknown objective type for mixed mode")
      }
    }
  }

  /**
   * Handles a Trailblaze prompt by calling the LLM
   */
  private fun handleTrailblazePrompt(prompt: TrailblazePrompt) {
    when (val trailblazeOpenAiRunnerResult = runner.run(prompt)) {
      is AgentTaskStatus.Success.ObjectiveComplete -> {
        // Successfully completed
        println("AI Task completed successfully: ${trailblazeOpenAiRunnerResult.llmExplanation}")
      }
      is AgentTaskStatus.Success -> {
        // Some other success state
        println("AI Task succeeded: $trailblazeOpenAiRunnerResult")
      }
      else -> {
        throw TrailblazeException(trailblazeOpenAiRunnerResult.toString())
      }
    }
  }

  /**
   * Handles a Trailblaze command by calling the TrailblazeTool
   */
  private fun handleTrailblazeCommand(objective: TrailblazeCommand) {
    objective.tools.forEach { staticObjective ->
      staticObjective.tools.forEach { tool ->
        runner.handleTrailblazeToolForPrompt(
          trailblazeTool = tool,
          llmResponseId = null,
          // Empty prompt step since we're just triggering the tool
          step = PromptStep(description = ""),
          screenStateForLlmRequest = screenStateProvider(),
        )
      }
    }
  }

  /**
   * Handles the rememberText command
   */
  private fun handleRememberTextCommand(objective: RememberTextCommand) {
    val interpolatedPrompt = interpolateVariables(objective.promptWithVars)
    val extractedValue = elementComparator.getElementValue(interpolatedPrompt)
      ?: throw TrailblazeException("Failed to find element for prompt: ${objective.promptWithVars}")

    variables[objective.variableName] = extractedValue
  }

  /**
   * Handles the rememberNumber command
   */
  private fun handleRememberNumberCommand(objective: RememberNumberCommand) {
    val interpolatedPrompt = interpolateVariables(objective.promptWithVars)
    val extractedValue = elementComparator.getElementValue(interpolatedPrompt)
      ?: throw TrailblazeException("Failed to find element for prompt: ${objective.promptWithVars}")

    // Extract numeric value using regex
    val numberMatch = numberRegex.find(extractedValue)
    variables[objective.variableName] = numberMatch?.value ?: "0" // todo: Should this throw if it's invalid?
  }

  /**
   * Handles the rememberWithAI command
   */
  private fun handleRememberWithAICommand(objective: RememberWithAiCommand) {
    val interpolatedPrompt = interpolateVariables(objective.promptWithVars)
    val answer = getDirectAnswer(interpolatedPrompt)
    variables[objective.variableName] = answer
  }

  /**
   * Handles the assertEquals command
   */
  private fun handleAssertEqualsCommand(objective: AssertEqualsCommand) {
    val actual = interpolateVariables(objective.actual)
    val expected = interpolateVariables(objective.expected)
    if (actual != expected) {
      throw TrailblazeException("Assertion failed: Expected '${objective.expected}', but got '${objective.actual}'")
    }
  }

  /**
   * Handles the assertNotEquals command
   */
  private fun handleAssertNotEqualsCommand(objective: AssertNotEqualsCommand) {
    val actual = interpolateVariables(objective.actual)
    val expected = interpolateVariables(objective.expected)
    if (actual == expected) {
      throw TrailblazeException("Assertion failed: Expected '${objective.expected}' to NOT equal '${objective.actual}', but they are equal")
    }
  }

  /**
   * Handles the assertWithAI command
   */
  private fun handleAssertWithAICommand(objective: AssertWithAiCommand) {
    val interpolatedPrompt = interpolateVariables(objective.promptWithVars)
    val response = evaluateUiAssertion(interpolatedPrompt)

    println("assertWithAI result for '$interpolatedPrompt': $response")

    if (response != "true") {
      throw TrailblazeException("AI assertion failed: $interpolatedPrompt")
    }
  }

  /**
   * Handles the assertMath command
   */
  private fun handleAssertMathCommand(objective: AssertMathCommand) {
    // Process any dynamic extraction patterns like [[prompt]] in the expression
    val interpolatedExpression = processDynamicExtractions(objective.expression)

    try {
      val result = ExpressionBuilder(interpolatedExpression).build().evaluate()
      val expectedValue = objective.expected.toDouble()

      if (abs(result - expectedValue) > 0.0001) {
        throw TrailblazeException("Math assertion failed: Expression '$interpolatedExpression' evaluated to $result, expected $expectedValue")
      }
    } catch (e: Exception) {
      // Make sure to include "Math assertion failed" in all error cases
      if (e is TrailblazeException) {
        throw e // Rethrow existing TrailblazeExceptions
      } else {
        throw TrailblazeException("Math assertion failed: Error evaluating expression - ${e.message}")
      }
    }
  }

  /**
   * Handles a Maestro command with a key-value pair
   */
  private fun handleMaestroCommand(objective: MaestroCommand) {
    val interpolatedMaestroCommand = interpolateVariables(objective.maestroCommandWithVars)
    TrailblazeLogger.log(
      TrailblazeLog.TopLevelMaestroCommandLog(
        command = interpolatedMaestroCommand,
        session = TrailblazeLogger.getCurrentSessionId(),
        timestamp = System.currentTimeMillis(),
      ),
    )
    val result = runYamlFlowFunction(interpolatedMaestroCommand)
    if (result is TrailblazeToolResult.Error) {
      throw TrailblazeToolExecutionException(result)
    }
  }

  /**
   * Helper method to directly evaluate a statement about the UI using the element comparator.
   * This replaces OpenAI runner usage while still maintaining AI-like assertion capabilities.
   *
   * @param prompt The prompt to evaluate against the screen
   * @return String representation of the result (typically "true" or "false")
   */
  private fun evaluateUiAssertion(prompt: String): String {
    val result = elementComparator.evaluateBoolean(prompt)
    println("UI Assertion result: ${result.result}, reason: ${result.reason}")
    return if (result.result) "true" else "false"
  }

  /**
   * Gets a direct answer from UI evaluation for a given prompt.
   * Similar to AI response but uses element comparator.
   *
   * @param prompt The prompt to evaluate
   * @return String with the direct response
   */
  private fun getDirectAnswer(prompt: String): String {
    val result = elementComparator.evaluateString(prompt)
    println("UI Evaluation result: ${result.result}, reason: ${result.reason}")
    return result.result
  }

  /**
   * Interpolates variables in a string. Replaces ${varName} or {{varName}} with variable values.
   */
  private fun interpolateVariables(input: String): String {
    var result = input
    // Support both ${varName} and {{varName}}
    val patterns = listOf(
      Regex("\\$\\{([^}]+)\\}"),
      Regex("\\{\\{([^}]+)\\}\\}"),
    )
    for (pattern in patterns) {
      pattern.findAll(result).forEach { matchResult ->
        val variableName = matchResult.groupValues[1]
        val variableValue = variables[variableName] ?: ""
        result = result.replace(matchResult.value, variableValue)
      }
    }
    return result
  }

  /**
   * Process expression string to extract values from UI using [[prompt]] syntax
   * Extracts the value for each prompt and replaces it in the expression
   *
   * @param expression The expression containing [[prompt]] patterns
   * @return The interpolated expression with actual values from UI
   */
  private fun processDynamicExtractions(expression: String): String {
    println("Processing dynamic extractions in: $expression")

    var interpolatedExpression = expression

    // Define regex pattern for [[prompt]]
    val dynamicExtractPattern = Regex("\\[\\[([^\\]]+)\\]\\]")

    // Find all matches
    val matches = dynamicExtractPattern.findAll(interpolatedExpression)

    for (match in matches) {
      val fullMatch = match.value
      val prompt = match.groupValues[1]

      println("Found dynamic extraction pattern: $fullMatch with prompt: $prompt")

      // Extract the value using the prompt
      val extractedValue = elementComparator.getElementValue(prompt)
      if (extractedValue != null) {
        // Try to extract a number from the value
        val numberValue = elementComparator.extractNumberFromString(extractedValue)

        if (numberValue != null) {
          println("Extracted value $numberValue for prompt '$prompt'")

          // Replace the pattern with the extracted value
          interpolatedExpression = interpolatedExpression.replace(fullMatch, numberValue.toString())
        } else {
          println("Could not extract a number from: $extractedValue for prompt: $prompt")
          throw TrailblazeException("Could not extract a numeric value for prompt: $prompt")
        }
      } else {
        println("Failed to find element for prompt: $prompt")
        throw TrailblazeException("Failed to find element for prompt: $prompt")
      }
    }

    // Also process regular variable interpolation after dynamic extractions
    val finalExpression = interpolateVariables(interpolatedExpression)
    println("Final interpolated expression: $finalExpression")

    return finalExpression
  }
}
