package xyz.block.trailblaze.device

import kotlinx.coroutines.CancellationException
import org.junit.Test
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Pins the swallow-and-log contract for [AndroidDeviceCommandExecutor.grantRuntimePermission]
 * via its shared helper [handleGrantRuntimePermissionOutcome].
 *
 * Launch tools commonly grant a conservative superset of runtime permissions in a loop (one
 * permission per `pm grant` call). A single missing-from-manifest entry, transient adb
 * hiccup, or transport exception must NOT abort the loop — that's the contract every caller
 * depends on. If a future refactor lets exceptions or `pm grant` stderr propagate, every
 * cold launch that relies on this loop breaks at once, and this test is the early-warning
 * signal.
 */
class GrantRuntimePermissionTest {

  @Test
  fun `successful pm grant returning empty output does not throw`() {
    // Happy path: `pm grant` exits silently. Helper must return normally.
    handleGrantRuntimePermissionOutcome("com.example.app", "android.permission.CAMERA") {
      ""
    }
  }

  @Test
  fun `successful pm grant returning null does not throw`() {
    // The Android `actual` returns `null` because the underlying transport returns Unit;
    // helper must treat null identically to empty-string.
    handleGrantRuntimePermissionOutcome("com.example.app", "android.permission.CAMERA") {
      null
    }
  }

  @Test
  fun `pm grant returning stderr-like diagnostic is tolerated`() {
    // The JVM transport surfaces `pm grant` stderr as a non-empty return string when the
    // target manifest doesn't declare the permission. Must not throw — callers grant
    // conservative supersets and tolerate per-permission failure.
    handleGrantRuntimePermissionOutcome("com.example.app", "android.permission.NEVER_DECLARED") {
      "Operation not allowed: java.lang.SecurityException: Permission " +
        "android.permission.NEVER_DECLARED is not a changeable permission type"
    }
  }

  @Test
  fun `IOException from transport is swallowed`() {
    // A real transport failure (dadb disconnect, broken pipe). Helper must swallow so the
    // outer loop continues to the next permission. If this ever starts propagating,
    // per-launch-tool permission loops will abort partway and JUnit will fail this test
    // the moment the rethrow lands.
    handleGrantRuntimePermissionOutcome("com.example.app", "android.permission.CAMERA") {
      throw IOException("dadb shell disconnected")
    }
  }

  @Test
  fun `RuntimeException from transport is swallowed`() {
    // Defensive coverage for any other unchecked exception the transport might surface.
    handleGrantRuntimePermissionOutcome("com.example.app", "android.permission.CAMERA") {
      throw RuntimeException("unexpected adb state")
    }
  }

  @Test
  fun `CancellationException propagates instead of being swallowed`() {
    // Structured-concurrency invariant: cancellation must not be silently absorbed by the
    // swallow-and-log contract. The Square cold-launch caller runs inside a suspend
    // context; if the surrounding coroutine cancels, the helper must let it through so
    // the broader cancellation isn't masked.
    assertFailsWith<CancellationException> {
      handleGrantRuntimePermissionOutcome("com.example.app", "android.permission.CAMERA") {
        throw CancellationException("parent coroutine cancelled")
      }
    }
  }

  @Test
  fun `block invoked exactly once per call`() {
    // Guards against an accidental retry/loop being introduced in the helper. Retry, if
    // any, is the caller's responsibility; the helper itself must be single-shot.
    var invocations = 0
    handleGrantRuntimePermissionOutcome("com.example.app", "android.permission.CAMERA") {
      invocations++
      ""
    }
    assertEquals(1, invocations)

    invocations = 0
    handleGrantRuntimePermissionOutcome("com.example.app", "android.permission.CAMERA") {
      invocations++
      throw IOException("boom")
    }
    assertEquals(1, invocations)
  }

  @Test
  fun `whitespace-only output is trimmed away and tolerated`() {
    // Some transports return whitespace-only output on success. Helper trims before
    // checking — a "          " return shouldn't fire a stderr log nor throw.
    handleGrantRuntimePermissionOutcome("com.example.app", "android.permission.CAMERA") {
      "   \n  "
    }
  }
}
