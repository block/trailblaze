package xyz.block.trailblaze.toolcalls.commands

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import xyz.block.trailblaze.api.TrailblazeElementSelector
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.exception.TrailblazeToolExecutionException
import xyz.block.trailblaze.toolcalls.DelegatingTrailblazeTool
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.viewmatcher.TapSelectorV2.findBestTrailblazeElementSelectorForTargetNode
import xyz.block.trailblaze.viewmatcher.models.RelativeViewPositioningData
import xyz.block.trailblaze.viewmatcher.models.toOrderedSpatialHints
import xyz.block.trailblaze.yaml.TrailblazeYaml

@Serializable
@TrailblazeToolClass(
  name = "assertVisibleWithNodeId",
  isRecordable = false,
)
@LLMDescription(
  """
Assert that the element with the given nodeId is visible on the screen. This will delegate to the appropriate assert tool (by text, resource ID, or accessibility text) based on the node's properties.
""",
)
data class AssertVisibleByNodeIdTrailblazeTool(
  @param:LLMDescription("Reasoning on why this element was chosen. Do NOT restate the nodeId.")
  val reason: String = "",
  @param:LLMDescription("The nodeId of the element in the view hierarchy to assert visibility for. Do NOT use the nodeId 0.")
  val nodeId: Long,
  @param:LLMDescription(
    """Helps further localize the tap target using spatial relationships to other nearby elements. Each entry describes where the tap TARGET is located relative to a reference element:
    - 'position' (enum): Direction from the other element—choose from LEFT_OF, RIGHT_OF, ABOVE, BELOW.
    - 'otherNodeId' (integer): The nodeId of the reference element.

    Only valid if the reference and target are *not* in a parent-child relationship; relationships must be geometric (siblings or visually nearby elements). Parent-child positions are ignored.

    Examples:
    • {position: "LEFT_OF", otherNodeId: 43}
    • {position: "ABOVE", otherNodeId: 20}
    • {position: "RIGHT_OF", otherNodeId: 99}
    • {position: "RIGHT_OF", otherNodeId: 48}

    Optional: Provide up to 4 relative positions, each using a unique direction. If nodeId alone is unambiguous, this list can be empty.""",
  )
  val relativelyPositionedViews: List<RelativeViewPositioningData> = emptyList(),
) : DelegatingTrailblazeTool {
  override fun toExecutableTrailblazeTools(executionContext: TrailblazeToolExecutionContext): List<ExecutableTrailblazeTool> {
    val screenState = executionContext.screenState
    if (screenState?.viewHierarchyOriginal == null) {
      throw TrailblazeToolExecutionException(
        message = "No View Hierarchy available when processing $this",
        tool = this,
      )
    }
    val matchingNode = ViewHierarchyTreeNode.dfs(screenState.viewHierarchyOriginal) {
      it.nodeId == nodeId
    }
    if (matchingNode == null) {
      throw TrailblazeToolExecutionException(
        message = "AssertVisibleWithNodeId: No node found with nodeId=$nodeId.  $this",
        tool = this,
      )
    }

    val trailblazeElementSelector: TrailblazeElementSelector = findBestTrailblazeElementSelectorForTargetNode(
      root = screenState.viewHierarchyOriginal,
      target = matchingNode,
      trailblazeDevicePlatform = screenState.trailblazeDevicePlatform,
      widthPixels = screenState.deviceWidth,
      heightPixels = screenState.deviceHeight,
      spatialHints = relativelyPositionedViews.toOrderedSpatialHints(),
    )
    println("Best Element Selector:\n${TrailblazeYaml.defaultYamlInstance.encodeToString(trailblazeElementSelector)}")
    val bestTapTrailblazeToolForNode: ExecutableTrailblazeTool = AssertVisibleBySelectorTrailblazeTool(
      reason = reason,
      selector = trailblazeElementSelector,
    )
    return listOf(bestTapTrailblazeToolForNode)
  }
}
