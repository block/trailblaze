package xyz.block.trailblaze.mcp

import io.modelcontextprotocol.kotlin.sdk.ProgressNotification
import io.modelcontextprotocol.kotlin.sdk.ProgressToken
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import xyz.block.trailblaze.mcp.models.McpSseSessionId

// Session context interface for tools
class TrailblazeMcpSseSessionContext(
  val mcpServer: Server,
  val mcpSseSessionId: McpSseSessionId,
  var progressToken: ProgressToken? = null,
) {

  val sendProgressNotificationsScope = CoroutineScope(Dispatchers.IO)

  var progressCount: Int = 0

  fun sendIndeterminateProgressMessage(message: String) {
    println("Sending progress $message $message")
    progressToken?.let { progressToken ->
      sendProgressNotificationsScope.launch {
        mcpServer.notification(
          ProgressNotification(
            progress = progressCount++,
            progressToken = progressToken,
            total = null,
            message = message,
          ),
        )
      }
    }
  }

  fun sendIndeterminateProgressMessage(progress: Int, message: String, total: Double? = null) {
    println("Sending progress $progress $message")
    progressToken?.let { progressToken ->
      sendProgressNotificationsScope.launch {
        mcpServer.notification(
          ProgressNotification(
            progress = progress,
            progressToken = progressToken,
            total = total,
            message = message,
          ),
        )
      }
    }
  }
}
