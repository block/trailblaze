package xyz.block.trailblaze.scripting.callback

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.util.Console

/**
 * Shared core for dispatching a `tools/call`-style callback against the live
 * [JsScriptingInvocationRegistry]. Two call sites wrap this:
 *
 *  - [xyz.block.trailblaze.logs.server.endpoints.ScriptingCallbackEndpoint] — the daemon's
 *    HTTP `/scripting/callback` endpoint for subprocess-spawned `@trailblaze/scripting`
 *    tools. Adds HTTP-specific concerns around this core (loopback gate, body-size cap,
 *    JSON framing).
 *  - The on-device `__trailblazeCallback` QuickJS binding in `:trailblaze-scripting-bundle`
 *    — calls directly into this dispatcher with no HTTP layer.
 *
 * Keeping the session-match check, depth gate, timeout, and [TrailblazeToolResult] mapping
 * in one place guarantees both transports produce identical semantics. Otherwise drift
 * between host-subprocess and on-device behavior would surface as debugging misery for
 * tool authors whose handlers are supposed to run identically in both modes.
 */
object JsScriptingCallbackDispatcher {

  /**
   * Resolve [request] against the registry and dispatch its action. Never throws
   * [Throwable] except for [CancellationException] — every other failure (unknown
   * invocation, session mismatch, depth exceeded, deserialization failure, dispatch
   * timeout, tool exception) maps to a [JsScriptingCallbackResult] variant so callers can forward
   * uniformly. Cancellation propagates because structured-concurrency callers (session
   * teardown, agent abort) must observe it.
   *
   * Default [maxDepth] / [timeoutMs] resolve from the same system properties the HTTP
   * endpoint's knobs point at ([CALLBACK_MAX_DEPTH_PROPERTY], [CALLBACK_TIMEOUT_MS_PROPERTY]),
   * falling back to the shared constants on unset/invalid values. This keeps the HTTP
   * (daemon) and in-process (on-device) transports honoring the same `-D` overrides — a
   * deployment that bumps the timeout for the subprocess path automatically gets the same
   * timeout on-device without a second configuration surface.
   */
  suspend fun dispatch(
    request: JsScriptingCallbackRequest,
    maxDepth: Int = resolveMaxDepth(),
    timeoutMs: Long = resolveTimeoutMs(),
  ): JsScriptingCallbackResult {
    val actionSummary = when (val action = request.action) {
      is JsScriptingCallbackAction.CallTool -> "call_tool name=${action.toolName}"
    }
    // START log — one per dispatch, tagged with session + invocation so every hop can be
    // correlated via `grep <sessionId>` in logcat/daemon logs. Covers both transports (HTTP
    // endpoint and in-process binding hit this same function).
    Console.log(
      "[JsScriptingCallbackDispatcher] START session=${request.sessionId} invocation=${request.invocationId} $actionSummary",
    )

    if (request.version != JsScriptingCallbackRequest.CURRENT_VERSION) {
      Console.log(
        "[JsScriptingCallbackDispatcher] VERSION_UNSUPPORTED received=${request.version} " +
          "expected=${JsScriptingCallbackRequest.CURRENT_VERSION} session=${request.sessionId}",
      )
      return JsScriptingCallbackResult.Error(
        "Unsupported callback version ${request.version}; this runtime speaks v${JsScriptingCallbackRequest.CURRENT_VERSION}",
      )
    }

    val entry = JsScriptingInvocationRegistry.lookup(request.invocationId)
    if (entry == null) {
      Console.log(
        "[JsScriptingCallbackDispatcher] INVOCATION_NOT_FOUND invocation=${request.invocationId} session=${request.sessionId}",
      )
      return JsScriptingCallbackResult.Error(
        "Invocation ${request.invocationId} not found or no longer in flight",
      )
    }

    if (entry.sessionId.value != request.sessionId) {
      // Matches the HTTP endpoint's invariant: never silently dispatch against the
      // wrong session even if the invocation id was legitimately obtained elsewhere.
      Console.log(
        "[JsScriptingCallbackDispatcher] SESSION_MISMATCH request_session=${request.sessionId} " +
          "entry_session=${entry.sessionId.value} invocation=${request.invocationId}",
      )
      return JsScriptingCallbackResult.Error(
        "Session mismatch: invocation ${request.invocationId} belongs to a different session than '${request.sessionId}'",
      )
    }

    if (entry.depth >= maxDepth) {
      Console.log(
        "[JsScriptingCallbackDispatcher] REJECTED depth ${entry.depth} >= max $maxDepth for invocation " +
          "${request.invocationId} (session ${request.sessionId})",
      )
      return JsScriptingCallbackResult.CallToolResult(
        success = false,
        errorMessage = "Callback reentrance depth ${entry.depth} reached max $maxDepth; " +
          "refusing further dispatch.",
      )
    }

    val result = when (val action = request.action) {
      is JsScriptingCallbackAction.CallTool -> dispatchCallTool(entry, action, timeoutMs)
    }
    // END log — pairs with START so a tester can confirm a full round-trip happened. Result
    // summary is a one-liner; detailed error branches already log above at the point of
    // failure (DESERIALIZE_FAILED, TIMEOUT, NON_EXECUTABLE).
    val resultSummary = when (result) {
      is JsScriptingCallbackResult.CallToolResult ->
        if (result.success) "call_tool_result success=true" else "call_tool_result success=false"
      is JsScriptingCallbackResult.Error -> "error"
    }
    Console.log(
      "[JsScriptingCallbackDispatcher] END session=${request.sessionId} invocation=${request.invocationId} $resultSummary",
    )
    return result
  }

