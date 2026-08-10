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

  // Real `axe describe-ui` capture (axe 1.8.0, iOS 18.5 simulator, system Contacts app) of the
  // search-field subtree, verbatim, wrapped in the capture's own Application root fields. This
  // is the node shape that broke the `hintTextRegex` bridge in CI: the empty search
  // field carries its placeholder as AXValue with AXLabel null (newer runtimes mirror it onto
  // AXLabel instead), help is null, and a decorative sibling Image carries AXLabel "Search".
  private val contactsSearchDescribeUiJson = """
    [
      {
        "AXFrame": "{{0, 0}, {402, 874}}",
        "AXLabel": "Contacts",
        "AXUniqueId": null,
        "AXValue": null,
        "content_required": false,
        "custom_actions": [],
        "enabled": true,
        "frame": {"height": 874, "width": 402, "x": 0, "y": 0},
        "help": null,
        "pid": 81282,
        "role": "AXApplication",
        "role_description": "application",
        "subrole": null,
        "title": null,
        "traits": [],
        "type": "Application",
        "children": [
          {
            "AXFrame": "{{16, 153.33333333333334}, {370, 36}}",
            "AXLabel": null,
            "AXUniqueId": null,
            "AXValue": "Search",
            "content_required": false,
            "custom_actions": [],
            "enabled": true,
            "frame": {"height": 36, "width": 370, "x": 16, "y": 153.33333333333334},
            "help": null,
            "pid": 81282,
            "role": "AXTextField",
            "role_description": "search text field",
            "subrole": "AXSearchField",
            "title": null,
            "traits": [],
            "type": "TextField",
            "children": [
              {
                "AXFrame": "{{360.66666666666669, 160.33333333333334}, {17.333333333333314, 22}}",
                "AXLabel": "Dictate",
                "AXUniqueId": "Dictate",
                "AXValue": null,
                "children": [],
                "content_required": false,
                "custom_actions": [],
                "enabled": true,
                "frame": {"height": 22, "width": 17.333333333333314, "x": 360.6666666666667, "y": 160.33333333333334},
                "help": "Double-tap to start dictation. Double-tap with two fingers when finished.",
                "pid": 81282,
                "role": "AXButton",
                "role_description": "button",
                "subrole": null,
                "title": null,
                "traits": [],
                "type": "Button"
              },
              {
                "AXFrame": "{{22, 161.66666666666669}, {20.333333333333329, 18.666666666666657}}",
                "AXLabel": "Search",
                "AXUniqueId": "magnifyingglass",
                "AXValue": null,
                "children": [],
                "content_required": false,
                "custom_actions": [],
                "enabled": true,
                "frame": {"height": 18.666666666666657, "width": 20.33333333333333, "x": 22, "y": 161.66666666666669},
                "help": null,
                "pid": 81282,
                "role": "AXImage",
                "role_description": "image",
                "subrole": null,
                "title": null,
                "traits": [],
                "type": "Image"
              }
            ]
          }
        ]
      }
    ]
  """.trimIndent()

  @Test
  fun `bridged hint selector resolves the iOS 18 search field carrying its placeholder as AXValue`() {
    val tree = AxeJsonMapper.parse(contactsSearchDescribeUiJson)

    val result = TrailblazeNodeSelectorResolver.resolve(
      tree,
      TrailblazeNodeSelector.withMatch(DriverNodeMatch.IosMaestro(hintTextRegex = "Search")),
    )

    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch>(result)
    val detail = assertIs<DriverNodeDetail.IosAxe>(result.node.driverDetail)
    assertEquals("TextField", detail.type, "must resolve the search field, not the magnifying-glass Image")
    assertEquals("AXSearchField", detail.subrole)
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
