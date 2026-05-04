package xyz.block.trailblaze.toolcalls

import kotlinx.serialization.json.JsonObject

/**
 * Marker for tools whose logical tool name comes from the instance rather than the class.
 *
 * Class-annotated tools (`@TrailblazeToolClass(name = "...")`) have a single name fixed to
 * their Kotlin class. YAML-defined (`tools:` mode) tools break that invariant: every YAML tool
 * name shares the same Kotlin class (`YamlDefinedTrailblazeTool`) but carries a distinct
 * `ToolYamlConfig.id` per instance. This interface lets the polymorphic serializer disambiguate
 * such tools without needing to reflect on the instance.
 */
interface InstanceNamedTrailblazeTool : TrailblazeTool {
  val instanceToolName: String
}

/**
 * Extension of [InstanceNamedTrailblazeTool] for dynamic tools whose arguments should round-trip
 * through the generic `OtherTrailblazeTool` shape when no per-name serializer is registered.
 *
 * This is the serialization contract used by session-scoped scripted tools: the logical tool
 * name comes from the instance, and the tool invocation payload is already available as a raw
 * JSON object that can be preserved verbatim across YAML/JSON boundaries.
 */
interface RawArgumentTrailblazeTool : InstanceNamedTrailblazeTool {
  val rawToolArguments: JsonObject
}
