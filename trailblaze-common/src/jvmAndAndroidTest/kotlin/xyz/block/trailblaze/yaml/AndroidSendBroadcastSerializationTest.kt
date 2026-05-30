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
 * The tool is YAML-registered (see `trails/config/tools/android_sendBroadcast.yaml`);
 * these tests pin down the closed-shape list-of-objects extras form (so the typed
 * scripted-tool surface can lower it) and the coerced Kotlin runtime types that
 * [BroadcastIntent.extras] will see.
 */
class AndroidSendBroadcastSerializationTest {
  private val trailblazeYaml = createTrailblazeYaml(setOf(AndroidSendBroadcastTrailblazeTool::class))

  @Test
  fun deserializeBroadcastWithStringExtras() {
    val yaml = """
- tools:
    - android_sendBroadcast:
        action: com.example.app.DEBUG
        componentPackage: com.example.app
        componentClass: com.example.app.DebugBroadcastReceiver
        extras:
          - key: enable_test_mode
            value: "1"
          - key: another_key
            value: "hello"
    """.trimIndent()

    val trailItems = trailblazeYaml.decodeTrail(yaml)
    val tool = (trailItems.single() as TrailYamlItem.ToolTrailItem)
      .tools.single().trailblazeTool as AndroidSendBroadcastTrailblazeTool

    assertThat(tool.action).isEqualTo("com.example.app.DEBUG")
    assertThat(tool.componentPackage).isEqualTo("com.example.app")
    assertThat(tool.componentClass).isEqualTo("com.example.app.DebugBroadcastReceiver")
    assertThat(tool.extras).isEqualTo(
      listOf(
        BroadcastExtra(key = "enable_test_mode", value = "1"),
        BroadcastExtra(key = "another_key", value = "hello"),
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
          - key: flag_string
            value: "hello"
            type: string
          - key: flag_bool
            value: "true"
            type: boolean
          - key: flag_int
            value: "42"
            type: int
          - key: flag_long
            value: "4200000000"
            type: long
          - key: flag_float
            value: "3.14"
            type: float
    """.trimIndent()

    val trailItems = trailblazeYaml.decodeTrail(yaml)
    val tool = (trailItems.single() as TrailYamlItem.ToolTrailItem)
      .tools.single().trailblazeTool as AndroidSendBroadcastTrailblazeTool

    val byKey = tool.extras.associateBy { it.key }
    assertThat(byKey["flag_string"]!!.toTypedValue()).isInstanceOf(String::class).isEqualTo("hello")
    assertThat(byKey["flag_bool"]!!.toTypedValue()).isInstanceOf(Boolean::class).isEqualTo(true)
    assertThat(byKey["flag_int"]!!.toTypedValue()).isInstanceOf(Int::class).isEqualTo(42)
    assertThat(byKey["flag_long"]!!.toTypedValue()).isInstanceOf(Long::class).isEqualTo(4_200_000_000L)
    assertThat(byKey["flag_float"]!!.toTypedValue()).isInstanceOf(Float::class).isEqualTo(3.14f)
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
          - key: flag
            value: "7"
            type: INT
    """.trimIndent()

    val tool = (trailblazeYaml.decodeTrail(yaml).single() as TrailYamlItem.ToolTrailItem)
      .tools.single().trailblazeTool as AndroidSendBroadcastTrailblazeTool

    assertThat(tool.extras.single().toTypedValue()).isEqualTo(7)
  }

  @Test
  fun unknownTypeReportsValidOptions() {
    val extra = BroadcastExtra(key = "k", value = "1", type = "bogus")

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
  fun cliFlagAliasesResolveToTheRightJavaType() {
    val yaml = """
- tools:
    - android_sendBroadcast:
        action: a
        componentPackage: p
        componentClass: c
        extras:
          - key: shortEi
            value: "42"
            type: ei
          - key: shortEz
            value: "true"
            type: ez
    """.trimIndent()

    val tool = (trailblazeYaml.decodeTrail(yaml).single() as TrailYamlItem.ToolTrailItem)
      .tools.single().trailblazeTool as AndroidSendBroadcastTrailblazeTool

    val byKey = tool.extras.associateBy { it.key }
    // Storage preserves whatever the author wrote; `toTypedValue()` resolves both short
    // (`ei`/`ez`) and long (`int`/`boolean`) forms via case-insensitive lookup.
    assertThat(byKey["shortEi"]!!.toTypedValue()).isEqualTo(42)
    assertThat(byKey["shortEz"]!!.toTypedValue()).isEqualTo(true)
  }

  @Test
  fun parseFailureIncludesValueAndType() {
    val extra = BroadcastExtra(key = "k", value = "not-a-number", type = "int")

    assertFailure { extra.toTypedValue() }
      .hasMessage { msg ->
        msg.contains("not-a-number")
        msg.contains("int")
      }
  }

  @Test
  fun unknownKeyInExtraObjectReportsHint() {
    val yaml = """
- tools:
    - android_sendBroadcast:
        action: a
        componentPackage: p
        componentClass: c
        extras:
          - key: flag
            valu: "oops"
    """.trimIndent()

    assertFailure { trailblazeYaml.decodeTrail(yaml) }
      .hasMessage { msg ->
        msg.contains("valu")
      }
  }

  @Test
  fun roundTripPreservesExtrasInListForm() {
    val yaml = """
- tools:
    - android_sendBroadcast:
        action: com.example.ACTION
        componentPackage: com.example
        componentClass: com.example.Receiver
        extras:
          - key: bare
            value: "1"
          - key: count
            value: "42"
            type: int
    """.trimIndent()

    val decoded = trailblazeYaml.decodeTrail(yaml)
    val reEncoded = trailblazeYaml.encodeToString(decoded)
    val reDecoded = trailblazeYaml.decodeTrail(reEncoded)

    val tool = (reDecoded.single() as TrailYamlItem.ToolTrailItem)
      .tools.single().trailblazeTool as AndroidSendBroadcastTrailblazeTool

    val byKey = tool.extras.associateBy { it.key }
    assertThat(byKey["bare"]).isEqualTo(BroadcastExtra(key = "bare", value = "1", type = "string"))
    assertThat(byKey["count"]).isEqualTo(BroadcastExtra(key = "count", value = "42", type = "int"))
    assertThat(byKey["count"]!!.toTypedValue()).isEqualTo(42)
  }

  @Test
  fun blankExtraKeyFailsExecutionWithClearError() = runBlocking {
    val tool = AndroidSendBroadcastTrailblazeTool(
      action = "a", componentPackage = "p", componentClass = "c",
      extras = listOf(BroadcastExtra(key = "  ", value = "oops")),
    )

    val result = tool.execute(createContext(TrailblazeDevicePlatform.ANDROID))

    assertIs<TrailblazeToolResult.Error.ExceptionThrown>(result)
    assertThat(result.errorMessage).contains("blank key")
    assertThat(result.errorMessage).contains("oops")
  }

  @Test
  fun duplicateExtraKeysFailExecutionWithClearError() = runBlocking {
    val tool = AndroidSendBroadcastTrailblazeTool(
      action = "a", componentPackage = "p", componentClass = "c",
      extras = listOf(
        BroadcastExtra(key = "dup", value = "1"),
        BroadcastExtra(key = "dup", value = "2"),
      ),
    )

    val result = tool.execute(createContext(TrailblazeDevicePlatform.ANDROID))

    assertIs<TrailblazeToolResult.Error.ExceptionThrown>(result)
    assertThat(result.errorMessage).contains("duplicate")
    assertThat(result.errorMessage).contains("dup")
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
