package xyz.block.trailblaze.util

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDeviceId

/**
 * A bounded host shell attempt has three outcomes and they are not interchangeable. The caller
 * turns each into a different thrown message, so getting them confused here means a disconnected
 * device is reported as a hang.
 */
class BoundedAdbShellAttemptTest {

  private val deviceId = TrailblazeDeviceId("emulator-5554", TrailblazeDevicePlatform.ANDROID)
  private val release = CountDownLatch(1)

  @AfterTest
  fun releaseAnyParkedWorker() {
    // The hang test leaves a daemon worker parked. Freeing it keeps the JVM's thread count from
    // growing across a full test run.
    release.countDown()
  }

  private fun attempt(timeoutMs: Long = 1_000L, shellCall: () -> String?) =
    AndroidHostAdbUtils.runSingleAdbShellAttempt(
      deviceId = deviceId,
      command = "getprop ro.build.version.sdk",
      redactedCommand = "getprop ro.build.version.sdk",
      timeoutMs = timeoutMs,
      evictClientOnTimeout = false,
      // Real callers report the Dadb client they resolved through this callback so a timeout can
      // evict that exact instance; these fakes have no client, so it's simply never invoked.
      shellCall = { _ -> shellCall() },
    )

  @Test
  fun `a command that answers is a success carrying its output`() {
    val result = attempt { "34" }
    assertEquals(AndroidHostAdbUtils.ShellAttemptOutcome.SUCCESS, result.outcome)
    assertEquals("34", result.value)
    assertNull(result.error, "a success has nothing to report as a cause")
  }

  @Test
  fun `a command that throws is a failure that keeps the exception`() {
    // The caller rethrows with this attached. Dropping it here is what leaves a session log
    // holding "adb shell failed" and no indication of why.
    val boom = IOException("connection reset by peer")
    val result = attempt { throw boom }
    assertEquals(AndroidHostAdbUtils.ShellAttemptOutcome.FAILED, result.outcome)
    assertNull(result.value)
    assertSame(boom, result.error)
  }

  @Test
  fun `a command that never comes back is a timeout, and is not reported as a failure`() {
    // The distinction the bound exists to make. A timeout also has no exception to blame, which
    // is why the caller's wording for it must not promise a cause.
    val result = attempt(timeoutMs = 150L) {
      release.await(30, TimeUnit.SECONDS)
      "too late"
    }
    assertEquals(AndroidHostAdbUtils.ShellAttemptOutcome.TIMED_OUT, result.outcome)
    assertNull(result.value)
    assertNull(result.error, "a timeout has no exception, so none must be invented")
  }

  @Test
  fun `the attempt itself never loops on a failure`() {
    // Covers only the attempt's own control flow: this fake replaces the transport. That the
    // default transport does not retry either is pinned where it lives, in
    // AndroidHostAdbUtilsTest's runOnResolvedClientOnce tests.
    var calls = 0
    val result = attempt { calls++; throw java.io.IOException("connection reset by peer") }
    assertEquals(1, calls, "a single bounded attempt must invoke the transport once")
    assertEquals(AndroidHostAdbUtils.ShellAttemptOutcome.FAILED, result.outcome)
  }

  @Test
  fun `a value the worker produced is reported, never discarded`() {
    // Deterministic on purpose. The implementation reads the result ref BEFORE asking whether the
    // worker is still alive, and that ordering is the whole guard: drop it and a worker that
    // finished inside the deadline reports SUCCESS with a null value, which reaches a caller as
    // empty output from a device that actually answered.
    val result = attempt(timeoutMs = 30_000L) {
      Thread.sleep(25)
      "answered"
    }
    assertEquals(AndroidHostAdbUtils.ShellAttemptOutcome.SUCCESS, result.outcome)
    assertEquals("answered", result.value)
  }

}
