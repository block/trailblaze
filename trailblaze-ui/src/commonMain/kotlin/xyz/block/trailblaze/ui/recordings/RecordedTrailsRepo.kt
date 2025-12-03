package xyz.block.trailblaze.ui.recordings

import xyz.block.trailblaze.logs.model.SessionInfo

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
}
