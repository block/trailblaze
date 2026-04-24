package xyz.block.trailblaze.scripting.bundle

import io.modelcontextprotocol.kotlin.sdk.shared.AbstractTransport
import io.modelcontextprotocol.kotlin.sdk.shared.TransportSendOptions
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCMessage
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import xyz.block.trailblaze.util.Console

/**
 * **Internal plumbing — don't touch from outside this module.** MCP transport that
 * ships JSON-RPC messages between the Kotlin MCP Client and a JS Server inside QuickJS.
 * [McpBundleSession] owns one of these; consumers talk to the session's `Client`.
 *
 * ```
 *  Kotlin Client                        JS Server (inside QuickJS)
 *   │  send(msg)  ──────────▶ eval JS ▶│  onmessage(msg)
 *   │                                   │      ↓
 *   │  _onMessage(resp)  ◀─── binding ─┤  transport.send(resp)
 * ```
 *
 * [evalMutex] serializes each Kotlin→JS→response cycle so concurrent `callTool`
 * coroutines queue cleanly (QuickJS is single-threaded anyway).
 */
class InProcessMcpTransport internal constructor(
  private val bridge: QuickJsBridge,
) : AbstractTransport() {

  private val evalMutex = Mutex()

  @Volatile
  private var started: Boolean = false

  init {
    // Wire the JS→Kotlin callback NOW (at construction), not in start(). The session's
    // handshake order is: build bridge → evaluate prelude → evaluate author bundle →
    // construct this transport → client.connect(transport). The bundle's top-level
    // `server.connect(globalThis.__trailblazeInProcessTransport)` fires between transport
    // construction and client.connect, and it races with our callback installation if
    // we wait until start(). Installing here keeps the JS-side `onmessage` handler visible
    // to our delivery path from the first message.
    bridge.onDeliverFromJs { jsonString -> deliverJsonRpcFromJs(jsonString) }
  }

  override suspend fun start() {
    started = true
  }

  override suspend fun send(message: JSONRPCMessage, options: TransportSendOptions?) {
    check(started) { "InProcessMcpTransport.send called before start()" }

    val json = McpJson.encodeToString(JSONRPCMessage.serializer(), message)
    // JSON is almost a subset of JS expression grammar, so a serialized JSON-RPC message
    // usually pastes straight into a JS function-call argument without a `JSON.parse`
    // round-trip. The one exception: U+2028 (LINE SEPARATOR) and U+2029 (PARAGRAPH
    // SEPARATOR). JSON permits them raw inside string values; JS source grammar treats
    // them as line terminators — a raw U+2028 embedded in the expression below would
    // split the string literal at lex time and break dispatch. JSON's `\uXXXX` escape is
    // valid in both JSON and JS string literals, so substituting raw→escape is a 1:1
    // transform that round-trips to the correct character on the JS object-literal parse.
    val jsSafeJson = json.replace(LINE_SEPARATOR, "\\u2028").replace(PARAGRAPH_SEPARATOR, "\\u2029")
    val expression =
      "globalThis.${BundleRuntimePrelude.IN_PROCESS_TRANSPORT}.__deliverFromKotlin($jsSafeJson)"

    evalMutex.withLock {
      try {
        bridge.dispatchToJs(expression)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Throwable) {
        // Surface via onError so Protocol's pending-request map can fail the Continuation
        // with a real exception instead of hanging. Client.send() wraps this in
        // McpException at its boundary; we just propagate.
        Console.log("InProcessMcpTransport: dispatch failed for message $message: ${e.message}")
        runCatching { _onError(e) }
        throw e
      }
    }
  }

  /** Fires when JS calls `__trailblazeDeliverToKotlin`. Decodes + dispatches to `_onMessage`. */
  private suspend fun deliverJsonRpcFromJs(jsonString: String) {
    val message = try {
      McpJson.decodeFromString(JSONRPCMessage.serializer(), jsonString)
    } catch (e: Throwable) {
      // A malformed message from a misbehaving bundle shouldn't crash the transport. Log
      // + onError, drop the message. The LLM caller blocked on the corresponding request
      // will time out at the Protocol layer, which is the right UX: malformed response →
      // author bug → visible timeout with the raw content in logs.
      Console.log("InProcessMcpTransport: failed to decode message from bundle: $jsonString — ${e.message}")
      runCatching { _onError(e) }
      return
    }
    try {
      _onMessage.invoke(message)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Throwable) {
      Console.log("InProcessMcpTransport: _onMessage dispatch failed for $message: ${e.message}")
      runCatching { _onError(e) }
    }
  }

  override suspend fun close() {
    // Fire the JS-side `onclose` hook so author-installed cleanup runs before
    // [McpBundleSession.shutdown] tears down the engine. Best-effort: if the engine is
    // already mid-shutdown or JS throws, swallow — we're on the teardown path and
    // propagating would strand the rest of cleanup. The evalMutex guard lets an in-flight
    // send() finish cleanly before close() fires.
    runCatching {
      evalMutex.withLock {
        bridge.dispatchToJs("globalThis.${BundleRuntimePrelude.IN_PROCESS_TRANSPORT}.close()")
      }
    }
    runCatching { bridge.onDeliverFromJs { /* no-op after close */ } }
    invokeOnCloseCallback()
  }

  private companion object {
    // Named constants because embedding raw U+2028 / U+2029 in a source file is a
    // maintenance hazard — editors display them as invisible whitespace and can mis-render
    // diffs. Named constants make the character identity explicit.
    private const val LINE_SEPARATOR: String = "\u2028"
    private const val PARAGRAPH_SEPARATOR: String = "\u2029"
  }
}
