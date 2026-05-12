package xyz.block.trailblaze.llm

import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.model.TrailblazeConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * Validates the `maxLlmCalls` field on [RunYamlRequest]: null leaves the runner's built-in
 * default in place, positive integers pass through, and zero/negative values are rejected at
 * construction time so the CLI/RPC layer can't smuggle a nonsense cap into the inner loop.
 */
class RunYamlRequestMaxLlmCallsTest {

  private val deviceId = TrailblazeDeviceId(
    instanceId = "test-device",
    trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
  )

  private val llmModel = TrailblazeLlmModel(
    trailblazeLlmProvider = TrailblazeLlmProvider(id = "test", display = "Test"),
    modelId = "test-model",
    inputCostPerOneMillionTokens = 0.0,
    outputCostPerOneMillionTokens = 0.0,
    contextLength = 1000,
    maxOutputTokens = 1000,
    capabilityIds = emptyList(),
  )

  private fun newRequest(maxLlmCalls: Int?): RunYamlRequest = RunYamlRequest(
    testName = "test",
    yaml = "",
    trailFilePath = null,
    targetAppName = null,
    useRecordedSteps = false,
    trailblazeDeviceId = deviceId,
    trailblazeLlmModel = llmModel,
    config = TrailblazeConfig(),
    referrer = TrailblazeReferrer(id = "test", display = "Test"),
    maxLlmCalls = maxLlmCalls,
  )

  @Test
  fun `null maxLlmCalls is the default and stays null on the round trip`() {
    val request = newRequest(maxLlmCalls = null)
    assertNull(request.maxLlmCalls)
  }

  @Test
  fun `positive maxLlmCalls passes validation`() {
    val request = newRequest(maxLlmCalls = 5)
    assertEquals(5, request.maxLlmCalls)
  }

  @Test
  fun `zero maxLlmCalls is rejected`() {
    val failure = assertFailsWith<IllegalArgumentException> {
      newRequest(maxLlmCalls = 0)
    }
    assertEquals(true, failure.message?.contains("positive integer"))
  }

  @Test
  fun `negative maxLlmCalls is rejected`() {
    assertFailsWith<IllegalArgumentException> {
      newRequest(maxLlmCalls = -1)
    }
  }
}
