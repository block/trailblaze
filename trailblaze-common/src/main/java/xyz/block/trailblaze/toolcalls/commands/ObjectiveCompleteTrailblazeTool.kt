package xyz.block.trailblaze.toolcalls.commands

import kotlinx.serialization.Serializable
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolProperty

@Serializable
@TrailblazeToolClass(
  name = "objectiveComplete",
  description = """
Use this tool to indicate the status of the current objective item.
Give an explanation of the current status of this specific objective item (not the overall objective).
This allows the system to track progress through individual items in the objective list.
      """,
)
data class ObjectiveCompleteTrailblazeTool(
  @TrailblazeToolProperty("The text description of the current objective item you're reporting on (copy exactly from the objective list)")
  val description: String,

  @TrailblazeToolProperty("A message explaining what was accomplished or the current progress for this specific objective item")
  val explanation: String,

  @TrailblazeToolProperty("Status of this specific objective item: 'completed' (move to next item), 'failed', or 'in_progress' (continuing with this same item).")
  val status: String,
) : TrailblazeTool
