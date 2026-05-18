package xyz.block.trailblaze.ui.devices

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.url
import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.browser.window
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.serializer
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.host.rpc.ConnectToDeviceRequest
import xyz.block.trailblaze.host.rpc.ConnectToDeviceResponse
import xyz.block.trailblaze.host.rpc.DeviceInteraction
import xyz.block.trailblaze.host.rpc.DeviceInteractionRequest
import xyz.block.trailblaze.host.rpc.DeviceInteractionResponse
import xyz.block.trailblaze.host.rpc.DisconnectDeviceRequest
import xyz.block.trailblaze.host.rpc.DisconnectDeviceResponse
import xyz.block.trailblaze.host.rpc.GetConnectedDevicesRequest
import xyz.block.trailblaze.host.rpc.GetConnectedDevicesResponse
import xyz.block.trailblaze.host.rpc.GetHostDeviceScreenRequest
import xyz.block.trailblaze.host.rpc.GetHostDeviceScreenResponse
import xyz.block.trailblaze.host.rpc.GetTargetAppsRequest
import xyz.block.trailblaze.host.rpc.GetTargetAppsResponse
import xyz.block.trailblaze.host.rpc.GetToolCatalogRequest
import xyz.block.trailblaze.host.rpc.GetToolCatalogResponse
import xyz.block.trailblaze.host.rpc.NavigateWebUrlRequest
import xyz.block.trailblaze.host.rpc.NavigateWebUrlResponse
import xyz.block.trailblaze.host.rpc.RunTrailYamlRequest
import xyz.block.trailblaze.host.rpc.RunTrailYamlResponse
import xyz.block.trailblaze.host.rpc.SetCurrentTargetAppRequest
import xyz.block.trailblaze.host.rpc.SetCurrentTargetAppResponse
import xyz.block.trailblaze.host.rpc.ws.FrameEvent
import xyz.block.trailblaze.host.rpc.ws.RpcWsEnvelope
import xyz.block.trailblaze.host.rpc.ws.SubscribeFramesRequest
import xyz.block.trailblaze.host.rpc.ws.SubscribeFramesResponse
import xyz.block.trailblaze.host.rpc.ws.UnsubscribeFramesRequest
import xyz.block.trailblaze.host.rpc.ws.UnsubscribeFramesResponse
import xyz.block.trailblaze.mcp.android.ondevice.rpc.RpcRequest
import xyz.block.trailblaze.mcp.android.ondevice.rpc.RpcRequest.Companion.toRpcPath
import xyz.block.trailblaze.util.Console
import kotlin.random.Random

/**
 * Multiplexed WebSocket transport for the daemon's host RPC API. Mirrors the surface of
 * [HostRpcClient] (same method names, same return types) so callers can swap implementations
 * with a one-line change.
 *
 * **Why a separate client?** The HTTP variant remains useful for non-Compose callers (CLI,
 * MCP, curl smoke-tests) and as a fallback if the daemon ever disables the WS endpoint, so we
 * deliberately keep both in the codebase. The web `/devices` viewer prefers this one because:
 *   - The 200 ms `GetHostDeviceScreenRequest` poll loop is replaced with [subscribeFrames]
 *     which receives server-pushed [FrameEvent]s.
 *   - Eliminating HTTP per-call overhead makes gestures feel snappier and drops the
 *     daemon-side log volume dramatically.
 *
 * **Connection lifecycle.** The first RPC call lazily opens the socket via [ensureConnected].
 * A reader coroutine demultiplexes incoming envelopes: responses ([RpcWsEnvelope.Response])
 * complete the matching pending [CompletableDeferred] by id; events ([RpcWsEnvelope.Event])
 * flow through [frameEvents]. [close] cancels the scope and shuts the socket down.
 */
