package xyz.block.trailblaze.ui.recordings

import xyz.block.trailblaze.logs.model.SessionInfo
import xyz.block.trailblaze.yaml.TrailYamlItem

/**
 * Repository for saving session recordings to disk.
 */
interface RecordedTrailsRepo {
  /**
   * Saves an already-lowered recording to disk with a platform-specific filename. The caller passes
   * the lowered [items] (built from the session logs via `generateRecordedTrailItems`) so the save
   * never round-trips through the v1 trail parser: the legacy path re-encodes them to the v1 list
   * shape, the unified path merges them straight into the classifier slot.
   *
   * @param items The lowered trail items for this device's session
   * @param sessionInfo The session info containing trail configuration
   * @return Result with the absolute path to the saved file on success, or an error message on failure
   */
  fun saveRecording(
    items: List<TrailYamlItem>,
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
}
