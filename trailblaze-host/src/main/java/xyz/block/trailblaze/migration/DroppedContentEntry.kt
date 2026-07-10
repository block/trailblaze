package xyz.block.trailblaze.migration

/**
 * One input key that a migration decode silently drops. Produced by [TrailRoundTripDropDetector] and
 * surfaced in [UnifiedTrailMigrator.Report.droppedContent] plus the migrated file's leading comments.
 * Top-level (rather than nested in either producer or consumer) so the detector stays reusable
 * without depending on the migrator.
 */
data class DroppedContentEntry(
  /** Input file the dropped key was found in (e.g. `android-phone.trail.yaml`). */
  val file: String,
  /** Human-readable YAML path to the dropped key (e.g. `[1].prompts[0]...nodeSelector.rightOf.textRegex`). */
  val path: String,
  /** The dropped key's name (e.g. `below`, `textRegex`). */
  val key: String,
  /** 1-based source line of the dropped key in [file]. */
  val line: Int,
)
