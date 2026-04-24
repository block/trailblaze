package xyz.block.trailblaze.toolcalls.commands

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import maestro.orchestra.Command
import maestro.orchestra.TapOnPointV2Command
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.api.TrailblazeNodeSelectorGenerator
import xyz.block.trailblaze.toolcalls.MapsToMaestroCommands
import xyz.block.trailblaze.toolcalls.ReasoningTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.isSuccess
import xyz.block.trailblaze.util.Console

@Serializable
@TrailblazeToolClass("tapOnPoint")
@LLMDescription("""Tap or long press on the UI at the provided coordinates.""")
data class TapOnPointTrailblazeTool(
  @param:LLMDescription("The center X coordinate for the clickable element")
  val x: Int,
  @param:LLMDescription("The center Y coordinate for the clickable element")
  val y: Int,
  @param:LLMDescription("A standard tap is default, but return 'true' to perform a long press instead.")
  val longPress: Boolean = false,
  override val reasoning: String? = null,
) : MapsToMaestroCommands(), ReasoningTrailblazeTool {

  override fun toMaestroCommands(memory: AgentMemory): List<Command> = listOf(
    TapOnPointV2Command(
      point = "$x,$y",
      longPress = longPress,
    ),
  )

  override suspend fun execute(
    toolExecutionContext: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    // Record-time silent upgrade: if we can resolve (x, y) to a uniquely-identifiable element
    // in the live TrailblazeNode tree, rewrite the *recorded* step as a Maestro-style
    // `tapOn: { selector, relativePoint: "x%,y%" }` (or omit relativePoint for a center tap).
    // The in-session tap still fires at the raw (x, y); this only changes what lands in the
    // YAML trail so replays survive reflow.
    //
    // Reuses TrailblazeNodeSelectorGenerator.resolveFromTap for hit-test ranking, selector
    // generation across all five DriverNodeDetail variants, and round-trip validation. This
    // tool itself is NOT a DelegatingTrailblazeTool — the swap happens via
    // `recordedToolOverride` on the execution context. A follow-up converts this to proper
    // DelegatingTrailblazeTool dispatch.
    toolExecutionContext.screenState?.trailblazeNodeTree?.let { tree ->
      val res = TrailblazeNodeSelectorGenerator.resolveFromTap(tree, x, y)
      if (res != null && res.roundTripValid) {
        val bounds = res.targetNode.bounds
        if (bounds != null && bounds.width > 0 && bounds.height > 0) {
          // Decide whether to elide relativePoint (→ null = center tap at replay).
          //
          // Absolute-pixel (not percent) tolerance so the rule is size-aware: on a 200px
          // button, 2px = 1% (tight, deliberate off-center intent still registers); on a
          // 20px icon, 2px = 10% (generous, since hitting the exact-center pixel of a tiny
          // element is physically unrealistic). Percent-based tolerance collapses to zero on
          // small elements after integer quantization.
          //
          // The "what if the bounds center is covered by a neighbor?" case is already
          // handled upstream: [TrailblazeNodeSelectorGenerator.resolveFromTap] only reports
          // [roundTripValid] = true when hit-testing the selector's resolved center lands
          // back on the target. We gate this whole block on `res.roundTripValid`, so by
          // the time we're deciding elision, tapping at the target's center is known to
          // hit the target — eliding is safe.
          val centerTolPx = CENTER_TOLERANCE_PX
          val isEffectivelyCenter =
            kotlin.math.abs(x - bounds.centerX) <= centerTolPx &&
              kotlin.math.abs(y - bounds.centerY) <= centerTolPx
          val relativePoint = if (isEffectivelyCenter) {
            null
          } else {
            // Use 0.1% precision (not integer) so sub-1% offsets on large elements survive
            // the encode/decode round-trip. E.g. a 3px x-offset on a 400px-wide element is
            // 0.75% — Int-truncated to 50% (center) would silently lose the intent;
            // quantized to tenths it records as "50.8%" and replay lands close to the original.
            "${formatRelativePercent(x - bounds.left, bounds.width)}%," +
              "${formatRelativePercent(y - bounds.top, bounds.height)}%"
          }
          Console.log(
            "### tapOnPoint: upgrading ($x, $y) → tapOn { " +
              "selector=${res.selector.description()}, relativePoint=$relativePoint }",
          )
          toolExecutionContext.recordedToolOverride = TapOnTrailblazeTool(
            selector = res.selector,
            relativePoint = relativePoint,
            longPress = longPress,
          )
        }
      }
    }

    val result = super.execute(toolExecutionContext)
    if (result.isSuccess()) {
      val action = if (longPress) "Long pressed" else "Tapped"
      return TrailblazeToolResult.Success(message = "$action at ($x, $y)")
    }
    return result
  }

  companion object {
    /**
     * Maximum deviation from an element's center (in pixels, each axis) that still counts as
     * a center tap for record-time relativePoint elision. Two pixels is sub-perceptual on any
     * DPI and forgiving enough that a 20px icon's exact-center pixel doesn't need to be hit
     * precisely. On a 200px element this is 1% — a deliberate off-center tap still registers.
     */
    private const val CENTER_TOLERANCE_PX = 2

    /**
     * Computes [offset]/[span] as a percent quantized to tenths, clamped to [0, 100], and
     * formats without a trailing ".0" so whole percents read as "50" (not "50.0") while
     * sub-integer offsets survive as "50.8".
     *
     * Uses pure integer arithmetic with half-up rounding. Floating-point division of
     * innocuous-looking ratios (e.g. 203/400) isn't representable exactly in IEEE-754, and
     * the drift silently flips tie-break cases — `round(203.0/400*100 * 10)` can land on
     * 507 or 508 depending on compiler optimizations. Integer math is deterministic and
     * matches human intuition (203/400 = 50.75 → "50.8").
     */
    internal fun formatRelativePercent(offset: Int, span: Int): String {
      if (span <= 0) return "0"
      // raw = offset * 1000 tenths, plus span/2 for half-up rounding at the tenths
      // granularity. Use Long to keep the multiply clear of Int overflow.
      val raw = offset.toLong() * 1000 + span / 2
      val tenths = (raw / span).coerceIn(0L, 1000L).toInt()
      val whole = tenths / 10
      val decimal = tenths % 10
      return if (decimal == 0) "$whole" else "$whole.$decimal"
    }
  }
}
