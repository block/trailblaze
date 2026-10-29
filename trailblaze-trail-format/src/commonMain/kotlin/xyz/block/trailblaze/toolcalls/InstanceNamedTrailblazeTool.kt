package xyz.block.trailblaze.toolcalls

import kotlinx.serialization.json.JsonObject

/**
 * Marker for tools whose logical tool name comes from the instance rather than the class.
 *
 * Class-annotated tools (`@TrailblazeToolClass(name = "...")`) have a single name fixed to their
 * Kotlin class. Dynamic tools break that invariant: one implementation class backs many named tools
 * (for example, every scripted tool in a session), and each instance carries its own name. This
 * interface lets the polymorphic serializer disambiguate such tools without needing to reflect on
 * the instance.
 */
interface InstanceNamedTrailblazeTool : TrailblazeTool {
  val instanceToolName: String
}

/**
 * Extension of [InstanceNamedTrailblazeTool] for dynamic tools whose arguments should round-trip
 * through the generic `OtherTrailblazeTool` shape when no per-name serializer is registered.
 *
 * This is the serialization contract used by session-scoped scripted tools: the logical tool name
 * comes from the instance, and the tool invocation payload is already available as a raw JSON
 * object that can be preserved verbatim across YAML/JSON boundaries.
 */
interface RawArgumentTrailblazeTool : InstanceNamedTrailblazeTool {
  val rawToolArguments: JsonObject
}
