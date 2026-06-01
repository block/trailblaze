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
import java.io.File
import java.util.concurrent.Callable

/**
 * Run one step on a connected device — you describe what you want in plain English,
 * the built-in agent picks the tools.
 *
 * Each `step` call records one step in the trail YAML. Use `trailblaze tool` when
 * you already know the primitive to call; use `trailblaze step` when you want the
 * built-in agent to derive the implementation. Either way the resulting trail YAML
 * has the same shape — both produce step entries.
 *
 * Requires an LLM provider configured (`trailblaze config llm <provider/model>`).
 * Connects to the running Trailblaze daemon via MCP. The daemon must be running
 * (`trailblaze app --headless` or the desktop app).
 *
 * Examples:
 *   trailblaze step -d android/emulator-5554 "Tap the login button"
 *   trailblaze step -d ios/SIM-UUID --verify "The email field is visible"
 *   trailblaze step -d ANDROID "Open settings"
 *
 * `blaze` is accepted as a deprecated alias (no runtime warning) for back-compat
 * with trails / scripts authored before the rename.
 */
@Command(
  name = "step",
  aliases = ["blaze"],
  mixinStandardHelpOptions = true,
  description = [
    "Run one step — describe what you want, the built-in agent picks the tools.",
    "Requires an LLM provider configured (`trailblaze config llm`).",
  ],
)
class StepCommand : Callable<Int> {
  // The shared per-device CLI session scope ([cliDeviceSessionScope]) and
  // [readLastCliSessionScope] / [writeLastCliSessionScope] live in
  // CliInfrastructure.kt so `tool`, `snapshot`, `ask`, `verify`, and `step`
  // all funnel into the same recording session per device — `step --save`
  // can then export a session that recorded any mix of those calls.

  @Parameters(
    description = ["Step description, or assertion when `--verify` is set (e.g., 'Tap login', 'The email field is visible')"],
    arity = "0..*",
  )
  var stepWords: List<String> = emptyList()

  @Option(
    names = ["--verify"],
    description = ["Verify an assertion instead of taking an action (exit code 1 if assertion fails)"]
  )
  var verify: Boolean = false

  @Option(
    names = ["-d", "--device"],
    description = [DEVICE_OPTION_DESCRIPTION]
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
    description = [TARGET_OPTION_DESCRIPTION]
  )
  var target: String? = null

  @Option(
    names = ["--no-screenshots", "--text-only"],
    description = [
      "Skip screenshots — the LLM only sees the textual view hierarchy, no vision " +
        "tokens, and disk logging of screenshots is skipped too. Faster and cheaper " +
        "for short objectives where the visual layout doesn't matter; some tasks need " +
        "vision and will degrade without it."
    ],
  )
  var noScreenshots: Boolean = false

  @Option(
    names = ["--snapshot-details"],
    description = [
      "Comma-separated snapshot detail levels passed through to the daemon's step tool: " +
        "BOUNDS, OFFSCREEN, OCCLUDED, ALL_ELEMENTS. Useful for waypoint capture: ALL_ELEMENTS bypasses " +
        "the on-device accessibility-importance filter so RecyclerView children land in the " +
        "captured trailblazeNodeTree. OCCLUDED is web-only and surfaces elements hidden under " +
        "popups/modals so the captured tree includes what's actually behind the overlay."
    ],
  )
  var snapshotDetails: String? = null

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

  @CommandLine.Mixin
  val headlessOption: HeadlessOption = HeadlessOption()

