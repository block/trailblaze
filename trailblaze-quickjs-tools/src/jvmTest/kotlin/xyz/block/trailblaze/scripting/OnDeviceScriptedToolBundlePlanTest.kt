package xyz.block.trailblaze.scripting

import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import xyz.block.trailblaze.config.InlineScriptToolConfig
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.toolcalls.ToolName
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo

/**
 * The target-declared (inline) half of [OnDeviceScriptedToolBundlePlan.resolve] — the reason the
 * planner was split out of the Android launcher in the first place, so it can be exercised without
 * an `AssetManager`.
 *
 * The catalog half needs classpath-discovered descriptors and is covered end-to-end by
 * `OnDeviceAdvertisedToolSurfaceTest` against real staged bundles.
 */
class OnDeviceScriptedToolBundlePlanTest {

  @Test
  fun `a bundle this runtime doesn't carry is dropped rather than failing the session`() {
    // An unavailable tool beats a session that dies loading a missing asset. The tool must also
    // leave `declaredToolNames`, or the launcher would expect an advertisement that never arrives.
    val plan = resolve(target = targetWith(tool("./tools/present.ts", "app_present")), packaged = false)

    assertTrue(plan.bundlePaths.isEmpty(), "unpackaged bundle must not be scheduled for loading")
    assertTrue(plan.declaredToolNames.isEmpty())
    assertTrue(plan.advertisementOverrides.isEmpty())
  }

  @Test
  fun `tools sharing a module load one bundle but are each declared`() {
    // A multi-export module backs many tool names from a single bundle — the distinction the
    // planner's own log line depends on.
    val plan = resolve(
      target = targetWith(
        tool("./tools/shared.ts", "app_first"),
        tool("./tools/shared.ts", "app_second"),
      ),
    )

    assertEquals(1, plan.bundlePaths.size, "one module is one bundle: ${plan.bundlePaths}")
    assertEquals(
      setOf(ToolName("app_first"), ToolName("app_second")),
      plan.declaredToolNames,
    )
  }

  @Test
  fun `a tool already registered in the session is not planned again`() {
    val plan = resolve(
      target = targetWith(tool("./tools/a.ts", "app_a"), tool("./tools/b.ts", "app_b")),
      alreadyRegistered = setOf(ToolName("app_a")),
    )

    assertFalse(ToolName("app_a") in plan.declaredToolNames, "already registered — skip it")
    assertTrue(ToolName("app_b") in plan.declaredToolNames)
    assertEquals(1, plan.bundlePaths.size)
  }

  @Test
  fun `every declared tool carries an advertisement`() {
    // The launcher advertises a declared tool using its override; a declared name with no override
    // would reach the LLM with an empty descriptor.
    val plan = resolve(target = targetWith(tool("./tools/a.ts", "app_a")))

    assertEquals(plan.declaredToolNames, plan.advertisementOverrides.keys)
  }

  private fun resolve(
    target: TrailblazeHostAppTarget,
    alreadyRegistered: Set<ToolName> = emptySet(),
    packaged: Boolean = true,
  ) = OnDeviceScriptedToolBundlePlan.resolve(
    toolRepo = TrailblazeToolRepo(trailblazeToolSet = xyz.block.trailblaze.toolcalls.TrailblazeToolSet.DynamicTrailblazeToolSet("empty", emptySet())),
    target = target,
    alreadyRegistered = alreadyRegistered,
    isPackaged = { packaged },
    logPrefix = "[plan-test]",
  )

  private fun tool(script: String, name: String) = InlineScriptToolConfig(script = script, name = name)

  private fun targetWith(vararg tools: InlineScriptToolConfig) = object : TrailblazeHostAppTarget(
    id = "planapp",
    displayName = "Plan App",
  ) {
    override fun getPossibleAppIdsForPlatform(platform: TrailblazeDevicePlatform): List<String>? =
      if (platform == TrailblazeDevicePlatform.ANDROID) listOf("com.example.planapp") else null

    override fun internalGetCustomToolsForDriver(
      driverType: TrailblazeDriverType,
    ): Set<KClass<out TrailblazeTool>> = emptySet()

    override fun getInlineScriptTools(): List<InlineScriptToolConfig> = tools.toList()
  }
}
