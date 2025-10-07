package xyz.block.trailblaze.logs.server

import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.Frame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import xyz.block.trailblaze.report.utils.FileWatchService
import java.io.File

object SocketsRepo {
  val webSocketConnections = mutableListOf<DefaultWebSocketSession>()

  val watchers = mutableMapOf<String?, FileWatchService>()

  // Use a proper coroutine scope instead of GlobalScope
  private val socketScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

  // Channel to batch WebSocket notifications
  private val notificationChannel = Channel<Pair<String?, String>>(Channel.UNLIMITED)

  init {
    // Start the notification processor
    socketScope.launch {
      processNotifications()
    }
  }

  private suspend fun processNotifications() {
    val pendingNotifications = mutableMapOf<String?, String>()

    while (true) {
      // Collect notifications for a short period
      var hasNotifications = false
      val startTime = System.currentTimeMillis()

      while (System.currentTimeMillis() - startTime < 100L) { // Batch for 100ms
        val notification = try {
          notificationChannel.tryReceive().getOrNull()
        } catch (e: Exception) {
          null
        }

        if (notification != null) {
          pendingNotifications[notification.first] = notification.second
          hasNotifications = true
        } else if (hasNotifications) {
          break // No more immediate notifications, process what we have
        } else {
          // Wait for the first notification
          val firstNotification = notificationChannel.receive()
          pendingNotifications[firstNotification.first] = firstNotification.second
          hasNotifications = true
        }
      }

      if (hasNotifications) {
        // Send batched notifications
        pendingNotifications.entries.forEach { (sessionId, message) ->
          sendToAllWebSockets(message)
        }
        pendingNotifications.clear()
      }
    }
  }

  private suspend fun sendToAllWebSockets(message: String) {
    val connectionsToRemove = mutableListOf<DefaultWebSocketSession>()

    webSocketConnections.forEach { session ->
      try {
        session.send(Frame.Text(message))
      } catch (e: Exception) {
        println("Failed to send message to WebSocket session, removing: ${e.message}")
        connectionsToRemove.add(session)
      }
    }

    // Remove failed connections
    connectionsToRemove.forEach { webSocketConnections.remove(it) }
  }

  fun startWatchForSession(serverFilesDir: File, sessionId: String?) {
    if (watchers[sessionId] == null) {
      // Start Watching
      val dirToWatch = if (sessionId == null) {
        serverFilesDir
      } else {
        File(serverFilesDir, sessionId)
      }

      socketScope.launch {
        // NOTE This doesn't do much right now because it only notifies when things in this directory itself changes.
        val watchService = FileWatchService(
          dirToWatch = dirToWatch,
          onFileChange = { changeType: FileWatchService.ChangeType, fileChanged ->
            // Queue notification instead of sending immediately
            val message = if (fileChanged.extension == "json") {
              "Session Updated: $sessionId"
            } else {
              "Some File changed $changeType $fileChanged for session $sessionId"
            }

            // Use non-blocking send to notification channel
            val success = notificationChannel.trySend(Pair(sessionId, message)).isSuccess
            if (!success) {
              println("Notification channel full, dropping message: $message")
            }

            println("File changed $changeType $fileChanged for $sessionId")
          },
          debounceDelayMs = 800L, // Longer debounce for WebSocket notifications
          maxEventsPerSecond = 2, // Very conservative rate limiting for WebSocket events
        )

        watchers[sessionId] = watchService
        socketScope.launch {
          watchService.startWatching()
        }
      }
    }
  }
}
