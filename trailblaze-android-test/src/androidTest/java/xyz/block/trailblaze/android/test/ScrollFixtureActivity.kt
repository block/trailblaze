package xyz.block.trailblaze.android.test

import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier

/**
 * A screen whose content is taller than the screen, for the driver's scroll-until-visible tests.
 *
 * The scrolling half is deliberately plain Views in a [ScrollView]: the thing under test is the
 * scroll loop's stop condition, and a lazy Compose list would additionally exercise the loop's
 * own-ancestor branch — a different contract, tested where that branch is what matters.
 *
 * The Compose footer is not part of that subject. It is there because this driver captures a
 * HYBRID hierarchy and refuses to read a screen with no Compose root at all, so a View-only
 * fixture cannot be looked at. Parked outside the [ScrollView] so scrolling never takes it away.
 *
 * The row counts carry the two situations the loop has to tell apart. [ROWS_ABOVE_TARGET] puts
 * [TARGET_LABEL] well below the fold on the tallest device the suite runs on, so finding it takes
 * several scrolls rather than one. [ROWS_BELOW_TARGET] then keeps the list scrolling past every
 * bound a test sets — both the recorded timeout and the loop's own scroll cap — so a search for
 * something absent ends at a BOUND rather than at the end of the list, which the loop reports as a
 * different thing entirely.
 */
class ScrollFixtureActivity : ComponentActivity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    column.addView(TextView(this).apply { text = TOP_LABEL; textSize = 28f })
    repeat(ROWS_ABOVE_TARGET) { row ->
      column.addView(TextView(this).apply { text = "Filler row $row"; textSize = 28f })
    }
    column.addView(TextView(this).apply { text = TARGET_LABEL; textSize = 28f })
    repeat(ROWS_BELOW_TARGET) { row ->
      column.addView(TextView(this).apply { text = "Tail row $row"; textSize = 28f })
    }

    val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    root.addView(
      ScrollView(this).apply { addView(column) },
      LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
    )
    root.addView(
      ComposeView(this).apply {
        setContent { Text(text = FOOTER_LABEL, modifier = Modifier.testTag(FOOTER_TAG)) }
      },
      LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
      ),
    )
    setContentView(root)
  }

  companion object {
    const val TOP_LABEL = "Top of the list"
    const val TARGET_LABEL = "Target row"
    const val FOOTER_LABEL = "Footer"
    const val FOOTER_TAG = "scroll_fixture_footer"
    const val ROWS_ABOVE_TARGET = 60
    const val ROWS_BELOW_TARGET = 500
  }
}
