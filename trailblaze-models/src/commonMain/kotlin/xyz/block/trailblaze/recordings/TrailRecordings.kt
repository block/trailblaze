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
   *     └── testrail/suite_123/section_456/case_789/  <- Trail ID (directory = trail identity)
   *         ├── blaze.yaml (or trailblaze.yaml)         <- Source of truth: NL steps (no recordings)
   *         ├── ios-iphone.trail.yaml                 <- Recording for iOS iPhone
   *         ├── ios-ipad.trail.yaml                   <- Recording for iOS iPad
   *         └── android.trail.yaml                    <- Recording for Android
   * ```
   *
   * Trail directory structure (without subdirectories - open source default):
   * ```
   * trails/
   * └── testrail/suite_123/section_456/case_789/      <- Trail ID (directory = trail identity)
   *     ├── blaze.yaml (or trailblaze.yaml)
   *     ├── ios-iphone.trail.yaml
   *     └── android.trail.yaml
   * ```
   */
  const val TRAILBLAZE_DOT_YAML = "trailblaze.yaml"
  const val BLAZE_DOT_YAML = "blaze.yaml"
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
   * (`blaze.yaml` / `trailblaze.yaml`) or a platform-specific recording (`*.trail.yaml`).
   */
  fun isTrailFile(fileName: String): Boolean =
    isNlDefinitionFile(fileName) || fileName.endsWith(DOT_TRAIL_DOT_YAML_FILE_SUFFIX)

  /**
   * Returns `true` if [fileName] is a platform-specific recording file
   * (ends with `.trail.yaml` but is **not** the NL definition file).
   */
  fun isRecordingFile(fileName: String): Boolean =
    fileName.endsWith(DOT_TRAIL_DOT_YAML_FILE_SUFFIX) && !isNlDefinitionFile(fileName)

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
