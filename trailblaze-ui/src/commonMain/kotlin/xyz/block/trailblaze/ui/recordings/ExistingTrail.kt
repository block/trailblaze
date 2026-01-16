package xyz.block.trailblaze.ui.recordings

import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.recordings.TrailRecordings

/**
 * Represents an existing trail file with path information.
 *
 * @param absolutePath The absolute path to the trail file
 * @param relativePath The path relative to the trails directory
 *                     (e.g., "testrail/suite_123/section_456/case_789/ios-iphone.trail.yaml")
 * @param fileName The name of the file (e.g., "ios-iphone.trail.yaml" or "trail.yaml")
 */
data class ExistingTrail(
  val absolutePath: String,
  val relativePath: String,
  val fileName: String,
) {
  /**
   * Determines if this is a prompts file (source of truth for natural language steps).
   */
  val isDefaultTrailFile: Boolean get() = fileName == TrailRecordings.TRAIL_DOT_YAML

  /**
   * Extracts the platform from the filename (e.g., "ios" from "ios-iphone.trail.yaml").
   * The platform is the first classifier. Returns null for trail.yaml or unrecognized formats.
   */
  val platform: TrailblazeDeviceClassifier?
    get() {
      if (isDefaultTrailFile) return null
      val nameWithoutExtension = fileName.removeSuffix(".trail.yaml")
      return nameWithoutExtension.split("-").firstOrNull()?.lowercase()?.let {
        TrailblazeDeviceClassifier(it)
      }
    }

  /**
   * Extracts the classifiers from the filename (e.g., ["iphone", "portrait"] from
   * "ios-iphone-portrait.trail.yaml"). Returns an empty list for trail.yaml.
   */
  val classifiers: List<TrailblazeDeviceClassifier>
    get() {
      if (isDefaultTrailFile) return emptyList()
      val nameWithoutExtension = fileName.removeSuffix(".trail.yaml")
      val parts = nameWithoutExtension.split("-")
      return if (parts.size > 1) {
        parts.drop(1).map { TrailblazeDeviceClassifier(it) }
      } else {
        emptyList()
      }
    }
}