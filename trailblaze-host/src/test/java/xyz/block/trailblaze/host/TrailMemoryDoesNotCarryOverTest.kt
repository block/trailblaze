package xyz.block.trailblaze.host

import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.model.PromptExecutor
import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import io.ktor.http.HttpStatusCode
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.http.DynamicLlmClient
import xyz.block.trailblaze.llm.RunYamlRequest
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.llm.TrailblazeLlmProvider
import xyz.block.trailblaze.llm.TrailblazeReferrer
import xyz.block.trailblaze.mcp.android.ondevice.rpc.OnDeviceRpcClient
import xyz.block.trailblaze.model.TrailblazeConfig

/**
 * Trail memory never carries from one trail to the next on the host's per-tool RPC path, even when
 * one runner process drives both against the same device. Trailhead reuse makes that
 * load-bearing: the only thing that may pass between trails is a reusable teardown result.
 *
 * Two whole runs go through [TrailblazeHostYamlRunner.runHostAgentWithOnDeviceRpc] against one
 * mock device. In the first, the device reports that an on-device tool remembered a value, and
 * the trail's next dispatch carries it; the second trail's dispatches must not.
 */
class TrailMemoryDoesNotCarryOverTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  private val deviceId = jvmScopedDeviceId("trail-memory-carry-over")
  private val mockServer = MockRpcServer(deviceId)
  private lateinit var rpcClient: OnDeviceRpcClient

  @Before
  fun setUp() {
    mockServer.start()
    rpcClient = OnDeviceRpcClient(deviceId)
  }

  @After
  fun tearDown() {
    try {
      if (::rpcClient.isInitialized) rpcClient.close()
    } finally {
      mockServer.stop()
    }
  }

  private val noLlm = object : DynamicLlmClient {
    override fun createPromptExecutor(): PromptExecutor = error("no LLM in this test")
    // Built for the run but never called: every step replays its recording.
    override fun createLlmClient(): LLMClient = OpenAILLMClient(apiKey = "not-a-real-key")
  }

  private val yaml = """
    trail:
      - step: Type a greeting
        recording:
          android:
            - inputText:
                text: hello
            - inputText:
                text: again
  """.trimIndent()

  private fun request(name: String) = RunYamlRequest(
    testName = name,
    yaml = yaml,
    trailFilePath = null,
    targetAppName = null,
    useRecordedSteps = true,
    trailblazeDeviceId = deviceId,
    trailblazeLlmModel = TrailblazeLlmModel(
      trailblazeLlmProvider = TrailblazeLlmProvider(id = "test", display = "Test"),
      modelId = "test-model",
      inputCostPerOneMillionTokens = 0.0,
      outputCostPerOneMillionTokens = 0.0,
      contextLength = 1000,
      maxOutputTokens = 1000,
      capabilityIds = emptyList(),
    ),
    config = TrailblazeConfig(),
    referrer = TrailblazeReferrer(id = "test", display = "Test"),
    driverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
  )

  private fun run(name: String) = runBlocking {
    TrailblazeHostYamlRunner.runHostAgentWithOnDeviceRpc(
      dynamicLlmClient = noLlm,
      onDeviceRpc = rpcClient,
      runYamlRequest = request(name),
      trailblazeDeviceId = deviceId,
      onProgressMessage = {},
      targetTestApp = null,
      logsDir = tempFolder.newFolder(name),
    )
  }

  @Test
  fun `a value an on-device tool remembered in one trail is not sent in the next trail`() {
    val dispatches = AtomicInteger(0)
    mockServer.onPost("/rpc/RunYamlRequest") {
      // Only the first trail's tool remembers something on the device.
      val memory = if (dispatches.incrementAndGet() == 1) """{"cartId":"cart-from-trail-1"}""" else "{}"
      HttpStatusCode.OK to """{"sessionId":"s","success":true,"memorySnapshot":$memory,"memoryDeletions":[]}"""
    }
    mockServer.onPost("/rpc/GetScreenStateRequest") {
      HttpStatusCode.OK to """{"viewHierarchy":{},"screenshotBase64":null,"deviceWidth":1080,"deviceHeight":1920}"""
    }

    run("trail-1")
    run("trail-2")

    val sent = mockServer.requestLog["/rpc/RunYamlRequest"].orEmpty().map { Json.parseToJsonElement(it).jsonObject }
    assertThat(sent).hasSize(4)
    val cartIds = sent.map { it["memorySnapshot"]?.jsonObject?.get("cartId")?.jsonPrimitive?.content }
    // Within trail 1 the remembered value rides the next dispatch; trail 2 starts empty.
    assertThat(cartIds).isEqualTo(listOf(null, "cart-from-trail-1", null, null))
  }
}
