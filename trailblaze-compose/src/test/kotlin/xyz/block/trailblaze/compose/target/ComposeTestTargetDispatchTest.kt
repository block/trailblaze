package xyz.block.trailblaze.compose.target

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.semantics.SemanticsNode
import kotlinx.coroutines.runBlocking
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Contract tests for the default [ComposeTestTarget.dispatchAndAwaitSettle] method — the
 * [xyz.block.trailblaze.api.DriverDispatch] implementation on the Compose Desktop path.
 *
 * Exercises:
 * - happy path: action runs first, `waitForIdle` runs after, result propagates;
 * - exception path: `waitForIdle` still runs when the action throws (the cross-driver
 *   contract — see [xyz.block.trailblaze.api.DriverDispatch] kdoc).
 *
 * Lives in `trailblaze-compose` (not `trailblaze-compose-target`) because the test
 * infrastructure (kotlin-test-junit4 + assertk) is already wired here, keeping the
 * `trailblaze-compose-target` module lean per its design intent.
 */
class ComposeTestTargetDispatchTest {

  /**
   * Minimal stub that only honors the calls the default `dispatchAndAwaitSettle` makes —
   * `waitForIdle()`. Everything else throws so a regression that starts touching other
   * interface methods would surface immediately.
   */
  private open class StubComposeTestTarget : ComposeTestTarget {
    val events = mutableListOf<String>()
    var settleCount: Int = 0

    override fun waitForIdle() {
      events.add("settle")
      settleCount++
    }

    override fun rootSemanticsNode(): SemanticsNode =
      error("rootSemanticsNode should not be invoked from dispatchAndAwaitSettle")
    override fun allSemanticsNodes(): List<SemanticsNode> =
      error("allSemanticsNodes should not be invoked from dispatchAndAwaitSettle")
    override fun click(node: SemanticsNode) =
      error("click should not be invoked from dispatchAndAwaitSettle")
    override fun typeText(node: SemanticsNode, text: String) =
      error("typeText should not be invoked from dispatchAndAwaitSettle")
    override fun clearText(node: SemanticsNode) =
      error("clearText should not be invoked from dispatchAndAwaitSettle")
    override fun scrollToIndex(node: SemanticsNode, index: Int) =
      error("scrollToIndex should not be invoked from dispatchAndAwaitSettle")
    override fun captureScreenshot(): ImageBitmap? =
      error("captureScreenshot should not be invoked from dispatchAndAwaitSettle")
  }

  @Test
  fun `dispatchAndAwaitSettle runs action then waitForIdle and returns the action's result`() {
    val target = StubComposeTestTarget()

    val result = runBlocking {
      target.dispatchAndAwaitSettle {
        target.events.add("action")
        "ok"
      }
    }

    assertEquals("ok", result, "action result must propagate back")
    assertEquals(listOf("action", "settle"), target.events, "settle runs strictly after action")
    assertEquals(1, target.settleCount, "waitForIdle must run exactly once")
  }

  @Test
  fun `dispatchAndAwaitSettle runs waitForIdle even when action throws`() {
    val target = StubComposeTestTarget()

    val thrown = assertFailsWith<IllegalStateException> {
      runBlocking {
        target.dispatchAndAwaitSettle<Unit> {
          target.events.add("action")
          error("boom")
        }
      }
    }

    assertEquals("boom", thrown.message, "original exception propagates unchanged")
    assertEquals(
      listOf("action", "settle"), target.events,
      "settle still runs on throw (DriverDispatch exception contract)",
    )
    assertEquals(1, target.settleCount, "waitForIdle must run exactly once even on throw")
  }

  @Test
  fun `dispatchAndAwaitSettle - finally exception wins when both throw`() {
    val target = object : StubComposeTestTarget() {
      override fun waitForIdle() {
        throw IllegalStateException("settle-failed")
      }
    }

    // When both action and settle throw, Kotlin's try/finally semantics surface the *finally*
    // exception. Pinning this so a future refactor doesn't accidentally invert it without an
    // explicit decision.
    val thrown = assertFailsWith<IllegalStateException> {
      runBlocking {
        target.dispatchAndAwaitSettle<Unit> { error("action-failed") }
      }
    }
    assertTrue(
      thrown.message == "settle-failed",
      "finally-exception is the visible one; got '${thrown.message}'",
    )
  }
}
