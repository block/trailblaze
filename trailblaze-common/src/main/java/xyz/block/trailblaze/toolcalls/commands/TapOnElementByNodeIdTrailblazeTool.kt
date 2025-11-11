package xyz.block.trailblaze.toolcalls.commands

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.exception.TrailblazeException
import xyz.block.trailblaze.toolcalls.DelegatingTrailblazeTool
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.viewmatcher.TapSelectorV2.findBestTrailblazeElementSelectorForTargetNode
import xyz.block.trailblaze.viewmatcher.models.RelativeViewPositioningData
import xyz.block.trailblaze.viewmatcher.models.toOrderedSpatialHints

@Serializable
@TrailblazeToolClass(
  name = "tapOnElementByNodeId",
  isRecordable = false,
)
@LLMDescription(
  "Tap/click on a specific view target by it's `nodeId`.",
)
data class TapOnElementByNodeIdTrailblazeTool(
  @param:LLMDescription("Reasoning on why this view was chosen. Do NOT restate the nodeId.")
  val reason: String,
  @param:LLMDescription("The `nodeId` of the tap target in the view hierarchy.")
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
  @param:LLMDescription("A standard tap is default, but return 'true' to perform a long press instead.")
  val longPress: Boolean = false,
) : DelegatingTrailblazeTool {

  override fun toExecutableTrailblazeTools(executionContext: TrailblazeToolExecutionContext): List<ExecutableTrailblazeTool> {
    val trailblazeTool = this
    val screenState = executionContext.screenState

    // Make sure we have a view hierarchy to work with
    if (screenState?.viewHierarchyOriginal == null) {
      throw TrailblazeException(
        message = "No View Hierarchy available when processing $trailblazeTool",
      )
    }
    // Make sure the nodeId is in the view hierarchy
    val matchingNode = ViewHierarchyTreeNode.dfs(screenState.viewHierarchyOriginal) {
      it.nodeId == trailblazeTool.nodeId
    }
    if (matchingNode == null) {
      throw TrailblazeException(
        message = "TapOnElementByNodeId: No node found with nodeId=${trailblazeTool.nodeId}.  $trailblazeTool",
      )
    }

    val trailblazeElementSelector = findBestTrailblazeElementSelectorForTargetNode(
      root = screenState.viewHierarchyOriginal,
      target = matchingNode,
      trailblazeDevicePlatform = screenState.trailblazeDevicePlatform,
      widthPixels = screenState.deviceWidth,
      heightPixels = screenState.deviceHeight,
      spatialHints = relativelyPositionedViews.toOrderedSpatialHints(),
    )

    println("Selected TrailblazeTool: $trailblazeElementSelector")
    val bestTapTrailblazeToolForNode: ExecutableTrailblazeTool = TapOnByElementSelector(
      reason = reason,
      selector = trailblazeElementSelector,
      longPress = longPress,
    )

    return listOf(bestTapTrailblazeToolForNode)
  }
}
