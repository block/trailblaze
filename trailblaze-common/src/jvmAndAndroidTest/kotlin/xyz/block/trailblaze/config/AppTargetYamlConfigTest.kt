package xyz.block.trailblaze.config

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppTargetYamlConfigTest {

  private val yaml = Yaml(
    configuration = YamlConfiguration(strictMode = false, encodeDefaults = false),
  )

  @Test
  fun `parses minimal app target`() {
    val config = yaml.decodeFromString(
      AppTargetYamlConfig.serializer(),
      """
      id: none
      display_name: None
      """.trimIndent(),
    )
    assertEquals("none", config.id)
    assertEquals("None", config.displayName)
    assertNull(config.platforms)
    assertFalse(config.hasCustomIosDriver)
  }

  @Test
  fun `parses full app target with platform-first structure`() {
    val config = yaml.decodeFromString(
      AppTargetYamlConfig.serializer(),
      """
      id: sample
      display_name: Sample App
      has_custom_ios_driver: true
      platforms:
        android:
          app_ids:
            - com.example.development
            - com.example.eng
          tool_sets:
            - setup_tools
            - general_tools
          tools:
            - customDebugTool
          excluded_tools:
            - tapOnPoint
          min_build_version: "67500000"
        ios:
          app_ids:
            - com.example.sample
          tool_sets:
            - setup_tools
          min_build_version: "6515"
        web:
          drivers: [playwright-native]
          tool_sets:
            - web_core
      """.trimIndent(),
    )
    assertEquals("sample", config.id)
    assertEquals("Sample App", config.displayName)
    assertTrue(config.hasCustomIosDriver)

    val platforms = config.platforms!!
    assertEquals(3, platforms.size)

    // Android platform
    val android = platforms["android"]!!
    assertEquals(listOf("com.example.development", "com.example.eng"), android.appIds)
    assertEquals(listOf("setup_tools", "general_tools"), android.toolSets)
    assertEquals(listOf("customDebugTool"), android.tools)
    assertEquals(listOf("tapOnPoint"), android.excludedTools)
    assertEquals("67500000", android.minBuildVersion)
    assertNull(android.drivers)

    // iOS platform
    val ios = platforms["ios"]!!
    assertEquals(listOf("com.example.sample"), ios.appIds)
    assertEquals(listOf("setup_tools"), ios.toolSets)
    assertEquals("6515", ios.minBuildVersion)

    // Web platform
    val web = platforms["web"]!!
    assertEquals(listOf("playwright-native"), web.drivers)
    assertEquals(listOf("web_core"), web.toolSets)
    assertNull(web.appIds)
  }

  @Test
  fun `parses toolset yaml config`() {
    val config = yaml.decodeFromString(
      ToolSetYamlConfig.serializer(),
      """
      id: setup_tools
      description: "Setup tools."
      tools:
        - setup_createAccount
        - setup_saveConfig
      """.trimIndent(),
    )
    assertEquals("setup_tools", config.id)
    assertEquals("Setup tools.", config.description)
    assertEquals(2, config.tools.size)
    assertFalse(config.alwaysEnabled)
  }

  @Test
  fun `parses toolset with platform constraint`() {
    val config = yaml.decodeFromString(
      ToolSetYamlConfig.serializer(),
      """
      id: device_tools
      description: "Device tools."
      platforms:
        - android
      tools:
        - connect_device
      """.trimIndent(),
    )
    assertEquals(listOf("android"), config.platforms)
    assertNull(config.drivers)
  }

  @Test
  fun `ignores unknown YAML fields (forward compatibility)`() {
    val config = yaml.decodeFromString(
      AppTargetYamlConfig.serializer(),
      """
      id: test
      display_name: Test
      future_field: some_value
      nested_future:
        key: value
      """.trimIndent(),
    )
    assertEquals("test", config.id)
  }
}
