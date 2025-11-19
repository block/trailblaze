package xyz.block.trailblaze.ui.tabs.sessions

/**
 * Represents the result of a session export operation.
 */
sealed class SessionExportResult {

  /**
   * Export succeeded and created a ZIP file.
   */
  data class SuccessZip(
    val zipFile: String
  ) : SessionExportResult()

  /**
   * Export failed with an error.
   */
  data class Error(
    val title: String,
    val message: String
  ) : SessionExportResult()
}
