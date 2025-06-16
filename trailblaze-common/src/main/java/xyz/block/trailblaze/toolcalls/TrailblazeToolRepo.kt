package xyz.block.trailblaze.toolcalls

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.message.Message
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.serializer
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.toolcalls.KoogToolExt.hasSerializableAnnotation
import xyz.block.trailblaze.toolcalls.KoogToolExt.toKoogTools
import xyz.block.trailblaze.toolcalls.TrailblazeKoogTool.Companion.toKoogToolDescriptor
import kotlin.reflect.KClass

/**
 * Manual calls we register that are not related to Maestro
 */
class TrailblazeToolRepo(
  /**
   * The initial set of tools that are registered in this repository.
   */
  trailblazeToolSet: TrailblazeToolSet,
) {
  val registeredTrailblazeToolClasses: MutableSet<KClass<out TrailblazeTool>> = trailblazeToolSet
    .asTools()
    .toMutableSet()

  fun getRegisteredTrailblazeTools(): Set<KClass<out TrailblazeTool>> = registeredTrailblazeToolClasses

  fun asToolRegistry(trailblazeToolContextProvider: () -> TrailblazeToolExecutionContext): ToolRegistry = ToolRegistry {
    tools(getRegisteredTrailblazeTools().toKoogTools(trailblazeToolContextProvider))
  }

  fun addTrailblazeTools(vararg trailblazeTool: KClass<out TrailblazeTool>) = synchronized(registeredTrailblazeToolClasses) {
    trailblazeTool.forEach { tool ->
      if (!tool.hasSerializableAnnotation()) {
        throw IllegalArgumentException("Class ${tool.qualifiedName} is not serializable. Please add @Serializable from the Kotlin Serialization library.")
      }
      registeredTrailblazeToolClasses.add(tool)
    }
  }

  fun removeTrailblazeTools(vararg trailblazeToolArgs: KClass<out TrailblazeTool>) = synchronized(registeredTrailblazeToolClasses) {
    trailblazeToolArgs.forEach { tool ->
      if (registeredTrailblazeToolClasses.contains(tool)) {
        registeredTrailblazeToolClasses.remove(tool)
      }
    }
  }

  fun removeAllTrailblazeTools() = synchronized(registeredTrailblazeToolClasses) {
    registeredTrailblazeToolClasses.clear()
  }

  fun toolCallToTrailblazeTool(toolMessage: Message.Tool): TrailblazeTool? = toolCallToTrailblazeTool(
    toolName = toolMessage.tool,
    toolContent = toolMessage.content,
  )

  fun toolCallToTrailblazeTool(
    toolName: String,
    /** The JSON string of the tool arguments. */
    toolContent: String,
  ): TrailblazeTool? {
    val trailblazeToolClass: KClass<out TrailblazeTool> =
      registeredTrailblazeToolClasses.firstOrNull { toolKClass ->
        toolKClass.toKoogToolDescriptor().name == toolName
      } ?: error(
        buildString {
          appendLine("Could not find Trailblaze tool class for name: $toolName.")
          appendLine("Registered tools: ${registeredTrailblazeToolClasses.map { it.simpleName }}")
        },
      )

    @OptIn(InternalSerializationApi::class)
    return TrailblazeJsonInstance.decodeFromString(trailblazeToolClass.serializer(), toolContent)
  }

  fun getCurrentToolDescriptors(): List<ToolDescriptor> = registeredTrailblazeToolClasses.map { toolClass ->
    toolClass.toKoogToolDescriptor()
  }
}
