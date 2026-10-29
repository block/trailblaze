package xyz.block.trailblaze.android.test

import android.app.Activity
import android.graphics.Bitmap
import android.view.View
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.test.espresso.ViewAction
import androidx.test.espresso.ViewAssertion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking
import org.hamcrest.Matcher

/**
 * [AndroidTestTarget.dispatchAndAwaitSettle]: an idle timeout after the action landed does not
 * fail the dispatch, and anything else the settle throws still does.
 *
 * An action that landed has already changed the app, so reporting a settle timeout as a failure
 * would drop the step from the recording. An app exception Espresso rethrows from the settle is the
 * app breaking, and must not let the run pass.
 */
class AndroidTestTargetDispatchTest {

  private class SettleFailingTarget(private val settleFailure: Throwable) : AndroidTestTarget {
    val events = mutableListOf<String>()

    override fun waitForIdle(ceilingMs: Long?) {
      events.add("settle")
      throw settleFailure
    }

    override val composeTestRule: AndroidComposeTestRule<*, *>? = null
    override fun currentActivity(): Activity = error("not used")
    override fun performViewAction(matcher: Matcher<View>, action: ViewAction) = error("not used")
    override fun checkView(matcher: Matcher<View>, assertion: ViewAssertion) = error("not used")
    override fun composeRoots(): List<SemanticsNode> = error("not used")
    override fun captureScreenshot(): Bitmap? = error("not used")
  }

  // The message form of Compose's idle timeout on Android. Espresso's own timeout types cannot be
  // constructed off-device: their constructors reach Android platform code.
  private fun idleTimeout() =
    IllegalStateException("ComposeIdlingResource is busy due to pending recompositions")

  @Test
  fun `an action that landed succeeds when the settle after it times out`() {
    val target = SettleFailingTarget(idleTimeout())

    val result = runBlocking {
      target.dispatchAndAwaitSettle {
        target.events.add("tap")
        "tapped"
      }
    }

    assertEquals("tapped", result)
    assertEquals(listOf("tap", "settle"), target.events)
  }

  @Test
  fun `an app exception the settle rethrows still fails the dispatch`() {
    val target = SettleFailingTarget(RuntimeException("app crashed on the main thread"))

    val thrown = assertFailsWith<RuntimeException> {
      runBlocking { target.dispatchAndAwaitSettle { "tapped" } }
    }

    assertEquals("app crashed on the main thread", thrown.message)
  }

  @Test
  fun `an action that throws still fails, with the settle failure suppressed`() {
    val target = SettleFailingTarget(idleTimeout())

    val thrown = assertFailsWith<IllegalStateException> {
      runBlocking { target.dispatchAndAwaitSettle<Unit> { error("no view matched") } }
    }

    assertEquals("no view matched", thrown.message)
    assertEquals(
      listOf("ComposeIdlingResource is busy due to pending recompositions"),
      thrown.suppressed.map { it.message },
    )
    assertEquals(listOf("settle"), target.events, "the settle still runs on the throw path")
  }
}
