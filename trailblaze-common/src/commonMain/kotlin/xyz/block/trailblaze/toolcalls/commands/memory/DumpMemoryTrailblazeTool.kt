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
@LLMDescription("Log remembered values to the console for debugging. Returns nothing.")
data object DumpMemoryTrailblazeTool : MemoryTrailblazeTool {
  override fun execute(
    memory: AgentMemory,
    elementComparator: ElementComparator,
  ): TrailblazeToolResult {
    memory.dump()
    return TrailblazeToolResult.Success()
  }
}

private fun AgentMemory.dump() {
  Console.log("DUMPING AGENT MEMORY ---------------------------")
  variables.forEach { item ->
    val value = if (item.key in sensitiveKeys) "[REDACTED]" else item.value
    Console.log("${item.key} : $value")
  }
  Console.log("FINISHED DUMPING AGENT MEMORY ---------------------------")
}
