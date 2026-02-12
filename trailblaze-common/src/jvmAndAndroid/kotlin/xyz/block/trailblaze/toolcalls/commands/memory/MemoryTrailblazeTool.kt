package xyz.block.trailblaze.toolcalls.commands.memory

import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.utils.ElementComparator

/**
 * A marker interface for all Trailblaze tools that require access to the test memory variables
 * during the tool execution
 */
sealed interface MemoryTrailblazeTool : TrailblazeTool {
  fun execute(
    memory: AgentMemory,
    elementComparator: ElementComparator,
  ): TrailblazeToolResult
}
