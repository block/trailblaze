package xyz.block.trailblaze.logs.server

import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.Frame
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.io.File

object SocketsRepo {
  val webSocketConnections = mutableListOf<DefaultWebSocketSession>()

  val watchers = mutableMapOf<String?, FileWatchService>()

  fun startWatchForSession(serverFilesDir: File, sessionId: String?) {
    if (watchers[sessionId] == null) {
      // Start Watching
      val dirToWatch = if (sessionId == null) {
        serverFilesDir
      } else {
        File(serverFilesDir, sessionId)
      }

      GlobalScope.launch {
        // NOTE This doesn't do much right now because it only notifies when things in this directory itself changes.
        val watchService = FileWatchService(dirToWatch) { changeType: FileWatchService.ChangeType, fileChanged ->
          GlobalScope.launch {
            if (fileChanged.extension == "json") {
              webSocketConnections.forEach { session ->
                println("File changed $changeType $fileChanged for $session $sessionId")
                session.send(Frame.Text("Session Updated: $sessionId"))
              }
            } else {
              webSocketConnections.forEach { session ->
                val message = "Some File changed $changeType $fileChanged for $session $sessionId"
                println(message)
                session.send(Frame.Text(message))
              }
            }
          }
        }

        watchers[sessionId] = watchService
        GlobalScope.launch {
          watchService.startWatching()
        }
      }
    }
  }
}
