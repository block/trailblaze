package xyz.block.trailblaze.yaml

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
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
import xyz.block.trailblaze.mobile.tools.AndroidSendBroadcastTrailblazeTool
import xyz.block.trailblaze.mobile.tools.BroadcastExtra
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult

/**
 * Covers `android_sendBroadcast` YAML round-trip and the typed-extra coercion layer.
 *
 * The tool is YAML-registered (see `trailblaze-config/tools/android_sendBroadcast.yaml`);
 * these tests pin down both the shorthand scalar extra form and the full object form,
 * and assert the coerced Kotlin runtime types that [BroadcastIntent.extras] will see.
 */
class AndroidSendBroadcastSerializationTest {
  private val trailblazeYaml = createTrailblazeYaml(setOf(AndroidSendBroadcastTrailblazeTool::class))

  @Test
  fun deserializeBroadcastWithShorthandStringExtras() {
    val yaml = """
- tools:
    - android_sendBroadcast:
        action: com.example.app.DEBUG
        componentPackage: com.example.app
        componentClass: com.example.app.DebugBroadcastReceiver
        extras:
          enable_test_mode: "1"
          another_key: "hello"
    """.trimIndent()

    val trailItems = trailblazeYaml.decodeTrail(yaml)
    val tool = (trailItems.single() as TrailYamlItem.ToolTrailItem)
      .tools.single().trailblazeTool as AndroidSendBroadcastTrailblazeTool

    assertThat(tool.action).isEqualTo("com.example.app.DEBUG")
    assertThat(tool.componentPackage).isEqualTo("com.example.app")
    assertThat(tool.componentClass).isEqualTo("com.example.app.DebugBroadcastReceiver")
    assertThat(tool.extras).isEqualTo(
      mapOf(
        "enable_test_mode" to BroadcastExtra(value = "1"),
        "another_key" to BroadcastExtra(value = "hello"),
      ),
    )
  }

  @Test
  fun deserializeBroadcastWithTypedExtras() {
    val yaml = """
- tools:
    - android_sendBroadcast:
        action: com.example.ACTION
        componentPackage: com.example
        componentClass: com.example.Receiver
        extras:
          flag_string:
            value: "hello"
            type: string
          flag_bool:
            value: "true"
            type: boolean
          flag_int:
            value: "42"
            type: int
          flag_long:
            value: "4200000000"
            type: long
          flag_float:
            value: "3.14"
            type: float
    """.trimIndent()

    val trailItems = trailblazeYaml.decodeTrail(yaml)
    val tool = (trailItems.single() as TrailYamlItem.ToolTrailItem)
      .tools.single().trailblazeTool as AndroidSendBroadcastTrailblazeTool

    assertThat(tool.extras["flag_string"]!!.toTypedValue()).isInstanceOf(String::class).isEqualTo("hello")
    assertThat(tool.extras["flag_bool"]!!.toTypedValue()).isInstanceOf(Boolean::class).isEqualTo(true)
    assertThat(tool.extras["flag_int"]!!.toTypedValue()).isInstanceOf(Int::class).isEqualTo(42)
    assertThat(tool.extras["flag_long"]!!.toTypedValue()).isInstanceOf(Long::class).isEqualTo(4_200_000_000L)
    assertThat(tool.extras["flag_float"]!!.toTypedValue()).isInstanceOf(Float::class).isEqualTo(3.14f)
  }

  @Test
  fun typeNameIsCaseInsensitive() {
    val yaml = """
- tools:
    - android_sendBroadcast:
        action: a
        componentPackage: p
        componentClass: c
        extras:
          flag:
            value: "7"
            type: INT
    """.trimIndent()

    val tool = (trailblazeYaml.decodeTrail(yaml).single() as TrailYamlItem.ToolTrailItem)
      .tools.single().trailblazeTool as AndroidSendBroadcastTrailblazeTool

    assertThat(tool.extras["flag"]!!.toTypedValue()).isEqualTo(7)
  }

  @Test
  fun unknownTypeReportsValidOptions() {
    val extra = BroadcastExtra(value = "1", type = "bogus")

    assertFailure { extra.toTypedValue() }
      .hasMessage { msg ->
        msg.contains("bogus")
        msg.contains("string")
        msg.contains("boolean")
        msg.contains("int")
        msg.contains("long")
        msg.contains("float")
      }
  }

