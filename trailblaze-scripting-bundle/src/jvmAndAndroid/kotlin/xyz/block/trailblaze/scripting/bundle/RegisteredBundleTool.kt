package xyz.block.trailblaze.scripting.bundle

import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import xyz.block.trailblaze.scripting.mcp.TrailblazeToolMeta
import xyz.block.trailblaze.toolcalls.ToolName

/**
 * One bundle-advertised tool that passed the capability filter and is ready to be wrapped
 * in a [BundleToolRegistration] + added to the session's tool repo.
 *
 * Just a data class — the intermediate result between [BundleToolFilter] (decides what
 * registers) and [BundleToolRegistration] (how the tool looks in the repo). Kept separate
 * so the filter stays a pure function and is easy to unit test.
 */
data class RegisteredBundleTool(
  val advertisedName: ToolName,
  val description: String?,
  val inputSchema: ToolSchema,
  val meta: TrailblazeToolMeta,
)
