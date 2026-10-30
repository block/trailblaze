package xyz.block.trailblaze.host

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import org.junit.Test
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.host.TrailblazeHostYamlRunner.forBoundAndroidDevice
import xyz.block.trailblaze.llm.RunYamlRequest
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.llm.TrailblazeLlmProvider
import xyz.block.trailblaze.llm.TrailblazeReferrer
import xyz.block.trailblaze.model.TrailblazeConfig

/**
 * An iOS or web launch is run with `--device-classifier=<the launch device's lane key>`. An Android
 * companion sent that override would describe itself as the launch device and pick its recordings
 * by the wrong classifiers.
 */
class TrailblazeHostYamlRunnerBoundDeviceRequestTest {

  private val companionId = TrailblazeDeviceId(
    instanceId = "emulator-5556",
    trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
  )

  @Test
  fun `a companion does not inherit the launch device's classifier override`() {
    val request = launchRequest(deviceClassifierOverride = listOf("ios-iphone"))
      .forBoundAndroidDevice(
        trailblazeDeviceId = companionId,
        driverType = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
        targetAppId = null,
        isLaunchDevice = false,
      )

    assertThat(request.deviceClassifierOverride).isEmpty()
    assertThat(request.trailblazeDeviceId).isEqualTo(companionId)
  }

  @Test
  fun `the launch device keeps the request's classifier override`() {
    val request = launchRequest(deviceClassifierOverride = listOf("android-tablet"))
      .forBoundAndroidDevice(
        trailblazeDeviceId = companionId,
        driverType = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
        targetAppId = null,
        isLaunchDevice = true,
      )

    assertThat(request.deviceClassifierOverride).isEqualTo(listOf("android-tablet"))
  }

  @Test
  fun `a device's own target names the app, else the session's`() {
    val ownTarget = launchRequest().forBoundAndroidDevice(
      trailblazeDeviceId = companionId,
      driverType = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
      targetAppId = "companion-app",
      isLaunchDevice = false,
    )
    val sessionTarget = launchRequest().forBoundAndroidDevice(
      trailblazeDeviceId = companionId,
      driverType = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
      targetAppId = null,
      isLaunchDevice = false,
    )

    assertThat(ownTarget.targetAppName).isEqualTo("companion-app")
    assertThat(sessionTarget.targetAppName).isEqualTo("session-app")
  }

  private fun launchRequest(deviceClassifierOverride: List<String> = emptyList()) = RunYamlRequest(
    testName = "test",
    yaml = "",
    trailFilePath = null,
    targetAppName = "session-app",
    useRecordedSteps = false,
    trailblazeDeviceId = TrailblazeDeviceId(
      instanceId = "launch-device",
      trailblazeDevicePlatform = TrailblazeDevicePlatform.IOS,
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
    deviceClassifierOverride = deviceClassifierOverride,
  )
}
