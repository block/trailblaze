package xyz.block.trailblaze.ui.tabs.testresults

import androidx.compose.runtime.*
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.report.utils.SessionListListener
import xyz.block.trailblaze.report.utils.TrailblazeSessionListener

/**
 * JVM-specific wrapper for TestResultsComposable that uses LogsRepo.
 * This handles the reactive file watching and data loading, then passes
 * the data to the common TestResultsComposable.
 */
@Composable
fun TestResultsComposableJvm(
  logsRepo: LogsRepo,
) {
  // Use state to trigger recomposition when sessions change
  var sessionUpdateTrigger by remember { mutableStateOf(0) }

  // Register a listener to watch for session list changes (additions/removals)
  DisposableEffect(logsRepo) {
    val listener = object : SessionListListener {
      override fun onSessionAdded(sessionId: xyz.block.trailblaze.logs.model.SessionId) {
        println("[TestResultsComposableJvm] Session added: $sessionId")
        sessionUpdateTrigger++
      }

      override fun onSessionRemoved(sessionId: xyz.block.trailblaze.logs.model.SessionId) {
        println("[TestResultsComposableJvm] Session removed: $sessionId")
        sessionUpdateTrigger++
      }
    }

    logsRepo.addSessionListListener(listener)

    onDispose {
      logsRepo.removeSessionListListener(listener)
    }
  }

  // Watch all active sessions for status updates
  val sessionIds = remember(sessionUpdateTrigger) { logsRepo.getSessionIds() }

  DisposableEffect(sessionIds) {
    val sessionListeners = sessionIds.mapNotNull { sessionId ->
      val sessionInfo = logsRepo.getSessionInfo(sessionId)

      // Only watch sessions that are in progress
      if (sessionInfo?.latestStatus is SessionStatus.Started) {
        val listener = object : TrailblazeSessionListener {
          override val trailblazeSessionId = sessionId

          override fun onSessionStarted() {
            println("[TestResultsComposableJvm] Session started: $sessionId")
            sessionUpdateTrigger++
          }

          override fun onUpdate(message: String) {
            println("[TestResultsComposableJvm] Session updated: $sessionId - $message")
            sessionUpdateTrigger++
          }

          override fun onSessionEnded() {
            println("[TestResultsComposableJvm] Session ended: $sessionId")
            sessionUpdateTrigger++
          }
        }

        logsRepo.startWatchingTrailblazeSession(listener)
        Pair(sessionId, listener)
      } else {
        null
      }
    }

    onDispose {
      sessionListeners.forEach { (sessionId, _) ->
        logsRepo.stopWatching(sessionId)
      }
    }
  }

  // This will recompute whenever sessionUpdateTrigger changes
  val sessionLogsMap = remember(sessionUpdateTrigger) {
    logsRepo.getSessionIds()
      .associateWith { sessionId ->
        logsRepo.getCachedLogsForSession(sessionId)
      }
      .mapKeys { (sessionId, _) -> sessionId.value }
  }

  // Use the common composable with the loaded data
  TestResultsComposable(sessionLogsMap = sessionLogsMap)
}
