package xyz.block.trailblaze.ui.utils

object FormattingUtils {
  // Helper function for duration formatting
  fun formatDuration(durationMs: Long): String {
    val seconds = durationMs / 1000.0
    return "${(seconds * 100).toInt() / 100.0}s"
  }
}