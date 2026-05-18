package xyz.block.trailblaze.compose.driver.rpc

import io.ktor.server.routing.Route
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlin.coroutines.cancellation.CancellationException
import kotlin.reflect.KClass
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import xyz.block.trailblaze.host.rpc.ws.RpcWsEnvelope
import xyz.block.trailblaze.host.rpc.ws.RpcWsError
import xyz.block.trailblaze.mcp.RpcHandler
import xyz.block.trailblaze.mcp.android.ondevice.rpc.RpcRequest
import xyz.block.trailblaze.mcp.android.ondevice.rpc.RpcRequest.Companion.toRpcPath
import xyz.block.trailblaze.mcp.android.ondevice.rpc.RpcResult

/**
 * Server-side dispatcher for the multiplexed `/rpc-ws` WebSocket transport.
 *
 * The dispatcher is intentionally *parallel* to the HTTP route helpers in [RpcRouteExt] —
 * the HTTP POST routes keep working, and this WS path is added alongside. Callers register
 * the same [RpcHandler] instances against both, and the WS handler hands off to those
 * existing handlers with no per-RPC duplication.
 *
 * Wire format: see [xyz.block.trailblaze.host.rpc.ws.RpcWsEnvelope]. Each incoming text frame
 * is a JSON envelope; each handler invocation produces a single [RpcWsEnvelope.Response]
 * frame correlated by `id`. Server-pushed events (e.g. live device frames) are emitted as
 * [RpcWsEnvelope.Event] frames at any time by long-lived push tasks held in [WsSessionContext].
 *
 * Usage pattern (mirrors `Routing.registerRpcHandler`):
 *
 * ```kotlin
 * val registry = RpcWsHandlerRegistry()
 * registry.register<MyResponse, MyRequest>(MyHandler())
 * // ...register more...
 * routing.registerRpcWebSocket(path = "/rpc-ws", registry = registry, json = json) { session ->
 *   // Optional: attach push-task starters keyed by RPC type for subscription RPCs.
 *   // The default dispatcher just answers request/response.
 * }
 * ```
 */
class RpcWsHandlerRegistry {

  /**
   * Per-path dispatcher closure. Captures the inline-reified [TRequest]/[TResponse]
   * serializers at registration time so the WS path can decode/encode without reflection.
   *
   * The closure also receives the active [WsSessionContext] so handlers that need to start
   * long-lived push streams (frame producers, log subscribers) can hook into the same
   * socket lifecycle without the registry knowing the specifics.
   */
  fun interface PathDispatcher {
    suspend fun dispatch(body: JsonElement, ctx: WsSessionContext, json: Json): RpcResult<JsonElement>
  }

  private val dispatchers = mutableMapOf<String, PathDispatcher>()

  /**
   * Register a request/response RPC handler. The path is derived from [TRequest] the
   * same way [toRpcPath] derives the HTTP route, so a single handler instance can serve
   * both the HTTP and WS sides if the caller registers it in both places.
   */
  inline fun <reified TResponse : Any, reified TRequest : RpcRequest<TResponse>> register(
    handler: RpcHandler<TRequest, TResponse>,
    requestSerializer: KSerializer<TRequest> = kotlinx.serialization.serializer(),
    responseSerializer: KSerializer<TResponse> = kotlinx.serialization.serializer(),
  ) {
    registerInternal(
      path = TRequest::class.toRpcPath(),
      handler = handler,
      requestSerializer = requestSerializer,
      responseSerializer = responseSerializer,
    )
  }

  /**
   * Register a handler that also wants access to the [WsSessionContext] to start long-lived
   * push tasks (e.g. a frame producer). Used by [xyz.block.trailblaze.host.rpc.ws.SubscribeFramesRequest]
   * which kicks off an event loop and exits, while the loop keeps pushing on the socket.
   *
   * The lambda parameter is intentionally not [crossinline] so the reified type arguments
   * flow into the lambda's `request` parameter (Kotlin's inference doesn't cross
   * `crossinline` boundaries cleanly when the call site uses an explicit type-argument list).
   */
  inline fun <reified TResponse : Any, reified TRequest : RpcRequest<TResponse>> registerWithContext(
    requestSerializer: KSerializer<TRequest> = kotlinx.serialization.serializer(),
    responseSerializer: KSerializer<TResponse> = kotlinx.serialization.serializer(),
    noinline handler: suspend (TRequest, WsSessionContext) -> RpcResult<TResponse>,
  ) {
    registerInternal(
      path = TRequest::class.toRpcPath(),
      // Wrap the lambda in an RpcHandler stub so the internal codepath stays uniform.
      handler = object : RpcHandler<TRequest, TResponse> {
        override suspend fun handle(request: TRequest): RpcResult<TResponse> {
          error("registerWithContext handler invoked without WsSessionContext")
        }
      },
      contextualHandler = handler,
      requestSerializer = requestSerializer,
      responseSerializer = responseSerializer,
    )
  }

