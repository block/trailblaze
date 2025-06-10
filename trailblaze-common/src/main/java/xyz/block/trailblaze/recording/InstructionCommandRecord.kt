package xyz.block.trailblaze.recording

import kotlinx.serialization.Serializable
import xyz.block.trailblaze.toolcalls.TrailblazeTool

@Serializable
data class InstructionCommandRecord(
  val instruction: String,
  val commands: List<TrailblazeTool>,
)
