package xyz.block.trailblaze.ios.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import maestro.orchestra.Command
import maestro.orchestra.OpenLinkCommand
import xyz.block.trailblaze.toolcalls.MapsToMaestroCommands
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass

/**
 * Opens a URL or deep link on an iOS simulator, routed by the OS to whichever app handles it. The
 * iOS half of `openUrl`; Android uses `am start` through `android_adbShell`.
 *
 * Runs as each iOS driver's own open-link action rather than calling simctl directly, so the
 * driver drops its cached screen and waits for the destination to render before the next step
 * reads it.
 */
@Serializable
@TrailblazeToolClass(
  name = "ios_openUrl",
  surfaceToLlm = false,
)
@LLMDescription(
  "Opens a URL or deep link on an iOS simulator. Agents use `openUrl`.",
)
data class IosOpenUrlTrailblazeTool(
  @param:LLMDescription("The URL or deep link to open (e.g. `https://example.com`, `myapp://route`).")
  val url: String,
) : MapsToMaestroCommands() {
  override fun toMaestroCommands(): List<Command> = listOf(OpenLinkCommand(link = url))
}
