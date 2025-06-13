package xyz.block.trailblaze.toolcalls

import com.aallam.openai.api.chat.ToolBuilder
import com.aallam.openai.api.chat.ToolCall
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.toolcalls.commands.EraseTextTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.HideKeyboardTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.InputTextTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.LaunchAppTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.LongPressElementWithAccessibilityTextTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.LongPressOnElementWithTextTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.ObjectiveStatusTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.PressBackTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.SwipeTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.TapOnElementByNodeIdTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.TapOnElementWithAccessiblityTextTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.TapOnElementWithTextTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.TapOnPointTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.WaitForIdleSyncTrailblazeTool
import kotlin.reflect.KClass
import kotlin.reflect.full.hasAnnotation

/**
 * Manual calls we register that are not related to Maestro
 */
class TrailblazeToolRepo(
  val recordModeEnabled: Boolean = false,
) {
  companion object {
    val DEFAULT_COMMON_COMMAND_CLASSES: Set<KClass<out TrailblazeTool>> = setOf(
      HideKeyboardTrailblazeTool::class,
      InputTextTrailblazeTool::class,
      ObjectiveStatusTrailblazeTool::class,
      EraseTextTrailblazeTool::class,
      PressBackTrailblazeTool::class,
      SwipeTrailblazeTool::class,
      WaitForIdleSyncTrailblazeTool::class,
      LaunchAppTrailblazeTool::class,
    )

    val RECORDING_ENABLED_COMMAND_CLASSES: Set<KClass<out TrailblazeTool>> = setOf(
      LongPressOnElementWithTextTrailblazeTool::class,
      LongPressElementWithAccessibilityTextTrailblazeTool::class,
      TapOnElementWithTextTrailblazeTool::class,
      TapOnElementWithAccessiblityTextTrailblazeTool::class,
    )

    val RECORDING_DISABLED_COMMAND_CLASSES: Set<KClass<out TrailblazeTool>> = setOf(
      TapOnElementByNodeIdTrailblazeTool::class,
    )

    val OTHER_COMMAND_CLASSES: Set<KClass<out TrailblazeTool>> = setOf(
      TapOnPointTrailblazeTool::class,
    )

    val ALL: Set<KClass<out TrailblazeTool>> = DEFAULT_COMMON_COMMAND_CLASSES + RECORDING_ENABLED_COMMAND_CLASSES + RECORDING_DISABLED_COMMAND_CLASSES + OTHER_COMMAND_CLASSES
  }

  private val registeredTrailblazeToolClasses = mutableSetOf<KClass<out TrailblazeTool>>().apply {
    addAll(DEFAULT_COMMON_COMMAND_CLASSES)
    if (recordModeEnabled) {
      addAll(RECORDING_ENABLED_COMMAND_CLASSES)
    } else {
      addAll(RECORDING_DISABLED_COMMAND_CLASSES)
    }
  }

  fun getRegisteredTrailblazeTools(): Set<KClass<out TrailblazeTool>> = registeredTrailblazeToolClasses

  fun KClass<*>.hasSerializableAnnotation(): Boolean = this.hasAnnotation<Serializable>()

  fun addTrailblazeTools(vararg trailblazeTool: KClass<out TrailblazeTool>) = synchronized(registeredTrailblazeToolClasses) {
    trailblazeTool.forEach { tool ->
      if (!tool.hasSerializableAnnotation()) {
        throw IllegalArgumentException("Class ${tool.qualifiedName} is not serializable. Please add @Serializable from the Kotlin Serialization library.")
      }
      registeredTrailblazeToolClasses.add(tool)
    }
  }

  fun removeTrailblazeTools(vararg trailblazeTool: KClass<out TrailblazeTool>) = synchronized(registeredTrailblazeToolClasses) {
    trailblazeTool.forEach { tool ->
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
          propertyFilter = { propertyName: String ->
            !TrailblazeToolAsLlmTool(trailblazeToolClass).excludedProperties.contains(propertyName)
          },
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
        propertyFilter = { propertyName: String ->
          !TrailblazeToolAsLlmTool(commandClass).excludedProperties.contains(propertyName)
        },
      )
    }
  }

  fun toolCallToTrailblazeTool(action: ToolCall.Function): TrailblazeTool? {
    val function = action.function
    val functionName = function.name
    val functionArgs = function.argumentsAsJson()

    val trailblazeToolClass: KClass<out TrailblazeTool>? = registeredTrailblazeToolClasses.firstOrNull {
      TrailblazeToolAsLlmTool(it).name == functionName
    }
    if (trailblazeToolClass == null) {
      // Count not find command class for function name
      return null
    }
    return try {
      JsonSerializationUtil.deserializeTrailblazeTool(trailblazeToolClass, functionArgs)
    } catch (e: Exception) {
      // Failed to deserialize command
      null
    }
  }
}
