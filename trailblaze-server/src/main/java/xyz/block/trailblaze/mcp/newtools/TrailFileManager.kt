package xyz.block.trailblaze.mcp.newtools

import xyz.block.trailblaze.config.project.TrailDiscovery
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.mcp.RecordedStep
import xyz.block.trailblaze.mcp.RecordedStepType
import xyz.block.trailblaze.recordings.UnifiedRecordingWriter
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
import xyz.block.trailblaze.yaml.unified.TrailblazeDeviceDefinition
import xyz.block.trailblaze.yaml.unified.UnifiedTrailAdapter
import xyz.block.trailblaze.yaml.unified.UnifiedTrailStep
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import xyz.block.trailblaze.logs.client.temp.OtherTrailblazeTool
import java.io.File

/**
 * Convert a human trail title to the per-trail directory name used under
 * `trails/`. Spaces become `-`, the result is lowercased, runs of `-` are
 * collapsed to one, and leading/trailing `-` are trimmed.
 *
 *   "CLI review test - example.com" -> "cli-review-test-example.com"
 *
 * Path-traversal characters (`/`, `\`, `..`) are NOT stripped — the call site
 * validates against those after slugging via [validateTrailNameSlug], so the
 * slug stays a 1:1 transform the user can read.
 *
 * May return an empty string when the input is only spaces and/or hyphens
 * (e.g. " - "). Callers MUST run the result through [validateTrailNameSlug]
 * before using it as a directory name — without that check, `File(dir, "")`
 * resolves to `dir` itself and the subsequent write would land in the trails
 * root, potentially clobbering an unrelated file.
 */
internal fun trailNameToDirSlug(name: String): String =
  name.replace(" ", "-").lowercase().replace(Regex("-+"), "-").trim('-')

/**
 * Reject trail-name slugs that aren't usable as a directory name. Returns
 * `null` when the slug is acceptable, or a human-readable error string
 * explaining why it isn't.
 *
 * Two failure modes:
 *  - **Blank** — `trailNameToDirSlug` produced an empty string (the input was
 *    only spaces and/or hyphens). Without this guard, `File(dir, "")`
 *    resolves to `dir` and the trail YAML would be written at the trails
 *    root, silently overwriting any sibling file with the same platform-based
 *    filename.
 *  - **Path traversal** — the slug contains `/`, `\`, `..`, or starts with
 *    `.`. None of these can appear in a slug produced by
 *    `trailNameToDirSlug` from a typed CLI title (spaces are mapped to `-`
 *    and edges are trimmed), but the function also accepts pre-existing slug
 *    inputs from the MCP tool surface, where a malicious caller could pass
 *    `..` directly. The check stays defensive at the boundary regardless.
 */
