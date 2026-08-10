package xyz.block.trailblaze.host.axe

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import xyz.block.trailblaze.api.DriverNodeDetail
import xyz.block.trailblaze.api.DriverNodeMatch
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.api.TrailblazeNodeSelectorResolver

/**
 * Regression tests for duplicate-node collapsing in [AxeJsonMapper].
 *
 * AXe's `describe-ui` sometimes reports the same physical element twice — identical type,
 * text, and frame (observed in a real app on a disclosure StaticText). The duplicate makes a
 * host-unique selector spuriously ambiguous, failing the recorded tap. The mapper must collapse
 * TRUE duplicates while never touching legitimately repeated elements (two identical list rows
 * at different frames are real UI).
 */
class AxeTreeDedupeTest {

  private fun describeUiJson(vararg children: String) = """
    [
      {
        "role": "AXApplication",
        "role_description": "application",
        "type": "Application",
        "AXLabel": "ExampleApp",
        "enabled": true,
        "pid": 4242,
        "frame": {"x": 0, "y": 0, "width": 402, "height": 874},
        "children": [
          {
            "role": "AXWindow",
            "role_description": "window",
            "type": "Window",
            "frame": {"x": 0, "y": 0, "width": 402, "height": 874},
            "children": [${children.joinToString(",")}]
          }
        ]
      }
    ]
  """.trimIndent()

  private fun staticText(label: String, y: Int, children: String? = null) = """
    {
      "role": "AXStaticText",
      "type": "StaticText",
      "AXLabel": "$label",
      "frame": {"x": 16, "y": $y, "width": 370, "height": 44}${
    if (children != null) ""","children": [$children]""" else ""
  }
    }
  """.trimIndent()

  @Test
  fun `an element reported twice with an identical frame collapses to one node`() {
    val tree = AxeJsonMapper.parse(
      describeUiJson(
        staticText("0.5% currency spread", y = 300),
        staticText("0.5% currency spread", y = 300),
        staticText("Amount", y = 400),
      ),
    )

    val matches = tree.findAll {
      (it.driverDetail as? DriverNodeDetail.IosAxe)?.label == "0.5% currency spread"
    }
    assertEquals(1, matches.size, "true duplicate (same type+label+frame) must collapse")
  }

  @Test
  fun `a host-unique selector resolves unambiguously despite an AXe duplicate`() {
    val tree = AxeJsonMapper.parse(
      describeUiJson(
        staticText("0.5% currency spread", y = 300),
        staticText("0.5% currency spread", y = 300),
        staticText("Amount", y = 400),
      ),
    )

    val result = TrailblazeNodeSelectorResolver.resolve(
      tree,
      TrailblazeNodeSelector.withMatch(DriverNodeMatch.IosAxe(labelRegex = ".*currency spread.*")),
    )
    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch>(result)
  }

  @Test
  fun `identical list rows at different frames are both kept`() {
    // Two visually identical rows at different y positions are real, repeated UI — not a
    // tree bug. The frame is part of the dedupe identity precisely so these survive.
    val tree = AxeJsonMapper.parse(
      describeUiJson(
        staticText("$1.00", y = 300),
        staticText("$1.00", y = 350),
      ),
    )

    val matches = tree.findAll {
      (it.driverDetail as? DriverNodeDetail.IosAxe)?.label == "$1.00"
    }
    assertEquals(2, matches.size, "repeated siblings at different frames are legitimate")
  }

  @Test
  fun `a child re-projection of its parent collapses onto the parent`() {
    // The overlapping parent/child projection shape: a StaticText whose child is the same
    // StaticText again (identical type+label+frame). Pre-order keeps the parent.
    val tree = AxeJsonMapper.parse(
      describeUiJson(
        staticText("0.5% currency spread", y = 300, children = staticText("0.5% currency spread", y = 300)),
      ),
    )

    val matches = tree.findAll {
      (it.driverDetail as? DriverNodeDetail.IosAxe)?.label == "0.5% currency spread"
    }
    assertEquals(1, matches.size, "child duplicate of its parent must collapse")
  }

