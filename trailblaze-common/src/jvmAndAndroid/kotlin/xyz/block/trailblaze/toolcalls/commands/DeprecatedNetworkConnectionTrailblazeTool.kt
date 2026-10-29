package xyz.block.trailblaze.toolcalls.commands

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult

/**
 * The old name of [NetworkConnectionTrailblazeTool], kept only so trails and scripted tools that
 * still say `networkConnection` keep replaying while they move to `mobile_networkConnection`.
 *
 * The tool was renamed because it only means anything on a mobile device: web and desktop have no
 * radios and refuse it. The fields are identical and the call is forwarded unchanged, so a step
 * means the same thing under either name.
 *
 * Hidden from the LLM so new recordings are written with the new name. Delete this class, its
 * `networkConnection.tool.yaml` and its `core_interaction` entry once no consumer calls it.
 */
@Serializable
@TrailblazeToolClass("networkConnection", surfaceToLlm = false)
@LLMDescription("Deprecated: renamed to mobile_networkConnection, which takes the same fields.")
data class DeprecatedNetworkConnectionTrailblazeTool(
  val wifi: Boolean? = null,
  val cellular: Boolean? = null,
  val bluetooth: Boolean? = null,
  val airplaneMode: Boolean? = null,
) : ExecutableTrailblazeTool {

  override suspend fun execute(
    toolExecutionContext: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult = NetworkConnectionTrailblazeTool(
    wifi = wifi,
    cellular = cellular,
    bluetooth = bluetooth,
    airplaneMode = airplaneMode,
  ).execute(toolExecutionContext)
}
