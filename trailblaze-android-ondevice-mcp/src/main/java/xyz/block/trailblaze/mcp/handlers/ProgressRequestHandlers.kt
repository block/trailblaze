package xyz.block.trailblaze.mcp.handlers

import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.mcp.RpcHandler
import xyz.block.trailblaze.mcp.android.ondevice.rpc.GetExecutionStatusRequest
import xyz.block.trailblaze.mcp.android.ondevice.rpc.GetExecutionStatusResponse
import xyz.block.trailblaze.mcp.android.ondevice.rpc.ListActiveSessionsRequest
import xyz.block.trailblaze.mcp.android.ondevice.rpc.ListActiveSessionsResponse
import xyz.block.trailblaze.mcp.android.ondevice.rpc.RpcResult
import xyz.block.trailblaze.mcp.android.ondevice.rpc.SubscribeToProgressRequest
import xyz.block.trailblaze.mcp.android.ondevice.rpc.SubscribeToProgressResponse
import xyz.block.trailblaze.mcp.progress.ProgressSessionManager
import xyz.block.trailblaze.util.Console

/**
 * Handler for subscribing to progress events.
 *
 * Returns a batch of progress events for the specified session (or all sessions).
 * For real-time streaming, clients should poll this endpoint or use WebSocket/SSE.
 *
 * ## Usage
 *
 * ```kotlin
 * val handler = SubscribeToProgressRequestHandler(progressManager)
 * val result = handler.handle(SubscribeToProgressRequest(sessionId = "my_session"))
 * ```
 *
 * @param progressManager The progress session manager for retrieving events
 */
class SubscribeToProgressRequestHandler(
  private val progressManager: ProgressSessionManager,
) : RpcHandler<SubscribeToProgressRequest, SubscribeToProgressResponse> {

  override suspend fun handle(request: SubscribeToProgressRequest): RpcResult<SubscribeToProgressResponse> {
    val sessionIdStr = request.sessionId
    val events = if (sessionIdStr != null) {
      // Get events for specific session
      val sessionId = SessionId(sessionIdStr)
      val afterTimestamp = if (request.includeHistory) null else System.currentTimeMillis()
      progressManager.getEventsForSession(sessionId, afterTimestamp)
    } else {
      // Get all recent events (from the SharedFlow replay buffer)
      // For cross-session subscription, clients should use the streaming API
      emptyList()
    }

    val lastTimestamp = events.lastOrNull()?.timestamp

    return RpcResult.Success(
      SubscribeToProgressResponse(
        events = events,
        hasMore = false, // HTTP API returns all available events
        lastEventTimestamp = lastTimestamp,
      )
    )
  }
}

/**
 * Handler for getting execution status.
 *
 * Returns a snapshot of the current execution state for a session.
 *
 * ## Usage
 *
 * ```kotlin
 * val handler = GetExecutionStatusRequestHandler(progressManager)
 * val result = handler.handle(GetExecutionStatusRequest(sessionId = "my_session"))
 * when (result) {
 *   is RpcResult.Success -> {
 *     if (result.data.found) {
 *       Console.log("Progress: ${result.data.status?.progressPercent}%")
 *     } else {
 *       Console.log("Session not found")
 *     }
 *   }
 * }
 * ```
 *
 * @param progressManager The progress session manager for retrieving status
 */
class GetExecutionStatusRequestHandler(
  private val progressManager: ProgressSessionManager,
) : RpcHandler<GetExecutionStatusRequest, GetExecutionStatusResponse> {

  override suspend fun handle(request: GetExecutionStatusRequest): RpcResult<GetExecutionStatusResponse> {
    val sessionId = SessionId(request.sessionId)
    val status = progressManager.getExecutionStatus(sessionId)

    return RpcResult.Success(
      GetExecutionStatusResponse(
        status = status,
        found = status != null,
      )
    )
  }
}

/**
 * Handler for listing all active sessions.
 *
 * Returns execution status for all currently running sessions,
 * optionally including recently completed sessions.
 *
 * ## Usage
 *
 * ```kotlin
 * val handler = ListActiveSessionsRequestHandler(progressManager)
 * val result = handler.handle(ListActiveSessionsRequest(includeCompleted = true))
 * when (result) {
 *   is RpcResult.Success -> {
 *     result.data.sessions.forEach { status ->
 *       Console.log("${status.sessionId}: ${status.state} (${status.progressPercent}%)")
 *     }
 *   }
 * }
 * ```
 *
 * @param progressManager The progress session manager for retrieving statuses
 */
class ListActiveSessionsRequestHandler(
  private val progressManager: ProgressSessionManager,
) : RpcHandler<ListActiveSessionsRequest, ListActiveSessionsResponse> {

  override suspend fun handle(request: ListActiveSessionsRequest): RpcResult<ListActiveSessionsResponse> {
    val sessions = progressManager.getAllSessionStatuses(request.includeCompleted)

    return RpcResult.Success(
      ListActiveSessionsResponse(sessions = sessions)
    )
  }
}
