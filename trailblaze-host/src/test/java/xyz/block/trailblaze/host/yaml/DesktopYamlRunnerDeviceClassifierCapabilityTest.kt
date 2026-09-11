package xyz.block.trailblaze.host.yaml

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import xyz.block.trailblaze.mcp.android.ondevice.rpc.OnDeviceRunnerCapabilities

class DesktopYamlRunnerDeviceClassifierCapabilityTest {

  @Test
  fun `ordinary runs do not require a runner capability`() {
    assertNull(
      DesktopYamlRunner.deviceClassifierOverrideCapabilityRejection(
        requestsOverride = false,
        runnerCapabilities = null,
      ),
    )
  }

  @Test
  fun `classifier override is accepted when the runner advertises support`() {
    assertNull(
      DesktopYamlRunner.deviceClassifierOverrideCapabilityRejection(
        requestsOverride = true,
        runnerCapabilities = OnDeviceRunnerCapabilities.ALL,
      ),
    )
  }

  @Test
  fun `classifier override is rejected when a stale runner advertises no support`() {
    assertNotNull(
      DesktopYamlRunner.deviceClassifierOverrideCapabilityRejection(
        requestsOverride = true,
        runnerCapabilities = null,
      ),
    )
  }

  @Test
  fun `an unrelated runner capability does not authorize classifier overrides`() {
    assertNotNull(
      DesktopYamlRunner.deviceClassifierOverrideCapabilityRejection(
        requestsOverride = true,
        runnerCapabilities = listOf("some-other-capability"),
      ),
    )
  }
}