  override fun call(): Int {
    // Validate --setup and --no-setup require --save
    if ((setup != null || noSetup) && savePath == null) {
      reportCliError(
        verb = "Step",
        reason = "--setup / --no-setup require --save",
        hint = "pass --save <path> to write the recorded session to disk, then add --setup or --no-setup",
      )
      return TrailblazeExitCode.MISUSE.code
    }
    if (setup != null && noSetup) {
      reportCliError(
        verb = "Step",
        reason = "--setup and --no-setup are mutually exclusive",
        hint = "pass one or the other (or neither — without either flag, --save prints a wizard)",
      )
      return TrailblazeExitCode.MISUSE.code
    }

    // Handle --save flow (doesn't need a goal or device)
    if (savePath != null) {
      return handleSave()
    }

    // Step description is always required (unless --save)
    val step = stepWords.joinToString(" ").trim()
    if (step.isEmpty()) {
      reportCliError(
        verb = "Step",
        reason = "step requires a description",
        hint = "describe what this step does, e.g. `trailblaze step \"Tap login\"`",
      )
      return TrailblazeExitCode.MISUSE.code
    }
    // `require-steps` is satisfied by the positional step description — `step` can't be
    // invoked without one. Calling the helper anyway keeps every action command on the
    // same enforcement path and protects against future refactors that might loosen
    // the positional check.
    requireStepIfConfigured(step, verb = "step")?.let { return it }

    return cliReusableWithDevice(
      verbose = verbose,
      device = device,
      webHeadless = headlessOption.resolve(),
      target = target,
      verb = "Step",
    ) { client ->
      // Execute the step. The MCP tool name matches the CLI verb name ("step") —
      // the wire-protocol rename landed in the same change as the CLI rename so
      // user-facing surface and the MCP-client-visible tool registry stay in sync.
      // The argument key stays `objective` for now because trail YAML / recorded
      // session JSON persist it as `objective:` on disk — a future protocol bump
      // will rename the field in lockstep with the session-replay surface.
      val arguments = mutableMapOf<String, Any?>("objective" to step)
      if (verify) arguments["hint"] = "VERIFY"
      if (context != null) arguments["context"] = context
      if (noScreenshots) arguments["fast"] = true
      if (snapshotDetails != null) arguments["snapshotDetails"] = snapshotDetails

      val result = client.callTool("step", arguments)

      formatStepResult(result)

      if (result.isError) {
        return@cliReusableWithDevice TrailblazeExitCode.INFRA_FAILED.code
      }

      // Parse JSON once for error/verify checks
      val parsedJson = try {
        Json.parseToJsonElement(result.content).jsonObject
      } catch (_: Exception) {
        // Not JSON — for verify, parse markdown for pass/fail status. A `❌ FAILED`
        // marker is an ASSERTION-style outcome (the verify produced a verdict and
        // the verdict was false), not an INFRA failure — keep this in sync with
        // [formatVerifyResultAgent], which already returns ASSERTION_FAILED in the
        // matching code path.
        if (verify) {
          val passed = parseVerifyPassedFromMarkdown(result.content)
          return@cliReusableWithDevice if (passed) TrailblazeExitCode.SUCCESS.code else TrailblazeExitCode.ASSERTION_FAILED.code
        }
        return@cliReusableWithDevice TrailblazeExitCode.SUCCESS.code
      }

      // Check JSON payload for errors
      val error = parsedJson["error"]?.jsonPrimitive?.content
      if (!error.isNullOrBlank()) {
        return@cliReusableWithDevice TrailblazeExitCode.INFRA_FAILED.code
      }

      // `cliReusableWithDevice` already wrote the last-scope file before the
      // action ran, so `step --save` can find this session even on partial
      // failures — no need to re-write it here.

      if (verify) {
        // Mirror [formatVerifyResultAgent] and the markdown branch above: a
        // verify that produced a verdict of false is ASSERTION_FAILED, not
        // INFRA_FAILED. Reserve INFRA_FAILED for transport/tool-error paths
        // (the `error` branch above already covers that).
        val passed = parsedJson["passed"]?.jsonPrimitive?.content?.toBoolean() ?: false
        if (passed) TrailblazeExitCode.SUCCESS.code else TrailblazeExitCode.ASSERTION_FAILED.code
      } else {
        TrailblazeExitCode.SUCCESS.code
      }
    }
  }

