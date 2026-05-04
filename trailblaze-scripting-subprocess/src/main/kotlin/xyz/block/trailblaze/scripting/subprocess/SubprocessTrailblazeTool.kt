package xyz.block.trailblaze.scripting.subprocess

import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.RequestMeta
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import xyz.block.trailblaze.scripting.callback.JsScriptingCallbackDispatchDepth
import xyz.block.trailblaze.scripting.callback.JsScriptingInvocationRegistry
import xyz.block.trailblaze.scripting.mcp.TrailblazeContextEnvelope
import xyz.block.trailblaze.scripting.mcp.toTrailblazeToolResult
import xyz.block.trailblaze.toolcalls.HostLocalExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.RawArgumentTrailblazeTool
import xyz.block.trailblaze.toolcalls.ToolName
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult

/**
 * [ExecutableTrailblazeTool] adapter for a single subprocess-advertised MCP tool.
 *
 * At LLM-invocation time the Koog dispatch layer deserializes the LLM's tool-call `arguments`
 * JSON into a [SubprocessTrailblazeTool] bound to (session, advertised name, args) via
 * [SubprocessToolSerializer]. `execute` then:
 *
 *  1. Generates a fresh `invocationId` and registers `(sessionId, toolRepo, context)` with
 *     [JsScriptingInvocationRegistry] so the `/scripting/callback` endpoint can resolve it.
 *  2. Builds both envelopes — the legacy `_trailblazeContext` argument key (read by raw-SDK
 *     tools) AND the `_meta.trailblaze` object (read by `@trailblaze/scripting`).
 *  3. Dispatches `tools/call` on the session's MCP client, passing `_meta` on the request.
 *  4. Closes the registry handle in a finally block so stale invocations can't accumulate.
 *  5. Maps the [io.modelcontextprotocol.kotlin.sdk.types.CallToolResult] per conventions § 3.
 *
 * Transport / serialization failures surface as [TrailblazeToolResult.Error.ExceptionThrown] —
 * the handler inside the subprocess has its own error shape (`isError: true`), which
 * [toTrailblazeToolResult] handles separately.
 */
