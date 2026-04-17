package xyz.block.trailblaze.mcp.newtools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import xyz.block.trailblaze.mcp.TrailblazeMcpBridge
import xyz.block.trailblaze.mcp.TrailblazeMcpSessionContext

/**
 * Session/Trail management tools for test authoring.
 *
 * NOT registered by default - these are for test authoring workflows
 * where you're creating trails and need explicit session boundaries.
 *
 * For exploration/automation, you don't need these - just connect and use two-tier tools.
 */
@Suppress("unused")
class SessionManagementToolSet(
  private val sessionContext: TrailblazeMcpSessionContext?,
  private val mcpBridge: TrailblazeMcpBridge,
) : ToolSet {

  @LLMDescription(
    """
    End the current trail/session. Use this when test authoring is complete.

    This marks the trail as done and finalizes any recording.
    For exploration/automation, you typically don't need this.
    """
  )
  @Tool
  suspend fun endSession(): String {
    val wasSessionEnded = mcpBridge.endSession()
    sessionContext?.clearAssociatedDevice()
    return if (wasSessionEnded) "Session ended. Trail finalized." else "No active session to end."
  }
}
