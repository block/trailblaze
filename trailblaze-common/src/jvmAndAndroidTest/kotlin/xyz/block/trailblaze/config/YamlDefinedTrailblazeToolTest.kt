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
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.commands.HideKeyboardTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.InputTextTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.MaestroTrailblazeTool
import xyz.block.trailblaze.yaml.TrailYamlItem
import xyz.block.trailblaze.yaml.createTrailblazeYaml

/**
 * End-to-end: trail YAML containing a `tools:` mode tool ([YamlDefinedToolFixture]) decodes through
 * the polymorphic registry into a [YamlDefinedTrailblazeTool], and its expansion produces the
 * class-backed tools it composes. This exercises the full serializer-registration + interpolation +
 * decode path that lets a YAML-defined tool stand in for a Kotlin class.
 */
class YamlDefinedTrailblazeToolTest {

  /** The recorded step's tool wrappers, decoded from a unified doc for the android classifier. */
  private fun decodeRecordedTools(yaml: String) =
    createTrailblazeYaml().decodeTrail(yaml, deviceClassifiers = listOf(TrailblazeDeviceClassifier("android")))
      .filterIsInstance<TrailYamlItem.PromptsTrailItem>().single()
      .promptSteps.single().recording!!.tools

  @Test
  fun `trail YAML for a YAML-defined tool decodes to YamlDefinedTrailblazeTool and expands into its composed tools`() =
    YamlDefinedToolFixture.registered {
      val yaml = """
config: {}
trail:
  - step: recorded
    recording:
      android:
        - ${YamlDefinedToolFixture.NAME}:
            text: hello
            clearFirst: true
      """.trimIndent()

      val tools = decodeRecordedTools(yaml)
      assertThat(tools).hasSize(1)
      val decoded = tools[0].trailblazeTool
      assertThat(decoded).isInstanceOf(YamlDefinedTrailblazeTool::class)

      val yamlDefined = decoded as YamlDefinedTrailblazeTool
      assertThat(yamlDefined.config.id).isEqualTo(YamlDefinedToolFixture.NAME)
      assertThat(yamlDefined.params["text"]).isEqualTo(JsonPrimitive("hello"))
      assertThat(yamlDefined.params["clearFirst"]).isEqualTo(JsonPrimitive(true))

      assertThat(yamlDefined.toExecutableTrailblazeTools(createContext())).isEqualTo(
        listOf(
          InputTextTrailblazeTool(text = "hello", hideKeyboardAfter = false, clearFirst = true),
          HideKeyboardTrailblazeTool,
        ),
      )
    }

  @Test
  fun `an omitted optional param drops its key so the composed tool keeps its own default`() =
    YamlDefinedToolFixture.registered {
      // Regression test: 'default: null' used to emit 'clearFirst: null' into the composed tool,
      // overriding its own default. A parameter with no declared default uses DropIfOmitted — the
      // key is absent from the expansion, so `inputText` falls back to `clearFirst = false`.
      val yaml = """
config: {}
trail:
  - step: recorded
    recording:
      android:
        - ${YamlDefinedToolFixture.NAME}:
            text: hello
      """.trimIndent()

      val yamlDefined = decodeRecordedTools(yaml)[0].trailblazeTool as YamlDefinedTrailblazeTool

      assertThat(yamlDefined.toExecutableTrailblazeTools(createContext()).first())
        .isEqualTo(InputTextTrailblazeTool(text = "hello", hideKeyboardAfter = false, clearFirst = false))
    }

  @Test
  fun `an explicit null from the LLM drops the key — JsonNull treated as omitted`() =
    YamlDefinedToolFixture.registered {
      // Regression test: when the LLM passes null for an optional param, JsonNull is a
      // non-null Kotlin object. Previously 'caller != null' was true for JsonNull, so it
      // bypassed DropIfOmitted and emitted 'key: null' into the composed tool.
      // The fix treats JsonNull the same as a missing parameter.
      val yaml = """
config: {}
trail:
  - step: recorded
    recording:
      android:
        - ${YamlDefinedToolFixture.NAME}:
            text: hello
            clearFirst: null
      """.trimIndent()

      val yamlDefined = decodeRecordedTools(yaml)[0].trailblazeTool as YamlDefinedTrailblazeTool
      assertThat(yamlDefined.params.containsKey("clearFirst")).isEqualTo(true)

      assertThat(yamlDefined.toExecutableTrailblazeTools(createContext()).first())
        .isEqualTo(InputTextTrailblazeTool(text = "hello", hideKeyboardAfter = false, clearFirst = false))
    }

  @Test
  fun `trail YAML with scalar args instead of a map rejects with a clear error`() =
    YamlDefinedToolFixture.registered {
      // Regression guard: `- <tool>: 3` is a YAML scalar, not a map of params.
      // Previously the deserializer silently coerced unknown
      // shapes into an empty params map, so this would execute with declared defaults and
      // mask the author's mistake. After switching the serializer's declared descriptor to
      // a map shape, kaml's own validator rejects the scalar before the custom deserialize
      // path even runs — that's the safer layering.
      val yaml = """
config: {}
trail:
  - step: recorded
    recording:
      android:
        - ${YamlDefinedToolFixture.NAME}: 3
      """.trimIndent()

      assertFailure { decodeRecordedTools(yaml) }
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
  fun `REJECTED - memory prefix is not valid in a YAML tool's tools body`() {
    expandFailureMessage(tokenTool(""""{{memory.foo}}""""))
      .contains("'tools:' body interpolates only")
  }

  @Test
  fun `REJECTED - device prefix is not valid in a YAML tool's tools body`() {
    expandFailureMessage(tokenTool(""""{{device.foo}}""""))
      .contains("'tools:' body interpolates only")
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
