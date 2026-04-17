package xyz.block.trailblaze.config

import org.junit.Test
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.toolcalls.commands.SwipeTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.TapTrailblazeTool
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class YamlBackedHostAppTargetTest {

  private val resolver = ToolNameResolver.fromBuiltInAndCustomTools()

  @Test
  fun `minimal app target with no tools or app ids`() {
    val target = AppTargetYamlLoader.loadFromYaml(
      """
      id: none
      display_name: None
      """.trimIndent(),
      toolNameResolver = resolver,
    )
    assertEquals("none", target.id)
    assertEquals("None", target.displayName)
    assertNull(target.getPossibleAppIdsForPlatform(TrailblazeDevicePlatform.ANDROID))
    assertTrue(target.getCustomToolsForDriver(TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION).isEmpty())
    assertFalse(target.hasCustomIosDriver)
  }

  @Test
  fun `app ids resolve by platform`() {
    val target = AppTargetYamlLoader.loadFromYaml(
      """
      id: test
      display_name: Test
      platforms:
        android:
          app_ids:
            - com.example.app.debug
        ios:
          app_ids:
            - com.example.app
      """.trimIndent(),
      toolNameResolver = resolver,
    )
    assertEquals(setOf("com.example.app.debug"), target.getPossibleAppIdsForPlatform(TrailblazeDevicePlatform.ANDROID))
    assertEquals(setOf("com.example.app"), target.getPossibleAppIdsForPlatform(TrailblazeDevicePlatform.IOS))
    assertNull(target.getPossibleAppIdsForPlatform(TrailblazeDevicePlatform.WEB))
  }

  @Test
  fun `toolset scoped by platform section`() {
    val swipeToolSet = ResolvedToolSet(
      config = ToolSetYamlConfig(id = "test_set", tools = listOf("swipe")),
      resolvedToolClasses = setOf(SwipeTrailblazeTool::class),
      compatibleDriverTypes = emptySet(),
    )

    val target = AppTargetYamlLoader.loadFromYaml(
      """
      id: test
      display_name: Test
      platforms:
        android:
          tool_sets: [test_set]
      """.trimIndent(),
      toolNameResolver = resolver,
      availableToolSets = mapOf("test_set" to swipeToolSet),
    )

    assertTrue(target.getCustomToolsForDriver(TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION).contains(SwipeTrailblazeTool::class))
    assertTrue(target.getCustomToolsForDriver(TrailblazeDriverType.IOS_HOST).isEmpty())
  }

  @Test
  fun `excluded tools resolve per platform`() {
    val target = AppTargetYamlLoader.loadFromYaml(
      """
      id: test
      display_name: Test
      platforms:
        ios:
          excluded_tools: [swipe]
      """.trimIndent(),
      toolNameResolver = resolver,
    )

    assertTrue(target.getExcludedToolsForDriver(TrailblazeDriverType.IOS_HOST).contains(SwipeTrailblazeTool::class))
    assertTrue(target.getExcludedToolsForDriver(TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION).isEmpty())
  }

  @Test
  fun `min build version resolves by platform`() {
    val target = AppTargetYamlLoader.loadFromYaml(
      """
      id: test
      display_name: Test
      platforms:
        ios:
          min_build_version: "6515"
        android:
          min_build_version: "67500000"
      """.trimIndent(),
      toolNameResolver = resolver,
    )

    assertEquals("6515", target.getMinBuildVersion(TrailblazeDevicePlatform.IOS))
    assertEquals("67500000", target.getMinBuildVersion(TrailblazeDevicePlatform.ANDROID))
    assertNull(target.getMinBuildVersion(TrailblazeDevicePlatform.WEB))
  }

  @Test
  fun `has_custom_ios_driver flag`() {
    val target = AppTargetYamlLoader.loadFromYaml(
      """
      id: test
      display_name: Test
      has_custom_ios_driver: true
      """.trimIndent(),
      toolNameResolver = resolver,
    )
    assertTrue(target.hasCustomIosDriver)
  }

  @Test
  fun `individual tools list resolves for platform`() {
    val target = AppTargetYamlLoader.loadFromYaml(
      """
      id: test
      display_name: Test
      platforms:
        android:
          tools: [tap]
      """.trimIndent(),
      toolNameResolver = resolver,
    )

    assertTrue(target.getCustomToolsForDriver(TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION).contains(TapTrailblazeTool::class))
    assertTrue(target.getCustomToolsForDriver(TrailblazeDriverType.IOS_HOST).isEmpty())
  }

  @Test
  fun `drivers narrowing within platform section`() {
    val toolSet = ResolvedToolSet(
      config = ToolSetYamlConfig(id = "hw", tools = listOf("tap")),
      resolvedToolClasses = setOf(TapTrailblazeTool::class),
      compatibleDriverTypes = emptySet(),
    )

    val target = AppTargetYamlLoader.loadFromYaml(
      """
      id: test
      display_name: Test
      platforms:
        web:
          drivers: [playwright-native]
          tool_sets: [hw]
      """.trimIndent(),
      toolNameResolver = resolver,
      availableToolSets = mapOf("hw" to toolSet),
    )

    assertTrue(target.getCustomToolsForDriver(TrailblazeDriverType.PLAYWRIGHT_NATIVE).contains(TapTrailblazeTool::class))
    // playwright-electron not included because drivers narrowed to playwright-native only
    assertTrue(target.getCustomToolsForDriver(TrailblazeDriverType.PLAYWRIGHT_ELECTRON).isEmpty())
  }
}
