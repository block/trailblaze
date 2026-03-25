package xyz.block.trailblaze.revyl.tools

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
  val RevylCoreToolSet =
    DynamicTrailblazeToolSet(
      name = "Revyl Native Core",
      toolClasses =
        setOf(
          RevylNativeTapTool::class,
          RevylNativeDoubleTapTool::class,
          RevylNativeTypeTool::class,
          RevylNativeSwipeTool::class,
          RevylNativeNavigateTool::class,
          RevylNativeBackTool::class,
          RevylNativePressKeyTool::class,
          ObjectiveStatusTrailblazeTool::class,
        ),
    )

  /** Revyl assertion tools for visual verification. */
  val RevylAssertionToolSet =
    DynamicTrailblazeToolSet(
      name = "Revyl Native Assertions",
      toolClasses =
        setOf(
          RevylNativeAssertTool::class,
        ),
    )

  /** Full LLM tool set -- core tools plus assertions and memory tools. */
  val RevylLlmToolSet =
    DynamicTrailblazeToolSet(
      name = "Revyl Native LLM",
      toolClasses =
        RevylCoreToolSet.toolClasses +
          RevylAssertionToolSet.toolClasses +
          xyz.block.trailblaze.toolcalls.TrailblazeToolSet.RememberTrailblazeToolSet.toolClasses,
    )
}
