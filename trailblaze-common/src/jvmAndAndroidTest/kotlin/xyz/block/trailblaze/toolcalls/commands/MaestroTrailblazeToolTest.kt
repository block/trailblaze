package xyz.block.trailblaze.toolcalls.commands

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import maestro.orchestra.BackPressCommand
import maestro.orchestra.Command
import maestro.orchestra.ElementSelector
import maestro.orchestra.EraseTextCommand
import maestro.orchestra.InputTextCommand
import maestro.orchestra.TapOnElementCommand
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.assertContains
import kotlin.test.assertFalse
import xyz.block.trailblaze.toolcalls.toLogPayload
import xyz.block.trailblaze.MaestroTrailblazeAgent
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.maestro.MaestroYamlParser
import xyz.block.trailblaze.maestro.MaestroYamlSerializer
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult

/**
 * Tests for [MaestroTrailblazeTool] focusing on:
 * - Detecting and reporting command deserialization failures instead of silently dropping them
 * - Accepting the canonical Maestro YAML syntax documented at
 *   https://docs.maestro.dev/reference/commands-available
 */
class MaestroTrailblazeToolTest {
  private var executionFailure: Exception? = null

  @Test
  fun `driver exceptions cannot escape with raw launch arguments`() = runBlocking {
    executionFailure = IllegalStateException("driver echoed fixture-session-secret")
    val tool = MaestroTrailblazeTool(
      """- launchApp: {appId: com.example.app, arguments: {credential: fixture-session-secret}}""",
    )

    val result = tool.execute(createContext())

    assertThat(result).isInstanceOf(TrailblazeToolResult.Error::class)
    assertContains((result as TrailblazeToolResult.Error).errorMessage, "driver echoed")
    assertFalse(result.toString().contains("fixture-session-secret"))
  }

  @Test
  fun `launch argument redaction preserves the execution wire payload`() {
    val tool = kotlinx.serialization.json.Json.decodeFromString(
      MaestroTrailblazeToolSerializer,
      """{"commands":[{"launchApp":{"appId":"com.example.app","arguments":{"credential":"fixture-session-secret"}}}]}""",
    )
    val wire = kotlinx.serialization.json.Json.encodeToString(MaestroTrailblazeToolSerializer, tool)
    assertContains(wire, "fixture-session-secret")
    val logged = tool.toLogPayload().raw.toString()
    assertContains(logged, "com.example.app")
    assertContains(logged, "credential")
    assertFalse(logged.contains("fixture-session-secret"))
    assertFalse(tool.toString().contains("fixture-session-secret"))
  }

  @Test
  fun `launch argument redaction masks numeric values without changing unrelated command fields`() {
    val tool = MaestroTrailblazeTool(
      """- launchApp: {appId: com.example.app1, arguments: {pin: 47391, debug: 1}}
- inputText: Item 1""",
    )

    val logged = tool.toLogPayload().raw.toString()
    assertFalse(logged.contains("47391"))
    assertContains(logged, "com.example.app1")
    assertContains(logged, "Item 1")
    assertContains(logged, "<redacted>")
  }

  @Test
  fun `malformed source does not leak through parse failures or log encoder diagnostics`() = runBlocking {
    val tool = MaestroTrailblazeTool("- launchApp: [fixture-session-secret")
    val priorErr = System.err
    val captured = ByteArrayOutputStream()
    try {
      System.setErr(PrintStream(captured))
      val result = tool.execute(createContext())
      assertThat(result).isInstanceOf(TrailblazeToolResult.Error::class)
      assertFalse(result.toString().contains("fixture-session-secret"))
      assertFalse(tool.toLogPayload().toString().contains("fixture-session-secret"))
    } finally {
      System.setErr(priorErr)
    }
    assertFalse(captured.toString().contains("fixture-session-secret"))
  }

  private val testAgent =
    object :
      MaestroTrailblazeAgent(
        trailblazeLogger = TrailblazeLogger.createNoOp(),
        trailblazeDeviceInfoProvider = {
          TrailblazeDeviceInfo(
            trailblazeDeviceId =
              TrailblazeDeviceId(
                instanceId = "test",
                trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
              ),
            trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
            widthPixels = 1080,
            heightPixels = 1920,
          )
        },
        sessionProvider =
          TrailblazeSessionProvider {
            TrailblazeSession(
              sessionId = SessionId("test-session"),
              startTime = Clock.System.now(),
            )
          },
      ) {
      override suspend fun executeMaestroCommands(
        commands: List<Command>,
        traceId: TraceId?,
      ): TrailblazeToolResult {
        executionFailure?.let { throw it }
        return TrailblazeToolResult.Success()
      }
    }

  private fun createContext(): TrailblazeToolExecutionContext {
    return TrailblazeToolExecutionContext(
      screenState = null,
      traceId = null,
      trailblazeDeviceInfo = testAgent.trailblazeDeviceInfoProvider(),
      sessionProvider = testAgent.sessionProvider,
      trailblazeLogger = testAgent.trailblazeLogger,
      memory = testAgent.memory,
      maestroTrailblazeAgent = testAgent,
    )
  }

