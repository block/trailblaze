package xyz.block.trailblaze.toolcalls

import xyz.block.trailblaze.toolcalls.commands.AssertVisibleByNodeIdTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.AssertVisibleWithAccessibilityTextTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.AssertVisibleWithResourceIdTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.AssertVisibleWithTextTrailblazeTool
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
import xyz.block.trailblaze.toolcalls.commands.memory.AssertEqualsTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.memory.AssertMathTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.memory.AssertNotEqualsTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.memory.AssertWithAiTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.memory.RememberNumberTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.memory.RememberTextTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.memory.RememberWithAiTrailblazeTool
import kotlin.reflect.KClass

@Suppress("ktlint:standard:property-naming")
abstract class TrailblazeToolSet(
  val tools: Set<KClass<out TrailblazeTool>>,
) {

  open val name: String = this::class.annotations
    .filterIsInstance<TrailblazeToolSetClass>()
    .firstOrNull()?.description ?: this::class.simpleName ?: error("A a @TrailblazeToolSetClass annotation")

  fun asTools(): Set<KClass<out TrailblazeTool>> = tools

  companion object {
    fun getSetOfMarkToolSet(setOfMarkEnabled: Boolean): TrailblazeToolSet = if (setOfMarkEnabled) {
      SetOfMarkTrailblazeToolSet
    } else {
      DeviceControlTrailblazeToolSet
    }

    val defaultUiTools = setOf<KClass<out TrailblazeTool>>(
      HideKeyboardTrailblazeTool::class,
      InputTextTrailblazeTool::class,
      ObjectiveStatusTrailblazeTool::class,
      EraseTextTrailblazeTool::class,
      PressBackTrailblazeTool::class,
      SwipeTrailblazeTool::class,
      WaitForIdleSyncTrailblazeTool::class,
      LaunchAppTrailblazeTool::class,
      AssertVisibleByNodeIdTrailblazeTool::class,
    )

    val AllBuiltInTrailblazeToolSets: Set<TrailblazeToolSet> = setOf(
      DeviceControlTrailblazeToolSet,
      InteractWithElementsByPropertyToolSet,
      AssertByPropertyToolSet,
      SetOfMarkTrailblazeToolSet,
      RememberTrailblazeToolSet,
    )

    val AllBuiltInTrailblazeTools: Set<KClass<out TrailblazeTool>> =
      AllBuiltInTrailblazeToolSets.flatMap { it?.asTools() ?: listOf() }.toSet()
  }

  class DynamicTrailblazeToolSet(
    override val name: String,
    tools: Set<KClass<out TrailblazeTool>>,
  ) : TrailblazeToolSet(tools)

  @TrailblazeToolSetClass("Set of Mark Ui Interactions (For Recording) - Do Not Combine with Device Control")
  object SetOfMarkTrailblazeToolSet : TrailblazeToolSet(
    mutableSetOf<KClass<out TrailblazeTool>>().apply {
      addAll(defaultUiTools)
      addAll(setOf(TapOnElementByNodeIdTrailblazeTool::class))
    },
  )

  @TrailblazeToolSetClass("Device Control Ui Interactions - Do Not Combine with Set of Mark")
  object DeviceControlTrailblazeToolSet : TrailblazeToolSet(
    mutableSetOf<KClass<out TrailblazeTool>>().apply {
      addAll(defaultUiTools)
      addAll(
        setOf(
          TapOnPointTrailblazeTool::class,
        ),
      )
    },
  )

  object RememberTrailblazeToolSet : TrailblazeToolSet(
    tools = setOf(
      AssertEqualsTrailblazeTool::class,
      AssertMathTrailblazeTool::class,
      AssertNotEqualsTrailblazeTool::class,
      AssertWithAiTrailblazeTool::class,
      RememberNumberTrailblazeTool::class,
      RememberTextTrailblazeTool::class,
      RememberWithAiTrailblazeTool::class,
    ),
  )

  @TrailblazeToolSetClass("TapOn By Property Toolset")
  object InteractWithElementsByPropertyToolSet : TrailblazeToolSet(
    tools = setOf(
      LongPressOnElementWithTextTrailblazeTool::class,
      LongPressElementWithAccessibilityTextTrailblazeTool::class,
      TapOnElementWithTextTrailblazeTool::class,
      TapOnElementWithAccessiblityTextTrailblazeTool::class,
    ),
  )

  @TrailblazeToolSetClass("Assert By Property Toolset")
  object AssertByPropertyToolSet : TrailblazeToolSet(
    tools = setOf(
      AssertVisibleWithTextTrailblazeTool::class,
      AssertVisibleWithAccessibilityTextTrailblazeTool::class,
      AssertVisibleWithResourceIdTrailblazeTool::class,
    ),
  )
}
