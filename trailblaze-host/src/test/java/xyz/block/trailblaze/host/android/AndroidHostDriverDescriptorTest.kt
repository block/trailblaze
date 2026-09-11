package xyz.block.trailblaze.host.android

import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.model.PromptExecutor
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import xyz.block.trailblaze.devices.TrailblazeConnectedDeviceSummary
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.host.TrailblazeHostYamlRunner
import xyz.block.trailblaze.host.driver.ConnectedAdbDevice
import xyz.block.trailblaze.host.driver.DeviceListingVisibility
import xyz.block.trailblaze.host.driver.HostDeviceInventory
import xyz.block.trailblaze.host.driver.HostDriverDescriptor
import xyz.block.trailblaze.host.driver.HostDriverDescriptorRegistry
import xyz.block.trailblaze.host.minimalDeviceManager
import xyz.block.trailblaze.host.yaml.RunOnHostParams
import xyz.block.trailblaze.http.DynamicLlmClient
import xyz.block.trailblaze.llm.RunYamlRequest
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.llm.TrailblazeLlmProvider
import xyz.block.trailblaze.llm.TrailblazeReferrer
import xyz.block.trailblaze.model.TrailblazeConfig
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The three Android drivers are offered on the same devices under different execution engines, so
 * these cases assert the shape all three share: one device per `adb` serial, keyed on the serial,
 * and no host run body.
 */
class AndroidHostDriverDescriptorTest {

  private val inventory = HostDeviceInventory(
    adbDevices = listOf(
      ConnectedAdbDevice(serial = "emulator-5554", description = "Pixel 8"),
      ConnectedAdbDevice(serial = "R5CX12345", description = "Galaxy S24"),
    ),
  )

  private val descriptorsByDriver: Map<TrailblazeDriverType, HostDriverDescriptor> = mapOf(
    TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY to AndroidAccessibilityHostDriverDescriptor(),
    TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION to AndroidInstrumentationHostDriverDescriptor(),
    TrailblazeDriverType.ANDROID_TEST to AndroidTestHostDriverDescriptor(),
  )

  private val tempDir: File = File.createTempFile("trailblaze-android-descriptor-", "").also {
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

  private fun runParams(driverType: TrailblazeDriverType): RunOnHostParams {
    val device = TrailblazeConnectedDeviceSummary(
      trailblazeDriverType = driverType,
      instanceId = "emulator-5554",
      description = driverType.name,
    )
    return RunOnHostParams(
      targetTestApp = null,
      runYamlRequest = RunYamlRequest(
        testName = "android-host-run-path",
        yaml = "",
        trailFilePath = null,
        targetAppName = null,
        useRecordedSteps = false,
        trailblazeDeviceId = TrailblazeDeviceId(
          instanceId = device.instanceId,
          trailblazeDevicePlatform = driverType.platform,
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

  /**
   * Every connected device is offered under every Android driver, so a user who switches drivers
   * finds the same devices. Keyed on the serial because that is what `adb -s` addresses; the
   * description is cosmetic and two emulators can share one.
   */
  @Test
  fun `every connected device is offered under each android driver`() {
    descriptorsByDriver.forEach { (driverType, descriptor) ->
      val devices = runBlocking { descriptor.discoverDevices(inventory) }

      assertEquals(
        listOf("emulator-5554", "R5CX12345"),
        devices.map { it.instanceId },
        "$driverType must key devices on the adb serial",
      )
      assertEquals(
        listOf("Pixel 8", "Galaxy S24"),
        devices.map { it.description },
        "$driverType must carry the cosmetic name through",
      )
      assertEquals(
        setOf(driverType),
        devices.map { it.trailblazeDriverType }.toSet(),
        "$driverType must offer devices under its own driver type only",
      )
    }
  }

  /**
   * No devices connected means no devices offered — including on a host with no `adb` at all,
   * whose enumeration yields the empty inventory.
   */
  @Test
  fun `no connected devices yields nothing for every android driver`() {
    descriptorsByDriver.forEach { (driverType, descriptor) ->
      assertEquals(
        emptyList(),
        runBlocking { descriptor.discoverDevices(HostDeviceInventory.EMPTY) },
        "$driverType must offer nothing when no device is connected",
      )
    }
  }

  /**
   * `ANDROID_TEST` is offered on every device, NOT only devices whose selected target declares an
   * in-process harness. Filtering here would drop the device from the list with nothing to explain
   * why, and the answer would change as the user switched targets without a new discovery pass —
   * so the check lives at dispatch/connect time, where the error can name the missing declaration.
   */
  @Test
  fun `the in-process driver does not filter on target harness declarations`() {
    val devices = runBlocking { AndroidTestHostDriverDescriptor().discoverDevices(inventory) }

    assertEquals(2, devices.size, "discovery must not consult the selected target")
  }

  @Test
  fun `each android driver is listed and covers only its own entry`() {
    descriptorsByDriver.forEach { (driverType, descriptor) ->
      assertEquals(setOf(driverType), descriptor.driverTypes)
      assertEquals(DeviceListingVisibility.LISTED, descriptor.listingVisibility)
    }
  }

  /**
   * These drivers have no host run body, and dispatching a trail to the host path anyway has to
   * fail in terms of the actual mistake: `DesktopDispatchDecision` routes an on-device driver over
   * RPC, so arriving here means a dispatch arm and a descriptor disagree about where the driver
   * runs. Driven through the real `runHostYaml` rather than by calling the default directly,
   * because the dispatch-and-descriptor pairing is the thing under test.
   */
  @Test
  fun `dispatching an android driver to the host run path fails naming the driver`() {
    descriptorsByDriver.forEach { (driverType, descriptor) ->
      val message = assertFailsWith<IllegalStateException> {
        runBlocking {
          TrailblazeHostYamlRunner.runHostYaml(
            dynamicLlmClient = noLlm,
            runOnHostParams = runParams(driverType),
            deviceManager = minimalDeviceManager(
              tempDir,
              HostDriverDescriptorRegistry(setOf(descriptor)),
            ),
          )
        }
      }.message!!

      assertTrue(
        message.contains(driverType.name),
        "$driverType must refuse a host run naming itself, got: $message",
      )
      assertTrue(
        message.contains("runHostYaml"),
        "$driverType must name the path that should not have been taken, got: $message",
      )
      assertTrue(
        message.contains("on-device"),
        "$driverType must say where it DOES run, got: $message",
      )
    }
  }
}
