package xyz.block.trailblaze.host.rpc.ws

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Wire format for the multiplexed `/rpc-ws` WebSocket. Every text frame on the socket is
 * a JSON-encoded [RpcWsEnvelope] message.
 *
 * The transport is intentionally minimal:
 *  - **Request**  → server: [Request]  (client-originated, expects exactly one [Response]).
 *  - **Response** ← server: [Response] (server-originated, correlates by [Request.id]).
 *  - **Event**    ← server: [Event]    (server-originated, no `id` correlation; identified by [Event.path]).
 *
 * The `path` field uses the same `/rpc/<SimpleClassName>` convention the HTTP routes do
 * ([xyz.block.trailblaze.mcp.android.ondevice.rpc.RpcRequest.Companion.toRpcPath]), so the
 * server's WS dispatcher can re-use the existing `RpcHandler` registrations keyed by path.
 *
 * The HTTP `/rpc/...` POST routes remain registered alongside this socket. Both transports
 * accept the same `TRequest` JSON body and produce the same `TResponse` JSON. The WS path
 * is preferred by the web viewer because it (a) eliminates per-call HTTP overhead and
 * (b) supports server-pushed [Event] frames (e.g. live device frames replacing the
 * 200 ms `GetHostDeviceScreenRequest` poll).
 */
@Serializable
sealed interface RpcWsEnvelope {

  /**
   * Client → server. Asks the daemon to invoke the handler registered for [path] with [body]
   * as the request JSON. The server responds with a single [Response] whose `id` matches.
   */
  @Serializable
  @kotlinx.serialization.SerialName("request")
  data class Request(
    /** Client-generated correlation id (any unique string per outstanding call). */
    val id: String,
    /** RPC path, e.g. `/rpc/GetConnectedDevicesRequest`. */
    val path: String,
    /** Request payload — same JSON the HTTP POST body carries. */
    val body: JsonElement,
  ) : RpcWsEnvelope

  /**
   * Server → client. Correlates to a [Request] by [id]. Carries either the success [body]
   * or a structured [error]; exactly one is non-null.
   */
  @Serializable
  @kotlinx.serialization.SerialName("response")
  data class Response(
    val id: String,
    val ok: Boolean,
    /** Response payload on success (same JSON the HTTP 2xx body carries). */
    val body: JsonElement? = null,
    /** Error envelope on failure (same shape the HTTP 5xx body uses). */
    val error: RpcWsError? = null,
  ) : RpcWsEnvelope

  /**
   * Server → client. Unsolicited push of a typed event. The [path] discriminates the body
   * (e.g. `/event/frame` for [xyz.block.trailblaze.host.rpc.ws.FrameEvent]). No id.
   *
   * Clients subscribe / unsubscribe by sending a regular [Request] for one of the
   * `Subscribe...` / `Unsubscribe...` RPC types — the server then begins / stops emitting
   * these events on the same socket.
   */
  @Serializable
  @kotlinx.serialization.SerialName("event")
  data class Event(
    val path: String,
    val body: JsonElement,
  ) : RpcWsEnvelope
}

/**
 * Mirrors the HTTP `RpcErrorResponse` body. Duplicated here (rather than reused from
 * `trailblaze-compose`) because models live in commonMain and the HTTP variant is JVM-only.
 */
@Serializable
data class RpcWsError(
  val errorType: String,
  val message: String,
  val details: String? = null,
)
