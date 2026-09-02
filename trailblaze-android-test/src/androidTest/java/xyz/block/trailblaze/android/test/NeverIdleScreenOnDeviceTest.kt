package xyz.block.trailblaze.android.test

import android.os.Looper
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.IdlingPolicies
import androidx.test.espresso.IdlingRegistry
import androidx.test.espresso.IdlingResource
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import xyz.block.trailblaze.android.test.hierarchy.AndroidHybridHierarchyCollector

/**
 * Reading a screen the app never reports as idle.
 *
 * That is a real state of a real app: the signed-out Square landing runs a looping photo carousel
 * that keeps something busy for as long as it is up, so a read that synchronizes first can only
 * expire there. An unanswered read is not a degraded read — it is no tree at all, and every caller
 * on this driver polls the tree — which is why [RuleBackedAndroidTestTarget.composeRoots] falls
 * back to an unsynchronized read, and why that fallback is worth a test of its own. Cases 5380698,
 * 5380821 and 5380822 each lost a whole sign-in inside it.
 *
 * Held busy through a never-idle [IdlingResource] rather than an animation. That is the mechanism
 * either way — an animating screen keeps the app busy through the same registry — and it is the
 * half that decides whether the read expires, so pinning it directly makes the test deterministic
 * instead of dependent on whether a given Compose version's clock counts as busy.
 */
class NeverIdleScreenOnDeviceTest {

  @get:Rule val composeRule = createEmptyComposeRule() as AndroidComposeTestRule<*, *>

  private lateinit var scenario: ActivityScenario<MixedUiFixtureActivity>
  private lateinit var fixture: MixedUiFixtureActivity
  private lateinit var target: RuleBackedAndroidTestTarget

  /** Every thread [target] asked for the Activity on, so an assertion can name the offender. */
  private val activityLookupThreads = mutableListOf<Thread>()

  private val neverIdle =
    object : IdlingResource {
      override fun getName() = "never-idle"

      override fun isIdleNow() = false

      override fun registerIdleTransitionCallback(callback: IdlingResource.ResourceCallback?) = Unit
    }

  @Before
  fun launchFixtureAndHoldItBusy() {
    scenario = ActivityScenario.launch(MixedUiFixtureActivity::class.java)
    var activity: MixedUiFixtureActivity? = null
    scenario.onActivity { activity = it }
    fixture = checkNotNull(activity)
    composeRule.waitForIdle()
    target =
      RuleBackedAndroidTestTarget(
        activityProvider = {
          activityLookupThreads += Thread.currentThread()
          fixture
        },
        composeTestRule = composeRule,
      )
    SemanticsReadProbe.reset()
    // Shortened from the default minute so the fallback is reached in seconds. The screen is no
    // less busy for the wait being shorter, so this changes how long the test takes and nothing
    // about what it proves. Registered AFTER the launch, which needs a settled app.
    IdlingPolicies.setMasterPolicyTimeout(2, TimeUnit.SECONDS)
    IdlingPolicies.setIdlingResourceTimeout(2, TimeUnit.SECONDS)
    IdlingRegistry.getInstance().register(neverIdle)
  }

  @After
  fun releaseFixture() {
    IdlingRegistry.getInstance().unregister(neverIdle)
    IdlingPolicies.setMasterPolicyTimeout(60, TimeUnit.SECONDS)
    IdlingPolicies.setIdlingResourceTimeout(26, TimeUnit.SECONDS)
    scenario.close()
  }

  @Test
  fun theScreenIsStillReadableWhileItNeverSettles() {
    val texts =
      target.composeRoots()
        .flatMap { it.selfAndDescendants() }
        .mapNotNull { it.config.getOrNull(SemanticsProperties.Text) }
        .flatten()
        .map { it.text }
    assertTrue(
      MixedUiFixtureActivity.COMPOSE_BUTTON_LABEL in texts,
      "the fixture's Compose content is on screen throughout; a never-idle screen must still read " +
        "as itself, got $texts",
    )
  }

  @Test
  fun theActivityIsNeverResolvedFromTheMainThread() {
    target.composeRoots()
    assertTrue(activityLookupThreads.isNotEmpty(), "the fallback never ran, so this proves nothing")
    // A host's activityProvider is free to reach for the resumed Activity through `runOnMainSync`,
    // which refuses to run on the main thread — Square's does, and polls with a sleep besides. So
    // resolving the Activity inside a main-thread block throws or hangs depending on the host, and
    // does so on the one kind of screen this path exists for.
    assertFalse(
      activityLookupThreads.any { it === Looper.getMainLooper().thread },
      "the Activity was resolved on the main thread",
    )
  }

  /**
   * The unsynchronized fallback is unsynchronized with respect to IDLE, not to the UI thread.
   *
   * Dropping the idle wait is what makes a never-idle screen readable at all; reading the tree off
   * the thread that owns it is a different thing entirely, and one this screen makes more likely
   * rather than less — whatever keeps the app busy is composition and layout still running while
   * the read happens. So the fallback resolves its roots off the UI thread, as it must, and the
   * walk that turns them into a hierarchy still happens on it.
   */
  @Test
  fun theTreeIsStillReadOnTheUiThreadWhenTheScreenNeverSettles() {
    // Captured and rethrown, not just joined: a throw inside the thread would otherwise vanish,
    // and the probe assertion below would blame the fixture for a crash in `collect`.
    val failure = AtomicReference<Throwable?>(null)
    thread {
      runCatching { AndroidHybridHierarchyCollector.collect(fixture, target) }
        .onFailure { failure.set(it) }
    }.join()
    failure.get()?.let { throw AssertionError("The concurrent capture failed", it) }
    SemanticsReadProbe.assertReadOnlyOnUiThread()
  }

  /** This node and every descendant, so a test can talk about the whole tree it got back. */
  private fun SemanticsNode.selfAndDescendants(): List<SemanticsNode> =
    listOf(this) + children.flatMap { it.selfAndDescendants() }
}
