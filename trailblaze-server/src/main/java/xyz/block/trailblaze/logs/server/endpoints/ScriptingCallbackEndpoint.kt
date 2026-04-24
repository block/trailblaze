package xyz.block.trailblaze.logs.server.endpoints

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.post
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import xyz.block.trailblaze.scripting.callback.JsScriptingCallbackDispatcher
import xyz.block.trailblaze.scripting.callback.JsScriptingCallbackRequest
import xyz.block.trailblaze.scripting.callback.JsScriptingCallbackResponse
import xyz.block.trailblaze.scripting.callback.JsScriptingCallbackResult
import xyz.block.trailblaze.scripting.callback.JsScriptingInvocationRegistry
import xyz.block.trailblaze.util.Console

/**
 * `/scripting/callback` endpoint — subprocess MCP tools authored with `@trailblaze/scripting`
 * hit this from inside their handler to compose other Trailblaze tools. JSON-first per the
 * envelope-migration devlog; proto is deliberately deferred.
 *
 * Request / response shapes live in
 * [xyz.block.trailblaze.scripting.callback.JsScriptingCallbackRequest] /
 * [xyz.block.trailblaze.scripting.callback.JsScriptingCallbackResponse]. The endpoint:
 *
 *  1. Rejects non-loopback callers — the daemon binds to all interfaces, but this endpoint
 *     can dispatch arbitrary Trailblaze tools against live sessions. Same pattern
 *     [ReverseProxyEndpoint] uses for the reverse-proxy path.
 *  2. Parses the JSON body as a [JsScriptingCallbackRequest]. Malformed body → 400 with error text.
 *  3. Looks up `invocationId` in [JsScriptingInvocationRegistry] AND validates that the
 *     entry's `sessionId` matches the request's claimed session — mismatch →
 *     [JsScriptingCallbackResult.Error] (NOT silent dispatch against someone else's session, per the
 *     documented invariant).
 *  4. Dispatches the action via the invocation's [TrailblazeToolRepo] + execution context,
 *     maps the [TrailblazeToolResult] onto the wire-compatible [JsScriptingCallbackResult.CallToolResult].
 *
 * **Staging note.** The endpoint ships before any subprocess sends a [JsScriptingCallbackRequest] —
 * the TS SDK callback surface lands in a follow-up. Shipping the endpoint first means the
 * SDK landing is a straight client-side change without touching the daemon.
 */
object ScriptingCallbackEndpoint {

  /** Explicit `Json` instance so response content-type stays JSON regardless of route defaults. */
  private val json: Json = Json {
    ignoreUnknownKeys = true
    classDiscriminator = "type"
  }

  /**
   * Default per-callback dispatch timeout, in milliseconds. Bounded so a buggy tool that never
   * returns can't wedge the MCP session indefinitely — the outer agent timeout would eventually
   * surface it, but at a much longer horizon and with a less actionable error. 30 s was picked
   * for the client-callTool landing as "comfortably above a realistic tap/wait-for-settle
   * sequence, comfortably below the outer agent timeout." Override via
   * [CALLBACK_TIMEOUT_MS_PROPERTY] (for example, `-Dtrailblaze.callback.timeoutMs=<ms>`) when
   * a specific test or deployment needs a different bound.
   */
  // Keep in lockstep with `McpSubprocessSpawner.resolveClientFetchTimeoutMs` — the spawner
  // reads `trailblaze.callback.timeoutMs` and forwards (value + 2 s buffer) to the subprocess
  // as `TRAILBLAZE_CLIENT_FETCH_TIMEOUT_MS` so the daemon-side dispatch timeout and the
  // client-side fetch timeout stay synchronized. Changing this default without updating the
  // spawner's default breaks the "override both together" invariant.
  const val DEFAULT_CALLBACK_TIMEOUT_MS: Long = 30_000L

  /**
   * Default maximum `JsScriptingCallbackRequest` body size accepted by the endpoint, in bytes. A callback
   * payload is a small JSON envelope (invocation id, session id, a single action with a
   * JSON-string args field); 1 MB is comfortably above any realistic tool-args payload and
   * comfortably below anything that would threaten daemon memory. The cap exists to stop a buggy
   * subprocess (infinite loop emitting a giant args string) from OOM-ing the daemon before the
   * dispatch timeout even fires — loopback-gated, so the attacker model is "accidental bug on the
   * host," not "remote attacker."
   */
  const val DEFAULT_CALLBACK_MAX_BODY_BYTES: Long = 1_048_576L

  /**
   * System property (`-Dtrailblaze.callback.maxBodyBytes=...`) overriding
   * [DEFAULT_CALLBACK_MAX_BODY_BYTES]. Same resolution pattern as the timeout / depth knobs —
   * unset / non-numeric / non-positive falls through to the default.
   */
  const val CALLBACK_MAX_BODY_BYTES_PROPERTY: String = "trailblaze.callback.maxBodyBytes"

  /**
   * Visible-for-test. Resolves the effective request-body size cap from
   * [CALLBACK_MAX_BODY_BYTES_PROPERTY], falling back to [DEFAULT_CALLBACK_MAX_BODY_BYTES] on
   * unset, non-numeric, or non-positive values.
   */
  internal fun resolveMaxBodyBytes(): Long {
    val raw = System.getProperty(CALLBACK_MAX_BODY_BYTES_PROPERTY) ?: return DEFAULT_CALLBACK_MAX_BODY_BYTES
    return raw.toLongOrNull()?.takeIf { it > 0 } ?: DEFAULT_CALLBACK_MAX_BODY_BYTES
  }

