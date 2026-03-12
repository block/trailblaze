package xyz.block.trailblaze.mcp.newtools

import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.mcp.RecordedStep
import xyz.block.trailblaze.mcp.RecordedStepType
import xyz.block.trailblaze.yaml.DirectionStep
import xyz.block.trailblaze.yaml.PromptStep
import xyz.block.trailblaze.yaml.ToolRecording
import xyz.block.trailblaze.yaml.TrailConfig
import xyz.block.trailblaze.yaml.TrailSource
import xyz.block.trailblaze.yaml.TrailSourceType
import xyz.block.trailblaze.yaml.TrailYamlItem
import xyz.block.trailblaze.yaml.TrailblazeToolYamlWrapper
import xyz.block.trailblaze.yaml.TrailblazeYaml
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.yaml.VerificationStep
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import xyz.block.trailblaze.logs.client.temp.OtherTrailblazeTool
import java.io.File

/**
 * Manages trail file operations: save, load, find, list.
 *
 * Handles conversion between:
 * - RecordedStep (from MCP session recording)
 * - Trail YAML format (for persistence)
 *
 * @param trailsDirectory Base directory for trail files
 */
class TrailFileManager(
  private val trailsDirectory: String,
  private val trailblazeYaml: TrailblazeYaml = TrailblazeYaml.Default,
) {

  /**
   * Validates that a resolved file path stays within the trails directory.
   * Prevents path traversal via sequences like "../" or symlinks.
   */
  private fun validateWithinTrailsDir(file: File, originalInput: String): File {
    val trailsDirCanonical = File(trailsDirectory).canonicalPath
    val fileCanonical = file.canonicalPath
    if (!fileCanonical.startsWith(trailsDirCanonical + File.separator) &&
      fileCanonical != trailsDirCanonical
    ) {
      throw IllegalArgumentException(
        "Path traversal detected: '$originalInput' resolves outside the trails directory"
      )
    }
    return file
  }

  /**
   * Result of saving a trail.
   */
  data class SaveResult(
    val success: Boolean,
    val filePath: String? = null,
    val error: String? = null,
  )

  /**
   * Result of loading a trail.
   */
  data class LoadResult(
    val success: Boolean,
    val trailItems: List<TrailYamlItem>? = null,
    val config: TrailConfig? = null,
    val promptSteps: List<PromptStep>? = null,
    val filePath: String? = null,
    val error: String? = null,
  )

  /**
   * Saves recorded steps as a trail YAML file.
   *
   * @param name Trail name (used for filename and config id)
   * @param steps Recorded steps from the session
   * @param platform Optional platform for the trail
   * @param metadata Optional additional metadata
   * @return SaveResult indicating success/failure and file path
   */
  fun saveTrail(
    name: String,
    steps: List<RecordedStep>,
    platform: TrailblazeDevicePlatform? = null,
    metadata: Map<String, String>? = null,
  ): SaveResult {
    if (steps.isEmpty()) {
      return SaveResult(success = false, error = "No steps to save")
    }

    try {
      // Validate trail name doesn't contain path traversal components
      val sanitizedName = name.replace(" ", "-").lowercase()
      if (sanitizedName.contains("/") || sanitizedName.contains("\\") ||
        sanitizedName.contains("..") || sanitizedName.startsWith(".")
      ) {
        return SaveResult(
          success = false,
          error = "Invalid trail name: must not contain path separators or '..' sequences",
        )
      }

      // Ensure directory exists
      val dir = File(trailsDirectory)
      if (!dir.exists()) {
        dir.mkdirs()
      }

      // Build trail YAML items
      val trailItems = buildTrailYamlItems(name, steps, platform, metadata)

      // Encode to YAML
      val yamlContent = trailblazeYaml.encodeToString(trailItems)

      // Determine filename based on platform
      val fileName = if (platform != null) {
        "${platform.name.lowercase()}.trail.yaml"
      } else {
        "trail.yaml"
      }

      // Create subdirectory for the trail
      val trailDir = File(dir, name.replace(" ", "-").lowercase())
      if (!trailDir.exists()) {
        trailDir.mkdirs()
      }

      val filePath = File(trailDir, fileName)
      filePath.writeText(yamlContent)

      Console.log("[TrailFileManager] Saved trail to: ${filePath.absolutePath}")
      return SaveResult(success = true, filePath = filePath.absolutePath)
    } catch (e: Exception) {
      Console.log("[TrailFileManager] Error saving trail: ${e.message}")
      return SaveResult(success = false, error = "Failed to save trail: ${e.message}")
    }
  }

  /**
   * Loads a trail from a file path.
   *
   * @param filePath Path to the trail YAML file
   * @return LoadResult with parsed trail data
   */
  fun loadTrail(filePath: String): LoadResult {
    val file = try {
      validateWithinTrailsDir(File(filePath), filePath)
    } catch (e: IllegalArgumentException) {
      return LoadResult(success = false, error = e.message)
    }
    if (!file.exists()) {
      return LoadResult(success = false, error = "Trail file not found: $filePath")
    }

    return try {
      val yamlContent = file.readText()
      val trailItems = trailblazeYaml.decodeTrail(yamlContent)
      val config = trailblazeYaml.extractTrailConfig(trailItems)

      // Extract prompt steps from the trail
      val promptSteps = trailItems
        .filterIsInstance<TrailYamlItem.PromptsTrailItem>()
        .flatMap { it.promptSteps }

      LoadResult(
        success = true,
        trailItems = trailItems,
        config = config,
        promptSteps = promptSteps,
        filePath = filePath,
      )
    } catch (e: Exception) {
      Console.log("[TrailFileManager] Error loading trail: ${e.message}")
      LoadResult(success = false, error = "Failed to load trail: ${e.message}")
    }
  }

  /**
   * Finds a trail file by name.
   *
   * Searches in order:
   * 1. Exact path: {trailsDirectory}/{name}.trail.yaml
   * 2. Directory with default: {trailsDirectory}/{name}/trail.yaml
   * 3. Recursive search for files containing the name
   *
   * @param name Trail name to search for
   * @return File path if found, null otherwise
   */
  fun findTrailByName(name: String): String? {
    val dir = File(trailsDirectory)
    if (!dir.exists()) return null

    // Validate name doesn't contain path traversal components
    if (name.contains("..") || name.startsWith("/") || name.startsWith("\\")) {
      return null
    }

    // Try exact file path
    val exactFile = File(dir, "$name.trail.yaml")
    if (exactFile.exists()) {
      return try {
        validateWithinTrailsDir(exactFile, name).absolutePath
      } catch (_: IllegalArgumentException) { null }
    }

    // Try as directory with default trail.yaml
    val dirWithDefault = File(dir, "$name/trail.yaml")
    if (dirWithDefault.exists()) {
      return try {
        validateWithinTrailsDir(dirWithDefault, name).absolutePath
      } catch (_: IllegalArgumentException) { null }
    }

    // Try directory with any platform variant
    val trailDir = File(dir, name)
    if (trailDir.exists() && trailDir.isDirectory) {
      trailDir.listFiles()?.firstOrNull { it.name.endsWith(".trail.yaml") }
        ?.let {
          return try {
            validateWithinTrailsDir(it, name).absolutePath
          } catch (_: IllegalArgumentException) { null }
        }
    }

    // Recursive search for files containing the name (walkTopDown is bounded to dir)
    return dir.walkTopDown()
      .filter { file ->
        file.isFile &&
          file.extension == "yaml" &&
          (file.name.contains(name, ignoreCase = true) ||
            file.parentFile?.name?.contains(name, ignoreCase = true) == true)
      }
      .firstOrNull()
      ?.absolutePath
  }

  /**
   * Lists trail files matching an optional filter.
   *
   * @param filter Optional filter string (matches file or directory name)
   * @return List of relative paths to matching trail files
   */
  fun listTrails(filter: String? = null): List<String> {
    val dir = File(trailsDirectory)
    if (!dir.exists()) return emptyList()

    return dir.walkTopDown()
      .filter { it.isFile && it.name.endsWith(".trail.yaml") }
      .filter { file ->
        if (filter == null) return@filter true
        file.name.contains(filter, ignoreCase = true) ||
          file.parentFile?.name?.contains(filter, ignoreCase = true) == true ||
          file.relativeTo(dir).path.contains(filter, ignoreCase = true)
      }
      .map { it.relativeTo(dir).path }
      .sorted()
      .toList()
  }

  /**
   * Gets trail info (config + prompt count) without fully loading.
   * Useful for displaying trail lists with metadata without parsing the entire file.
   *
   * @param filePath Path to the trail file
   * @return Pair of (config, step count) or null if not found
   */
  fun getTrailInfo(filePath: String): Pair<TrailConfig?, Int>? {
    val file = try {
      validateWithinTrailsDir(File(filePath), filePath)
    } catch (_: IllegalArgumentException) {
      return null
    }
    if (!file.exists()) return null

    return try {
      val yamlContent = file.readText()
      val trailItems = trailblazeYaml.decodeTrail(yamlContent)
      val config = trailblazeYaml.extractTrailConfig(trailItems)
      val stepCount = trailItems
        .filterIsInstance<TrailYamlItem.PromptsTrailItem>()
        .sumOf { it.promptSteps.size }
      Pair(config, stepCount)
    } catch (_: Exception) {
      null
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // Private helpers
  // ─────────────────────────────────────────────────────────────────────────────

  /**
   * Converts recorded steps to trail YAML format.
   */
  private fun buildTrailYamlItems(
    name: String,
    steps: List<RecordedStep>,
    platform: TrailblazeDevicePlatform?,
    metadata: Map<String, String>?,
  ): List<TrailYamlItem> {
    val items = mutableListOf<TrailYamlItem>()

    // Build metadata including platform if provided
    val fullMetadata = buildMap {
      metadata?.let { putAll(it) }
      platform?.let { put("platform", it.name) }
    }.ifEmpty { null }

    // Add config item
    val config = TrailConfig(
      id = name.replace(" ", "-").lowercase(),
      title = name,
      source = TrailSource(type = TrailSourceType.HANDWRITTEN),
      metadata = fullMetadata,
    )
    items.add(TrailYamlItem.ConfigTrailItem(config))

    // Convert recorded steps to prompt steps
    val promptSteps = steps.map { step ->
      convertRecordedStepToPromptStep(step)
    }

    // Add prompts item
    items.add(TrailYamlItem.PromptsTrailItem(promptSteps))

    return items
  }

  /**
   * Converts a single RecordedStep to a PromptStep with optional recording.
   */
  private fun convertRecordedStepToPromptStep(step: RecordedStep): PromptStep {
    // Convert tool calls to TrailblazeToolYamlWrapper
    // Note: We store tool calls as simple wrappers; actual tool instances
    // would need the tool registry to reconstruct
    val toolRecording = if (step.toolCalls.isNotEmpty()) {
      ToolRecording(
        tools = step.toolCalls.map { toolCall ->
          createToolWrapper(toolCall.toolName, toolCall.args)
        },
      )
    } else {
      null
    }

    return when (step.type) {
      RecordedStepType.STEP -> DirectionStep(
        step = step.input,
        recording = toolRecording,
      )
      RecordedStepType.VERIFY -> VerificationStep(
        verify = step.input,
        recording = toolRecording,
      )
      RecordedStepType.ASK -> {
        // ASK steps are stored as direction steps with a prefix
        // since there's no dedicated AskStep type
        DirectionStep(
          step = "[Question] ${step.input}",
          recording = toolRecording,
        )
      }
    }
  }

  /**
   * Creates a TrailblazeToolYamlWrapper from tool call data.
   *
   * Uses [OtherTrailblazeTool] to store raw tool call data as a generic tool representation.
   * This enables deterministic replay via [DeterministicTrailExecutor] which extracts
   * the tool name and args from the wrapper for execution.
   */
  private fun createToolWrapper(
    toolName: String,
    args: Map<String, String>,
  ): TrailblazeToolYamlWrapper {
    val jsonArgs = JsonObject(args.mapValues { (_, v) -> JsonPrimitive(v) })
    return TrailblazeToolYamlWrapper(
      name = toolName,
      trailblazeTool = OtherTrailblazeTool(toolName = toolName, raw = jsonArgs),
    )
  }
}
