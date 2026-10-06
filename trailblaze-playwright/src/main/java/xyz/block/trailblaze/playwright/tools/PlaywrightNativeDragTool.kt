package xyz.block.trailblaze.playwright.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import com.microsoft.playwright.Locator
import com.microsoft.playwright.Mouse
import com.microsoft.playwright.Page
import com.microsoft.playwright.options.Position
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.playwright.PlaywrightScreenState
import xyz.block.trailblaze.toolcalls.ReasoningTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.util.Console

/**
 * Drag one element and drop it on another element or on a viewport point.
 *
 * An element drop uses Playwright's `Locator.dragTo`; a point drop drives `page.mouse()` from the
 * source's center to the point. Either way Chromium's native drag session is intercepted — so
 * HTML5 drag-and-drop pages receive `dragstart`/`dragover`/`drop`, and pointer-event editors
 * receive real mouse events.
 *
 * The source is the tool's recordable target: `ref` / `nodeSelector` go through the same
 * enrichment as `web_click`, so a recording keeps a durable selector for what was dragged.
 * The drop target is deliberately looser — an ARIA descriptor, a `css=` selector, or a raw
 * viewport point — because drop zones (a grid, a canvas) often have no accessible identity.
 *
 * What it will NOT take as a drop target is a snapshot element ID (`e5`). Those number the
 * elements of the snapshot the caller just read, so they move when the page gains or loses one.
 * The source survives that because enrichment rewrites it into a durable selector before the step
 * is recorded; the drop target gets no such rewrite, so a recorded `dropRef: e5` would drop on
 * whatever holds that ID on the next run. It is refused with the durable form to use instead.
 */
