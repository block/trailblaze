package xyz.block.trailblaze.host

import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.model.PromptExecutor
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import xyz.block.trailblaze.model.TrailblazeConfig
import xyz.block.trailblaze.devices.TrailblazeConnectedDeviceSummary
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.host.driver.HostDriverDescriptorRegistry
import xyz.block.trailblaze.host.yaml.RunOnHostParams
import xyz.block.trailblaze.http.DynamicLlmClient
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.llm.TrailblazeLlmProvider
import xyz.block.trailblaze.llm.RunYamlRequest
import xyz.block.trailblaze.llm.TrailblazeReferrer
import java.io.File
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A converted driver lost its `when` arm in [TrailblazeHostYamlRunner], so if no descriptor claims
 * it the `else` would hand it to the Maestro runner — a driver Revyl cannot be driven by. Running
 * on the WRONG driver is worse than not running: it reports failures against machinery the trail
 * never asked for.
 */
class HostYamlRunnerUnregisteredDriverTest {

  private val tempDir: File = File.createTempFile("trailblaze-unregistered-driver-", "").also {
    it.delete()
    it.mkdirs()
  }

  @After
  fun tearDown() {
    tempDir.deleteRecursively()
  }

  private val noLlm = object : DynamicLlmClient {
    override fun createPromptExecutor(): PromptExecutor = error("no LLM in this test")
    override fun createLlmClient(): LLMClient = error("no LLM in this test")
  }

  private fun runParams(
    driverType: TrailblazeDriverType,
    platform: TrailblazeDevicePlatform,
  ): RunOnHostParams {
    val device = TrailblazeConnectedDeviceSummary(
      trailblazeDriverType = driverType,
      instanceId = "unregistered-${driverType.name.lowercase()}",
      description = driverType.name,
    )
    return RunOnHostParams(
      targetTestApp = null,
      runYamlRequest = RunYamlRequest(
        testName = "unregistered-driver",
        yaml = "",
        trailFilePath = null,
        targetAppName = null,
        useRecordedSteps = false,
        trailblazeDeviceId = TrailblazeDeviceId(
          instanceId = device.instanceId,
          trailblazeDevicePlatform = platform,
        ),
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
      ),
      device = device,
      forceStopTargetApp = false,
      additionalInstrumentationArgs = { emptyMap() },
      onProgressMessage = {},
      referrer = TrailblazeReferrer(id = "test", display = "Test"),
    )
  }

  private fun failureMessageFor(
    driverType: TrailblazeDriverType,
    platform: TrailblazeDevicePlatform,
  ): String = assertFailsWith<IllegalStateException> {
    runBlocking {
      TrailblazeHostYamlRunner.runHostYaml(
        dynamicLlmClient = noLlm,
        runOnHostParams = runParams(driverType, platform),
        deviceManager = minimalDeviceManager(tempDir, HostDriverDescriptorRegistry.EMPTY),
      )
    }
  }.message!!

  /**
   * The failure has to name the driver and the remedy. Reaching the Maestro `else` instead would
   * fail somewhere inside a Maestro session, blaming the trail rather than the missing plug-in.
   */
  @Test
  fun `running a converted driver with nothing registered fails saying so`() {
    val message = failureMessageFor(TrailblazeDriverType.REVYL_ANDROID, TrailblazeDevicePlatform.ANDROID)

    assertTrue(message.contains("REVYL_ANDROID"), "must name the driver that isn't plugged in: $message")
    assertTrue(message.contains("hostDriverDescriptors"), "must name the remedy: $message")
  }

  /**
   * Same guard for Compose: its `when` arm is gone, and the Maestro `else` cannot drive a Compose
   * RPC app. Dropping COMPOSE from `convertedDriverTypes` would send it there silently.
   */
  @Test
  fun `running compose with nothing registered fails saying so`() {
    val message = failureMessageFor(TrailblazeDriverType.COMPOSE, TrailblazeDriverType.COMPOSE.platform)

    assertTrue(message.contains("COMPOSE"), "must name the driver that isn't plugged in: $message")
    assertTrue(message.contains("hostDriverDescriptors"), "must name the remedy: $message")
  }

  /**
   * Same guard for the web drivers: their `when` arms are gone, and the Maestro fallback cannot
   * drive a Playwright page. Dropping either from `convertedDriverTypes` would send it there
   * silently.
   */
  @Test
  fun `running playwright native with nothing registered fails saying so`() {
    val message = failureMessageFor(
      TrailblazeDriverType.PLAYWRIGHT_NATIVE,
      TrailblazeDriverType.PLAYWRIGHT_NATIVE.platform,
    )

    assertTrue(message.contains("PLAYWRIGHT_NATIVE"), "must name the driver that isn't plugged in: $message")
    assertTrue(message.contains("hostDriverDescriptors"), "must name the remedy: $message")
  }

  @Test
  fun `running playwright electron with nothing registered fails saying so`() {
    val message = failureMessageFor(
      TrailblazeDriverType.PLAYWRIGHT_ELECTRON,
      TrailblazeDriverType.PLAYWRIGHT_ELECTRON.platform,
    )

    assertTrue(message.contains("PLAYWRIGHT_ELECTRON"), "must name the driver that isn't plugged in: $message")
    assertTrue(message.contains("hostDriverDescriptors"), "must name the remedy: $message")
  }
}
