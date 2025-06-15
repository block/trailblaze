package xyz.block.trailblaze.toolcalls

import ai.koog.agents.core.tools.ToolRegistry
import com.aallam.openai.api.chat.ToolBuilder
import com.aallam.openai.api.chat.ToolCall
import xyz.block.trailblaze.toolcalls.KoogToolExt.hasSerializableAnnotation
import xyz.block.trailblaze.toolcalls.KoogToolExt.toKoogTools
import xyz.block.trailblaze.toolcalls.TrailblazeKoogTool.Companion.toKoogToolDescriptor
import kotlin.reflect.KClass

/**
 * Manual calls we register that are not related to Maestro
 */
class TrailblazeToolRepo(
  val setOfMarkEnabled: Boolean = true,
) {

  private val registeredTrailblazeToolClasses = mutableSetOf<KClass<out TrailblazeTool>>().apply {
    addAll(TrailblazeToolSet.DefaultUiToolSet.asTools())
    if (setOfMarkEnabled) {
      addAll(TrailblazeToolSet.InteractWithElementsByNodeIdToolSet.asTools())
    } else {
      addAll(TrailblazeToolSet.InteractWithElementsByPropertyToolSet.asTools())
    }
  }.also {
    println("Registered Trailblaze tools (setOfMarkEnabled=$setOfMarkEnabled): ${it.map { it -> it.simpleName }}")
  }

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

  /**
   * Register all manual tools with the OpenAI tool builder.
   *
   * @param builder The ToolBuilder to register the tools with
   */
  fun registerManualTools(
    builder: ToolBuilder,
  ) {
    with(builder) {
      // Register standard tools
      registeredTrailblazeToolClasses.forEach { trailblazeToolClass ->
        DataClassToToolUtils.registerManualToolForDataClass(
          builder = this,
          clazz = trailblazeToolClass,
        )
      }
    }
  }

  /**
   * Register only a specific tool, useful when we want to force the LLM to use a particular tool
   * @param builder The ToolBuilder to register the tool with
   * @param commandClass The specific command class to register
   */
  fun registerSpecificToolOnly(builder: ToolBuilder, commandClass: KClass<out TrailblazeTool>) {
    // Register only this specific tool
    with(builder) {
      DataClassToToolUtils.registerManualToolForDataClass(
        builder = this,
        clazz = commandClass,
      )
    }
  }

  fun toolCallToTrailblazeTool(action: ToolCall.Function): TrailblazeTool? {
    val function = action.function
    val functionName = function.name
    val functionArgs = function.argumentsAsJson()

    val trailblazeToolArgsClass: KClass<out TrailblazeTool>? =
      registeredTrailblazeToolClasses.firstOrNull { toolKClass ->
        toolKClass.toKoogToolDescriptor().name == functionName
      }
    if (trailblazeToolArgsClass == null) {
      // Count not find command class for function name
      return null
    }
    return try {
      JsonSerializationUtil.deserializeTrailblazeTool(trailblazeToolArgsClass, functionArgs)
    } catch (e: Exception) {
      // Failed to deserialize command
      null
    }
  }
}
