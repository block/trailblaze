package xyz.block.trailblaze.host

import assertk.assertThat
import assertk.assertions.isEqualTo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Test
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform

/**
 * Pins the degrade contract of [TrailblazeHostYamlRunner.hostProbedAndroidClassifiers], the
 * fallback both Android on-device-RPC runners use when the on-device classifier probe returns
 * nothing.
 *
 * The behavior that matters to callers is narrow: the fallback yields the probe's specific
 * classifier when it has one, and otherwise yields the bare platform — never nothing, and never
 * a stalled run. The probe itself is injected, so none of this touches adb; the resolver's own
 * classification rules (phone vs tablet, density, caching) are covered by
 * `DeviceClassifierResolverTest` and are deliberately not re-asserted here.
 */
class HostProbedAndroidClassifiersTest {

  private val deviceId = TrailblazeDeviceId(
    instanceId = "emulator-5554",
    trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
  )

  private val platformOnly = listOf(TrailblazeDeviceClassifier("android"))

  @Test
  fun `a probe that resolves a specific classifier is used`() = runBlocking {
    val resolved = TrailblazeHostYamlRunner.hostProbedAndroidClassifiers(deviceId) {
      listOf(TrailblazeDeviceClassifier("android"), TrailblazeDeviceClassifier("phone"))
    }

    assertThat(resolved).isEqualTo(
      listOf(TrailblazeDeviceClassifier("android"), TrailblazeDeviceClassifier("phone")),
    )
  }

  @Test
  fun `a throwing probe degrades to the bare platform`() = runBlocking {
    val resolved = TrailblazeHostYamlRunner.hostProbedAndroidClassifiers(deviceId) {
      error("adb unreachable")
    }

    assertThat(resolved).isEqualTo(platformOnly)
  }

  /**
   * An override is free to hand back an empty list, which downstream reads as device-AGNOSTIC
   * rather than "unknown" — a `resolveSkip` on any classifier would then apply to this run. The
   * expression this fallback replaced was unconditionally non-empty, so that contract is kept.
   */
  @Test
  fun `an empty probe result degrades to the bare platform rather than staying empty`() = runBlocking {
    val resolved = TrailblazeHostYamlRunner.hostProbedAndroidClassifiers(deviceId) { emptyList() }

    assertThat(resolved).isEqualTo(platformOnly)
  }

  /**
   * The load-bearing one. A distribution override may shell out through the UNBOUNDED
   * `execAdbShellCommand`, and a wedged dadb transport hangs on read instead of throwing — so the
   * probe can neither be cancelled nor unwound. This fallback runs exactly when the device RPC has
   * already failed, which is when that wedge is most likely, so a hung probe must not take the run
   * with it: the caller has to come back with the bare platform.
   *
   * The latch is released by the test itself, so this asserts the observable degrade contract
   * without betting on a wall-clock bound (the timeout is the code's own configured value, not a
   * measurement of how fast the machine ran).
   */
  @Test
  fun `a probe that never returns still yields the bare platform`() = runBlocking {
    val probeEntered = CountDownLatch(1)
    val releaseProbe = CountDownLatch(1)

    val resolved = TrailblazeHostYamlRunner.hostProbedAndroidClassifiers(
      trailblazeDeviceId = deviceId,
      probe = {
        probeEntered.countDown()
        // Blocks the way a wedged dadb read does — uninterruptible from the caller's side. Held
        // only until the assertion below has run.
        releaseProbe.await()
        listOf(TrailblazeDeviceClassifier("android"), TrailblazeDeviceClassifier("phone"))
      },
      probeTimeoutMs = 50,
    )

    assertThat(probeEntered.await(30, TimeUnit.SECONDS)).isEqualTo(true)
    assertThat(resolved).isEqualTo(platformOnly)
    releaseProbe.countDown()
  }
}
