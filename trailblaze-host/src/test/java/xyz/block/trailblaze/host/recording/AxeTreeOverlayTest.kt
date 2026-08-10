package xyz.block.trailblaze.host.recording

import org.junit.Test
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import xyz.block.trailblaze.api.DriverNodeDetail
import xyz.block.trailblaze.api.DriverNodeMatch
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.api.TrailblazeNodeSelectorResolver
import xyz.block.trailblaze.host.axe.AxeCli
import xyz.block.trailblaze.host.axe.AxeJsonMapper

/**
 * Unit tests for [AxeTreeOverlay]: the tree-merge and selector-provenance pure functions, plus the
 * [AxeTreeOverlay.captureAxeTree] subprocess guard (its `describe-ui` call is injected so the
 * error-declines-to-null contract is exercised without a device or the `axe` binary).
 */
class AxeTreeOverlayTest {

  private val screenWidth = 400
  private val screenHeight = 800

  // --- captureAxeTree (subprocess guard) ---

  @Test
  fun `captureAxeTree returns null when the axe subprocess throws`() {
    val result = AxeTreeOverlay.captureAxeTree(
      udid = "SIM-UDID",
      isAvailable = { true },
      describeUi = { throw IOException("Too many open files") },
    )
    assertNull(result)
  }

  @Test
  fun `captureAxeTree parses a successful axe result`() {
    val tree = AxeTreeOverlay.captureAxeTree(
      udid = "SIM-UDID",
      isAvailable = { true },
      describeUi = { AxeCli.Result(exitCode = 0, stdout = axeJson, stderr = "") },
    )
    assertNotNull(tree)
  }

  // --- mergeAxeIntoMaestroTree ---

  @Test
  fun `merge returns the Maestro tree unchanged when there is no AXe tree`() {
    val maestro = maestroRoot(listOf(iosMaestro("Timecard", timecardBounds)))
    assertSame(maestro, AxeTreeOverlay.mergeAxeIntoMaestroTree(maestro, null, screenWidth, screenHeight))
  }

  @Test
  fun `merge returns the AXe tree when there is no Maestro tree`() {
    val axe = AxeJsonMapper.parse(axeJson)
    assertSame(axe, AxeTreeOverlay.mergeAxeIntoMaestroTree(null, axe, screenWidth, screenHeight))
  }

  @Test
  fun `merge appends only the AXe content the Maestro tree was missing`() {
    val maestro = maestroRoot(listOf(iosMaestro("Timecard", timecardBounds)))
    val axe = AxeJsonMapper.parse(axeJson)

    val merged = AxeTreeOverlay.mergeAxeIntoMaestroTree(maestro, axe, screenWidth, screenHeight)!!

    // The dropped sheet row is now resolvable by an iosAxe label selector — the whole point.
    val result = TrailblazeNodeSelectorResolver.resolve(
      merged,
      TrailblazeNodeSelector.withMatch(DriverNodeMatch.IosAxe(labelRegex = "Meal break \\(30m\\)")),
    )
    val match = assertIs<TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch>(result)
    assertIs<DriverNodeDetail.IosAxe>(match.node.driverDetail)

    // Exactly one node was appended: the sheet row. The duplicate "Timecard", the screen-sized
    // chrome, and the textless image are all excluded.
    assertEquals(1, merged.children.size - maestro.children.size)
    assertEquals(1, merged.aggregate().count { it.resolvedText() == "Timecard" })
    assertTrue(merged.aggregate().none { it.resolvedText() == "MyApp" })
  }

  // --- selectorReferencesIosAxe (replay perf gate) ---

  @Test
  fun `selector referencing iosAxe is detected`() {
    val selector = TrailblazeNodeSelector.withMatch(DriverNodeMatch.IosAxe(labelRegex = "Meal break"))
    assertTrue(AxeTreeOverlay.selectorReferencesIosAxe(selector))
  }

  @Test
  fun `a Maestro-dialect selector does not trigger enrichment`() {
    val selector = TrailblazeNodeSelector.withMatch(DriverNodeMatch.IosMaestro(textRegex = "Timecard"))
    assertTrue(!AxeTreeOverlay.selectorReferencesIosAxe(selector))
    assertTrue(!AxeTreeOverlay.selectorReferencesIosAxe(null))
  }

  @Test
  fun `an iosAxe match nested in a relational sub-selector is detected`() {
    val below = TrailblazeNodeSelector.withMatch(
      DriverNodeMatch.IosMaestro(textRegex = "Header"),
      below = TrailblazeNodeSelector.withMatch(DriverNodeMatch.IosAxe(labelRegex = "Rest break")),
    )
    assertTrue(AxeTreeOverlay.selectorReferencesIosAxe(below))

    val descendant = TrailblazeNodeSelector.withMatch(
      DriverNodeMatch.IosMaestro(textRegex = "List"),
      containsDescendants = listOf(TrailblazeNodeSelector.withMatch(DriverNodeMatch.IosAxe(uniqueId = "row_1"))),
    )
    assertTrue(AxeTreeOverlay.selectorReferencesIosAxe(descendant))
  }

  // --- helpers ---

  private fun maestroRoot(children: List<TrailblazeNode>): TrailblazeNode = TrailblazeNode(
    nodeId = 0,
    bounds = TrailblazeNode.Bounds(0, 0, screenWidth, screenHeight),
    children = children,
    driverDetail = DriverNodeDetail.IosMaestro(className = "Window"),
  )

  private fun iosMaestro(text: String?, bounds: TrailblazeNode.Bounds?): TrailblazeNode = TrailblazeNode(
    nodeId = 0,
    bounds = bounds,
    driverDetail = DriverNodeDetail.IosMaestro(text = text),
  )

  private val timecardBounds = TrailblazeNode.Bounds(16, 100, 386, 144)

  private fun TrailblazeNode.resolvedText(): String? = when (val d = driverDetail) {
    is DriverNodeDetail.IosMaestro -> d.resolveText()
    is DriverNodeDetail.IosAxe -> d.resolveText()
    else -> null
  }

  // AXApplication chrome ("MyApp", screen-sized) wrapping a window whose children are: the
  // already-present "Timecard" (dup), the dropped sheet row "Meal break (30m)" (new), and a
  // textless image (never overlaid).
  private val axeJson = """
    [
      {
        "role": "AXApplication",
        "type": "Application",
        "AXLabel": "MyApp",
        "frame": {"x": 0, "y": 0, "width": $screenWidth, "height": $screenHeight},
        "children": [
          {
            "role": "AXWindow",
            "type": "Window",
            "frame": {"x": 0, "y": 0, "width": $screenWidth, "height": $screenHeight},
            "children": [
              {
                "role": "AXStaticText",
                "type": "StaticText",
                "AXLabel": "Timecard",
                "frame": {"x": 16, "y": 100, "width": 370, "height": 44}
              },
              {
                "role": "AXStaticText",
                "type": "StaticText",
                "AXLabel": "Meal break (30m)",
                "frame": {"x": 16, "y": 496, "width": 370, "height": 44}
              },
              {
                "role": "AXImage",
                "type": "Image",
                "frame": {"x": 16, "y": 600, "width": 60, "height": 44}
              }
            ]
          }
        ]
      }
    ]
  """.trimIndent()
}
