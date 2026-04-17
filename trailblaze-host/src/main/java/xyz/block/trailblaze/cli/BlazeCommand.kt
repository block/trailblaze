package xyz.block.trailblaze.cli

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import xyz.block.trailblaze.util.Console
import java.util.concurrent.Callable

/**
 * Take the next step toward an objective on a connected device.
 *
 * Each blaze call is one step in an ongoing exploration — Trailblaze's AI agent
 * analyzes the screen and acts. Use 'trail' to execute complete trail files.
 * For direct tool execution with known tools, use `trailblaze tool --yaml`.
 *
 * Connects to the running Trailblaze daemon via MCP and calls the `blaze` tool.
 * The daemon must be running (`trailblaze app --headless` or the desktop app).
 *
 * Examples:
 *   trailblaze blaze "Tap the login button"
 *   trailblaze blaze --verify "The email field is visible"
 *   trailblaze blaze -d ANDROID "Open settings"
 */
@Command(
  name = "blaze",
  mixinStandardHelpOptions = true,
  description = ["Drive a device with AI — describe what to do in plain English"]
)
class BlazeCommand : Callable<Int> {

  @Parameters(
    description = ["Objective or assertion (e.g., 'Tap login', 'The email field is visible')"],
    arity = "0..*",
  )
  var goalWords: List<String> = emptyList()

  @Option(
    names = ["--verify"],
    description = ["Verify an assertion instead of taking an action (exit code 1 if assertion fails)"]
  )
  var verify: Boolean = false

  @Option(
    names = ["-d", "--device"],
    description = ["Device: platform (android, ios, web) or platform/id (e.g., android/emulator-5554). " +
      "Switches the daemon's active device for all clients. Required for multi-device workflows."]
  )
  var device: String? = null

  @Option(
    names = ["--context"],
    description = ["Context from previous steps for situational awareness"]
  )
  var context: String? = null

  @Option(
    names = ["-v", "--verbose"],
    description = ["Enable verbose output (show daemon logs, MCP calls)"]
  )
  var verbose: Boolean = false

  @Option(
    names = ["--target"],
    description = ["Target app ID. Saved for future commands."]
  )
  var target: String? = null

  @Option(
    names = ["--fast"],
    description = ["Text-only mode: skip screenshots, use text-only screen analysis (no vision tokens sent to LLM), and skip disk logging. Also enabled by BLAZE_FAST=1 env var."],
  )
  var fast: Boolean = System.getenv("BLAZE_FAST") == "1"

  @Option(
    names = ["--save"],
    description = ["Save current session as a trail file. Shows steps if --setup not specified."]
  )
  var savePath: String? = null

  @Option(
    names = ["--setup"],
    description = ["Step range for setup/trailhead (e.g., '1-3'). Use with --save."]
  )
  var setup: String? = null

  @Option(
    names = ["--no-setup"],
    description = ["Save without setup steps. Use with --save."]
  )
  var noSetup: Boolean = false

