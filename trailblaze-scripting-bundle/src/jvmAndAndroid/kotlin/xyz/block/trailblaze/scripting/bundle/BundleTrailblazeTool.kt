package xyz.block.trailblaze.scripting.bundle

import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.RequestMeta
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import xyz.block.trailblaze.scripting.callback.JsScriptingCallbackDispatchDepth
import xyz.block.trailblaze.scripting.callback.JsScriptingInvocationRegistry
import xyz.block.trailblaze.scripting.mcp.TrailblazeContextEnvelope
import xyz.block.trailblaze.scripting.mcp.toTrailblazeToolResult
import xyz.block.trailblaze.toolcalls.HostLocalExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.ToolName
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.util.Console

/**
 * The executable form of a bundle tool — what the LLM dispatches when it picks this tool
 * from the registry.
 *
 * [execute] wraps the args with the Trailblaze context envelopes, calls the MCP Client's
 * `callTool`, and maps the response into a `TrailblazeToolResult`. That's it.
 *
 * ### Callback-channel wiring
 *
 * When [callbackContext] is non-null, `execute` also:
 *
 *  1. Registers `(sessionId, toolRepo, context, depth)` with
 *     [JsScriptingInvocationRegistry] before dispatching, so the in-process
 *     `__trailblazeCallback` binding can resolve any `client.callTool(…)` calls the
 *     bundled handler makes back to the same live tool repo + execution context.
 *  2. Stamps `_meta.trailblaze.runtime = "ondevice"` on the `CallToolRequest` so the TS
 *     SDK picks the in-process callback transport instead of HTTP fetch.
 *  3. Closes the registry handle in a finally block so stale invocations can't
 *     accumulate across session teardown or test churn.
 *
 * When [callbackContext] is null, the tool still dispatches — same fallback the
 * subprocess path uses. `_meta.trailblaze` is omitted and any `client.callTool(…)`
 * inside the bundled handler throws the SDK's "no envelope" error at call time.
 */
class BundleTrailblazeTool(
  val sessionProvider: () -> McpBundleSession,
  val advertisedName: ToolName,
  val args: JsonObject,
  /**
   * Per-session callback wiring. Null in tests (or framework paths) that don't exercise
   * the callback channel — in that case the tool still dispatches, but `_meta.trailblaze`
   * is omitted and no invocation is registered.
   */
  private val callbackContext: BundleToolRegistration.JsScriptingCallbackContext? = null,
) : HostLocalExecutableTrailblazeTool {

  override val advertisedToolName: String get() = advertisedName.toolName

  override suspend fun execute(toolExecutionContext: TrailblazeToolExecutionContext): TrailblazeToolResult {
    val legacyEnvelope = TrailblazeContextEnvelope.buildLegacyArgEnvelope(toolExecutionContext)
    val argsWithContext = JsonObject(args + (TrailblazeContextEnvelope.RESERVED_KEY to legacyEnvelope))
    val session = sessionProvider()
    val sessionId = toolExecutionContext.sessionProvider.invoke().sessionId

    // Same register-and-build-inside-one-try shape SubprocessTrailblazeTool uses: a
    // synchronous throw anywhere in envelope construction must still close the registry
    // handle in `finally`, or a leaked entry survives for the process's lifetime.
    var callbackHandle: JsScriptingInvocationRegistry.Handle? = null
    return try {
      callbackHandle = callbackContext?.let {
        // Parent depth is present when this execute() is already inside a callback-dispatched
        // chain (one bundle tool calling another via client.callTool, etc.). Absent = outer
        // invocation from the LLM; register at depth 0.
        val parentDepth = currentCoroutineContext()[JsScriptingCallbackDispatchDepth]?.depth ?: 0
        val handle = JsScriptingInvocationRegistry.register(
          sessionId = sessionId,
          toolRepo = it.toolRepo,
          executionContext = toolExecutionContext,
          depth = parentDepth,
        )
        // Ties a tool invocation to its registry entry in logs — so when the bundle calls
        // `__trailblazeCallback` later, a tester can grep logcat for this invocation id and
        // see every hop (this log → `[QuickJsBridge] CALLBACK_BINDING` → `[JsScriptingCallbackDispatcher]
        // START/END`) without having to infer which session the callback belongs to.
        Console.log(
          "[BundleTrailblazeTool] REGISTERED tool=${advertisedName.toolName} " +
            "session=${sessionId.value} invocation=${handle.invocationId} depth=$parentDepth " +
            "runtime=${TrailblazeContextEnvelope.RUNTIME_ONDEVICE}",
        )
        handle
      }

      val requestMeta = callbackContext?.let {
        val invocationId = requireNotNull(callbackHandle?.invocationId) {
          "callbackHandle must be present when callbackContext is — both are derived together"
        }
        val trailblazeMeta = TrailblazeContextEnvelope.buildMetaTrailblaze(
          context = toolExecutionContext,
          baseUrl = null,
          sessionId = sessionId,
          invocationId = invocationId,
          runtime = TrailblazeContextEnvelope.RUNTIME_ONDEVICE,
        )
        RequestMeta(
          json = buildJsonObject {
            put(TrailblazeContextEnvelope.META_KEY, trailblazeMeta)
          },
        )
      }

      val response = session.client.callTool(
        request = CallToolRequest(
          params = CallToolRequestParams(
            name = advertisedName.toolName,
            arguments = argsWithContext,
            meta = requestMeta,
          ),
        ),
      )
      response.toTrailblazeToolResult()
    } catch (e: CancellationException) {
      // Coroutine cancellation must propagate — swallowing here breaks structured
      // concurrency for session teardown, driver disconnect, agent abort. Catch order
      // matters because CancellationException extends Exception.
      throw e
    } catch (e: Exception) {
      TrailblazeToolResult.Error.ExceptionThrown.fromThrowable(e, this)
    } finally {
      runCatching { callbackHandle?.close() }
    }
  }
}