  fun <TRequest : RpcRequest<TResponse>, TResponse : Any> registerInternal(
    path: String,
    handler: RpcHandler<TRequest, TResponse>,
    contextualHandler: (suspend (TRequest, WsSessionContext) -> RpcResult<TResponse>)? = null,
    requestSerializer: KSerializer<TRequest>,
    responseSerializer: KSerializer<TResponse>,
  ) {
    dispatchers[path] = PathDispatcher { bodyJson, ctx, json ->
      val request = json.decodeFromJsonElement(requestSerializer, bodyJson)
      val result = if (contextualHandler != null) {
        contextualHandler(request, ctx)
      } else {
        handler.handle(request)
      }
      when (result) {
        is RpcResult.Success -> RpcResult.Success(
          json.encodeToJsonElement(responseSerializer, result.data),
        )
        is RpcResult.Failure -> result
      }
    }
  }

  internal fun lookup(path: String): PathDispatcher? = dispatchers[path]

  internal fun registeredPaths(): Set<String> = dispatchers.keys.toSet()
}

/**
 * Per-connection context shared across all RPCs on the same WebSocket. Long-running push
 * tasks (frame producers, log subscribers) attach to [pushScope] so they're auto-cancelled
 * when the socket closes; subscription RPCs use [pushTasks] to track active streams by key
 * (e.g. device id) so a follow-up `Unsubscribe` can find and cancel them.
 *
 * Outgoing frames go through [emit] rather than directly to the socket so the WS-write side
 * is serialized — Ktor requires sends to be single-threaded per session.
 */
class WsSessionContext(
  /** CoroutineScope tied to the socket lifetime. Cancelled in `finally` when the socket closes. */
  val pushScope: CoroutineScope,
  private val outbound: Channel<RpcWsEnvelope>,
) {
  private val pushTasks = mutableMapOf<String, Job>()

  /** Enqueue a frame for the writer coroutine. Suspends if the channel is full. */
  suspend fun emit(envelope: RpcWsEnvelope) {
    outbound.send(envelope)
  }

  /**
   * Replace the currently-running push job for [key] (cancelling the old one if any). The
   * new [job] should be a coroutine launched on [pushScope] that periodically calls [emit].
   */
  fun replacePushTask(key: String, job: Job) {
    pushTasks.remove(key)?.cancel(CancellationException("replaced by new subscription for $key"))
    pushTasks[key] = job
  }

  /** Cancel the push task for [key], if any. Returns true iff a task was cancelled. */
  fun cancelPushTask(key: String): Boolean {
    val job = pushTasks.remove(key) ?: return false
    job.cancel(CancellationException("unsubscribe for $key"))
    return true
  }

  internal fun cancelAllPushTasks() {
    pushTasks.values.forEach { it.cancel(CancellationException("socket closing")) }
    pushTasks.clear()
  }
}

/**
 * Install the multiplexed RPC WebSocket on [route] at [path]. The caller must have already
 * installed the Ktor `WebSockets` plugin on the engine (see `io.ktor.server.websocket.WebSockets`).
 *
 * [setupSession] is invoked once per connected client *after* the [WsSessionContext] is wired
 * up but *before* the inbound-frame loop starts. Use it to push initial events (e.g. "hello"
 * or a one-shot connected-devices snapshot). Defaults to a no-op.
 */
