package xyz.block.trailblaze.ui.tabs.sessions

/**
 * Represents the result of a session import operation.
 */
sealed class SessionImportResult {

  /**
   * Import succeeded and extracted the session.
   */
  data class Success(
    val sessionId: String,
    val fileCount: Int
  ) : SessionImportResult()

  /**
   * Import failed with an error.
   */
  data class Error(
    val title: String,
    val message: String
  ) : SessionImportResult()
}
