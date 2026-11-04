package xyz.block.trailblaze.host.yaml

import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.model.PromptExecutor
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.cli.DeviceClassifierResolver
import xyz.block.trailblaze.devices.TrailblazeConnectedDeviceSummary
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.host.HostYamlRunResult
import xyz.block.trailblaze.host.driver.DeviceListingVisibility
import xyz.block.trailblaze.host.driver.HostDeviceInventory
import xyz.block.trailblaze.host.driver.HostDriverDescriptor
import xyz.block.trailblaze.host.driver.HostDriverDescriptorRegistry
import xyz.block.trailblaze.host.driver.HostRunDeps
import xyz.block.trailblaze.host.driver.HostScreenStateDeps
import xyz.block.trailblaze.host.minimalDeviceManager
import xyz.block.trailblaze.http.DynamicLlmClient
import xyz.block.trailblaze.llm.RunYamlRequest
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.llm.TrailblazeLlmProvider
import xyz.block.trailblaze.llm.TrailblazeReferrer
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.model.DesktopAppRunYamlParams
import xyz.block.trailblaze.model.TrailExecutionResult
import xyz.block.trailblaze.model.TrailblazeConfig
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.ui.TrailblazeAnalytics
import xyz.block.trailblaze.yaml.TrailYamlItem
import xyz.block.trailblaze.yaml.createTrailblazeYaml

/**
 * A run tells its instrumentation-args provider whether it can reach the AI, so the provider can
 * skip an LLM token check only for a run that never calls the AI. Asserted through a real
 * [DesktopYamlRunner.runYaml] against a host device, so the answer comes from the trail as resolved
 * for that device, the same resolution the run then replays.
 */
class DesktopYamlRunnerLlmReachTest {

  @Test
  fun `a fully recorded replay with self-heal off cannot reach the AI`() = runBlocking {
    assertEquals(false, runCanReachLlmFor(fullyRecorded, useRecordedSteps = true, selfHeal = false))
  }

  @Test
  fun `self-heal on can reach the AI even when every step is recorded`() = runBlocking {
    assertEquals(true, runCanReachLlmFor(fullyRecorded, useRecordedSteps = true, selfHeal = true))
  }

  @Test
  fun `self-heal never hands a recorded trailhead to the AI`() {
    // Called directly: a trail document needs a step, and any recorded step can self-heal.
    val trailheadOnly =
      createTrailblazeYaml()
        .decodeTrail(fullyRecorded, deviceClassifiers = listOf(TrailblazeDeviceClassifier("ios")))
        .filterIsInstance<TrailYamlItem.TrailheadTrailItem>()
    assertEquals(1, trailheadOnly.size)
    assertEquals(
      false,
      DesktopYamlRunner.runCanReachLlm(trailItems = trailheadOnly, useRecordedSteps = true, selfHeal = true),
    )
  }

  @Test
  fun `an unrecorded step reaches the AI`() = runBlocking {
    val yaml = fullyRecorded + "  - step: Open the settings screen\n"
    assertEquals(true, runCanReachLlmFor(yaml, useRecordedSteps = true, selfHeal = false))
  }

  @Test
  fun `a step recorded only for another device reaches the AI on this one`() = runBlocking {
    val yaml = fullyRecorded.replace("    ios:\n", "    android:\n")
    assertEquals(true, runCanReachLlmFor(yaml, useRecordedSteps = true, selfHeal = false))
  }

  @Test
  fun `a recorded step whose tool asks the AI reaches the AI`() = runBlocking {
    val yaml = fullyRecorded + "        - assertWithAi:\n            prompt: The app is open\n"
    assertEquals(true, runCanReachLlmFor(yaml, useRecordedSteps = true, selfHeal = false))
  }

  @Test
  fun `a recorded trailhead whose tool asks the AI reaches the AI`() = runBlocking {
    val yaml = fullyRecorded.replaceFirst(
      "      launchApp:\n        appId: com.example.app\n",
      "      rememberText:\n        prompt: The welcome message\n        variable: welcome\n",
    )
    assertEquals(true, runCanReachLlmFor(yaml, useRecordedSteps = true, selfHeal = false))
  }

