package xyz.block.trailblaze.toolcalls.commands

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.toolcalls.DelegatingTrailblazeTool
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.ReasoningTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext

/**
 * Press-and-hold (long press) on an element by its snapshot ref.
 *
 * This is the discoverable, first-class long-press affordance for the LLM agent. It is a thin
 * delegate over [TapTrailblazeTool] with `longPress = true`, so it reuses the exact same ref
 * resolution and lowers to the same recordable [TapOnByElementSelector] — no new recorded shape
 * and no driver changes. Surfacing it as its own named tool (rather than relying on the
 * `longPress` boolean flag on `tap`) is what lets the agent reliably choose tap-and-hold.
 */
@Serializable
@TrailblazeToolClass(name = "longPress", isRecordable = false)
@LLMDescription(
  "Press and hold an element by its snapshot ref (e.g. y778 from [y778] \"Profile photo\"). Use " +
    "instead of `tap` when the interaction needs a hold, e.g. a context menu, drag handles, or " +
    "multi-select.",
)
data class LongPressTrailblazeTool(
  @param:LLMDescription("Element ref from the snapshot, e.g. 'y778'.")
  val ref: String,
  override val reasoning: String? = null,
) : DelegatingTrailblazeTool, ReasoningTrailblazeTool {

  override fun toExecutableTrailblazeTools(
    executionContext: TrailblazeToolExecutionContext,
  ): List<ExecutableTrailblazeTool> = TapTrailblazeTool(
    ref = ref,
    longPress = true,
    reasoning = reasoning,
  ).toExecutableTrailblazeTools(executionContext)
}
