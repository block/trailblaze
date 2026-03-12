package xyz.block.trailblaze.yaml

import xyz.block.trailblaze.toolcalls.TrailblazeTool

/**
 * Creates a [TrailblazeToolYamlWrapper] from a [TrailblazeTool] with an explicit tool name.
 *
 * This is the KMP-compatible version that works on all platforms (JVM, Wasm, etc.)
 * because it does not require JVM reflection to resolve the tool name.
 */
fun wrapTrailblazeTool(
  trailblazeTool: TrailblazeTool,
  toolName: String,
): TrailblazeToolYamlWrapper = TrailblazeToolYamlWrapper(
  name = toolName,
  trailblazeTool = trailblazeTool,
)
