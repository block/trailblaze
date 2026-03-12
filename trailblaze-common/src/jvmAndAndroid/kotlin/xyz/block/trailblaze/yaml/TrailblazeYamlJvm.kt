package xyz.block.trailblaze.yaml

import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolSet
import xyz.block.trailblaze.toolcalls.toolName
import kotlin.reflect.KClass

/**
 * Initializes [TrailblazeYaml.Default] with all built-in tool serializers.
 * Call this once during JVM app startup. If not called explicitly, [TrailblazeYaml.Default]
 * will have no tool serializers registered.
 */
fun initTrailblazeYamlDefault() {
  TrailblazeYaml.initDefault(createTrailblazeYaml())
}

/**
 * Builds a [TrailblazeYaml] from [KClass] sets, using JVM reflection to resolve
 * tool names and serializers from annotations.
 */
@OptIn(InternalSerializationApi::class)
fun createTrailblazeYaml(
  customTrailblazeToolClasses: Set<KClass<out TrailblazeTool>> = emptySet(),
): TrailblazeYaml {
  val allClasses = TrailblazeToolSet.AllBuiltInTrailblazeToolsForSerialization + customTrailblazeToolClasses
  return TrailblazeYaml(
    toolSerializersByName = buildToolSerializerMap(allClasses),
  )
}

@OptIn(InternalSerializationApi::class)
fun buildToolSerializerMap(
  toolClasses: Set<KClass<out TrailblazeTool>>,
): Map<String, KSerializer<out TrailblazeTool>> = toolClasses.associate { kClass ->
  kClass.toolName().toolName to kClass.serializer()
}
