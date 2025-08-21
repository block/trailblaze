package xyz.block.trailblaze.yaml

import kotlinx.serialization.Serializable
import xyz.block.trailblaze.toolcalls.TrailblazeTool

/**
 * This structure is used to wrap a [TrailblazeTool] in a YAML-friendly format using the toolName.
 *
 * toolName:
 *   property1: value1
 *   property2: value2
 */
@Serializable
data class TrailblazeToolYamlWrapper(
  val name: String,
  val trailblazeTool: TrailblazeTool,
)
