package xyz.block.trailblaze.logs.client.temp

import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.modules.SerializersModuleBuilder
import xyz.block.trailblaze.toolcalls.ToolName
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import kotlin.reflect.KClass

/**
 * Custom Serializer for [TrailblazeTool]
 *
 * This allows us to handle commands that are not on the classpath gracefully.
 */
@OptIn(InternalSerializationApi::class)
fun SerializersModuleBuilder.registerTrailblazeToolSerializer(allToolClasses: Map<ToolName, KClass<out TrailblazeTool>>) {
  polymorphicDefaultDeserializer(TrailblazeTool::class) { className ->
    OtherTrailblazeToolSerializer(allToolClasses)
  }

  polymorphicDefaultSerializer(TrailblazeTool::class) { value ->
    OtherTrailblazeToolSerializer(allToolClasses)
  }
}