  override fun call(): Int {
    // Save --target to sticky config if provided
    if (target != null) {
      CliConfigHelper.updateConfig { it.copy(selectedTargetAppId = target!!.lowercase()) }
    }

    // Validate --setup and --no-setup require --save
    if ((setup != null || noSetup) && savePath == null) {
      Console.error("Error: --setup and --no-setup require --save.")
      return CommandLine.ExitCode.USAGE
    }
    if (setup != null && noSetup) {
      Console.error("Error: --setup and --no-setup are mutually exclusive.")
      return CommandLine.ExitCode.USAGE
    }

    // Handle --save flow (doesn't need a goal or device)
    if (savePath != null) {
      return handleSave()
    }

    // Goal is always required (unless --save)
    val goal = goalWords.joinToString(" ").trim()
    if (goal.isEmpty()) {
      Console.error("Error: blaze requires an objective. Usage: trailblaze blaze \"Tap login\"")
      return CommandLine.ExitCode.USAGE
    }

    return cliWithDevice(verbose, device) { client ->
      // Execute blaze action
      val arguments = mutableMapOf<String, Any?>("objective" to goal)
      if (verify) arguments["hint"] = "VERIFY"
      if (context != null) arguments["context"] = context
      if (fast) arguments["fast"] = true

      val isNewDevice = !client.hasExistingDevice
      val result = client.callTool("blaze", arguments)

      formatBlazeResult(result)
      // Show Trailblaze session ID after the first action in a new session
      if (isNewDevice && result.isSuccess) {
        client.getTrailblazeSessionId()?.let {
          Console.info("Session: trailblaze session info --id $it")
        }
      }

      if (result.isError) {
        return@cliWithDevice CommandLine.ExitCode.SOFTWARE
      }

      // Parse JSON once for error/verify checks
      val parsedJson = try {
        Json.parseToJsonElement(result.content).jsonObject
      } catch (_: Exception) {
        // Not JSON — for verify, parse markdown for pass/fail status
        if (verify) {
          val passed = parseVerifyPassedFromMarkdown(result.content)
          return@cliWithDevice if (passed) CommandLine.ExitCode.OK else CommandLine.ExitCode.SOFTWARE
        }
        return@cliWithDevice CommandLine.ExitCode.OK
      }

      // Check JSON payload for errors
      val error = parsedJson["error"]?.jsonPrimitive?.content
      if (!error.isNullOrBlank()) {
        return@cliWithDevice CommandLine.ExitCode.SOFTWARE
      }

      if (verify) {
        val passed = parsedJson["passed"]?.jsonPrimitive?.content?.toBoolean() ?: false
        if (passed) CommandLine.ExitCode.OK else CommandLine.ExitCode.SOFTWARE
      } else {
        CommandLine.ExitCode.OK
      }
    }
  }

  private fun formatBlazeResult(result: CliMcpClient.ToolResult) {
    if (verify) {
      formatVerifyResultAgent(result)
    } else {
      formatBlazeResultAgent(result)
    }
  }

  /**
   * Parses Markdown output from StepResult.toMarkdown().
   *
   * Format: `**<emoji> <Status>** — <message>\n\n**Screen:** <summary>`
   *
   * @return Triple of (status, message, screenSummary) or null if not Markdown format
   */
  private fun parseMarkdownResult(content: String): Triple<String, String, String?>? {
    if (!content.startsWith("**")) return null
    val statusMatch = Regex("""^\*\*.*?(Done|Executed|Analyzed|PASSED|FAILED|Error|Needs input)\*\*""")
      .find(content) ?: return null
    val status = statusMatch.groupValues[1]
    val afterStatus = content.substring(statusMatch.range.last + 1)

    // Extract screen summary if present
    val screenMarker = "\n\n**Screen:** "
    val screenIdx = afterStatus.indexOf(screenMarker)
    val (messagePart, screenSummary) = if (screenIdx >= 0) {
      afterStatus.substring(0, screenIdx) to afterStatus.substring(screenIdx + screenMarker.length)
    } else {
      afterStatus to null
    }

    // Strip leading " — " separator
    val message = messagePart.removePrefix(" — ").trim()
    return Triple(status, message, screenSummary)
  }

