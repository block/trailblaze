package xyz.block.trailblaze.cli

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
class StepCommand : Callable<Int>, QuietUnlessVerbose {
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

  /**
   * Closes the internal [Console.log] channel for this command unless `--verbose`, applied at
   * dispatch so the early-return paths are covered too — see [QuietUnlessVerbose].
   */
  override val verboseRequested: Boolean get() = verbose

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
    description = ["Save the current session's steps as a trail file."]
  )
  var savePath: String? = null

  // Retired: `--save` used to require `--setup <range>` or this flag. Still accepted and ignored,
  // so commands written against the old form keep working.
  @Option(names = ["--no-setup"], hidden = true)
  var noSetup: Boolean = false

  @CommandLine.Mixin
  val headlessOption: HeadlessOption = HeadlessOption()

  override fun call(): Int {
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

  /** Handles the --save flow: export the current session as a trail YAML file. */
  private fun handleSave(): Int = quietUnlessVerbose(verbose) { writeSavedTrail() }

  /** The `--save` flow proper; [handleSave] owns the quiet scope around it. */
  private fun writeSavedTrail(): Int {
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
      } catch (e: CliMcpClient.DaemonStarvedException) {
        // Not "no active session" -- the session may well be there; the daemon just did not answer
        // in time to say. Its own message names the symptom and what to do.
        reportDaemonStarved(e.message ?: "the daemon did not answer the pre-flight probe")
        return@runBlocking TrailblazeExitCode.INFRA_FAILED.code
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

        return@use performSave(it, steps)
      }
    }
  }

  private suspend fun performSave(
    client: CliMcpClient,
    steps: List<Triple<Int, String, String>>
  ): Int {
    val path = savePath!!

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

    outputFile.writeText(yamlContent)

    if (generatedFile.canonicalPath != outputFile.canonicalPath) {
      generatedFile.delete()
      generatedFile.parentFile?.let { dir ->
        if (dir.exists() && dir.listFiles()?.isEmpty() == true) dir.delete()
      }
    }

    Console.info("Trail saved: ${outputFile.path}")
    Console.info("  Steps: ${steps.size}")

    return TrailblazeExitCode.SUCCESS.code
  }
}
