package xyz.block.trailblaze.toolcalls.commands

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.api.describe
import xyz.block.trailblaze.exception.TrailblazeToolExecutionException
import xyz.block.trailblaze.toolcalls.DelegatingTrailblazeTool
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.ReasoningTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.util.Console

/**
 * Drags one element to another element (or to a point) in a single continuous gesture.
 *
 * This is a self-contained drag: press the source, move to the destination, and release — all
 * one touch that never lifts mid-gesture. It is NOT "longPress then move" composed from two
 * tools; the press-and-move is one atomic action so the destination receives the dragged item.
 *
 * Ref-based and not separately recordable: resolves the source ref (and target ref, if given) to
 * coordinates from the current snapshot, then delegates to the recordable
 * [DragByPointsTrailblazeTool]. Mirrors how [TapTrailblazeTool] delegates.
 */
@Serializable
@TrailblazeToolClass(name = "dragTo", isRecordable = false)
@LLMDescription(
  "Drag an element by snapshot ref onto another element (`toRef`) or to a point (`toX`/`toY`), " +
    "in one continuous press-move-release gesture. Use to reorder items, move a card, or drag a " +
    "slider.",
)
data class DragToTrailblazeTool(
  @param:LLMDescription("Ref of the element to drag, e.g. 'y778'.")
  val ref: String,
  @param:LLMDescription("Ref of the element to drop onto.")
  val toRef: String? = null,
  @param:LLMDescription("Target X coordinate; set with toY.")
  val toX: Int? = null,
  @param:LLMDescription("Target Y coordinate; set with toX.")
  val toY: Int? = null,
  @param:LLMDescription("Drag duration in ms. Default 1000, slow enough to register as a drag, not a flick.")
  val durationMs: Long = 1000L,
  override val reasoning: String? = null,
) : DelegatingTrailblazeTool, ReasoningTrailblazeTool {

  override fun toExecutableTrailblazeTools(
    executionContext: TrailblazeToolExecutionContext,
  ): List<ExecutableTrailblazeTool> {
    if (toRef != null && (toX != null || toY != null)) {
      throw TrailblazeToolExecutionException(
        message = "dragTo: give the destination as EITHER `toRef` OR `toX`/`toY`, not both.",
        tool = this,
      )
    }
    if (durationMs <= 0) {
      throw TrailblazeToolExecutionException(
        message = "dragTo: durationMs must be positive (got $durationMs).",
        tool = this,
      )
    }
    val screenState = executionContext.screenState
      ?: throw TrailblazeToolExecutionException(message = "dragTo: No screen state available", tool = this)
    val tree = screenState.trailblazeNodeTree
      ?: throw TrailblazeToolExecutionException(
        message = "dragTo: No element tree available. Re-read the view hierarchy appended to this request.",
        tool = this,
      )

    val sourceNode = tree.findFirst { it.ref == ref }
      ?: throw TrailblazeToolExecutionException(
        message = "dragTo: Element ref '$ref' not found on current screen. The screen has " +
          "changed since this ref was last visible. Use a ref from the current view hierarchy instead.",
        tool = this,
      )
    val source = sourceNode.centerPoint()
      ?: throw TrailblazeToolExecutionException(
        message = "dragTo: Source element ref '$ref' found but has no bounds.",
        tool = this,
      )

    val target: Pair<Int, Int> = when {
      toRef != null -> {
        val targetNode = tree.findFirst { it.ref == toRef }
          ?: throw TrailblazeToolExecutionException(
            message = "dragTo: Element ref '$toRef' not found on current screen. " +
              "Use a ref from the current view hierarchy instead.",
            tool = this,
          )
        targetNode.centerPoint()
          ?: throw TrailblazeToolExecutionException(
            message = "dragTo: Target element ref '$toRef' found but has no bounds.",
            tool = this,
          )
      }
      toX != null && toY != null -> toX to toY
      else -> throw TrailblazeToolExecutionException(
        message = "dragTo: provide a destination — either `toRef` (an element) or both `toX` and `toY`.",
        tool = this,
      )
    }

    Console.log(
      "### dragTo: '$ref' (${sourceNode.describe()}) at (${source.first}, ${source.second}) → " +
        (toRef?.let { "'$it' " } ?: "") + "(${target.first}, ${target.second}) over ${durationMs}ms",
    )

    return listOf(
      DragByPointsTrailblazeTool(
        startX = source.first,
        startY = source.second,
        endX = target.first,
        endY = target.second,
        durationMs = durationMs,
      ),
    )
  }
}
