package xyz.block.trailblaze.trailhead

import kotlinx.serialization.json.JsonObject
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext

/**
 * A tool whose runtime can carry a trailhead's `teardown` and `ctx.trailhead` surface. Scripted
 * tools implement it (the in-process QuickJS runtime today); a tool that doesn't simply never takes
 * part in Teardown.
 */
interface TeardownCapableTool {

  /** The tool name, as it appears in the trail's `trailhead:` block. */
  val trailheadToolName: String

  /**
   * Identifies this tool's code, so a hand-off written by one version never reaches another. A
   * scripted tool returns a hash of its bundle source.
   */
  val trailheadCodeVersion: String

  /** Whether this tool defines `trailhead.teardown`. */
  suspend fun definesTeardown(): Boolean

  /**
   * Call the tool's `teardown(args, ctx, trailRun)` with the same resolved [args] the
   * trailhead ran with. Returns the raw result object, or throws when the call failed.
   */
  suspend fun teardown(
    context: TrailblazeToolExecutionContext,
    args: JsonObject,
    trailRun: JsonObject,
  ): JsonObject
}
