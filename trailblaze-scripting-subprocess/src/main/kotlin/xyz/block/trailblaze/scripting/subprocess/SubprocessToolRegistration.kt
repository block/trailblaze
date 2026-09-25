package xyz.block.trailblaze.scripting.subprocess

import io.modelcontextprotocol.kotlin.sdk.types.RequestMeta
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.scripting.callback.JsScriptingInvocationRegistry
import xyz.block.trailblaze.scripting.mcp.TrailblazeContextEnvelope
import xyz.block.trailblaze.scripting.mcp.toTrailblazeToolDescriptor
import xyz.block.trailblaze.toolcalls.DynamicTrailblazeToolRegistration
import xyz.block.trailblaze.toolcalls.ToolName
import xyz.block.trailblaze.toolcalls.TrailblazeKoogTool
import xyz.block.trailblaze.toolcalls.TrailblazeKoogTool.Companion.toKoogToolDescriptor
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolDescriptor
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.toolcalls.TrailblazeToolSourceDescriptor
import java.util.concurrent.atomic.AtomicReference

/**
 * [DynamicTrailblazeToolRegistration] backed by a subprocess-advertised MCP tool.
 *
 * Bridges one [RegisteredSubprocessTool] onto the repo's plug point. Constructed per tool at
 * session start by the caller that spawned the subprocess (the Trailblaze session wiring
 * layer in a later PR, or by fixture tests here). Holds the [sessionProvider] so
 * tool-dispatch time can reach the live [McpSubprocessSession] without coupling the
 * registration record to its owner's lifecycle.
 */
class SubprocessToolRegistration(
  private val registered: RegisteredSubprocessTool,
  private val sessionProvider: () -> McpSubprocessSession,
  /**
   * Per-subprocess dispatch context — plumbed through so `SubprocessTrailblazeTool.execute` can
   * populate `_meta.trailblaze.{baseUrl, invocationId}` and register the invocation in
   * [xyz.block.trailblaze.scripting.callback.JsScriptingInvocationRegistry] for the callback
   * endpoint to resolve back to the live [toolRepo] + context. Optional on the ctor because
   * existing tests exercise registration/lookup without needing a live callback path; when
   * null the tool dispatches without populating the `_meta` envelope or registering.
   */
  private val callbackContext: JsScriptingCallbackContext? = null,
  private val source: TrailblazeToolSourceDescriptor? = null,
) : DynamicTrailblazeToolRegistration {

  // `_meta.trailblaze/surfaceToLlm` is the source of truth for all scripted MCP tools, including
  // SDK-owned hidden composition/finalizer helpers. Dynamic registrations default to true, so
  // failing to forward this bit would leak a hidden subprocess tool into the model's menu.
  override val surfaceToLlm: Boolean get() = registered.meta.surfaceToLlm

  /**
   * Bundle of the callback-channel wiring a [SubprocessTrailblazeTool] needs at dispatch time.
   * Grouped so the registration constructor stays manageable and so session-startup code can
   * hand this down without re-threading two unrelated parameters everywhere.
   */
  data class JsScriptingCallbackContext(
    val baseUrl: String,
    val toolRepo: TrailblazeToolRepo,
  ) {
    private val latestExecutionContext = AtomicReference<TrailblazeToolExecutionContext?>(null)

    /**
     * Retain only the current session's latest dispatch context. Session finalization needs a
     * fresh callback invocation after the acquiring tool's own registry handle has closed; any
     * resource in this subprocess necessarily came from one of these dispatches.
     */
    fun recordExecutionContext(context: TrailblazeToolExecutionContext) {
      latestExecutionContext.set(context)
    }

    /** Opens a live callback invocation for the hidden session-finalizer MCP call. */
    fun openFinalizerInvocation(sessionId: SessionId): FinalizerInvocation? {
      val context = latestExecutionContext.get() ?: return null
      val handle = JsScriptingInvocationRegistry.register(
        sessionId = sessionId,
        toolRepo = toolRepo,
        executionContext = context,
        depth = 0,
      )
      val meta = TrailblazeContextEnvelope.buildMetaTrailblaze(
        context = context,
        baseUrl = baseUrl,
        sessionId = sessionId,
        invocationId = handle.invocationId,
      )
      return FinalizerInvocation(
        handle = handle,
        requestMeta = RequestMeta(json = buildJsonObject {
          put(TrailblazeContextEnvelope.META_KEY, meta)
        }),
      )
    }

    /** Drops the session-owned context after all subprocess finalizers have run. */
    fun clearExecutionContext() {
      latestExecutionContext.set(null)
    }
  }

  data class FinalizerInvocation(
    val handle: JsScriptingInvocationRegistry.Handle,
    val requestMeta: RequestMeta,
  )

  override val name: ToolName get() = registered.advertisedName

  override val trailblazeDescriptor: TrailblazeToolDescriptor =
    registered.inputSchema.toTrailblazeToolDescriptor(
      name = registered.advertisedName.toolName,
      description = registered.description,
    ).copy(source = source)

  override fun buildKoogTool(
    trailblazeToolContextProvider: () -> TrailblazeToolExecutionContext,
  ): TrailblazeKoogTool<out TrailblazeTool> {
    // Lenient projection: an MCP server can advertise schema types the LLM-tool descriptor
    // doesn't model today (`array`, `object`, etc.); those fall back to String rather than
    // crashing session startup.
    val descriptor = trailblazeDescriptor.toKoogToolDescriptor(strict = false)
    val serializer = SubprocessToolSerializer(
      registered.advertisedName,
      sessionProvider,
      callbackContext,
      registered.meta.sensitiveArgs,
    )
    return TrailblazeKoogTool(
      argsSerializer = serializer,
      descriptor = descriptor,
      executeTool = { args: SubprocessTrailblazeTool ->
        val context = trailblazeToolContextProvider()
        val result = args.execute(context)
        "Executed subprocess tool: ${registered.advertisedName.toolName} — result: $result"
      },
    )
  }

  override fun decodeToolCall(argumentsJson: String): TrailblazeTool {
    val serializer = SubprocessToolSerializer(
      registered.advertisedName,
      sessionProvider,
      callbackContext,
      registered.meta.sensitiveArgs,
    )
    return Json.decodeFromString(serializer, argumentsJson)
  }
}
