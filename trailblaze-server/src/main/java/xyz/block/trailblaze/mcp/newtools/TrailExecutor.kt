package xyz.block.trailblaze.mcp.newtools

import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.mcp.TrailblazeMcpBridge
import xyz.block.trailblaze.mcp.TrailblazeMcpSessionContext
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.yaml.DirectionStep
import xyz.block.trailblaze.yaml.PromptStep
import xyz.block.trailblaze.yaml.TrailYamlItem
import xyz.block.trailblaze.yaml.TrailblazeYaml
import xyz.block.trailblaze.yaml.VerificationStep
import java.io.File
import xyz.block.trailblaze.util.Console

/**
 * Result of trail execution.
 */
@Serializable
data class TrailExecutionResult(
  /** Whether all steps passed */
  val passed: Boolean,

  /** Total number of steps executed */
  val stepsExecuted: Int,

  /** Execution duration in milliseconds */
  val durationMs: Long,

  /** Step index where failure occurred (0-indexed), null if passed */
  val failedAtStep: Int? = null,

  /** Human-readable failure reason, null if passed */
  val failureReason: String? = null,

  /** List of step results for detailed reporting */
  val stepResults: List<StepExecutionResult> = emptyList(),
) {
  fun toJson(): String = TrailblazeJsonInstance.encodeToString(serializer(), this)
}

/**
 * Result of executing a single step within a trail.
 */
@Serializable
data class StepExecutionResult(
  /** Step index (0-indexed) */
  val stepIndex: Int,

  /** The prompt/step text */
  val prompt: String,

  /** Whether this was a direction step (step) or verification step (verify) */
  val stepType: String,

  /** Whether this step passed */
  val passed: Boolean,

  /** Error message if step failed */
  val error: String? = null,

  /** Number of tools executed in this step */
  val toolsExecuted: Int = 0,
)

/**
 * Executes trail files deterministically without AI.
 *
 * This interface allows running recorded trails by replaying the tool calls
 * captured during recording. Steps without recordings will fail (no AI fallback).
 *
 * Usage:
 * ```
 * val executor = TrailExecutorImpl(mcpBridge, trailsDirectory)
 * val result = executor.executeFromFile("login_test.trail.yaml")
 * if (!result.passed) {
 *   Console.log("Failed at step ${result.failedAtStep}: ${result.failureReason}")
 * }
 * ```
 */
interface TrailExecutor {
  /**
   * Executes a trail from a YAML file path.
   *
   * @param filePath Path to the .trail.yaml file
   * @param onProgress Optional callback for progress updates
   * @return Execution result with pass/fail status and details
   */
  suspend fun executeFromFile(
    filePath: String,
    onProgress: ((String) -> Unit)? = null,
  ): TrailExecutionResult

  /**
   * Executes a trail from parsed YAML items.
   *
   * @param trailItems List of TrailYamlItem parsed from YAML
   * @param trailName Name of the trail (for logging)
   * @param onProgress Optional callback for progress updates
   * @return Execution result with pass/fail status and details
   */
  suspend fun execute(
    trailItems: List<TrailYamlItem>,
    trailName: String,
    onProgress: ((String) -> Unit)? = null,
  ): TrailExecutionResult
}

/**
 * Default implementation of TrailExecutor.
 *
 * Executes trails by:
 * 1. Parsing the YAML file using TrailblazeYaml
 * 2. Iterating through prompt steps
 * 3. For steps with recordings: executing recorded tools via mcpBridge
 * 4. For steps without recordings: failing (deterministic mode = no AI)
 */
