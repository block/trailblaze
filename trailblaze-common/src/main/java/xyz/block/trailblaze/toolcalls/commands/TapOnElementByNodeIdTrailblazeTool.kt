package xyz.block.trailblaze.toolcalls.commands

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.exception.TrailblazeException
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.MapsToExecutableTrailblazeTools
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext

@Serializable
@TrailblazeToolClass("tapOnElementByNodeId")
@LLMDescription(
  """
Provide the nodeId of the element you want to tap on in the nodeId parameter.
""",
)
data class TapOnElementByNodeIdTrailblazeTool(
  @LLMDescription("Reasoning on why this element was chosen. Do NOT restate the nodeId.")
  val reason: String = "",
  @LLMDescription("The nodeId of the element in the view hierarchy that will be tapped on. Do NOT use the nodeId 0.")
  val nodeId: Long,
  @LLMDescription("A standard tap is default, but return 'true' to perform a long press instead.")
  val longPress: Boolean = false,
) : MapsToExecutableTrailblazeTools {

  private fun prettyPrintViewHierarchy(
    node: ViewHierarchyTreeNode,
    indent: String = "",
  ): String {
    val builder = StringBuilder()
    builder.append(
      "$indent- nodeId=${node.nodeId}, text='${node.text}', accessibilityText='${node.accessibilityText}', bounds=${node.bounds}\n",
    )
    node.children.forEach { child ->
      builder.append(prettyPrintViewHierarchy(child, "$indent  "))
    }
    return builder.toString()
  }

  override fun toExecutableTrailblazeTools(executionContext: TrailblazeToolExecutionContext): List<ExecutableTrailblazeTool> {
    val trailblazeTool = this
    val screenState = executionContext.screenState

    // Make sure we have a view hierarchy to work with
    if (screenState?.viewHierarchy == null) {
      throw TrailblazeException(
        message = "No View Hierarchy available when processing $trailblazeTool",
      )
    }
    // Make sure the nodeId is in the view hierarchy
    val matchingNode = ViewHierarchyTreeNode.dfs(screenState.viewHierarchy) {
      it.nodeId == trailblazeTool.nodeId
    }
    if (matchingNode == null) {
      throw TrailblazeException(
        message = "TapOnElementByNodeId: No node found with nodeId=${trailblazeTool.nodeId}.  $trailblazeTool",
      )
    }

    println("Full View Hierarchy:\n" + prettyPrintViewHierarchy(screenState.viewHierarchy))
    println("TapOnElementByNodeId: Looking for nodeId=${trailblazeTool.nodeId}")

    println("TapOnElementByNodeId: Found node: text='${matchingNode.text}', accessibilityText='${matchingNode.accessibilityText}', bounds=${matchingNode.bounds}")
    val bestTapTrailblazeToolForNode: ExecutableTrailblazeTool = findBestTapTrailblazeToolForNode(
      screenState.viewHierarchyOriginal,
      matchingNode,
      trailblazeTool.longPress,
    )
    println("Selected TrailblazeTool: $bestTapTrailblazeToolForNode")
    return listOf(bestTapTrailblazeToolForNode)
  }
}
