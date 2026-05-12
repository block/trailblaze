package xyz.block.trailblaze.android.accessibility

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Contract tests for [AccessibilityDeviceManager.dispatchAndAwaitSettle] — the
 * [xyz.block.trailblaze.api.DriverDispatch] implementation on the Android accessibility path.
 *
 * Exercises:
 * - happy path: action runs first, settle runs after, result propagates;
 * - exception path: settle still runs when action throws (the cross-driver contract — see
 *   [xyz.block.trailblaze.api.DriverDispatch] kdoc);
 * - call ordering: settle is observed only after the action has finished.
 *
 * Uses the `awaitSettle` constructor seam to substitute a counting stub in place of the real
 * `UiDevice.waitForIdle()` so the test does not require an instrumentation context.
 */
class AccessibilityDeviceManagerDispatchTest {

  private class CallLog {
    val events = mutableListOf<String>()
    var settleCount: Int = 0
  }

  private fun managerWith(log: CallLog): AccessibilityDeviceManager =
    AccessibilityDeviceManager(
      deviceClassifiers = emptyList(),
      awaitSettle = {
        log.events.add("settle")
        log.settleCount++
      },
    )

  @Test
  fun `dispatchAndAwaitSettle runs action then settle and returns the action's result`() {
    val log = CallLog()
    val manager = managerWith(log)

    val result = runBlocking {
      manager.dispatchAndAwaitSettle {
        log.events.add("action")
        42
      }
    }

    assertEquals(42, result, "action result must propagate back")
    assertEquals(listOf("action", "settle"), log.events, "settle runs strictly after action")
    assertEquals(1, log.settleCount, "settle must run exactly once")
  }

  @Test
  fun `dispatchAndAwaitSettle runs settle even when action throws`() {
    val log = CallLog()
    val manager = managerWith(log)

    val thrown = assertFailsWith<IllegalStateException> {
      runBlocking {
        manager.dispatchAndAwaitSettle<Unit> {
          log.events.add("action")
          error("boom")
        }
      }
    }

    assertEquals("boom", thrown.message, "original exception propagates unchanged")
    assertEquals(listOf("action", "settle"), log.events, "settle still runs on throw")
    assertEquals(1, log.settleCount, "settle must run exactly once even on throw")
  }

  @Test
  fun `dispatchAndAwaitSettle propagates the original exception when settle also throws`() {
    val manager =
      AccessibilityDeviceManager(
        awaitSettle = { throw IllegalStateException("settle-failed") },
      )

    // When both action and settle throw, Kotlin's try/finally semantics surface the *finally*
    // exception. This pins that behavior so a future refactor doesn't accidentally invert it
    // (e.g., by suppressing the original) without an explicit decision.
    val thrown = assertFailsWith<IllegalStateException> {
      runBlocking {
        manager.dispatchAndAwaitSettle<Unit> { error("action-failed") }
      }
    }
    assertTrue(
      thrown.message == "settle-failed",
      "finally-exception is the visible one (Kotlin try/finally semantics); got '${thrown.message}'",
    )
  }
}
