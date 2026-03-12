package xyz.block.trailblaze.ui.tabs.testresults

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import xyz.block.trailblaze.logs.model.getSessionInfo
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

  // Build lightweight session info list - recomputes when sessionIds change
  val sessions = remember(sessionIds) {
    sessionIds.mapNotNull { sessionId ->
      val logs = logsRepo.getCachedLogsForSession(sessionId)
      if (logs.isNotEmpty()) logs.getSessionInfo() else null
    }
  }

  // Use the common composable with the loaded data
  TestResultsComposable(sessions = sessions)
}
