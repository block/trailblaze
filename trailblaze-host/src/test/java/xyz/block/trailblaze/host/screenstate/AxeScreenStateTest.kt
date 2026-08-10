package xyz.block.trailblaze.host.screenstate

import org.junit.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import xyz.block.trailblaze.api.CompactScreenElements
import xyz.block.trailblaze.api.DriverNodeDetail
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.host.axe.AxeCli
import xyz.block.trailblaze.host.axe.AxeJsonMapper

/**
 * Contract tests for [AxeScreenState]: every tree it exposes is viewport-clamped.
 *
 * `axe describe-ui` spans the whole scroll content, while the host (Maestro/XCUITest)
 * driver's screen state never carries below-fold content (XCTest doesn't materialize it,
 * and Maestro's `filterOutOfBounds` trims edge stragglers). Selector consumers resolve
 * against [AxeScreenState.trailblazeNodeTree] directly (`findMatches`, waypoint matching),
 * the shared scroll loop reads [AxeScreenState.viewHierarchy], and refs come from the
 * compact element list — if any of those surfaces stayed unclamped, off-viewport elements
 * would assert/match/tap on IOS_AXE only. The nastiest case is a node that barely straddles
 * the viewport edge (under Maestro's 10% visibility threshold but not FULLY offscreen, so
 * the compact builder's own offscreen rule alone would still list it and assign it a ref
 * whose tap falls back to a blind coordinate tap at its off-screen center).
 */
class AxeScreenStateTest {

  private val deviceWidth = 402
  private val deviceHeight = 874

  // Screen-sized app root with one fully on-screen row, one fold straddler at y=872..916
  // on an 874pt screen (~4.5% visible: not fully offscreen, but under the 10% clamp), and
  // one row fully below the fold.
  private val describeUiJson = """
    [
      {
        "role": "AXApplication",
        "role_description": "application",
        "type": "Application",
        "AXLabel": "Finance",
        "enabled": true,
        "pid": 4242,
        "frame": {"x": 0, "y": 0, "width": 402, "height": 874},
        "children": [
          {
            "role": "AXCell",
            "type": "Cell",
            "AXLabel": "On-screen row",
            "frame": {"x": 16, "y": 300, "width": 370, "height": 44}
          },
          {
            "role": "AXCell",
            "type": "Cell",
            "AXLabel": "Straddler row",
            "frame": {"x": 16, "y": 872, "width": 370, "height": 44}
          },
          {
            "role": "AXCell",
            "type": "Cell",
            "AXLabel": "Below-fold row",
            "frame": {"x": 16, "y": 1036, "width": 370, "height": 44}
          }
        ]
      }
    ]
  """.trimIndent()

  private fun screenState() = AxeScreenState(
    udid = "test-udid",
    deviceWidth = deviceWidth,
    deviceHeight = deviceHeight,
    describeUi = { AxeCli.Result(exitCode = 0, stdout = describeUiJson, stderr = "") },
  )

  private fun TrailblazeNode.findByLabel(label: String): TrailblazeNode? =
    findFirst { (it.driverDetail as? DriverNodeDetail.IosAxe)?.label == label }

  @Test
  fun `selector-visible tree excludes off-viewport nodes and refs only on-screen elements`() {
    // Precondition documenting the bug window: describe-ui DOES report both off-viewport
    // rows, and refs built over that unclamped tree would reach the straddler (it is not
    // FULLY offscreen, so the compact builder's own rule lists it).
    val fullTree = AxeJsonMapper.parse(describeUiJson)
    assertNotNull(fullTree.findByLabel("Straddler row"))
    assertNotNull(fullTree.findByLabel("Below-fold row"))
    val unclampedRefs = CompactScreenElements.buildForIosAxe(
      tree = fullTree,
      screenHeight = deviceHeight,
      screenWidth = deviceWidth,
    ).applyRefsToTree(fullTree)
    assertNotNull(
      assertNotNull(unclampedRefs.findByLabel("Straddler row")).ref,
      "precondition: without the clamp the straddler would earn a ref",
    )

    // The tree findMatches / waypoint matching / ref taps resolve against: clamped.
    val tree = assertNotNull(screenState().trailblazeNodeTree)
    assertNull(
      tree.findByLabel("Straddler row"),
      "a sub-10%-visible edge straddler must not be matchable or ref-tappable",
    )
    assertNull(
      tree.findByLabel("Below-fold row"),
      "a below-fold element must not match live findMatches / waypoint queries",
    )
    assertNotNull(
      assertNotNull(tree.findByLabel("On-screen row")).ref,
      "on-screen elements still earn refs",
    )
  }

  @Test
  fun `agent-facing element text lists only clamp-surviving elements`() {
    val text = assertNotNull(screenState().viewHierarchyTextRepresentation)
    assertTrue(text.contains("On-screen row"))
    assertTrue(
      !text.contains("Straddler row") && !text.contains("Below-fold row"),
      "the LLM must not be offered an element it cannot act on",
    )
  }

  @Test
  fun `clamp-to-nothing capture falls back to the unclamped tree like Maestro`() {
    // Everything is off-viewport (the root itself sits below the fold), so the clamp prunes
    // the whole tree. Maestro's ViewHierarchy.from keeps the unfiltered tree in that case
    // (`filtered ?: it`) — the capture must stay usable instead of throwing.
    val allOffViewportJson = """
      [
        {
          "role": "AXApplication",
          "type": "Application",
          "AXLabel": "Finance",
          "enabled": true,
          "pid": 4242,
          "frame": {"x": 0, "y": 2000, "width": 402, "height": 874},
          "children": [
            {
              "role": "AXCell",
              "type": "Cell",
              "AXLabel": "Below-fold row",
              "frame": {"x": 16, "y": 2036, "width": 370, "height": 44}
            }
          ]
        }
      ]
    """.trimIndent()
    val state = AxeScreenState(
      udid = "test-udid",
      deviceWidth = deviceWidth,
      deviceHeight = deviceHeight,
      describeUi = { AxeCli.Result(exitCode = 0, stdout = allOffViewportJson, stderr = "") },
    )
    val tree = assertNotNull(
      state.trailblazeNodeTree,
      "a fully-pruned clamp must fall back to the unclamped tree, not drop the capture",
    )
    assertNotNull(tree.findByLabel("Below-fold row"))
    // The Maestro-shaped hierarchy must not throw either.
    assertNotNull(state.viewHierarchy)
  }

  @Test
  fun `maestro-shaped view hierarchy is viewport-clamped`() {
    val hierarchy = screenState().viewHierarchy
    val offViewport = ViewHierarchyTreeNode.dfs(hierarchy) {
      it.accessibilityText == "Straddler row" || it.text == "Straddler row" ||
        it.accessibilityText == "Below-fold row" || it.text == "Below-fold row"
    }
    assertNull(offViewport, "the clamped hierarchy drops off-viewport nodes, matching Maestro")
  }
}
