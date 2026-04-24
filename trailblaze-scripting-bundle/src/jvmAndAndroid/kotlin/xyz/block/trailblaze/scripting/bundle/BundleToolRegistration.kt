package xyz.block.trailblaze.scripting.bundle

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
 * One bundle-advertised tool's entry in the session's `TrailblazeToolRepo`.
 *
 * Every tool the LLM can see corresponds to one of these in the repo. The launcher builds
 * one per surviving tool after [BundleToolFilter] runs, then hands the batch to
 * `TrailblazeToolRepo.addDynamicTools`. From that point the repo treats bundle tools the
 * same as any other dynamic registration — nothing downstream (Koog, the agent loop)
 * knows or cares that the backing handler lives inside QuickJS.
 */
class BundleToolRegistration(
  private val registered: RegisteredBundleTool,
  private val sessionProvider: () -> McpBundleSession,
  /**
   * Per-session callback wiring. Non-null in production paths where the bundle runtime
   * has access to the session's [TrailblazeToolRepo] (for the in-process callback
   * binding to look up composed tools). Null in unit tests and fixture paths that just
   * exercise the transport — in that case the tool still dispatches, but
   * `_meta.trailblaze` is omitted and any `client.callTool(…)` from the bundled handler
   * throws the SDK's "no envelope" error. Mirrors the subprocess
   * `SubprocessToolRegistration.JsScriptingCallbackContext` shape; see that class for the design
   * rationale.
   */
  private val callbackContext: JsScriptingCallbackContext? = null,
) : DynamicTrailblazeToolRegistration {

  /**
   * Bundle of the callback-channel wiring a [BundleTrailblazeTool] needs at dispatch time.
   *
   * Unlike [xyz.block.trailblaze.scripting.subprocess.SubprocessToolRegistration.JsScriptingCallbackContext],
   * there is **no `baseUrl` field** — the on-device transport is the in-process QuickJS
   * binding (`__trailblazeCallback`), not an HTTP endpoint. The `_meta.trailblaze.runtime`
   * = "ondevice" tag is what tells the TS SDK to pick the in-process transport.
   */
  data class JsScriptingCallbackContext(
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
    // doesn't model today (`array`, `object`, etc.) — those fall back to String rather
    // than crashing session startup.
    val descriptor = trailblazeDescriptor.toKoogToolDescriptor(strict = false)
    val serializer = BundleToolSerializer(registered.advertisedName, sessionProvider, callbackContext)
    return TrailblazeKoogTool(
      argsSerializer = serializer,
      descriptor = descriptor,
      executeTool = { args: BundleTrailblazeTool ->
        val context = trailblazeToolContextProvider()
        val result = args.execute(context)
        "Executed bundle tool: ${registered.advertisedName.toolName} — result: $result"
      },
    )
  }

  override fun decodeToolCall(argumentsJson: String): TrailblazeTool {
    val serializer = BundleToolSerializer(registered.advertisedName, sessionProvider, callbackContext)
    return Json.decodeFromString(serializer, argumentsJson)
  }
}
