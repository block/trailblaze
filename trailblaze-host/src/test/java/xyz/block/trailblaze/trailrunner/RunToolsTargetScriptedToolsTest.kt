package xyz.block.trailblaze.trailrunner

import kotlin.reflect.KClass
import kotlin.test.assertEquals
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test
import xyz.block.trailblaze.config.InlineScriptToolConfig
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.toolcalls.ToolName
import xyz.block.trailblaze.toolcalls.TrailblazeTool

/**
 * A trailmap's own `.ts` tools belong to no toolset, so "Tools for this run" can only list them
 * by asking the target. These pin which of them it lists for a driver: the ones the session
 * offers the agent.
 */
class RunToolsTargetScriptedToolsTest {

  private fun tool(name: String, platforms: List<String> = emptyList(), surfaceToLlm: Boolean = true) =
    InlineScriptToolConfig(
      script = "trails/config/trailmaps/probe/tools/$name.ts",
      name = name,
      surfaceToLlm = surfaceToLlm,
      meta = JsonObject(
        if (platforms.isEmpty()) emptyMap()
        else mapOf("trailblaze/supportedPlatforms" to JsonArray(platforms.map(::JsonPrimitive))),
      ),
    )

  private fun target(tools: List<InlineScriptToolConfig>, excluded: Set<String> = emptySet()) =
    object : TrailblazeHostAppTarget(id = "probe", displayName = "Probe") {
      override fun getPossibleAppIdsForPlatform(platform: TrailblazeDevicePlatform): List<String> = emptyList()

      override fun internalGetCustomToolsForDriver(driverType: TrailblazeDriverType): Set<KClass<out TrailblazeTool>> =
        emptySet()

      override fun getInlineScriptTools(): List<InlineScriptToolConfig> = tools

      override fun getExcludedScriptedToolNamesForDriver(driverType: TrailblazeDriverType): Set<ToolName> =
        excluded.mapTo(mutableSetOf(), ::ToolName)
    }

  @Test
  fun `a target's own scripted tools are listed for a driver they register on`() {
    val probe = target(listOf(tool("probe_webOpen", listOf("web")), tool("probe_anywhere")))

    assertEquals(
      setOf("probe_webOpen", "probe_anywhere"),
      targetScriptedToolNames(probe, TrailblazeDriverType.PLAYWRIGHT_NATIVE),
    )
  }

  @Test
  fun `a tool scoped to another platform, hidden from the agent, or opted out is not listed`() {
    val probe = target(
      listOf(
        tool("probe_webOpen", listOf("web")),
        tool("probe_hiddenHelper", surfaceToLlm = false),
        tool("probe_optedOut"),
        tool("probe_androidSwipe", listOf("android")),
      ),
      excluded = setOf("probe_optedOut"),
    )

    assertEquals(
      setOf("probe_androidSwipe"),
      targetScriptedToolNames(probe, TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY),
    )
  }
}
