package xyz.block.trailblaze.ui.tabs.session

import kotlinx.serialization.Serializable

/** Metadata about a captured video for a session, used to sync video playback with the timeline. */
data class VideoMetadata(
  /** URL or file path to the video file or sprite sheet. */
  val url: String,
  /** Absolute file system path to the video file or sprite sheet (for frame extraction). */
  val filePath: String,
  /** Epoch millis when video recording started. */
  val startTimestampMs: Long,
  /** Epoch millis when video recording ended, or null if still recording. */
  val endTimestampMs: Long?,
  /** Non-null when the artifact is a sprite sheet instead of a video file. */
  val spriteInfo: SpriteSheetInfo? = null,
  /** Absolute file system path to the original video MP4 (for external playback). */
  val videoFilePath: String? = null,
)

/**
 * Describes a sprite sheet image containing video frames arranged in a grid.
 *
 * Frames are laid out left-to-right, top-to-bottom. Frame N is at
 * column `N / rows`, row `N % rows`. For legacy single-column sheets,
 * [columns] = 1 and [rows] = [frameCount].
 */
data class SpriteSheetInfo(
  /** Frames per second encoded in the sprite sheet. */
  val fps: Int,
  /** Total number of logical frames (including duplicates). */
  val frameCount: Int,
  /** Height in pixels of each individual frame. */
  val frameHeight: Int,
  /** Number of columns in the grid (1 for legacy vertical-only sheets). */
  val columns: Int = 1,
  /** Number of rows per column in the grid. For deduped sheets, based on [uniqueFrameCount]. */
  val rows: Int = frameCount,
  /** Number of unique (physical) frames in the sprite grid, or null for legacy sheets. */
  val uniqueFrameCount: Int? = null,
  /**
   * Maps logical frame index to physical sprite position. When non-null, frame N should be
   * looked up at physical index `frameMap[N]` in the sprite grid. Null for legacy sheets
   * where logical index == physical index.
   */
  val frameMap: IntArray? = null,
)

/**
 * Serializable model matching the capture_metadata.json format written by CaptureSession. Duplicated
 * from trailblaze-capture (JVM-only) so commonMain code can parse it.
 */
@Serializable
internal data class CaptureMetadataModel(
  val artifacts: List<ArtifactEntry>,
) {
  @Serializable
  data class ArtifactEntry(
    val filename: String,
    val type: String,
    val startTimestampMs: Long,
    val endTimestampMs: Long? = null,
  )
}
