package xyz.block.trailblaze.toolcalls

import xyz.block.trailblaze.toolcalls.commands.EraseTextTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.HideKeyboardTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.InputTextTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.LaunchAppTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.LongPressElementWithAccessibilityTextTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.LongPressOnElementWithTextTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.ObjectiveStatusTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.PressBackTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.SwipeTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.TapOnElementByNodeIdTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.TapOnElementWithAccessiblityTextTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.TapOnElementWithTextTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.TapOnPointTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.WaitForIdleSyncTrailblazeTool
import kotlin.reflect.KClass

class TrailblazeToolSet(
  vararg tools: KClass<out TrailblazeTool>,
) {
  private val toolSet = tools.toSet()
  fun asTools() = toolSet

  companion object {
    val DefaultUiToolSet = TrailblazeToolSet(
      HideKeyboardTrailblazeTool::class,
      InputTextTrailblazeTool::class,
      ObjectiveStatusTrailblazeTool::class,
      EraseTextTrailblazeTool::class,
      PressBackTrailblazeTool::class,
      SwipeTrailblazeTool::class,
      WaitForIdleSyncTrailblazeTool::class,
      LaunchAppTrailblazeTool::class,
    )

    val NonDefaultUiToolSet = TrailblazeToolSet(
      TapOnPointTrailblazeTool::class,
    )

    val InteractWithElementsByPropertyToolSet = TrailblazeToolSet(
      LongPressOnElementWithTextTrailblazeTool::class,
      LongPressElementWithAccessibilityTextTrailblazeTool::class,
      TapOnElementWithTextTrailblazeTool::class,
      TapOnElementWithAccessiblityTextTrailblazeTool::class,
    )

    val InteractWithElementsByNodeIdToolSet = TrailblazeToolSet(
      TapOnElementByNodeIdTrailblazeTool::class,
    )

    val BuiltInTrailblazeToolSets: List<TrailblazeToolSet> = listOf(
      DefaultUiToolSet,
      NonDefaultUiToolSet,
      InteractWithElementsByPropertyToolSet,
      InteractWithElementsByNodeIdToolSet,
    )
    val BuiltInTrailblazeTools: Set<KClass<out TrailblazeTool>> = BuiltInTrailblazeToolSets.flatMap { it.asTools() }.toSet()
  }
}
