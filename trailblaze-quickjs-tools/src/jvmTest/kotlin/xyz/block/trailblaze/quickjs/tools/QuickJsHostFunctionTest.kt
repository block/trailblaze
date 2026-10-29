package xyz.block.trailblaze.quickjs.tools

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.llm.config.ConfigResourceSource
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.scripting.host.TrailblazeHostFunction
import xyz.block.trailblaze.scripting.host.TrailblazeHostFunctionClass
import xyz.block.trailblaze.scripting.host.TrailblazeHostFunctionException
import xyz.block.trailblaze.scripting.host.TrailblazeHostFunctionRegistry
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.toolcalls.TrailblazeToolSet

@Serializable
internal data class QuickJsHostTestCredentials(val email: String)

@Serializable
@TrailblazeHostFunctionClass(name = "test_lookupCredentials", result = QuickJsHostTestCredentials::class)
internal data class QuickJsHostTestLookup(val key: String) : TrailblazeHostFunction<QuickJsHostTestCredentials> {
  override suspend fun invoke(context: TrailblazeToolExecutionContext): QuickJsHostTestCredentials {
    if (key == "missing") throw TrailblazeHostFunctionException("no account for '$key'")
    return QuickJsHostTestCredentials("$key@example.com")
  }
}

/**
 * `__trailblazeHost` end to end: a script in a real QuickJS engine calls a host function through
 * [SessionScopedHostBinding], and the call never reaches tool dispatch.
 */
class QuickJsHostFunctionTest {

  private val hosts = mutableListOf<QuickJsToolHost>()

  @AfterTest
  fun teardown() = runBlocking {
    hosts.forEach { runCatching { it.shutdown() } }
  }

  private val sessionId = SessionId("quickjs-host-function-test")

  private val registry = TrailblazeHostFunctionRegistry(
    ConfigResourceSource { _, _ -> emptyMap() }.let { flat ->
      object : ConfigResourceSource by flat {
        override fun discoverAndLoadRecursive(directoryPath: String, suffix: String) = mapOf(
          "accounts/host/test_lookupCredentials.host.yaml" to
            "id: test_lookupCredentials\nclass: ${QuickJsHostTestLookup::class.java.name}\n",
        )
      }
    },
  )

  private val dispatchedTools = mutableListOf<String>()

  private val context = TrailblazeToolExecutionContext(
    screenState = null,
    traceId = null,
    trailblazeDeviceInfo = TrailblazeDeviceInfo(
      trailblazeDeviceId = TrailblazeDeviceId("emulator-5554", TrailblazeDevicePlatform.ANDROID),
      trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
      widthPixels = 1080,
      heightPixels = 1920,
    ),
    sessionProvider = TrailblazeSessionProvider {
      TrailblazeSession(sessionId = sessionId, startTime = Clock.System.now())
    },
    trailblazeLogger = TrailblazeLogger.createNoOp(),
    memory = AgentMemory(),
    // Every tool a script dispatches goes through here — it is where a call gets logged as a step
    // and drops the cached screen. A host function must never arrive.
    nestedToolExecutor = { tool ->
      dispatchedTools += tool::class.simpleName.orEmpty()
      error("host function call dispatched a tool")
    },
  )

  private suspend fun runScript(body: String): String {
    val binding = SessionScopedHostBinding(
      toolRepo = TrailblazeToolRepo(TrailblazeToolSet.DynamicTrailblazeToolSet("empty", emptySet())),
      sessionId = sessionId,
      hostFunctions = registry,
    ).apply { activeContext = context }
    val host = QuickJsToolHost.connect(
      """
      globalThis.__trailblazeTools = {
        probe: { name: "probe", spec: {}, handler: async () => {
          $body
        } },
      };
      """.trimIndent(),
      bundleFilename = "host-function-test.js",
      hostBinding = binding,
    )
    hosts += host
    val result = host.callTool("probe", JsonObject(emptyMap()))
    return ((result["content"] as JsonArray).first().jsonObject["text"] as JsonPrimitive).content
  }

  @Test
  fun `a script gets the host function's result without dispatching a tool`() = runBlocking {
    val text = runScript(
      """
      const reply = JSON.parse(globalThis.__trailblazeHost("test_lookupCredentials", JSON.stringify({ key: "ada" })));
      return { content: [{ type: "text", text: JSON.stringify(reply) }] };
      """,
    )
    assertEquals("""{"ok":true,"value":{"email":"ada@example.com"}}""", text)
    assertEquals(emptyList(), dispatchedTools)
  }

  @Test
  fun `a failing host function returns its message instead of throwing into the script`() = runBlocking {
    val text = runScript(
      """
      const reply = JSON.parse(globalThis.__trailblazeHost("test_lookupCredentials", JSON.stringify({ key: "missing" })));
      return { content: [{ type: "text", text: JSON.stringify(reply) }] };
      """,
    )
    assertEquals("""{"ok":false,"error":"no account for 'missing'"}""", text)
    assertEquals(emptyList(), dispatchedTools)
  }
}
