package xyz.block.trailblaze.toolcalls

import kotlin.reflect.KClass
import xyz.block.trailblaze.android.tools.SetClipboardTrailblazeTool
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.toolcalls.commands.AssertNotVisibleWithTextTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.SetActiveToolSetsTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.AssertVisibleByNodeIdTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.EraseTextTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.HideKeyboardTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.InputTextTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.LaunchAppTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.NetworkConnectionTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.ObjectiveStatusTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.OpenUrlTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.PasteClipboardTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.PressKeyTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.ScrollUntilTextIsVisibleTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.SwipeTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.TakeSnapshotTool
import xyz.block.trailblaze.toolcalls.commands.TapOnPointTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.TapTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.WaitForIdleSyncTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.memory.AssertEqualsTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.memory.AssertMathTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.memory.AssertNotEqualsTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.memory.AssertWithAiTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.memory.DumpMemoryTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.memory.RememberNumberTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.memory.RememberTextTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.memory.RememberWithAiTrailblazeTool

/**
 * A single entry in the toolset catalog that the LLM can browse and enable.
 */
data class ToolSetCatalogEntry(
  val id: String,
  val description: String,
  val toolClasses: Set<KClass<out TrailblazeTool>>,
  val alwaysEnabled: Boolean = false,
) {
  val toolNames: List<String> by lazy {
    toolClasses.map { it.toolName().toolName }
  }
}

/**
 * Catalog of available TrailblazeTool sets that can be dynamically enabled/disabled.
 *
 * The LLM starts with only the [CORE] toolset and can request additional toolsets
 * via the `setActiveToolSets` MCP tool. Each entry has an ID, description, and
 * the list of tool names so the LLM can preview what's available without loading
 * the full tool definitions.
 */
object TrailblazeToolSetCatalog {

  /**
   * Tools that are always present regardless of which toolsets are enabled.
   * Includes the meta-tool for switching toolsets and the objective status tool.
   */
  val META_TOOLS: Set<KClass<out TrailblazeTool>> = setOf(
    SetActiveToolSetsTrailblazeTool::class,
    ObjectiveStatusTrailblazeTool::class,
  )

  private val CORE_NAVIGATION_TOOLS: Set<KClass<out TrailblazeTool>> = setOf(
    PressKeyTrailblazeTool::class,
    SwipeTrailblazeTool::class,
    ScrollUntilTextIsVisibleTrailblazeTool::class,
  )

  val CORE_SET_OF_MARK = ToolSetCatalogEntry(
    id = "core",
    description = "Essential interaction and navigation tools. Always enabled.",
    toolClasses = META_TOOLS + CORE_NAVIGATION_TOOLS + setOf(
      TapTrailblazeTool::class,
      InputTextTrailblazeTool::class,
    ),
    alwaysEnabled = true,
  )

  val CORE_DEVICE_CONTROL = ToolSetCatalogEntry(
    id = "core-device-control",
    description = "Essential interaction and navigation tools. Always enabled.",
    toolClasses = META_TOOLS + CORE_NAVIGATION_TOOLS + setOf(
      TapOnPointTrailblazeTool::class,
      InputTextTrailblazeTool::class,
    ),
    alwaysEnabled = true,
  )

  val NAVIGATION = ToolSetCatalogEntry(
    id = "navigation",
    description = "Navigate between apps: launch apps, open URLs.",
    toolClasses = setOf(
      LaunchAppTrailblazeTool::class,
      OpenUrlTrailblazeTool::class,
    ),
  )

  val TEXT_EDITING = ToolSetCatalogEntry(
    id = "text-editing",
    description = "Advanced text input: erase text, hide keyboard, clipboard operations.",
    toolClasses = setOf(
      EraseTextTrailblazeTool::class,
      HideKeyboardTrailblazeTool::class,
      PasteClipboardTrailblazeTool::class,
      SetClipboardTrailblazeTool::class,
    ),
  )

  val VERIFICATION = ToolSetCatalogEntry(
    id = "verification",
    description = "Assert UI state: verify elements are visible or not visible on screen.",
    toolClasses = setOf(
      AssertVisibleByNodeIdTrailblazeTool::class,
      AssertNotVisibleWithTextTrailblazeTool::class,
    ),
  )

  val MEMORY = ToolSetCatalogEntry(
    id = "memory",
    description = "Remember values and assert against them: store text/numbers, compare values, math assertions, AI-powered assertions.",
    toolClasses = setOf(
      RememberTextTrailblazeTool::class,
      RememberNumberTrailblazeTool::class,
      RememberWithAiTrailblazeTool::class,
      AssertEqualsTrailblazeTool::class,
      AssertMathTrailblazeTool::class,
      AssertNotEqualsTrailblazeTool::class,
      AssertWithAiTrailblazeTool::class,
      DumpMemoryTrailblazeTool::class,
    ),
  )

  val ADVANCED = ToolSetCatalogEntry(
    id = "advanced",
    description = "Advanced device control: take screenshots, wait for idle, toggle network.",
    toolClasses = setOf(
      TakeSnapshotTool::class,
      WaitForIdleSyncTrailblazeTool::class,
      NetworkConnectionTrailblazeTool::class,
    ),
  )

  /**
   * Framework-level tool exclusions by driver type.
   *
   * These apply to ALL app targets regardless of their [TrailblazeHostAppTarget.getExcludedToolsForDriver]
   * overrides. Consumers should merge these with target-specific exclusions.
   */
  fun getFrameworkExcludedTools(driverType: TrailblazeDriverType): Set<KClass<out TrailblazeTool>> =
    emptySet()

  /** Returns the default catalog entries. */
  fun defaultEntries(): List<ToolSetCatalogEntry> {
    return listOf(CORE_SET_OF_MARK, NAVIGATION, TEXT_EDITING, VERIFICATION, MEMORY, ADVANCED)
  }

  /**
   * Resolves a set of toolset IDs to the combined set of tool classes.
   * Always includes the core toolset.
   */
  fun resolve(
    requestedIds: List<String>,
    catalog: List<ToolSetCatalogEntry>,
  ): Set<KClass<out TrailblazeTool>> {
    val alwaysEnabled = catalog.filter { it.alwaysEnabled }.flatMap { it.toolClasses }.toSet()
    val requested = catalog
      .filter { it.id in requestedIds }
      .flatMap { it.toolClasses }
      .toSet()
    return alwaysEnabled + requested
  }

  /**
   * Formats the catalog as a human-readable summary for embedding in tool descriptions.
   */
  fun formatCatalogSummary(catalog: List<ToolSetCatalogEntry>): String = buildString {
    appendLine("Available toolsets:")
    for (entry in catalog) {
      val toolNamesPreview = entry.toolNames.joinToString(", ")
      val marker = if (entry.alwaysEnabled) " [always enabled]" else ""
      appendLine("- **${entry.id}**$marker: ${entry.description} Tools: [$toolNamesPreview]")
    }
  }
}
