package xyz.block.trailblaze.android.test

import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import xyz.block.trailblaze.android.test.hierarchy.AndroidHybridHierarchyCollector
import xyz.block.trailblaze.api.DriverNodeMatch
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.api.TrailblazeNodeSelectorResolver

/**
 * An icon button whose icon has an empty description, named the two
 * ways its recordings name it.
 *
 * The accessibility tree shows such a button as a plain view holding its icon, followed by a
 * synthetic child that carries the button role. One recording names the search as the second view
 * whose child has an empty description; another names it as the second `android.widget.Button`.
 * Both have to land on it here.
 */
class IconButtonAccessibilityShapeOnDeviceTest {

  @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun anIconButtonIsAViewHoldingItsIconAndARoleCarryingButton() {
    composeRule.setContent {
      Row {
        // The overflow, then search: two icon buttons alike but for where they sit.
        IconButton()
        IconButton()
      }
    }
    composeRule.waitForIdle()
    val tree = capture()

    val viewHoldingUnlabelledIcon = TrailblazeNodeSelector(
      androidAccessibility = DriverNodeMatch.AndroidAccessibility(classNameRegex = "android\\.view\\.View"),
      containsChild = TrailblazeNodeSelector(
        androidAccessibility = DriverNodeMatch.AndroidAccessibility(contentDescriptionRegex = ""),
      ),
      index = 1,
    )
    val secondButton = TrailblazeNodeSelector(
      androidAccessibility = DriverNodeMatch.AndroidAccessibility(classNameRegex = "android\\.widget\\.Button"),
      index = 1,
    )

    assertEquals(SEARCH_LEFT_PX, leftOf(tree, viewHoldingUnlabelledIcon), "the view-shaped recording missed search")
    assertEquals(SEARCH_LEFT_PX, leftOf(tree, secondButton), "the button-shaped recording missed search")
  }

  @androidx.compose.runtime.Composable
  private fun IconButton() {
    Box(Modifier.size(BUTTON_DP.dp).clickable(role = Role.Button) {}) {
      Icon(ColorPainter(Color.Black), contentDescription = "")
    }
  }

  private fun capture(): TrailblazeNode {
    val host = composeRule.activity
    val target = RuleBackedAndroidTestTarget(activityProvider = { host }, composeTestRule = composeRule)
    return AndroidHybridHierarchyCollector.collect(host, target).trailblazeTree
  }

  private fun leftOf(tree: TrailblazeNode, selector: TrailblazeNodeSelector): Int? =
    when (val result = TrailblazeNodeSelectorResolver.resolve(tree, selector)) {
      is TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch -> result.node.bounds?.left
      else -> null
    }

  private val SEARCH_LEFT_PX: Int
    get() = (BUTTON_DP * composeRule.activity.resources.displayMetrics.density).toInt()

  private companion object {
    const val BUTTON_DP = 48
  }
}
