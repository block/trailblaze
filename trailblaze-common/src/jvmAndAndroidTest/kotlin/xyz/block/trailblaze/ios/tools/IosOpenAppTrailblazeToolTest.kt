package xyz.block.trailblaze.ios.tools

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.toLogPayload
import xyz.block.trailblaze.toolcalls.toScriptedToolDescriptor
import xyz.block.trailblaze.tools.FakeTrailblazeAgent
import xyz.block.trailblaze.yaml.TrailYamlItem
import xyz.block.trailblaze.yaml.createTrailblazeYaml

/**
 * `ios_openApp`'s launch arguments replace Maestro's `launchApp.arguments` for iOS trailheads, so the app
 * must receive the same argv it got from Maestro, and the values (session tokens) must stay out of
 * logs the way Maestro's do.
 */
class IosOpenAppTrailblazeToolTest {

  @Test
  fun `arguments become the argv Maestro's iOS launch passes`() {
    // A locale, a port, an environment and a boolean flag, as real iOS trailheads pass them.
    val arguments = buildJsonObject {
      put("AppleLanguages", "(es-US)")
      put("AppleLocale", "es_US")
      put("trailblazePort", 52525)
      put("trailblazeEnvironment", "staging")
      put("skipPermissionsSetup", true)
      put("-AlreadyDashed", "kept")
    }

    assertThat(IosLaunchArguments.argv(arguments)).isEqualTo(
      listOf(
        "-AppleLanguages", "(es-US)",
        "-AppleLocale", "es_US",
        "-trailblazePort", "52525",
        "-trailblazeEnvironment", "staging",
        // Maestro passes a boolean's name without the dash.
        "skipPermissionsSetup", "true",
        "-AlreadyDashed", "kept",
      ),
    )
  }

  @Test
  fun `the string true is a string, not a boolean`() {
    val arguments = buildJsonObject { put("Flag", "true") }
    assertThat(IosLaunchArguments.argv(arguments)).isEqualTo(listOf("-Flag", "true"))
  }

  @Test
  fun `only scalars can be launch arguments`() {
    val arguments = buildJsonObject {
      put("ok", "fine")
      put("nested", buildJsonObject { put("a", 1) })
      put("list", buildJsonArray { add(JsonPrimitive(1)) })
      put("missing", JsonNull)
    }
    assertThat(IosLaunchArguments.violation(arguments)).isEqualTo(
      "ios_openApp launch arguments must be strings, numbers or booleans; nested, list, missing are not.",
    )
    assertThat(IosLaunchArguments.violation(buildJsonObject { put("ok", 1) })).isNull()
  }

  @Test
  fun `a recorded ios_openApp launches with the argv Maestro passes`() {
    val yaml = """
      config: {}
      trail:
        - step: recorded
          recording:
            ios:
              - ios_openApp:
                  appId: com.example.app
                  arguments:
                    AppleLanguages: "(es-US)"
                    trailblazePort: 52525
                    skipPermissionsSetup: true
    """.trimIndent()

    val tool = decodeTrail(yaml)

    assertThat(tool.appId).isEqualTo("com.example.app")
    assertThat(IosLaunchArguments.argv(tool.arguments!!)).isEqualTo(
      listOf("-AppleLanguages", "(es-US)", "-trailblazePort", "52525", "skipPermissionsSetup", "true"),
    )
  }

  @Test
  fun `trailheads can pass arguments to ios_openApp`() {
    // A parameter type the descriptor lowering can't model would drop ios_openApp from ctx.tools.
    val scripted = IosOpenAppTrailblazeTool::class.toScriptedToolDescriptor()
    assertThat(scripted).isNotNull()
    assertThat(scripted!!.optionalParameters.map { it.name }).isEqualTo(listOf("arguments"))
  }

  @Test
  fun `string argument values are masked in the logged call`() {
    val token = "fixture-session-token-7f3a"
    val tool = IosOpenAppTrailblazeTool(
      appId = "com.example.app",
      arguments = buildJsonObject {
        put("SessionToken", token)
        put("trailblazePort", 52525)
      },
    )

    val logged = TrailblazeJsonInstance.encodeToString(tool.toLogPayload().raw)

    assertThat(logged).doesNotContain(token)
    assertThat(logged).contains("SessionToken")
    assertThat(logged).contains("com.example.app")
  }