internal fun validateTrailNameSlug(slug: String): String? = when {
  slug.isBlank() ->
    "Invalid trail name: must contain at least one alphanumeric character " +
      "(the title sanitized to an empty slug — was it only spaces or hyphens?)"
  slug.contains("/") || slug.contains("\\") || slug.contains("..") || slug.startsWith(".") ->
    "Invalid trail name: must not contain path separators or '..' sequences"
  else -> null
}

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
    /** The multi-device configuration these items were lowered for, or null for single-device. */
    val selectedDeviceConfiguration: String? = null,
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
    val trailItems = buildTrailYamlItems(name, steps, platform, metadata)
    return writeRoutedTrail(
      name = name,
      platform = platform,
      trailItemsForMerge = { trailItems },
    )
  }

  /**
   * Saves an already-lowered recording (the log-backed MCP save path builds these via
   * `generateRecordedTrailItems`) through the same routing as [saveTrail].
   *
   * @param name Trail name (used for the directory slug)
   * @param recordedItems The lowered trail items for this one device's session
   * @param platform Optional platform for the classifier / sibling filename
   * @param selectedDeviceConfiguration The multi-device configuration the session bound
   *   ([xyz.block.trailblaze.logs.model.SessionStatus.Started.selectedDeviceConfiguration]), or null
   *   for a single-device session. It keys the recording slot instead of the platform.
   * @param castToDeclare The configuration definition to DECLARE under
   *   [selectedDeviceConfiguration] when the destination declares no device layout of its own —
   *   synthesized from an interactive session's named-device roster so the saved trail carries the
   *   `config.devices:` cast its configuration-keyed legs need to replay. A destination that already
   *   declares any device (a cast, a single-device entry, or a classifier-keyed leg) wins and the
   *   save refuses instead — see [UnifiedRecordingWriter.mergeIntoUnified].
   */
  fun saveTrailItems(
    name: String,
    recordedItems: List<TrailYamlItem>,
    platform: TrailblazeDevicePlatform? = null,
    selectedDeviceConfiguration: String? = null,
    castToDeclare: TrailblazeDeviceDefinition? = null,
  ): SaveResult = writeRoutedTrail(
    name = name,
    platform = platform,
    selectedDeviceConfiguration = selectedDeviceConfiguration,
    castToDeclare = castToDeclare,
    trailItemsForMerge = { recordedItems },
  )

  /**
   * Shared save-back routing for the recorded-step ([saveTrail]) and log-backed ([saveTrailItems])
   * MCP paths. Validates the name slug, then routes through the shared [UnifiedRecordingWriter]:
   * merge [trailItemsForMerge] into the directory's shared unified `trail.yaml` under the
   * platform's classifier slot, or write a per-classifier `<platform>.trail.yaml` sibling —
   * refusing to shadow an existing unified trail. Both destinations hold unified YAML.
   *
   * A session that bound a multi-device configuration keys its slot by that configuration's NAME
   * and always merges: the sibling layout names its file after the device and can't declare a cast,
   * so a configuration routed there would write the classifier-keyed leg the keying exists to
   * prevent.
   */
  private fun writeRoutedTrail(
    name: String,
    platform: TrailblazeDevicePlatform?,
    selectedDeviceConfiguration: String? = null,
    castToDeclare: TrailblazeDeviceDefinition? = null,
    trailItemsForMerge: () -> List<TrailYamlItem>,
  ): SaveResult {
    return try {
      // Validate trail name: empty (blank-after-slug) AND path-traversal cases both share the same
      // guard so a `' - '`-only title can't land at the trails root and clobber a sibling file.
      val sanitizedName = trailNameToDirSlug(name)
      validateTrailNameSlug(sanitizedName)?.let { err ->
        return SaveResult(success = false, error = err)
      }

      // A unified trail keys each device's tools under a classifier slot, so a save with no
      // platform has nothing to key on. Refuse rather than inventing a device-agnostic slot —
      // mirrors loadTrail's "bind a device first" stance for the same reason. A configuration
      // session's name IS the slot key, so it needs no platform (blank counts as absent).
      val configuration = selectedDeviceConfiguration?.takeIf { it.isNotBlank() }
      val classifier = configuration ?: platform?.name?.lowercase().orEmpty()
      if (classifier.isBlank()) {
        return SaveResult(
          success = false,
          error = "Can't save a recording without a device platform: a unified trail keys each " +
            "device's tools under a classifier slot and there is none to key on. Bind a device " +
            "first (e.g. via the `device` tool), then retry.",
        )
      }

      val dir = File(trailsDirectory)
      if (!dir.exists()) dir.mkdirs()
      val trailDir = File(dir, sanitizedName)
      if (!trailDir.exists()) trailDir.mkdirs()

      val fileName = "$classifier.trail.yaml"
      val trailItems = trailItemsForMerge()

      if (configuration != null || UnifiedRecordingWriter.shouldMergeIntoSharedTrail(trailDir, classifier)) {
        return saveTrailAsUnified(trailDir, trailItems, classifier, fileName, configuration, castToDeclare)
      }

      // Per-classifier sibling. Refuse to drop one into (or overwrite a `trail.yaml` in) a
      // directory that already has a shared unified trail — the sibling can't update it, so it
      // would only shadow it at run time.
      if (UnifiedRecordingWriter.unifiedTrailPresent(trailDir)) {
        return SaveResult(
          success = false,
          error = UnifiedRecordingWriter.siblingShadowRefusalMessage(fileName, trailDir),
        )
      }

      // Same render + invariants as the shared-trail route, so a shape refused there (no
      // classifier, multi-tool trailhead, zero steps) can't be planted as a sibling instead.
      val rendered = UnifiedRecordingWriter.renderStandalone(trailItems, classifier)
        .getOrElse { return SaveResult(success = false, error = it.message) }
      val filePath = File(trailDir, fileName)
      filePath.writeText(rendered)

      Console.log("[TrailFileManager] Saved trail to: ${filePath.absolutePath}")
      SaveResult(success = true, filePath = filePath.absolutePath)
    } catch (e: Exception) {
      Console.log("[TrailFileManager] Error saving trail: ${e.message}")
      SaveResult(success = false, error = "Failed to save trail: ${e.message}")
    }
  }

  /**
   * Merge the just-authored trail into the directory's shared unified `trail.yaml` under
   * [classifier]'s slot, preserving every other classifier already on disk. Falls back to a
   * per-classifier sibling only for the shapes the unified format can't hold (a multi-tool
   * trailhead — never produced by the MCP authoring path, handled defensively) and refuses a
   * corrupt existing trail rather than clobbering it.
   */
  private fun saveTrailAsUnified(
    trailDir: File,
    trailItems: List<TrailYamlItem>,
    classifier: String,
    siblingFileName: String,
    selectedDeviceConfiguration: String? = null,
    castToDeclare: TrailblazeDeviceDefinition? = null,
  ): SaveResult = when (
    val outcome = UnifiedRecordingWriter.mergeIntoUnified(
      trailFileOrDir = trailDir,
      recordedItems = trailItems,
      classifier = classifier,
      selectedDeviceConfiguration = selectedDeviceConfiguration,
      castToDeclare = castToDeclare,
    )
    ) {
    is UnifiedRecordingWriter.MergeOutcome.Merged -> {
      Console.log("[TrailFileManager] Merged trail into: ${outcome.target.absolutePath} (classifier `$classifier`)")
      SaveResult(success = true, filePath = outcome.target.absolutePath)
    }

    is UnifiedRecordingWriter.MergeOutcome.NoTarget ->
      SaveResult(
        success = false,
        error = "Recording not saved: no unified trail target resolved for " +
          "${trailDir.absolutePath} (expected $siblingFileName or a shared trail.yaml).",
      )

    is UnifiedRecordingWriter.MergeOutcome.RefusedCorrupt ->
      SaveResult(success = false, error = UnifiedRecordingWriter.corruptRefusalMessage(outcome.target, outcome.reason))

    is UnifiedRecordingWriter.MergeOutcome.SkippedEmpty ->
      SaveResult(success = false, error = UnifiedRecordingWriter.EMPTY_MERGE_MESSAGE)

    is UnifiedRecordingWriter.MergeOutcome.SteplessIntoExistingTrail ->
      SaveResult(success = false, error = UnifiedRecordingWriter.STEPLESS_INTO_EXISTING_MESSAGE)

    is UnifiedRecordingWriter.MergeOutcome.SkippedMultiDeviceTrail ->
      SaveResult(
        success = false,
        error = UnifiedRecordingWriter.multiDeviceMergeSkippedMessage(outcome.target, outcome.configurationNames),
      )

    is UnifiedRecordingWriter.MergeOutcome.ConfigurationNotDeclared ->
      SaveResult(
        success = false,
        error = UnifiedRecordingWriter.configurationNotDeclaredMessage(
          outcome.target,
          outcome.configurationName,
          outcome.declaredConfigurationNames,
        ),
      )

    is UnifiedRecordingWriter.MergeOutcome.SynthesizedCastWouldBeShadowed ->
      SaveResult(
        success = false,
        error = UnifiedRecordingWriter.synthesizedCastShadowedMessage(outcome.target, outcome.siblingFileNames),
      )

    // This path saves a whole recording, so it passes no step window and neither partial-recording
    // refusal can fire. Reported rather than ignored so a future caller that does pass one gets the
    // real message instead of a silent success.
    is UnifiedRecordingWriter.MergeOutcome.StepWindowOutOfRange ->
      SaveResult(
        success = false,
        error = UnifiedRecordingWriter.stepWindowOutOfRangeMessage(
          outcome.target,
          outcome.window,
          outcome.existingStepCount,
        ),
      )

    is UnifiedRecordingWriter.MergeOutcome.StepWindowMismatch ->
      SaveResult(
        success = false,
        error = UnifiedRecordingWriter.stepWindowMismatchMessage(
          outcome.target,
          outcome.window,
          outcome.expectedStepCount,
          outcome.recordedStepCount,
        ),
      )

    is UnifiedRecordingWriter.MergeOutcome.TrailChangedUnderRun ->
      SaveResult(
        success = false,
        error = UnifiedRecordingWriter.trailChangedUnderRunMessage(outcome.target, outcome.changed),
      )
  }

  /**
   * Loads a trail from a file path.
   *
   * @param filePath Path to the trail YAML file
   * @param deviceClassifiers The bound device's classifiers, used to lower a unified trail's
   *   per-classifier recordings (v1 trails ignore the list). Callers loading for EXECUTION must
   *   pass the session device's classifiers — see [deviceClassifiersFor] for the semantics
   *   (platform-only limitation, and why empty means "refuse a unified-with-recordings trail").
   * @param requestedDeviceConfiguration Which `config.devices:` CONFIGURATION entry to lower. Null
   *   derives it — see [selectDeviceConfiguration]. Configuration names are invisible to classifier
   *   lineage, so a two-device trail loaded without one lowers every configuration-keyed step with
   *   NO recording and reads as unrecorded.
   * @return LoadResult with parsed trail data
   */
  fun loadTrail(
    filePath: String,
    deviceClassifiers: List<TrailblazeDeviceClassifier> = emptyList(),
    requestedDeviceConfiguration: String? = null,
  ): LoadResult {
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
      val selection = selectDeviceConfiguration(yamlContent, requestedDeviceConfiguration, trailblazeYaml)
      selection.errorMessage()?.let { message ->
        return LoadResult(success = false, error = message)
      }
      val selectedDeviceConfiguration = (selection as DeviceConfigurationSelection.Selected).name
      val trailItems = trailblazeYaml.decodeTrail(
        yamlContent,
        deviceClassifiers = deviceClassifiers,
        selectedDeviceConfiguration = selectedDeviceConfiguration,
      )
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
        selectedDeviceConfiguration = selectedDeviceConfiguration,
      )
    } catch (e: IllegalStateException) {
      // decodeTrail's unified-with-recordings guard: the trail needs device classifiers to lower
      // its per-classifier recordings, and this call had none. Surface an actionable message on
      // the reachable path (previously the raw guard text leaked through the generic catch below).
      Console.log("[TrailFileManager] Unified trail loaded without device classifiers: ${e.message}")
      LoadResult(
        success = false,
        error = "This is a unified (per-classifier) trail with recordings; loading it requires " +
          "the bound device's classifiers. Bind a device first (e.g. via the `device` tool), " +
          "then retry. Underlying error: ${e.message}",
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
    trailblazeYaml.decodeUnifiedTrail(file.readText()).config.title
  } catch (_: Exception) {
    null
  }

  /** [readTrailTitle] by path — the edit surface reports the title without loading the steps. */
  fun readTrailTitle(filePath: String): String? =
    runCatching { readTrailTitle(validateWithinTrailsDir(File(filePath), filePath)) }.getOrNull()

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
   * Loads a trail and returns its normalized flat list of editable steps, or null when the trail
   * can't be loaded for editing (including a unified trail with recordings — see [loadTrail]).
   *
   * The trail's `config:` and `trailhead:` aren't exposed: [saveEditedSteps] carries them over
   * from the file on disk, so an edit can't move, flatten, or drop them.
   */
  fun getEditableSteps(filePath: String): List<EditableStep>? {
    val loadResult = loadTrail(filePath)
    if (!loadResult.success || loadResult.trailItems == null) return null

    return normalizeToEditableSteps(loadResult.trailItems)
  }

  /**
   * Writes an edited trail back to disk as a unified document.
   *
   * The file's own `config:` and `trailhead:` are carried over verbatim from disk rather than
   * rebuilt: the edit API exposes neither, and round-tripping them through the v1 `TrailConfig`
   * would drop unified-only shape (per-classifier `devices:`/`skip:` collapse to scalars). Only
   * the step list is rewritten.
   *
   * Every per-step field the edit API doesn't expose (`recordable:`, `maxRetries:`, `recordings:`)
   * is carried over by `copy()`ing the on-disk step with the same NL text, so an unrelated
   * insert/delete/reorder can't silently reset them and a field added later is preserved by
   * default. Rewriting a step's NL text is treated as authoring a new step and drops them.
   *
   * Let a decode failure propagate to the catch below — overwriting a file we couldn't parse would
   * silently drop its config and trailhead.
   */
  fun saveEditedSteps(
    filePath: String,
    steps: List<EditableStep>,
  ): EditResult {
    return try {
      val file = validateWithinTrailsDir(File(filePath), filePath)
      val existing = trailblazeYaml.decodeUnifiedTrail(file.readText())
      // An edited step carries no classifier, so a recording on one has no slot to land in.
      // getEditableSteps can only load recording-less trails (decodeTrail's no-classifier guard
      // rejects the rest), so this is unreachable — fail loud rather than drop the tools.
      require(steps.none { it.recording != null }) {
        "Editing a step that carries a recording is not supported: a unified trail keys tools " +
          "per device classifier and the edit API has none."
      }
      // NL text → the on-disk steps carrying it, oldest-first, so duplicated prose still pairs up
      // one-for-one instead of every copy inheriting the first one's fields.
      val carryOver = existing.trail
        .groupByTo(mutableMapOf(), { it.step }, { it })
        .mapValues { (_, v) -> ArrayDeque(v) }
      val yamlContent = trailblazeYaml.encodeUnifiedTrailToString(
        existing.copy(
          trail = steps.map { edited ->
            // copy() the on-disk step rather than rebuilding from an allowlist: the edit API
            // exposes only prose and kind, so anything else it doesn't know about — `recordings:`
            // today, any field added later — is carried through instead of silently reset.
            carryOver[edited.prompt]?.removeFirstOrNull()
              ?.copy(step = edited.prompt, verify = edited.type == "verify")
              ?: UnifiedTrailStep(step = edited.prompt, verify = edited.type == "verify")
          },
        ),
      )
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
        is TrailYamlItem.TrailheadTrailItem -> {
          // The trailhead (step 0) is NOT an editable step — surfacing it here would let an edit
          // move/delete it or flatten it into a prompt on save. It's preserved verbatim across
          // save by reconstructTrailItems (which re-reads it from disk). Skip it here.
        }
        is TrailYamlItem.ConfigTrailItem -> {
          // Config handled separately, skip here
        }
      }
      i++
    }
    return steps
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
      id = trailNameToDirSlug(name),
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
