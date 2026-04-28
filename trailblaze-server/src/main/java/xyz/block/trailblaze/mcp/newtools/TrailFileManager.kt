package xyz.block.trailblaze.mcp.newtools

import xyz.block.trailblaze.config.project.TrailDiscovery
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
import xyz.block.trailblaze.recordings.TrailRecordings
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

      // Build trail YAML items (prerequisite validation happens at the MCP tool layer
      // since saveTrail builds its own config without prerequisites)
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

    // Try directory with NL definition file (blaze.yaml / trailblaze.yaml)
    val trailDir = File(dir, name)
    if (trailDir.exists() && trailDir.isDirectory) {
      for (nlFileName in TrailRecordings.NL_DEFINITION_FILENAMES) {
        val nlFile = File(trailDir, nlFileName)
        if (nlFile.exists()) {
          return try {
            validateWithinTrailsDir(nlFile, name).absolutePath
          } catch (_: IllegalArgumentException) { null }
        }
      }

      // Try directory with any platform variant
      trailDir.listFiles()?.firstOrNull { it.name.endsWith(".trail.yaml") }
        ?.let {
          return try {
            validateWithinTrailsDir(it, name).absolutePath
          } catch (_: IllegalArgumentException) { null }
        }
    }

    // Recursive search via TrailDiscovery's streaming API — prunes build/, .gradle/,
    // etc. and short-circuits the walk on first match, so an early-directory hit in
    // a 10k-trail workspace stays cheap.
    //
    // Note: `contains` is byte-wise and does not account for Unicode normalization.
    // On macOS filesystems the filename bytes are NFD ("café.yaml") but a
    // user-supplied `name` may be NFC ("café.yaml"); the two will not match. Rare
    // in practice for trail names, but worth flagging before a future i18n pass.
    return TrailDiscovery.findFirstTrail(dir.toPath()) { path ->
      val file = path.toFile()
      file.name.contains(name, ignoreCase = true) ||
        file.parentFile?.name?.contains(name, ignoreCase = true) == true
    }?.toFile()?.absolutePath
  }

  /**
   * Info about a trail file returned from [listTrails].
   */
  data class TrailInfo(
    val path: String,
    val title: String?,
  )

  /**
   * Result of a paginated [listTrails] call.
   */
  data class TrailListPage(
    val trails: List<TrailInfo>,
    val totalCount: Int,
    val page: Int,
    val pageSize: Int,
    val hasMore: Boolean,
  )

  /**
   * Lists trail files matching an optional filter, with pagination.
   *
   * Performance note: when [filter] is null (the common "show me all" case), title
   * extraction only runs on the returned page slice — we do not open YAML files for trails
   * the caller won't see. When [filter] is non-null the title has to be read so
   * `info.title.contains(filter)` can be evaluated, so the eager shape is used there.
   *
   * @param filter Optional filter string (matches file path, directory name, or trail title)
   * @param page 1-based page number (default 1)
   * @param pageSize Number of results per page (default 20)
   * @return Paginated list of trail info with titles
   */
  fun listTrails(filter: String? = null, page: Int = 1, pageSize: Int = 20): TrailListPage {
    val dir = File(trailsDirectory)
    if (!dir.exists()) return TrailListPage(
      trails = emptyList(), totalCount = 0, page = page, pageSize = pageSize, hasMore = false,
    )

    // TrailDiscovery prunes build/, .gradle/, etc. and surfaces both .trail.yaml and
    // NL-definition files (blaze.yaml / nested trailblaze.yaml).
    val discoveredFiles = TrailDiscovery.discoverTrailFiles(dir.toPath())

    return if (filter == null) {
      paginateWithoutTitleFilter(discoveredFiles, dir, page, pageSize)
    } else {
      paginateWithTitleFilter(discoveredFiles, dir, filter, page, pageSize)
    }
  }

  /**
   * Fast path: paginate by path first, then read YAML titles only for the page slice.
   * A 10k-trail workspace costs ~pageSize YAML reads instead of 10k.
   *
   * Sorts by absolute path so the ordering key matches [TrailDiscovery.discoverTrails]
   * exactly — the two sort keys would agree on every file sharing the same root
   * regardless, but aligning them defends against future callers that might pass an
   * un-prefiltered list.
   */
  private fun paginateWithoutTitleFilter(
    discoveredFiles: List<File>,
    dir: File,
    page: Int,
    pageSize: Int,
  ): TrailListPage {
    val sortedFiles = discoveredFiles.sortedBy { it.absolutePath }
    val bounds = paginationBounds(sortedFiles.size, page, pageSize)
    val pageTrails = sortedFiles.subList(bounds.startIndex, bounds.endIndex).map { file ->
      TrailInfo(path = file.relativeTo(dir).path, title = readTrailTitle(file))
    }
    return bounds.toPage(pageTrails)
  }

  /**
   * Slow path: title-matching requires reading every file's YAML so the filter can run
   * against `info.title`. Kept identical to pre-Phase-3 eager behavior — callers that
   * pass a filter already accept the O(n) read cost in exchange for a full search.
   *
   * Note on ordering: the sort runs **before** the filter so `discoveredFiles` is
   * ordered by absolute path (matching [TrailDiscovery.discoverTrails]'s key) at the
   * time YAML titles are read. Moving the filter earlier would skip reads for
   * path-mismatched trails but would drop title-only matches — preserving the
   * pre-Phase-3 contract is worth the wasted sort on filtered-out entries.
   */
  private fun paginateWithTitleFilter(
    discoveredFiles: List<File>,
    dir: File,
    filter: String,
    page: Int,
    pageSize: Int,
  ): TrailListPage {
    val allTrails = discoveredFiles
      .sortedBy { it.absolutePath }
      .map { file -> TrailInfo(path = file.relativeTo(dir).path, title = readTrailTitle(file)) }
      .filter { info ->
        info.path.contains(filter, ignoreCase = true) ||
          info.title?.contains(filter, ignoreCase = true) == true
      }
    val bounds = paginationBounds(allTrails.size, page, pageSize)
    return bounds.toPage(allTrails.subList(bounds.startIndex, bounds.endIndex))
  }

  /**
   * Pre-computed slice bounds for a page request — shared by both paginate branches so
   * the start/end arithmetic and `hasMore` calculation live in one place.
   */
  private data class PaginationBounds(
    val totalCount: Int,
    val page: Int,
    val pageSize: Int,
    val startIndex: Int,
    val endIndex: Int,
  ) {
    val hasMore: Boolean get() = endIndex < totalCount

    fun toPage(trails: List<TrailInfo>) = TrailListPage(
      trails = trails,
      totalCount = totalCount,
      page = page,
      pageSize = pageSize,
      hasMore = hasMore,
    )
  }

  private fun paginationBounds(totalCount: Int, page: Int, pageSize: Int): PaginationBounds {
    val startIndex = ((page - 1) * pageSize).coerceAtMost(totalCount)
    val endIndex = (startIndex + pageSize).coerceAtMost(totalCount)
    return PaginationBounds(totalCount, page, pageSize, startIndex, endIndex)
  }

  private fun readTrailTitle(file: File): String? = try {
    val trailItems = trailblazeYaml.decodeTrail(file.readText())
    trailblazeYaml.extractTrailConfig(trailItems)?.title
  } catch (_: Exception) {
    null
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

  /**
   * Loads the NL definition config (from blaze.yaml / trailblaze.yaml) for a trail directory.
   * This is useful when a variant file (e.g., android.trail.yaml) is loaded but its config
   * may not contain all fields (like prerequisites) that are defined in the NL definition file.
   *
   * @param variantFilePath Path to any file within the trail directory
   * @return The TrailConfig from the NL definition file, or null if not found
   */
  fun loadNlDefinitionConfig(variantFilePath: String): TrailConfig? {
    val parentDir = File(variantFilePath).parentFile ?: return null
    for (nlFileName in TrailRecordings.NL_DEFINITION_FILENAMES) {
      val nlFile = File(parentDir, nlFileName)
      if (nlFile.exists()) {
        return try {
          val yamlContent = nlFile.readText()
          trailblazeYaml.extractTrailConfig(yamlContent)
        } catch (_: Exception) {
          null
        }
      }
    }
    return null
  }

  /**
   * Returns the effective config for a trail, merging the variant config with the NL definition
   * config. The variant config takes precedence, but NL definition config fills in missing fields
   * (like prerequisites).
   *
   * @param variantFilePath Path to the variant trail file
   * @param variantConfig Config extracted from the variant file (may be null)
   * @return Merged config with prerequisites from NL definition if not set in variant
   */
  fun getEffectiveConfig(variantFilePath: String, variantConfig: TrailConfig?): TrailConfig? {
    val nlConfig = loadNlDefinitionConfig(variantFilePath)
    if (nlConfig == null) return variantConfig
    if (variantConfig == null) return nlConfig

    // Merge: variant config takes precedence, NL config fills in missing fields
    return variantConfig.copy(
      prerequisites = variantConfig.prerequisites ?: nlConfig.prerequisites,
      id = variantConfig.id ?: nlConfig.id,
      title = variantConfig.title ?: nlConfig.title,
      description = variantConfig.description ?: nlConfig.description,
      priority = variantConfig.priority ?: nlConfig.priority,
      context = variantConfig.context ?: nlConfig.context,
      source = variantConfig.source ?: nlConfig.source,
      metadata = if (variantConfig.metadata != null) variantConfig.metadata else nlConfig.metadata,
      app = variantConfig.app ?: nlConfig.app,
      platform = variantConfig.platform ?: nlConfig.platform,
      driver = variantConfig.driver ?: nlConfig.driver,
    )
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // Prerequisite validation
  // ─────────────────────────────────────────────────────────────────────────────

  /**
   * Validates that all prerequisite trail IDs in [config] resolve to existing trail files.
   *
   * @param config The trail config whose prerequisites should be validated
   * @return A list of validation error strings. Empty list means all prerequisites are valid.
   */
  fun validatePrerequisites(config: TrailConfig?): List<String> {
    val prerequisites = config?.prerequisites
    if (prerequisites.isNullOrEmpty()) return emptyList()

    val errors = mutableListOf<String>()
    for (prereqId in prerequisites) {
      if (findTrailByName(prereqId) == null) {
        errors.add("Prerequisite trail '$prereqId' not found")
      }
    }
    return errors
  }

  /**
   * Detects circular dependencies between trails using a full depth-first traversal.
   *
   * Checks whether [targetTrailId] adding [newPrerequisites] would create a cycle,
   * including transitive chains (e.g., A → B → C → A).
   *
   * @param targetTrailId The ID of the trail being updated
   * @param newPrerequisites The prerequisite IDs to set
   * @return A circular dependency error message, or null if no cycle detected
   */
  fun detectCircularDependency(targetTrailId: String, newPrerequisites: List<String>): String? {
    // Direct self-reference
    if (targetTrailId in newPrerequisites) {
      return "Circular dependency: trail '$targetTrailId' cannot be a prerequisite of itself"
    }

    // DFS through the full prerequisite graph looking for a path back to targetTrailId
    val visited = mutableSetOf<String>()
    val stack = ArrayDeque(newPrerequisites)

    while (stack.isNotEmpty()) {
      val prereqId = stack.removeLast()
      if (!visited.add(prereqId)) continue

      val prereqFile = findTrailByName(prereqId) ?: continue
      val prereqConfig = getEffectiveConfig(prereqFile, getTrailInfo(prereqFile)?.first)
      val transitiveDeps = prereqConfig?.prerequisites ?: continue

      for (dep in transitiveDeps) {
        if (dep == targetTrailId) {
          return "Circular dependency: trail '$targetTrailId' is reachable through '$prereqId' → '$dep'"
        }
        if (dep !in visited) {
          stack.addLast(dep)
        }
      }
    }

    return null
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // Prerequisite flattening
  // ─────────────────────────────────────────────────────────────────────────────

  /**
   * Flattens prerequisite trail steps into the given YAML content.
   *
   * When a trail has `prerequisites: [trailA, trailB]` in its config, this method:
   * 1. Loads each prerequisite trail file
   * 2. Extracts their prompt steps
   * 3. Prepends them before the main trail's steps
   * 4. Returns the combined YAML content
   *
   * This is used by the Desktop UI to ensure prerequisite steps are executed as part
   * of the same session when running a trail from the trails browser or YAML editor.
   *
   * If no prerequisites are defined, or if prerequisite resolution fails, the original
   * YAML content is returned unchanged.
   *
   * @param yamlContent The YAML content of the main trail
   * @param variantFilePath Optional file path of the trail variant (used to resolve
   *   NL definition config for prerequisites defined in blaze.yaml)
   * @return Flattened YAML with prerequisite steps prepended, or original content if
   *   no prerequisites exist
   */
  fun flattenPrerequisites(
    yamlContent: String,
    variantFilePath: String? = null,
  ): String {
    return flattenPrerequisitesRecursive(yamlContent, variantFilePath, mutableSetOf())
  }

  private fun flattenPrerequisitesRecursive(
    yamlContent: String,
    variantFilePath: String? = null,
    visited: MutableSet<String>,
  ): String {
    val trailItems = try {
      trailblazeYaml.decodeTrail(yamlContent)
    } catch (e: Exception) {
      Console.log("[TrailFileManager] Failed to parse YAML for prerequisite flattening: ${e.message}")
      return yamlContent
    }

    val config = trailblazeYaml.extractTrailConfig(trailItems)

    // Merge with NL definition config to pick up prerequisites defined in blaze.yaml
    val effectiveConfig = if (variantFilePath != null) {
      getEffectiveConfig(variantFilePath, config)
    } else {
      config
    }

    // Track this trail in visited set to prevent circular dependency loops.
    // We add both the config ID and the directory name (used for prerequisite lookups)
    // so that cycle detection works even when a trail's config ID differs from its directory name.
    val currentId = effectiveConfig?.id
    if (currentId != null) {
      if (currentId in visited) {
        Console.log("[TrailFileManager] Circular dependency detected for '$currentId', skipping")
        return yamlContent
      }
      visited.add(currentId)
    }
    val directoryName = variantFilePath?.let { File(it).parentFile?.name }
    if (directoryName != null && directoryName !in visited) {
      visited.add(directoryName)
    }

    val prerequisites = effectiveConfig?.prerequisites
    if (prerequisites.isNullOrEmpty()) return yamlContent

    Console.log("[TrailFileManager] Flattening ${prerequisites.size} prerequisite(s): ${prerequisites.joinToString(", ")}")

    // Collect all prerequisite prompt steps (in order)
    val prereqPromptSteps = mutableListOf<PromptStep>()
    for (prereqId in prerequisites) {
      if (prereqId in visited) {
        Console.log("[TrailFileManager] Skipping already-visited prerequisite '$prereqId' (circular dependency)")
        continue
      }

      val prereqFile = findTrailByName(prereqId)
      if (prereqFile == null) {
        Console.log("[TrailFileManager] Warning: prerequisite trail '$prereqId' not found, skipping")
        continue
      }

      val prereqLoad = loadTrail(prereqFile)
      if (!prereqLoad.success || prereqLoad.trailItems == null) {
        Console.log("[TrailFileManager] Warning: failed to load prerequisite trail '$prereqId', skipping")
        continue
      }

      // Also recursively flatten the prerequisite's own prerequisites
      val prereqConfig = prereqLoad.config
      val prereqEffConfig = getEffectiveConfig(prereqFile, prereqConfig)
      val nestedPrereqs = prereqEffConfig?.prerequisites
      val prereqItems = if (!nestedPrereqs.isNullOrEmpty()) {
        // Re-encode the prerequisite trail, flatten recursively, then re-decode
        val prereqYaml = trailblazeYaml.encodeToString(prereqLoad.trailItems)
        val flattenedPrereqYaml = flattenPrerequisitesRecursive(prereqYaml, prereqFile, visited)
        try {
          trailblazeYaml.decodeTrail(flattenedPrereqYaml)
        } catch (_: Exception) {
          prereqLoad.trailItems
        }
      } else {
        prereqLoad.trailItems
      }

      val steps = prereqItems
        .filterIsInstance<TrailYamlItem.PromptsTrailItem>()
        .flatMap { it.promptSteps }
      prereqPromptSteps.addAll(steps)
      Console.log("[TrailFileManager] Added ${steps.size} steps from prerequisite '$prereqId'")
    }

    if (prereqPromptSteps.isEmpty()) return yamlContent

    // Reconstruct the trail items with prerequisite steps prepended
    val mainPromptSteps = trailItems
      .filterIsInstance<TrailYamlItem.PromptsTrailItem>()
      .flatMap { it.promptSteps }

    val combinedSteps = prereqPromptSteps + mainPromptSteps

    // Rebuild trail items: config (without prerequisites, since they're now flattened)
    // + combined prompts + any other non-prompt/non-config items
    val newItems = mutableListOf<TrailYamlItem>()

    // Keep the config but clear prerequisites since they're now inline
    val configWithoutPrereqs = effectiveConfig?.copy(prerequisites = null)
    if (configWithoutPrereqs != null) {
      newItems.add(TrailYamlItem.ConfigTrailItem(configWithoutPrereqs))
    }

    // Add the combined prompt steps
    newItems.add(TrailYamlItem.PromptsTrailItem(combinedSteps))

    // Preserve any tool items that aren't part of prompt steps
    trailItems.filterIsInstance<TrailYamlItem.ToolTrailItem>().forEach { newItems.add(it) }

    return try {
      trailblazeYaml.encodeToString(newItems)
    } catch (e: Exception) {
      Console.log("[TrailFileManager] Failed to encode flattened YAML: ${e.message}")
      yamlContent
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // Trail editing
  // ─────────────────────────────────────────────────────────────────────────────

  /**
   * Result of an edit operation on a trail file.
   */
  data class EditResult(
    val success: Boolean,
    val filePath: String? = null,
    val totalSteps: Int = 0,
    val recordedSteps: Int = 0,
    val unrecordedSteps: Int = 0,
    val changes: List<String> = emptyList(),
    val error: String? = null,
  )

  /**
   * Lightweight representation of a trail step for editing and inspection.
   */
  data class EditableStep(
    val prompt: String,
    val type: String, // "step" or "verify"
    val recording: ToolRecording?,
  )

  /**
   * Loads a trail and returns its config and normalized flat list of editable steps.
   *
   * Normalizes both formats:
   * - Embedded recording (PromptStep with recording field) → direct mapping
   * - Separate blocks (PromptsTrailItem + ToolTrailItem) → merges tools into last prompt
   */
  fun getEditableSteps(filePath: String): Pair<TrailConfig?, List<EditableStep>>? {
    val loadResult = loadTrail(filePath)
    if (!loadResult.success || loadResult.trailItems == null) return null

    val steps = normalizeToEditableSteps(loadResult.trailItems)
    return Pair(loadResult.config, steps)
  }

  /**
   * Writes an edited trail back to disk.
   *
   * Always produces the embedded recording format (recordings inside PromptStep).
   */
  fun saveEditedSteps(
    filePath: String,
    config: TrailConfig?,
    steps: List<EditableStep>,
  ): EditResult {
    // Validate prerequisites if present
    val prereqErrors = validatePrerequisites(config)
    if (prereqErrors.isNotEmpty()) {
      return EditResult(
        success = false,
        error = "Invalid prerequisites: ${prereqErrors.joinToString("; ")}",
      )
    }

    return try {
      val file = validateWithinTrailsDir(File(filePath), filePath)
      val items = reconstructTrailItems(config, steps)
      val yamlContent = trailblazeYaml.encodeToString(items)
      file.writeText(yamlContent)

      val recorded = steps.count { it.recording != null }
      EditResult(
        success = true,
        filePath = filePath,
        totalSteps = steps.size,
        recordedSteps = recorded,
        unrecordedSteps = steps.size - recorded,
      )
    } catch (e: Exception) {
      Console.log("[TrailFileManager] Error saving edited trail: ${e.message}")
      EditResult(success = false, error = "Failed to save edited trail: ${e.message}")
    }
  }

  /**
   * Normalizes trail items from any format into a flat list of [EditableStep]s.
   *
   * Handles:
   * - PromptStep with embedded recording → maps directly
   * - PromptsTrailItem followed by ToolTrailItem → attaches tools to last prompt
   * - Standalone ToolTrailItem → skipped (no natural language intent)
   */
  private fun normalizeToEditableSteps(trailItems: List<TrailYamlItem>): List<EditableStep> {
    val steps = mutableListOf<EditableStep>()
    var i = 0
    while (i < trailItems.size) {
      when (val item = trailItems[i]) {
        is TrailYamlItem.PromptsTrailItem -> {
          // Check if any steps already have embedded recordings
          val hasEmbeddedRecordings = item.promptSteps.any { it.recording != null }

          if (hasEmbeddedRecordings) {
            // Embedded format: each PromptStep maps directly
            for (promptStep in item.promptSteps) {
              steps.add(promptStep.toEditableStep())
            }
          } else {
            // Separate blocks format: check if next item is a ToolTrailItem
            val nextItem = trailItems.getOrNull(i + 1)
            if (nextItem is TrailYamlItem.ToolTrailItem) {
              // Attach tools to the last prompt step
              for ((idx, promptStep) in item.promptSteps.withIndex()) {
                val recording = if (idx == item.promptSteps.lastIndex) {
                  ToolRecording(nextItem.tools)
                } else {
                  null
                }
                steps.add(EditableStep(
                  prompt = promptStep.prompt,
                  type = if (promptStep is VerificationStep) "verify" else "step",
                  recording = recording,
                ))
              }
              i++ // Skip the ToolTrailItem we just consumed
            } else {
              // Prompts with no following tools — all AI-driven
              for (promptStep in item.promptSteps) {
                steps.add(promptStep.toEditableStep())
              }
            }
          }
        }
        is TrailYamlItem.ToolTrailItem -> {
          // Standalone tools (not preceded by prompts) — skip or represent as synthetic step
          // These are rare; typically tools follow prompts.
        }
        is TrailYamlItem.ConfigTrailItem -> {
          // Config handled separately, skip here
        }
      }
      i++
    }
    return steps
  }

  /**
   * Reconstructs trail YAML items from config + edited steps.
   *
   * Produces the embedded recording format: a single [PromptsTrailItem]
   * with all steps containing their recordings inline.
   */
  private fun reconstructTrailItems(
    config: TrailConfig?,
    steps: List<EditableStep>,
  ): List<TrailYamlItem> {
    val items = mutableListOf<TrailYamlItem>()

    if (config != null) {
      items.add(TrailYamlItem.ConfigTrailItem(config))
    }

    if (steps.isNotEmpty()) {
      val promptSteps = steps.map { it.toPromptStep() }
      items.add(TrailYamlItem.PromptsTrailItem(promptSteps))
    }

    return items
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // Private helpers
  // ─────────────────────────────────────────────────────────────────────────────

  private fun PromptStep.toEditableStep() = EditableStep(
    prompt = prompt,
    type = if (this is VerificationStep) "verify" else "step",
    recording = recording,
  )

  private fun EditableStep.toPromptStep(): PromptStep = when (type) {
    "verify" -> VerificationStep(verify = prompt, recording = recording)
    else -> DirectionStep(step = prompt, recording = recording)
  }

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

    // Convert recorded steps to prompt steps (ASK steps are excluded — only
    // blaze/verify steps are persisted as trail steps)
    val promptSteps = steps
      .filter { it.type != RecordedStepType.ASK }
      .map { step -> convertRecordedStepToPromptStep(step) }

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