  @Test
  fun `valid command round-trips through MaestroYamlSerializer and executes successfully`() {
    runBlocking {
      val command = TapOnElementCommand(selector = ElementSelector(textRegex = "OK"))
      val tool = MaestroTrailblazeTool(
        yaml = MaestroYamlSerializer.toYaml(listOf(command), includeConfiguration = false),
      )

      val result = tool.execute(createContext())

      assertThat(result).isInstanceOf(TrailblazeToolResult.Success::class)
    }
  }

  @Test
  fun `invalid YAML surfaces an error instead of silently dropping commands`() {
    runBlocking {
      val tool = MaestroTrailblazeTool(
        yaml = """
        - totallyFakeCommand:
            bogus: value
        """.trimIndent(),
      )

      val result = tool.execute(createContext())

      assertThat(result).isInstanceOf(TrailblazeToolResult.Error::class)
      val errorMessage = (result as TrailblazeToolResult.Error).errorMessage
      assertThat(errorMessage).contains("failed to deserialize")
    }
  }

  @Test
  fun `maestro YAML short names from docs-maestro-dev decode to commands`() {
    // https://docs.maestro.dev/reference/commands-available is the canonical spec. The
    // `mobile_maestro:` tool block in a trail feeds its YAML straight to MaestroYamlParser, so
    // the accepted surface is whatever Maestro's YAML reference accepts.
    val commands = MaestroYamlParser.parseYaml(
      """
      - eraseText:
          charactersToErase: 5
      - back
      - inputText: hello
      """.trimIndent(),
    )
    assertThat(commands).hasSize(3)
    assertThat((commands[0] as EraseTextCommand).charactersToErase).isEqualTo(5)
    assertThat(commands[1] is BackPressCommand).isEqualTo(true)
    assertThat((commands[2] as InputTextCommand).text).isEqualTo("hello")
  }

  @Test
  fun `JSON-flavoured YAML (our internal serializer form) also decodes`() {
    // The serializer renders each authored YAML command back to JSON flow style before
    // feeding Maestro's parser, so prove that flow style roundtrips too.
    val commands = MaestroYamlParser.parseYaml(
      """
      - {"eraseText":{"charactersToErase":3}}
      - {"back":{}}
      """.trimIndent(),
    )
    assertThat((commands[0] as EraseTextCommand).charactersToErase).isEqualTo(3)
    assertThat(commands[1] is BackPressCommand).isEqualTo(true)
  }

  @Test
  fun `clean numeric-looking inputText survives the RPC-forwarding round trip and executes`() {
    // Regression for a real login-flow CI failure: a verification code like "123123" is a
    // clean, zero-padding-free numeric string, so it wasn't caught by the #4282 leading-zero fix
    // (YamlJsonBridge's round-trip check treats it as a "genuine canonical number" by design).
    // `HostOnDeviceRpcTrailblazeAgent.executeMaestroCommands` rebuilds a MaestroTrailblazeTool from
    // parsed commands and forwards it to the device by re-serializing via this tool's custom
    // serializer — which used to round-trip every scalar through YamlJsonBridge's number-guessing
    // and silently turn "123123" into an unquoted YAML integer, which Maestro's own parser then
    // rejects for the object-form `text` field. Simulate that exact forwarding round trip.
    runBlocking {
      val decoded = kotlinx.serialization.json.Json.decodeFromString(
        MaestroTrailblazeToolSerializer,
        """{"commands":[{"inputText":"123123"}]}""",
      )
      val commands = MaestroYamlParser.parseYaml(decoded.yaml)
      val forwarded = MaestroTrailblazeTool(
        yaml = MaestroYamlSerializer.toYaml(commands, includeConfiguration = false),
      )
      // The forwarding hop: re-serialize (as executeToolViaRpc's YAML encoder would) and decode
      // back into the tool that would actually run on the device.
      val reserialized = kotlinx.serialization.json.Json.encodeToString(
        MaestroTrailblazeToolSerializer,
        forwarded,
      )
      val redecoded = kotlinx.serialization.json.Json.decodeFromString(
        MaestroTrailblazeToolSerializer,
        reserialized,
      )

      val result = redecoded.execute(createContext())

      assertThat(result).isInstanceOf(TrailblazeToolResult.Success::class)
      assertThat((MaestroYamlParser.parseYaml(redecoded.yaml)[0] as InputTextCommand).text)
        .isEqualTo("123123")
    }
  }

  @Test
  fun `empty yaml returns success`() {
    runBlocking {
      val tool = MaestroTrailblazeTool(yaml = "")

      val result = tool.execute(createContext())

      assertThat(result).isInstanceOf(TrailblazeToolResult.Success::class)
    }
  }
}
