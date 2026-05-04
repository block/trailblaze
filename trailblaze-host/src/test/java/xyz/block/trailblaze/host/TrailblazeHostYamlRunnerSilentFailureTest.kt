package xyz.block.trailblaze.host

import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.model.PromptExecutor
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isInstanceOf
import kotlin.test.assertFails
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import org.junit.After
import org.junit.Before
import org.junit.Test
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.http.DynamicLlmClient
import xyz.block.trailblaze.llm.RunYamlRequest
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.llm.TrailblazeLlmProvider
import xyz.block.trailblaze.llm.TrailblazeReferrer
import xyz.block.trailblaze.mcp.android.ondevice.rpc.OnDeviceRpcClient
import xyz.block.trailblaze.model.TrailblazeConfig

/**
 * Pins the silent-failure fix against the three sibling paths in
 * [TrailblazeHostYamlRunner] that previously caught `Exception` and returned
 * `null`. The original silent-failure (executeTrailSession) hid a cached-LLM-
 * model bug: a thrown exception got swallowed, the
 * runner reported success, and MCP told the user "✓ Done" while the page was
 * blank.
 *
 * The two on-device-RPC paths covered here ([runHostV3WithAccessibilityYaml]
 * and [runHostTrailblazeRunnerWithOnDeviceRpc]) bail out on malformed trail
 * YAML before any device RPC is made, so we can drive them with stub
 * dependencies. A regression that re-introduces `return null` on YAML decode
 * failure would let `DesktopYamlRunner.runYaml` keep `executionResult =
 * Success` and lie to the caller — exactly the bug class the silent-failure fix closed.
 *
 * The third path ([TrailblazeHostYamlRunner.runRevylYaml]) is private and
 * needs a live Revyl cloud session to drive, so it's covered by code review
 * + the shared catch-block comment instead.
 */
class TrailblazeHostYamlRunnerSilentFailureTest {

  private val testDeviceId = TrailblazeDeviceId(
    instanceId = "silent-failure-test-device",
    trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
  )

  private val malformedYaml = "this: is: not: valid: trail: yaml: ::: -\n  - {{}}}"

  // Stub never invoked: bad-YAML decode happens before createLlmClient().
  private val stubLlmClient = object : DynamicLlmClient {
    override fun createPromptExecutor(): PromptExecutor =
      error("stub LLM client should not be invoked when YAML decode fails")
    override fun createLlmClient(): LLMClient =
      error("stub LLM client should not be invoked when YAML decode fails")
  }

  private val request = RunYamlRequest(
    testName = "silent-failure-test",
    yaml = malformedYaml,
    trailFilePath = null,
    targetAppName = null,
    useRecordedSteps = false,
    trailblazeDeviceId = testDeviceId,
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
  )

  // Constructed but never connects: bad-YAML decode happens before any RPC.
  private lateinit var rpcClient: OnDeviceRpcClient

  @Before
  fun setUp() {
    rpcClient = OnDeviceRpcClient(testDeviceId)
  }

  @After
  fun tearDown() {
    rpcClient.close()
  }

  @Test(expected = SerializationException::class)
  fun `runHostV3WithAccessibilityYaml rethrows on malformed yaml instead of returning null`() {
    runBlocking {
      TrailblazeHostYamlRunner.runHostV3WithAccessibilityYaml(
        dynamicLlmClient = stubLlmClient,
        onDeviceRpc = rpcClient,
        runYamlRequest = request,
        trailblazeDeviceId = testDeviceId,
        onProgressMessage = {},
        targetTestApp = null,
      )
    }
  }

  @Test(expected = SerializationException::class)
  fun `runHostTrailblazeRunnerWithOnDeviceRpc rethrows on malformed yaml instead of returning null`() {
    runBlocking {
      TrailblazeHostYamlRunner.runHostTrailblazeRunnerWithOnDeviceRpc(
        dynamicLlmClient = stubLlmClient,
        onDeviceRpc = rpcClient,
        runYamlRequest = request,
        trailblazeDeviceId = testDeviceId,
        onProgressMessage = {},
        targetTestApp = null,
      )
    }
  }

  @Test
  fun `runHostV3WithAccessibilityYaml surfaces the underlying decode error`() {
    // assertFails (not runCatching + !!) so an unexpected success surfaces a clear
    // "Expected an exception to be thrown" failure instead of a NullPointerException
    // on the !! — Copilot review feedback on the original PR.
    val captured = assertFails {
      runBlocking {
        TrailblazeHostYamlRunner.runHostV3WithAccessibilityYaml(
          dynamicLlmClient = stubLlmClient,
          onDeviceRpc = rpcClient,
          runYamlRequest = request,
          trailblazeDeviceId = testDeviceId,
          onProgressMessage = {},
          targetTestApp = null,
        )
      }
    }
    // Pin: the YAML library's typed exception must propagate. A regression to a
    // generic runtime error here means we wrapped or swallowed the original cause.
    assertThat(captured).isInstanceOf(SerializationException::class)
  }

  @Test
  fun `runHostV3WithAccessibilityYaml emits a progress message before throwing`() {
    val progress = mutableListOf<String>()
    runCatching {
      runBlocking {
        TrailblazeHostYamlRunner.runHostV3WithAccessibilityYaml(
          dynamicLlmClient = stubLlmClient,
          onDeviceRpc = rpcClient,
          runYamlRequest = request,
          trailblazeDeviceId = testDeviceId,
          onProgressMessage = { progress += it },
          targetTestApp = null,
        )
      }
    }
    // Pin: the user-facing progress channel still gets the failure reason — the
    // throw must happen *after* the progress emit, not in place of it. A regression
    // that drops the message would silently confuse the desktop UI / MCP client.
    assertThat(progress.joinToString("\n")).contains("Failed to decode trail YAML")
  }
}
