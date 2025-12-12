package xyz.block.trailblaze.mcp

import io.modelcontextprotocol.kotlin.sdk.ProgressNotification
import io.modelcontextprotocol.kotlin.sdk.ProgressToken
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import xyz.block.trailblaze.mcp.models.McpSseSessionId

// Session context interface for tools
class TrailblazeMcpSseSessionContext(
  var mcpServerSession: ServerSession?, // Nullable and mutable to handle race condition during initialization
  val mcpSseSessionId: McpSseSessionId,
  var progressToken: ProgressToken? = null,
) {
  // Store transport directly so POSTs can be handled before connect() completes
  var sseTransport: io.modelcontextprotocol.kotlin.sdk.server.SseServerTransport? = null

  val sendProgressNotificationsScope = CoroutineScope(Dispatchers.IO)

  var progressCount: Int = 0

  fun sendIndeterminateProgressMessage(message: String) {
    println("Sending progress $message $message")
    progressToken?.let { progressToken ->
      sendProgressNotificationsScope.launch {
        mcpServerSession?.notification(
          ProgressNotification(
            params = ProgressNotification.Params(
              progress = progressCount++.toDouble(),
              progressToken = progressToken,
              total = null,
              message = message,
            ),
          ),
        )
      }
    }
  }

  fun sendIndeterminateProgressMessage(progress: Int, message: String, total: Double? = null) {
    println("Sending progress $progress $message")
    progressToken?.let { progressToken ->
      sendProgressNotificationsScope.launch {
        mcpServerSession?.notification(
          ProgressNotification(
            params = ProgressNotification.Params(
              progress = progress.toDouble(),
              progressToken = progressToken,
              total = total,
              message = message,
            ),
          ),
        )
      }
    }
  }
}
