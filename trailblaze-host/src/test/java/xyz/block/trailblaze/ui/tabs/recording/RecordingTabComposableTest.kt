package xyz.block.trailblaze.ui.tabs.recording

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.junit.Test
import xyz.block.trailblaze.config.InlineScriptToolConfig
import xyz.block.trailblaze.config.ToolYamlConfig
import xyz.block.trailblaze.config.TrailheadMetadata
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import kotlin.reflect.KClass
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression tests for [computeTargetScopedTrailheads] — pulled out of
 * [RecordingTabComposable]'s `remember` block specifically so this bug class is unit-testable.
 *
 * The bug this file exists to pin: two independent bots (see PR #4404 review) found that a
 * cross-platform scripted tool declared at target-root `target.tools:` (surfaced only via
 * [TrailblazeHostAppTarget.getInlineScriptTools]) was invisible to `targetToolNames`'s membership
 * filter, because [TrailblazeHostAppTarget.getCustomScriptedToolNamesForDriver] only covers names
 * resolved from `platforms.<p>.tools:` / toolsets. A root-declared inline trailhead would
 * synthesize into the candidate list and then get silently dropped.
 */
class RecordingTabComposableTest {

  private fun target(
    inlineScriptTools: List<InlineScriptToolConfig> = emptyList(),
  ): TrailblazeHostAppTarget = object : TrailblazeHostAppTarget(
    id = "testtarget",
    displayName = "Test Target",
  ) {
    override fun getPossibleAppIdsForPlatform(platform: TrailblazeDevicePlatform): List<String>? =
      if (platform == TrailblazeDevicePlatform.ANDROID) listOf("com.example.test") else null

    override fun internalGetCustomToolsForDriver(
      driverType: TrailblazeDriverType,
    ): Set<KClass<out TrailblazeTool>> = emptySet()

    override fun getInlineScriptTools(): List<InlineScriptToolConfig> = inlineScriptTools
  }

  @Test
  fun `returns yaml trailheads unfiltered when target is null`() {
    val yaml = listOf(ToolYamlConfig(id = "yamlTrailhead", trailhead = TrailheadMetadata(to = "app/home")))
    val result = computeTargetScopedTrailheads(
      yamlTrailheads = yaml,
      target = null,
      driverType = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
    )
    assertEquals(yaml, result)
  }

  @Test
  fun `returns yaml trailheads unfiltered when driverType is null`() {
    val yaml = listOf(ToolYamlConfig(id = "yamlTrailhead", trailhead = TrailheadMetadata(to = "app/home")))
    val result = computeTargetScopedTrailheads(
      yamlTrailheads = yaml,
      target = target(),
      driverType = null,
    )
    assertEquals(yaml, result)
  }

  @Test
  fun `a cross-platform trailhead declared at target-root surfaces without a yaml sidecar`() {
    // The exact regression: no *.trailhead.yaml exists for this tool anywhere — trailhead-ness
    // comes entirely from InlineScriptToolConfig.trailhead on a target-root scripted tool. Before
    // the fix, this tool would be dropped by the targetToolNames membership filter because
    // getCustomScriptedToolNamesForDriver (platform-scoped) never sees it.
    val rootTool = InlineScriptToolConfig(
      script = "./tools/rootTrailhead.ts",
      name = "rootTrailhead",
      trailhead = TrailheadMetadata(to = "app/home_signed_in"),
      meta = buildJsonObject {
        put("trailblaze/supportedPlatforms", buildJsonArray { add(JsonPrimitive("ANDROID")) })
      },
    )
    val result = computeTargetScopedTrailheads(
      yamlTrailheads = emptyList(),
      target = target(inlineScriptTools = listOf(rootTool)),
      driverType = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
    )
    assertTrue(
      result.any { it.id == "rootTrailhead" },
      "expected the target-root inline trailhead to survive the scope filter; got: $result",
    )
  }

  @Test
  fun `a target-root trailhead gated to a different platform is excluded for an incompatible driver`() {
    val webOnlyTool = InlineScriptToolConfig(
      script = "./tools/webRootTrailhead.ts",
      name = "webRootTrailhead",
      trailhead = TrailheadMetadata(to = "app/web/home"),
      meta = buildJsonObject {
        put("trailblaze/supportedPlatforms", buildJsonArray { add(JsonPrimitive("WEB")) })
      },
    )
    val result = computeTargetScopedTrailheads(
      yamlTrailheads = emptyList(),
      target = target(inlineScriptTools = listOf(webOnlyTool)),
      driverType = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
    )
    assertFalse(
      result.any { it.id == "webRootTrailhead" },
      "expected a WEB-only root trailhead to be excluded for an ANDROID driver; got: $result",
    )
  }

  @Test
  fun `yaml trailhead wins over a same-id scripted trailhead`() {
    val yamlEntry = ToolYamlConfig(
      id = "sharedId",
      description = "Yaml-authored description",
      trailhead = TrailheadMetadata(to = "app/from_yaml"),
    )
    val scriptedTool = InlineScriptToolConfig(
      script = "./tools/shared.ts",
      name = "sharedId",
      trailhead = TrailheadMetadata(to = "app/from_script"),
      meta = buildJsonObject {
        put("trailblaze/supportedPlatforms", buildJsonArray { add(JsonPrimitive("ANDROID")) })
      },
    )
    val result = computeTargetScopedTrailheads(
      yamlTrailheads = listOf(yamlEntry),
      target = target(inlineScriptTools = listOf(scriptedTool)),
      driverType = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
    )
    val match = result.singleOrNull { it.id == "sharedId" }
      ?: error("expected exactly one 'sharedId' entry; got: $result")
    assertEquals("app/from_yaml", match.trailhead?.to, "expected the yaml entry to win the dedupe")
  }

  @Test
  fun `a scripted tool with no trailhead is not synthesized into the result`() {
    val nonTrailheadTool = InlineScriptToolConfig(
      script = "./tools/plain.ts",
      name = "plainTool",
      meta = buildJsonObject {
        put("trailblaze/supportedPlatforms", buildJsonArray { add(JsonPrimitive("ANDROID")) })
      },
    )
    val result = computeTargetScopedTrailheads(
      yamlTrailheads = emptyList(),
      target = target(inlineScriptTools = listOf(nonTrailheadTool)),
      driverType = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
    )
    assertTrue(result.isEmpty(), "expected no trailheads when no tool declares one; got: $result")
  }
}
