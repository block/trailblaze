package xyz.block.trailblaze.ui.recordings

/**
 * Represents a trail file change event.
 *
 * @param changeType The type of change (CREATE, DELETE, MODIFY)
 * @param filePath The absolute path to the changed file
 */
data class TrailFileChangeEvent(
  val changeType: TrailFileChangeType,
  val filePath: String
)