class TrailExecutorImpl(
  private val mcpBridge: TrailblazeMcpBridge,
  private val sessionContext: TrailblazeMcpSessionContext?,
  private val trailsDirectory: String = "./trails",
) : TrailExecutor {

  private val trailblazeYaml = TrailblazeYaml.Default

  override suspend fun executeFromFile(
    filePath: String,
    onProgress: ((String) -> Unit)?,
  ): TrailExecutionResult {
    val startTime = Clock.System.now()

    // Resolve file path
    val file = resolveFilePath(filePath)
    if (!file.exists()) {
      return TrailExecutionResult(
        passed = false,
        stepsExecuted = 0,
        durationMs = (Clock.System.now() - startTime).inWholeMilliseconds,
        failureReason = "Trail file not found: ${file.absolutePath}",
      )
    }

    onProgress?.invoke("Loading trail: ${file.name}")

    // Parse YAML
    val trailItems = try {
      val yamlContent = file.readText()
      trailblazeYaml.decodeTrail(yamlContent)
    } catch (e: Exception) {
      return TrailExecutionResult(
        passed = false,
        stepsExecuted = 0,
        durationMs = (Clock.System.now() - startTime).inWholeMilliseconds,
        failureReason = "Failed to parse trail YAML: ${e.message}",
      )
    }

    val trailName = file.nameWithoutExtension.removeSuffix(".trail")
    return execute(trailItems, trailName, onProgress)
  }

  override suspend fun execute(
    trailItems: List<TrailYamlItem>,
    trailName: String,
    onProgress: ((String) -> Unit)?,
  ): TrailExecutionResult {
    val startTime = Clock.System.now()
    val stepResults = mutableListOf<StepExecutionResult>()

    // Extract all prompt steps from the trail
    val promptSteps = trailItems
      .filterIsInstance<TrailYamlItem.PromptsTrailItem>()
      .flatMap { it.promptSteps }

    // Also extract tool items (these execute without AI)
    val toolItems = trailItems.filterIsInstance<TrailYamlItem.ToolTrailItem>()

    if (promptSteps.isEmpty() && toolItems.isEmpty()) {
      return TrailExecutionResult(
        passed = false,
        stepsExecuted = 0,
        durationMs = (Clock.System.now() - startTime).inWholeMilliseconds,
        failureReason = "Trail has no executable steps",
      )
    }

    onProgress?.invoke("Running trail '$trailName' with ${promptSteps.size} prompt steps and ${toolItems.size} tool items")

    var stepIndex = 0

    // Execute tool items first (these are direct tool calls, not prompt-based)
    for (toolItem in toolItems) {
      for (toolWrapper in toolItem.tools) {
        val tool = toolWrapper.trailblazeTool
        onProgress?.invoke("Executing tool: ${toolWrapper.name}")

        val result = try {
          mcpBridge.executeTrailblazeTool(tool)
          true to null
        } catch (e: Exception) {
          false to e.message
        }

        stepResults.add(
          StepExecutionResult(
            stepIndex = stepIndex,
            prompt = "[tool] ${toolWrapper.name}",
            stepType = "tool",
            passed = result.first,
            error = result.second,
            toolsExecuted = 1,
          ),
        )

        if (!result.first) {
          return TrailExecutionResult(
            passed = false,
            stepsExecuted = stepIndex + 1,
            durationMs = (Clock.System.now() - startTime).inWholeMilliseconds,
            failedAtStep = stepIndex,
            failureReason = "Tool execution failed: ${result.second}",
            stepResults = stepResults,
          )
        }
        stepIndex++
      }
    }

    // Execute prompt steps
    for (promptStep in promptSteps) {
      val stepType = when (promptStep) {
        is DirectionStep -> "step"
        is VerificationStep -> "verify"
      }

      onProgress?.invoke("Step ${stepIndex + 1}/${promptSteps.size + toolItems.sumOf { it.tools.size }}: [${stepType}] ${promptStep.prompt.take(50)}...")

      val result = executePromptStep(promptStep, stepIndex, onProgress)
      stepResults.add(result)

      if (!result.passed) {
        return TrailExecutionResult(
          passed = false,
          stepsExecuted = stepIndex + 1,
          durationMs = (Clock.System.now() - startTime).inWholeMilliseconds,
          failedAtStep = stepIndex,
          failureReason = result.error ?: "Step failed",
          stepResults = stepResults,
        )
      }
      stepIndex++
    }

    val totalSteps = stepIndex
    onProgress?.invoke("Trail '$trailName' completed successfully (${totalSteps} steps)")

    return TrailExecutionResult(
      passed = true,
      stepsExecuted = totalSteps,
      durationMs = (Clock.System.now() - startTime).inWholeMilliseconds,
      stepResults = stepResults,
    )
  }

  /**
   * Executes a single prompt step using its recorded tools.
   * If no recording exists, the step fails (no AI fallback in deterministic mode).
   */
  private suspend fun executePromptStep(
    promptStep: PromptStep,
    stepIndex: Int,
    onProgress: ((String) -> Unit)?,
  ): StepExecutionResult {
    val stepType = when (promptStep) {
      is DirectionStep -> "step"
      is VerificationStep -> "verify"
    }

    // Check for recording
    val recording = promptStep.recording
    if (recording == null || recording.tools.isEmpty()) {
      return StepExecutionResult(
        stepIndex = stepIndex,
        prompt = promptStep.prompt,
        stepType = stepType,
        passed = false,
        error = "No recording for this step. Deterministic execution requires recorded tool calls.",
        toolsExecuted = 0,
      )
    }

    // Execute recorded tools
    var toolsExecuted = 0
    for (toolWrapper in recording.tools) {
      val tool: TrailblazeTool = toolWrapper.trailblazeTool

      onProgress?.invoke("  Executing: ${toolWrapper.name}")

      try {
        val result = mcpBridge.executeTrailblazeTool(tool)
        toolsExecuted++

        // Check for error in result using robust detection
        if (isToolExecutionError(result)) {
          return StepExecutionResult(
            stepIndex = stepIndex,
            prompt = promptStep.prompt,
            stepType = stepType,
            passed = false,
            error = "Tool '${toolWrapper.name}' returned error: $result",
            toolsExecuted = toolsExecuted,
          )
        }
      } catch (e: Exception) {
        return StepExecutionResult(
          stepIndex = stepIndex,
          prompt = promptStep.prompt,
          stepType = stepType,
          passed = false,
          error = "Tool '${toolWrapper.name}' threw exception: ${e.message}",
          toolsExecuted = toolsExecuted,
        )
      }
    }

    return StepExecutionResult(
      stepIndex = stepIndex,
      prompt = promptStep.prompt,
      stepType = stepType,
      passed = true,
      toolsExecuted = toolsExecuted,
    )
  }

  /**
   * Determines if a tool execution result indicates an error.
   *
   * Uses multiple heuristics to distinguish genuine errors from normal output:
   * 1. JSON responses with explicit "error" field (not "success":true)
   * 2. JSON responses with "passed":false (verification failures)
   * 3. JSON responses with "executed":false and "error" field
   *
   * @param result The string result from tool execution
   * @return true if the result indicates an error, false otherwise
   */
  private fun isToolExecutionError(result: String): Boolean {
    // Skip non-JSON results - they're usually success messages
    if (!result.trimStart().startsWith("{")) {
      return false
    }

    // Explicit success indicators take priority — even if an "error" key is present
    // (e.g., "error":null or "error":"" alongside "success":true is not an error)
    if (result.contains("\"success\":true", ignoreCase = true) ||
      result.contains("\"passed\":true", ignoreCase = true) ||
      result.contains("\"executed\":true", ignoreCase = true)
    ) {
      return false
    }

    // Check for explicit failure indicators
    if (result.contains("\"passed\":false", ignoreCase = true)) return true
    if (result.contains("\"executed\":false", ignoreCase = true)) return true

    // Check for error field with a non-null, non-empty value
    // Matches "error":"some message" but not "error":null or "error":""
    val errorWithValuePattern = """"error"\s*:\s*"[^"]+"""".toRegex()
    if (errorWithValuePattern.containsMatchIn(result)) return true

    return false
  }

  /**
   * Resolves a file path, checking multiple locations.
   * Validates that the resolved path stays within the trails directory to prevent path traversal.
   */
  private fun resolveFilePath(filePath: String): File {
    val trailsDirCanonical = File(trailsDirectory).canonicalPath

    // If it's an absolute path, validate it's within the trails directory
    if (File(filePath).isAbsolute) {
      val resolved = File(filePath)
      if (!resolved.canonicalPath.startsWith(trailsDirCanonical + File.separator) &&
        resolved.canonicalPath != trailsDirCanonical
      ) {
        throw IllegalArgumentException(
          "Path traversal detected: absolute path '$filePath' is outside the trails directory"
        )
      }
      return resolved
    }

    // Try relative to trails directory
    val inTrailsDir = File(trailsDirectory, filePath)
    if (inTrailsDir.exists()) {
      return validateWithinTrailsDir(inTrailsDir, trailsDirCanonical, filePath)
    }

    // Try adding .trail.yaml extension
    if (!filePath.endsWith(".trail.yaml")) {
      val withExtension = File(trailsDirectory, "$filePath.trail.yaml")
      if (withExtension.exists()) {
        return validateWithinTrailsDir(withExtension, trailsDirCanonical, filePath)
      }
    }

    // Search recursively in trails directory
    val trailsDir = File(trailsDirectory)
    if (trailsDir.exists()) {
      val baseName = File(filePath).name
      trailsDir.walkTopDown()
        .filter { it.isFile && (it.name == baseName || it.name == "$baseName.trail.yaml") }
        .firstOrNull()
        ?.let { return validateWithinTrailsDir(it, trailsDirCanonical, filePath) }
    }

    // Return relative to trails dir (will fail with "not found" if it doesn't exist)
    // Validate even non-existent paths to prevent traversal attempts
    val fallback = File(trailsDirectory, filePath)
    return validateWithinTrailsDir(fallback, trailsDirCanonical, filePath)
  }

  /**
   * Validates that a resolved file is within the trails directory.
   * Prevents path traversal via sequences like "../".
   */
  private fun validateWithinTrailsDir(
    file: File,
    trailsDirCanonical: String,
    originalPath: String,
  ): File {
    val fileCanonical = file.canonicalPath
    if (!fileCanonical.startsWith(trailsDirCanonical + File.separator) &&
      fileCanonical != trailsDirCanonical
    ) {
      throw IllegalArgumentException(
        "Path traversal detected: '$originalPath' resolves outside the trails directory"
      )
    }
    return file
  }
}
