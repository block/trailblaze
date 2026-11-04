package xyz.block.trailblaze.android.test

import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import xyz.block.trailblaze.android.test.hierarchy.AndroidHybridHierarchyCollector
import xyz.block.trailblaze.api.DriverNodeDetail

/**
 * A row scrolled to a sliver at the edge of its list, with its label clipped out of view.
 *
 * The accessibility tree publishes the row as one node with its label merged in, because some of
 * the row is still on screen, so a recording made there names the row by that label. This driver
 * holds the label on its own node, which is clipped to nothing. It has to stay in the tree for the
 * recorded selector to find the row.
 */
class ClippedRowLabelOnDeviceTest {

  @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun aRowOnScreenKeepsItsClippedLabelAndAClippedLooseTextIsDropped() {
    composeRule.setContent {
      Box(Modifier.height(100.dp).clipToBounds()) {
        // Laid out at full height and cut off by the Box, as a list is by its viewport.
        Column(Modifier.wrapContentHeight(Alignment.Top, unbounded = true)) {
          Spacer(Modifier.height(95.dp))
          // 5dp of this row is on screen; its label starts 30dp down, so none of the label is.
          Box(Modifier.height(60.dp).clickable {}) {
            Text(ROW_LABEL, Modifier.padding(top = 30.dp))
          }
          // Wholly below the clip and part of no on-screen node: nothing publishes it.
          Text(LOOSE_TEXT)
        }
      }
    }
    composeRule.waitForIdle()

    val texts = captureComposeTexts()

    assertTrue(ROW_LABEL in texts, "the sliver row's label was dropped, so no selector can name the row")
    assertFalse(LOOSE_TEXT in texts, "a clipped text that belongs to no on-screen row was kept")
  }

  private fun captureComposeTexts(): List<String> {
    val host = composeRule.activity
    val target = RuleBackedAndroidTestTarget(activityProvider = { host }, composeTestRule = composeRule)
    return AndroidHybridHierarchyCollector.collect(host, target).trailblazeTree.aggregate()
      .mapNotNull { (it.driverDetail as? DriverNodeDetail.Compose)?.text }
  }

  private companion object {
    const val ROW_LABEL = "Sliver row"
    const val LOOSE_TEXT = "Loose text"
  }
}
