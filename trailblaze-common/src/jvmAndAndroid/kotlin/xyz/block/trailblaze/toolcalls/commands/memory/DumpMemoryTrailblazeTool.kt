package xyz.block.trailblaze.toolcalls.commands.memory

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.utils.ElementComparator
import xyz.block.trailblaze.util.Console

@Serializable
@TrailblazeToolClass("dumpMemory")
@LLMDescription(
  """
Calling this function will dump any of the remembered values from the agent's memory.
This is useful for debugging tests that remember data from one screen state and compare it to
a later screen state.
""",
)
data object DumpMemoryTrailblazeTool : MemoryTrailblazeTool {
  override fun execute(
    memory: AgentMemory,
    elementComparator: ElementComparator,
  ): TrailblazeToolResult {
    memory.dump()
    return TrailblazeToolResult.Success
  }
}

private fun AgentMemory.dump() {
  Console.log("DUMPING AGENT MEMORY ---------------------------")
  variables.forEach { item ->
    Console.log("${item.key} : ${item.value}")
  }
  Console.log("FINISHED DUMPING AGENT MEMORY ---------------------------")
}
