package xyz.block.trailblaze.android.test

import android.content.Context
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import xyz.block.trailblaze.android.test.tools.AndroidViewActions

/**
 * A tap sent while its target is still sliding into place.
 *
 * The slide here is the shape a bottom sheet's drag helper produces: driven frame by frame from
 * `postOnAnimation`, invisible to Espresso's idle check (so the driver sees an idle screen
 * throughout), and decelerating in sub-pixel steps at the end, so it sits on the same pixel for
 * consecutive frames before it is done. Its container catches every touch while it
 * moves, as a settling drag helper does. The button only hears a tap that arrives after the slide
 * has finished.
 */
class TapOnSlidingElementOnDeviceTest {

  @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun aTapWaitsForItsTargetToStopSliding() {
    val activity = composeRule.activity
    val slidingSheet = onMainThread { SlidingSheet(activity).also { activity.setContentView(it) } }
    composeRule.waitForIdle()
    var taps = 0
    // A clickable container whose label sits on a child, so the tap goes as a touch rather than
    // as the click action — the route a touch-swallowing container can defeat.
    slidingSheet.button.setOnClickListener { taps++ }
    val target = RuleBackedAndroidTestTarget(
      activityProvider = { activity },
      composeTestRule = composeRule,
    )

    onMainThread { slidingSheet.startSlide() }
    runBlocking { AndroidViewActions.click(target, slidingSheet.button) }
    composeRule.waitForIdle()

    assertEquals(1, taps, "the tap was sent while the sheet still caught touches, so the button never heard it")
  }

  private class SlidingSheet(context: Context) : FrameLayout(context) {
    val button = FrameLayout(context).apply {
      isClickable = true
      addView(TextView(context).apply { text = "Charge" })
    }
    private var remainingPx = 0f

    init {
      addView(button, LayoutParams(600, 200))
    }

    fun startSlide() {
      remainingPx = SLIDE_PX
      step()
    }

    // A fifth of what is left each frame, never less than a third of a pixel. Drawn at whole
    // pixels, as layout positions are.
    private fun step() {
      remainingPx = (remainingPx - maxOf(MIN_STEP_PX, remainingPx / 5)).coerceAtLeast(0f)
      button.translationY = kotlin.math.ceil(remainingPx)
      if (remainingPx > 0f) postOnAnimation(::step)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = remainingPx > 0f

    override fun onTouchEvent(event: MotionEvent): Boolean = remainingPx > 0f || super.onTouchEvent(event)

    private companion object {
      const val SLIDE_PX = 400f
      const val MIN_STEP_PX = 0.34f
    }
  }
}