  @Test
  fun `three identical siblings collapse to one node`() {
    // The rule is "keep the first pre-order occurrence", not "collapse pairs" — any number
    // of true duplicates folds into a single survivor.
    val tree = AxeJsonMapper.parse(
      describeUiJson(
        staticText("Continue", y = 500),
        staticText("Continue", y = 500),
        staticText("Continue", y = 500),
      ),
    )

    val matches = tree.findAll {
      (it.driverDetail as? DriverNodeDetail.IosAxe)?.label == "Continue"
    }
    assertEquals(1, matches.size, "all trailing true duplicates must collapse, not just the second")
  }

  @Test
  fun `a duplicate at a different depth with the same frame collapses`() {
    // The seen-set is tree-global, not per-sibling-list: the same element re-projected
    // inside a wrapping Group (different depth, identical frame) is still a true duplicate.
    val nestedDuplicate = """
      {
        "role": "AXGroup",
        "type": "Group",
        "frame": {"x": 0, "y": 200, "width": 402, "height": 200},
        "children": [${staticText("0.5% currency spread", y = 300)}]
      }
    """.trimIndent()
    val tree = AxeJsonMapper.parse(
      describeUiJson(
        staticText("0.5% currency spread", y = 300),
        nestedDuplicate,
      ),
    )

    val matches = tree.findAll {
      (it.driverDetail as? DriverNodeDetail.IosAxe)?.label == "0.5% currency spread"
    }
    assertEquals(1, matches.size, "depth is not part of the dedupe identity")
  }

  @Test
  fun `a duplicate that carries its own children is kept`() {
    // Conservative rule: only childless duplicates are dropped. A subtree-bearing "duplicate"
    // might be the only path to its children, so losing it is worse than a rare ambiguity.
    val tree = AxeJsonMapper.parse(
      describeUiJson(
        staticText("Header", y = 100),
        staticText("Header", y = 100, children = staticText("Nested detail", y = 110)),
      ),
    )

    val headers = tree.findAll {
      (it.driverDetail as? DriverNodeDetail.IosAxe)?.label == "Header"
    }
    assertEquals(2, headers.size, "a duplicate with children is never dropped")
    assertEquals(
      1,
      tree.findAll { (it.driverDetail as? DriverNodeDetail.IosAxe)?.label == "Nested detail" }.size,
      "the duplicate's subtree stays reachable",
    )
  }

  @Test
  fun `a childless duplicate AFTER a subtree-bearing twin is dropped`() {
    // Order-swapped counterpart of the test above: when the subtree-bearing node comes
    // first (seeding the seen-set), the trailing childless re-projection satisfies every
    // dedupe condition and collapses. The childless-only rule is deliberately asymmetric —
    // it protects subtrees from being dropped, not duplicates from being kept.
    val tree = AxeJsonMapper.parse(
      describeUiJson(
        staticText("Header", y = 100, children = staticText("Nested detail", y = 110)),
        staticText("Header", y = 100),
      ),
    )

    val headers = tree.findAll {
      (it.driverDetail as? DriverNodeDetail.IosAxe)?.label == "Header"
    }
    assertEquals(1, headers.size, "the trailing childless duplicate must collapse onto the subtree-bearing twin")
    assertEquals(
      1,
      tree.findAll { (it.driverDetail as? DriverNodeDetail.IosAxe)?.label == "Nested detail" }.size,
      "the surviving twin's subtree is untouched",
    )
  }

  @Test
  fun `blank structural containers at the same frame are left alone`() {
    // Nested chrome (Window inside Application, stacked Groups) legitimately shares frames.
    // Dedupe only applies to nodes carrying matchable text, so structure is never touched.
    val tree = AxeJsonMapper.parse(
      describeUiJson(
        """
        {
          "role": "AXGroup",
          "type": "Group",
          "frame": {"x": 0, "y": 0, "width": 402, "height": 874},
          "children": [
            {
              "role": "AXGroup",
              "type": "Group",
              "frame": {"x": 0, "y": 0, "width": 402, "height": 874}
            }
          ]
        }
        """.trimIndent(),
      ),
    )

    val groups = tree.findAll { (it.driverDetail as? DriverNodeDetail.IosAxe)?.type == "Group" }
    assertEquals(2, groups.size, "content-less containers are exempt from dedupe")
  }
}
