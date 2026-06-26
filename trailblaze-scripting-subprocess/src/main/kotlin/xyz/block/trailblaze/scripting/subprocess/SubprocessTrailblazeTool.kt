package xyz.block.trailblaze.scripting.subprocess

import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.RequestMeta
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
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
import java.util.concurrent.TimeUnit

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
    // Resolve ${key}/{{key}} memory tokens in the recorded args before dispatch — same replay-path
    // gap as QuickJsTrailblazeTool (the AI path interpolates upstream; recorded-replay didn't).
    // Idempotent on the AI path. See AgentMemory.interpolateVariablesInJson.
    val resolvedArgs = (toolExecutionContext.memory.interpolateVariablesInJson(args) as? JsonObject) ?: args
    val argsWithContext = JsonObject(resolvedArgs + (TrailblazeContextEnvelope.RESERVED_KEY to legacyEnvelope))
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
      // Apply any `_meta.trailblaze.memoryDelta` (+ memoryDeletions) the handler emitted
      // into the shared `AgentMemory` — but only on a successful result. The TS SDK's
      // `attachMemoryDelta` runs after the handler returns, so an `isError: true` result
      // would otherwise commit partial scratchpad state while a THROW (same conceptual
      // failure) would not. Gating here keeps the failure modes symmetric: both error
      // shapes leave host memory untouched.
      if (response.isError != true) {
        TrailblazeContextEnvelope.applyResultMemoryDelta(toolExecutionContext.memory, response.meta)
      }
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
      // A subprocess that dies mid-dispatch closes its stdout; the MCP transport turns that EOF
      // into a `CONNECTION_CLOSED` that unwinds into this catch. At that instant the OS has
      // often not yet *reaped* the child — it's a zombie whose exit status hasn't been collected
      // — so `process.isAlive` can still read `true` and `exitValue()` still throws. Reading
      // them right now would misclassify a genuine crash as a transient transport failure
      // (`ExceptionThrown`) and drop the exit code from the envelope, which is exactly the
      // post-incident signal a [FatalError] is supposed to carry.
      //
      // Whether the reaper wins that race is a function of host scheduling pressure (the
      // transport's coroutine hops between EOF and here vs. the reaper thread getting a slice),
      // so it is timing-dependent rather than fixed — green on a roomy dev box, flaky-to-red on
      // a constrained CI runner. Blocking briefly for the reap makes the classification and exit
      // code deterministic regardless of host. `waitFor` returns immediately once the child is
      // reaped — the cap is only fully paid on a genuine transient failure where the subprocess
      // really is still running, in which case `isAlive = true` is the correct (delayed) answer
      // and the session keeps dispatching. (The companion stderr race — the transport cancelling
      // its stderr reader on the same EOF — is handled at the source by the session-owned stderr
      // pump in McpSubprocessSession.connect; the drain call below just settles it here.)
      val processExited = withContext(Dispatchers.IO) {
        val exited = try {
          session.spawnedProcess.process.waitFor(CRASH_REAP_GRACE_MS, TimeUnit.MILLISECONDS)
        } catch (interrupted: InterruptedException) {
          // The reap grace is best-effort: if we're interrupted while waiting, restore the
          // interrupt flag (so cancellation still propagates at the next suspension point) and
          // fall back to treating the subprocess as still alive. Letting the InterruptedException
          // escape would replace the original dispatch failure `e` and lose the crash mapping.
          Thread.currentThread().interrupt()
          false
        }
        // Once the child is reaped its stderr pipe is at EOF; let the session-owned stderr pump
        // finish draining the final diagnostic lines before the tail is snapshotted below, so a
        // crash envelope carries the real stderr instead of an empty "(no stderr captured)".
        // Reusing CRASH_REAP_GRACE_MS as the drain bound is deliberate: the pump finishes in well
        // under it once the pipe is at EOF, so the same generous safety cap covers both waits.
        if (exited) session.awaitStderrDrained(CRASH_REAP_GRACE_MS)
        exited
      }
      mapDispatchFailure(
        cause = e,
        toolName = advertisedName.toolName,
        isAlive = !processExited,
        stderrTail = session.stderrCapture.tailSnapshot(),
        exitCode = if (processExited) {
          runCatching { session.spawnedProcess.process.exitValue() }.getOrNull()
        } else {
          null
        },
        tool = this,
      )
    } finally {
      runCatching { callbackHandle?.close() }
    }
  }

  companion object {
    /**
     * Upper bound on how long the dispatch-failure path waits for a dying subprocess to be
     * reaped before deciding whether the failure was a crash ([TrailblazeToolResult.Error.FatalError])
     * or a transient transport error ([TrailblazeToolResult.Error.ExceptionThrown]).
     *
     * A crashed child is already a zombie by the time the transport surfaces the failure, so
     * the reaper collects its status almost immediately and the wait returns well under this
     * cap. The full window is only ever paid on a genuine transient failure where the
     * subprocess is still alive — a rare error path — so a generous bound costs nothing in the
     * common case while staying robust to reaper-scheduling jitter on a loaded CI host.
     */
    internal const val CRASH_REAP_GRACE_MS: Long = 2_000L
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
