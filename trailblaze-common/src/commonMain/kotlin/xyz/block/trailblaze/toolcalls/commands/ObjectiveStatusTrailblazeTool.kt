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
Report the current objective's status: 'in_progress' until all its goals are met, 'completed' once
they are, 'failed' only as a last resort after trying multiple options.
""",
)
data class ObjectiveStatusTrailblazeTool(
  @param:LLMDescription("What was accomplished or the progress so far.")
  val explanation: String,

  @param:LLMDescription("IN_PROGRESS, COMPLETED, or FAILED.")
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
