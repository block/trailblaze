package xyz.block.trailblaze.ui.recordings

import xyz.block.trailblaze.recordings.TrailRecordings

/**
 * Represents an existing trail file with path information.
 *
 * @param absolutePath The absolute path to the trail file
 * @param relativePath The path relative to the trails directory
 *                     (e.g., "generated/testrail/suite_123/section_456/case_789/ios-iphone.trail.yaml")
 *                     where "generated" is the subdirectory and "testrail/suite_123/section_456/case_789" is the trail ID
 * @param fileName The name of the file (e.g., "ios-iphone.trail.yaml" or "trail.yaml")
 * @param subdirectory The subdirectory category (e.g., "generated", "handwritten"), or null if at root
 * @param isShadowed Whether this trail is shadowed by another trail with the same filename in a higher-priority directory
 * @param shadowedBy The subdirectory name that shadows this trail, or null if not shadowed
 */
data class ExistingTrail(
  val absolutePath: String,
  val relativePath: String,
  val fileName: String,
  val subdirectory: String? = null,
  val isShadowed: Boolean = false,
  val shadowedBy: String? = null,
) {
  /**
   * Determines if this is a prompts file (source of truth for natural language steps).
   */
  val isDefaultTrailFile: Boolean get() = fileName == TrailRecordings.TRAIL_DOT_YAML

  /**
   * Extracts the platform from the filename (e.g., "ios" from "ios-iphone.trail.yaml").
   * Returns null for trail.yaml or unrecognized formats.
   */
  val platform: String?
    get() {
      if (isDefaultTrailFile) return null
      val nameWithoutExtension = fileName.removeSuffix(".trail.yaml")
      return nameWithoutExtension.split("-").firstOrNull()?.lowercase()
    }

  /**
   * Extracts the classifier from the filename (e.g., "iphone" from "ios-iphone.trail.yaml").
   * Returns null if no classifier or for trail.yaml.
   */
  val classifier: String?
    get() {
      if (isDefaultTrailFile) return null
      val nameWithoutExtension = fileName.removeSuffix(".trail.yaml")
      val parts = nameWithoutExtension.split("-")
      return if (parts.size > 1) parts.drop(1).joinToString("-") else null
    }
}