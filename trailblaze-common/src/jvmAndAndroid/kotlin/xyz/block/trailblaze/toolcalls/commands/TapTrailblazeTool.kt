package xyz.block.trailblaze.toolcalls.commands

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.api.TrailblazeNodeSelectorGenerator
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.api.describe
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
import xyz.block.trailblaze.viewmatcher.strategies.IndexStrategy

/**
 * Taps an element by its stable hash ref from the snapshot output.
 *
 * The ref (e.g., `y778`) is a content-hashed ID that remains stable across
 * captures of the same screen. Resolution finds the node by its pre-applied
 * [TrailblazeNode.ref] field, set during compact element list generation.
 *
 * Works both on-device (inner agent) and host-side (CLI).
 */
@Serializable
@TrailblazeToolClass("tap")
@LLMDescription(
  "Tap an element by its ref ID from the snapshot. Use the short hash ref shown in " +
    "square brackets (e.g., y778 from [y778] \"Network & internet\"). " +
    "These refs are stable across captures of the same screen.",
)
data class TapTrailblazeTool(
  @param:LLMDescription("The element ref from the snapshot (e.g., 'y778')")
  val ref: String,
  @param:LLMDescription("Set to true for a long press instead of a tap.")
  val longPress: Boolean = false,
  override val reasoning: String? = null,
) : DelegatingTrailblazeTool, ReasoningTrailblazeTool {

  override fun toExecutableTrailblazeTools(
    executionContext: TrailblazeToolExecutionContext,
  ): List<ExecutableTrailblazeTool> {
    val screenState = executionContext.screenState
      ?: throw TrailblazeToolExecutionException(
        message = "tap: No screen state available",
        tool = this,
      )

    val tree = screenState.trailblazeNodeTree
      ?: throw TrailblazeToolExecutionException(
        message = "tap: No element tree available. Use 'snapshot' first.",
        tool = this,
      )

    // Find the node by its pre-applied ref field (set during compact element list generation).
    // This is authoritative — the compact list assigns refs and the tree carries them.
    // Do NOT use ElementRef.resolveRef() here: it recomputes hashes from scratch with a
    // different traversal (no offscreen/dedup skipping), causing ref mismatches on iOS
    // screens with many offscreen elements.
    val targetNode = tree.findFirst { it.ref == ref }
      ?: throw TrailblazeToolExecutionException(
        message = "tap: Element ref '$ref' not found on current screen. " +
          "The screen may have changed — use 'snapshot' to get updated refs.",
        tool = this,
      )

    val center = targetNode.centerPoint()
      ?: throw TrailblazeToolExecutionException(
        message = "tap: Element ref '$ref' found but has no bounds.",
        tool = this,
      )

    Console.log("### tap: Resolved '$ref' → ${targetNode.describe()} at (${center.first}, ${center.second})")

    // Find the corresponding ViewHierarchyTreeNode by center point (needed for legacy selector path)
    val matchingNode = ViewHierarchyTreeNode.dfs(screenState.viewHierarchy) { node ->
      node.centerPoint?.let {
        val (cx, cy) = it.split(",").map { s -> s.toInt() }
        cx == center.first && cy == center.second
      } ?: false
    }

    // If we can't find a matching ViewHierarchyTreeNode, fall back to coordinate tap
    if (matchingNode == null) {
      Console.log("### tap: No matching ViewHierarchyTreeNode for ref '$ref', falling back to coordinate tap")
      return listOf(
        TapOnPointTrailblazeTool(
          x = center.first,
          y = center.second,
          longPress = longPress,
          reasoning = reasoning,
        ),
      )
    }

    // Generate a rich TrailblazeNodeSelector if the screen state has a native tree.
    // Hit-test at the center point to find the best target for selector generation
    // (prefers nodes with identifiable properties over propertyless containers).
    val nodeSelector = tree.let { trailblazeTree ->
      val targetTrailblazeNode = trailblazeTree.hitTest(center.first, center.second)
      targetTrailblazeNode?.let { target ->
        try {
          TrailblazeNodeSelectorGenerator.findBestSelector(trailblazeTree, target)
        } catch (e: Exception) {
          Console.log(
            "WARNING: TrailblazeNodeSelector generation failed, falling back to legacy selector: ${e.message}",
          )
          null
        }
      }
    }

    val mode = executionContext.nodeSelectorMode

    // FORCE_LEGACY: skip nodeSelector entirely, always use TapSelectorV2
    // FORCE_NODE_SELECTOR: use nodeSelector; fall through to legacy if generation fails
    // PREFER_NODE_SELECTOR: iOS uses nodeSelector when available; non-iOS uses TapSelectorV2
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
        if (screenState.trailblazeDevicePlatform == TrailblazeDevicePlatform.IOS &&
          nodeSelector != null
        ) {
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
      spatialHints = null,
    )

    // If IndexStrategy was used with a high index (> 10), the selector is fragile.
    // High indices depend on exact element ordering in the hierarchy which can change.
    // Low indices (≤ 10) are usually stable enough to be useful.
    // Fall back to tapping by coordinates for high indices.
    if (selectorWithStrategy.strategyName == IndexStrategy.name) {
      val index = selectorWithStrategy.selector.index?.toIntOrNull()
      if (index != null && index > 10) {
        return listOf(
          TapOnPointTrailblazeTool(
            x = center.first,
            y = center.second,
            longPress = longPress,
            reasoning = reasoning,
          ),
        )
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
