package xyz.block.trailblaze.mcp.android.ondevice.rpc

import kotlinx.serialization.Serializable
import xyz.block.trailblaze.agent.ExecutionStatus
import xyz.block.trailblaze.agent.TrailblazeProgressEvent
import xyz.block.trailblaze.util.Console

/**
 * RPC request to subscribe to progress events for a session.
 *
 * Returns a stream of progress events as they occur during execution.
 * Use this to provide real-time progress updates to MCP clients (Cursor, Claude, etc.).
 *
 * ## Usage
 *
 * ```kotlin
 * val request = SubscribeToProgressRequest(sessionId = "my_session")
 * val result = client.rpcCall(request)
 * when (result) {
 *   is RpcResult.Success -> {
 *     result.data.events.forEach { event ->
 *       Console.log("Progress: $event")
 *     }
 *   }
 *   is RpcResult.Failure -> {
 *     Console.log("Failed: ${result.message}")
 *   }
 * }
 * ```
 *
 * @param sessionId The session ID to subscribe to. If null, subscribes to all active sessions.
 * @param includeHistory If true, includes events that occurred before the subscription started.
 *                       Default is false (only new events).
 */
@Serializable
data class SubscribeToProgressRequest(
  val sessionId: String? = null,
  val includeHistory: Boolean = false,
) : RpcRequest<SubscribeToProgressResponse>

/**
 * Response containing progress events.
 *
 * For HTTP-based transport, this returns a batch of events.
 * For WebSocket/SSE transport, events can be streamed individually.
 *
 * @param events List of progress events that have occurred
 * @param hasMore True if there are more events available (for paginated retrieval)
 * @param lastEventTimestamp Timestamp of the last event, for pagination
 */
@Serializable
data class SubscribeToProgressResponse(
  val events: List<TrailblazeProgressEvent>,
  val hasMore: Boolean = false,
  val lastEventTimestamp: Long? = null,
)

/**
 * RPC request to get the current execution status for a session.
 *
 * Returns a snapshot of the current execution state, useful for
 * status queries and dashboard displays.
 *
 * ## Usage
 *
 * ```kotlin
 * val request = GetExecutionStatusRequest(sessionId = "my_session")
 * val result = client.rpcCall(request)
 * when (result) {
 *   is RpcResult.Success -> {
 *     val status = result.data.status
 *     Console.log("Progress: ${status.progressPercent}%")
 *     Console.log("State: ${status.state}")
 *   }
 *   is RpcResult.Failure -> {
 *     Console.log("Session not found: ${result.message}")
 *   }
 * }
 * ```
 *
 * @param sessionId The session ID to query
 */
@Serializable
data class GetExecutionStatusRequest(
  val sessionId: String,
) : RpcRequest<GetExecutionStatusResponse>

/**
 * Response containing the current execution status.
 *
 * @param status The current execution status snapshot
 * @param found True if the session was found
 */
@Serializable
data class GetExecutionStatusResponse(
  val status: ExecutionStatus?,
  val found: Boolean,
)

/**
 * RPC request to list all active sessions with their status.
 *
 * Useful for dashboard views that need to show all running executions.
 *
 * @param includeCompleted If true, includes recently completed sessions (last 5 minutes).
 *                         Default is false (only active sessions).
 */
@Serializable
data class ListActiveSessionsRequest(
  val includeCompleted: Boolean = false,
) : RpcRequest<ListActiveSessionsResponse>

/**
 * Response containing all active session statuses.
 *
 * @param sessions List of execution statuses for active sessions
 */
@Serializable
data class ListActiveSessionsResponse(
  val sessions: List<ExecutionStatus>,
)
