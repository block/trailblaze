package xyz.block.trailblaze.util

import assertk.assertThat
import assertk.assertions.containsExactly
import org.junit.Test
import xyz.block.trailblaze.android.tools.shellEscape
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDevicePort
import xyz.block.trailblaze.devices.TrailblazeDevicePort.getTrailblazeOnDeviceSpecificPort

class HostAndroidDeviceConnectUtilsTest {

  private val deviceId = TrailblazeDeviceId(
    instanceId = "emulator-5554",
    trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
  )

  @Test
  fun instrumentationCommandArgsShellEscapeDynamicValues() {
    val args = HostAndroidDeviceConnectUtils.instrumentationAdbShellCommandArgs(
      testAppId = "xyz.block.trailblaze.runner",
      fqTestName = "xyz.block.trailblaze.AndroidStandaloneServerTest",
      deviceId = deviceId,
      additionalInstrumentationArgs = linkedMapOf(
        "trailblaze.llm.auth.token.test" to "token with spaces & symbols \$(echo nope)",
        "trailblaze.llm.provider.base_url" to "https://example.com/v1/chat?x=1&y=2",
        "trailblaze.llm.default_model" to "openai/gpt 4.1",
      ),
    )

    assertThat(args).containsExactly(
      "am",
      "instrument",
      "-w",
      "-r",
      "-e",
      "class",
      "xyz.block.trailblaze.AndroidStandaloneServerTest".shellEscape(),
      "-e",
      "trailblaze.reverseProxy".shellEscape(),
      "true".shellEscape(),
      "-e",
      TrailblazeDevicePort.INSTRUMENTATION_ARG_KEY.shellEscape(),
      deviceId.getTrailblazeOnDeviceSpecificPort().toString().shellEscape(),
      "-e",
      "trailblaze.llm.auth.token.test".shellEscape(),
      "token with spaces & symbols \$(echo nope)".shellEscape(),
      "-e",
      "trailblaze.llm.provider.base_url".shellEscape(),
      "https://example.com/v1/chat?x=1&y=2".shellEscape(),
      "-e",
      "trailblaze.llm.default_model".shellEscape(),
      "openai/gpt 4.1".shellEscape(),
      "xyz.block.trailblaze.runner/androidx.test.runner.AndroidJUnitRunner".shellEscape(),
    )
  }
}
