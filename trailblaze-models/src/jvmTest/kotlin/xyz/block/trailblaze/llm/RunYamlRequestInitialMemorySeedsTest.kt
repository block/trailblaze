package xyz.block.trailblaze.llm

import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.model.TrailblazeConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Validates the `initialMemorySeeds` field on [RunYamlRequest] — the channel that carries
 * CLI `--memory KEY=VAL` entries from the CLI layer into the runner where they're applied
 * AFTER the trail YAML's `config.memory:` block. Default is empty, arbitrary maps pass
 * through unchanged.
 *
 * Distinct from [RunYamlRequest.memorySnapshot], which carries already-populated host memory
 * through to a sub-trail RPC dispatch — that channel has an `awaitCompletion=false` guard;
 * `initialMemorySeeds` does not (a fire-and-forget run is a fine target for CLI seeding).
 */
class RunYamlRequestInitialMemorySeedsTest {

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

  private fun newRequest(
    seeds: Map<String, String> = emptyMap(),
    awaitCompletion: Boolean = true,
  ): RunYamlRequest = RunYamlRequest(
    testName = "test",
    yaml = "",
    trailFilePath = null,
    targetAppName = null,
    useRecordedSteps = false,
    trailblazeDeviceId = deviceId,
    trailblazeLlmModel = llmModel,
    config = TrailblazeConfig(),
    referrer = TrailblazeReferrer(id = "test", display = "Test"),
    awaitCompletion = awaitCompletion,
    initialMemorySeeds = seeds,
  )

  @Test
  fun `default initialMemorySeeds is empty`() {
    val request = newRequest()
    assertTrue(request.initialMemorySeeds.isEmpty())
  }

  @Test
  fun `arbitrary seeds round trip unchanged`() {
    val seeds = mapOf("user" to "sam", "password" to "hunter2", "accountTier" to "PRO")
    val request = newRequest(seeds)
    assertEquals(seeds, request.initialMemorySeeds)
  }

  @Test
  fun `initialMemorySeeds and memorySnapshot are independent channels`() {
    val request = RunYamlRequest(
      testName = "test",
      yaml = "",
      trailFilePath = null,
      targetAppName = null,
      useRecordedSteps = false,
      trailblazeDeviceId = deviceId,
      trailblazeLlmModel = llmModel,
      config = TrailblazeConfig(),
      referrer = TrailblazeReferrer(id = "test", display = "Test"),
      memorySnapshot = mapOf("a" to "1"),
      initialMemorySeeds = mapOf("b" to "2"),
    )
    assertEquals(mapOf("a" to "1"), request.memorySnapshot)
    assertEquals(mapOf("b" to "2"), request.initialMemorySeeds)
  }

  @Test
  fun `initialMemorySeeds permitted on fire-and-forget runs (no awaitCompletion guard)`() {
    // memorySnapshot requires awaitCompletion=true (round-trip sync). initialMemorySeeds is
    // host→runner one-way data — no round-trip needed, so fire-and-forget is fine.
    val request = newRequest(
      seeds = mapOf("user" to "sam"),
      awaitCompletion = false,
    )
    assertEquals(mapOf("user" to "sam"), request.initialMemorySeeds)
  }

  // -- args twins: initialArgs (one-way, seeds-like) vs argsSnapshot (round-trip, snapshot-like) --

  @Test
  fun `initialArgs permitted on fire-and-forget runs, like the memory seeds twin`() {
    val request = RunYamlRequest(
      testName = "test",
      yaml = "",
      trailFilePath = null,
      targetAppName = null,
      useRecordedSteps = false,
      trailblazeDeviceId = deviceId,
      trailblazeLlmModel = llmModel,
      config = TrailblazeConfig(),
      referrer = TrailblazeReferrer(id = "test", display = "Test"),
      awaitCompletion = false,
      initialArgs = mapOf("recipient" to "\"sam\""),
    )
    assertEquals(mapOf("recipient" to "\"sam\""), request.initialArgs)
  }

  @Test
  fun `argsSnapshot on a fire-and-forget run is rejected, like the memorySnapshot twin`() {
    val error = kotlin.test.assertFailsWith<IllegalArgumentException> {
      RunYamlRequest(
        testName = "test",
        yaml = "",
        trailFilePath = null,
        targetAppName = null,
        useRecordedSteps = false,
        trailblazeDeviceId = deviceId,
        trailblazeLlmModel = llmModel,
        config = TrailblazeConfig(),
        referrer = TrailblazeReferrer(id = "test", display = "Test"),
        awaitCompletion = false,
        argsSnapshot = mapOf("x" to "\"v\""),
      )
    }
    assertTrue(error.message.orEmpty().contains("argsSnapshot"), error.message)
  }

  @Test
  fun `sensitiveArgNames on a fire-and-forget run is rejected - taint accompanies the snapshot`() {
    val error = kotlin.test.assertFailsWith<IllegalArgumentException> {
      RunYamlRequest(
        testName = "test",
        yaml = "",
        trailFilePath = null,
        targetAppName = null,
        useRecordedSteps = false,
        trailblazeDeviceId = deviceId,
        trailblazeLlmModel = llmModel,
        config = TrailblazeConfig(),
        referrer = TrailblazeReferrer(id = "test", display = "Test"),
        awaitCompletion = false,
        sensitiveArgNames = listOf("x"),
      )
    }
    assertTrue(error.message.orEmpty().contains("sensitiveArgNames"), error.message)
  }
}
