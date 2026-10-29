package xyz.block.trailblaze.compose.target

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.ComposeTimeoutException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.Test

/**
 * Contract tests for the default [ComposeTestTarget.dispatchAndAwaitSettle] method — the
 * [xyz.block.trailblaze.api.DriverDispatch] implementation on the Compose Desktop path.
 *
 * Exercises:
 * - happy path: action runs first, `waitForIdle` runs after, result propagates;
 * - exception path: `waitForIdle` still runs when the action throws (the cross-driver
 *   contract — see [xyz.block.trailblaze.api.DriverDispatch] kdoc);
 * - settle timeout: a `ComposeTimeoutException` after a successful action returns the action's
 *   result, while any other settle failure (an app exception the settle rethrows) still fails.
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
  fun `dispatchAndAwaitSettle returns the action's result when the settle after it times out`() {
    val target = object : StubComposeTestTarget() {
      override fun waitForIdle() {
        events.add("settle")
        throw ComposeTimeoutException("UI never went idle")
      }
    }

    // The action has already changed the app; a settle timeout afterwards must not report it
    // as failed, or the recording drops a step that really happened.
    val result = runBlocking {
      target.dispatchAndAwaitSettle {
        target.events.add("action")
        "clicked"
      }
    }

    assertEquals("clicked", result)
    assertEquals(listOf("action", "settle"), target.events)
  }

  @Test
  fun `dispatchAndAwaitSettle - action exception wins when both throw`() {
    val target = object : StubComposeTestTarget() {
      override fun waitForIdle() {
        throw IllegalStateException("settle-failed")
      }
    }

    val thrown = assertFailsWith<IllegalStateException> {
      runBlocking {
        target.dispatchAndAwaitSettle<Unit> { error("action-failed") }
      }
    }
    assertEquals("action-failed", thrown.message, "the action's failure is the visible one")
    assertEquals(
      listOf("settle-failed"),
      thrown.suppressed.map { it.message },
      "the settle's failure rides along as suppressed",
    )
  }

  @Test
  fun `dispatchAndAwaitSettle propagates cancellation from the settle`() {
    val target = object : StubComposeTestTarget() {
      override fun waitForIdle() {
        throw CancellationException("cancelled")
      }
    }

    assertFailsWith<CancellationException> {
      runBlocking { target.dispatchAndAwaitSettle { "ok" } }
    }
  }

  @Test
  fun `dispatchAndAwaitSettle - an app exception the settle rethrows still fails`() {
    // waitForIdle rethrows exceptions the app raised on the UI thread. That is the app breaking,
    // not a timeout, and swallowing it would let a run pass with a crashed app.
    val target = object : StubComposeTestTarget() {
      override fun waitForIdle() {
        throw AssertionError("app assertion after the click")
      }
    }

    val thrown = assertFailsWith<AssertionError> {
      runBlocking { target.dispatchAndAwaitSettle { "clicked" } }
    }
    assertEquals("app assertion after the click", thrown.message)
  }

  @Test
  fun `dispatchAndAwaitSettle - action exception wins over a settle Error`() {
    val target = object : StubComposeTestTarget() {
      override fun waitForIdle() {
        throw AssertionError("settle-assertion")
      }
    }

    val thrown = assertFailsWith<IllegalStateException> {
      runBlocking { target.dispatchAndAwaitSettle<Unit> { error("action-failed") } }
    }
    assertEquals("action-failed", thrown.message)
    assertEquals(listOf("settle-assertion"), thrown.suppressed.map { it.message })
  }

  @Test
  fun `dispatchAndAwaitSettle - settle cancellation wins over an action failure`() {
    val target = object : StubComposeTestTarget() {
      override fun waitForIdle() {
        throw CancellationException("cancelled")
      }
    }

    val thrown = assertFailsWith<CancellationException> {
      runBlocking { target.dispatchAndAwaitSettle<Unit> { error("action-failed") } }
    }
    assertEquals(listOf("action-failed"), thrown.suppressed.map { it.message })
  }
}
