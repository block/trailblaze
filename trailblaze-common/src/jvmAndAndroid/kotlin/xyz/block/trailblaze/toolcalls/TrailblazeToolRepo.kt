package xyz.block.trailblaze.toolcalls

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.message.Message
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.serializer
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.toolcalls.KoogToolExt.hasSerializableAnnotation
import xyz.block.trailblaze.toolcalls.KoogToolExt.toKoogTools
import xyz.block.trailblaze.toolcalls.commands.ObjectiveStatusTrailblazeTool
import xyz.block.trailblaze.yaml.DirectionStep
import xyz.block.trailblaze.yaml.PromptStep
import xyz.block.trailblaze.yaml.VerificationStep
import kotlin.reflect.KClass
import xyz.block.trailblaze.util.Console

/**
 * Manual calls we register that are not related to Maestro
 */
class TrailblazeToolRepo(
  /**
   * The initial set of tools that are registered in this repository.
   */
  trailblazeToolSet: TrailblazeToolSet,
  /**
   * Optional catalog for dynamic toolset switching. When set, the LLM can call
   * `setActiveToolSets` to swap which tools are available.
   */
  val toolSetCatalog: List<ToolSetCatalogEntry>? = null,
) {
  val registeredTrailblazeToolClasses: MutableSet<KClass<out TrailblazeTool>> = trailblazeToolSet
    .asTools()
    .toMutableSet()

  /**
   * Tools that are not part of the catalog (e.g., app-specific custom tools).
   * These are preserved across [setActiveToolSets] calls.
   */
  private val extraToolClasses: Set<KClass<out TrailblazeTool>> by lazy {
    val catalogToolClasses = toolSetCatalog
      ?.flatMap { it.toolClasses }
      ?.toSet()
      ?: emptySet()
    registeredTrailblazeToolClasses.filter { it !in catalogToolClasses }.toSet()
  }

  fun getRegisteredTrailblazeTools(): Set<KClass<out TrailblazeTool>> = synchronized(registeredTrailblazeToolClasses) {
    registeredTrailblazeToolClasses.toSet()
  }

  fun asToolRegistry(trailblazeToolContextProvider: () -> TrailblazeToolExecutionContext): ToolRegistry = ToolRegistry {
    // Always include verify tools so assertion tool calls from verification steps
    // can be resolved, even when dynamic toolsets limit registeredTrailblazeToolClasses.
    tools((getRegisteredTrailblazeTools() + verifyTools).toKoogTools(trailblazeToolContextProvider))
  }

  private fun addTrailblazeTools(vararg trailblazeTool: KClass<out TrailblazeTool>) = synchronized(registeredTrailblazeToolClasses) {
    trailblazeTool.forEach { tool ->
      if (!tool.hasSerializableAnnotation()) {
        throw IllegalArgumentException("Class ${tool.qualifiedName} is not serializable. Please add @Serializable from the Kotlin Serialization library.")
      }
      if (tool.toKoogToolDescriptor() != null) {
        registeredTrailblazeToolClasses.add(tool)
      } else {
        Console.log("Class ${tool.qualifiedName} (${tool.toolName().toolName}) cannot be used by the LLM.  It was not registered.")
      }
    }
  }

  fun addTrailblazeToolSet(trailblazeToolSet: TrailblazeToolSet) = synchronized(registeredTrailblazeToolClasses) {
    addTrailblazeTools(*trailblazeToolSet.asTools().toTypedArray())
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
   * Replaces all registered tools with the resolved set from the given toolset IDs.
   * Requires [toolSetCatalog] to be set.
   */
  fun setActiveToolSets(toolSetIds: List<String>): String {
    val catalog = toolSetCatalog
      ?: return "Dynamic toolsets not configured for this test."
    val validIds = catalog.map { it.id }.toSet()
    val invalidIds = toolSetIds.filter { it !in validIds }
    if (invalidIds.isNotEmpty()) {
      return "Unknown toolset IDs: $invalidIds. Valid IDs: ${validIds.filter { id -> !catalog.first { it.id == id }.alwaysEnabled }}"
    }
    val newToolClasses = TrailblazeToolSetCatalog.resolve(toolSetIds, catalog) + extraToolClasses
    synchronized(registeredTrailblazeToolClasses) {
      registeredTrailblazeToolClasses.clear()
      registeredTrailblazeToolClasses.addAll(newToolClasses)
    }
    Console.log("Active toolsets changed to: $toolSetIds (${newToolClasses.size} tools)")
    return buildString {
      appendLine("Active tool sets updated.")
      appendLine("Enabled sets: ${(toolSetIds + "core").distinct()}")
      appendLine("Total tools available: ${newToolClasses.size}")
    }
  }

  fun toolCallToTrailblazeTool(toolMessage: Message.Tool): TrailblazeTool? = toolCallToTrailblazeTool(
    toolName = toolMessage.tool,
    toolContent = toolMessage.content,
  )

  fun toolCallToTrailblazeTool(
    toolName: String,
    /** The JSON string of the tool arguments. */
    toolContent: String,
  ): TrailblazeTool {
    val currentTools = getRegisteredTrailblazeTools()
    val trailblazeToolClass: KClass<out TrailblazeTool> =
      currentTools.firstOrNull { toolKClass ->
        toolKClass.toKoogToolDescriptor()?.name == toolName
      } ?: error(
        buildString {
          appendLine("Could not find Trailblaze tool class for name: $toolName.")
          appendLine("Registered tools: ${currentTools.map { it.simpleName }}")
        },
      )

    @OptIn(InternalSerializationApi::class)
    return TrailblazeJsonInstance.decodeFromString(trailblazeToolClass.serializer(), toolContent)
  }

  fun getCurrentToolDescriptors(): List<ToolDescriptor> = getRegisteredTrailblazeTools().mapNotNull { toolClass ->
    toolClass.toKoogToolDescriptor()
  }

  companion object {
    /**
     * Creates a [TrailblazeToolRepo] with dynamic toolset support.
     *
     * Starts with only core tools (from the catalog) plus any [customToolClasses].
     * The LLM can enable additional toolsets at runtime via `setActiveToolSets`.
     */
    fun withDynamicToolSets(
      customToolClasses: Set<KClass<out TrailblazeTool>> = emptySet(),
      excludedToolClasses: Set<KClass<out TrailblazeTool>> = emptySet(),
      catalog: List<ToolSetCatalogEntry> = TrailblazeToolSetCatalog.defaultEntries(),
    ): TrailblazeToolRepo {
      val coreTools = TrailblazeToolSetCatalog.resolve(emptyList(), catalog)
      return TrailblazeToolRepo(
        trailblazeToolSet = TrailblazeToolSet.DynamicTrailblazeToolSet(
          "Core Tool Set",
          coreTools + customToolClasses - excludedToolClasses,
        ),
        toolSetCatalog = catalog,
      )
    }
  }

  // When running - verify: only provide the assertion tools and the objective status tool
  // If you don't provide the objective status tool then the agent cannot complete the step
  // Use node-based assertion tool (DelegatingTrailblazeTool) for Set of Mark mode
  private val verifyTools = TrailblazeToolSet.VerifyToolSet.toolClasses +
    ObjectiveStatusTrailblazeTool::class

  // This function returns different tool descriptors based on the type of prompt step passed in.
  // The DirectionStep returns all registered trailblaze tool classes, while the VerificationStep
  // will return a subset of the assert tool set.
  fun getToolDescriptorsForStep(promptStep: PromptStep): List<ToolDescriptor> = when (promptStep) {
    is DirectionStep -> getCurrentToolDescriptors()
    is VerificationStep -> verifyTools.mapNotNull { it.toKoogToolDescriptor() }
  }
}
