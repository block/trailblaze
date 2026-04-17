package xyz.block.trailblaze.toolcalls

import xyz.block.trailblaze.android.tools.AndroidSystemUiDemoModeTrailblazeTool
import xyz.block.trailblaze.mobile.tools.ClearAppDataTrailblazeTool
import xyz.block.trailblaze.android.tools.SetClipboardTrailblazeTool
import xyz.block.trailblaze.android.tools.androidworldbenchmarks.AndroidWorldBenchmarksToolSet
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.toolcalls.commands.AssertNotVisibleWithTextTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.MaestroTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.AssertVisibleByNodeIdTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.AssertVisibleBySelectorTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.AssertVisibleWithAccessibilityTextTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.AssertVisibleWithResourceIdTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.AssertVisibleWithTextTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.EraseTextTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.HideKeyboardTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.InputTextTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.LaunchAppTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.TapTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.LongPressElementWithAccessibilityTextTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.LongPressOnElementWithTextTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.NetworkConnectionTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.ObjectiveStatusTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.OpenUrlTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.PressBackTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.PressKeyTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.PasteClipboardTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.ScrollUntilTextIsVisibleTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.SwipeTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.SwipeWithRelativeCoordinatesTool
import xyz.block.trailblaze.toolcalls.commands.TakeSnapshotTool
import xyz.block.trailblaze.toolcalls.commands.TapOnByElementSelector
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
import xyz.block.trailblaze.util.Console

@Suppress("ktlint:standard:property-naming")
abstract class TrailblazeToolSet(
  open val name: String = this::class.annotations
    .filterIsInstance<TrailblazeToolSetClass>()
    .firstOrNull()?.description ?: this::class.simpleName ?: error("Add a @TrailblazeToolSetClass annotation"),
  val toolClasses: Set<KClass<out TrailblazeTool>>,
  val supportedDriverTypes: Set<TrailblazeDriverType>? = null,
) {

  // Provide a way to add multiple tool sets together
  operator fun plus(otherToolSet: TrailblazeToolSet): TrailblazeToolSet =
    DynamicToolSet(toolClasses = this.toolClasses + otherToolSet.toolClasses)

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
        PasteClipboardTrailblazeTool::class,
        PressBackTrailblazeTool::class,
        PressKeyTrailblazeTool::class,
        TakeSnapshotTool::class,
        TapTrailblazeTool::class,
        ScrollUntilTextIsVisibleTrailblazeTool::class,
        SetClipboardTrailblazeTool::class,
        SwipeTrailblazeTool::class,
        WaitForIdleSyncTrailblazeTool::class,
      ),
    )

    val DefaultSetOfMarkTrailblazeToolSet = DynamicTrailblazeToolSet(
      name = "Set of Mark Ui Interactions (For Recording) - Do Not Combine with Device Control",
      toolClasses = DefaultUiTrailblazeToolSet.toolClasses,
    )

    val DeviceControlTrailblazeToolSet = DynamicTrailblazeToolSet(
      name = "Non-recordable x,y Device Control Ui Interactions - Do Not Combine with Set of Mark",
      toolClasses = DefaultUiTrailblazeToolSet.toolClasses + TapOnPointTrailblazeTool::class,
    )

    fun getSetOfMarkToolSet(): TrailblazeToolSet = DefaultSetOfMarkTrailblazeToolSet

    fun getLlmToolSet(): TrailblazeToolSet = DynamicTrailblazeToolSet(
      name = "Set-of-Mark LLM Tools",
      toolClasses = getSetOfMarkToolSet().toolClasses +
          RememberTrailblazeToolSet.toolClasses +
          VerifyToolSet.toolClasses +
          TrailblazeToolSetCatalog.META_TOOLS,
    )

    val AllDefaultTrailblazeToolSets: Set<TrailblazeToolSet> = setOf(
      DeviceControlTrailblazeToolSet,
      RememberTrailblazeToolSet,
      DefaultSetOfMarkTrailblazeToolSet,
      VerifyToolSet,
    )

    val NonLlmTrailblazeTools: Set<KClass<out TrailblazeTool>> = setOf(
      // Raw Maestro command passthrough - not for LLM, only for legacy/escape-hatch usage
      MaestroTrailblazeTool::class,

      // Used by recordings, but shouldn't be registered directly to the LLM
      AssertVisibleBySelectorTrailblazeTool::class,
      TapOnByElementSelector::class,
      SwipeWithRelativeCoordinatesTool::class,
      AndroidSystemUiDemoModeTrailblazeTool::class,
      ClearAppDataTrailblazeTool::class,

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

    @Deprecated(
      "Use TrailblazeSerializationInitializer.initialize() which discovers all tools from YAML. " +
        "This set only contains built-in mobile/Maestro tools and misses driver-specific tools " +
        "(Playwright, Compose, Revyl) that are now registered via trailblaze-config/tools/*.yaml.",
    )
    val AllBuiltInTrailblazeToolsForSerialization: Set<KClass<out TrailblazeTool>> =
      DefaultLlmTrailblazeTools + NonLlmTrailblazeTools +
          AndroidWorldBenchmarksToolSet.toolClasses +
          TrailblazeToolSetCatalog.META_TOOLS

    @Deprecated(
      "Use TrailblazeSerializationInitializer.initialize() which discovers all tools from YAML.",
    )
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