  private fun formatStepResult(result: CliMcpClient.ToolResult) {
    if (verify) {
      formatVerifyResultAgent(result)
    } else {
      // `formatBlazeResultAgent` is the shared agent-output formatter — used here, by
      // `snapshot`, and by `tool`. The "Blaze" in the name is lineage (the blaze
      // command was the first caller); renaming to a verb-agnostic name is a separate
      // cleanup.
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
    val sessionScope = device?.takeIf { it.isNotBlank() }?.let(::cliDeviceSessionScope)
      ?: readLastCliSessionScope(port)

    return runBlocking {
      if (sessionScope == null) {
        Console.error("Error: No recorded step session. Run `trailblaze step` first, or pass --device to choose a device-scoped session.")
        return@runBlocking TrailblazeExitCode.INFRA_FAILED.code
      }
      val client = try {
        CliMcpClient.connectReusable(port, sessionScope = sessionScope)
      } catch (e: Exception) {
        Console.error("Error: No active session. ${e.message}")
        return@runBlocking TrailblazeExitCode.INFRA_FAILED.code
      }

      client.use {
        val infoResult = it.callTool("session", mapOf("action" to "INFO"))
        if (infoResult.isError) {
          Console.error("Error: ${infoResult.content}")
          return@use TrailblazeExitCode.INFRA_FAILED.code
        }

        val infoJson = try {
          Json.parseToJsonElement(infoResult.content).jsonObject
        } catch (_: Exception) {
          Console.error("Error: Failed to parse session info.")
          return@use TrailblazeExitCode.INFRA_FAILED.code
        }

        val infoError = infoJson["error"]?.jsonPrimitive?.content
        if (!infoError.isNullOrBlank()) {
          Console.error("Error: $infoError")
          return@use TrailblazeExitCode.INFRA_FAILED.code
        }

        val stepsArray = infoJson["steps"]?.let { stepsElement ->
          try { stepsElement.jsonArray } catch (_: Exception) { null }
        }

        if (stepsArray == null || stepsArray.isEmpty()) {
          Console.error("Error: No recorded steps in session. Run `trailblaze step` (or `trailblaze tool`) to record actions first.")
          return@use TrailblazeExitCode.INFRA_FAILED.code
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
    val cmd = "trailblaze step"
    val suggestedEnd = (steps.size / 2).coerceAtLeast(1).coerceAtMost(steps.size - 1)
    if (suggestedEnd < steps.size) {
      Console.info("  $cmd --save $path --setup 1-$suggestedEnd")
    }
    Console.info("  $cmd --save $path --no-setup")

    return TrailblazeExitCode.SUCCESS.code
  }

  private suspend fun performSave(
    client: CliMcpClient,
    steps: List<Triple<Int, String, String>>
  ): Int {
    val path = savePath!!
    val setupStepIndices: Set<Int> = if (setup != null) {
      parseStepRange(setup!!, steps.size) ?: run {
        Console.error("Error: Invalid step range '$setup'. Use e.g. '1-3' or '1,2,3'.")
        return TrailblazeExitCode.MISUSE.code
      }
    } else {
      emptySet()
    }

    val file = File(path)
    val title = file.nameWithoutExtension.removeSuffix(".trail")

    val saveResult = client.callTool("session", mapOf("action" to "SAVE", "title" to title))
    if (saveResult.isError) {
      Console.error("Error saving trail: ${saveResult.content}")
      return TrailblazeExitCode.INFRA_FAILED.code
    }

    val saveJson = try {
      Json.parseToJsonElement(saveResult.content).jsonObject
    } catch (_: Exception) {
      Console.error("Error: Failed to parse save result.")
      return TrailblazeExitCode.INFRA_FAILED.code
    }

    val saveError = saveJson["error"]?.jsonPrimitive?.content
    if (!saveError.isNullOrBlank()) {
      Console.error("Error: $saveError")
      return TrailblazeExitCode.INFRA_FAILED.code
    }

    val generatedPath = saveJson["file"]?.jsonPrimitive?.content
    if (generatedPath == null) {
      Console.error("Error: Save succeeded but no file path returned.")
      return TrailblazeExitCode.INFRA_FAILED.code
    }

    val generatedFile = File(generatedPath)
    if (!generatedFile.exists()) {
      Console.error("Error: Generated trail file not found: $generatedPath")
      return TrailblazeExitCode.INFRA_FAILED.code
    }

    val yamlContent = generatedFile.readText()
    // Resolve --save path. If it points at an existing directory, drop the generated
    // trail file inside it (mirroring `session save`'s default behavior). Without this,
    // `--save=trails/foo` against an existing folder would crash with FileNotFoundException
    // ("Is a directory") on the writeText below.
    val resolvedPath = file.let { f ->
      if (f.isAbsolute) f else File(System.getProperty("user.dir"), path)
    }
    val outputFile = if (resolvedPath.isDirectory) {
      File(resolvedPath, File(generatedPath).name)
    } else {
      resolvedPath
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

    return TrailblazeExitCode.SUCCESS.code
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
