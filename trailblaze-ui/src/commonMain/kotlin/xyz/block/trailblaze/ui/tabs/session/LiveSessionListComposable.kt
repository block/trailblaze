package xyz.block.trailblaze.ui.tabs.session

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import xyz.block.trailblaze.logs.model.SessionInfo

@Composable
fun LiveSessionListComposable(
  sessionDataProvider: LiveSessionDataProvider,
  sessionClicked: (SessionInfo) -> Unit = {},
  deleteSession: (SessionInfo) -> Unit,
  clearAllLogs: () -> Unit,
) {
  var sessions by remember {
    mutableStateOf(
      sessionDataProvider.getSessionIds().mapNotNull { sessionId ->
        val firstLog = sessionDataProvider.getLogsForSession(sessionId).firstOrNull()
        if (firstLog != null) {
          sessionDataProvider.getSessionInfo(sessionId)
        } else {
          null
        }
      }
    )
  }

  DisposableEffect(sessionDataProvider) {
    val listener = object : SessionListListener {
      override fun onSessionAdded(sessionId: String) {
        val updatedSessions = sessionDataProvider.getSessionIds().mapNotNull { id ->
          val firstLog = sessionDataProvider.getLogsForSession(id).firstOrNull()
          if (firstLog != null) {
            sessionDataProvider.getSessionInfo(id)
          } else {
            null
          }
        }
        sessions = updatedSessions
      }

      override fun onSessionRemoved(sessionId: String) {
        sessions = sessions.filterNot { it.sessionId == sessionId }
      }
    }

    sessionDataProvider.addSessionListListener(listener)

    onDispose {
      sessionDataProvider.removeSessionListListener(listener)
    }
  }

  SessionListComposable(
    sessions = sessions,
    sessionClicked = sessionClicked,
    deleteSession = deleteSession,
    clearAllLogs = clearAllLogs,
  )
}
