package xyz.block.trailblaze.revyl.tools

import xyz.block.trailblaze.toolcalls.TrailblazeToolSet
import xyz.block.trailblaze.toolcalls.TrailblazeToolSet.DynamicTrailblazeToolSet
import xyz.block.trailblaze.toolcalls.commands.ObjectiveStatusTrailblazeTool

/**
 * Tool sets for the Revyl cloud device agent.
 *
 * Provides mobile-native tools that operate against Revyl cloud devices
 * using natural language targeting and AI-powered visual grounding.
 */
object RevylNativeToolSet {

  /** Core tools for mobile interaction -- tap, type, swipe, navigate, etc. */
  val CoreToolSet =
    DynamicTrailblazeToolSet(
      name = "Revyl Native Core",
      toolClasses =
        setOf(
          RevylNativeTapTool::class,
          RevylNativeTypeTool::class,
          RevylNativeSwipeTool::class,
          RevylNativeLongPressTool::class,
          RevylNativeScreenshotTool::class,
          RevylNativeNavigateTool::class,
          RevylNativeBackTool::class,
          RevylNativePressKeyTool::class,
          ObjectiveStatusTrailblazeTool::class,
        ),
    )

  /** Revyl assertion tools for visual verification. */
  val AssertionToolSet =
    DynamicTrailblazeToolSet(
      name = "Revyl Native Assertions",
      toolClasses =
        setOf(
          RevylNativeAssertTool::class,
        ),
    )

  /** Full LLM tool set -- core tools plus assertions and memory tools. */
  val LlmToolSet =
    DynamicTrailblazeToolSet(
      name = "Revyl Native LLM",
      toolClasses =
        CoreToolSet.toolClasses +
          AssertionToolSet.toolClasses +
          TrailblazeToolSet.RememberTrailblazeToolSet.toolClasses,
    )
}