  /**
   * Default per-dispatch timeout. Overridable via the [CALLBACK_TIMEOUT_MS_PROPERTY] system
   * property. 30s matches
   * [xyz.block.trailblaze.logs.server.endpoints.ScriptingCallbackEndpoint.DEFAULT_CALLBACK_TIMEOUT_MS].
   */
  const val DEFAULT_DISPATCH_TIMEOUT_MS: Long = 30_000L

  /**
   * System property (`-Dtrailblaze.callback.timeoutMs=...`) overriding
   * [DEFAULT_DISPATCH_TIMEOUT_MS]. Read per-dispatch so tests can flip it via
   * `System.setProperty` without re-constructing the dispatcher. Shared across both
   * transports so a single `-D` override tunes HTTP and on-device dispatch together.
   */
  const val CALLBACK_TIMEOUT_MS_PROPERTY: String = "trailblaze.callback.timeoutMs"

  /**
   * System property (`-Dtrailblaze.callback.maxDepth=...`) overriding
   * [JsScriptingInvocationRegistry.MAX_CALLBACK_DEPTH]. Same resolution semantics as
   * [CALLBACK_TIMEOUT_MS_PROPERTY]: unset / non-numeric / non-positive falls through to
   * the default.
   */
  const val CALLBACK_MAX_DEPTH_PROPERTY: String = "trailblaze.callback.maxDepth"

  /**
   * Resolves the effective dispatch timeout. Unset / non-numeric / non-positive values fall
   * back to [DEFAULT_DISPATCH_TIMEOUT_MS] — the tradeoff is "silently default on typo" vs.
   * "refuse to start", and defaulting keeps dispatch serving. Callers that want to see
   * invalid configuration should validate separately.
   */
  fun resolveTimeoutMs(): Long {
    val raw = System.getProperty(CALLBACK_TIMEOUT_MS_PROPERTY) ?: return DEFAULT_DISPATCH_TIMEOUT_MS
    return raw.toLongOrNull()?.takeIf { it > 0 } ?: DEFAULT_DISPATCH_TIMEOUT_MS
  }

  /**
   * Resolves the effective reentrance cap. Same fall-through-on-typo tradeoff as
   * [resolveTimeoutMs].
   */
  fun resolveMaxDepth(): Int {
    val raw = System.getProperty(CALLBACK_MAX_DEPTH_PROPERTY)
      ?: return JsScriptingInvocationRegistry.MAX_CALLBACK_DEPTH
    return raw.toIntOrNull()?.takeIf { it > 0 } ?: JsScriptingInvocationRegistry.MAX_CALLBACK_DEPTH
  }

  private suspend fun dispatchCallTool(
    entry: JsScriptingInvocationRegistry.Entry,
    action: JsScriptingCallbackAction.CallTool,
    timeoutMs: Long,
  ): JsScriptingCallbackResult {
    val tool = try {
      entry.toolRepo.toolCallToTrailblazeTool(action.toolName, action.argumentsJson)
    } catch (e: Exception) {
      Console.log(
        "[JsScriptingCallbackDispatcher] DESERIALIZE_FAILED tool '${action.toolName}' in session " +
          "${entry.sessionId.value}: ${e.message}",
      )
      return JsScriptingCallbackResult.CallToolResult(
        success = false,
        errorMessage = "Failed to deserialize tool call '${action.toolName}': ${e.message}",
      )
    }

    if (tool !is ExecutableTrailblazeTool) {
      Console.log(
        "[JsScriptingCallbackDispatcher] NON_EXECUTABLE tool '${action.toolName}' in session " +
          "${entry.sessionId.value} (decoded as ${tool::class.simpleName})",
      )
      return JsScriptingCallbackResult.CallToolResult(
        success = false,
        errorMessage = "Tool '${action.toolName}' is not executable from a scripting callback",
      )
    }

    return try {
      // [withTimeout] bounds dispatch so a tool that never returns can't wedge the session.
      // [withContext(JsScriptingCallbackDispatchDepth)] stamps the parent depth onto the coroutine so
      // any child invocation (SubprocessTrailblazeTool / BundleTrailblazeTool) registers
      // itself at `entry.depth + 1` and the depth gate catches a runaway recursive chain
      // at the next callback, not the one after it.
      withTimeout(timeoutMs) {
        withContext(JsScriptingCallbackDispatchDepth(entry.depth + 1)) {
          when (val result = tool.execute(entry.executionContext)) {
            is TrailblazeToolResult.Success ->
              JsScriptingCallbackResult.CallToolResult(
                success = true,
                textContent = result.message.orEmpty(),
              )
            is TrailblazeToolResult.Error ->
              JsScriptingCallbackResult.CallToolResult(
                success = false,
                errorMessage = result.errorMessage,
              )
          }
        }
      }
    } catch (e: TimeoutCancellationException) {
      // Must be caught before generic CancellationException — TimeoutCancellationException
      // IS a CancellationException, but it's scoped to this withTimeout and is ours to
      // translate, not propagate. Same ordering rationale as SubprocessTrailblazeTool.execute.
      Console.log(
        "[JsScriptingCallbackDispatcher] TIMEOUT tool '${action.toolName}' after ${timeoutMs}ms " +
          "(session ${entry.sessionId.value}, depth ${entry.depth})",
      )
      JsScriptingCallbackResult.CallToolResult(
        success = false,
        errorMessage = "Tool '${action.toolName}' callback dispatch timed out after ${timeoutMs}ms",
      )
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      JsScriptingCallbackResult.CallToolResult(
        success = false,
        errorMessage = "Tool '${action.toolName}' threw while executing: ${e.message}",
      )
    }
  }
}