  @Test
  fun `strings nested in a value the launch rejects are masked too`() {
    val tool = IosOpenAppTrailblazeTool(
      appId = "com.example.app",
      arguments = buildJsonObject {
        put("session", buildJsonObject { put("token", "fixture-nested-token") })
        put("list", buildJsonArray { add(JsonPrimitive("fixture-listed-token")) })
      },
    )

    val logged = TrailblazeJsonInstance.encodeToString(tool.toLogPayload().raw) + tool.toString()

    assertThat(logged).doesNotContain("fixture-nested-token")
    assertThat(logged).doesNotContain("fixture-listed-token")
  }

  @Test
  fun `a memory reference names the secret, so it is not masked`() {
    val tool = IosOpenAppTrailblazeTool(
      appId = "com.example.app",
      arguments = buildJsonObject {
        put("SessionToken", "{{session_token}}")
        put("Other", "fixture-literal-token")
      },
    )

    assertThat(tool.sensitiveValues.toList()).isEqualTo(listOf("fixture-literal-token"))
  }

  @Test
  fun `toString masks string values`() {
    val tool = IosOpenAppTrailblazeTool(
      appId = "com.example.app",
      arguments = buildJsonObject { put("SessionToken", "fixture-session-token-7f3a") },
    )

    assertThat(tool.toString()).doesNotContain("fixture-session-token-7f3a")
    assertThat(tool.toString()).contains("com.example.app")
  }

  @Test
  fun `toString masks a value that rendering would escape`() {
    val tool = IosOpenAppTrailblazeTool(
      appId = "com.example.app",
      arguments = buildJsonObject { put("SessionToken", "fixture\"quoted\\token\nline") },
    )

    assertThat(tool.toString()).doesNotContain("quoted")
    assertThat(tool.toString()).doesNotContain("line")
  }

  @Test
  fun `a numeric trail YAML value is a masked string`() {
    // The YAML parser drops quoting, so a quoted all-digit token looks like a number.
    val tool = decodeRecordedOpenApp(
      """
        appId: com.example.app
        arguments:
          SessionToken: "123123"
      """,
    )

    assertThat(tool.arguments!!["SessionToken"]).isEqualTo(JsonPrimitive("123123"))
    assertThat(tool.sensitiveValues.toList()).isEqualTo(listOf("123123"))
  }

  @Test
  fun `ios_openApp refuses a device that is not iOS`() = runBlocking {
    val tool = IosOpenAppTrailblazeTool(
      appId = "com.example.app",
      arguments = buildJsonObject { put("AppleLanguages", "(es-US)") },
    )

    val result = tool.execute(androidContext())

    assertThat(result).isInstanceOf(TrailblazeToolResult.Error.ExceptionThrown::class)
    assertThat((result as TrailblazeToolResult.Error.ExceptionThrown).errorMessage)
      .isEqualTo(
        "ios_openApp is only supported on iOS simulators (got platform: ANDROID). Use openApp elsewhere.",
      )
  }

  private fun decodeTrail(yaml: String): IosOpenAppTrailblazeTool =
    createTrailblazeYaml(setOf(IosOpenAppTrailblazeTool::class))
      .decodeTrail(yaml, deviceClassifiers = listOf(TrailblazeDeviceClassifier("ios")))
      .filterIsInstance<TrailYamlItem.PromptsTrailItem>().single()
      .promptSteps.single().recording!!.tools.single()
      .trailblazeTool as IosOpenAppTrailblazeTool

  /** [openAppYaml] is the body of one recorded `ios_openApp` call. */
  private fun decodeRecordedOpenApp(openAppYaml: String): IosOpenAppTrailblazeTool = decodeTrail(
    "config: {}\ntrail:\n  - step: recorded\n    recording:\n      ios:\n        - ios_openApp:\n" +
      openAppYaml.trimIndent().prependIndent("            "),
  )

  private fun androidContext(): TrailblazeToolExecutionContext {
    val agent = FakeTrailblazeAgent()
    return TrailblazeToolExecutionContext(
      screenState = null,
      traceId = null,
      trailblazeDeviceInfo = agent.trailblazeDeviceInfoProvider(),
      sessionProvider = agent.sessionProvider,
      trailblazeLogger = agent.trailblazeLogger,
      memory = agent.memory,
    )
  }
}
