package xyz.block.trailblaze.scripting.subprocess

import io.modelcontextprotocol.kotlin.sdk.types.ListToolsRequest
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.scripting.mcp.TrailblazeToolMeta
import xyz.block.trailblaze.toolcalls.ToolName

/**
 * One subprocess-advertised tool that passed the Trailblaze registration filter and is ready
 * to enter the session's tool registry.
 *
 * Carries everything a later dispatch layer (adapter + Koog wiring) needs: the advertised
 * name (also the registered name per conventions § 4), parsed [TrailblazeToolMeta], the MCP
 * [inputSchema] for the LLM-facing arg signature, and the human-readable description.
 *
 * Deliberately thin — the adapter that implements `ExecutableTrailblazeTool` lives one
 * commit later; this record is what the registration pipeline produces, independent of how
 * it's surfaced to Koog / the LLM.
 */
data class RegisteredSubprocessTool(
  val advertisedName: ToolName,
  val description: String?,
  val inputSchema: ToolSchema,
  val meta: TrailblazeToolMeta,
)

/**
 * Pure filter-and-project step: turn a `tools/list` response into the set that applies to
 * the current session (driver + agent mode) and should be registered.
 *
 * Separated from the session-level `fetchAndFilterTools` call so tests can exercise the
 * filter logic with synthesized [Tool] lists — no real subprocess required.
 */
object SubprocessToolRegistrar {

  /**
   * Parse each tool's `_meta`, apply the capability filter, and return the registerable
   * subset. Order matches the input. Tools rejected by the filter are dropped silently —
   * the session log records what was skipped and why is a polish item for the lifecycle
   * commit (structured logging will ride there).
   */
  fun filterAdvertisedTools(
    tools: List<Tool>,
    driver: TrailblazeDriverType,
    preferHostAgent: Boolean,
  ): List<RegisteredSubprocessTool> = tools.mapNotNull { tool ->
    val meta = TrailblazeToolMeta.fromTool(tool)
    if (!meta.shouldRegister(driver, preferHostAgent)) {
      null
    } else {
      RegisteredSubprocessTool(
        advertisedName = ToolName(tool.name),
        description = tool.description,
        inputSchema = tool.inputSchema,
        meta = meta,
      )
    }
  }
}

/**
 * Convenience: run `tools/list` on this session's connected [io.modelcontextprotocol.kotlin.sdk.client.Client]
 * and delegate to [SubprocessToolRegistrar.filterAdvertisedTools].
 *
 * The caller provides the session-context bits (driver + host-agent mode) so this method
 * stays agnostic of where those come from — the repo integration layer can pass in what it
 * has without coupling to a higher-level session object.
 */
suspend fun McpSubprocessSession.fetchAndFilterTools(
  driver: TrailblazeDriverType,
  preferHostAgent: Boolean,
): List<RegisteredSubprocessTool> {
  val result = client.listTools(ListToolsRequest())
  return SubprocessToolRegistrar.filterAdvertisedTools(result.tools, driver, preferHostAgent)
}
