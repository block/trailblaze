package xyz.block.trailblaze.recordings

import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.util.Console

object TrailRecordings {

  /**
   * Standard filename for the NL-only test definition file (the source of truth for natural
   * language test steps, without platform-specific recordings).
   *
   * Trail directory structure (with subdirectories like "generated"):
   * ```
   * trails/
   * └── generated/                                    <- Subdirectory (configurable)
   *     └── regression/suite_123/section_456/case_789/  <- Trail ID (directory = trail identity)
   *         ├── blaze.yaml (or trailblaze.yaml)         <- Source of truth: NL steps (no recordings)
   *         ├── ios-iphone.trail.yaml                 <- Recording for iOS iPhone
   *         ├── ios-ipad.trail.yaml                   <- Recording for iOS iPad
   *         └── android.trail.yaml                    <- Recording for Android
   * ```
   *
   * Trail directory structure (without subdirectories - open source default):
   * ```
   * trails/
   * └── regression/suite_123/section_456/case_789/      <- Trail ID (directory = trail identity)
   *     ├── blaze.yaml (or trailblaze.yaml)
   *     ├── ios-iphone.trail.yaml
   *     └── android.trail.yaml
   * ```
   */
  const val TRAILBLAZE_DOT_YAML = "trailblaze.yaml"
  const val BLAZE_DOT_YAML = "blaze.yaml"

  /**
   * Filename for the unified-per-test trail YAML file. The unified format
   * collapses the per-platform `<classifier>.trail.yaml` siblings plus the
   * `blaze.yaml` NL definition into a single document keyed by device
   * classifier.
   *
   * Resolution priority within a trail directory:
   *   1. Most-specific classifier match (`ios-iphone.trail.yaml`, then
   *      `ios.trail.yaml`).
   *   2. [UNIFIED_TRAIL_FILENAME] — the unified file, classifier-agnostic at
   *      the filename level (lowering happens at parse time).
   *   3. NL definition fallback ([BLAZE_DOT_YAML] / [TRAILBLAZE_DOT_YAML]).
   *
   * The unified file sits between the platform-specific legacy recordings
   * and the legacy NL definitions so directories that still contain
   * per-platform files keep running unchanged — bulk-migration deletes
   * those legacy files when the unified format is intended to win.
   */
  const val UNIFIED_TRAIL_FILENAME = "trail.yaml"
  /**
   * The default filename used when creating new NL definition files.
   * Change this single constant to switch the default across all write paths.
   */
  const val DEFAULT_NL_DEFINITION_FILENAME = BLAZE_DOT_YAML

  /**
   * List of NL-definition filenames, in priority order. Used for resolution.
   */
  val NL_DEFINITION_FILENAMES = listOf(DEFAULT_NL_DEFINITION_FILENAME) +
    listOf(TRAILBLAZE_DOT_YAML, BLAZE_DOT_YAML).filter { it != DEFAULT_NL_DEFINITION_FILENAME }

  const val DOT_TRAIL_DOT_YAML_FILE_SUFFIX = ".trail.yaml"

  // ---------------------------------------------------------------------------
  // Utility methods — use these instead of inline .endsWith() / == checks
  // ---------------------------------------------------------------------------

  /**
   * Returns `true` if [fileName] is a natural-language definition file
   * (`blaze.yaml` or `trailblaze.yaml`).
   */
  fun isNlDefinitionFile(fileName: String): Boolean = fileName in NL_DEFINITION_FILENAMES

  /**
   * Returns `true` if [fileName] is any Trailblaze YAML file — either an NL definition
   * (`blaze.yaml` / `trailblaze.yaml`), a unified file (`trail.yaml`), or a
   * platform-specific legacy recording (`*.trail.yaml`).
   */
  fun isTrailFile(fileName: String): Boolean =
    isNlDefinitionFile(fileName) || fileName.endsWith(DOT_TRAIL_DOT_YAML_FILE_SUFFIX)

  /** Returns `true` if [fileName] is the unified per-test trail file. */
  fun isUnifiedTrailFile(fileName: String): Boolean = fileName == UNIFIED_TRAIL_FILENAME

  /**
   * Returns `true` if [fileName] is a platform-specific legacy recording file —
   * ends with `.trail.yaml`, is not the NL definition, and is not the unified
   * file (`trail.yaml` happens to end with `.trail.yaml`).
   */
  fun isRecordingFile(fileName: String): Boolean =
    fileName.endsWith(DOT_TRAIL_DOT_YAML_FILE_SUFFIX) &&
      !isNlDefinitionFile(fileName) &&
      !isUnifiedTrailFile(fileName)

  /**
   * Based on the classifiers, determine possible names, in order of most specific to least specific
   */
  fun computePossibleFileNamesForDeviceClassifiers(
    deviceClassifiers: List<TrailblazeDeviceClassifier>,
  ): List<String> {
    // Build list of candidate filenames in priority order
    // e.g., for ["pixel", "android", "5g"]:
    //   ["pixel-android-5g.trail.yaml", "pixel-android.trail.yaml", "pixel.trail.yaml",
    //    "blaze.yaml", "trailblaze.yaml"]
    return buildList {
      // Add device-specific combinations from most to least specific
      for (count in deviceClassifiers.size downTo 1) {
        add(
          deviceClassifiers.take(count).joinToString("-") { it.classifier } +
            DOT_TRAIL_DOT_YAML_FILE_SUFFIX
        )
      }
      // Unified-format trail file — chosen after the per-classifier legacy
      // recordings (so back-compat dirs that still have those keep working)
      // but BEFORE the NL-definition fallback (so a unified-migrated test
      // wins over an obsolete blaze.yaml that wasn't deleted alongside the
      // recordings).
      add(UNIFIED_TRAIL_FILENAME)
      // Add default fallback
      addAll(NL_DEFINITION_FILENAMES)
    }
  }

  fun findBestTrailForClassifiers(
    trailPaths: List<String>,
    deviceClassifiers: List<TrailblazeDeviceClassifier>,
  ): String? {
    // Build list of candidate filenames in priority order
    val candidateFileNames = computePossibleFileNamesForDeviceClassifiers(deviceClassifiers)

    // Find the first trail file that matches any candidate
    val matchedFile =
      candidateFileNames.firstNotNullOfOrNull { candidateFileName ->
        trailPaths.find { trail -> trail.endsWith(candidateFileName) }
      }

    Console.log("No matching trail file found matching ($candidateFileNames) for classifiers $deviceClassifiers in $trailPaths")

    return matchedFile
  }

  /**
   * Finds the best trail resource path based on the given path and device classifiers.
   *
   * @param path The input path - can be either a specific trail/blaze YAML file or a directory
   *   containing trail files
   * @param deviceClassifiers List of device classifiers to determine priority of trail files
   * @param doesResourceExist Function to check if a resource exists at the given path
   * @return The best matching trail resource path, or null if none found
   */
  fun findBestTrailResourcePath(
    path: String,
    deviceClassifiers: List<TrailblazeDeviceClassifier>,
    doesResourceExist: (String) -> Boolean,
  ): String? =
    if (isTrailFile(path.substringAfterLast("/"))) {
      // Path is already a specific trail/blaze file
      path
    } else {
      // Path is a directory - find the best matching trail file
      val possibleFileNames = computePossibleFileNamesForDeviceClassifiers(deviceClassifiers)
      val possibleResourcePaths =
        possibleFileNames.map { possibleFileName -> "$path/$possibleFileName" }
      possibleResourcePaths.firstOrNull { resourcePath -> doesResourceExist(resourcePath) }
    }
}
