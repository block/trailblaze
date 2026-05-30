package xyz.block.trailblaze.toolcalls.commands.memory

import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.toolcalls.ReadOnlyTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.utils.ElementComparator

/**
 * A marker interface for all Trailblaze tools that require access to the test memory variables
 * during the tool execution.
 *
 * Inherits [ReadOnlyTrailblazeTool] so the dispatcher's snapshot-cache invalidation gate
 * skips memory tools — they may mutate agent-side variables but never touch device state,
 * so a captured view-hierarchy survives across them.
 *
 * ## Authoring contract — MUST NOT mutate device state
 *
 * All [MemoryTrailblazeTool] implementations are implicitly [ReadOnlyTrailblazeTool]
 * because memory tools by definition only touch agent-side variables. Implementations
 * MUST NOT issue device-mutating side effects — taps, swipes, navigations, app launches,
 * network calls, focus changes, or anything else observable on the device — even if the
 * tool ALSO writes to memory along the way. A tool that breaks this contract will silently
 * leave the snapshot cache showing a stale view-hierarchy to any follow-up `findMatches`
 * in the same batch.
 *
 * If you need a tool that touches both memory and the device, model it as an
 * [xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool] instead so the dispatcher's
 * default-invalidate behaviour applies. The dual-write shape can still read/write memory
 * via the execution context.
 */
sealed interface MemoryTrailblazeTool : TrailblazeTool, ReadOnlyTrailblazeTool {
  fun execute(
    memory: AgentMemory,
    elementComparator: ElementComparator,
  ): TrailblazeToolResult
}
