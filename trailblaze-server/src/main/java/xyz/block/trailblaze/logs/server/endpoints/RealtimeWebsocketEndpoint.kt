package xyz.block.trailblaze.logs.server.endpoints

import io.ktor.server.routing.Routing
import io.ktor.server.websocket.webSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import xyz.block.trailblaze.logs.server.SocketsRepo
import xyz.block.trailblaze.report.utils.LogsRepo

/**
 * Handles WebSocket connections for real-time updates.
 */
object RealtimeWebsocketEndpoint {

  fun register(
    routing: Routing,
    logsRepo: LogsRepo,
  ) = with(routing) {
    webSocket("/updates") {
      val currSocketSession = this
      val sessionId: String? = currSocketSession.call.request.queryParameters["id"]
      CoroutineScope(Dispatchers.IO).launch {
        println("Start Watching for Session $sessionId")
        SocketsRepo.startWatchForSession(logsRepo.logsDir, sessionId)
      }
      SocketsRepo.webSocketConnections.add(currSocketSession)
      try {
        for (frame in incoming) {
          // You can handle incoming messages here if needed
          println("Incoming WebSocket Message: $frame")
        }
      } finally {
        println("Removing $currSocketSession for $sessionId")
        SocketsRepo.webSocketConnections.remove(currSocketSession)
      }
    }
  }
}
