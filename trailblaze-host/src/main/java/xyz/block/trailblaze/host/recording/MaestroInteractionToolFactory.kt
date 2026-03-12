package xyz.block.trailblaze.host.recording

import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.recording.InteractionToolFactory
import xyz.block.trailblaze.recording.ViewHierarchyHitTester
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.InputTextTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.PressKeyTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.SwipeWithRelativeCoordinatesTool
import xyz.block.trailblaze.toolcalls.commands.TapOnElementWithTextTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.TapOnPointTrailblazeTool

/**
 * Creates Maestro tool instances from user input events during interactive recording.
 *
 * Resolves taps to element-based tools when the hit-tested node has semantic text,
 * falling back to coordinate-based taps otherwise.
 */
class MaestroInteractionToolFactory(
  private val deviceWidth: Int,
  private val deviceHeight: Int,
) : InteractionToolFactory {

  override fun createTapTool(
    node: ViewHierarchyTreeNode?,
    x: Int,
    y: Int,
  ): Pair<TrailblazeTool, String> {
    val semanticText = node?.let { ViewHierarchyHitTester.resolveSemanticText(it) }
    return if (semanticText != null) {
      @Suppress("DEPRECATION")
      TapOnElementWithTextTrailblazeTool(text = semanticText) to "tapOnElementWithText"
    } else {
      TapOnPointTrailblazeTool(x = x, y = y) to "tapOnPoint"
    }
  }

  override fun createLongPressTool(
    node: ViewHierarchyTreeNode?,
    x: Int,
    y: Int,
  ): Pair<TrailblazeTool, String> {
    return TapOnPointTrailblazeTool(x = x, y = y, longPress = true) to "tapOnPoint"
  }

  override fun createSwipeTool(
    startX: Int,
    startY: Int,
    endX: Int,
    endY: Int,
  ): Pair<TrailblazeTool, String> {
    val startXPct = ((startX.toFloat() / deviceWidth) * 100).toInt()
    val startYPct = ((startY.toFloat() / deviceHeight) * 100).toInt()
    val endXPct = ((endX.toFloat() / deviceWidth) * 100).toInt()
    val endYPct = ((endY.toFloat() / deviceHeight) * 100).toInt()

    @Suppress("DEPRECATION")
    return SwipeWithRelativeCoordinatesTool(
      startRelative = "$startXPct%, $startYPct%",
      endRelative = "$endXPct%, $endYPct%",
    ) to "swipeWithRelativeCoordinates"
  }

  override fun createInputTextTool(text: String): Pair<TrailblazeTool, String> {
    return InputTextTrailblazeTool(text = text) to "inputText"
  }

  override fun createPressKeyTool(key: String): Pair<TrailblazeTool, String>? {
    val keyCode = when (key) {
      "Back" -> PressKeyTrailblazeTool.PressKeyCode.BACK
      "Enter" -> PressKeyTrailblazeTool.PressKeyCode.ENTER
      "Home" -> PressKeyTrailblazeTool.PressKeyCode.HOME
      else -> return null // Unsupported key for Maestro recording
    }
    return PressKeyTrailblazeTool(keyCode = keyCode) to "pressKey"
  }
}
