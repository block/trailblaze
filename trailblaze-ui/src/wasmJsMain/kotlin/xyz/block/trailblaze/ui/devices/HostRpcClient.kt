package xyz.block.trailblaze.ui.devices

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.browser.window
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.host.rpc.ConnectToDeviceRequest
import xyz.block.trailblaze.host.rpc.ConnectToDeviceResponse
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
import xyz.block.trailblaze.host.rpc.ws.SubscribeFramesResponse
import xyz.block.trailblaze.host.rpc.ws.UnsubscribeFramesResponse
import xyz.block.trailblaze.mcp.android.ondevice.rpc.RpcRequest.Companion.toRpcPath
import xyz.block.trailblaze.util.Console

/**
 * Daemon host-RPC client used by the web `/devices` viewer.
 *
 * **Transport.** WebSocket-first: the first call lazily opens `ws://<origin>/rpc-ws` and
 * multiplexes all subsequent RPCs over that single connection. If the socket fails to
 * open (older daemon, proxy that strips WS upgrades, etc.) the client transparently falls
 * back to the legacy per-call HTTP POST routes that the daemon still registers alongside.
 * Per-call: WS path on success, HTTP path on WS failure.
 *
 * **Wire format on the WS path.** See [RpcWsEnvelope]. Each request/response is a JSON
 * text frame; the daemon dispatches on the `path` field (same `/rpc/<SimpleClassName>`
 * convention the HTTP routes use). Server-pushed [FrameEvent]s arrive on [frameEvents]
 * — see [RemoteDeviceScreenStream] for the consumer that replaces the old 200 ms HTTP poll.
 *
 * **Wire format on the HTTP fallback.** Identical to before this change:
 * - 2xx response: body is the raw `TResponse` JSON.
 * - 5xx response: body is an `RpcErrorResponse` JSON.
 *
 * Default [baseUrl] is the page's own origin so the viewer works regardless of how the
 * daemon is reached (localhost, LAN IP, preview proxy).
 */
