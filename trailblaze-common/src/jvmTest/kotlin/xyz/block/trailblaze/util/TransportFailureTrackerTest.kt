package xyz.block.trailblaze.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import xyz.block.trailblaze.exception.TrailblazeException

/**
 * A polling loop treats every throwing attempt as "not yet met". For a device that answered "no"
 * that is correct. For one that never answered it hands the caller a confident `false` — and the
 * launch tool acts on it by re-launching the app on a wedged device.
 */
class TransportFailureTrackerTest {

  private val wedged = TrailblazeException("adb shell did not return within 330000ms")

  @Test
  fun `a poll that only ever saw a wedged transport reports the wedge, not a verdict`() {
    // The finding this class exists for: without it the caller receives `false` and believes it.
    val tracker = TransportFailureTracker()
    repeat(3) {
      assertFailsWith<TrailblazeException> { tracker.record<String> { throw wedged } }
    }
    val thrown = assertFailsWith<TrailblazeException> { tracker.rethrowIfUnresolved(succeeded = false) }
    assertSame(wedged, thrown, "the original failure must surface, not a summary of it")
  }

  @Test
  fun `a device that recovered mid-poll gets its verdict trusted again`() {
    // The transport failed, then answered. `false` now means the app really is not in the
    // foreground, and throwing a stale failure over it would fail a trail for a resolved problem.
    val tracker = TransportFailureTracker()
    assertFailsWith<TrailblazeException> { tracker.record<String> { throw wedged } }
    assertEquals("mResumedActivity: com.example/.Main", tracker.record { "mResumedActivity: com.example/.Main" })
    tracker.rethrowIfUnresolved(succeeded = false)
  }

  @Test
  fun `a poll that succeeded is never overridden by an earlier failure`() {
    // One attempt wedged, a later one found the app. That is a success and must be reported as one.
    val tracker = TransportFailureTracker()
    assertFailsWith<TrailblazeException> { tracker.record<String> { throw wedged } }
    tracker.rethrowIfUnresolved(succeeded = true)
  }

  @Test
  fun `a clean poll that simply never met its condition still returns false`() {
    // The ordinary timeout. Nothing was wrong with the transport, so nothing may be thrown —
    // otherwise every "app did not come to the foreground" becomes a spurious transport error.
    val tracker = TransportFailureTracker()
    assertEquals("nothing resumed", tracker.record { "nothing resumed" })
    tracker.rethrowIfUnresolved(succeeded = false)
  }

  @Test
  fun `the failure still reaches the polling loop, so the loop keeps its own retry cadence`() {
    // `record` observes; it must not swallow. Swallowing would make a wedged read look like a
    // successful read of an empty screen, which is the bug one layer further down.
    val tracker = TransportFailureTracker()
    assertFailsWith<TrailblazeException> { tracker.record<String> { throw wedged } }
  }

  @Test
  fun `a parse error is not a transport failure and does not become one`() {
    // Only TrailblazeException means "the transport did not answer". An IllegalStateException from
    // parsing is the condition failing, which the poll is entitled to treat as "not yet".
    val tracker = TransportFailureTracker()
    assertFailsWith<IllegalStateException> { tracker.record<String> { error("bad dumpsys output") } }
    tracker.rethrowIfUnresolved(succeeded = false)
  }
}
