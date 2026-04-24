package xyz.block.trailblaze.scripting.bundle

import io.modelcontextprotocol.kotlin.sdk.types.ListToolsRequest
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.scripting.mcp.TrailblazeToolMeta
import xyz.block.trailblaze.toolcalls.ToolName

/**
 * Decides which tools from a bundle's `tools/list` response should register for the
 * current session. Inspects each tool's `_meta.trailblaze/…` keys and drops tools whose
 * declared capabilities don't match the session (wrong driver, wrong platform, or
 * host-only when we're on-device).
 *
 * On-device sessions always pass `preferHostAgent = false` (there is no host agent from
 * the on-device runner's point of view). That's what makes the `trailblaze/requiresHost`
 * filter bite at registration time — host-only tools never appear in the LLM's tool
 * list, so a trail referencing one fails at trail-validation, not silently at runtime.
 *
 * Pure function. No I/O, no side effects. [fetchAndFilterTools] is a convenience wrapper
 * that runs `tools/list` then delegates here.
 */
object BundleToolFilter {

  /** Apply the capability filter and return tools that should register. Order preserved. */
  fun filterAdvertisedTools(
    tools: List<Tool>,
    driver: TrailblazeDriverType,
  ): List<RegisteredBundleTool> = tools.mapNotNull { tool ->
    val meta = TrailblazeToolMeta.fromTool(tool)
    if (!meta.shouldRegister(driver, preferHostAgent = false)) {
      null
    } else {
      RegisteredBundleTool(
        advertisedName = ToolName(tool.name),
        description = tool.description,
        inputSchema = tool.inputSchema,
        meta = meta,
      )
    }
  }
}

/**
 * Run `tools/list` on this session's MCP client, then apply [BundleToolFilter]. The
 * launcher uses this directly; tests can call it to peek at the filtered set without
 * going through full registration.
 */
suspend fun McpBundleSession.fetchAndFilterTools(
  driver: TrailblazeDriverType,
): List<RegisteredBundleTool> {
  val result = client.listTools(ListToolsRequest())
  return BundleToolFilter.filterAdvertisedTools(result.tools, driver)
}
