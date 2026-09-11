package xyz.block.trailblaze.toolcalls.commands

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.toolcalls.CoreTools
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.yaml.serializers.CaseInsensitiveEnumSerializer

@Serializable
// Not recordable: this reports the AGENT's progress through an objective, it does not drive the
// device. Persisting it into a recording gives a replay a tool that can only no-op.
@TrailblazeToolClass(name = CoreTools.OBJECTIVE_STATUS, isRecordable = false)
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

// The tool's own @LLMDescription instructs the model to return lowercase 'in_progress' /
// 'completed' / 'failed', so this enum in particular must decode case-insensitively.
@Serializable(with = Status.Serializer::class)
enum class Status {
  IN_PROGRESS,
  COMPLETED,
  FAILED,
  ;

  object Serializer : CaseInsensitiveEnumSerializer<Status>(Status::class, Status.entries)
}
