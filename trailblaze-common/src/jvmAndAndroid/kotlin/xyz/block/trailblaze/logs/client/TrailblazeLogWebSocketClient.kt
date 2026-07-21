package xyz.block.trailblaze.logs.client

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.url
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.readBytes
import io.ktor.websocket.send
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import xyz.block.trailblaze.ondevice.rpc.proto.LogUploadAck
import xyz.block.trailblaze.ondevice.rpc.proto.LogUploadEnvelope
import xyz.block.trailblaze.ondevice.rpc.proto.OnDeviceRpcProtoCodec

/** Persistent device-to-host protobuf upload channel. */
internal class TrailblazeLogWebSocketClient(
  private val httpClient: HttpClient,
  baseUrl: String,
) {
  sealed interface Attempt {
    data object Success : Attempt
    /** Connection failed before an upload frame was sent, so HTTP is safe. */
    data object FallbackToHttp : Attempt
    /** Delivery after send is uncertain; callers must use their durable disk fallback. */
    data class Failure(val message: String) : Attempt
  }

  private val webSocketUrl = when {
    baseUrl.startsWith("https://") -> baseUrl.replaceFirst("https://", "wss://")
    else -> baseUrl.replaceFirst("http://", "ws://")
  } + WEBSOCKET_PATH
  private val requestIds = AtomicLong(0)
  private val pending = ConcurrentHashMap<Long, CompletableDeferred<LogUploadAck>>()
  private val connectionMutex = Mutex()
  private val sendMutex = Mutex()
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  @Volatile private var session: WebSocketSession? = null
  @Volatile private var readerJob: Job? = null
  @Volatile private var reconnectAfterEpochMs = 0L

  suspend fun send(payload: (Long) -> LogUploadEnvelope): Attempt {
    val activeSession = ensureConnected() ?: return Attempt.FallbackToHttp
    val uploadId = requestIds.incrementAndGet()
    val ack = CompletableDeferred<LogUploadAck>()
    pending[uploadId] = ack
    try {
      sendMutex.withLock {
        activeSession.send(
          Frame.Binary(
            fin = true,
            data = OnDeviceRpcProtoCodec.encode(payload(uploadId)),
          ),
        )
      }
    } catch (e: CancellationException) {
      pending.remove(uploadId)
      throw e
    } catch (e: Exception) {
      pending.remove(uploadId)
      clearSession(activeSession)
      return Attempt.Failure("Protobuf log upload failed after send: ${e.message}")
    }

    val response = try {
      withTimeoutOrNull(ACK_TIMEOUT_MS) { ack.await() }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      null
    } finally {
      pending.remove(uploadId)
    }
    return when {
      response == null -> Attempt.Failure("Protobuf log upload timed out waiting for acknowledgement")
      response.success -> Attempt.Success
      else -> Attempt.Failure(response.error_message ?: "Host rejected protobuf log upload")
    }
  }

  private suspend fun ensureConnected(): WebSocketSession? = connectionMutex.withLock {
    session?.let { return@withLock it }
    if (System.currentTimeMillis() < reconnectAfterEpochMs) return@withLock null
    val newSession = try {
      httpClient.webSocketSession { url(webSocketUrl) }
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      reconnectAfterEpochMs = System.currentTimeMillis() + CONNECT_RETRY_DELAY_MS
      return@withLock null
    }
    reconnectAfterEpochMs = 0L
    session = newSession
    readerJob = scope.launch {
      try {
        for (frame in newSession.incoming) {
          if (frame !is Frame.Binary) continue
          val ack = OnDeviceRpcProtoCodec.decodeLogUploadAck(frame.readBytes())
          pending.remove(ack.upload_id)?.complete(ack)
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        failPending(e)
      } finally {
        clearSession(newSession)
        failPending(IOException("Protobuf log WebSocket closed"))
      }
    }
    newSession
  }

  private fun clearSession(expected: WebSocketSession) {
    if (session === expected) session = null
  }

  private fun failPending(error: Throwable) {
    pending.entries.toList().forEach { (id, deferred) ->
      if (pending.remove(id, deferred)) deferred.completeExceptionally(error)
    }
  }

  fun close() {
    readerJob?.cancel()
    readerJob = null
    session = null
    failPending(IOException("Protobuf log client closed"))
    scope.cancel()
  }

  private companion object {
    const val WEBSOCKET_PATH = "/logs-ws"
    const val ACK_TIMEOUT_MS = 2_000L
    const val CONNECT_RETRY_DELAY_MS = 30_000L
  }
}
