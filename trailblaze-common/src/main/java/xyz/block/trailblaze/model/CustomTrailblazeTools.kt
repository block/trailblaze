package xyz.block.trailblaze.model

import xyz.block.trailblaze.toolcalls.ToolName
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.toolcalls.TrailblazeToolSet
import xyz.block.trailblaze.toolcalls.toolName
import kotlin.reflect.KClass

data class CustomTrailblazeTools(
  /** App Specific Tools given to the LLM by Default */
  val registeredAppSpecificLlmTools: Set<KClass<out TrailblazeTool>>,
  /** App Specific Tools that can be registered to the LLM, but are not by default */
  val otherAppSpecificLlmTools: Set<KClass<out TrailblazeTool>> = setOf(),
  /** App Specific Tools that cannot be registered to the LLM */
  val nonLlmAppSpecificTools: Set<KClass<out TrailblazeTool>> = setOf(),
  /** Whether to use Set-of-Mark tools (true) or device control tools (false) for UI interactions */
  val setOfMarkEnabled: Boolean = true,
  /** Initial set of tools given to the LLM via a [TrailblazeToolRepo]. Uses Set-of-Mark tools by default. */
  val initialToolRepoToolClasses: Set<KClass<out TrailblazeTool>> =
    TrailblazeToolSet.getLlmToolSet(setOfMarkEnabled).toolClasses + registeredAppSpecificLlmTools,
) {
  fun allForSerializationTools(): Set<KClass<out TrailblazeTool>> = buildSet {
    addAll(registeredAppSpecificLlmTools)
    addAll(otherAppSpecificLlmTools)
    addAll(nonLlmAppSpecificTools)
    addAll(initialToolRepoToolClasses)
    addAll(TrailblazeToolSet.DefaultLlmTrailblazeTools)
    addAll(TrailblazeToolSet.NonLlmTrailblazeTools)
  }

  fun allForSerializationToolsByName(): Map<ToolName, KClass<out TrailblazeTool>> = allForSerializationTools().associateBy { it.toolName() }
}