  @Test
  fun cliFlagAliasesCanonicalizeToJavaTypeNames() {
    val yaml = """
- tools:
    - android_sendBroadcast:
        action: a
        componentPackage: p
        componentClass: c
        extras:
          shortEi:
            value: "42"
            type: ei
          shortEz:
            value: "true"
            type: ez
    """.trimIndent()

    val tool = (trailblazeYaml.decodeTrail(yaml).single() as TrailYamlItem.ToolTrailItem)
      .tools.single().trailblazeTool as AndroidSendBroadcastTrailblazeTool

    // Short forms normalize to long forms at decode time — storage stays canonical.
    assertThat(tool.extras["shortEi"]!!.type).isEqualTo("int")
    assertThat(tool.extras["shortEz"]!!.type).isEqualTo("boolean")
    assertThat(tool.extras["shortEi"]!!.toTypedValue()).isEqualTo(42)
    assertThat(tool.extras["shortEz"]!!.toTypedValue()).isEqualTo(true)
  }

  @Test
  fun parseFailureIncludesValueAndType() {
    val extra = BroadcastExtra(value = "not-a-number", type = "int")

    assertFailure { extra.toTypedValue() }
      .hasMessage { msg ->
        msg.contains("not-a-number")
        msg.contains("int")
      }
  }

  @Test
  fun unknownKeyInExtraObjectFormReportsHint() {
    val yaml = """
- tools:
    - android_sendBroadcast:
        action: a
        componentPackage: p
        componentClass: c
        extras:
          flag:
            valu: "oops"
    """.trimIndent()

    assertFailure { trailblazeYaml.decodeTrail(yaml) }
      .hasMessage { msg ->
        msg.contains("valu")
        msg.contains("value")
        msg.contains("type")
      }
  }

  @Test
  fun roundTripPreservesExtrasInCanonicalForm() {
    val yaml = """
- tools:
    - android_sendBroadcast:
        action: com.example.ACTION
        componentPackage: com.example
        componentClass: com.example.Receiver
        extras:
          bare: "1"
          count:
            value: "42"
            type: int
    """.trimIndent()

    val decoded = trailblazeYaml.decodeTrail(yaml)
    val reEncoded = trailblazeYaml.encodeToString(decoded)
    val reDecoded = trailblazeYaml.decodeTrail(reEncoded)

    val tool = (reDecoded.single() as TrailYamlItem.ToolTrailItem)
      .tools.single().trailblazeTool as AndroidSendBroadcastTrailblazeTool

    // Shorthand expands to canonical object form on the round trip; coerced
    // typed values still resolve correctly.
    assertThat(tool.extras["bare"]).isEqualTo(BroadcastExtra(value = "1", type = "string"))
    assertThat(tool.extras["count"]).isEqualTo(BroadcastExtra(value = "42", type = "int"))
    assertThat(tool.extras["count"]!!.toTypedValue()).isEqualTo(42)
  }

  @Test
  fun executeReturnsErrorOnNonAndroidPlatform() = runBlocking {
    val tool = AndroidSendBroadcastTrailblazeTool(
      action = "a", componentPackage = "p", componentClass = "c",
    )
    val result = tool.execute(createContext(TrailblazeDevicePlatform.IOS))

    assertIs<TrailblazeToolResult.Error.ExceptionThrown>(result)
    assertThat(result.errorMessage).contains("Android")
  }

  @Test
  fun executeReturnsErrorWhenExecutorIsMissing() = runBlocking {
    val tool = AndroidSendBroadcastTrailblazeTool(
      action = "a", componentPackage = "p", componentClass = "c",
    )
    // Android platform but no AndroidDeviceCommandExecutor wired in.
    val result = tool.execute(createContext(TrailblazeDevicePlatform.ANDROID))

    assertIs<TrailblazeToolResult.Error.ExceptionThrown>(result)
    assertThat(result.errorMessage).contains("AndroidDeviceCommandExecutor")
  }

  private fun createContext(platform: TrailblazeDevicePlatform): TrailblazeToolExecutionContext {
    val driverType = when (platform) {
      TrailblazeDevicePlatform.ANDROID -> TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION
      TrailblazeDevicePlatform.IOS -> TrailblazeDriverType.IOS_HOST
      TrailblazeDevicePlatform.WEB -> TrailblazeDriverType.PLAYWRIGHT_NATIVE
      TrailblazeDevicePlatform.DESKTOP -> TrailblazeDriverType.COMPOSE
    }
    return TrailblazeToolExecutionContext(
      screenState = null,
      traceId = null,
      trailblazeDeviceInfo = TrailblazeDeviceInfo(
        trailblazeDeviceId = TrailblazeDeviceId(
          instanceId = "test-device",
          trailblazeDevicePlatform = platform,
        ),
        trailblazeDriverType = driverType,
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
}

private fun assertk.Assert<Throwable>.hasMessage(check: (message: assertk.Assert<String>) -> Unit) =
  transform { it.message ?: "" }.let(check)