class HostRpcClient(
  private val baseUrl: String = window.location.origin,
) {

  /**
   * Last failure message seen from either transport. Set as a side-effect so the UI can
   * surface the daemon's specific error after a method returns null. The web viewer runs
   * RPCs sequentially per click, so racy reads across concurrent calls aren't a concern.
   */
  var lastErrorMessage: String? = null
    private set

  private val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
  }

  @Serializable
  private data class RpcErrorResponse(
    val errorType: String? = null,
    val message: String,
    val details: String? = null,
  )

  private val httpClient = HttpClient {
    install(ContentNegotiation) { json(json) }
  }

  /**
   * Live WS-backed delegate. Lazy because instantiating the underlying [HttpClient] with
   * the [io.ktor.client.plugins.websocket.WebSockets] plugin is non-trivial and we want
   * the cost only if the page actually needs it.
   *
   * Stored as an explicit [Lazy] so [close] can branch on `isInitialized()` and skip
   * touching the WS client (and its lazy [HttpClient]) when nothing ever used it.
   */
  private val wsClientLazy: Lazy<HostRpcWsClient> = lazy { HostRpcWsClient(baseUrl) }
  private val wsClient: HostRpcWsClient by wsClientLazy

  /**
   * Per-process flag: once a WS attempt has failed for this client instance, give up and
   * use HTTP only. Avoids retrying the WS handshake on every call when the daemon doesn't
   * support it. Cleared when the page is reloaded.
   */
  private var wsBroken: Boolean = false

  /**
   * Flow of server-pushed events received over the WS socket. Today only `/event/frame`
   * ([FrameEvent]) is emitted; future event types (recording-log entries, device state)
   * will arrive on the same flow with a different [RpcWsEnvelope.Event.path] discriminator.
   *
   * Empty (cold) if the WS transport has been disabled (i.e. fell back to HTTP for any
   * reason) — callers that need streaming should treat an empty stream as "feature
   * unavailable" rather than "no events yet".
   */
  val events: Flow<RpcWsEnvelope.Event> get() = wsClient.events

  /**
   * Release every resource this client owns: the always-eager [httpClient] and the
   * lazy [wsClient] (only if it was actually initialized). The WS close cancels the
   * read loop, fails any pending requests, and tears down its own embedded Ktor
   * [HttpClient]. Safe to call multiple times — subsequent RPC calls after close
   * will throw or fail-soft via the existing `try`/`null-return` paths.
   *
   * Composable callers (e.g. `WebDevicesGridPage`) must call this from a coroutine
   * scope that outlives composition — `rememberCoroutineScope` cancels alongside
   * `onDispose`, which would defeat the cleanup.
   */
  fun close() {
    if (wsClientLazy.isInitialized()) {
      wsClientLazy.value.close()
    }
    httpClient.close()
  }

  // -----------------------------------------------------------------------
  // Public RPC surface (unchanged signatures — callers don't see the transport)
  // -----------------------------------------------------------------------

  suspend fun getConnectedDevices(): GetConnectedDevicesResponse? =
    rpcCall(GetConnectedDevicesRequest, serializer(), serializer()) { wsClient.getConnectedDevices() }

  suspend fun connectToDevice(trailblazeDeviceId: TrailblazeDeviceId): ConnectToDeviceResponse? =
    rpcCall(ConnectToDeviceRequest(trailblazeDeviceId), serializer(), serializer()) {
      wsClient.connectToDevice(trailblazeDeviceId)
    }

  suspend fun getHostDeviceScreen(
    trailblazeDeviceId: TrailblazeDeviceId,
    includeTree: Boolean = false,
  ): GetHostDeviceScreenResponse? =
    rpcCall(
      GetHostDeviceScreenRequest(trailblazeDeviceId, includeTree = includeTree),
      serializer(),
      serializer(),
    ) {
      wsClient.getHostDeviceScreen(trailblazeDeviceId, includeTree = includeTree)
    }

  suspend fun sendInteraction(
    trailblazeDeviceId: TrailblazeDeviceId,
    interaction: xyz.block.trailblaze.host.rpc.DeviceInteraction,
  ): DeviceInteractionResponse? =
    rpcCall(DeviceInteractionRequest(trailblazeDeviceId, interaction), serializer(), serializer()) {
      wsClient.sendInteraction(trailblazeDeviceId, interaction)
    }

  suspend fun disconnectDevice(trailblazeDeviceId: TrailblazeDeviceId): DisconnectDeviceResponse? =
    rpcCall(DisconnectDeviceRequest(trailblazeDeviceId), serializer(), serializer()) {
      wsClient.disconnectDevice(trailblazeDeviceId)
    }

  suspend fun getTargetApps(): GetTargetAppsResponse? =
    rpcCall(GetTargetAppsRequest, serializer(), serializer()) { wsClient.getTargetApps() }

  suspend fun setCurrentTargetApp(targetAppId: String): SetCurrentTargetAppResponse? =
    rpcCall(SetCurrentTargetAppRequest(targetAppId), serializer(), serializer()) {
      wsClient.setCurrentTargetApp(targetAppId)
    }

  suspend fun navigateWebUrl(
    trailblazeDeviceId: TrailblazeDeviceId,
    url: String,
  ): NavigateWebUrlResponse? =
    rpcCall(NavigateWebUrlRequest(trailblazeDeviceId, url), serializer(), serializer()) {
      wsClient.navigateWebUrl(trailblazeDeviceId, url)
    }

  suspend fun getToolCatalog(driverType: TrailblazeDriverType): GetToolCatalogResponse? =
    rpcCall(GetToolCatalogRequest(driverType), serializer(), serializer()) {
      wsClient.getToolCatalog(driverType)
    }

  suspend fun runTrailYaml(
    trailblazeDeviceId: TrailblazeDeviceId,
    yaml: String,
  ): RunTrailYamlResponse? =
    rpcCall(RunTrailYamlRequest(trailblazeDeviceId, yaml), serializer(), serializer()) {
      wsClient.runTrailYaml(trailblazeDeviceId, yaml)
    }

  /**
   * WS-only: ask the daemon to start pushing [FrameEvent]s for [trailblazeDeviceId].
   * Returns null on failure (sets [lastErrorMessage]); callers should fall back to
   * polling [getHostDeviceScreen] when this is null. The actual frame events arrive on
   * [events]; see [RemoteDeviceScreenStream] for the consumer.
   */
  suspend fun subscribeFrames(
    trailblazeDeviceId: TrailblazeDeviceId,
    intervalMs: Long = 200,
  ): SubscribeFramesResponse? {
    if (wsBroken) return null
    // Match [rpcCall]'s discipline: a thrown exception is a transport-level failure (flip
    // [wsBroken] and let the caller fall back to HTTP polling); a null return is a *domain*
    // failure (e.g. "Device not connected" before connect completes) and must NOT trip
    // [wsBroken] — otherwise one transient daemon-side rejection permanently degrades the
    // page to HTTP polling, regressing every later RPC for the rest of the page session.
    // Review feedback on PR #3014 caught this regression.
    val response = try {
      wsClient.subscribeFrames(trailblazeDeviceId, intervalMs)
    } catch (e: Exception) {
      Console.log("[HostRpcClient] subscribeFrames WS path threw: ${e.message}, marking broken")
      wsBroken = true
      lastErrorMessage = e.message ?: e::class.simpleName
      return null
    }
    if (response == null) {
      val wsErr = wsClient.lastErrorMessage
      if (wsErr != null && wsErr.contains("WebSocket connect failed", ignoreCase = true)) {
        wsBroken = true
        Console.log("[HostRpcClient] subscribeFrames: WS transport unavailable, will fall back to HTTP")
      } else if (wsErr != null) {
        // Real domain RPC failure (device not connected, etc) — surface it but keep WS healthy.
        lastErrorMessage = wsErr
      }
    } else {
      lastErrorMessage = null
    }
    return response
  }

  suspend fun unsubscribeFrames(
    trailblazeDeviceId: TrailblazeDeviceId,
  ): UnsubscribeFramesResponse? {
    if (wsBroken) return null
    return wsClient.unsubscribeFrames(trailblazeDeviceId)
  }

  // -----------------------------------------------------------------------
  // Internal: WS-first, HTTP-fallback dispatch
  // -----------------------------------------------------------------------

  private suspend fun <TRequest : Any, TResponse : Any> rpcCall(
    request: TRequest,
    requestSerializer: KSerializer<TRequest>,
    responseSerializer: KSerializer<TResponse>,
    wsCall: suspend () -> TResponse?,
  ): TResponse? {
    if (!wsBroken) {
      val wsResult = try {
        wsCall()
      } catch (e: Exception) {
        Console.log("[HostRpcClient] WS path threw: ${e.message}, marking broken")
        null
      }
      if (wsResult != null) {
        lastErrorMessage = null
        return wsResult
      }
      // wsClient updates its own lastErrorMessage. If the WS surface itself reports an
      // error, surface it (so the UI sees the daemon's specific reason) but don't trip
      // wsBroken — a domain-level "Device not connected" is a real error, not a
      // transport problem. Only flip wsBroken when the WS underlying transport is
      // unreachable, which `wsClient` reports as a connect-failed message.
      val wsErr = wsClient.lastErrorMessage
      if (wsErr != null && wsErr.contains("WebSocket connect failed", ignoreCase = true)) {
        wsBroken = true
        Console.log("[HostRpcClient] WS transport unavailable, falling back to HTTP")
      } else if (wsErr != null) {
        // Real RPC failure — don't fall back to HTTP (the daemon already said no).
        lastErrorMessage = wsErr
        return null
      }
    }
    return httpRpcCall(request, requestSerializer, responseSerializer)
  }

  private suspend fun <TRequest : Any, TResponse : Any> httpRpcCall(
    request: TRequest,
    requestSerializer: KSerializer<TRequest>,
    responseSerializer: KSerializer<TResponse>,
  ): TResponse? {
    val path = request::class.toRpcPath()
    val url = "$baseUrl$path"
    return try {
      val response: HttpResponse = httpClient.post(url) {
        contentType(ContentType.Application.Json)
        setBody(json.encodeToString(requestSerializer, request))
      }
      val responseBody = response.body<String>()
      if (response.status.isSuccess()) {
        lastErrorMessage = null
        json.decodeFromString(responseSerializer, responseBody)
      } else {
        val parsedMessage = runCatching {
          json.decodeFromString(RpcErrorResponse.serializer(), responseBody).message
        }.getOrNull() ?: responseBody.take(300)
        lastErrorMessage = parsedMessage
        Console.log("[HostRpcClient] HTTP ${response.status.value} for $path: $parsedMessage")
        null
      }
    } catch (e: Exception) {
      lastErrorMessage = e.message ?: e::class.simpleName
      Console.log("[HostRpcClient] Exception calling $url: ${e.message}")
      null
    }
  }
}
