package xyz.block.trailblaze.host.devices

import assertk.assertThat
import assertk.assertions.isEqualTo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Test
import xyz.block.trailblaze.cli.DeviceClassifierResolver
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform

/**
 * Pins the degrade contract of [HostProbedDeviceClassifiers.forDevice], the host-side
 * classification both the Android on-device-RPC runners (when the on-device probe returns nothing)
 * and the MCP trail executor rely on.
 *
 * The behavior that matters to callers is narrow: it yields the probe's specific classifier when
 * that classifier is definitive, and otherwise yields the bare platform — never nothing, never a
 * guessed sub-category, and never a stalled run. The probe itself is injected, so none of this
 * touches adb; the resolver's own classification rules (phone vs tablet, density, caching,
 * definitiveness) are covered by `DeviceClassifierResolverTest` and are deliberately not
 * re-asserted here.
 */
class HostProbedDeviceClassifiersTest {

  /**
   * Each test gets its own instance id: [HostProbedDeviceClassifiers] coalesces concurrent probes by
   * device, so a shared id would let the never-returns test's parked future be joined by another
   * test instead of running that test's own probe.
   */
  private fun androidDeviceId(instanceId: String) = TrailblazeDeviceId(
    instanceId = instanceId,
    trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
  )

  private val androidPlatformOnly = listOf(TrailblazeDeviceClassifier("android"))
  private val androidPhone =
    listOf(TrailblazeDeviceClassifier("android"), TrailblazeDeviceClassifier("phone"))

  private fun definitive(classifiers: List<TrailblazeDeviceClassifier>) =
    DeviceClassifierResolver.Classification(classifiers, definitive = true)

  @Test
  fun `a probe that resolves a definitive classifier is used`() = runBlocking {
    val resolved = HostProbedDeviceClassifiers.forDevice(androidDeviceId("emulator-definitive")) { definitive(androidPhone) }

    assertThat(resolved).isEqualTo(androidPhone)
  }

  @Test
  fun `a throwing probe degrades to the bare platform`() = runBlocking {
    val resolved = HostProbedDeviceClassifiers.forDevice(androidDeviceId("emulator-throwing")) { error("adb unreachable") }

    assertThat(resolved).isEqualTo(androidPlatformOnly)
  }

  /**
   * An override is free to hand back an empty list, which downstream reads as device-AGNOSTIC
   * rather than "unknown" — a `resolveSkip` on any classifier would then apply to this run. The
   * expression this fallback replaced was unconditionally non-empty, so that contract is kept.
   */
  @Test
  fun `an empty probe result degrades to the bare platform rather than staying empty`() = runBlocking {
    val resolved = HostProbedDeviceClassifiers.forDevice(androidDeviceId("emulator-empty")) { definitive(emptyList()) }

    assertThat(resolved).isEqualTo(androidPlatformOnly)
  }

  /**
   * The point of fix 2. A non-definitive classification is the resolver saying "this is the
   * pixel-heuristic guess, not a measurement" — and on a low-density tablet that guess is `phone`.
   * Closest-wins lowering would then select the `android-phone:` recording leg and run steps
   * recorded for a different device shape, which is strictly worse than resolving the generic
   * `android:` leg. So a non-definitive answer is discarded, not narrowed.
   */
  @Test
  fun `a non-definitive classification is discarded in favor of the bare platform`() = runBlocking {
    val resolved = HostProbedDeviceClassifiers.forDevice(androidDeviceId("emulator-nondefinitive")) {
      DeviceClassifierResolver.Classification(androidPhone, definitive = false)
    }

    assertThat(resolved).isEqualTo(androidPlatformOnly)
  }

  /** The platform-only fallback follows the device, so a non-Android session degrades to `[ios]`. */
  @Test
  fun `the platform-only fallback is the device's own platform`() = runBlocking {
    val iosDeviceId = TrailblazeDeviceId(
      instanceId = "SIM-UUID",
      trailblazeDevicePlatform = TrailblazeDevicePlatform.IOS,
    )

    val resolved = HostProbedDeviceClassifiers.forDevice(iosDeviceId) { error("simctl unreachable") }

    assertThat(resolved).isEqualTo(listOf(TrailblazeDeviceClassifier("ios")))
  }

