package xyz.block.trailblaze.android.test.tools

import xyz.block.trailblaze.android.test.AndroidTestTarget
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult

/** A Trailblaze tool that executes against an app's in-process Espresso/Compose harness. */
interface AndroidTestExecutableTool : ExecutableTrailblazeTool {
  suspend fun executeWithAndroidTest(
    target: AndroidTestTarget,
    context: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult

  override suspend fun execute(
    toolExecutionContext: TrailblazeToolExecutionContext
  ): TrailblazeToolResult =
    error("AndroidTestExecutableTool must be executed by AndroidTestTrailblazeAgent")
}
