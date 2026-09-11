package xyz.block.trailblaze.android.test

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.system.measureTimeMillis
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Settling on a screen whose window does not hold window focus.
 *
 * That is a real state of a real app, and the settle after a tap is where the driver meets it: the
 * tap opens a modal in a window of its own, and for the handover the outgoing window has already
 * lost focus while the incoming one has not yet gained it. Espresso's `onView` — with `isRoot()` as
 * much as with any other matcher — picks ONE window against `RootMatchers.DEFAULT` and then waits a
 * hardcoded 10s for it to become ready, never re-picking; and because `DEFAULT` requires
 * `isFocusable()`, "ready" means exactly "this window holds window focus". So a settle routed
 * through `onView` is a bet that one particular window holds focus, and it loses that bet on every
 * transition it does not win the race with. A real trail lost it on the tap that opens an
 * item-detail sheet, 91s into a run, on a tap that had already landed.
 *
 * The unfocused window here is produced by expanding the notification shade rather than by racing a
 * modal, because the shade holds focus for as long as the test wants it to and the racing version
 * reproduces roughly one run in two. It is the same condition and a strictly harsher one: the shade
 * belongs to another process, so the app's own window is the only root Espresso can see, and it
 * stays unfocused. The Activity is not paused by the shade, so Espresso still finds a resumed
 * Activity and still picks that window — the pick succeeds and the readiness wait is what expires.
 */
class SettleWithoutWindowFocusOnDeviceTest {

  @get:Rule val composeRule = createEmptyComposeRule() as AndroidComposeTestRule<*, *>

  private lateinit var scenario: ActivityScenario<MixedUiFixtureActivity>
  private lateinit var fixture: MixedUiFixtureActivity
  private lateinit var target: RuleBackedAndroidTestTarget

  @Before
  fun launchFixture() {
    scenario = ActivityScenario.launch(MixedUiFixtureActivity::class.java)
    var activity: MixedUiFixtureActivity? = null
    scenario.onActivity { activity = it }
    fixture = checkNotNull(activity)
    composeRule.waitForIdle()
    target =
      RuleBackedAndroidTestTarget(activityProvider = { fixture }, composeTestRule = composeRule)
  }

  @After
  fun collapseShadeAndRelease() {
    try {
      // Collapsed before the scenario closes, and waited on: a shade left up outlives this test and
      // takes focus off whatever the next one launches. Asserted rather than best-effort, because a
      // shade that fails to collapse does not fail here — it fails whatever runs next, as a focus
      // flake with nothing pointing back at this test.
      //
      // Skipped when `fixture` was never assigned, which also means the `@Test` never ran and so
      // never expanded the shade.
      if (::fixture.isInitialized) {
        shell("cmd statusbar collapse")
        assertTrue(
          awaitFixtureWindowFocus(focused = true),
          "the notification shade did not collapse, so it still holds window focus and every " +
            "on-device test after this one settles against an unfocused window",
        )
      }
    } finally {
      // Unconditional, so a failed collapse still releases the Activity, and guarded because a
      // `@Before` that threw before assigning these leaves the real launch failure as the signal
      // worth keeping — not an `UninitializedPropertyAccessException` raised from teardown.
      if (::scenario.isInitialized) scenario.close()
    }
  }

  @Test
  fun theSettleCompletesWhileAnotherProcessHoldsWindowFocus() {
    shell("cmd statusbar expand-notifications")
    assertTrue(
      awaitFixtureWindowFocus(focused = false),
      "the shade never took window focus off the app's window, so this proves nothing",
    )

    val elapsedMs = measureTimeMillis { target.waitForIdle() }

    // The failing implementation does not reach this assertion — it throws
    // `RootViewWithoutFocusException` out of `waitForIdle`. The bound is here for the case where
    // that expiry stops being fatal but is still paid: a settle that costs 10s per tap is the same
    // defect wearing a different failure mode.
    assertTrue(
      elapsedMs < ROOT_READY_TIMEOUT_MS,
      "the settle took ${elapsedMs}ms, so it waited out Espresso's ${ROOT_READY_TIMEOUT_MS}ms " +
        "root-readiness timeout on a window that will not be focused again until the shade closes",
    )
  }

  /** Whether the fixture's window reaches [focused] window focus before [FOCUS_HANDOVER_MS]. */
  private fun awaitFixtureWindowFocus(focused: Boolean): Boolean {
    val deadline = SystemClock.uptimeMillis() + FOCUS_HANDOVER_MS
    while (SystemClock.uptimeMillis() < deadline) {
      if (onMainThread { fixture.window.decorView.hasWindowFocus() } == focused) return true
      Thread.sleep(POLL_MS)
    }
    return false
  }

  /**
   * Drained and closed rather than fire-and-forget: `executeShellCommand` hands back a live pipe,
   * and leaving it unread leaks the descriptor for the rest of the instrumentation run.
   */
  private fun shell(command: String) {
    val fd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
    ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() }
  }

  private companion object {
    /** `RootViewPicker.waitForRootToBeReady`'s budget. Hardcoded there, so hardcoded here. */
    const val ROOT_READY_TIMEOUT_MS = 10_000L
    const val FOCUS_HANDOVER_MS = 10_000L
    const val POLL_MS = 50L
  }
}
