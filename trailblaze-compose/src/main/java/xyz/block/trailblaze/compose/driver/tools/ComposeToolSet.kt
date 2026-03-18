package xyz.block.trailblaze.compose.driver.tools

import xyz.block.trailblaze.toolcalls.TrailblazeToolSet
import xyz.block.trailblaze.toolcalls.TrailblazeToolSet.DynamicTrailblazeToolSet
import xyz.block.trailblaze.toolcalls.commands.ObjectiveStatusTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.TakeSnapshotTool
import xyz.block.trailblaze.toolcalls.toolName

/**
 * Tool sets for the Compose agent.
 *
 * Provides Compose-native tools that operate directly against ComposeUiTest,
 * without any Maestro or Playwright dependency.
 */
object ComposeToolSet {

  /** Core tools for Compose interaction — clicks, typing, snapshots, etc. */
  val CoreToolSet =
    DynamicTrailblazeToolSet(
      name = "Compose Core",
      toolClasses =
        setOf(
          ComposeClickTool::class,
          ComposeTypeTool::class,
          ComposeScrollTool::class,
          ComposeWaitTool::class,
          TakeSnapshotTool::class,
          ObjectiveStatusTrailblazeTool::class,
        ),
    )

  /** Compose test assertion tools. */
  val AssertionToolSet =
    DynamicTrailblazeToolSet(
      name = "Compose Assertions",
      toolClasses =
        setOf(
          ComposeVerifyTextVisibleTool::class,
          ComposeVerifyElementVisibleTool::class,
        ),
    )

  /** Full LLM tool set — core tools plus assertions, memory, and progressive disclosure tools. */
  val LlmToolSet =
    DynamicTrailblazeToolSet(
      name = "Compose LLM",
      toolClasses =
        CoreToolSet.toolClasses +
          AssertionToolSet.toolClasses +
          TrailblazeToolSet.RememberTrailblazeToolSet.toolClasses +
          setOf(ComposeRequestDetailsTool::class),
    )

  /** Compose tool classes keyed by tool name, for JSON serialization registration. */
  val toolClassesByToolName by lazy {
    LlmToolSet.toolClasses.associateBy { it.toolName() }
  }

}