  /**
   * Loopback addresses accepted by this endpoint. IPv4 and IPv6 numeric forms plus the literal
   * "localhost" hostname (Ktor `testApplication` reports the latter). Anything else is
   * rejected with 403 before the body is read. Tracks [ReverseProxyEndpoint]'s set, extended
   * with "localhost" so the testApplication path exercises the endpoint without a special
   * bypass flag — the whole point of the gate is testable in production configuration.
   *
   * `internal` (rather than `private`) so [isLoopback] can be unit-tested directly without
   * injecting a fake `ApplicationCall` — testApplication always reports loopback, so the
   * non-loopback rejection branch would otherwise go uncovered. Not part of the public API.
   */
  internal val LOOPBACK_ADDRESSES = setOf("127.0.0.1", "::1", "0:0:0:0:0:0:0:1", "localhost")

  internal fun isLoopback(address: String): Boolean =
    address in LOOPBACK_ADDRESSES || address.startsWith("127.")

  fun register(routing: Routing) = with(routing) {
    val timeoutMs = JsScriptingCallbackDispatcher.resolveTimeoutMs()
    val maxDepth = JsScriptingCallbackDispatcher.resolveMaxDepth()
    val maxBodyBytes = resolveMaxBodyBytes()
    post("/scripting/callback") {
      // Loopback gate — the daemon binds to all interfaces (::), so without this a remote
      // caller on the same network could POST `/scripting/callback` and dispatch arbitrary
      // tools against whatever session is currently in flight. Subprocess tool scripts run on
      // the host alongside the daemon; legitimate callers are always loopback.
      val remoteAddress = call.request.local.remoteAddress
      if (!isLoopback(remoteAddress)) {
        Console.log("[scripting/callback] BLOCKED non-loopback request from $remoteAddress")
        call.respond(
          HttpStatusCode.Forbidden,
          "Scripting callback is only available from loopback (got $remoteAddress)",
        )
        return@post
      }

      // Reject oversized payloads before buffering. A buggy subprocess emitting a giant JSON args
      // string could OOM the daemon in the `call.receive<String>()` below — this check fires on
      // the declared Content-Length, so a client that lies about the header (or omits it entirely
      // via chunked transfer) bypasses the cap. That's acceptable: subprocesses are trusted (they
      // run on the host, already have write access to the process), and Ktor's own transport-level
      // limits apply to chunked bodies. The check catches the realistic "accidental OOM" class.
      val declaredContentLength = call.request.headers["Content-Length"]?.toLongOrNull()
      if (declaredContentLength != null && declaredContentLength > maxBodyBytes) {
        Console.log(
          "[scripting/callback] REJECTED body size $declaredContentLength > max $maxBodyBytes " +
            "from ${call.request.local.remoteAddress}",
        )
        call.respond(
          HttpStatusCode.PayloadTooLarge,
          "JsScriptingCallbackRequest body size $declaredContentLength exceeds max $maxBodyBytes bytes",
        )
        return@post
      }

      val bodyText = try {
        call.receive<String>()
      } catch (e: Exception) {
        Console.log("[scripting/callback] Failed to read body: ${e.message}")
        call.respond(HttpStatusCode.BadRequest, "Failed to read request body: ${e.message}")
        return@post
      }

      val request: JsScriptingCallbackRequest = try {
        json.decodeFromString(JsScriptingCallbackRequest.serializer(), bodyText)
      } catch (e: SerializationException) {
        // Malformed request envelope (missing fields, wrong types) — respond 400. Using 400
        // rather than a 200 + JsScriptingCallbackResult.Error because this is a protocol-level framing
        // error, not a dispatch failure the subprocess can branch on. The subprocess's HTTP
        // client should surface it as a thrown error.
        Console.log("[scripting/callback] Malformed request: ${e.message}")
        call.respond(HttpStatusCode.BadRequest, "Malformed JsScriptingCallbackRequest JSON: ${e.message}")
        return@post
      }

      // Core dispatch (version / registry lookup / session match / depth gate / timeout /
      // TrailblazeToolResult mapping) lives in [JsScriptingCallbackDispatcher] so the on-device
      // QuickJS binding in `:trailblaze-scripting-bundle` produces identical semantics. This
      // endpoint keeps only the HTTP-specific concerns around it (loopback gate, body-size
      // cap, content-type, JSON framing).
      val result = JsScriptingCallbackDispatcher.dispatch(
        request = request,
        maxDepth = maxDepth,
        timeoutMs = timeoutMs,
      )
      respondResult(result)
    }
  }

  private suspend fun io.ktor.server.routing.RoutingContext.respondResult(result: JsScriptingCallbackResult) {
    // Serialize the response ourselves so the route doesn't depend on whatever
    // ContentNegotiation config is (or isn't) installed. The endpoint is a narrow JSON
    // contract; responding with explicit text keeps that contract stable.
    val body = json.encodeToString(JsScriptingCallbackResponse.serializer(), JsScriptingCallbackResponse(result))
    call.respondText(body, ContentType.Application.Json, HttpStatusCode.OK)
  }
}
