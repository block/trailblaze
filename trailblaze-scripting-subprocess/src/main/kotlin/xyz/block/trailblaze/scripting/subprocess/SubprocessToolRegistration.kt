package xyz.block.trailblaze.scripting.subprocess

import kotlinx.serialization.json.Json
import xyz.block.trailblaze.scripting.mcp.toTrailblazeToolDescriptor
import xyz.block.trailblaze.toolcalls.DynamicTrailblazeToolRegistration
import xyz.block.trailblaze.toolcalls.ToolName
import xyz.block.trailblaze.toolcalls.TrailblazeKoogTool
import xyz.block.trailblaze.toolcalls.TrailblazeKoogTool.Companion.toKoogToolDescriptor
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolDescriptor
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo

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
) : DynamicTrailblazeToolRegistration {

  /**
   * Bundle of the callback-channel wiring a [SubprocessTrailblazeTool] needs at dispatch time.
   * Grouped so the registration constructor stays manageable and so session-startup code can
   * hand this down without re-threading two unrelated parameters everywhere.
   */
  data class JsScriptingCallbackContext(
    val baseUrl: String,
    val toolRepo: TrailblazeToolRepo,
  )

  override val name: ToolName get() = registered.advertisedName

  override val trailblazeDescriptor: TrailblazeToolDescriptor =
    registered.inputSchema.toTrailblazeToolDescriptor(
      name = registered.advertisedName.toolName,
      description = registered.description,
    )

  override fun buildKoogTool(
    trailblazeToolContextProvider: () -> TrailblazeToolExecutionContext,
  ): TrailblazeKoogTool<out TrailblazeTool> {
    // Lenient projection: an MCP server can advertise schema types the LLM-tool descriptor
    // doesn't model today (`array`, `object`, etc.); those fall back to String rather than
    // crashing session startup.
    val descriptor = trailblazeDescriptor.toKoogToolDescriptor(strict = false)
    val serializer = SubprocessToolSerializer(registered.advertisedName, sessionProvider, callbackContext)
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
    val serializer = SubprocessToolSerializer(registered.advertisedName, sessionProvider, callbackContext)
    return Json.decodeFromString(serializer, argumentsJson)
  }
}