  @Test
  fun `a root tool item that asks the AI reaches the AI`() {
    // A trail document no longer decodes to root tool items (a bare tool list fails to decode, which
    // already counts as reachable), so the items are built directly.
    val reach = { tools: String ->
      DesktopYamlRunner.runCanReachLlm(
        trailItems = listOf(TrailYamlItem.ToolTrailItem(createTrailblazeYaml().decodeTools(tools))),
        useRecordedSteps = true,
        selfHeal = false,
      )
    }
    assertEquals(false, reach("- launchApp:\n    appId: com.example.app\n"))
    assertEquals(true, reach("- assertWithAi:\n    prompt: The app is open\n"))
  }

  @Test
  fun `recorded steps off sends every step to the AI`() = runBlocking {
    assertEquals(true, runCanReachLlmFor(fullyRecorded, useRecordedSteps = false, selfHeal = false))
  }

  @Test
  fun `a trail that does not decode for the device is treated as reaching the AI`() {
    // Called directly: a document that doesn't parse at all is rejected before the provider runs.
    assertEquals(true, DesktopYamlRunner.runCanReachLlm(trailItems = null, useRecordedSteps = true, selfHeal = false))
  }

  @Test
  fun `credentials that cannot be prepared fail the run as infrastructure`() = runBlocking {
    val result = runOnHostDevice(fullyRecorded, useRecordedSteps = true, selfHeal = false) {
      error("sign-in was refused")
    }
    assertIs<TrailExecutionResult.Failed>(result)
    assertContains(result.errorMessage.orEmpty(), "sign-in was refused")
    assertEquals(true, result.infrastructure)
  }

  @Test
  fun `a setup trail or self-heal from the runner environment can reach the AI`() {
    fun mayMiss(env: Map<String, String>) =
      DesktopYamlRunner.preflightMayMissLlm(TrailblazeDriverType.IOS_HOST, classificationDefinitive = true, env::get)
    assertEquals(false, mayMiss(emptyMap()))
    assertEquals(true, mayMiss(mapOf("TRAILBLAZE_SETUP_TRAIL_ID" to "setup.trail.yaml")))
    assertEquals(true, mayMiss(mapOf("TRAILBLAZE_SELF_HEAL_ENABLED" to "true")))
    assertEquals(false, mayMiss(mapOf("TRAILBLAZE_SELF_HEAL_ENABLED" to "false")))
  }

  @Test
  fun `an incomplete host probe can reach the AI through recordings the device picks`() {
    assertEquals(
      true,
      DesktopYamlRunner.preflightMayMissLlm(TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY, classificationDefinitive = false) { null },
    )
  }

  @Test
  fun `drivers that replay with classifiers of their own can reach the AI`() {
    fun mayMiss(driver: TrailblazeDriverType) =
      DesktopYamlRunner.preflightMayMissLlm(driver, classificationDefinitive = true) { null }
    listOf(
      TrailblazeDriverType.COMPOSE,
      TrailblazeDriverType.PLAYWRIGHT_ELECTRON,
      TrailblazeDriverType.REVYL_ANDROID,
      TrailblazeDriverType.REVYL_IOS,
    ).forEach { assertEquals(true, mayMiss(it), "$it") }
    listOf(
      TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
      TrailblazeDriverType.IOS_HOST,
      TrailblazeDriverType.PLAYWRIGHT_NATIVE,
    ).forEach { assertEquals(false, mayMiss(it), "$it") }
  }

  private val fullyRecorded =
    """
    trailhead:
      step: Launch the app
      recording:
        ios:
          launchApp:
            appId: com.example.app
    trail:
      - step: Relaunch the app
        recording:
          ios:
            - launchApp:
                appId: com.example.app
    """.trimIndent() + "\n"

  /** Runs [yaml] on a host iOS device and returns what the run told its args provider. */
  private suspend fun runCanReachLlmFor(
    yaml: String,
    useRecordedSteps: Boolean,
    selfHeal: Boolean,
  ): Boolean {
    val reported = CompletableDeferred<Boolean>()
    runOnHostDevice(yaml, useRecordedSteps, selfHeal) { runCanReachLlm ->
      reported.complete(runCanReachLlm)
      emptyMap()
    }
    return withTimeout(10_000) { reported.await() }
  }