fun Route.registerRpcWebSocket(
  path: String = "/rpc-ws",
  registry: RpcWsHandlerRegistry,
  json: Json,
  setupSession: suspend (WsSessionContext) -> Unit = {},
) {
  webSocket(path) {
    // Outbound channel decouples handler coroutines from the single Ktor writer.
    // Capacity > 1 prevents back-pressure between a frame push and a unrelated request reply.
    val outbound = Channel<RpcWsEnvelope>(capacity = 64)
    val ctx = WsSessionContext(pushScope = this, outbound = outbound)

    // Writer: drains [outbound] onto the socket. Lives on the WS coroutine's scope so it's
    // auto-cancelled when the socket closes.
    val writerJob: Job = launch {
      try {
        for (envelope in outbound) {
          send(json.encodeToString(RpcWsEnvelope.serializer(), envelope))
        }
      } catch (_: CancellationException) {
        // socket closing
      } catch (e: Exception) {
        // Best-effort log; the inbound loop will tear the session down.
        System.err.println("[RpcWsDispatcher] writer error: ${e.message}")
      }
    }

    try {
      setupSession(ctx)

      for (frame in incoming) {
        if (frame !is Frame.Text) continue
        val text = frame.readText()
        val envelope = runCatching {
          json.decodeFromString(RpcWsEnvelope.serializer(), text)
        }.getOrElse { e ->
          // Malformed payload — best we can do is log and ignore. We can't correlate to an id.
          System.err.println("[RpcWsDispatcher] malformed envelope ignored: ${e.message}")
          continue
        }

        when (envelope) {
          is RpcWsEnvelope.Request -> {
            // Launch each request on the WS scope so the read loop keeps draining frames
            // while a long-running RPC (e.g. RunTrailYamlRequest, up to ~5min) executes.
            // Without this, the inbound loop blocks on `handleRequest` and no other request,
            // unsubscribe, or pushed event can flow through this socket for the duration —
            // defeating the whole point of multiplexing. Review feedback on PR #3014.
            launch { handleRequest(envelope, registry, ctx, json) }
          }
          is RpcWsEnvelope.Response,
          is RpcWsEnvelope.Event,
          -> {
            // Clients should never send these; ignore.
          }
        }
      }
    } catch (_: CancellationException) {
      // socket closing — propagate normally
    } catch (e: Exception) {
      System.err.println("[RpcWsDispatcher] inbound error, closing: ${e.message}")
      close(CloseReason(CloseReason.Codes.INTERNAL_ERROR, "dispatcher error"))
    } finally {
      ctx.cancelAllPushTasks()
      outbound.close()
      writerJob.cancel()
    }
  }
}

private suspend fun handleRequest(
  request: RpcWsEnvelope.Request,
  registry: RpcWsHandlerRegistry,
  ctx: WsSessionContext,
  json: Json,
) {
  val dispatcher = registry.lookup(request.path)
  val response: RpcWsEnvelope.Response = if (dispatcher == null) {
    RpcWsEnvelope.Response(
      id = request.id,
      ok = false,
      error = RpcWsError(
        errorType = "UNKNOWN_PATH",
        message = "No WS handler registered for path '${request.path}'",
        details = "Registered paths: ${registry.registeredPaths().sorted()}",
      ),
    )
  } else {
    try {
      when (val result = dispatcher.dispatch(request.body, ctx, json)) {
        is RpcResult.Success -> RpcWsEnvelope.Response(id = request.id, ok = true, body = result.data)
        is RpcResult.Failure -> RpcWsEnvelope.Response(
          id = request.id,
          ok = false,
          error = RpcWsError(
            errorType = result.errorType.name,
            message = result.message,
            details = result.details,
          ),
        )
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      RpcWsEnvelope.Response(
        id = request.id,
        ok = false,
        error = RpcWsError(
          errorType = "UNKNOWN_ERROR",
          message = "Failed to process RPC request",
          details = e.message,
        ),
      )
    }
  }
  ctx.emit(response)
}

/** Convenience: classes that already have an [RpcHandler] HTTP registration use this. */
inline fun <reified TResponse : Any, reified TRequest : RpcRequest<TResponse>> RpcWsHandlerRegistry.also(
  handler: RpcHandler<TRequest, TResponse>,
): RpcWsHandlerRegistry = also { register(handler) }

/**
 * For symmetry with [toRpcPath]. Used by tests that want the registry to expose its
 * registered set without dragging in [KClass] reflection at the call site.
 */
fun Set<KClass<*>>.toRegisteredRpcPaths(): Set<String> = mapTo(mutableSetOf()) { it.toRpcPath() }
