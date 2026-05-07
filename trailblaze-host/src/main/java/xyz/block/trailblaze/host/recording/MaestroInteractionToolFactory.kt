package xyz.block.trailblaze.host.recording

import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.TrailblazeNodeSelectorGenerator
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.recording.InteractionToolFactory
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.InputTextTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.PressKeyTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.SwipeWithRelativeCoordinatesTool
import xyz.block.trailblaze.toolcalls.commands.TapOnByElementSelector
import xyz.block.trailblaze.toolcalls.commands.TapOnPointTrailblazeTool
import xyz.block.trailblaze.toolcalls.toolName

/**
 * Creates Maestro tool instances from user input events during interactive recording.
 *
 * Tap resolution is driven entirely off the [TrailblazeNode] tree:
 *  - When [TrailblazeNodeSelectorGenerator.resolveFromTap] finds a round-trip-valid target,
 *    emit [TapOnByElementSelector] with a `nodeSelector` (`androidMaestro`,
 *    `androidAccessibility`, or `iosMaestro` per driver). The legacy flat `selector:` field
 *    is intentionally left null — it's part of the Maestro `ViewHierarchyTreeNode` surface
 *    that's being retired, and recordings should not carry it forward.
 *  - When no node is at the tap point, or the selector fails round-trip verification, emit
 *    [TapOnPointTrailblazeTool]. That isn't a legacy fallback — coordinate taps are the
 *    correct primitive for "user tapped empty space" and for cases where the layout doesn't
 *    expose a stable target. Refusing to record those would just lose data.
 *
 * The [ViewHierarchyTreeNode] parameter from the parent interface is intentionally unused
 * here. It exists for the Playwright recorder's ARIA-ref resolver; the Maestro path no
 * longer consults it.
 */
class MaestroInteractionToolFactory(
  private val deviceWidth: Int,
  private val deviceHeight: Int,
) : InteractionToolFactory {

  override fun createTapTool(
    node: ViewHierarchyTreeNode?,
    x: Int,
    y: Int,
    trailblazeNodeTree: TrailblazeNode?,
  ): Pair<TrailblazeTool, String> = createSelectorTapOrPoint(trailblazeNodeTree, x, y, longPress = false)

  override fun createLongPressTool(
    node: ViewHierarchyTreeNode?,
    x: Int,
    y: Int,
    trailblazeNodeTree: TrailblazeNode?,
  ): Pair<TrailblazeTool, String> = createSelectorTapOrPoint(trailblazeNodeTree, x, y, longPress = true)

  /**
   * Round-trip validation matters: the generator can produce a selector that resolves to a
   * different node when a parent intercepts the tap. Falling through to [TapOnPointTrailblazeTool]
   * keeps the recording faithful to the actual tap target instead of silently substituting
   * a parent the user never meant to tap.
   */
  private fun createSelectorTapOrPoint(
    trailblazeNodeTree: TrailblazeNode?,
    x: Int,
    y: Int,
    longPress: Boolean,
  ): Pair<TrailblazeTool, String> {
    val tree = trailblazeNodeTree
    val resolution = tree?.let { TrailblazeNodeSelectorGenerator.resolveFromTap(it, x, y) }
    return if (resolution != null && resolution.roundTripValid) {
      TapOnByElementSelector(
        nodeSelector = resolution.selector,
        longPress = longPress,
      ) to TapOnByElementSelector::class.toolName().toolName
    } else {
      TapOnPointTrailblazeTool(x = x, y = y, longPress = longPress) to TapOnPointTrailblazeTool::class.toolName().toolName
    }
  }

  override fun createSwipeTool(
    startX: Int,
    startY: Int,
    endX: Int,
    endY: Int,
    durationMs: Long?,
  ): Pair<TrailblazeTool, String> {
    val startXPct = ((startX.toFloat() / deviceWidth) * 100).toInt()
    val startYPct = ((startY.toFloat() / deviceHeight) * 100).toInt()
    val endXPct = ((endX.toFloat() / deviceWidth) * 100).toInt()
    val endYPct = ((endY.toFloat() / deviceHeight) * 100).toInt()

    @Suppress("DEPRECATION")
    return SwipeWithRelativeCoordinatesTool(
      startRelative = "$startXPct%, $startYPct%",
      endRelative = "$endXPct%, $endYPct%",
      durationMs = durationMs,
    ) to SwipeWithRelativeCoordinatesTool::class.toolName().toolName
  }

  override fun createInputTextTool(text: String): Pair<TrailblazeTool, String> {
    return InputTextTrailblazeTool(text = text) to InputTextTrailblazeTool::class.toolName().toolName
  }

  override fun createPressKeyTool(key: String): Pair<TrailblazeTool, String>? {
    val keyCode = when (key) {
      "Back" -> PressKeyTrailblazeTool.PressKeyCode.BACK
      "Enter" -> PressKeyTrailblazeTool.PressKeyCode.ENTER
      "Home" -> PressKeyTrailblazeTool.PressKeyCode.HOME
      else -> return null // Unsupported key for Maestro recording
    }
    return PressKeyTrailblazeTool(keyCode = keyCode) to PressKeyTrailblazeTool::class.toolName().toolName
  }

  /**
   * Mirrors `./trailblaze waypoint suggest-selector --at` so the recording UI can offer the
   * author the same alternatives. The default best-first entry matches what [createTapTool]
   * already emits; subsequent entries let the author swap to a more semantic strategy
   * (e.g. trade `index: 1` for a parent-anchored selector or a className+text combination)
   * without leaving the recorder.
   *
   * Empty when no node was hit at (x, y) — no target means nothing to generate selectors for.
   *
   * Round-trip-failed cases (the cascade bailed [createTapTool] to `tapOnPoint`) still surface
   * candidates here so the picker UI can offer "promote this coordinate tap to a selector tap"
   * if the author wants to override. The UI shows a warning chip in that mode (signalled by
   * the recorded tool being [TapOnPointTrailblazeTool]) so the author knows they're overriding
   * a fallback, not picking from a verified set.
   */
  override fun findSelectorCandidates(
    trailblazeNodeTree: TrailblazeNode?,
    x: Int,
    y: Int,
  ): List<TrailblazeNodeSelectorGenerator.NamedSelector> {
    val tree = trailblazeNodeTree ?: return emptyList()
    val resolution = TrailblazeNodeSelectorGenerator.resolveFromTap(tree, x, y) ?: return emptyList()
    return TrailblazeNodeSelectorGenerator.findAllValidSelectors(
      root = tree,
      target = resolution.targetNode,
      maxResults = 5,
    )
  }
}
