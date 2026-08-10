package xyz.block.trailblaze.host.axe

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import xyz.block.trailblaze.api.DriverNodeMatch
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.api.TrailblazeNodeSelectorResolver

/**
 * Contract tests for the IOS_AXE viewport clamp.
 *
 * `axe describe-ui` spans the WHOLE scroll content, not just the viewport, so without a
 * clamp an element below the fold still resolves: asserts pass for off-screen elements
 * (so they stop being scroll barriers) and taps dispatch a blind HID tap at off-screen
 * coordinates with reported success (a real capture showed a Terms-of-Service tap land at
 * y=1058 on an 874pt screen, activating the wrong element).
 *
 * The clamp mirrors Maestro's capture-time `TreeNode.filterOutOfBounds` (maestro-client
 * `ViewHierarchy.from`): a node less than 10% visible in the viewport with no surviving
 * children is pruned from the tree before selector resolution, so off-screen elements are
 * simply absent, exactly as on the IOS_HOST/Maestro driver.
 */
class AxeViewportClampTest {

  // Portrait iPhone 16 Pro sim in points, the same space describe-ui frames use.
  private val viewportWidth = 402
  private val viewportHeight = 874

  // Trimmed describe-ui capture: a screen-sized app root wrapping a tall scroll surface
  // whose content extends far below the fold. "Terms of Service" sits fully below the
  // viewport (the blind-tap victim); "Bitcoin" appears twice, once on screen and once
  // below the fold (the duplicate that hijacks scroll-until-visible target selection).
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
            "role": "AXWindow",
            "role_description": "window",
            "type": "Window",
            "frame": {"x": 0, "y": 0, "width": 402, "height": 874},
            "children": [
              {
                "role": "AXScrollArea",
                "type": "ScrollView",
                "frame": {"x": 16, "y": -822, "width": 370, "height": 9822},
                "children": [
                  {
                    "role": "AXCell",
                    "type": "Cell",
                    "AXLabel": "Bitcoin",
                    "frame": {"x": 16, "y": 300, "width": 370, "height": 44}
                  },
                  {
                    "role": "AXCell",
                    "type": "Cell",
                    "AXLabel": "Bitcoin",
                    "frame": {"x": 16, "y": 2100, "width": 370, "height": 44}
                  },
                  {
                    "role": "AXStaticText",
                    "type": "StaticText",
                    "AXLabel": "Mostly visible fold straddler",
                    "frame": {"x": 16, "y": 860, "width": 370, "height": 44}
                  },
                  {
                    "role": "AXStaticText",
                    "type": "StaticText",
                    "AXLabel": "Barely visible fold straddler",
                    "frame": {"x": 16, "y": 872, "width": 370, "height": 44}
                  },
                  {
                    "role": "AXButton",
                    "type": "Button",
                    "AXLabel": "Terms of Service",
                    "frame": {"x": 16, "y": 1036, "width": 370, "height": 44}
                  }
                ]
              }
            ]
          }
        ]
      }
    ]
  """.trimIndent()

  private fun clampedTree(): TrailblazeNode {
    val full = AxeJsonMapper.parse(describeUiJson)
    return assertNotNull(
      AxeViewportClamp.clamp(full, viewportWidth, viewportHeight),
      "the screen-sized app root must survive the clamp",
    )
  }

  private fun resolve(tree: TrailblazeNode, label: String) = TrailblazeNodeSelectorResolver.resolve(
    tree,
    TrailblazeNodeSelector.withMatch(DriverNodeMatch.IosAxe(labelRegex = Regex.escape(label))),
  )

  @Test
  fun `below-fold element is in the full tree but absent from the clamped tree`() {
    val full = AxeJsonMapper.parse(describeUiJson)
    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch>(
      resolve(full, "Terms of Service"),
      "precondition: describe-ui does report the below-fold element",
    )

    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.NoMatch>(
      resolve(clampedTree(), "Terms of Service"),
      "an element fully below the fold must not resolve, matching Maestro's filtered hierarchy",
    )
  }

  @Test
  fun `duplicate label resolves to the on-screen match after clamping`() {
    val result = resolve(clampedTree(), "Bitcoin")

    val single = assertIs<TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch>(
      result,
      "the below-fold duplicate must be clamped out so it cannot hijack match selection",
    )
    assertEquals(300, single.node.bounds?.top, "the surviving match is the on-screen row")
  }

  @Test
  fun `fold straddler above the 10 percent visibility threshold survives the clamp`() {
    // y=860..904 on an 874pt screen: 14/44 = ~32% visible. Maestro keeps it (and taps its
    // raw bounds center, even though that center at y=882 is off screen) - parity keeps it too.
    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch>(
      resolve(clampedTree(), "Mostly visible fold straddler"),
    )
  }

  @Test
  fun `fold straddler below the 10 percent visibility threshold is clamped out`() {
    // y=872..916 on an 874pt screen: 2/44 = ~4.5% visible — under Maestro's 0.1 cutoff.
    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.NoMatch>(
      resolve(clampedTree(), "Barely visible fold straddler"),
    )
  }

  @Test
  fun `scroll surface under 10 percent visible is kept while it has visible descendants`() {
    // The ScrollView spans y=-822..9000 (874/9822 = ~8.9% visible, and at x=16 it does not
    // trip the whole-viewport overflow case). Pruning it would drop its on-screen children
    // with it; Maestro keeps such containers as long as any child survives.
    val scrollArea = clampedTree().findFirst { it.bounds?.height == 9822 }
    assertNotNull(scrollArea, "the scroll surface survives via its visible children")
  }

  @Test
  fun `everything off screen clamps to null`() {
    val json = """
      [
        {
          "role": "AXCell",
          "type": "Cell",
          "AXLabel": "Below the fold",
          "frame": {"x": 16, "y": 1036, "width": 370, "height": 44}
        }
      ]
    """.trimIndent()
    assertNull(AxeViewportClamp.clamp(AxeJsonMapper.parse(json), viewportWidth, viewportHeight))
  }

  @Test
  fun `zero-size node is pruned even at an on-screen position`() {
    // Maestro's getVisiblePercentage returns 0.0 for 0x0 bounds before any division, so a
    // childless zero-size node is filtered no matter where it sits.
    val json = """
      [
        {
          "role": "AXImage",
          "type": "Image",
          "AXLabel": "Zero size",
          "frame": {"x": 100, "y": 100, "width": 0, "height": 0}
        }
      ]
    """.trimIndent()
    assertNull(AxeViewportClamp.clamp(AxeJsonMapper.parse(json), viewportWidth, viewportHeight))
  }

  @Test
  fun `degenerate one-dimensional node is kept even when fully off screen`() {
    // Zero height with nonzero width: Maestro divides 0 by 0, gets NaN, and NaN < 0.1 is
    // false — such nodes are always kept, even below the fold. The port keeps them too.
    val json = """
      [
        {
          "role": "AXStaticText",
          "type": "StaticText",
          "AXLabel": "Divider",
          "frame": {"x": 16, "y": 2000, "width": 370, "height": 0}
        }
      ]
    """.trimIndent()
    assertNotNull(AxeViewportClamp.clamp(AxeJsonMapper.parse(json), viewportWidth, viewportHeight))
  }

  @Test
  fun `node overflowing the whole viewport counts as fully visible`() {
    // Maestro's overflow special case: bounds covering the entire screen on every side are
    // 100% visible regardless of how large the total area is.
    val json = """
      [
        {
          "role": "AXGroup",
          "type": "Group",
          "AXLabel": "Backdrop",
          "frame": {"x": -10, "y": -8000, "width": 422, "height": 17000}
        }
      ]
    """.trimIndent()
    assertNotNull(AxeViewportClamp.clamp(AxeJsonMapper.parse(json), viewportWidth, viewportHeight))
  }
}
