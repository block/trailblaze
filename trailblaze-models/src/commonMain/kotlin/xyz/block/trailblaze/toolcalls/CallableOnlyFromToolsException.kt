package xyz.block.trailblaze.toolcalls

import kotlinx.serialization.SerializationException

/**
 * Thrown when a [TrailblazeToolClass.callableOnlyFromTools] tool is decoded from trail YAML — a
 * trail step, a `trailblaze tool` / MCP `step` call, or a recorder palette entry. A tool reaches
 * such a tool by object (`ctx.tools.<name>(...)` or Kotlin composition), never through YAML, so
 * refusing at decode closes every entry point that isn't another tool.
 *
 * A [SerializationException], so a caller that already reports "couldn't parse this YAML" reports
 * this too; a caller that wants the bare sentence catches this type.
 */
class CallableOnlyFromToolsException(val toolName: String) : SerializationException(message(toolName)) {
  companion object {
    fun message(toolName: String): String =
      "`$toolName` can only be called from another tool, e.g. a scripted tool's " +
        "`ctx.tools.$toolName(...)`. It can't run as a trail step, through `trailblaze tool` or " +
        "MCP `step`, or from the agent."
  }
}
