package xyz.block.trailblaze.toolcalls

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
