package xyz.block.trailblaze.ui.tabs.testresults

import androidx.compose.runtime.*
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.report.utils.LogsRepo

/**
 * JVM-specific wrapper for TestResultsComposable that uses LogsRepo.
 * This handles the reactive file watching and data loading, then passes
 * the data to the common TestResultsComposable.
 */
@Composable
fun TestResultsComposableJvm(
  logsRepo: LogsRepo,
) {
  // Collect session IDs reactively from the Flow - automatic updates!
  val sessionIds by logsRepo.sessionsFlow.collectAsState()

  // Build a map of session logs - recomputes when sessionIds change
  val sessionLogsMap = remember(sessionIds) {
    sessionIds.associate { sessionId ->
      sessionId.value to logsRepo.getCachedLogsForSession(sessionId)
    }
  }

  // Use the common composable with the loaded data
  TestResultsComposable(sessionLogsMap = sessionLogsMap)
}
