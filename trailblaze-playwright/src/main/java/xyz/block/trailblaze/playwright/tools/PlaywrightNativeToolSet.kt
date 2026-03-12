package xyz.block.trailblaze.playwright.tools

import xyz.block.trailblaze.toolcalls.TrailblazeToolSet
import xyz.block.trailblaze.toolcalls.TrailblazeToolSet.DynamicTrailblazeToolSet
import xyz.block.trailblaze.toolcalls.commands.ObjectiveStatusTrailblazeTool

/**
 * Tool sets for the Playwright-native web agent.
 *
 * Provides web-native tools that operate directly against Playwright,
 * without any Maestro dependency.
 */
object PlaywrightNativeToolSet {

  /** Core tools for web interaction - navigation, clicks, typing, etc. */
  val CoreToolSet =
    DynamicTrailblazeToolSet(
      name = "Playwright Native Core",
      toolClasses =
        setOf(
          PlaywrightNativeNavigateTool::class,
          PlaywrightNativeClickTool::class,
          PlaywrightNativeTypeTool::class,
          PlaywrightNativePressKeyTool::class,
          PlaywrightNativeHoverTool::class,
          PlaywrightNativeSelectOptionTool::class,
          PlaywrightNativeWaitTool::class,
          PlaywrightNativeScrollTool::class,
          PlaywrightNativeSnapshotTool::class,
          PlaywrightNativeRequestDetailsTool::class,
          ObjectiveStatusTrailblazeTool::class,
        ),
    )

  /** Playwright-native test assertion tools. */
  val AssertionToolSet =
    DynamicTrailblazeToolSet(
      name = "Playwright Native Assertions",
      toolClasses =
        setOf(
          PlaywrightNativeVerifyTextVisibleTool::class,
          PlaywrightNativeVerifyElementVisibleTool::class,
          PlaywrightNativeVerifyValueTool::class,
          PlaywrightNativeVerifyListVisibleTool::class,
        ),
    )

  /** Full LLM tool set - core tools plus assertions and memory tools. */
  val LlmToolSet =
    DynamicTrailblazeToolSet(
      name = "Playwright Native LLM",
      toolClasses =
        CoreToolSet.toolClasses +
          AssertionToolSet.toolClasses +
          TrailblazeToolSet.RememberTrailblazeToolSet.toolClasses,
    )
}
