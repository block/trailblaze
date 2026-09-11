package xyz.block.trailblaze.devices

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidAccessibilityServiceDriversTest {

  private val serviceBacked = TrailblazeDriverType.entries
    .filter { AndroidAccessibilityServiceDrivers.includes(it) }

  /**
   * Eight host call sites and two on-device ones read this to decide whether to bind the
   * accessibility service and whether to require it in the readiness probe. Pinned rather than
   * derived because both consequences of a wrong answer are silent: an unbound service means the
   * driver's first tap fails against a device the probe already called ready, and a driver wrongly
   * included gets the service enabled and required on a device that never needed it — a readiness
   * timeout on a healthy server.
   */
  @Test
  fun `membership is pinned`() {
    assertEquals(
      listOf(TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY),
      serviceBacked,
    )
  }

  /**
   * The service is bound over ADB against a connected Android device and required in an on-device
   * RPC probe, so a driver in this set must be an Android driver the host reaches over RPC. A
   * host-resident or non-Android driver would have the host shell out to bind a service on a device
   * it isn't driving, then block on a probe with nothing to answer it.
   */
  @Test
  fun `service-backed drivers are Android drivers the host reaches over RPC`() {
    // Without this the per-driver checks below pass vacuously the moment the set empties, which is
    // the regression the membership test above catches — this test would just stop noticing.
    assertTrue(serviceBacked.isNotEmpty(), "no driver drives through the accessibility service")
    serviceBacked.forEach { driverType ->
      assertEquals(
        TrailblazeDevicePlatform.ANDROID,
        driverType.platform,
        "$driverType binds an Android service, so it must be an Android driver",
      )
      assertTrue(
        driverType.hostRpcReachable,
        "$driverType must be reachable over RPC for the probe to require its service",
      )
    }
  }

  /**
   * Two host readers and the on-device one hold a nullable driver — no driver resolved for the
   * device yet. That has to answer "not service-backed" rather than throw, and it must not be
   * mistaken for a driver that needs the service: binding one for a device with no driver would
   * enable an accessibility service on it and then require it in a probe nothing answers.
   */
  @Test
  fun `an unresolved driver is not service-backed`() {
    assertFalse(AndroidAccessibilityServiceDrivers.includes(null))
  }

  /**
   * Being on Android is not the question. `REVYL_ANDROID` targets Android from a cloud host with no
   * device this host can bind a service on, and the two other on-device Android drivers reach the
   * screen through Maestro and in-process Espresso instead. Asserted per driver rather than as a
   * count so a widening names which one widened.
   */
  @Test
  fun `the other Android drivers are excluded`() {
    listOf(
      TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
      TrailblazeDriverType.ANDROID_TEST,
      TrailblazeDriverType.REVYL_ANDROID,
    ).forEach { driverType ->
      assertFalse(
        AndroidAccessibilityServiceDrivers.includes(driverType),
        "$driverType does not reach the screen through the accessibility service",
      )
    }
  }

  /**
   * No driver off Android can be service-backed. Kept as a sweep over every non-Android entry
   * rather than a spot check, so a driver added to another platform cannot be quietly included.
   */
  @Test
  fun `no driver off Android is service-backed`() {
    TrailblazeDriverType.entries
      .filter { it.platform != TrailblazeDevicePlatform.ANDROID }
      .forEach { driverType ->
        assertFalse(
          AndroidAccessibilityServiceDrivers.includes(driverType),
          "$driverType is not an Android driver, so it has no Android service to bind",
        )
      }
  }
}
