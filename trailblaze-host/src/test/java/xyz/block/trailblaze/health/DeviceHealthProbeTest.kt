package xyz.block.trailblaze.health

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for [DeviceHealthProbe.check] — the pure device-reachability decision.
 *
 * The device round-trip is injected as a plain `suspend () -> DeviceProbeOutcome` lambda, so every
 * branch is exercised with plain inputs — no real device, no mock of a device API type. Assertions
 * are on the observable contract ([DeviceHealthResult] and that the injected probe is invoked and
 * bounded), never on internal control flow.
 */
class DeviceHealthProbeTest {

  @Test
  fun `reachable probe yields deviceReachable true and forwards the detail`() = runTest {
    val result = DeviceHealthProbe.check {
      DeviceProbeOutcome.Reachable("device emulator-5554 answered getprop")
    }

    assertTrue(result.deviceReachable)
    assertTrue(result.detail.contains("answered getprop"))
  }

  @Test
  fun `unreachable probe yields deviceReachable false and forwards the detail`() = runTest {
    val result = DeviceHealthProbe.check {
      DeviceProbeOutcome.Unreachable("no Android device attached to the daemon")
    }

    assertFalse(result.deviceReachable)
    assertTrue(result.detail.contains("no Android device"))
  }

  @Test
  fun `probe that throws yields deviceReachable false without propagating the exception`() = runTest {
    val result = DeviceHealthProbe.check {
      throw IllegalStateException("adb transport reset")
    }

    assertFalse(result.deviceReachable)
    assertTrue(result.detail.contains("adb transport reset"))
  }

  @Test
  fun `a probe slower than the timeout is reported unreachable, not awaited to completion`() = runTest {
    val timeoutMs = 50L
    var probeCompleted = false
    val result = DeviceHealthProbe.check(timeoutMs = timeoutMs) {
      delay(timeoutMs * 100)
      probeCompleted = true
      DeviceProbeOutcome.Reachable("should never be reported")
    }

    assertFalse(result.deviceReachable)
    // The wedge symptom: the probe never got to finish, yet the check still returned.
    assertFalse(probeCompleted)
  }

  @Test
  fun `the injected device probe is actually invoked`() = runTest {
    var invoked = false
    DeviceHealthProbe.check {
      invoked = true
      DeviceProbeOutcome.Reachable("ok")
    }

    assertTrue(invoked)
  }

  @Test
  fun `elapsedMs is populated for a normal completion`() = runTest {
    val result = DeviceHealthProbe.check {
      DeviceProbeOutcome.Reachable("ok")
    }

    assertTrue(result.elapsedMs >= 0)
  }

  @Test
  fun `result serializes to the deviceReachable detail elapsedMs JSON contract`() {
    val json = kotlinx.serialization.json.Json { encodeDefaults = true }
    val encoded = json.encodeToString(
      DeviceHealthResult.serializer(),
      DeviceHealthResult(deviceReachable = true, detail = "ok", elapsedMs = 7),
    )
    val decoded = json.decodeFromString(DeviceHealthResult.serializer(), encoded)

    assertEquals(DeviceHealthResult(deviceReachable = true, detail = "ok", elapsedMs = 7), decoded)
    assertTrue(encoded.contains("\"deviceReachable\""))
    assertTrue(encoded.contains("\"detail\""))
    assertTrue(encoded.contains("\"elapsedMs\""))
  }
}
