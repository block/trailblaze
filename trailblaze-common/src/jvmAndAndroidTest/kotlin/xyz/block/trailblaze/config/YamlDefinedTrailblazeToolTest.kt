package xyz.block.trailblaze.config

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonElement
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
  fun `eraseText with no caller args drops the key so Maestro erases all`() {
    // Regression test: previously 'default: null' produced 'charactersToErase: null' in the
    // Maestro YAML, which Maestro silently ignores (no-op). The fix removes 'default: null'
    // so the parameter uses DropIfOmitted — the key is absent from the command, which is
    // what triggers Maestro's erase-all behavior.
    val yaml = """
- tools:
    - eraseText: {}
    """.trimIndent()

    val items = TrailblazeYaml.Default.decodeTrail(yaml)
    val tools = (items[0] as TrailYamlItem.ToolTrailItem).tools
    val yamlDefined = tools[0].trailblazeTool as YamlDefinedTrailblazeTool

    val expanded = yamlDefined.toExecutableTrailblazeTools(createContext())
    val yamlText = (expanded[0] as MaestroTrailblazeTool).yaml
    assertThat(yamlText).contains("eraseText")
    // The key must be absent — not 'charactersToErase: null' which Maestro treats as a no-op.
    assertThat(yamlText).transform("should not contain charactersToErase") {
      !it.contains("charactersToErase")
    }.isEqualTo(true)
  }

  @Test
  fun `eraseText with explicit null from LLM drops the key — JsonNull treated as omitted`() {
    // Regression test: when the LLM passes null for an optional param, JsonNull is a
    // non-null Kotlin object. Previously 'caller != null' was true for JsonNull, so it
    // bypassed DropIfOmitted and produced 'key: null' in YAML (no-op in Maestro).
    // The fix treats JsonNull the same as a missing parameter.
    val yaml = """
- tools:
    - eraseText:
        charactersToErase: null
    """.trimIndent()

    val items = TrailblazeYaml.Default.decodeTrail(yaml)
    val tools = (items[0] as TrailYamlItem.ToolTrailItem).tools
    val yamlDefined = tools[0].trailblazeTool as YamlDefinedTrailblazeTool
    // The LLM passed null, which should resolve to DropIfOmitted (same as absent).
    assertThat(yamlDefined.params.containsKey("charactersToErase")).isEqualTo(true)

    val expanded = yamlDefined.toExecutableTrailblazeTools(createContext())
    val yamlText = (expanded[0] as MaestroTrailblazeTool).yaml
    assertThat(yamlText).contains("eraseText")
    assertThat(yamlText).transform("should not contain charactersToErase") {
      !it.contains("charactersToErase")
    }.isEqualTo(true)
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
        - mobile_maestro:
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
        - mobile_maestro:
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

  // ---------------------------------------------------------------------------------------------
  // Interpolation-token PREFIX CONTRACT for `.tool.yaml` bodies.
  //
  // A tool body's `{{...}}` token MUST carry a dotted scope prefix, and today only `params.` is
  // wired up. Each rejection below asserts (a) that expansion FAILS and (b) that the message names
  // enough to be actionable (the offending token/prefix and how to fix it) — never the full exact
  // wording. `params.` is the only accepted prefix; `memory.`/`device.` are recognized-but-future;
  // anything else is an unknown prefix; a bare token with no dot is malformed. Both the
  // whole-value token path (`WHOLE_TOKEN_REGEX`) and the embedded path (`EMBEDDED_TOKEN_REGEX`) in
  // `substitutePrimitive` route through `parseToken`, so the prefix rules hold in both positions.
  // The gap this closes: bare / unknown-prefix / undeclared-name / device had no coverage, and the
  // one prior test claimed "memory and device" but only exercised `memory`.
  // ---------------------------------------------------------------------------------------------

  @Test
  fun `ACCEPTED - params prefix resolves, whole-value and embedded`() {
    // Whole-value token → native-typed substitution; embedded token → inline string rendering.
    val whole = tokenTool(""""{{params.who}}"""", declaresRequired = "who")
    val wholeYaml = (YamlDefinedTrailblazeTool(whole, mapOf("who" to JsonPrimitive("world")))
      .toExecutableTrailblazeTools(createContext()).single() as MaestroTrailblazeTool).yaml
    assertThat(wholeYaml).contains("world")

    val embedded = tokenTool(""""Hi {{params.who}}"""", declaresRequired = "who")
    val embeddedYaml = (YamlDefinedTrailblazeTool(embedded, mapOf("who" to JsonPrimitive("world")))
      .toExecutableTrailblazeTools(createContext()).single() as MaestroTrailblazeTool).yaml
    assertThat(embeddedYaml).contains("Hi world")
  }

  @Test
  fun `REJECTED - bare token with no namespace prefix (embedded position)`() {
    val msg = expandFailureMessage(tokenTool(""""Hi {{who}}""""))
    msg.contains("malformed interpolation token")
    // Names the valid shapes so the author knows the fix is a scope prefix.
    msg.contains("{{params.name}}")
  }

  @Test
  fun `REJECTED - bare token with no namespace prefix (whole-value position)`() {
    val msg = expandFailureMessage(tokenTool(""""{{who}}""""))
    msg.contains("malformed interpolation token")
    msg.contains("{{params.name}}")
  }

  @Test
  fun `REJECTED - memory prefix is recognized but not yet wired up`() {
    expandFailureMessage(tokenTool(""""{{memory.foo}}""""))
      .contains("memory/device interpolation is not yet")
  }

  @Test
  fun `REJECTED - device prefix is recognized but not yet wired up`() {
    expandFailureMessage(tokenTool(""""{{device.foo}}""""))
      .contains("memory/device interpolation is not yet")
  }

  @Test
  fun `REJECTED - an unknown prefix is called out with the supported set`() {
    // `args.` is a planned-but-unshipped scope — today it's just an unknown prefix.
    val msg = expandFailureMessage(tokenTool(""""{{args.foo}}""""))
    msg.contains("unknown interpolation prefix")
    msg.contains("params")
  }

  @Test
  fun `REJECTED - a params prefix naming an undeclared parameter`() {
    // Correct prefix, wrong name: declares `who`, references `{{params.typo}}`.
    val msg = expandFailureMessage(
      tokenTool(""""{{params.typo}}"""", declaresRequired = "who"),
      params = mapOf("who" to JsonPrimitive("world")),
    )
    msg.contains("unknown parameter")
    msg.contains("typo")
  }

  // The name-shape guard (`parseToken`'s second `require`, name matches
  // `[a-zA-Z_][a-zA-Z0-9_]*`) and the two edge positions of a dot (leading / trailing) are
  // distinct branches from the prefix guards above — a correctly-scoped token can still be
  // malformed. These lock those branches so a name-validation regression is caught too.

  @Test
  fun `REJECTED - a params prefix with a hyphenated (invalid) parameter name`() {
    val msg = expandFailureMessage(tokenTool(""""{{params.bad-name}}""""))
    msg.contains("invalid name")
    msg.contains("bad-name")
  }

  @Test
  fun `REJECTED - a params prefix with a digit-leading (invalid) parameter name`() {
    val msg = expandFailureMessage(tokenTool(""""{{params.9abc}}""""))
    msg.contains("invalid name")
    msg.contains("9abc")
  }

  @Test
  fun `REJECTED - a nested dotted name is invalid (no dot-path access today)`() {
    // First `.` splits scope from name, so `{{params.reply.email}}` has name `reply.email`,
    // which the name regex rejects. Pins "no nested paths yet" (a future grammar item).
    val msg = expandFailureMessage(tokenTool(""""{{params.reply.email}}""""))
    msg.contains("invalid name")
    msg.contains("reply.email")
  }

  @Test
  fun `REJECTED - a leading dot is malformed (no scope before the dot)`() {
    val msg = expandFailureMessage(tokenTool(""""{{.foo}}""""))
    msg.contains("malformed interpolation token")
    msg.contains("{{params.name}}")
  }

  @Test
  fun `REJECTED - a trailing dot is malformed (no name after the dot)`() {
    val msg = expandFailureMessage(tokenTool(""""{{foo.}}""""))
    msg.contains("malformed interpolation token")
    msg.contains("{{params.name}}")
  }

  /**
   * Builds a one-command tool whose `inputText.text` is [tokenTextLiteral] (pass it already
   * quoted, e.g. `"\"{{params.who}}\""`). When [declaresRequired] is non-null it declares that one
   * required string parameter; otherwise the tool declares no parameters. Built with explicit
   * indentation (not `trimIndent` interpolation) so the injected multi-line params block can't
   * disturb the common-indent computation.
   */
  private fun tokenTool(tokenTextLiteral: String, declaresRequired: String? = null): ToolYamlConfig {
    val paramsYaml = if (declaresRequired == null) {
      "parameters: []"
    } else {
      "parameters:\n  - name: $declaresRequired\n    type: string\n    required: true"
    }
    val yaml = buildString {
      appendLine("id: greeter")
      appendLine("description: Prefix-contract fixture.")
      appendLine(paramsYaml)
      appendLine("tools:")
      appendLine("  - mobile_maestro:")
      appendLine("      commands:")
      appendLine("        - inputText:")
      appendLine("            text: $tokenTextLiteral")
    }
    return parse(yaml)
  }

  /** Expands [config] and returns an assertk assertion over the resulting failure message. */
  private fun expandFailureMessage(config: ToolYamlConfig, params: Map<String, JsonElement> = emptyMap()) =
    assertFailure {
      YamlDefinedTrailblazeTool(config, params).toExecutableTrailblazeTools(createContext())
    }.transform { it.message ?: "" }

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
