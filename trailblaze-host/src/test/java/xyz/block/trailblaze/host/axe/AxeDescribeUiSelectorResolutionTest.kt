package xyz.block.trailblaze.host.axe

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import xyz.block.trailblaze.api.DriverNodeDetail
import xyz.block.trailblaze.api.DriverNodeMatch
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.api.TrailblazeNodeSelectorResolver

/**
 * Regression test for selector resolution over a parsed `axe describe-ui` tree.
 *
 * The AXe `AXApplication` root carries the app name as its AXLabel and is sized to the
 * screen — in the iOS Settings app, an anchored `labelRegex: ^Settings$` matched the root
 * ahead of the intended row (the root sorts first at (0,0)), so the resolved tap landed at
 * screen center. Nasty because the wrong-coordinate tap sometimes still "passes".
 */
class AxeDescribeUiSelectorResolutionTest {

  // Trimmed describe-ui capture shape: the Settings app root (AXLabel "Settings", screen-sized)
  // wrapping a window with a nav row whose label is also exactly "Settings".
  private val describeUiJson = """
    [
      {
        "role": "AXApplication",
        "role_description": "application",
        "type": "Application",
        "AXLabel": "Settings",
        "enabled": true,
        "pid": 4242,
        "frame": {"x": 0, "y": 0, "width": 402, "height": 874},
        "children": [
          {
            "role": "AXWindow",
            "role_description": "window",
            "type": "Window",
            "frame": {"x": 0, "y": 0, "width": 402, "height": 874},
            "children": [
              {
                "role": "AXStaticText",
                "type": "StaticText",
                "AXLabel": "General",
                "frame": {"x": 16, "y": 250, "width": 370, "height": 44}
              },
              {
                "role": "AXCell",
                "type": "Cell",
                "AXLabel": "Settings",
                "custom_actions": [],
                "frame": {"x": 16, "y": 300, "width": 370, "height": 44}
              }
            ]
          }
        ]
      }
    ]
  """.trimIndent()

  @Test
  fun `anchored label selector resolves to the row, not the screen-sized Application root`() {
    val tree = AxeJsonMapper.parse(describeUiJson)

    val result = TrailblazeNodeSelectorResolver.resolve(
      tree,
      TrailblazeNodeSelector.withMatch(DriverNodeMatch.IosAxe(labelRegex = "^Settings$")),
    )

    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch>(result)
    val detail = assertIs<DriverNodeDetail.IosAxe>(result.node.driverDetail)
    assertEquals("Cell", detail.type, "must resolve the row, not the Application/Window chrome")

    val center = assertNotNull(result.node.centerPoint())
    assertEquals(16 + 370 / 2 to 300 + 44 / 2, center, "tap center is the row's center, not screen center")
  }

  @Test
  fun `bridged Maestro text selector also skips the Application root`() {
    val tree = AxeJsonMapper.parse(describeUiJson)

    val result = TrailblazeNodeSelectorResolver.resolve(
      tree,
      TrailblazeNodeSelector.withMatch(DriverNodeMatch.IosMaestro(textRegex = "Settings")),
    )

    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch>(result)
    val detail = assertIs<DriverNodeDetail.IosAxe>(result.node.driverDetail)
    assertEquals("Cell", detail.type)
  }
}
