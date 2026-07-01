package xyz.block.trailblaze.toolcalls.commands

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import maestro.Point
import maestro.orchestra.Command
import maestro.orchestra.SwipeCommand
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.toolcalls.MapsToMaestroCommands
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.util.Console

/**
 * Recordable, point-based form of a drag. The ref-based [DragToTrailblazeTool] resolves its source
 * and target to absolute device coordinates and delegates here; this is what lands in recordings.
 *
 * Lowers to a Maestro [SwipeCommand] with explicit start/end points and a long [durationMs] — a
 * drag is a swipe done slowly and deliberately. Absolute points (not relative percentages) are
 * used because both the Android accessibility converter and the iOS AXe converter handle
 * `startPoint`/`endPoint`, whereas AXe silently drops relative-coordinate swipes. `dragTo` is
 * surfaced on the mobile drivers (Android `dispatchGesture`, iOS AXe) via `core_interaction`,
 * matching where `tap`/`longPress` live; a web-native drag is a separate follow-up. Not surfaced
 * to the LLM; reached only via [DragToTrailblazeTool].
 */
@Serializable
@TrailblazeToolClass(
  name = "dragByPoints",
  surfaceToLlm = false,
)
@LLMDescription("Drags between absolute device coordinates. Internal tool only.")
data class DragByPointsTrailblazeTool(
  val startX: Int,
  val startY: Int,
  val endX: Int,
  val endY: Int,
  val durationMs: Long = 1000L,
) : MapsToMaestroCommands() {
  override fun toMaestroCommands(memory: AgentMemory): List<Command> {
    Console.log(
      "DragByPointsTrailblazeTool creating Maestro SwipeCommand: " +
        "($startX, $startY) → ($endX, $endY) over ${durationMs}ms",
    )
    return listOf(
      SwipeCommand(
        startPoint = Point(x = startX, y = startY),
        endPoint = Point(x = endX, y = endY),
        duration = durationMs,
      ),
    )
  }
}
