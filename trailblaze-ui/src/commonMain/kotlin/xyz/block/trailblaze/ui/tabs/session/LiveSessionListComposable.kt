package xyz.block.trailblaze.ui.tabs.session

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.block.trailblaze.logs.model.SessionInfo

@Composable
fun LiveSessionListComposable(
  sessionDataProvider: LiveSessionDataProvider,
  sessionClicked: (SessionInfo) -> Unit = {},
  deleteSession: (SessionInfo) -> Unit,
  clearAllLogs: () -> Unit,
  openLogsFolder: ((SessionInfo) -> Unit)? = null,
) {
  var sessions by remember {
    mutableStateOf(emptyList<SessionInfo>())
  }

  // Load initial sessions asynchronously
  LaunchedEffect(sessionDataProvider) {
    withContext(Dispatchers.Default) {
      val sessionIds = sessionDataProvider.getSessionIds()
      val loadedSessions = sessionIds.mapNotNull { sessionId ->
        sessionDataProvider.getSessionInfo(sessionId)
      }
      sessions = loadedSessions
    }
  }

  DisposableEffect(sessionDataProvider) {
    val listener = object : SessionListListener {
      override fun onSessionAdded(sessionId: String) {
        CoroutineScope(Dispatchers.Default).launch {
          val sessionInfo = sessionDataProvider.getSessionInfo(sessionId)

          // Only update if we successfully got the new session info
          if (sessionInfo != null) {
            // Add the new session to the existing list instead of reloading everything
            sessions = sessions + sessionInfo
          }
        }
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
    openLogsFolder = openLogsFolder,
  )
}
