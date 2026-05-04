package xyz.block.trailblaze.yaml

import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import xyz.block.trailblaze.logs.client.TrailblazeSerializationInitializer
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.toolName
import kotlin.reflect.KClass

/**
 * Builds a fresh [TrailblazeYaml] containing serializers for every YAML-discovered tool
 * class plus any extra [customTrailblazeToolClasses] the caller wants included.
 *
 * Uses [TrailblazeSerializationInitializer.buildAllTools] so that any imperatively-registered
 * tool classes (via [TrailblazeSerializationInitializer.registerImperativeToolClasses]) are
 * included alongside classpath-discovered ones. Pure factory — does not mutate
 * [TrailblazeYaml.Default] or register any globals. Callers that just want the shared default
 * should read [TrailblazeYaml.Default] directly.
 */
fun createTrailblazeYaml(
  customTrailblazeToolClasses: Set<KClass<out TrailblazeTool>> = emptySet(),
): TrailblazeYaml {
  val allDiscovered = TrailblazeSerializationInitializer.buildAllTools().values.toSet()
  return createTrailblazeYamlFromAllTools(allDiscovered + customTrailblazeToolClasses)
}

/**
 * Builds a fresh [TrailblazeYaml] from an explicit set of all tool classes. Also picks up any
 * YAML-defined (`tools:` mode) tools discovered on the classpath and registers a per-tool
 * custom serializer for each. Used by [TrailblazeYaml.Default]'s lazy initializer via
 * `buildTrailblazeYamlDefault()`.
 */
@OptIn(InternalSerializationApi::class)
fun createTrailblazeYamlFromAllTools(
  allToolClasses: Set<KClass<out TrailblazeTool>>,
): TrailblazeYaml {
  val classBacked = buildToolSerializerMap(allToolClasses)
  val yamlDefined = TrailblazeSerializationInitializer
    .buildYamlDefinedToolSerializers()
    .mapKeys { it.key.toolName }
  return TrailblazeYaml(
    toolSerializersByName = classBacked + yamlDefined,
  )
}

@OptIn(InternalSerializationApi::class)
fun buildToolSerializerMap(
  toolClasses: Set<KClass<out TrailblazeTool>>,
): Map<String, KSerializer<out TrailblazeTool>> = toolClasses.associate { kClass ->
  kClass.toolName().toolName to kClass.serializer()
}
