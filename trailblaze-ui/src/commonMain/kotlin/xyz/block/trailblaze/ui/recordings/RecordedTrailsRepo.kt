package xyz.block.trailblaze.ui.recordings

import kotlinx.coroutines.flow.Flow
import xyz.block.trailblaze.logs.model.SessionInfo

/**
 * Configuration for a trails directory.
 *
 * @param path Absolute path to the directory
 * @param label Display label for the directory (e.g., "Handwritten", "Generated")
 * @param priority Priority for lookup order (lower number = higher priority, 1 is highest)
 */
data class TrailsDirectory(
  val path: String,
  val label: String,
  val priority: Int,
)

/**
 * Repository for saving session recordings to disk.
 */
interface RecordedTrailsRepo {
  /**
   * Saves a recording YAML to disk with platform-specific filename.
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
  ): Result<String>

  /**
   * Saves a prompts YAML to disk as the source of truth file (trail.yaml).
   * This file contains the natural language test steps without recordings.
   *
   * @param yaml The YAML content to save
   * @param sessionInfo The session info containing trail configuration (uses trailConfig.id for path)
   * @return Result with the absolute path to the saved file on success, or an error message on failure
   */
  fun savePrompts(
    yaml: String,
    sessionInfo: SessionInfo,
  ): Result<String>

  /**
   * Gets configured trails directory
   */
  fun getTrailsDirectory(): String

  /**
   * Gets a list of existing recording files for the given session.
   * Searches for files matching the pattern based on trailPath or id.
   *
   * @param sessionInfo The session to check for existing recordings
   * @return List of existing trails with path information, or empty list if none found
   */
  fun getExistingTrails(sessionInfo: SessionInfo): List<ExistingTrail>
  
  /**
   * Gets the directory that should be watched for a given session's recordings.
   *
   * @param sessionInfo The session to get the watch directory for
   * @return The directory path to watch, or null if the directory doesn't exist
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
