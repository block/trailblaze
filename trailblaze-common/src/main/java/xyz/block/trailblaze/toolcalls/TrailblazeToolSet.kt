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
import xyz.block.trailblaze.toolcalls.commands.LongPressOnPointTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.NetworkConnectionTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.ObjectiveStatusTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.PressBackTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.ScrollUntilTextIsVisibleTrailblazeTool
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
import xyz.block.trailblaze.toolcalls.commands.memory.DumpMemoryTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.memory.RememberNumberTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.memory.RememberTextTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.memory.RememberWithAiTrailblazeTool
import kotlin.reflect.KClass

@Suppress("ktlint:standard:property-naming")
abstract class TrailblazeToolSet(
  val tools: Set<KClass<out TrailblazeTool>>,
  open val name: String = this::class.annotations
    .filterIsInstance<TrailblazeToolSetClass>()
    .firstOrNull()?.description ?: this::class.simpleName ?: error("A a @TrailblazeToolSetClass annotation"),
) {

  // Provide a way to add multiple tool sets together
  operator fun plus(otherToolSet: TrailblazeToolSet): TrailblazeToolSet = DynamicToolSet(tools = this.tools + otherToolSet.tools)

  fun asTools(): Set<KClass<out TrailblazeTool>> = tools

  companion object {

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
      NetworkConnectionTrailblazeTool::class,
      ScrollUntilTextIsVisibleTrailblazeTool::class,
    )

    val SetOfMarkTrailblazeToolSet = DynamicTrailblazeToolSet(
      tools = mutableSetOf<KClass<out TrailblazeTool>>().apply {
        addAll(defaultUiTools)
        addAll(setOf(TapOnElementByNodeIdTrailblazeTool::class))
      },
      name = "Set of Mark Ui Interactions (For Recording) - Do Not Combine with Device Control",
    )

    val DeviceControlTrailblazeToolSet = DynamicTrailblazeToolSet(
      tools = mutableSetOf<KClass<out TrailblazeTool>>().apply {
        addAll(defaultUiTools)
        addAll(
          setOf(
            TapOnPointTrailblazeTool::class,
            LongPressOnPointTrailblazeTool::class,
          ),
        )
      },
      name = "Device Control Ui Interactions - Do Not Combine with Set of Mark",
    )

    fun getSetOfMarkToolSet(setOfMarkEnabled: Boolean): TrailblazeToolSet = if (setOfMarkEnabled) {
      SetOfMarkTrailblazeToolSet
    } else {
      DeviceControlTrailblazeToolSet
    }

    val AllBuiltInTrailblazeToolSets: Set<TrailblazeToolSet> = setOf(
      DeviceControlTrailblazeToolSet,
      InteractWithElementsByPropertyToolSet,
      AssertByPropertyToolSet,
      SetOfMarkTrailblazeToolSet,
      RememberTrailblazeToolSet,
    ).also {
      println("All Built In Trailblaze Tool Sets: $it")
    }

    val AllBuiltInTrailblazeTools: Set<KClass<out TrailblazeTool>> =
      AllBuiltInTrailblazeToolSets.flatMap { it.asTools() }.toSet()

    val AllBuiltInTrailblazeToolsByKoogToolDescriptor = AllBuiltInTrailblazeTools
      .associateBy { it.toKoogToolDescriptor() }
  }

  class DynamicTrailblazeToolSet(
    override val name: String,
    tools: Set<KClass<out TrailblazeTool>>,
  ) : TrailblazeToolSet(tools)

  object RememberTrailblazeToolSet : TrailblazeToolSet(
    tools = setOf(
      AssertEqualsTrailblazeTool::class,
      AssertMathTrailblazeTool::class,
      AssertNotEqualsTrailblazeTool::class,
      AssertWithAiTrailblazeTool::class,
      RememberNumberTrailblazeTool::class,
      RememberTextTrailblazeTool::class,
      RememberWithAiTrailblazeTool::class,
      DumpMemoryTrailblazeTool::class,
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

  @TrailblazeToolSetClass("Toolset meant for combining multiple sets together")
  class DynamicToolSet(tools: Set<KClass<out TrailblazeTool>>) : TrailblazeToolSet(tools)
}
