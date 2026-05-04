package xyz.block.trailblaze.yaml

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.toolcalls.TrailblazeTool

/**
 * This structure is used to wrap a [TrailblazeTool] in a YAML-friendly format using the toolName.
 *
 * toolName:
 *   property1: value1
 *   property2: value2
 *
 * [trailblazeTool] is `@Contextual` because [TrailblazeTool] is an abstract type — the JSON
 * module supplies a contextual serializer that encodes/decodes through the
 * `{toolName, raw}` `OtherTrailblazeTool` shape, and the YAML module supplies its own
 * via [xyz.block.trailblaze.yaml.serializers.TrailblazeToolYamlWrapperSerializer] (which
 * sees the wrapper as a whole and never reflects into this field).
 */
@Serializable
data class TrailblazeToolYamlWrapper(
  val name: String,
  @Contextual val trailblazeTool: TrailblazeTool,
)
