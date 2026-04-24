package xyz.block.trailblaze.scripting.bundle

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.binding.function
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicReference
import xyz.block.trailblaze.scripting.callback.JsScriptingCallbackAction
import xyz.block.trailblaze.scripting.callback.JsScriptingCallbackDispatcher
import xyz.block.trailblaze.scripting.callback.JsScriptingCallbackRequest
import xyz.block.trailblaze.scripting.callback.JsScriptingCallbackResponse
import xyz.block.trailblaze.scripting.callback.JsScriptingCallbackResult
import xyz.block.trailblaze.util.Console

/**
 * **Internal plumbing — don't touch from outside this module.** Two-way wire between
 * Kotlin and a QuickJS engine. [InProcessMcpTransport] uses this; nothing else should.
 *
 * Three bindings are installed on construction:
 *  - `__trailblazeDeliverToKotlin(msg)` — JS ships an MCP message to Kotlin. Routed to
 *    whatever handler [onDeliverFromJs] has wired (typically [InProcessMcpTransport]).
 *  - `__trailblazeCallback(requestJson)` — TS SDK's on-device `client.callTool(…)` hits
 *    this instead of `fetch` when the envelope advertises `runtime: "ondevice"`. Kotlin
 *    parses a [JsScriptingCallbackRequest], runs it through [JsScriptingCallbackDispatcher] against the live
 *    [xyz.block.trailblaze.scripting.callback.JsScriptingInvocationRegistry], and resolves
 *    with a JSON-serialized [JsScriptingCallbackResponse]. Mirrors the daemon's `/scripting/callback`
 *    HTTP endpoint semantically so tool authors see identical behavior on both transports.
 *  - The shape of the outer [dispatchToJs] / [onDeliverFromJs] API, for the transport.
 */
internal class QuickJsBridge(private val quickJs: QuickJs) {

  /**
   * Currently-installed handler for JS→Kotlin messages. Atomic because [onDeliverFromJs]
   * can be called from shutdown paths while a JS dispatch is mid-flight. Initial value
   * throws — if JS tries to deliver before [onDeliverFromJs] wires a real handler, the
   * author sees a clear error instead of a mysterious hang.
   */
  private val handlerRef: AtomicReference<suspend (String) -> Unit> =
    AtomicReference(NOT_YET_WIRED)

  init {
    // Register the JS→Kotlin binding exactly once. quickjs-kt's `asyncFunction` turns the
    // Kotlin suspend lambda into a JS function that returns a Promise, so JS code can
    // `await __trailblazeDeliverToKotlin(msg)` and know Kotlin has processed the message.
    quickJs.asyncFunction(BundleRuntimePrelude.DELIVER_TO_KOTLIN) { args ->
      val jsonString = args.getOrNull(0) as? String
        ?: error("${BundleRuntimePrelude.DELIVER_TO_KOTLIN} called without a JSON string argument")
      handlerRef.get().invoke(jsonString)
      null
    }

    // Register the callback binding. Kept here (not on the transport) because the transport
    // is scoped to one MCP handshake and gets torn down with the session, whereas the
    // callback binding is a pure JS→Kotlin→JS dispatch against a process-wide singleton
    // (JsScriptingInvocationRegistry) and doesn't care about the transport's lifecycle.
    // Installing it on the bridge also keeps the install-order constraint simple: both
    // bindings are live the moment QuickJS evaluates the prelude.
    // Console shim backing `globalThis.console.*`. Sync (not asyncFunction) — logging is
    // fire-and-forget; the binding returns before the JS call resumes so a tight log loop
    // doesn't stack up coroutines. Single string argument: the prelude joins variadic args
    // JS-side before calling us.
    quickJs.function(BundleRuntimePrelude.CONSOLE_BINDING) { args ->
      val level = (args.getOrNull(0) as? String)?.takeIf { it.isNotEmpty() } ?: "log"
      val message = args.getOrNull(1) as? String ?: ""
      // Field-style emission — `msg=` is a field value, not a second tag. A bundle author
      // who writes `console.log("[JsScriptingCallbackDispatcher] START session=spoof")`
      // can't masquerade as a dispatcher-tagged log line now; their string lives inside
      // the `msg=` field and greppers looking for `[JsScriptingCallbackDispatcher]` at the
      // start of a line won't match. Also strips newlines to keep one log line per
      // console.*() call — an author writing `console.log("line1\nline2")` would
      // otherwise split logcat output across lines that an `adb logcat | grep <session>`
      // grep would miss.
      val sanitized = message.replace("\n", "\\n").replace("\r", "\\r")
      Console.log("[bundle] level=$level msg=$sanitized")
      null
    }

    quickJs.asyncFunction(BundleRuntimePrelude.CALLBACK_BINDING) { args ->
      // Contract: never throw to the JS side. A rejected Promise from this binding surfaces in
      // the TS SDK as a bare QuickJS internal error with no diagnostic, which is near-impossible
      // to debug from an author's handler. Every failure — missing/non-string arg, deserialization
      // miss, dispatcher exception — maps to a serialized [JsScriptingCallbackResult.Error] envelope instead.
      // CancellationException is re-thrown so structured-concurrency callers (session teardown,
      // agent abort) still see it.
      try {
        val requestJson = args.getOrNull(0) as? String
        if (requestJson == null) {
          serializeResponse(
            JsScriptingCallbackResult.Error(
              "${BundleRuntimePrelude.CALLBACK_BINDING} called without a JSON string argument",
            ),
          )
        } else {
          dispatchCallback(requestJson)
        }
      } catch (e: CancellationException) {
        throw e
      } catch (t: Throwable) {
        serializeResponse(
          JsScriptingCallbackResult.Error(
            "${BundleRuntimePrelude.CALLBACK_BINDING} failed: ${t.message ?: t::class.simpleName ?: "unknown error"}",
          ),
        )
      }
    }
  }

