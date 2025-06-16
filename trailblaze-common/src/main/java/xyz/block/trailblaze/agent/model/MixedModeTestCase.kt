package xyz.block.trailblaze.agent.model

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml
import xyz.block.trailblaze.agent.model.TestObjective.AssertEqualsCommand
import xyz.block.trailblaze.agent.model.TestObjective.AssertMathCommand
import xyz.block.trailblaze.agent.model.TestObjective.AssertNotEqualsCommand
import xyz.block.trailblaze.agent.model.TestObjective.AssertWithAiCommand
import xyz.block.trailblaze.agent.model.TestObjective.MaestroCommand
import xyz.block.trailblaze.agent.model.TestObjective.RememberNumberCommand
import xyz.block.trailblaze.agent.model.TestObjective.RememberTextCommand
import xyz.block.trailblaze.agent.model.TestObjective.RememberWithAiCommand
import xyz.block.trailblaze.agent.model.TestObjective.TrailblazeObjective
import xyz.block.trailblaze.agent.model.TestObjective.TrailblazeObjective.TrailblazeCommand
import xyz.block.trailblaze.agent.model.TestObjective.TrailblazeObjective.TrailblazePrompt
import xyz.block.trailblaze.exception.TrailblazeException
import xyz.block.trailblaze.toolcalls.JsonSerializationUtil
import xyz.block.trailblaze.toolcalls.TrailblazeKoogTool.Companion.toKoogToolDescriptor
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolSet
import java.util.UUID
import kotlin.reflect.KClass