class SubprocessTrailblazeTool(
  val sessionProvider: () -> McpSubprocessSession,
  val advertisedName: ToolName,
  val args: JsonObject,
  /**
   * Per-subprocess callback wiring. Null in existing test paths that don't exercise the
   * callback channel — in that case the tool still dispatches, but the `_meta.trailblaze`
   * envelope is omitted and no invocation is registered. The legacy `_trailblazeContext` arg
   * is always injected regardless so raw-SDK authors' tools work either way.
   *
   * Private so that a hold of a `SubprocessTrailblazeTool` instance doesn't give a caller
   * reach-through access to the embedded `TrailblazeToolRepo`. Tests that need to observe the
   * wiring construct through the public ctor with a known value.
   */
  private val callbackContext: SubprocessToolRegistration.JsScriptingCallbackContext? = null,
) : HostLocalExecutableTrailblazeTool, RawArgumentTrailblazeTool {

  override val advertisedToolName: String get() = advertisedName.toolName
  override val instanceToolName: String get() = advertisedName.toolName
  override val rawToolArguments: JsonObject get() = args

  override suspend fun execute(toolExecutionContext: TrailblazeToolExecutionContext): TrailblazeToolResult {
    val legacyEnvelope = TrailblazeContextEnvelope.buildLegacyArgEnvelope(toolExecutionContext)
    val argsWithContext = JsonObject(args + (TrailblazeContextEnvelope.RESERVED_KEY to legacyEnvelope))
    val session = sessionProvider()
    val sessionId = toolExecutionContext.sessionProvider.invoke().sessionId

    // Register-and-build inside a single try so a synchronous throw anywhere in the setup
    // (register, envelope construction, RequestMeta build) still closes the registry handle
    // in the finally. An earlier revision had `register(...)` outside the try, which leaked
    // the registry entry for the daemon's lifetime if `buildMetaTrailblaze` or `buildJsonObject`
    // threw — flagged by the lead-dev review.
    var callbackHandle: JsScriptingInvocationRegistry.Handle? = null
    return try {
      callbackHandle = callbackContext?.let {
        // Read the reentrance depth from the current coroutine context — present only when this
        // execute() is running inside a `/scripting/callback`-dispatched tool chain. Absent for
        // outer `tools/call` invocations originating from the LLM, which are depth 0.
        val parentDepth = currentCoroutineContext()[JsScriptingCallbackDispatchDepth]?.depth ?: 0
        JsScriptingInvocationRegistry.register(
          sessionId = sessionId,
          toolRepo = it.toolRepo,
          executionContext = toolExecutionContext,
          depth = parentDepth,
        )
      }

      // Build `_meta.trailblaze` when we have a baseUrl to advertise. `_meta` on
      // `CallToolRequestParams` (MCP SDK 0.9.0) is a `RequestMeta` — a value class wrapping a
      // free-form `JsonObject`, so vendor-namespaced keys like `trailblaze` ride alongside
      // SDK-reserved ones (`progressToken` etc.). Verified round-trip on tools/call in devlog
      // 2026-04-22-scripting-sdk-envelope-migration.md.
      val requestMeta = callbackContext?.let { ctx ->
        val invocationId = requireNotNull(callbackHandle?.invocationId) {
          "callbackHandle must be present when callbackContext is — both are derived together"
        }
        val trailblazeMeta = TrailblazeContextEnvelope.buildMetaTrailblaze(
          context = toolExecutionContext,
          baseUrl = ctx.baseUrl,
          sessionId = sessionId,
          invocationId = invocationId,
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
      // Coroutine cancellation must propagate — swallowing it here would break structured
      // concurrency for session teardown, driver disconnect, or agent abort. The catch
      // order matters: CancellationException extends Exception, so removing this branch
      // or reordering with the generic Exception catch would silently re-introduce the
      // bug. Dedicated integration coverage lands with the session-startup PR where a
      // real in-flight callTool can be cancelled end-to-end.
      throw e
    } catch (e: Exception) {
      mapDispatchFailure(
        cause = e,
        toolName = advertisedName.toolName,
        isAlive = session.isAlive,
        stderrTail = session.stderrCapture.tailSnapshot(),
        exitCode = runCatching { session.spawnedProcess.process.exitValue() }.getOrNull(),
        tool = this,
      )
    } finally {
      runCatching { callbackHandle?.close() }
    }
  }
}

/**
 * Pure mapping from a `tools/call` dispatch failure onto a [TrailblazeToolResult]. Extracted
 * from [SubprocessTrailblazeTool.execute] so the crash-aware vs. transient branching can be
 * unit-tested without spawning a real subprocess.
 *
 * - `isAlive == false` → [TrailblazeToolResult.Error.FatalError] with a crash message that
 *   includes the subprocess exit code, underlying cause, and captured stderr tail.
 * - `isAlive == true`  → [TrailblazeToolResult.Error.ExceptionThrown], treating the failure
 *   as a transient transport / protocol-level error. The subprocess is still up, so the
 *   session can keep dispatching other tools.
 */
internal fun mapDispatchFailure(
  cause: Exception,
  toolName: String,
  isAlive: Boolean,
  stderrTail: List<String>,
  exitCode: Int?,
  tool: TrailblazeTool,
): TrailblazeToolResult = if (!isAlive) {
  TrailblazeToolResult.Error.FatalError(
    errorMessage = buildCrashMessage(toolName, cause, stderrTail, exitCode),
    stackTraceString = cause.stackTraceToString(),
  )
} else {
  TrailblazeToolResult.Error.ExceptionThrown.fromThrowable(cause, tool)
}

private fun buildCrashMessage(
  toolName: String,
  cause: Exception,
  stderrTail: List<String>,
  exitCode: Int?,
): String {
  val tailBlock = if (stderrTail.isEmpty()) {
    "(no stderr captured)"
  } else {
    stderrTail.joinToString(separator = "\n")
  }
  val exitLine = exitCode?.let { "exit code: $it" } ?: "exit code: (unavailable)"
  // `cause.message` is null for a lot of low-level failures (e.g. EOF from the transport);
  // anonymous `object : RuntimeException()` instances also report `null` for ::class.simpleName.
  // `javaClass.name` always resolves to a non-null FQN so the "cause:" line never prints null.
  val causeText = cause.message?.takeIf { it.isNotBlank() } ?: cause.javaClass.name
  return buildString {
    appendLine("Subprocess MCP server died while dispatching tool '$toolName'.")
    appendLine(exitLine)
    appendLine("cause: $causeText")
    appendLine("tail stderr:")
    append(tailBlock)
  }
}

/**
 * Custom [KSerializer] that turns the LLM's tool-call `arguments` JSON into a bound
 * [SubprocessTrailblazeTool] instance.
 *
 * One serializer per registered tool. Captures the tool's name + a session lookup so
 * deserialization yields an instance that can dispatch back through the right subprocess.
 */
class SubprocessToolSerializer(
  private val advertisedName: ToolName,
  private val sessionProvider: () -> McpSubprocessSession,
  private val callbackContext: SubprocessToolRegistration.JsScriptingCallbackContext? = null,
) : KSerializer<SubprocessTrailblazeTool> {

  override val descriptor: SerialDescriptor = buildClassSerialDescriptor("subprocess:${advertisedName.toolName}")

  override fun deserialize(decoder: Decoder): SubprocessTrailblazeTool {
    val jsonDecoder = decoder as? JsonDecoder
      ?: error("SubprocessToolSerializer requires JSON decoding (got ${decoder::class.simpleName}).")
    val argsElement = jsonDecoder.decodeJsonElement()
    val args = argsElement as? JsonObject ?: JsonObject(emptyMap())
    return SubprocessTrailblazeTool(
      sessionProvider = sessionProvider,
      advertisedName = advertisedName,
      args = args,
      callbackContext = callbackContext,
    )
  }

  override fun serialize(encoder: Encoder, value: SubprocessTrailblazeTool) {
    val jsonEncoder = encoder as? JsonEncoder
      ?: error("SubprocessToolSerializer requires JSON encoding (got ${encoder::class.simpleName}).")
    jsonEncoder.encodeJsonElement(value.args)
  }
}
