package xyz.block.trailblaze.android.test

import android.view.Gravity
import android.widget.PopupWindow
import android.widget.TextView
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import xyz.block.trailblaze.android.test.tools.AndroidViewActions

/**
 * A View in a popup that does not take focus, such as a search field's suggestion menu, can be
 * asserted on and tapped.
 *
 * The capture includes such a window (focus stays on the window under it), so a selector resolves
 * to the popup's View. Espresso's default root matcher picks the focused window only, so without
 * routing through the View's own window every check and tap there fails as "no matching view".
 */
class NonFocusablePopupOnDeviceTest {

  @get:Rule val composeRule = createEmptyComposeRule() as AndroidComposeTestRule<*, *>

  private lateinit var scenario: ActivityScenario<MixedUiFixtureActivity>
  private lateinit var fixture: MixedUiFixtureActivity
  private lateinit var target: RuleBackedAndroidTestTarget
  private var popup: PopupWindow? = null

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
  fun release() {
    if (::scenario.isInitialized) {
      scenario.onActivity { popup?.dismiss() }
      scenario.close()
    }
  }

  @Test
  fun aViewInANonFocusablePopupCanBeCheckedAndTapped() {
    var clicks = 0
    lateinit var label: TextView
    scenario.onActivity { activity ->
      label = TextView(activity).apply {
        text = "Popup label"
        setPadding(48, 48, 48, 48)
        setOnClickListener { clicks++ }
      }
      popup = PopupWindow(label, 600, 300, /* focusable = */ false).apply {
        showAtLocation(activity.window.decorView, Gravity.CENTER, 0, 0)
      }
    }
    composeRule.waitForIdle()

    AndroidViewActions.assertDisplayed(target, label)
    runBlocking { AndroidViewActions.click(target, label) }
    composeRule.waitForIdle()

    assertEquals(1, clicks, "the tap on the popup's label did not reach it")
  }
}