class MixedModeTestCase(
  yamlContent: String,
  private val executeRecordedSteps: Boolean = true,
  private val additionalTrailblazeTools: List<KClass<out TrailblazeTool>> = emptyList(),
) : TestCase(yamlContent) {
  private val allPossibleTools: Set<KClass<out TrailblazeTool>> =
    TrailblazeToolSet.BuiltInTrailblazeTools + additionalTrailblazeTools

  override val objectives: List<TestObjective> = parseObjectives()

  private fun parseObjectives(): List<TestObjective> {
    if (instructions.isBlank()) {
      throw TrailblazeException("Cannot generate test case from empty yamlConfig")
    }
    val yaml = Yaml()
    println("### Parsing yaml content: $instructions")
    val parsedYaml = yaml.load(instructions) as List<*>
    println("### Parsing parsed yaml: $parsedYaml")

    // Process each command, filtering out TB commands and collecting Maestro commands
    return parsedYaml.map { command ->
      println("### Parsing command from yaml $command")
      when (command) {
        is Map<*, *> -> {
          // Handle Map commands (most common for YAML blocks)
          val key = command.keys.firstOrNull()?.toString()
          val value = command.values.firstOrNull()

          // Handle special commands
          with(key) {
            when {
              isTrailblazeCommand() -> parseTrailblazePrompt(value)
              isRememberTextCommand() -> parseRememberTextCommand(value)
              isRememberNumberCommand() -> parseRememberNumberCommand(value)
              isRememberWithAiCommand() -> parseRememberWithAiCommand(value)
              isAssertEqualsCommand() -> parseAssertEqualsCommand(value)
              isAssertNotEqualsCommand() -> parseAssertNotEqualsCommand(value)
              isAssertWithAiCommand() -> parseAssertWithAiCommand(value)
              isAssertMathCommand() -> parseAssertMathCommand(value)
              isMaestroCommand() -> parseMaestroCommand(this!!, value)
              else -> parseTrailblazePrompt(listOf("$key ${value ?: ""}"))
            }
          }
        }

        else -> {
          val commandStr = command?.toString()?.trim()
          if (commandStr.isNoParamMaestroCommand()) {
            parseMaestroCommand(commandStr!!, null)
          } else {
            parseTrailblazePrompt(listOf(command))
          }
        }
      }
    }
  }

  // TODO: Many, many unit tests for this
  private fun parseTrailblazePrompt(
    value: Any?,
  ): TrailblazeObjective {
    val objectives = value as? List<*>
      ?: throw TrailblazeException("No objectives found in Trailblaze objective: $value")

    return if (shouldParseCommands(objectives)) {
      println("### parsing trailblaze commands")
      parseTrailblazeCommands(objectives)
    } else {
      println("### parsing full prompt with prompt steps")
      val fullInstructions = parseFullPrompt(objectives)
      val promptSteps = generatePromptSteps(fullInstructions)
      TrailblazePrompt(fullInstructions, promptSteps)
    }
  }

  private fun shouldParseCommands(objectives: List<*>): Boolean = objectives.all { it is Map<*, *> } and executeRecordedSteps

  private fun parseTrailblazeCommands(objectives: List<*>): TrailblazeCommand {
    val staticObjectives = objectives.map { obj ->
      val objMap = obj as? Map<*, *>
        ?: throw TrailblazeException("Cannot parse objectives from invalid type $obj")

      val objValue = objMap.values.firstOrNull()
      val steps: List<*> = objValue as? List<*>
        ?: throw TrailblazeException("Cannot parse trailblaze commands from empty steps")
      val desc = objMap.keys.firstOrNull()?.toString() ?: ""
      println("[MixedModeExecutor] Executing steps for objective: $desc")

      val tools = steps.map { parseTrailblazeToolsForStep(it) }
      StaticObjective(tools)
    }
    return TrailblazeCommand(staticObjectives)
  }

  private fun parseTrailblazeToolsForStep(step: Any?): TrailblazeTool {
    val stepMap = step as? Map<*, *>
      ?: throw TrailblazeException("Cannot parse Trailblaze tools for invalid step type $step")
    val toolName = stepMap.keys.firstOrNull()?.toString()
      ?: throw TrailblazeException("Unknown tool name provided for $step")
    val params = stepMap.values.firstOrNull() as? Map<*, *> ?: emptyMap<String, Any?>()
    val toolClass = allPossibleTools.firstOrNull { toolKClass ->
      toolKClass.toKoogToolDescriptor().name == toolName
    } ?: throw TrailblazeException("Could not find TrailblazeTool that matches tool name $toolName")
    val jsonParams = buildJsonObject {
      params.forEach { (k, v) ->
        when (v) {
          is Boolean -> put(k.toString(), JsonPrimitive(v))
          is Number -> put(k.toString(), JsonPrimitive(v))
          is String -> put(k.toString(), JsonPrimitive(v))
          else -> put(k.toString(), JsonPrimitive(v.toString()))
        }
      }
    }
    return try {
      JsonSerializationUtil.deserializeTrailblazeTool(toolClass, jsonParams)
    } catch (e: Exception) {
      throw TrailblazeException("Failed to deserialize tool: $toolName with params $params: \" + e.message")
    }
  }

  private fun parseFullPrompt(objectives: List<*>): String {
    // Combine all descriptions into a single objective separated by newlines
    val fullInstructions = objectives.mapNotNull {
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
    if (fullInstructions.isBlank()) {
      throw TrailblazeException("Cannot parse objectives from empty instructions.")
    }
    return fullInstructions
  }

  private fun generatePromptSteps(stepInstructions: String): List<TrailblazePromptStep> {
    val taskId = UUID.randomUUID().toString()
    return stepInstructions.lines()
      .map { it.trim() }
      .filter { it.isNotEmpty() }
      .mapIndexed { index, line ->
        TrailblazePromptStep(
          description = line,
          taskId = taskId,
          taskIndex = index,
          fullPrompt = stepInstructions,
        )
      }
  }

  private fun parseRememberTextCommand(value: Any?): RememberTextCommand = if (value is Map<*, *>) {
    val prompt = value["prompt"]?.toString()
    val variable = value["variable"]?.toString()

    if (prompt == null || variable == null) {
      throw TrailblazeException("Invalid rememberText command: missing prompt or variable name")
    }

    RememberTextCommand(prompt, variable)
  } else {
    throw TrailblazeException("Invalid rememberText command format")
  }

  private fun parseRememberNumberCommand(value: Any?): RememberNumberCommand = if (value is Map<*, *>) {
    val prompt = value["prompt"]?.toString()
    val variable = value["variable"]?.toString()

    if (prompt == null || variable == null) {
      throw TrailblazeException("Invalid rememberNumber command: missing prompt or variable name")
    }

    RememberNumberCommand(prompt, variable)
  } else {
    throw TrailblazeException("Invalid rememberNumber command format")
  }

  private fun parseRememberWithAiCommand(value: Any?): RememberWithAiCommand = if (value is Map<*, *>) {
    val prompt = value["prompt"]?.toString()
    val variable = value["variable"]?.toString()

    if (prompt == null || variable == null) {
      throw TrailblazeException("Invalid rememberWithAI command: missing prompt or variable name")
    }
    RememberWithAiCommand(prompt, variable)
  } else {
    throw TrailblazeException("Invalid rememberWithAI command format")
  }

  private fun parseAssertEqualsCommand(value: Any?): AssertEqualsCommand = if (value is Map<*, *>) {
    val actual = value["actual"]?.toString()
    val expected = value["expected"]?.toString()

    if (actual == null || expected == null) {
      throw TrailblazeException("Invalid assertEquals command: missing actual or expected name")
    }

    AssertEqualsCommand(actual, expected)
  } else {
    throw TrailblazeException("Invalid assertEquals command format")
  }

  private fun parseAssertNotEqualsCommand(value: Any?): AssertNotEqualsCommand = if (value is Map<*, *>) {
    val actual = value["actual"]?.toString()
    val expected = value["expected"]?.toString()

    if (actual == null || expected == null) {
      throw TrailblazeException("Invalid assertNotEquals command: missing actual or expected name")
    }

    AssertNotEqualsCommand(actual, expected)
  } else {
    throw TrailblazeException("Invalid assertNotEquals command format")
  }

  private fun parseAssertWithAiCommand(value: Any?): AssertWithAiCommand = if (value is Map<*, *>) {
    val prompt = value["prompt"]?.toString()
      ?: throw TrailblazeException("Invalid assertWithAi command: missing prompt")

    AssertWithAiCommand(prompt)
  } else {
    throw TrailblazeException("Invalid assertWithAi command")
  }

  private fun parseAssertMathCommand(value: Any?): AssertMathCommand = if (value is Map<*, *>) {
    val expression = value["expression"]?.toString()
    val expected = value["expected"]?.toString()

    if (expression == null || expected == null) {
      throw TrailblazeException("Invalid assertMath command: missing expression or expected values")
    }

    AssertMathCommand(expression, expected)
  } else {
    throw TrailblazeException("Invalid assertMath command")
  }

  private fun parseMaestroCommand(key: String, value: Any?): MaestroCommand {
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

    return MaestroCommand(maestroCommand)
  }

  companion object {
    private val trailblazeCommands = setOf(
      "tb",
      "trailblaze",
      "🧭",
      "run",
    )
    private val noParamMaestroCommands = setOf(
      "scroll",
      "back",
      "hideKeyboard",
      "waitForAnimationToEnd",
    )
    private val maestroCommands = setOf(
      "addMedia",
      "assertVisible",
      "assertNotVisible",
      "assertTrue",
      "assertNoDefectsWithAi",
      "back",
      "clearKeychain",
      "clearState",
      "copyTextFrom",
      "evalScript",
      "eraseText",
      "extendedWaitUntil",
      "hideKeyboard",
      "inputText",
      "killApp",
      "launchApp",
      "openLink",
      "pressKey",
      "pasteText",
      "repeat",
      "retry",
      "runFlow",
      "runScript",
      "scroll",
      "scrollUntilVisible",
      "setAirplaneMode",
      "setLocation",
      "startRecording",
      "stopApp",
      "stopRecording",
      "swipe",
      "takeScreenshot",
      "toggleAirplaneMode",
      "tapOn",
      "doubleTapOn",
      "longPressOn",
      "travel",
      "waitForAnimationToEnd",
    )

    private fun String?.isTrailblazeCommand(): Boolean = this != null && trailblazeCommands.contains(this)
    private fun String?.isRememberTextCommand(): Boolean = "rememberText" == this
    private fun String?.isRememberNumberCommand(): Boolean = "rememberNumber" == this
    private fun String?.isRememberWithAiCommand(): Boolean = "rememberWithAI" == this
    private fun String?.isAssertEqualsCommand(): Boolean = "assertEquals" == this
    private fun String?.isAssertNotEqualsCommand(): Boolean = "assertNotEquals" == this
    private fun String?.isAssertWithAiCommand(): Boolean = "assertWithAI" == this
    private fun String?.isAssertMathCommand(): Boolean = "assertMath" == this
    private fun String?.isMaestroCommand(): Boolean = this != null && maestroCommands.contains(this)
    private fun String?.isNoParamMaestroCommand(): Boolean = this != null && noParamMaestroCommands.contains(this)
  }
}
