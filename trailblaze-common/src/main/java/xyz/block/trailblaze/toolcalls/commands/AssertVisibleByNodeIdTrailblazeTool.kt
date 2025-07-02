package xyz.block.trailblaze.toolcalls.commands

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.exception.TrailblazeException
import xyz.block.trailblaze.toolcalls.DelegatingTrailblazeTool
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext

@Serializable
@TrailblazeToolClass("assertVisibleWithNodeId")
@LLMDescription(
  """
Assert that the element with the given nodeId is visible on the screen. This will delegate to the appropriate assert tool (by text, resource ID, or accessibility text) based on the node's properties.
""",
)
data class AssertVisibleByNodeIdTrailblazeTool(
  @LLMDescription("Reasoning on why this element was chosen. Do NOT restate the nodeId.")
  val reason: String = "",
  @LLMDescription("The nodeId of the element in the view hierarchy to assert visibility for. Do NOT use the nodeId 0.")
  val nodeId: Long,
) : DelegatingTrailblazeTool {
  override fun toExecutableTrailblazeTools(executionContext: TrailblazeToolExecutionContext): List<ExecutableTrailblazeTool> {
    val screenState = executionContext.screenState
    if (screenState?.viewHierarchy == null) {
      throw TrailblazeException(
        message = "No View Hierarchy available when processing $this",
      )
    }
    val matchingNode = ViewHierarchyTreeNode.dfs(screenState.viewHierarchy) {
      it.nodeId == nodeId
    }
    if (matchingNode == null) {
      throw TrailblazeException(
        message = "AssertVisibleWithNodeId: No node found with nodeId=$nodeId.  $this",
      )
    }
    // Prefer resourceId, then accessibilityText, then text
    val tool: ExecutableTrailblazeTool = when {
      !matchingNode.text.isNullOrBlank() -> AssertVisibleWithTextTrailblazeTool(
        text = matchingNode.text!!,
        id = matchingNode.resourceId,
        index = 0,
        enabled = matchingNode.enabled,
        selected = matchingNode.selected,
      )
      !matchingNode.accessibilityText.isNullOrBlank() -> AssertVisibleWithAccessibilityTextTrailblazeTool(
        accessibilityText = matchingNode.accessibilityText!!,
        id = matchingNode.resourceId,
        index = 0,
        enabled = matchingNode.enabled,
        selected = matchingNode.selected,
      )
      !matchingNode.resourceId.isNullOrBlank() -> AssertVisibleWithResourceIdTrailblazeTool(
        resourceId = matchingNode.resourceId!!,
        accessibilityText = matchingNode.accessibilityText,
        index = 0,
        enabled = matchingNode.enabled,
        selected = matchingNode.selected,
      )
      else -> throw TrailblazeException("AssertVisibleWithNodeId: No suitable property to assert visibility for nodeId=$nodeId")
    }
    return listOf(tool)
  }
}
