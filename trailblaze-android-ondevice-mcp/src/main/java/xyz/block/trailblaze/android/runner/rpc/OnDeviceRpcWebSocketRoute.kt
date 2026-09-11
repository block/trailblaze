package xyz.block.trailblaze.android.runner.rpc

import io.ktor.server.routing.Route
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import io.ktor.websocket.send
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import xyz.block.trailblaze.mcp.android.ondevice.rpc.RpcResult
import xyz.block.trailblaze.llm.RunYamlRequest
import xyz.block.trailblaze.llm.RunYamlResponse
import xyz.block.trailblaze.mcp.android.ondevice.rpc.DrainSessionRequest
import xyz.block.trailblaze.mcp.android.ondevice.rpc.DrainSessionResponse
import xyz.block.trailblaze.mcp.android.ondevice.rpc.GetExecutionStatusRequest
import xyz.block.trailblaze.mcp.android.ondevice.rpc.GetExecutionStatusResponse
import xyz.block.trailblaze.mcp.android.ondevice.rpc.GetScreenStateRequest
import xyz.block.trailblaze.mcp.android.ondevice.rpc.GetScreenStateResponse
import xyz.block.trailblaze.mcp.android.ondevice.rpc.ListActiveSessionsRequest
import xyz.block.trailblaze.mcp.android.ondevice.rpc.ListActiveSessionsResponse
import xyz.block.trailblaze.mcp.android.ondevice.rpc.SubscribeToProgressRequest
import xyz.block.trailblaze.mcp.android.ondevice.rpc.SubscribeToProgressResponse
import xyz.block.trailblaze.ondevice.rpc.proto.OnDeviceRpcProtoCodec
import xyz.block.trailblaze.ondevice.rpc.proto.RpcFailure
import xyz.block.trailblaze.ondevice.rpc.proto.RpcRequestEnvelope
import xyz.block.trailblaze.ondevice.rpc.proto.RpcResponseEnvelope
import xyz.block.trailblaze.replay.ActionTrace
import xyz.block.trailblaze.util.Console

internal data class OnDeviceProtoRpcHandlers(
  val getScreenState: suspend (GetScreenStateRequest) -> RpcResult<GetScreenStateResponse>,
  val runYaml: suspend (RunYamlRequest) -> RpcResult<RunYamlResponse>,
  val drainSession: suspend (DrainSessionRequest) -> RpcResult<DrainSessionResponse>,
  val subscribeToProgress:
    suspend (SubscribeToProgressRequest) -> RpcResult<SubscribeToProgressResponse>,
  val getExecutionStatus:
    suspend (GetExecutionStatusRequest) -> RpcResult<GetExecutionStatusResponse>,
  val listActiveSessions:
    suspend (ListActiveSessionsRequest) -> RpcResult<ListActiveSessionsResponse>,
)

/** Binary WebSocket transport for all host-to-Android RPC calls. */
internal fun Route.registerOnDeviceRpcWebSocket(
  handlers: OnDeviceProtoRpcHandlers,
) {
  webSocket(ON_DEVICE_RPC_WEBSOCKET_PATH) {
    val sendMutex = Mutex()
    for (frame in incoming) {
      if (frame !is Frame.Binary) continue
      // t=0 for the boundary trace, stamped here rather than inside `begin()`. Everything between
      // this line and `begin()` — reading the frame's bytes, dispatching the coroutine, decoding
      // the envelope to name the op — is ingress the request really paid for, and the trace's job
      // is to attribute the whole request.
      val arrivedNs = System.nanoTime()
      val bytes = frame.readBytes()
      launch {
        // The only place a whole request is bracketed. `run_yaml` is one replayed action, so its
        // frame arrival is t=0 for the boundary trace and its reply frame is the last boundary;
        // every other op just notes that it overlapped, so a polluted trace is visible.
        //
        // Naming the op means decoding the request envelope a second time, and a `run_yaml`
        // envelope carries the whole trail YAML plus its config — so read the gate FIRST and only
        // pay for the decode on a run that asked to be traced. With the trace off `op` stays null
        // and every ActionTrace call below is a volatile read and a return.
        val op = if (ActionTrace.gateEnabled()) opName(bytes) else null
        if (op == "run_yaml") ActionTrace.begin(arrivedNs) else ActionTrace.otherRpc()
        // With t=0 at frame arrival this is no longer ~0: it is what ingress cost — the coroutine
        // dispatch and the envelope decode that named the op.
        ActionTrace.mark(ActionTrace.Boundary.DISPATCH_START)
        val response = handleBinaryRequest(bytes, handlers)
        ActionTrace.mark(ActionTrace.Boundary.HANDLER_RETURNED)
        val encoded = OnDeviceRpcProtoCodec.encode(response)
        // Encoding the reply is where a `get_screen_state` accessibility tree becomes wire bytes,
        // and the only place that size is knowable, so the boundary carries it.
        ActionTrace.markWith(ActionTrace.Boundary.REPLY_ENCODED, encoded.size.toLong())
        sendMutex.withLock {
          send(Frame.Binary(fin = true, data = encoded))
        }
        ActionTrace.mark(ActionTrace.Boundary.REPLY_WRITTEN)
        if (op == "run_yaml") ActionTrace.emit()
      }
    }
  }
}

