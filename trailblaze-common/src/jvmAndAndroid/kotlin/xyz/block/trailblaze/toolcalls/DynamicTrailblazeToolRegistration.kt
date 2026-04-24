package xyz.block.trailblaze.toolcalls

/**
 * Session-scoped dynamic tool source — the plug point that lets runtimes like the subprocess
 * MCP path (`:trailblaze-scripting-subprocess`) contribute tools into a session's
 * [TrailblazeToolRepo] alongside Kotlin `KClass`-backed tools and YAML-defined tools.
 *
 * Each registration represents **one tool** — register N of these for a subprocess that
 * advertises N tools. Implementations are free to close over whatever per-source state they
 * need (MCP session handle, callback-channel endpoints, etc.). The repo doesn't care.
 *
 * Kept narrow on purpose: the repo only needs to (a) surface the tool to the LLM via a Koog
 * tool, (b) report the tool's descriptor, and (c) reconstruct a [TrailblazeTool] instance
 * when a tool-call comes back from the LLM. No lifecycle hooks — those belong to whatever
 * owns the session, not the repo.
 */
interface DynamicTrailblazeToolRegistration {

  /** Globally-unique tool name (same one the LLM sees in tool-selection + tool-call). */
  val name: ToolName

  /** Descriptor Trailblaze surfaces via [TrailblazeToolRepo.getCurrentToolDescriptors]. */
  val trailblazeDescriptor: TrailblazeToolDescriptor

  /**
   * Build the Koog-level [TrailblazeKoogTool] for this tool. Called each time the repo
   * produces a [ai.koog.agents.core.tools.ToolRegistry] via
   * [TrailblazeToolRepo.asToolRegistry].
   */
  fun buildKoogTool(
    trailblazeToolContextProvider: () -> TrailblazeToolExecutionContext,
  ): TrailblazeKoogTool<out TrailblazeTool>

  /**
   * Reconstruct a [TrailblazeTool] instance from the LLM's serialized tool-call arguments.
   * Used by [TrailblazeToolRepo.toolCallToTrailblazeTool] to look up subprocess tools by
   * name alongside the KClass-based path.
   */
  fun decodeToolCall(argumentsJson: String): TrailblazeTool
}
