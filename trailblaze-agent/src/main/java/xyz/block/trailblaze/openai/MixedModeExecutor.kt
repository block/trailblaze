package xyz.block.trailblaze.openai

import net.objecthunter.exp4j.ExpressionBuilder
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml
import xyz.block.trailblaze.agent.model.AgentTaskStatus
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.exception.TrailblazeException
import xyz.block.trailblaze.exception.TrailblazeToolExecutionException
import xyz.block.trailblaze.llm.LlmModel
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import kotlin.math.abs

/**
 * MixedModeExecutor provides functionality to execute YAML flows that contain
 * a mix of direct Trailblaze AI commands and Maestro commands.
 */
class MixedModeExecutor(
  private val openAiApiKey: String,
  private val screenStateProvider: () -> ScreenState,
  private val llmModel: LlmModel? = null,
  private val runYamlFlowFunction: (String) -> TrailblazeToolResult,
  private val runner: TrailblazeOpenAiRunner,
) {
  // Variable store for remember commands
  private val variables = mutableMapOf<String, String>()

  // Regex for detecting numbers
  private val numberRegex = Regex("[-+]?\\d*\\.?\\d+")

  private val elementComparator: TrailblazeElementComparator by lazy {
    TrailblazeElementComparator(
      screenStateProvider = screenStateProvider,
      openAiApiKey = openAiApiKey,
      llmModel = llmModel ?: LlmModel.GPT_4_1,
    )
  }

  private val noParamMaestroCommands = setOf(
    "scroll",
    "back",
    "hideKeyboard",
    "waitForAnimationToEnd",
  )

  /**
   * Executes a mixed mode workflow where commands can be either Trailblaze AI commands
   * or direct Maestro commands.
   *
   * @param yamlContent The YAML content containing the mixed commands
   * @param executeSteps If true, execute static Trailblaze steps; if false, run the objective as a Trailblaze command
   */
  fun runMixedMode(yamlContent: String, executeSteps: Boolean = true) {
    val yaml = Yaml()
    val parsedYaml = yaml.load(yamlContent) as List<*>

    parsedYaml.forEach { command ->
      when (command) {
        is Map<*, *> -> {
          val key = command.keys.firstOrNull()?.toString()
          val value = command.values.firstOrNull()

          if (isTrailblazeCommandKey(key)) {
            handleTrailblazeObjective(value, yamlContent, executeSteps)
          } else {
            when (key) {
              "rememberText" -> handleRememberTextCommand(value)
              "rememberNumber" -> handleRememberNumberCommand(value)
              "rememberWithAI" -> handleRememberWithAICommand(value)
              "assertEquals" -> handleAssertEqualsCommand(value)
              "assertNotEquals" -> handleAssertNotEqualsCommand(value)
              "assertWithAI" -> handleAssertWithAICommand(value)
              "assertMath" -> handleAssertMathCommand(value)
              // For known Maestro commands, handle them directly
              "addMedia", "assertVisible", "assertNotVisible", "assertTrue",
              "assertNoDefectsWithAi", "back", "clearKeychain", "clearState", "copyTextFrom",
              "evalScript", "eraseText", "extendedWaitUntil", "hideKeyboard",
              "inputText", "killApp", "launchApp", "openLink", "pressKey", "pasteText", "repeat",
              "retry", "runFlow", "runScript", "scroll", "scrollUntilVisible", "setAirplaneMode",
              "setLocation", "startRecording", "stopApp", "stopRecording", "swipe", "takeScreenshot",
              "toggleAirplaneMode", "tapOn", "doubleTapOn", "longPressOn", "travel",
              "waitForAnimationToEnd",
              -> handleMaestroCommand(key, value)
              else -> {
                // For unknown commands, try handling as Trailblaze command first
                val commandString = "$key " + (value ?: "")
                handleTrailblazeCommand(commandString)
              }
            }
          }
        }
        else -> {
          val commandStr = command?.toString()?.trim()
          if (commandStr != null && noParamMaestroCommands.contains(commandStr)) {
            handleMaestroCommand(commandStr, null)
          } else {
            handleTrailblazeCommand(command)
          }
        }
      }
    }
  }

  /**
   * Determines if the key is a Trailblaze command key
   */
  private fun isTrailblazeCommandKey(key: String?): Boolean = key == "tb" || key == "trailblaze" || key == "🧭" || key == "run"

  /**
   * Handles Trailblaze objectives, either running as a command or executing steps, based on the flag
   */
  private fun handleTrailblazeObjective(value: Any?, yamlContent: String, executeSteps: Boolean) {
    val objectives = value as? List<*>
    if (objectives == null) {
      throw TrailblazeException("No objectives found in Trailblaze objective")
    }
    if (objectives.all { it is Map<*, *> } and executeSteps) {
      // Ensure the agent's task is set up before running any steps
      runner.agent.setUpTask(yamlContent)
      objectives.forEach { obj ->
        val objMap = obj as? Map<*, *> ?: return@forEach
        val desc = objMap.keys.firstOrNull()?.toString() ?: ""
        val objValue = objMap.values.firstOrNull()
        val steps: List<*>? = objValue as? List<*>
        if (steps != null) {
          println("[MixedModeExecutor] Executing steps for objective: $desc")
          steps.forEach { step ->
            val stepMap = step as? Map<*, *> ?: return@forEach
            runner.runToolStep(
              step = stepMap,
              llmResponseId = null,
              instructions = yamlContent,
              screenStateForLlmRequest = screenStateProvider(),
            )
          }
        }
      }
    } else {
      // Combine all descriptions into a single objective separated by newlines
      val combinedDesc = objectives.mapNotNull {
        when (it) {
          is String -> it
          is Map<*, *> -> {
            val key = it.keys.firstOrNull()?.toString() ?: ""
            val value = it.values.firstOrNull()?.toString() ?: ""
            "$key: $value"
          }
          else -> null
        }
      }.joinToString("\n")
      handleTrailblazeCommand(combinedDesc)
    }
  }

  /**
   * Handles a Trailblaze command
   */
  private fun handleTrailblazeCommand(value: Any?) {
    val tbCommand = when (value) {
      is List<*> -> {
        // Handle case where value is a list of items
        // Join all items together with newlines to create a single command string
        value.filterNotNull().joinToString("\n") { it.toString() }
      }
      else -> {
        // Handle the existing case for simple string commands
        value?.toString()
      }
    }

    if (tbCommand != null) {
      runTrailblazeTool(interpolateVariables(tbCommand.trim()))
    }
  }

  /**
   * Handles the rememberText command
   */
  private fun handleRememberTextCommand(value: Any?) {
    if (value is Map<*, *>) {
      val prompt = value["prompt"]?.toString()
      val variable = value["variable"]?.toString()

      if (prompt != null && variable != null) {
        val promptWithVars = interpolateVariables(prompt)
        val extractedValue = elementComparator.getElementValue(promptWithVars)

        if (extractedValue == null) {
          throw TrailblazeException("Failed to find element for prompt: $promptWithVars")
        }

        variables[variable] = extractedValue
      } else {
        throw TrailblazeException("Invalid rememberText command: missing prompt or variable name")
      }
    } else {
      throw TrailblazeException("Invalid rememberText command format")
    }
  }

  /**
   * Handles the rememberNumber command
   */
  private fun handleRememberNumberCommand(value: Any?) {
    if (value is Map<*, *>) {
      val prompt = value["prompt"]?.toString()
      val variable = value["variable"]?.toString()

      if (prompt != null && variable != null) {
        val promptWithVars = interpolateVariables(prompt)
        val extractedValue = elementComparator.getElementValue(promptWithVars)

        if (extractedValue == null) {
          throw TrailblazeException("Failed to find element for prompt: $promptWithVars")
        }

        // Extract numeric value using regex
        val numberMatch = numberRegex.find(extractedValue)
        variables[variable] = numberMatch?.value ?: "0"
      } else {
        throw TrailblazeException("Invalid rememberNumber command: missing prompt or variable name")
      }
    } else {
      throw TrailblazeException("Invalid rememberNumber command format")
    }
  }

  /**
   * Handles the rememberWithAI command
   */
  private fun handleRememberWithAICommand(value: Any?) {
    if (value is Map<*, *>) {
      val prompt = value["prompt"]?.toString()
      val variable = value["variable"]?.toString()

      if (prompt != null && variable != null) {
        val promptWithVars = interpolateVariables(prompt)
        val answer = getDirectAnswer(promptWithVars)
        variables[variable] = answer
      } else {
        throw TrailblazeException("Invalid rememberWithAI command: missing prompt or variable name")
      }
    } else {
      throw TrailblazeException("Invalid rememberWithAI command format")
    }
  }

  /**
   * Handles the assertEquals command
   */
  private fun handleAssertEqualsCommand(value: Any?) {
    if (value is Map<*, *>) {
      val actual = value["actual"]?.toString()
      val expected = value["expected"]?.toString()

      if (actual != null && expected != null) {
        val actualValue = interpolateVariables(actual)
        val expectedValue = interpolateVariables(expected)

        if (actualValue != expectedValue) {
          throw TrailblazeException("Assertion failed: Expected '$expectedValue', but got '$actualValue'")
        }
      }
    }
  }

  /**
   * Handles the assertNotEquals command
   */
  private fun handleAssertNotEqualsCommand(value: Any?) {
    if (value is Map<*, *>) {
      val actual = value["actual"]?.toString()
      val expected = value["expected"]?.toString()

      if (actual != null && expected != null) {
        val actualValue = interpolateVariables(actual)
        val expectedValue = interpolateVariables(expected)

        if (actualValue == expectedValue) {
          throw TrailblazeException("Assertion failed: Expected '$actualValue' to NOT equal '$expectedValue', but they are equal")
        }
      }
    }
  }

  /**
   * Handles the assertWithAI command
   */
  private fun handleAssertWithAICommand(value: Any?) {
    if (value is Map<*, *>) {
      val prompt = value["prompt"]?.toString()

      if (prompt != null) {
        val promptWithVars = interpolateVariables(prompt)
        val response = evaluateUiAssertion(promptWithVars)

        println("assertWithAI result for '$promptWithVars': $response")

        if (response != "true") {
          throw TrailblazeException("AI assertion failed: $promptWithVars")
        }
      }
    }
  }

  /**
   * Handles the assertMath command
   */
  private fun handleAssertMathCommand(value: Any?) {
    if (value is Map<*, *>) {
      val expression = value["expression"]?.toString()
      val expected = value["expected"]?.toString()

      if (expression != null && expected != null) {
        // Process any dynamic extraction patterns like [[prompt]] in the expression
        val interpolatedExpression = processDynamicExtractions(expression)

        try {
          val result = ExpressionBuilder(interpolatedExpression).build().evaluate()
          val expectedValue = expected.toDouble()

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
    }
  }

  /**
   * Handles a Maestro command with a key-value pair
   */
  private fun handleMaestroCommand(key: String?, value: Any?) {
    val maestroCommand = when (value) {
      null -> "- $key"
      is Map<*, *> -> {
        // Serialize as inline YAML object (flow style)
        val options = DumperOptions().apply {
          defaultFlowStyle = DumperOptions.FlowStyle.FLOW
          indent = 2
        }
        val yaml = Yaml(options)
        val inline = yaml.dump(value).trim()
        "- $key: $inline"
      }
      else -> "- $key: $value"
    }

    val result = runYamlFlowFunction(interpolateVariables(maestroCommand))
    if (result is TrailblazeToolResult.Error) {
      throw TrailblazeToolExecutionException(result)
    }
  }

  /**
   * Executes a Trailblaze AI command
   */
  private fun runTrailblazeTool(command: String) {
    val trailblazeOpenAiRunnerResult = runner.run(command)
    when (trailblazeOpenAiRunnerResult) {
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