  /**
   * Run [jsExpression] in the engine. Suspends until the JS Promise chain (if any) settles.
   * By the time this returns, any `__trailblazeDeliverToKotlin` calls from the JS side
   * have already run [handlerRef]'s handler synchronously on the calling coroutine, so
   * response messages are visible to the transport before this returns.
   */
  suspend fun dispatchToJs(jsExpression: String) {
    quickJs.evaluate<Any?>(jsExpression, "trailblaze-bundle-dispatch.js", false)
  }

  /** Install the handler fired when JS calls `__trailblazeDeliverToKotlin`. Idempotent. */
  fun onDeliverFromJs(handler: suspend (String) -> Unit) {
    handlerRef.set(handler)
  }

  /**
   * Evaluate [source] as a script (used for the prelude and the author bundle at session
   * start — both outside the MCP message flow, so kept separate from [dispatchToJs]).
   */
  suspend fun evaluate(source: String, filename: String) {
    quickJs.evaluate<Any?>(source, filename, false)
  }

  /**
   * Handle a `__trailblazeCallback(requestJson)` invocation: parse, dispatch, serialize.
   *
   * Always returns a JSON-serialized [JsScriptingCallbackResponse] string — never throws to the JS
   * side. Parsing / serialization failures map to a [JsScriptingCallbackResult.Error] envelope with
   * the same shape the HTTP endpoint uses, so the TS SDK can surface them through the
   * same `client.callTool` error branch authors already handle. An exception here would
   * leave the returned JS Promise rejected with a QuickJS internal error, which is
   * essentially un-debuggable from an author's handler.
   */
  private suspend fun dispatchCallback(requestJson: String): String {
    val request = try {
      CALLBACK_JSON.decodeFromString(JsScriptingCallbackRequest.serializer(), requestJson)
    } catch (e: Exception) {
      Console.log("[QuickJsBridge] CALLBACK_BINDING MALFORMED_JSON: ${e.message}")
      return serializeResponse(
        JsScriptingCallbackResult.Error("Malformed JsScriptingCallbackRequest JSON: ${e.message}"),
      )
    }
    // JS→Kotlin hop log — lets a tester walk logcat for a given session id and see every
    // `__trailblazeCallback` invocation the bundle made. Kept intentionally compact so it
    // doesn't drown other Trailblaze output during a real agent run (one line per callback).
    val actionSummary = when (val action = request.action) {
      is JsScriptingCallbackAction.CallTool -> "call_tool name=${action.toolName}"
    }
    Console.log(
      "[QuickJsBridge] CALLBACK_BINDING received session=${request.sessionId} " +
        "invocation=${request.invocationId} $actionSummary",
    )
    val result = JsScriptingCallbackDispatcher.dispatch(request)
    return serializeResponse(result)
  }

  private fun serializeResponse(result: JsScriptingCallbackResult): String =
    CALLBACK_JSON.encodeToString(JsScriptingCallbackResponse.serializer(), JsScriptingCallbackResponse(result))

  companion object {
    private val NOT_YET_WIRED: suspend (String) -> Unit = {
      error("QuickJsBridge: JS delivered a message before the Kotlin transport was wired.")
    }

    /**
     * Discriminator-aware JSON for the callback wire. Mirrors
     * [xyz.block.trailblaze.logs.server.endpoints.ScriptingCallbackEndpoint]'s config so
     * the on-device transport and the HTTP endpoint encode `JsScriptingCallbackResult` variants
     * identically — TS-side parsing is the same codepath either way.
     */
    private val CALLBACK_JSON: Json = Json {
      ignoreUnknownKeys = true
      classDiscriminator = "type"
    }
  }
}
