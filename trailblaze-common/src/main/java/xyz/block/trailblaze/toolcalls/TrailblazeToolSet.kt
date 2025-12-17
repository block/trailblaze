package xyz.block.trailblaze.toolcalls

import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.toolcalls.commands.AssertNotVisibleWithTextTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.AssertVisibleByNodeIdTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.AssertVisibleBySelectorTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.AssertVisibleWithAccessibilityTextTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.AssertVisibleWithResourceIdTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.AssertVisibleWithTextTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.EraseTextTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.HideKeyboardTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.InputTextTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.LaunchAppTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.LongPressElementWithAccessibilityTextTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.LongPressOnElementWithTextTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.NetworkConnectionTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.ObjectiveStatusTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.OpenUrlTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.PressBackTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.PressKeyTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.ScrollUntilTextIsVisibleTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.SwipeTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.SwipeWithRelativeCoordinatesTool
import xyz.block.trailblaze.toolcalls.commands.TakeScreenshotTool
import xyz.block.trailblaze.toolcalls.commands.TapOnByElementSelector
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
  open val name: String = this::class.annotations
    .filterIsInstance<TrailblazeToolSetClass>()
    .firstOrNull()?.description ?: this::class.simpleName ?: error("Add a @TrailblazeToolSetClass annotation"),
  val toolClasses: Set<KClass<out TrailblazeTool>>,
  val supportedDriverTypes: Set<TrailblazeDriverType>? = null,
) {

  // Provide a way to add multiple tool sets together
  operator fun plus(otherToolSet: TrailblazeToolSet): TrailblazeToolSet = DynamicToolSet(toolClasses = this.toolClasses + otherToolSet.toolClasses)

  fun asTools(): Set<KClass<out TrailblazeTool>> = toolClasses

  companion object {

    private object DefaultUiTrailblazeToolSet : TrailblazeToolSet(
      name = "Default UI Tools",
      toolClasses = setOf(
        AssertVisibleByNodeIdTrailblazeTool::class,
        AssertNotVisibleWithTextTrailblazeTool::class,
        EraseTextTrailblazeTool::class,
        HideKeyboardTrailblazeTool::class,
        InputTextTrailblazeTool::class,
        LaunchAppTrailblazeTool::class,
        NetworkConnectionTrailblazeTool::class,
        ObjectiveStatusTrailblazeTool::class,
        OpenUrlTrailblazeTool::class,
        PressBackTrailblazeTool::class,
        PressKeyTrailblazeTool::class,
        TakeScreenshotTool::class,
        ScrollUntilTextIsVisibleTrailblazeTool::class,
        SwipeTrailblazeTool::class,
        WaitForIdleSyncTrailblazeTool::class,
      ),
    )

    val DefaultSetOfMarkTrailblazeToolSet = DynamicTrailblazeToolSet(
      name = "Set of Mark Ui Interactions (For Recording) - Do Not Combine with Device Control",
      toolClasses = DefaultUiTrailblazeToolSet.toolClasses + TapOnElementByNodeIdTrailblazeTool::class,
    )

    val DeviceControlTrailblazeToolSet = DynamicTrailblazeToolSet(
      name = "Non-recordable x,y Device Control Ui Interactions - Do Not Combine with Set of Mark",
      toolClasses = DefaultUiTrailblazeToolSet.toolClasses + TapOnPointTrailblazeTool::class,
    )

    fun getSetOfMarkToolSet(setOfMarkEnabled: Boolean): TrailblazeToolSet = if (setOfMarkEnabled) {
      DefaultSetOfMarkTrailblazeToolSet
    } else {
      DeviceControlTrailblazeToolSet
    }

    fun getLlmToolSet(setOfMarkEnabled: Boolean): TrailblazeToolSet = DynamicTrailblazeToolSet(
      name = if (setOfMarkEnabled) "Set-of-Mark LLM Tools" else "Device Control LLM Tools",
      toolClasses = getSetOfMarkToolSet(setOfMarkEnabled).toolClasses +
        RememberTrailblazeToolSet.toolClasses +
        VerifyToolSet.toolClasses,
    )

    val AllDefaultTrailblazeToolSets: Set<TrailblazeToolSet> = setOf(
      DeviceControlTrailblazeToolSet,
      RememberTrailblazeToolSet,
      DefaultSetOfMarkTrailblazeToolSet,
      VerifyToolSet,
    ).also {
      println("All Built In Trailblaze Tool Sets: $it")
    }

    val NonLlmTrailblazeTools: Set<KClass<out TrailblazeTool>> = setOf(
      // Used by recordings, but shouldn't be registered directly to the LLM
      AssertVisibleBySelectorTrailblazeTool::class,
      TapOnByElementSelector::class,
      SwipeWithRelativeCoordinatesTool::class,

      // Deprecated Tools - Tap On by Property
      LongPressOnElementWithTextTrailblazeTool::class,
      LongPressElementWithAccessibilityTextTrailblazeTool::class,
      TapOnElementWithTextTrailblazeTool::class,
      TapOnElementWithAccessiblityTextTrailblazeTool::class,

      // Deprecated Tools - Assert Visible by Property
      AssertVisibleWithTextTrailblazeTool::class,
      AssertVisibleWithAccessibilityTextTrailblazeTool::class,
      AssertVisibleWithResourceIdTrailblazeTool::class,
    )

    val DefaultLlmTrailblazeTools: Set<KClass<out TrailblazeTool>> =
      AllDefaultTrailblazeToolSets.flatMap { it.asTools() }.toSet()

    val AllBuiltInTrailblazeToolsForSerialization: Set<KClass<out TrailblazeTool>> =
      DefaultLlmTrailblazeTools + NonLlmTrailblazeTools

    val AllBuiltInTrailblazeToolsForSerializationByToolName = AllBuiltInTrailblazeToolsForSerialization
      .associateBy { it.toolName() }
  }

  class DynamicTrailblazeToolSet(
    override val name: String,
    toolClasses: Set<KClass<out TrailblazeTool>>,
  ) : TrailblazeToolSet(name = name, toolClasses = toolClasses)

  object RememberTrailblazeToolSet : TrailblazeToolSet(
    toolClasses = setOf(
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

  @TrailblazeToolSetClass("Verify Toolset")
  object VerifyToolSet : TrailblazeToolSet(
    toolClasses = setOf(
      AssertVisibleByNodeIdTrailblazeTool::class,
      AssertNotVisibleWithTextTrailblazeTool::class,
    ),
  )

  @TrailblazeToolSetClass("Toolset meant for combining multiple sets together")
  class DynamicToolSet(
    toolClasses: Set<KClass<out TrailblazeTool>>,
    name: String = "Dynamic Toolset",
  ) : TrailblazeToolSet(
    name = name,
    toolClasses = toolClasses,
  )
}
