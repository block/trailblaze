package xyz.block.trailblaze.toolcalls.commands

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass

@Serializable
@TrailblazeToolClass(
  name = SetActiveToolSetsTrailblazeTool.TOOL_NAME,
  isRecordable = false,
)
@LLMDescription(
  """Enable additional tool sets for device interaction. By default, only core tools (tap, input text) are available.
Use this to enable more tools when needed. You can enable multiple tool sets at once.
Call with an empty list to reset to only the core tools.
The available toolset IDs are listed in your system prompt.""",
)
data class SetActiveToolSetsTrailblazeTool(
  @param:LLMDescription(
    "The list of toolset IDs to enable (e.g. ['navigation', 'text-editing']). Core tools are always included.",
  )
  val toolSetIds: List<String>,
) : TrailblazeTool {
  companion object {
    const val TOOL_NAME = "setActiveToolSets"
  }
}
