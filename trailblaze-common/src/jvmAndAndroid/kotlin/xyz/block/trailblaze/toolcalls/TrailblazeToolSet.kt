package xyz.block.trailblaze.toolcalls

import xyz.block.trailblaze.android.tools.AndroidSystemUiDemoModeTrailblazeTool
import xyz.block.trailblaze.mobile.tools.ClearAppDataTrailblazeTool
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.toolcalls.commands.AssertVisibleBySelectorTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.AssertVisibleWithAccessibilityTextTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.AssertVisibleWithResourceIdTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.AssertVisibleWithTextTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.LongPressElementWithAccessibilityTextTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.LongPressOnElementWithTextTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.MaestroTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.SwipeWithRelativeCoordinatesTool
import xyz.block.trailblaze.toolcalls.commands.TapOnByElementSelector
import xyz.block.trailblaze.toolcalls.commands.TapOnElementWithAccessiblityTextTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.TapOnElementWithTextTrailblazeTool
import kotlin.reflect.KClass

@Suppress("ktlint:standard:property-naming")
abstract class TrailblazeToolSet(
  open val name: String = this::class.annotations
    .filterIsInstance<TrailblazeToolSetClass>()
    .firstOrNull()?.description ?: this::class.simpleName ?: error("Add a @TrailblazeToolSetClass annotation"),
  val toolClasses: Set<KClass<out TrailblazeTool>>,
  val yamlToolNames: Set<ToolName> = emptySet(),
  val supportedDriverTypes: Set<TrailblazeDriverType>? = null,
) {

  // Provide a way to add multiple tool sets together
  operator fun plus(otherToolSet: TrailblazeToolSet): TrailblazeToolSet =
    DynamicToolSet(
      toolClasses = this.toolClasses + otherToolSet.toolClasses,
      yamlToolNames = this.yamlToolNames + otherToolSet.yamlToolNames,
    )

  fun asTools(): Set<KClass<out TrailblazeTool>> = toolClasses

  fun asYamlToolNames(): Set<ToolName> = yamlToolNames

  companion object {

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

    /**
     * Union of every class-backed tool class discoverable from the YAML catalog. Derived
     * directly from [TrailblazeToolSetCatalog.defaultEntries] — no Kotlin overlay — so new
     * YAML toolsets (including `memory`, `verification`, `device_control`) contribute
     * automatically. Used by [xyz.block.trailblaze.model.CustomTrailblazeTools] for tool
     * serialization and by `ToolSetCategory.ALL` in the MCP server.
     */
    val DefaultLlmTrailblazeTools: Set<KClass<out TrailblazeTool>> by lazy {
      TrailblazeToolSetCatalog.defaultEntries().flatMap { it.toolClasses }.toSet()
    }
  }

  class DynamicTrailblazeToolSet(
    override val name: String,
    toolClasses: Set<KClass<out TrailblazeTool>>,
    yamlToolNames: Set<ToolName> = emptySet(),
  ) : TrailblazeToolSet(name = name, toolClasses = toolClasses, yamlToolNames = yamlToolNames)

  @TrailblazeToolSetClass("Toolset meant for combining multiple sets together")
  class DynamicToolSet(
    toolClasses: Set<KClass<out TrailblazeTool>>,
    name: String = "Dynamic Toolset",
    yamlToolNames: Set<ToolName> = emptySet(),
  ) : TrailblazeToolSet(
    name = name,
    toolClasses = toolClasses,
    yamlToolNames = yamlToolNames,
  )
}
