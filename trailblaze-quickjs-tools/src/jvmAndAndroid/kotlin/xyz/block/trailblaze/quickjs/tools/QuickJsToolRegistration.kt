package xyz.block.trailblaze.quickjs.tools

import kotlinx.serialization.json.Json
import xyz.block.trailblaze.toolcalls.DynamicTrailblazeToolRegistration
import xyz.block.trailblaze.toolcalls.ToolName
import xyz.block.trailblaze.toolcalls.TrailblazeKoogTool
import xyz.block.trailblaze.toolcalls.TrailblazeKoogTool.Companion.toKoogToolDescriptor
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolDescriptor
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext

/**
 * One QuickJS-bundle-advertised tool's entry in the session's `TrailblazeToolRepo`.
 *
 * Mirror of the legacy `BundleToolRegistration` minus the MCP framing — tools are resolved
 * by name and dispatched via [QuickJsToolHost.callTool]. The repo can't tell whether a
 * dynamic registration came from the QuickJS or MCP runtime; both surface as Koog tools
 * the LLM picks from the same list.
 */
class QuickJsToolRegistration(
  /**
   * The host this tool lives in. The registration holds the host reference rather than
   * looking it up at execute time so the `@trailblaze/tools` SDK's "register on bundle
   * evaluation, dispatch by name" contract can be implemented as a direct call.
   */
  internal val host: QuickJsToolHost,
  /** What the bundle advertised when [QuickJsToolHost.listTools] was called. */
  internal val spec: RegisteredToolSpec,
) : DynamicTrailblazeToolRegistration {

  override val name: ToolName = ToolName(spec.name)

  override val trailblazeDescriptor: TrailblazeToolDescriptor = spec.toTrailblazeToolDescriptor()

  override fun buildKoogTool(
    trailblazeToolContextProvider: () -> TrailblazeToolExecutionContext,
  ): TrailblazeKoogTool<out TrailblazeTool> {
    // Lenient projection: bundle authors can advertise schema types the LLM-tool descriptor
    // doesn't model today (`array`, `object`, etc.) — those fall back to String rather than
    // crashing session startup. Same posture the legacy MCP-bundle registration takes.
    val descriptor = trailblazeDescriptor.toKoogToolDescriptor(strict = false)
    val serializer = QuickJsToolSerializer(name, host)
    return TrailblazeKoogTool(
      argsSerializer = serializer,
      descriptor = descriptor,
      executeTool = { args: QuickJsTrailblazeTool ->
        val context = trailblazeToolContextProvider()
        val result = args.execute(context)
        "Executed QuickJS tool: ${name.toolName} — result: $result"
      },
    )
  }

  override fun decodeToolCall(argumentsJson: String): TrailblazeTool {
    val serializer = QuickJsToolSerializer(name, host)
    return Json.decodeFromString(serializer, argumentsJson)
  }
}
