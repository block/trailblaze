package xyz.block.trailblaze.android.test

import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import xyz.block.trailblaze.android.test.hierarchy.AndroidHybridHierarchyCollector
import xyz.block.trailblaze.api.DriverNodeDetail
import xyz.block.trailblaze.api.TrailblazeNode

/**
 * The class name this driver publishes for a Compose node, against a real semantics tree.
 *
 * The point of these is the NULLS. A canonical `classNameRegex` selector is answered from this
 * field, and the recorder writes `android.view.View` with an `index` for a row it can name no other
 * way — so every node this driver claims a class for that the accessibility tree would not have
 * shown shifts that index onto the wrong element. The unmerged tree carries one node per modifier,
 * which is where the extras come from.
 */
class AndroidComposeClassProjectionOnDeviceTest {

  @get:Rule val composeRule = createEmptyComposeRule() as AndroidComposeTestRule<*, *>

  private lateinit var scenario: ActivityScenario<MixedUiFixtureActivity>
  private lateinit var nodes: List<TrailblazeNode>

  @Before
  fun captureFixtureHierarchy() {
    scenario = ActivityScenario.launch(MixedUiFixtureActivity::class.java)
    var activity: MixedUiFixtureActivity? = null
    scenario.onActivity { activity = it }
    val fixture = checkNotNull(activity)
    composeRule.waitForIdle()
    val target = RuleBackedAndroidTestTarget(
      activityProvider = { fixture },
      composeTestRule = composeRule,
    )
    nodes = AndroidHybridHierarchyCollector.collect(fixture, target).trailblazeTree.aggregate()
  }

  @After
  fun closeFixture() {
    scenario.close()
  }

  @Test
  fun aLayoutNodeCarryingOnlyATestTagIsPublishedWithNoClass() {
    // A zero-sized tagged Box: a test tag is not published to the accessibility tree unless the app
    // opts into `testTagsAsResourceId`, so this node is one the recorder could never have seen.
    val box = composeNodeWithTag(MixedUiFixtureActivity.COMPOSE_NEVER_PLACED_TAG)
    assertNull(
      box.accessibilityClassName,
      "a tag-only node is invisible to the accessibility tree, so it must claim no class",
    )
  }

  @Test
  fun aClasslessNodeStaysInTheTreeAndKeepsItsTestTag() {
    // Withdrawing the class must not withdraw the node: a `composeTestTagRegex` selector is
    // answered from the Compose dialect and has to keep resolving against exactly these nodes.
    val box = composeNodeWithTag(MixedUiFixtureActivity.COMPOSE_LATE_TAG)
    assertNull(box.accessibilityClassName)
    assertEquals(MixedUiFixtureActivity.COMPOSE_LATE_TAG, box.testTag)
  }

  @Test
  fun textAndTextFieldsAndActionableNodesKeepTheirClass() {
    val text = composeNodeWithTag(MixedUiFixtureActivity.COMPOSE_STATUS_TAG)
    assertEquals("android.widget.TextView", text.accessibilityClassName)

    val field = composeNodeWithTag(MixedUiFixtureActivity.COMPOSE_INPUT_TAG)
    assertEquals("android.widget.EditText", field.accessibilityClassName)

    // A button is a container here — its label is a child node — so the role does not apply and it
    // publishes the plain view class, exactly as the accessibility tree shows a Market row.
    val button = composeNodeWithTag(MixedUiFixtureActivity.COMPOSE_BUTTON_TAG)
    assertEquals("android.view.View", button.accessibilityClassName)
  }

  @Test
  fun theTreeCarriesFewerPlainViewsThanItHasComposeNodes() {
    // The size of the gap is the whole defect: an `index` counts these, so a driver that names a
    // class for every semantics node counts layout that the recording device never published.
    val compose = nodes.mapNotNull { it.driverDetail as? DriverNodeDetail.Compose }
    val plain = compose.count { it.accessibilityClassName == "android.view.View" }
    val classless = compose.count { it.accessibilityClassName == null }
    assertTrue(
      classless > 0,
      "this fixture has semantics-less layout nodes; none were withheld, so the projection is off",
    )
    assertTrue(
      plain < compose.size - classless + 1,
      "plain-view count ($plain) should exclude the $classless withheld nodes",
    )
  }

  private fun composeNodeWithTag(tag: String): DriverNodeDetail.Compose {
    val detail =
      nodes
        .mapNotNull { it.driverDetail as? DriverNodeDetail.Compose }
        .firstOrNull { it.testTag == tag }
    return assertNotNull(detail, "no Compose node tagged '$tag' in the captured hierarchy")
  }
}