@Serializable
@TrailblazeToolClass("web_drag")
@LLMDescription(
  """
Drag a web element and drop it somewhere else. The source is identified like web_click
(element ID such as 'e5', an ARIA descriptor such as 'button "Table 1"', or 'css=...').
Drop either on another element (dropRef, with optional dropOffsetX/dropOffsetY measured
from that element's top-left corner) or on an absolute viewport point (x, y). Use this for
canvas editors, sortable lists, sliders, and anything else that needs a real press-move-release.
""",
)
data class PlaywrightNativeDragTool(
  @param:LLMDescription(
    "Source element to drag: element ID (e.g., 'e5'), ARIA descriptor (e.g., 'button \"Table 1\"'), " +
      "or CSS selector with css= prefix.",
  )
  val ref: String? = null,
  @param:LLMDescription(
    "Element to drop onto: ARIA descriptor (e.g., 'button \"Table 1\"') or CSS selector with css= " +
      "prefix. Snapshot element IDs such as 'e5' are NOT accepted here — they are positional and " +
      "would not replay. Omit to drop at the viewport point (x, y).",
  )
  val dropRef: String? = null,
  @param:LLMDescription(
    "Horizontal drop offset in pixels from the target element's left edge. Defaults to its center.",
  )
  val dropOffsetX: Int? = null,
  @param:LLMDescription(
    "Vertical drop offset in pixels from the target element's top edge. Defaults to its center.",
  )
  val dropOffsetY: Int? = null,
  @param:LLMDescription("Absolute viewport x to drop at, when no dropRef is given.")
  val x: Int? = null,
  @param:LLMDescription("Absolute viewport y to drop at, when no dropRef is given.")
  val y: Int? = null,
  override val reasoning: String? = null,
  val nodeSelector: TrailblazeNodeSelector? = null,
) : PlaywrightExecutableTool, ReasoningTrailblazeTool {
  override val targetRef: String? get() = ref
  override val targetNodeSelector: TrailblazeNodeSelector? get() = nodeSelector

  // Dragging is how a list gets reordered, which renumbers every ref at or after the drop. The
  // source has to be enriched from the screen as it was BEFORE the drag, or the recorded selector
  // names whatever slid into the source's old position.
  override val renumbersRefs: Boolean get() = true
  override fun withNodeSelector(selector: TrailblazeNodeSelector): PlaywrightExecutableTool =
    copy(ref = null, nodeSelector = selector)

  override suspend fun executeWithPlaywright(
    page: Page,
    context: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    val description = PlaywrightExecutableTool.describeTarget(nodeSelector, ref)
    reasoning?.let { Console.log("### Reasoning: $it") }
    if (dropRef == null && (x == null || y == null)) {
      return TrailblazeToolResult.Error.ExceptionThrown(
        "web_drag needs a drop target: give dropRef, or both x and y.",
      )
    }
    dropRef?.let { positionalDropRefError(it, context) }?.let { return it }
    return try {
      val (source, sourceError) =
        PlaywrightExecutableTool.validateAndResolveRef(page, ref, description, context, nodeSelector)
      if (sourceError != null) return sourceError
      source!!.scrollIntoViewIfNeeded()
      val sourceBox = source.boundingBox()
        ?: return TrailblazeToolResult.Error.ExceptionThrown("Drag source '$description' has no bounding box.")
      val startX = sourceBox.x + sourceBox.width / 2
      val startY = sourceBox.y + sourceBox.height / 2

      val dropDescription = if (dropRef != null) {
        val (target, targetError) =
          PlaywrightExecutableTool.validateAndResolveRef(page, dropRef, "drop target", context)
        if (targetError != null) return targetError
        val targetBox = target!!.boundingBox()
          ?: return TrailblazeToolResult.Error.ExceptionThrown("Drop target '$dropRef' has no bounding box.")
        // Positions are relative to the target's top-left corner.
        val targetPosition = Position(
          dropOffsetX?.toDouble() ?: (targetBox.width / 2),
          dropOffsetY?.toDouble() ?: (targetBox.height / 2),
        )
        Console.log("### Dragging '$description' from ($startX, $startY) to '$dropRef'")
        source.dragTo(target, Locator.DragToOptions().setTargetPosition(targetPosition))
        "'$dropRef'"
      } else {
        // A bare point has no element to hand dragTo, and dragTo's actionability checks would
        // run against whatever element stood in for it: <body> is never visible when it has zero
        // height (an all-absolute canvas app), and a point outside its box hits <html>, so both
        // wait out the timeout. The mouse goes straight to the point instead; Chromium still
        // turns these events into a native drag session. The press still goes through the
        // source's own actionability checks: hover() waits until the source is visible, stable
        // and the element actually under the pointer, so a source covered by an overlay fails
        // here instead of pressing the overlay and reporting a drag that never happened.
        Console.log("### Dragging '$description' from ($startX, $startY) to point ($x, $y)")
        source.hover()
        page.mouse().down()
        page.mouse().move(x!!.toDouble(), y!!.toDouble(), Mouse.MoveOptions().setSteps(DROP_MOVE_STEPS))
        page.mouse().up()
        "point ($x, $y)"
      }

      TrailblazeToolResult.Success(message = "Dragged '$description' to $dropDescription.")
    } catch (e: Exception) {
      TrailblazeToolResult.Error.ExceptionThrown("Drag failed on '$description': ${e.message}")
    }
  }

  companion object {
    /**
     * Intermediate pointer moves on the way to a point drop. Drag-and-drop pages decide where a
     * drop lands from the `dragover`/`mousemove` events they see, so one jump straight to the point
     * can skip the hover a drop zone needs before it accepts the drop.
     */
    private const val DROP_MOVE_STEPS = 10

    /** `e5` / `[e5]` — how a snapshot names the elements it just listed. */
    private val SNAPSHOT_ELEMENT_ID = Regex("""\[?e\d+]?""")

    /**
     * Refuses a positional element ID as the drop target, naming the durable form to use instead.
     * Resolving it against the screen state the caller read lets the message carry that element's
     * own ARIA descriptor, so the fix is a copy-paste rather than another snapshot.
     */
    fun positionalDropRefError(
      dropRef: String,
      context: TrailblazeToolExecutionContext,
    ): TrailblazeToolResult.Error? {
      val trimmed = dropRef.trim()
      if (!SNAPSHOT_ELEMENT_ID.matches(trimmed)) return null
      val resolved = (context.screenState as? PlaywrightScreenState)?.resolveElementId(trimmed)
      val alternative = when {
        resolved == null ->
          "the drop target's ARIA descriptor (e.g. button \"Table 1\"), a css= selector, or x and y"
        resolved.nthIndex == 0 -> "dropRef: '${resolved.descriptor}'"
        else ->
          "a css= selector for it — its descriptor '${resolved.descriptor}' matches several " +
            "elements — or x and y"
      }
      return TrailblazeToolResult.Error.ExceptionThrown(
        "web_drag: dropRef '$trimmed' is a snapshot element ID, which is positional: a recording of " +
          "this step would drop on whatever holds that ID next time. Use $alternative.",
      )
    }
  }
}
