package xyz.block.trailblaze.ui.recordings

import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.recordings.TrailRecordings

/**
 * Represents an existing trail file with path information.
 *
 * @param absolutePath The absolute path to the trail file
 * @param relativePath The path relative to the trails directory
 *                     (e.g., "regression/suite_123/section_456/case_789/ios-iphone.trail.yaml")
 * @param fileName The name of the file (e.g., "ios-iphone.trail.yaml" or "trail.yaml")
 * @param isUnifiedContent True when the file's *content* is unified-format (a `config:`/`trail:`
 *   mapping), detected by whoever constructed this (they hold the file; `ExistingTrail` doesn't).
 *   Defaults false — a caller without file access falls back to the filename check ([isUnifiedTrailFile]).
 */
data class ExistingTrail(
  val absolutePath: String,
  val relativePath: String,
  val fileName: String,
  val isUnifiedContent: Boolean = false,
) {
  /**
   * Determines if this is a prompts file (source of truth for natural language steps).
   */
  val isDefaultTrailFile: Boolean get() = TrailRecordings.isNlDefinitionFile(fileName)

  /**
   * Filename-level check for the canonical bare unified file (`trail.yaml`). A unified trail
   * authored under a different name is NOT recognized here — use [isUnified], which also honors the
   * content signal.
   */
  val isUnifiedTrailFile: Boolean get() = TrailRecordings.isUnifiedTrailFile(fileName)

  /**
   * True when this is a unified single-file trail — either the canonical bare `trail.yaml` name or
   * any file whose content is a `config:`/`trail:` mapping ([isUnifiedContent]). A unified trail's
   * device coverage lives in its content (recordings + `config.devices`), not its filename, so the
   * filename-derived [platform]/[classifiers] below don't apply to it.
   */
  val isUnified: Boolean get() = isUnifiedTrailFile || isUnifiedContent

  /**
   * Extracts the platform from the filename (e.g., "ios" from "ios-iphone.trail.yaml").
   * The platform is the first classifier. Returns null for trailblaze.yaml, a unified trail,
   * or unrecognized formats.
   */
  val platform: TrailblazeDeviceClassifier?
    get() {
      if (isDefaultTrailFile || isUnified) return null
      val nameWithoutExtension = fileName.removeSuffix(".trail.yaml")
      return nameWithoutExtension.split("-").firstOrNull()?.lowercase()?.let {
        TrailblazeDeviceClassifier(it)
      }
    }

  /**
   * Extracts the classifiers from the filename (e.g., ["iphone", "portrait"] from
   * "ios-iphone-portrait.trail.yaml"). Returns an empty list for trailblaze.yaml or a unified trail.
   */
  val classifiers: List<TrailblazeDeviceClassifier>
    get() {
      if (isDefaultTrailFile || isUnified) return emptyList()
      val nameWithoutExtension = fileName.removeSuffix(".trail.yaml")
      val parts = nameWithoutExtension.split("-")
      return if (parts.size > 1) {
        parts.drop(1).map { TrailblazeDeviceClassifier(it) }
      } else {
        emptyList()
      }
    }
}