/** Which payload the request envelope carried, so the trace can bracket `run_yaml`. Never throws. */
private fun opName(bytes: ByteArray): String = try {
  val request = OnDeviceRpcProtoCodec.decodeRequest(bytes)
  when {
    request.get_screen_state != null -> "get_screen_state"
    request.run_yaml != null -> "run_yaml"
    request.drain_session != null -> "drain_session"
    request.subscribe_to_progress != null -> "subscribe_to_progress"
    request.get_execution_status != null -> "get_execution_status"
    request.list_active_sessions != null -> "list_active_sessions"
    else -> "unknown"
  }
} catch (e: Exception) {
  "undecodable"
}

internal suspend fun handleBinaryRequest(
  bytes: ByteArray,
  handlers: OnDeviceProtoRpcHandlers,
): RpcResponseEnvelope {
  val request = try {
    OnDeviceRpcProtoCodec.decodeRequest(bytes)
  } catch (e: Exception) {
    return failureResponse(
      requestId = 0,
      errorType = RpcResult.ErrorType.SERIALIZATION_ERROR,
      message = "Invalid protobuf request",
      details = e.message,
    )
  }

  return try {
    val getScreenState = request.get_screen_state
    val runYaml = request.run_yaml
    val drainSession = request.drain_session
    val subscribeToProgress = request.subscribe_to_progress
    val getExecutionStatus = request.get_execution_status
    val listActiveSessions = request.list_active_sessions
    OnDeviceRpcProtoCodec.run {
      when {
        getScreenState != null -> handlers.getScreenState(getScreenState.toModel())
          .toEnvelope(request.request_id) { id, value ->
            RpcResponseEnvelope(request_id = id, get_screen_state = value.toProto())
          }
        runYaml != null -> handlers.runYaml(runYaml.toModel())
          .toEnvelope(request.request_id) { id, value ->
            RpcResponseEnvelope(request_id = id, run_yaml = value.toProto())
          }
        drainSession != null -> handlers.drainSession(drainSession.toModel())
          .toEnvelope(request.request_id) { id, value ->
            RpcResponseEnvelope(request_id = id, drain_session = value.toProto())
          }
        subscribeToProgress != null ->
          handlers.subscribeToProgress(subscribeToProgress.toModel())
            .toEnvelope(request.request_id) { id, value ->
              RpcResponseEnvelope(request_id = id, subscribe_to_progress = value.toProto())
            }
        getExecutionStatus != null ->
          handlers.getExecutionStatus(getExecutionStatus.toModel())
            .toEnvelope(request.request_id) { id, value ->
              RpcResponseEnvelope(request_id = id, get_execution_status = value.toProto())
            }
        listActiveSessions != null ->
          handlers.listActiveSessions(listActiveSessions.toModel())
            .toEnvelope(request.request_id) { id, value ->
              RpcResponseEnvelope(request_id = id, list_active_sessions = value.toProto())
            }
        else -> failureResponse(
          requestId = request.request_id,
          errorType = RpcResult.ErrorType.SERIALIZATION_ERROR,
          message = "Protobuf request omitted its payload",
        )
      }
    }
  } catch (e: CancellationException) {
    throw e
  } catch (e: kotlinx.serialization.SerializationException) {
    failureResponse(
      requestId = request.request_id,
      errorType = RpcResult.ErrorType.SERIALIZATION_ERROR,
      message = e.message ?: "Binary RPC body failed to deserialize",
      details = e::class.simpleName,
    )
  } catch (e: Exception) {
    Console.log("[OnDeviceRpcWebSocket] request failed: ${e.message}")
    failureResponse(
      requestId = request.request_id,
      errorType = RpcResult.ErrorType.UNKNOWN_ERROR,
      message = "Binary RPC request failed: ${e.message}",
      details = e.stackTraceToString(),
    )
  }
}

private inline fun <T> RpcResult<T>.toEnvelope(
  requestId: Long,
  success: (Long, T) -> RpcResponseEnvelope,
): RpcResponseEnvelope = when (this) {
  is RpcResult.Success -> success(requestId, data)
  is RpcResult.Failure -> RpcResponseEnvelope(
    request_id = requestId,
    failure = OnDeviceRpcProtoCodec.run { toProto() },
  )
}

private fun failureResponse(
  requestId: Long,
  errorType: RpcResult.ErrorType,
  message: String,
  details: String? = null,
): RpcResponseEnvelope =
  RpcResponseEnvelope(
    request_id = requestId,
    failure = RpcFailure(
      error_type = errorType.name,
      message = message,
      details = details,
    ),
  )

internal const val ON_DEVICE_RPC_WEBSOCKET_PATH = "/rpc-ws"
