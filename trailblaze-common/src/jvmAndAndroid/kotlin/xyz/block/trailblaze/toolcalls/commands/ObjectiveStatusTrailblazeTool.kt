package xyz.block.trailblaze.toolcalls.commands

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass

@Serializable
@TrailblazeToolClass("objectiveStatus")
@LLMDescription(
  """
Use this tool to report the status of the current objective.
First determine if all of the objective's goals have been met, and if they have not return an 'in_progress' status.
If all of the goals have been met successfully, return a 'completed' status.
If you have tried multiple options to complete the objective and are still unsuccessful, then return a 'failed' status.
Returning 'failed' should be a last resort once all options have been tested.
      """,
)
data class ObjectiveStatusTrailblazeTool(
  @param:LLMDescription("A message explaining what was accomplished or the current progress for this objective")
  val explanation: String,

  @param:LLMDescription("Status of this objective: 'IN_PROGRESS' (still working on it), 'COMPLETED' (fully done), or 'FAILED'")
  val status: Status,
) : TrailblazeTool

enum class Status {
  IN_PROGRESS,
  COMPLETED,
  FAILED,
}
