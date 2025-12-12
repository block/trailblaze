package xyz.block.trailblaze.recordings

import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier

object TrailRecordings {

  /**
   * Standard filename for the prompts file - the source of truth for natural language test steps.
   *
   * Trail directory structure (with subdirectories like "generated"):
   * ```
   * trails/
   * └── generated/                                    ← Subdirectory (configurable)
   *     └── testrail/suite_123/section_456/case_789/  ← Trail ID (directory = trail identity)
   *         ├── trail.yaml                    ← Source of truth: NL steps (no recordings)
   *         ├── ios-iphone.trail.yaml                 ← Recording for iOS iPhone
   *         ├── ios-ipad.trail.yaml                   ← Recording for iOS iPad
   *         └── android.trail.yaml                    ← Recording for Android
   * ```
   *
   * Trail directory structure (without subdirectories - open source default):
   * ```
   * trails/
   * └── testrail/suite_123/section_456/case_789/      ← Trail ID (directory = trail identity)
   *     ├── trail.yaml
   *     ├── ios-iphone.trail.yaml
   *     └── android.trail.yaml
   * ```
   */
  const val TRAIL_DOT_YAML = "trail.yaml"

  const val DOT_TRAIL_DOT_YAML_FILE_SUFFIX = ".${TRAIL_DOT_YAML}"

  /**
   * Based on the classifiers, determine possible names, in order of most specific to least specific
   */
  fun computePossibleFileNamesForDeviceClassifiers(
    deviceClassifiers: List<TrailblazeDeviceClassifier>,
  ): List<String> {
    // Build list of candidate filenames in priority order
    // e.g., for ["pixel", "android", "5g"]: ["pixel-android-5g.trail.yaml", "pixel-android.trail.yaml", "pixel.trail.yaml", "trail.yaml"]
    return buildList {
      // Add device-specific combinations from most to least specific
      for (count in deviceClassifiers.size downTo 1) {
        add(deviceClassifiers.take(count).joinToString("-") { it.classifier } + DOT_TRAIL_DOT_YAML_FILE_SUFFIX)
      }
      // Add default fallback
      add(TRAIL_DOT_YAML)
    }
  }

  fun findBestTrailForClassifiers(
    trailPaths: List<String>,
    deviceClassifiers: List<TrailblazeDeviceClassifier>,
  ): String? {
    // Build list of candidate filenames in priority order
    // e.g., for ["pixel", "android", "5g"]: ["pixel-android-5g.trail.yaml", "pixel-android.trail.yaml", "pixel.trail.yaml", "trail.yaml"]
    val candidateFileNames = computePossibleFileNamesForDeviceClassifiers(deviceClassifiers)

    // Find the first trail file that matches any candidate
    val matchedFile = candidateFileNames.firstNotNullOfOrNull { candidateFileName ->
      trailPaths.find { trail -> trail.endsWith(candidateFileName) }
    }

    println("No matching trail file found matching ($candidateFileNames) for classifiers $deviceClassifiers in $trailPaths")

    return matchedFile
  }

  /**
   * Finds the best trail resource path based on the given path and device classifiers.
   *
   * @param path The input path - can be either a specific .trail.yaml file or a directory containing trail files
   * @param deviceClassifiers List of device classifiers to determine priority of trail files
   * @param doesResourceExist Function to check if a resource exists at the given path
   * @return The best matching trail resource path, or null if none found
   */
  fun findBestTrailResourcePath(
    path: String,
    deviceClassifiers: List<TrailblazeDeviceClassifier>,
    doesResourceExist: (String) -> Boolean,
  ): String? = if (path.endsWith(TRAIL_DOT_YAML)) {
    // Path is already a specific trail file
    path
  } else {
    // Path is a directory - find the best matching trail file
    val possibleFileNames = computePossibleFileNamesForDeviceClassifiers(deviceClassifiers)
    val possibleResourcePaths = possibleFileNames.map { possibleFileName -> "$path/$possibleFileName" }
    possibleResourcePaths.firstOrNull { resourcePath ->
      doesResourceExist(resourcePath)
    }
  }
}