  /**
   * Handles the --save flow: export the current session as a trail YAML file.
   *
   * If neither --setup nor --no-setup is provided, shows a numbered step list
   * and suggests follow-up commands (wizard mode).
   *
   * If --setup or --no-setup is provided, saves the trail to the specified path.
   */
  private fun handleSave(): Int {
    if (!verbose) Console.enableQuietMode()

    val port = CliConfigHelper.resolveEffectiveHttpPort()

    return runBlocking {
      val client = try {
        CliMcpClient.connectToDaemon(port)
      } catch (e: Exception) {
        Console.error("Error: No active session. ${e.message}")
        return@runBlocking CommandLine.ExitCode.SOFTWARE
      }

      client.use {
        val infoResult = it.callTool("session", mapOf("action" to "INFO"))
        if (infoResult.isError) {
          Console.error("Error: ${infoResult.content}")
          return@use CommandLine.ExitCode.SOFTWARE
        }

        val infoJson = try {
          Json.parseToJsonElement(infoResult.content).jsonObject
        } catch (_: Exception) {
          Console.error("Error: Failed to parse session info.")
          return@use CommandLine.ExitCode.SOFTWARE
        }

        val infoError = infoJson["error"]?.jsonPrimitive?.content
        if (!infoError.isNullOrBlank()) {
          Console.error("Error: $infoError")
          return@use CommandLine.ExitCode.SOFTWARE
        }

        val stepsArray = infoJson["steps"]?.let { stepsElement ->
          try { stepsElement.jsonArray } catch (_: Exception) { null }
        }

        if (stepsArray == null || stepsArray.isEmpty()) {
          Console.error("Error: No recorded steps in session. Use blaze to record actions first.")
          return@use CommandLine.ExitCode.SOFTWARE
        }

        val steps = stepsArray.map { stepElement ->
          val stepObj = stepElement.jsonObject
          val index = stepObj["index"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
          val type = stepObj["type"]?.jsonPrimitive?.content ?: "STEP"
          val input = stepObj["input"]?.jsonPrimitive?.content ?: ""
          Triple(index, type, input)
        }

        if (setup == null && !noSetup) {
          return@use showSaveWizard(steps)
        }
        return@use performSave(it, steps)
      }
    }
  }

  private fun showSaveWizard(steps: List<Triple<Int, String, String>>): Int {
    val path = savePath!!

    Console.info("Session has ${steps.size} steps:")
    for ((index, type, input) in steps) {
      val prefix = when (type) {
        "VERIFY" -> "Verify: "
        "ASK" -> "Ask: "
        else -> ""
      }
      Console.info("  $index. $prefix$input")
    }

    Console.info("")
    Console.info("Specify setup steps to save:")
    val cmd = "trailblaze blaze"
    val suggestedEnd = (steps.size / 2).coerceAtLeast(1).coerceAtMost(steps.size - 1)
    if (suggestedEnd < steps.size) {
      Console.info("  $cmd --save $path --setup 1-$suggestedEnd")
    }
    Console.info("  $cmd --save $path --no-setup")

    return CommandLine.ExitCode.OK
  }

  private suspend fun performSave(
    client: CliMcpClient,
    steps: List<Triple<Int, String, String>>
  ): Int {
    val path = savePath!!
    val setupStepIndices: Set<Int> = if (setup != null) {
      parseStepRange(setup!!, steps.size) ?: run {
        Console.error("Error: Invalid step range '$setup'. Use e.g. '1-3' or '1,2,3'.")
        return CommandLine.ExitCode.USAGE
      }
    } else {
      emptySet()
    }

    val file = java.io.File(path)
    val title = file.nameWithoutExtension.removeSuffix(".trail")

    val saveResult = client.callTool("session", mapOf("action" to "SAVE", "title" to title))
    if (saveResult.isError) {
      Console.error("Error saving trail: ${saveResult.content}")
      return CommandLine.ExitCode.SOFTWARE
    }

    val saveJson = try {
      Json.parseToJsonElement(saveResult.content).jsonObject
    } catch (_: Exception) {
      Console.error("Error: Failed to parse save result.")
      return CommandLine.ExitCode.SOFTWARE
    }

    val saveError = saveJson["error"]?.jsonPrimitive?.content
    if (!saveError.isNullOrBlank()) {
      Console.error("Error: $saveError")
      return CommandLine.ExitCode.SOFTWARE
    }

    val generatedPath = saveJson["file"]?.jsonPrimitive?.content
    if (generatedPath == null) {
      Console.error("Error: Save succeeded but no file path returned.")
      return CommandLine.ExitCode.SOFTWARE
    }

    val generatedFile = java.io.File(generatedPath)
    if (!generatedFile.exists()) {
      Console.error("Error: Generated trail file not found: $generatedPath")
      return CommandLine.ExitCode.SOFTWARE
    }

    val yamlContent = generatedFile.readText()
    val outputFile = file.let { f ->
      if (f.isAbsolute) f else java.io.File(System.getProperty("user.dir"), path)
    }
    outputFile.parentFile?.let { dir ->
      if (!dir.exists()) dir.mkdirs()
    }

    if (setupStepIndices.isNotEmpty()) {
      outputFile.writeText(restructureWithSetup(yamlContent, setupStepIndices))
    } else {
      outputFile.writeText(yamlContent)
    }

    if (generatedFile.canonicalPath != outputFile.canonicalPath) {
      generatedFile.delete()
      generatedFile.parentFile?.let { dir ->
        if (dir.exists() && dir.listFiles()?.isEmpty() == true) dir.delete()
      }
    }

    val setupCount = setupStepIndices.size
    val trailCount = steps.size - setupCount
    Console.info("Trail saved: ${outputFile.path}")
    if (setupCount > 0) {
      Console.info("  Setup steps (trailhead): $setupCount")
      Console.info("  Trail steps: $trailCount")
    } else {
      Console.info("  Steps: ${steps.size}")
    }

    return CommandLine.ExitCode.OK
  }

  internal fun parseStepRange(range: String, maxSteps: Int): Set<Int>? {
    val indices = mutableSetOf<Int>()
    for (part in range.split(",")) {
      val trimmed = part.trim()
      if (trimmed.contains("-")) {
        val bounds = trimmed.split("-")
        if (bounds.size != 2) return null
        val start = bounds[0].trim().toIntOrNull() ?: return null
        val end = bounds[1].trim().toIntOrNull() ?: return null
        if (start < 1 || end > maxSteps || start > end) return null
        indices.addAll(start..end)
      } else {
        val idx = trimmed.toIntOrNull() ?: return null
        if (idx < 1 || idx > maxSteps) return null
        indices.add(idx)
      }
    }
    return if (indices.isEmpty()) null else indices
  }

  internal fun restructureWithSetup(yamlContent: String, setupIndices: Set<Int>): String {
    val lines = yamlContent.lines()

    // Parse the YAML structurally to find the prompts list and step boundaries.
    val rootNode = Yaml.default.parseToYamlNode(yamlContent)
    val rootList = rootNode as? YamlList
      ?: return yamlContent // Not the expected list format; return unchanged.

    // Find the first top-level item whose map key is "prompts".
    var promptsItemIndex = -1
    var promptsStepNodes: List<YamlNode> = emptyList()
    for ((i, item) in rootList.items.withIndex()) {
      val map = item as? YamlMap ?: continue
      val promptsValue = map.entries.entries.firstOrNull { it.key.content == "prompts" }?.value
      if (promptsValue != null) {
        promptsItemIndex = i
        promptsStepNodes = (promptsValue as? YamlList)?.items ?: emptyList()
        break
      }
    }

    if (promptsItemIndex == -1 || promptsStepNodes.isEmpty()) {
      return yamlContent // No prompts found; return unchanged.
    }

    // Determine the line where the "- prompts:" item starts (1-indexed).
    val promptsMapNode = rootList.items[promptsItemIndex]
    val promptsStartLine = promptsMapNode.location.line

    // Collect the start line (1-indexed) of each step in the prompts list.
    val stepStartLines = promptsStepNodes.map { it.location.line }

    // Header = everything before the prompts item line.
    val headerLines = lines.take(promptsStartLine - 1)

    // Step blocks: each step runs from its start line to just before the next step (or EOF).
    val totalLines = lines.size
    val setupStepBlocks = mutableListOf<List<String>>()
    val trailStepBlocks = mutableListOf<List<String>>()
    for ((stepIdx, startLine) in stepStartLines.withIndex()) {
      val endLine = if (stepIdx + 1 < stepStartLines.size) {
        stepStartLines[stepIdx + 1] - 1
      } else {
        totalLines
      }
      // lines is 0-indexed; startLine is 1-indexed.
      val stepLines = lines.subList(startLine - 1, endLine)
      val stepNumber = stepIdx + 1 // 1-based, matching setupIndices convention
      if (stepNumber in setupIndices) {
        setupStepBlocks.add(stepLines)
      } else {
        trailStepBlocks.add(stepLines)
      }
    }

    // Reassemble the output.
    val result = StringBuilder()
    for (h in headerLines) result.appendLine(h)
    if (setupStepBlocks.isNotEmpty()) {
      result.appendLine("# setup (trailhead)")
      result.appendLine("- prompts:")
      for (block in setupStepBlocks) for (l in block) result.appendLine(l)
    }
    result.appendLine("# trail")
    result.appendLine("- prompts:")
    for (block in trailStepBlocks) for (l in block) result.appendLine(l)

    return result.toString().trimEnd() + "\n"
  }
}
