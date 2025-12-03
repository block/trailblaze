package xyz.block.trailblaze.ui.recordings

import kotlinx.coroutines.flow.Flow
import xyz.block.trailblaze.logs.model.SessionInfo

/**
 * Types of file system changes for trail files.
 */
enum class TrailFileChangeType {
  CREATE,
  DELETE,
  MODIFY
}

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

/**
 * Repository for saving session recordings to disk.
 */
interface RecordedTrailsRepo {
  /**
   * Saves a recording YAML to disk.
   *
   * @param yaml The YAML content to save
   * @param sessionInfo The session info containing trail configuration
   * @param includePlatform Whether to include platform in the filename
   * @param numClassifiers Number of classifiers to include in the filename (0 = none, -1 = all)
   * @return Result with the absolute path to the saved file on success, or an error message on failure
   */
  fun saveRecording(
    yaml: String,
    sessionInfo: SessionInfo,
    includePlatform: Boolean = true,
    numClassifiers: Int = -1
  ): Result<String>

  /**
   * Gets the default recordings directory path.
   */
  fun getTrailsDirectory(): String

  /**
   * Gets a list of existing recording files for the given session.
   * Searches for files matching the pattern based on trailPath or id.
   *
   * @param sessionInfo The session to check for existing recordings
   * @return List of absolute file paths for existing recordings, or empty list if none found
   */
  fun getExistingTrails(sessionInfo: SessionInfo): List<String>

  /**
   * Gets the specific directory that should be watched for a given session's recordings.
   * This is the most specific directory that contains the recordings for this session.
   *
   * @param sessionInfo The session to get the watch directory for
   * @return The directory path, or null if no specific directory can be determined
   */
  fun getWatchDirectoryForSession(sessionInfo: SessionInfo): String?

  /**
   * Watches a directory for trail file changes and returns a Flow of change events.
   * Platform-specific implementations may use different mechanisms (file watchers, polling, etc.)
   *
   * The returned Flow will emit events for .trail.yaml files in the watched directory.
   * The Flow is cold - it will start watching when collected and stop when the collection is cancelled.
   *
   * @param directoryPath The absolute path to the directory to watch
   * @return A Flow of file change events, or null if watching is not supported on this platform
   */
  fun watchDirectory(directoryPath: String): Flow<TrailFileChangeEvent>?
}
