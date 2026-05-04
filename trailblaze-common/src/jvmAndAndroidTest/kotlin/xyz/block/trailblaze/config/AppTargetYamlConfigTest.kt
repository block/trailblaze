package xyz.block.trailblaze.config

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
      id: default
      display_name: Default
      """.trimIndent(),
    )
    assertEquals("default", config.id)
    assertEquals("Default", config.displayName)
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
  fun `parses mcp_servers with script entry`() {
    val config = yaml.decodeFromString(
      AppTargetYamlConfig.serializer(),
      """
      id: sample
      display_name: Sample App
      mcp_servers:
        - script: ./tools/sample/login.ts
      """.trimIndent(),
    )
    val servers = config.mcpServers!!
    assertEquals(1, servers.size)
    assertEquals("./tools/sample/login.ts", servers[0].script)
    assertNull(servers[0].command)
    assertTrue(servers[0].isBundleable)
  }

  @Test
  fun `parses mcp_servers with command entry`() {
    val config = yaml.decodeFromString(
      AppTargetYamlConfig.serializer(),
      """
      id: sample
      display_name: Sample App
      mcp_servers:
        - command: python
          args: [./tools/sample/validators.py]
          env:
            API_BASE_URL: https://api.example.com
      """.trimIndent(),
    )
    val server = config.mcpServers!!.single()
    assertNull(server.script)
    assertEquals("python", server.command)
    assertEquals(listOf("./tools/sample/validators.py"), server.args)
    assertEquals(mapOf("API_BASE_URL" to "https://api.example.com"), server.env)
    assertFalse(server.isBundleable)
  }

  @Test
  fun `parses mcp_servers with mixed script and command entries`() {
    val config = yaml.decodeFromString(
      AppTargetYamlConfig.serializer(),
      """
      id: sample
      display_name: Sample App
      mcp_servers:
        - script: ./tools/sample/login.ts
        - command: python
          args: [./tools/sample/validators.py]
          env:
            FOO: bar
      """.trimIndent(),
    )
    val servers = config.mcpServers!!
    assertEquals(2, servers.size)
    // First entry: script — bundleable.
    assertEquals("./tools/sample/login.ts", servers[0].script)
    assertNull(servers[0].command)
    assertTrue(servers[0].isBundleable)
    // Second entry: command — host-only.
    assertNull(servers[1].script)
    assertEquals("python", servers[1].command)
    assertEquals(listOf("./tools/sample/validators.py"), servers[1].args)
    assertEquals(mapOf("FOO" to "bar"), servers[1].env)
    assertFalse(servers[1].isBundleable)
  }

  @Test
  fun `mcp_servers absent is null`() {
    val config = yaml.decodeFromString(
      AppTargetYamlConfig.serializer(),
      """
      id: sample
      display_name: Sample App
      """.trimIndent(),
    )
    assertNull(config.mcpServers)
  }

  @Test
  fun `parses root level inline script tools`() {
    val config = yaml.decodeFromString(
      AppTargetYamlConfig.serializer(),
      """
      id: sample
      display_name: Sample App
      tools:
        - script: ./tools/greet_user.js
          name: greetUser
          description: Greets a user.
          _meta:
            trailblaze/supportedPlatforms:
              - WEB
            trailblaze/toolset: web_core
          inputSchema:
            type: object
            properties:
              customerId:
                type: string
            required:
              - customerId
      """.trimIndent(),
    )

    val tool = config.tools!!.single()
    assertEquals("./tools/greet_user.js", tool.script)
    assertEquals("greetUser", tool.name)
    assertEquals("Greets a user.", tool.description)
    val meta = tool.meta!!
    assertEquals(
      "web_core",
      meta["trailblaze/toolset"]?.jsonPrimitive?.content,
    )
    assertEquals(
      "WEB",
      meta["trailblaze/supportedPlatforms"]!!.jsonArray.single().jsonPrimitive.content,
    )
    val schema = tool.inputSchema
    assertEquals("object", schema["type"]?.jsonPrimitive?.content)
    val properties = schema["properties"]!!.jsonObject
    assertEquals("string", properties["customerId"]!!.jsonObject["type"]?.jsonPrimitive?.content)
  }

  @Test
  fun `mcp_servers entry with neither script nor command fails`() {
    try {
      yaml.decodeFromString(
        AppTargetYamlConfig.serializer(),
        """
        id: sample
        display_name: Sample App
        mcp_servers:
          - args: [foo]
        """.trimIndent(),
      )
      error("Expected parse/validation failure")
    } catch (e: IllegalArgumentException) {
      assertTrue(e.message!!.contains("exactly one of `script:` or `command:`"))
    }
  }

  @Test
  fun `mcp_servers script entry with args fails`() {
    try {
      yaml.decodeFromString(
        AppTargetYamlConfig.serializer(),
        """
        id: sample
        display_name: Sample App
        mcp_servers:
          - script: ./tools/login.ts
            args: [foo]
        """.trimIndent(),
      )
      error("Expected parse/validation failure")
    } catch (e: IllegalArgumentException) {
      assertTrue(e.message!!.contains("`args:` and `env:` are only valid alongside `command:`"))
    }
  }

  @Test
  fun `mcp_servers script entry with env fails`() {
    try {
      yaml.decodeFromString(
        AppTargetYamlConfig.serializer(),
        """
        id: sample
        display_name: Sample App
        mcp_servers:
          - script: ./tools/login.ts
            env:
              FOO: bar
        """.trimIndent(),
      )
      error("Expected parse/validation failure")
    } catch (e: IllegalArgumentException) {
      assertTrue(e.message!!.contains("`args:` and `env:` are only valid alongside `command:`"))
    }
  }

  @Test
  fun `mcp_servers entry with blank script fails`() {
    try {
      yaml.decodeFromString(
        AppTargetYamlConfig.serializer(),
        """
        id: sample
        display_name: Sample App
        mcp_servers:
          - script: ""
        """.trimIndent(),
      )
      error("Expected parse/validation failure")
    } catch (e: IllegalArgumentException) {
      assertTrue(e.message!!.contains("exactly one of `script:` or `command:`"))
    }
  }

  @Test
  fun `mcp_servers entry with both script and command fails`() {
    try {
      yaml.decodeFromString(
        AppTargetYamlConfig.serializer(),
        """
        id: sample
        display_name: Sample App
        mcp_servers:
          - script: ./tools/login.ts
            command: python
        """.trimIndent(),
      )
      error("Expected parse/validation failure")
    } catch (e: IllegalArgumentException) {
      assertTrue(e.message!!.contains("exactly one of `script:` or `command:`"))
    }
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
