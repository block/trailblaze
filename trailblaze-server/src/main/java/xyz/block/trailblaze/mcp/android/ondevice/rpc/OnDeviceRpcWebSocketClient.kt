package xyz.block.trailblaze.mcp.android.ondevice.rpc

import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import xyz.block.trailblaze.ondevice.rpc.proto.OnDeviceRpcProtoCodec
import xyz.block.trailblaze.ondevice.rpc.proto.RpcRequestEnvelope
import xyz.block.trailblaze.ondevice.rpc.proto.RpcResponseEnvelope
import xyz.block.trailblaze.util.Console

/** Persistent, multiplexed binary channel to the Android on-device runner. */
internal class OnDeviceRpcWebSocketClient(
  baseUrl: String,
  private val client: OkHttpClient = defaultClient(),
) : AutoCloseable {

  sealed interface Attempt<out T> {
    data class Success<T>(val value: T) : Attempt<T>
    data class Failure(val failure: RpcResult.Failure) : Attempt<Nothing>
    /** No request was sent, so the caller may safely use HTTP instead. */
    data object FallbackToHttp : Attempt<Nothing>
  }

  private val webSocketUrl = baseUrl.replaceFirst("http://", "ws://") + WEBSOCKET_PATH
  private val requestIds = AtomicLong(0)
  private val pending = ConcurrentHashMap<Long, CompletableDeferred<RpcResponseEnvelope>>()
  private val connectionLock = Any()

  @Volatile private var socket: WebSocket? = null
  @Volatile private var connectingSocket: WebSocket? = null
  @Volatile private var connecting: CompletableDeferred<WebSocket>? = null
  @Volatile private var unsupported = false
  @Volatile private var closed = false

  suspend fun call(request: RpcRequest<*>, timeoutMs: Long): Attempt<Any> {
    return when (
      val attempt = exchange(
        requestId = requestIds.incrementAndGet(),
        timeoutMs = timeoutMs,
        payload = { id -> request.toEnvelope(id) },
      )
    ) {
      is Attempt.FallbackToHttp -> attempt
      is Attempt.Failure -> attempt
      is Attempt.Success -> decodeResponse(request, attempt.value)
    }
  }

  private fun RpcRequest<*>.toEnvelope(requestId: Long): RpcRequestEnvelope? =
    OnDeviceRpcProtoCodec.run {
      when (this@toEnvelope) {
        is GetScreenStateRequest -> RpcRequestEnvelope(
          request_id = requestId,
          get_screen_state = toProto(),
        )
        is xyz.block.trailblaze.llm.RunYamlRequest -> RpcRequestEnvelope(
          request_id = requestId,
          run_yaml = toProto(),
        )
        is DrainSessionRequest -> RpcRequestEnvelope(
          request_id = requestId,
          drain_session = toProto(),
        )
        is SubscribeToProgressRequest -> RpcRequestEnvelope(
          request_id = requestId,
          subscribe_to_progress = toProto(),
        )
        is GetExecutionStatusRequest -> RpcRequestEnvelope(
          request_id = requestId,
          get_execution_status = toProto(),
        )
        is ListActiveSessionsRequest -> RpcRequestEnvelope(
          request_id = requestId,
          list_active_sessions = toProto(),
        )
        else -> null
      }
    }

  private fun decodeResponse(
    request: RpcRequest<*>,
    response: RpcResponseEnvelope,
  ): Attempt<Any> = OnDeviceRpcProtoCodec.run {
    val model = when (request) {
      is GetScreenStateRequest -> response.get_screen_state?.toModel()
      is xyz.block.trailblaze.llm.RunYamlRequest -> response.run_yaml?.toModel()
      is DrainSessionRequest -> response.drain_session?.toModel()
      is SubscribeToProgressRequest -> response.subscribe_to_progress?.toModel()
      is GetExecutionStatusRequest -> response.get_execution_status?.toModel()
      is ListActiveSessionsRequest -> response.list_active_sessions?.toModel()
      else -> null
    } ?: return protocolFailure(
      "Binary ${request::class.simpleName} response omitted its payload",
    )
    Attempt.Success(model)
  }

  private suspend fun exchange(
    requestId: Long,
    timeoutMs: Long,
    payload: (Long) -> RpcRequestEnvelope?,
  ): Attempt<RpcResponseEnvelope> {
    // Preserve the generic client's HTTP behavior for legacy or future request types until they
    // gain an explicit protobuf mapping. Strict protobuf mode turns this fallback into a failure.
    val envelope = payload(requestId) ?: return Attempt.FallbackToHttp
    val activeSocket = ensureConnected() ?: return Attempt.FallbackToHttp
    val deferred = CompletableDeferred<RpcResponseEnvelope>()
    pending[requestId] = deferred
    val sent = activeSocket.send(OnDeviceRpcProtoCodec.encode(envelope).toByteString())
    if (!sent) {
      pending.remove(requestId)
      discardSocket(activeSocket, IOException("Binary RPC WebSocket rejected the request"))
      return Attempt.FallbackToHttp
    }

    val response = try {
      withTimeoutOrNull(timeoutMs) { deferred.await() }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      return protocolFailure("Binary RPC connection failed after send: ${e.message}")
    } finally {
      pending.remove(requestId)
    }
    if (response == null) {
      discardSocket(
        activeSocket,
        IOException("Binary RPC timed out after ${timeoutMs}ms"),
      )
      return protocolFailure("Binary RPC timed out after ${timeoutMs}ms")
    }
    response.failure?.let { failure ->
      return Attempt.Failure(
        OnDeviceRpcProtoCodec.run {
          failure.toModel(method = null, url = webSocketUrl)
        },
      )
    }
    return Attempt.Success(response)
  }

  private suspend fun ensureConnected(): WebSocket? {
    socket?.let { return it }
    if (unsupported || closed) return null

    val waiter = synchronized(connectionLock) {
      socket?.let { return@synchronized CompletableDeferred<WebSocket>().also { d -> d.complete(it) } }
      connecting?.let { return@synchronized it }
      CompletableDeferred<WebSocket>().also { deferred ->
        connecting = deferred
        connectingSocket = client.newWebSocket(Request.Builder().url(webSocketUrl).build(), listener)
      }
    }
    val connected = try {
      withTimeoutOrNull(CONNECT_TIMEOUT_MS) { waiter.await() }
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      null
    }
    if (connected == null) {
      synchronized(connectionLock) {
        if (connecting === waiter) {
          connecting = null
          connectingSocket?.cancel()
          connectingSocket = null
        }
      }
    }
    return connected
  }

  private val listener = object : WebSocketListener() {
    override fun onOpen(webSocket: WebSocket, response: Response) {
      val waiter = synchronized(connectionLock) {
        if (connectingSocket !== webSocket || closed) {
          null
        } else {
          socket = webSocket
          connectingSocket = null
          connecting.also { connecting = null }
        }
      }
      if (waiter == null) {
        webSocket.close(1000, "superseded connection")
      } else {
        waiter.complete(webSocket)
      }
    }

    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
      val response = try {
        OnDeviceRpcProtoCodec.decodeResponse(bytes.toByteArray())
      } catch (e: Exception) {
        failPending(IOException("Invalid protobuf response: ${e.message}", e))
        webSocket.cancel()
        return
      }
      pending.remove(response.request_id)?.complete(response)
        ?: Console.log("[OnDeviceRpcWebSocket] response for unknown id ${response.request_id}")
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
      webSocket.close(code, reason)
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
      if (clearSocket(webSocket)) {
        failPending(IOException("Binary RPC WebSocket closed ($code): $reason"))
      }
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
      if (response != null) {
        // A real HTTP response to the upgrade means this runner predates the WebSocket route.
        unsupported = true
      }
      val wasActive = clearSocket(webSocket)
      val waiter = synchronized(connectionLock) {
        if (connectingSocket === webSocket) {
          connectingSocket = null
          connecting.also { connecting = null }
        } else {
          null
        }
      }
      waiter?.completeExceptionally(t)
      if (wasActive) failPending(t)
    }
  }

  private fun failPending(cause: Throwable) {
    val snapshot = pending.entries.toList()
    snapshot.forEach { (id, deferred) ->
      if (pending.remove(id, deferred)) deferred.completeExceptionally(cause)
    }
  }

  private fun clearSocket(expected: WebSocket): Boolean {
    return synchronized(connectionLock) {
      if (socket === expected) {
        socket = null
        true
      } else {
        false
      }
    }
  }

  private fun discardSocket(expected: WebSocket, cause: Throwable) {
    if (clearSocket(expected)) {
      expected.cancel()
      failPending(cause)
    }
  }

  private fun protocolFailure(message: String): Attempt.Failure =
    Attempt.Failure(
      RpcResult.Failure(
        errorType = RpcResult.ErrorType.NETWORK_ERROR,
        message = message,
        url = webSocketUrl,
      ),
    )

  override fun close() {
    closed = true
    socket?.close(1000, "client closed")
    socket = null
    connectingSocket?.cancel()
    connectingSocket = null
    failPending(IOException("Binary RPC client closed"))
    client.dispatcher.executorService.shutdown()
    client.connectionPool.evictAll()
  }

  private companion object {
    const val WEBSOCKET_PATH = "/rpc-ws"
    const val CONNECT_TIMEOUT_MS = 2_000L

    fun defaultClient(): OkHttpClient =
      OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
  }
}