  /**
   * The load-bearing one. A distribution override may shell out through the UNBOUNDED
   * `execAdbShellCommand`, and a wedged dadb transport hangs on read instead of throwing — so the
   * probe can neither be cancelled nor unwound. On the runner path this runs exactly when the device
   * RPC has already failed, which is when that wedge is most likely, so a hung probe must not take
   * the run with it: the caller has to come back with the bare platform.
   *
   * The latch is released by the test itself, so this asserts the observable degrade contract
   * without betting on a wall-clock bound (the timeout is the code's own configured value, not a
   * measurement of how fast the machine ran).
   */
  @Test
  fun `a probe that never returns still yields the bare platform`() = runBlocking {
    val probeEntered = CountDownLatch(1)
    val releaseProbe = CountDownLatch(1)

    val resolved = HostProbedDeviceClassifiers.forDevice(
      deviceId = androidDeviceId("emulator-wedged"),
      probe = {
        probeEntered.countDown()
        // Blocks the way a wedged dadb read does — uninterruptible from the caller's side. Held
        // only until the assertion below has run.
        releaseProbe.await()
        definitive(androidPhone)
      },
      probeTimeoutMs = 50,
    )

    assertThat(probeEntered.await(30, TimeUnit.SECONDS)).isEqualTo(true)
    assertThat(resolved).isEqualTo(androidPlatformOnly)
    releaseProbe.countDown()
  }

  /**
   * A timed-out classification is never cached by the resolver, so without coalescing every replay
   * against a wedged device would start another probe and park another thread — unbounded growth for
   * as long as the wedge lasts. Repeated calls must join the in-flight probe instead: one probe
   * entry for three calls, each still degrading to platform-only on its own bound.
   */
  @Test
  fun `repeated calls against a wedged device join the in-flight probe`() = runBlocking {
    val probeEntries = AtomicInteger(0)
    val releaseProbe = CountDownLatch(1)
    val wedgedDevice = androidDeviceId("emulator-wedged-repeat")

    repeat(3) {
      val resolved = HostProbedDeviceClassifiers.forDevice(
        deviceId = wedgedDevice,
        probe = {
          probeEntries.incrementAndGet()
          releaseProbe.await()
          definitive(androidPhone)
        },
        probeTimeoutMs = 50,
      )
      assertThat(resolved).isEqualTo(androidPlatformOnly)
    }

    assertThat(probeEntries.get()).isEqualTo(1)
    releaseProbe.countDown()
  }

  /**
   * End-to-end against the REAL resolver (only the dimension probe is stubbed) with an installed
   * override that declines the device — the shape any distribution presents once it installs an
   * override, since an override recognizes only its own hardware and declines everything else.
   *
   * The measured sub-category has to survive. Treating "an override declined" as uncertainty would
   * hand back bare `[android]` for a device whose `wm size` and `wm density` both succeeded, which
   * is both worse than the value this replaced and fatal for a step recorded only under
   * `android-phone:`.
   */
  @Test
  fun `a declining override does not cost the device its measured sub-category`() = runBlocking {
    DeviceClassifierResolver.resetCacheForTesting()
    DeviceClassifierResolver.installOverride { _, _ -> null }
    try {
      val deviceId = androidDeviceId("emulator-declined-override")
      val resolved = HostProbedDeviceClassifiers.forDevice(deviceId) { id ->
        DeviceClassifierResolver.classificationFor(
          platform = id.trailblazeDevicePlatform,
          instanceId = id.instanceId,
          dimensionsProbe = DeviceClassifierResolver.DimensionsProbe { _, _ ->
            DeviceClassifierResolver.DeviceProbe(1080, 2340, densityDpi = 400)
          },
        )
      }

      assertThat(resolved).isEqualTo(androidPhone)
    } finally {
      DeviceClassifierResolver.installOverride(null)
      DeviceClassifierResolver.resetCacheForTesting()
    }
  }
}
