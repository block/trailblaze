package xyz.block.trailblaze.toolcalls.commands

import kotlinx.serialization.Serializable
import maestro.orchestra.TapOnPointV2Command
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.api.TrailblazeNodeSelectorResolver
import xyz.block.trailblaze.exception.TrailblazeToolExecutionException
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.util.Console

/**
 * Tap on an element identified by [TrailblazeNodeSelector], optionally at a percent-offset
 * within its bounds.
 *
 * ```yaml
 * - tapOn:
 *     selector:
 *       androidAccessibility:
 *         textRegex: "A text with a hyperlink"
 *     relativePoint: "90%,50%"
 * ```
 *
 * The [selector] is the universal [TrailblazeNodeSelector] — any of its `DriverNodeMatch`
 * variants (AndroidAccessibility, AndroidMaestro, IosMaestro, Web, Compose) is supported,
 * because resolution goes through [TrailblazeNodeSelectorResolver].
 *
 * The [relativePoint] field is an optional `"x%,y%"` offset **within the resolved element's
 * bounds**. Omit for center tap. Percentages are clamped to `[0, 100]`. This mirrors
 * [Maestro's `point`](https://docs.maestro.dev/reference/commands-available/tapon#tap-a-coordinate-within-an-element),
 * but named to make the "relative to the element, not the screen" semantics obvious at read
 * time — `point: "90%,50%"` looks like a coordinate; `relativePoint: "90%,50%"` reads as
 * "a point relative to the thing you just selected."
 *
 * This tool is the recorded form of a record-time upgraded [TapOnPointTrailblazeTool], and
 * is also authorable by hand. Replay resolves the selector against the live
 * [TrailblazeNode] tree on [ScreenState], so the offset survives screen reflow —
 * 90% across a wrapping paragraph stays at 90% after the paragraph re-lays out.
 */
@Serializable
@TrailblazeToolClass("tapOn", isForLlm = false)
data class TapOnTrailblazeTool(
  val selector: TrailblazeNodeSelector,
  val relativePoint: String? = null,
  val longPress: Boolean = false,
) : ExecutableTrailblazeTool {

  override suspend fun execute(
    toolExecutionContext: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    val screen = toolExecutionContext.screenState
      ?: throw TrailblazeToolExecutionException(
        message = "tapOn requires screen state; none available.",
        tool = this,
      )
    val tree = screen.trailblazeNodeTree
      ?: throw TrailblazeToolExecutionException(
        message = "tapOn requires a TrailblazeNode tree — this driver doesn't expose one. " +
          "Use a Maestro-selector-based tap (TapOnByElementSelector) instead.",
        tool = this,
      )

    val target = when (val result = TrailblazeNodeSelectorResolver.resolve(tree, selector)) {
      is TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch -> result.node
      is TrailblazeNodeSelectorResolver.ResolveResult.NoMatch -> throw TrailblazeToolExecutionException(
        message = "tapOn: selector matched no element — ${selector.description()}",
        tool = this,
      )
      is TrailblazeNodeSelectorResolver.ResolveResult.MultipleMatches -> throw TrailblazeToolExecutionException(
        message = "tapOn: selector is ambiguous (${result.nodes.size} matches) — " +
          "add spatial, hierarchy, or index disambiguation. Selector: ${selector.description()}",
        tool = this,
      )
    }

    val bounds = target.bounds ?: throw TrailblazeToolExecutionException(
      message = "tapOn: resolved element has no bounds.",
      tool = this,
    )

    val (tapX, tapY) = tapPointIn(bounds, relativePoint, tool = this)
    Console.log("### tapOn: resolved to bounds $bounds → tapping at ($tapX, $tapY)")

    val agent = toolExecutionContext.maestroTrailblazeAgent
      ?: throw TrailblazeToolExecutionException(
        message = "tapOn requires a MaestroTrailblazeAgent for dispatch.",
        tool = this,
      )
    val result = agent.runMaestroCommands(
      maestroCommands = listOf(TapOnPointV2Command(point = "$tapX,$tapY", longPress = longPress)),
      traceId = toolExecutionContext.traceId,
    )
    return if (result is TrailblazeToolResult.Success) {
      val action = if (longPress) "Long pressed" else "Tapped"
      TrailblazeToolResult.Success(message = "$action at ($tapX, $tapY) within element")
    } else {
      result
    }
  }

  companion object {

    /**
     * Computes the tap point inside [bounds] given an optional `"x%,y%"` relative point.
     * Returns the element center when [relativePoint] is null/blank.
     *
     * Uses integer arithmetic for the bounds × percent multiplication so replay rounding
     * matches record-side encoding (see [TapOnPointTrailblazeTool.formatRelativePercent]).
     * The two sides snap to tenths-of-a-percent with half-up rounding, which means a point
     * that records as "50.8%" replays to the same integer pixel on the same bounds — no
     * IEEE-754 drift mismatching record vs. replay.
     */
    internal fun tapPointIn(
      bounds: TrailblazeNode.Bounds,
      relativePoint: String?,
      tool: TrailblazeTool,
    ): Pair<Int, Int> {
      if (relativePoint.isNullOrBlank()) return bounds.centerX to bounds.centerY
      val (pctX, pctY) = parseRelativePoint(relativePoint)
        ?: throw TrailblazeToolExecutionException(
          message = "tapOn: unparseable relativePoint=\"$relativePoint\" — expected \"x%,y%\" (e.g. \"90%,50%\")",
          tool = tool,
        )
      return (bounds.left + offsetFromPercent(bounds.width, pctX)) to
        (bounds.top + offsetFromPercent(bounds.height, pctY))
    }

    /**
     * Returns `round(span × pct / 100)` using integer arithmetic on tenths-of-a-percent,
     * half-up rounding. Quantizes [pct] (a Double from parsing) to tenths first, then does
     * the multiplication in Long to keep the record/replay code paths symmetric.
     */
    private fun offsetFromPercent(span: Int, pct: Double): Int {
      val tenths = kotlin.math.round(pct * 10).toLong().coerceIn(0L, 1000L)
      // span × tenths / 1000, with half-up rounding via + 500 before the integer divide.
      return ((span.toLong() * tenths + 500) / 1000).toInt()
    }

    /**
     * Parses `"90%,50%"` → `(90.0, 50.0)`. Clamps to `[0, 100]`. Tolerates whitespace and
     * raw numbers without `%` (so `"50,50"` works too). Returns null for malformed input.
     */
    internal fun parseRelativePoint(s: String): Pair<Double, Double>? {
      val parts = s.trim().split(",").map { it.trim() }
      if (parts.size != 2) return null
      fun parsePct(p: String): Double? {
        val v = p.removeSuffix("%").trim().toDoubleOrNull() ?: return null
        return v.coerceIn(0.0, 100.0)
      }
      val x = parsePct(parts[0]) ?: return null
      val y = parsePct(parts[1]) ?: return null
      return x to y
    }
  }
}