  /** Runs [yaml] on a host iOS device with [instrumentationArgs] as its args provider. */
  private suspend fun runOnHostDevice(
    yaml: String,
    useRecordedSteps: Boolean,
    selfHeal: Boolean,
    instrumentationArgs: suspend (Boolean) -> Map<String, String>,
  ): TrailExecutionResult {
    val tempDir = File.createTempFile("desktop-runner-llm-reach-", "").also {
      it.delete()
      it.mkdirs()
    }
    // A fake simulator can't be probed; classify it definitively, as a real one would be.
    val previousOverride = DeviceClassifierResolver.currentOverrideForTesting()
    DeviceClassifierResolver.resetCacheForTesting()
    DeviceClassifierResolver.installOverride { _, _ ->
      listOf(TrailblazeDeviceClassifier("ios"), TrailblazeDeviceClassifier("iphone"))
    }
    try {
      val device =
        TrailblazeConnectedDeviceSummary(
          trailblazeDriverType = TrailblazeDriverType.IOS_HOST,
          instanceId = "desktop-runner-llm-reach-ios",
          description = "Desktop runner LLM reach test",
        )
      val descriptor =
        object : HostDriverDescriptor {
          override val driverTypes = setOf(TrailblazeDriverType.IOS_HOST)
          override val listingVisibility = DeviceListingVisibility.ADDRESSABLE_NOT_LISTED

          override suspend fun discoverDevices(
            inventory: HostDeviceInventory
          ): List<TrailblazeConnectedDeviceSummary> = listOf(device)

          override suspend fun runYaml(deps: HostRunDeps, params: RunOnHostParams) =
            HostYamlRunResult(sessionId = SessionId("llm-reach-session"))

          override suspend fun screenState(
            driverType: TrailblazeDriverType,
            deviceId: TrailblazeDeviceId,
            deps: HostScreenStateDeps,
          ): ScreenState? = error("screen capture is not part of this test")
        }
      val runner =
        DesktopYamlRunner(
          trailblazeDeviceManager =
            minimalDeviceManager(tempDir = tempDir, registry = HostDriverDescriptorRegistry(setOf(descriptor))),
          trailblazeAnalytics = TrailblazeAnalytics.NoOp,
          trailblazeHostAppTargetProvider = { TrailblazeHostAppTarget.DefaultTrailblazeHostAppTarget },
          dynamicLlmClientProvider = { noLlm },
          logsDirProvider = { File(tempDir, "logs") },
        )
      val completion = CompletableDeferred<TrailExecutionResult>()
      runner.runYaml(
        DesktopAppRunYamlParams(
          forceStopTargetApp = false,
          runYamlRequest =
            RunYamlRequest(
              testName = "desktop runner llm reach",
              yaml = yaml,
              trailFilePath = null,
              targetAppName = null,
              useRecordedSteps = useRecordedSteps,
              trailblazeDeviceId = device.trailblazeDeviceId,
              trailblazeLlmModel = testModel,
              config = TrailblazeConfig(selfHeal = selfHeal),
              referrer = TrailblazeReferrer.YAML_TAB,
            ),
          targetTestApp = null,
          onProgressMessage = {},
          onConnectionStatus = {},
          additionalInstrumentationArgs = instrumentationArgs,
          onComplete = { completion.complete(it) },
          noLogging = true,
          captureVideo = false,
          captureLogcat = false,
          captureIosLogs = false,
        ),
      ).join()
      return withTimeout(10_000) { completion.await() }
    } finally {
      DeviceClassifierResolver.installOverride(previousOverride)
      DeviceClassifierResolver.resetCacheForTesting()
      tempDir.deleteRecursively()
    }
  }

  private val noLlm =
    object : DynamicLlmClient {
      override fun createPromptExecutor(): PromptExecutor = error("no LLM in this test")

      override fun createLlmClient(): LLMClient = error("no LLM in this test")
    }

  private val testModel =
    TrailblazeLlmModel(
      trailblazeLlmProvider = TrailblazeLlmProvider(id = "test", display = "Test"),
      modelId = "test-model",
      inputCostPerOneMillionTokens = 0.0,
      outputCostPerOneMillionTokens = 0.0,
      contextLength = 1000,
      maxOutputTokens = 1000,
      capabilityIds = emptyList(),
    )
}