class HostRpcWsClient(
  /**
   * Base URL of the page (e.g. `http://localhost:52525`). We replace the scheme to derive
   * `ws://` / `wss://` automatically so the viewer works regardless of how the daemon is
   * reached.
   */
  private val baseUrl: String = window.location.origin,
) {

  /** See [HostRpcClient.lastErrorMessage] — same semantics, set as a side-effect of failures. */
  var lastErrorMessage: String? = null
    private set

  private val json: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
  }

  private val httpClient: HttpClient = HttpClient {
    install(WebSockets)
  }

  private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  /**
   * Currently-open WS session, or null if not yet connected. Re-opened on demand by
   * [ensureConnected]; never reused after a close (the reader coroutine clears it).
   */
  private var session: DefaultWebSocketSession? = null
  private var readerJob: Job? = null

  /**
   * Serializes [ensureConnected] so two coroutines racing into the first RPC (e.g. concurrent
   * component mounts on initial page load) can't both observe `session == null`, both suspend
   * on the handshake, and both store back — which would leak the loser's socket and orphan
   * its [pending] map / [readerJob] reference. wasmJs is single-threaded, but `webSocketSession`
   * suspends, so cooperative concurrency alone still races without explicit mutual exclusion.
   * Review feedback on PR #3014 caught this.
   */
  private val connectMutex = Mutex()

  /**
   * Map of outstanding request ids to the deferred awaiting their response. Capacity is
   * unbounded — the daemon answers every request promptly under normal conditions, and any
   * abandoned ids are removed in [close] when the scope is cancelled.
   */
  private val pending: MutableMap<String, CompletableDeferred<RpcWsEnvelope.Response>> =
    mutableMapOf()

  /**
   * Shared flow of every server-pushed [RpcWsEnvelope.Event] received. Today only
   * `/event/frame` ([FrameEvent]) flows here; future event types (recording-log entries,
   * device-state changes) will follow the same path.
   *
   * Replay = 0: subscribers only see events that arrive *after* they collect. Buffer = 16:
   * one slow consumer doesn't slow other consumers, but a runaway emit will start dropping
   * the oldest event (better than an unbounded queue under sustained backpressure).
   */
  private val _events: MutableSharedFlow<RpcWsEnvelope.Event> =
    MutableSharedFlow(replay = 0, extraBufferCapacity = 16)
  val events: Flow<RpcWsEnvelope.Event> get() = _events.asSharedFlow()

  // -----------------------------------------------------------------------
  // Public RPC surface — mirrors HostRpcClient method-by-method
  // -----------------------------------------------------------------------

  suspend fun getConnectedDevices(): GetConnectedDevicesResponse? =
    call(GetConnectedDevicesRequest, serializer(), serializer())

  suspend fun connectToDevice(trailblazeDeviceId: TrailblazeDeviceId): ConnectToDeviceResponse? =
    call(ConnectToDeviceRequest(trailblazeDeviceId), serializer(), serializer())

  /**
   * Single-shot screenshot fetch — kept for parity with the HTTP path and for callers that
   * only need an occasional capture (e.g. snapshots, exports). The live frame stream uses
   * [subscribeFrames] instead.
   */
  suspend fun getHostDeviceScreen(
    trailblazeDeviceId: TrailblazeDeviceId,
    includeTree: Boolean = false,
  ): GetHostDeviceScreenResponse? =
    call(
      GetHostDeviceScreenRequest(trailblazeDeviceId, includeTree = includeTree),
      serializer(),
      serializer(),
    )

  suspend fun sendInteraction(
    trailblazeDeviceId: TrailblazeDeviceId,
    interaction: DeviceInteraction,
  ): DeviceInteractionResponse? =
    call(DeviceInteractionRequest(trailblazeDeviceId, interaction), serializer(), serializer())

  suspend fun disconnectDevice(trailblazeDeviceId: TrailblazeDeviceId): DisconnectDeviceResponse? =
    call(DisconnectDeviceRequest(trailblazeDeviceId), serializer(), serializer())

  suspend fun getTargetApps(): GetTargetAppsResponse? =
    call(GetTargetAppsRequest, serializer(), serializer())

  suspend fun setCurrentTargetApp(targetAppId: String): SetCurrentTargetAppResponse? =
    call(SetCurrentTargetAppRequest(targetAppId), serializer(), serializer())

  suspend fun navigateWebUrl(trailblazeDeviceId: TrailblazeDeviceId, url: String): NavigateWebUrlResponse? =
    call(NavigateWebUrlRequest(trailblazeDeviceId, url), serializer(), serializer())

  suspend fun getToolCatalog(driverType: TrailblazeDriverType): GetToolCatalogResponse? =
    call(GetToolCatalogRequest(driverType), serializer(), serializer())

  suspend fun runTrailYaml(trailblazeDeviceId: TrailblazeDeviceId, yaml: String): RunTrailYamlResponse? =
    call(RunTrailYamlRequest(trailblazeDeviceId, yaml), serializer(), serializer())

  /**
   * Ask the daemon to start pushing [FrameEvent]s for [trailblazeDeviceId] over the WS.
   * Returns null on failure (sets [lastErrorMessage]). The actual frames arrive on [events];
   * collect them, filter by [FrameEvent.EVENT_PATH], and decode the body to [FrameEvent].
   *
   * Sending [SubscribeFramesRequest] twice for the same device replaces the server-side
   * producer (no leak). Always pair with [unsubscribeFrames] on teardown.
   */
  suspend fun subscribeFrames(
    trailblazeDeviceId: TrailblazeDeviceId,
    intervalMs: Long = 200,
  ): SubscribeFramesResponse? =
    call(SubscribeFramesRequest(trailblazeDeviceId, intervalMs), serializer(), serializer())

  suspend fun unsubscribeFrames(trailblazeDeviceId: TrailblazeDeviceId): UnsubscribeFramesResponse? =
    call(UnsubscribeFramesRequest(trailblazeDeviceId), serializer(), serializer())

  fun close() {
    pending.values.forEach { it.completeExceptionally(IllegalStateException("WS client closed")) }
    pending.clear()
    readerJob?.cancel()
    scope.cancel()
    session = null
  }

  // -----------------------------------------------------------------------
  // Internals
  // -----------------------------------------------------------------------

  private suspend fun <TRequest : RpcRequest<TResponse>, TResponse : Any> call(
    request: TRequest,
    requestSerializer: KSerializer<TRequest>,
    responseSerializer: KSerializer<TResponse>,
  ): TResponse? {
    val s = try {
      ensureConnected()
    } catch (e: Exception) {
      lastErrorMessage = "WebSocket connect failed: ${e.message}"
      Console.log("[HostRpcWsClient] $lastErrorMessage")
      return null
    }

    val id = nextId()
    val deferred = CompletableDeferred<RpcWsEnvelope.Response>()
    pending[id] = deferred

    return try {
      val envelope = RpcWsEnvelope.Request(
        id = id,
        path = request::class.toRpcPath(),
        body = json.encodeToJsonElement(requestSerializer, request),
      )
      s.send(json.encodeToString(RpcWsEnvelope.serializer(), envelope))

      // Time-bound the wait so a stuck server doesn't deadlock the UI. Default 30s matches
      // the worst-case `RunTrailYamlRequest` HTTP timeout the HTTP client tolerates today.
      val response = withTimeout(request.requestTimeoutMs ?: DEFAULT_REQUEST_TIMEOUT_MS) {
        deferred.await()
      }
      if (response.ok && response.body != null) {
        lastErrorMessage = null
        json.decodeFromJsonElement(responseSerializer, response.body!!)
      } else {
        lastErrorMessage = response.error?.message ?: "Unknown RPC failure"
        Console.log("[HostRpcWsClient] RPC ${envelope.path} failed: $lastErrorMessage")
        null
      }
    } catch (e: Exception) {
      lastErrorMessage = e.message ?: e::class.simpleName
      Console.log("[HostRpcWsClient] call ${request::class.simpleName} exception: ${e.message}")
      null
    } finally {
      pending.remove(id)
    }
  }

  /**
   * Open the socket if it's not already up. [connectMutex] serializes the handshake so two
   * coroutines racing in can't both open a socket and clobber [session]. The first-arrival
   * fast-path (`session?.let { return it }`) avoids the lock when the connection is already
   * established. If the previous socket died, the reader coroutine has cleared [session]
   * before releasing the mutex; we transparently re-open inside the critical section.
   */
  private suspend fun ensureConnected(): DefaultWebSocketSession {
    session?.let { return it }
    return connectMutex.withLock {
      // Re-check inside the lock: another coroutine may have opened the socket while we
      // were waiting for the mutex.
      session?.let { return@withLock it }
      val wsUrl = httpToWs(baseUrl) + "/rpc-ws"
      val newSession = httpClient.webSocketSession { url(wsUrl) }
      session = newSession
      readerJob = scope.launch { readLoop(newSession) }
      newSession
    }
  }

  private suspend fun readLoop(s: DefaultWebSocketSession) {
    try {
      for (frame in s.incoming) {
        if (frame !is Frame.Text) continue
        val envelope = try {
          json.decodeFromString(RpcWsEnvelope.serializer(), frame.readText())
        } catch (e: Exception) {
          Console.log("[HostRpcWsClient] malformed envelope: ${e.message}")
          continue
        }
        when (envelope) {
          is RpcWsEnvelope.Response -> {
            pending.remove(envelope.id)?.complete(envelope)
              ?: Console.log("[HostRpcWsClient] response for unknown id ${envelope.id}")
          }
          is RpcWsEnvelope.Event -> {
            // tryEmit (not emit) so a slow collector doesn't block the reader; the buffer
            // capacity drops the oldest event if backpressure kicks in.
            _events.tryEmit(envelope)
          }
          is RpcWsEnvelope.Request -> {
            // Server should never send requests; ignore.
          }
        }
      }
    } catch (e: Exception) {
      Console.log("[HostRpcWsClient] read loop ended: ${e.message}")
    } finally {
      // Drop the stale session; the next call() will re-open. Outstanding deferreds are
      // failed so callers see a clean error rather than hanging forever.
      session = null
      val snapshot = pending.toMap()
      pending.clear()
      snapshot.values.forEach { it.completeExceptionally(IllegalStateException("WebSocket closed")) }
    }
  }

  private fun nextId(): String = "${idCounter.incrementAndGet()}-${Random.nextInt().toString(16)}"

  private companion object {
    /** Default per-call timeout on the WS path. Matches the HTTP client's worst-case budget. */
    private const val DEFAULT_REQUEST_TIMEOUT_MS: Long = 30_000

    /** Replace `http(s)://` with `ws(s)://` so the daemon's CORS policy is satisfied. */
    private fun httpToWs(url: String): String = when {
      url.startsWith("https://") -> "wss://" + url.removePrefix("https://")
      url.startsWith("http://") -> "ws://" + url.removePrefix("http://")
      else -> url // already a ws/wss scheme
    }
  }
}

/**
 * Minimal incrementing counter for request ids. We don't need true uniqueness across
 * client instances — the daemon scopes the pending-id map per socket session.
 */
private object idCounter {
  private var value: Int = 0
  fun incrementAndGet(): Int = ++value
}
