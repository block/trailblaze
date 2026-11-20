package xyz.block.trailblaze.yaml

import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.serializer
import xyz.block.trailblaze.logs.client.TrailblazeSerializationInitializer
import xyz.block.trailblaze.toolcalls.CallableOnlyFromToolsException
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.isCallableOnlyFromTools
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
@JvmOverloads
fun createTrailblazeYaml(
  customTrailblazeToolClasses: Set<KClass<out TrailblazeTool>> = emptySet(),
  strict: Boolean = false,
): TrailblazeYaml {
  val allDiscovered = TrailblazeSerializationInitializer.buildAllTools().values.toSet()
  return createTrailblazeYamlFromAllTools(allDiscovered + customTrailblazeToolClasses, strict)
}

/**
 * Builds a fresh [TrailblazeYaml] from an explicit set of all tool classes. Used by
 * [TrailblazeYaml.Default]'s lazy initializer via `buildTrailblazeYamlDefault()`.
 */
@OptIn(InternalSerializationApi::class)
@JvmOverloads
fun createTrailblazeYamlFromAllTools(
  allToolClasses: Set<KClass<out TrailblazeTool>>,
  strict: Boolean = false,
): TrailblazeYaml {
  return TrailblazeYaml(
    toolSerializersByName = buildToolSerializerMap(allToolClasses),
    strict = strict,
  )
}

/**
 * A tool marked [xyz.block.trailblaze.toolcalls.TrailblazeToolClass.callableOnlyFromTools] maps to
 * a serializer that still encodes but refuses to decode: trail YAML is how every caller that is
 * not a tool names a tool, while a tool calling one passes it by object and never comes here.
 */
@OptIn(InternalSerializationApi::class)
fun buildToolSerializerMap(
  toolClasses: Set<KClass<out TrailblazeTool>>,
): Map<String, KSerializer<out TrailblazeTool>> = toolClasses.associate { kClass ->
  val name = kClass.toolName().toolName
  val serializer = kClass.serializer()
  name to if (kClass.isCallableOnlyFromTools()) DecodeRefusingSerializer(name, serializer) else serializer
}

private class DecodeRefusingSerializer<T : TrailblazeTool>(
  private val toolName: String,
  private val delegate: KSerializer<T>,
) : KSerializer<T> {
  override val descriptor: SerialDescriptor get() = delegate.descriptor
  override fun serialize(encoder: Encoder, value: T) = delegate.serialize(encoder, value)
  override fun deserialize(decoder: Decoder): T = throw CallableOnlyFromToolsException(toolName)
}
