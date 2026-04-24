package xyz.block.trailblaze.toolcalls.commands

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.api.TrailblazeNodeSelectorGenerator
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.api.describe
import xyz.block.trailblaze.exception.TrailblazeToolExecutionException
import xyz.block.trailblaze.toolcalls.DelegatingTrailblazeTool
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.ReasoningTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.viewmatcher.TapSelectorV2.findBestTrailblazeElementSelectorForTargetNodeWithStrategy

/**
 * Asserts an element is visible by its stable hash ref from the snapshot output.
 *
 * Mirrors [TapTrailblazeTool] — the ref (e.g. `y778`) is the same content-hashed
 * id the user sees in compact snapshot output. Resolution goes through the
 * pre-applied [TrailblazeNode.ref] field, then delegates to
 * [AssertVisibleBySelectorTrailblazeTool] which handles node/legacy selector
 * mode switching internally.
 */
@Serializable
@TrailblazeToolClass("assertVisible")
@LLMDescription(
  "Assert an element is visible on screen by its ref ID from the snapshot. Use the " +
    "short hash ref shown in square brackets (e.g., y778 from [y778] \"Network & internet\"). " +
    "These refs are stable across captures of the same screen.",
)
data class AssertVisibleTrailblazeTool(
  @param:LLMDescription("The element ref from the snapshot (e.g., 'y778')")
  val ref: String,
  override val reasoning: String? = null,
) : DelegatingTrailblazeTool, ReasoningTrailblazeTool {

  override fun toExecutableTrailblazeTools(
    executionContext: TrailblazeToolExecutionContext,
  ): List<ExecutableTrailblazeTool> {
    val screenState = executionContext.screenState
      ?: throw TrailblazeToolExecutionException(
        message = "assertVisible: No screen state available",
        tool = this,
      )

    val tree = screenState.trailblazeNodeTree
      ?: throw TrailblazeToolExecutionException(
        message = "assertVisible: No element tree available. Use 'snapshot' first.",
        tool = this,
      )

    // Find the node by its pre-applied ref field (set during compact element list generation).
    // Same lookup pattern as TapTrailblazeTool — do NOT recompute hashes via ElementRef.resolveRef
    // here: its traversal doesn't skip offscreen/dedup the same way and will mismatch on iOS.
    val targetNode = tree.findFirst { it.ref == ref }
      ?: throw TrailblazeToolExecutionException(
        message = "assertVisible: Element ref '$ref' not found on current screen. " +
          "The screen may have changed — use 'snapshot' to get updated refs.",
        tool = this,
      )

    val center = targetNode.centerPoint()
      ?: throw TrailblazeToolExecutionException(
        message = "assertVisible: Element ref '$ref' found but has no bounds.",
        tool = this,
      )

    Console.log(
      "### assertVisible: Resolved '$ref' → ${targetNode.describe()} at (${center.first}, ${center.second})",
    )

    val matchingNode = ViewHierarchyTreeNode.dfs(screenState.viewHierarchy) { node ->
      node.centerPoint?.let {
        val (cx, cy) = it.split(",").map { s -> s.toInt() }
        cx == center.first && cy == center.second
      } ?: false
    } ?: throw TrailblazeToolExecutionException(
      message = "assertVisible: Could not map ref '$ref' to a view-hierarchy node for selector generation.",
      tool = this,
    )

    // Generate a rich TrailblazeNodeSelector where possible; AssertVisibleBySelectorTrailblazeTool
    // honors nodeSelector vs legacy selector based on NodeSelectorMode internally, so we just
    // supply both and let the downstream tool decide.
    val nodeSelector = run {
      val targetTrailblazeNode = tree.hitTest(center.first, center.second)
      targetTrailblazeNode?.let { target ->
        try {
          TrailblazeNodeSelectorGenerator.findBestSelector(tree, target)
        } catch (e: Exception) {
          Console.log(
            "WARNING: TrailblazeNodeSelector generation failed, falling back to legacy selector: ${e.message}",
          )
          null
        }
      }
    }

    val selectorWithStrategy = findBestTrailblazeElementSelectorForTargetNodeWithStrategy(
      root = screenState.viewHierarchy,
      target = matchingNode,
      trailblazeDevicePlatform = screenState.trailblazeDevicePlatform,
      widthPixels = screenState.deviceWidth,
      heightPixels = screenState.deviceHeight,
      spatialHints = null,
    )

    return listOf(
      AssertVisibleBySelectorTrailblazeTool(
        reason = reasoning,
        selector = selectorWithStrategy.selector,
        nodeSelector = nodeSelector,
      ),
    )
  }
}
