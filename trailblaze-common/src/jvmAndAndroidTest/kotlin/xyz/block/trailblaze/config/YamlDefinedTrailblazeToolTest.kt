package xyz.block.trailblaze.config

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.commands.MaestroTrailblazeTool
import xyz.block.trailblaze.yaml.TrailYamlItem
import xyz.block.trailblaze.yaml.TrailblazeYaml

/**
 * End-to-end: trail YAML containing `- eraseText:` (a `tools:` mode tool) decodes through the
 * polymorphic registry into a [YamlDefinedTrailblazeTool] and its expansion produces a
 * [MaestroTrailblazeTool] with the expected Maestro commands. This exercises the full
 * serializer-registration + interpolation + decode path that was wired up to let a YAML-defined
 * tool replace a Kotlin class 1:1.
 */
class YamlDefinedTrailblazeToolTest {

  @Test
  fun `trail YAML eraseText decodes to YamlDefinedTrailblazeTool and expands with charactersToErase`() {
    val yaml = """
- tools:
    - eraseText:
        charactersToErase: 3
    """.trimIndent()

    val items = TrailblazeYaml.Default.decodeTrail(yaml)
    assertThat(items).hasSize(1)
    val tools = (items[0] as TrailYamlItem.ToolTrailItem).tools
    assertThat(tools).hasSize(1)
    val decoded = tools[0].trailblazeTool
    assertThat(decoded).isInstanceOf(YamlDefinedTrailblazeTool::class)

    val yamlDefined = decoded as YamlDefinedTrailblazeTool
    assertThat(yamlDefined.config.id).isEqualTo("eraseText")
    assertThat(yamlDefined.params["charactersToErase"]).isEqualTo(JsonPrimitive(3))

    val expanded = yamlDefined.toExecutableTrailblazeTools(createContext())
    assertThat(expanded).hasSize(1)
    val maestro = expanded[0]
    assertThat(maestro).isInstanceOf(MaestroTrailblazeTool::class)
    // MaestroTrailblazeTool.yaml holds the Maestro-canonical YAML commands list (stored in
    // JSON flow style by the serializer). Substring checks are stable against both forms.
    val yamlText = (maestro as MaestroTrailblazeTool).yaml
    assertThat(yamlText).contains("eraseText")
    assertThat(yamlText).contains("charactersToErase")
    assertThat(yamlText).contains("3")
  }

  @Test
  fun `eraseText with no caller args substitutes null default`() {
    val yaml = """
- tools:
    - eraseText: {}
    """.trimIndent()

    val items = TrailblazeYaml.Default.decodeTrail(yaml)
    val tools = (items[0] as TrailYamlItem.ToolTrailItem).tools
    val yamlDefined = tools[0].trailblazeTool as YamlDefinedTrailblazeTool

    val expanded = yamlDefined.toExecutableTrailblazeTools(createContext())
    val yamlText = (expanded[0] as MaestroTrailblazeTool).yaml
    // YAML declares `default: null`, so omitted caller arg resolves to explicit null (not
    // key-drop and not missing). Maestro's EraseTextCommand treats null as "erase all".
    assertThat(yamlText).contains("null")
  }

  @Test
  fun `trail YAML with scalar args instead of a map rejects with a clear error`() {
    // Regression guard: `- eraseText: 3` is a YAML scalar, not a map of params.
    // Previously the deserializer silently coerced unknown
    // shapes into an empty params map, so this would execute with declared defaults and
    // mask the author's mistake. After switching the serializer's declared descriptor to
    // a map shape, kaml's own validator rejects the scalar before the custom deserialize
    // path even runs — that's the safer layering.
    val yaml = """
- tools:
    - eraseText: 3
    """.trimIndent()

    assertFailure { TrailblazeYaml.Default.decodeTrail(yaml) }
      .transform { it.message ?: "" }.contains("Expected a map")
  }

  @Test
  fun `required param missing from caller throws`() {
    val config = parse(
      """
      id: needsText
      description: Requires text.
      parameters:
        - name: text
          type: string
          required: true
      tools:
        - maestro:
            commands:
              - inputText:
                  text: "{{params.text}}"
      """.trimIndent(),
    )

    val tool = YamlDefinedTrailblazeTool(config = config, params = emptyMap())

    assertFailure { tool.toExecutableTrailblazeTools(createContext()) }
      .transform { it.message ?: "" }.contains("missing required parameter 'text'")
  }

  @Test
  fun `string-typed default preserves numeric-looking content as a string`() {
    // Regression guard: YamlJsonBridge's generic scalar coercion would turn "00123" into
    // a number, silently mangling string-typed
    // defaults. The presence-aware decoder on TrailblazeToolParameterConfig branches on the
    // declared `type: string` to take the scalar's raw content as-is.
    val config = parse(
      """
      id: testStringDefault
      description: Tool whose default is a numeric-looking string.
      parameters:
        - name: zipCode
          type: string
          required: false
          default: "00123"
      tools:
        - maestro:
            commands:
              - inputText:
                  text: "{{params.zipCode}}"
      """.trimIndent(),
    )

    val zipCodeParam = config.parameters.single { it.name == "zipCode" }
    val defaultValue = (zipCodeParam.default as DefaultBehavior.Use).value as JsonPrimitive
    assertThat(defaultValue.isString).isEqualTo(true)
    assertThat(defaultValue.content).isEqualTo("00123")
  }

  @Test
  fun `memory and device prefixes are rejected in this build`() {
    val config = parse(
      """
      id: usesMemory
      description: References memory — not yet supported.
      parameters: []
      tools:
        - maestro:
            commands:
              - inputText:
                  text: "{{memory.foo}}"
      """.trimIndent(),
    )

    val tool = YamlDefinedTrailblazeTool(config = config, params = emptyMap())

    assertFailure { tool.toExecutableTrailblazeTools(createContext()) }
      .transform { it.message ?: "" }.contains("memory/device interpolation is not yet")
  }

  private fun parse(yaml: String): ToolYamlConfig =
    TrailblazeConfigYaml.instance.decodeFromString(ToolYamlConfig.serializer(), yaml)
      .also { it.validate() }

  private fun createContext(): TrailblazeToolExecutionContext = TrailblazeToolExecutionContext(
    screenState = null,
    traceId = null,
    trailblazeDeviceInfo = TrailblazeDeviceInfo(
      trailblazeDeviceId = TrailblazeDeviceId(
        instanceId = "test-device",
        trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
      ),
      trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
      widthPixels = 1080,
      heightPixels = 1920,
    ),
    sessionProvider = TrailblazeSessionProvider {
      TrailblazeSession(sessionId = SessionId("test-session"), startTime = Clock.System.now())
    },
    trailblazeLogger = TrailblazeLogger.createNoOp(),
    memory = AgentMemory(),
  )
}
