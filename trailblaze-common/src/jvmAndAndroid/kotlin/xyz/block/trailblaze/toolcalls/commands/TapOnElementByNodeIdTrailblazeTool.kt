package xyz.block.trailblaze.toolcalls.commands

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames
import xyz.block.trailblaze.api.TrailblazeNodeSelectorGenerator
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.exception.TrailblazeToolExecutionException
import xyz.block.trailblaze.model.NodeSelectorMode
import xyz.block.trailblaze.toolcalls.DelegatingTrailblazeTool
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.ReasoningTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.viewmatcher.TapSelectorV2.findBestTrailblazeElementSelectorForTargetNodeWithStrategy
import xyz.block.trailblaze.viewmatcher.models.RelativeViewPositioningData
import xyz.block.trailblaze.viewmatcher.models.toOrderedSpatialHints
import xyz.block.trailblaze.viewmatcher.strategies.IndexStrategy

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@TrailblazeToolClass(
  name = "tapOnElementByNodeId",
  isRecordable = false,
)
@LLMDescription(
  "Tap/click on a specific view target by it's `nodeId`.",
)
data class TapOnElementByNodeIdTrailblazeTool(
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
  // @JsonNames("reason") allows deserializing old recordings that used the "reason" field name.
  @JsonNames("reason")
  override val reasoning: String? = null,
) : DelegatingTrailblazeTool, ReasoningTrailblazeTool {

  override fun toExecutableTrailblazeTools(executionContext: TrailblazeToolExecutionContext): List<ExecutableTrailblazeTool> {
    val trailblazeTool = this
    val screenState = executionContext.screenState

    // Make sure we have a view hierarchy to work with
    if (screenState?.viewHierarchy == null) {
      throw TrailblazeToolExecutionException(
        message = "No View Hierarchy available when processing $trailblazeTool",
        tool = this,
      )
    }
    // Make sure the nodeId is in the view hierarchy
    val matchingNode = ViewHierarchyTreeNode.dfs(screenState.viewHierarchy) {
      it.nodeId == trailblazeTool.nodeId
    }
    if (matchingNode == null) {
      throw TrailblazeToolExecutionException(
        message = "TapOnElementByNodeId: No node found with nodeId=${trailblazeTool.nodeId}.  $trailblazeTool",
        tool = this,
      )
    }

    // Generate a rich TrailblazeNodeSelector if the screen state has a native tree.
    // This provides richer element matching using driver-specific properties
    // (className, inputType, collectionItemInfo, labeledByText, etc.)
    //
    // Node IDs don't align between ViewHierarchyTreeNode (pre-order DFS) and
    // TrailblazeNode (post-order DFS from AccessibilityNode), so we match by
    // center point coordinates instead. Both trees are built from the same
    // AccessibilityNodeInfo root, so bounds are identical.
    val nodeSelector = screenState.trailblazeNodeTree?.let { trailblazeTree ->
      val centerPoint = matchingNode.centerPoint
      if (centerPoint != null) {
        val (cx, cy) = centerPoint.split(",").map { it.toInt() }
        // Find all TrailblazeNodes whose bounds contain the center point,
        // then pick the smallest (most specific) match.
        val targetTrailblazeNode = trailblazeTree
          .findAll { node -> node.bounds?.containsPoint(cx, cy) == true }
          .minByOrNull { node ->
            val b = node.bounds ?: return@minByOrNull Long.MAX_VALUE
            b.width.toLong() * b.height.toLong()
          }
        targetTrailblazeNode?.let { target ->
          try {
            TrailblazeNodeSelectorGenerator.findBestSelector(trailblazeTree, target)
          } catch (e: Exception) {
            Console.log("WARNING: TrailblazeNodeSelector generation failed, falling back to legacy selector: ${e.message}")
            null
          }
        }
      } else {
        null
      }
    }

    val mode = executionContext.nodeSelectorMode

    // FORCE_LEGACY: skip nodeSelector entirely, always use TapSelectorV2
    // FORCE_NODE_SELECTOR: derive legacy from nodeSelector; fall through to legacy if generation fails
    // PREFER_NODE_SELECTOR: iOS derives from nodeSelector when available; non-iOS uses TapSelectorV2
    when (mode) {
      NodeSelectorMode.FORCE_LEGACY -> { /* fall through to TapSelectorV2 below */ }
      NodeSelectorMode.FORCE_NODE_SELECTOR -> {
        if (nodeSelector != null) {
          return listOf(
            TapOnByElementSelector(
              reason = reasoning,
              selector = nodeSelector.toTrailblazeElementSelector(),
              longPress = longPress,
              nodeSelector = nodeSelector,
            ),
          )
        }
        // nodeSelector generation failed; fall through to legacy path
      }
      NodeSelectorMode.PREFER_NODE_SELECTOR -> {
        if (screenState.trailblazeDevicePlatform == TrailblazeDevicePlatform.IOS && nodeSelector != null) {
          return listOf(
            TapOnByElementSelector(
              reason = reasoning,
              selector = nodeSelector.toTrailblazeElementSelector(),
              longPress = longPress,
              nodeSelector = nodeSelector,
            ),
          )
        }
      }
    }

    // Legacy path: generate selector via TapSelectorV2.
    val selectorWithStrategy = findBestTrailblazeElementSelectorForTargetNodeWithStrategy(
      root = screenState.viewHierarchy,
      target = matchingNode,
      trailblazeDevicePlatform = screenState.trailblazeDevicePlatform,
      widthPixels = screenState.deviceWidth,
      heightPixels = screenState.deviceHeight,
      spatialHints = relativelyPositionedViews.toOrderedSpatialHints(),
    )

    // If IndexStrategy was used with a high index (> 10), the selector is fragile.
    // High indices depend on exact element ordering in the hierarchy which can change.
    // Low indices (≤ 10) are usually stable enough to be useful.
    // Fall back to tapping by coordinates for high indices.
    if (selectorWithStrategy.strategyName == IndexStrategy.name) {
      val index = selectorWithStrategy.selector.index?.toIntOrNull()
      if (index != null && index > 10) {
        val centerPoint = matchingNode.centerPoint
        if (centerPoint != null) {
          val (x, y) = centerPoint.split(",").map { it.toInt() }
          return listOf(
            TapOnPointTrailblazeTool(
              x = x,
              y = y,
              longPress = longPress,
              reasoning = reasoning,
            ),
          )
        }
      }
    }

    return listOf(
      TapOnByElementSelector(
        reason = reasoning,
        selector = selectorWithStrategy.selector,
        longPress = longPress,
        nodeSelector = if (mode == NodeSelectorMode.FORCE_LEGACY) null else nodeSelector,
      ),
    )
  }
}
