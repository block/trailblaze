package xyz.block.trailblaze.api

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IosAxeCompactElementListTest {

  private var nextId = 0L

  private fun node(
    detail: DriverNodeDetail.IosAxe = DriverNodeDetail.IosAxe(),
    bounds: TrailblazeNode.Bounds? = TrailblazeNode.Bounds(0, 0, 100, 50),
    children: List<TrailblazeNode> = emptyList(),
  ): TrailblazeNode =
    TrailblazeNode(
      nodeId = nextId++,
      driverDetail = detail,
      bounds = bounds,
      children = children,
    )

  @Test
  fun `label and value render as 'label, value' composite`() {
    val row =
      node(
        detail =
          DriverNodeDetail.IosAxe(
            role = "AXStaticText",
            type = "StaticText",
            label = "home",
            value = "(555) 478-7672",
          ),
      )
    val root = node(children = listOf(row))
    val result = IosAxeCompactElementList.build(root)

    assertContains(result.text, "StaticText \"home: (555) 478-7672\"")
  }

  @Test
  fun `label only renders as label`() {
    val btn =
      node(
        detail =
          DriverNodeDetail.IosAxe(
            role = "AXButton",
            type = "Button",
            label = "Send",
          ),
      )
    val root = node(children = listOf(btn))
    val result = IosAxeCompactElementList.build(root)

    assertContains(result.text, "Button \"Send\"")
    assertFalse(result.text.contains(":"), "no colon when only label present")
  }

  @Test
  fun `value only renders as value (no label to prefix)`() {
    val row =
      node(
        detail =
          DriverNodeDetail.IosAxe(
            role = "AXStaticText",
            type = "StaticText",
            value = "Body paragraph content",
          ),
      )
    val root = node(children = listOf(row))
    val result = IosAxeCompactElementList.build(root)

    assertContains(result.text, "StaticText \"Body paragraph content\"")
  }

  @Test
  fun `duplicate label and value dedupe to one`() {
    val btn =
      node(
        detail =
          DriverNodeDetail.IosAxe(
            role = "AXButton",
            type = "Button",
            label = "OK",
            value = "OK",
          ),
      )
    val root = node(children = listOf(btn))
    val result = IosAxeCompactElementList.build(root)

    // Single "OK", not "OK: OK"
    assertContains(result.text, "Button \"OK\"")
    assertFalse(result.text.contains("OK: OK"), "duplicate label/value should dedupe")
  }

  @Test
  fun `uniqueId surfaces as id=annotation`() {
    val btn =
      node(
        detail =
          DriverNodeDetail.IosAxe(
            role = "AXButton",
            type = "Button",
            label = "Sign In",
            uniqueId = "signin_button",
          ),
      )
    val root = node(children = listOf(btn))
    val result = IosAxeCompactElementList.build(root)

    assertContains(result.text, "Button \"Sign In\"")
    assertContains(result.text, "[id=signin_button]")
  }

  @Test
  fun `subrole annotation emitted for secure text field`() {
    val input =
      node(
        detail =
          DriverNodeDetail.IosAxe(
            role = "AXTextField",
            subrole = "AXSecureTextField",
            type = "TextField",
            label = "Password",
          ),
      )
    val root = node(children = listOf(input))
    val result = IosAxeCompactElementList.build(root)

    assertContains(result.text, "[subrole=AXSecureTextField]")
  }

  @Test
  fun `custom actions preserved as actions annotation`() {
    val row =
      node(
        detail =
          DriverNodeDetail.IosAxe(
            role = "AXButton",
            type = "Button",
            label = "mail",
            customActions = listOf("Show other options"),
          ),
      )
    val root = node(children = listOf(row))
    val result = IosAxeCompactElementList.build(root)

    assertContains(result.text, "[actions=Show other options]")
  }

  @Test
  fun `disabled annotation present when enabled=false`() {
    val btn =
      node(
        detail =
          DriverNodeDetail.IosAxe(
            role = "AXButton",
            type = "Button",
            label = "Delete",
            enabled = false,
          ),
      )
    val root = node(children = listOf(btn))
    val result = IosAxeCompactElementList.build(root)

    assertContains(result.text, "[disabled]")
  }

  @Test
  fun `annotations stay in priority order`() {
    val btn =
      node(
        detail =
          DriverNodeDetail.IosAxe(
            role = "AXButton",
            subrole = "AXSecureTextField",
            type = "TextField",
            label = "Password",
            uniqueId = "pwd",
            customActions = listOf("Clear"),
            enabled = false,
          ),
      )
    val root = node(children = listOf(btn))
    val result = IosAxeCompactElementList.build(root)

    // id before subrole before actions before disabled
    val line = result.text.lines().first { "TextField" in it }
    val idIdx = line.indexOf("[id=")
    val subroleIdx = line.indexOf("[subrole=")
    val actionsIdx = line.indexOf("[actions=")
    val disabledIdx = line.indexOf("[disabled]")
    assertTrue(idIdx >= 0 && subroleIdx > idIdx, "id before subrole: line=$line")
    assertTrue(actionsIdx > subroleIdx, "subrole before actions: line=$line")
    assertTrue(disabledIdx > actionsIdx, "actions before disabled: line=$line")
  }

  @Test
  fun `title omitted when it duplicates the visible label`() {
    val row =
      node(
        detail =
          DriverNodeDetail.IosAxe(
            role = "AXWindow",
            type = "Window",
            label = "Contacts",
            title = "Contacts",
          ),
      )
    val root = node(children = listOf(row))
    val result = IosAxeCompactElementList.build(root)

    assertFalse(result.text.contains("[title="), "redundant title should be suppressed")
  }

  @Test
  fun `title emitted when it adds info beyond the composite`() {
    val row =
      node(
        detail =
          DriverNodeDetail.IosAxe(
            role = "AXStaticText",
            type = "StaticText",
            value = "32.5",
            title = "Celsius reading",
          ),
      )
    val root = node(children = listOf(row))
    val result = IosAxeCompactElementList.build(root)

    assertContains(result.text, "[title=Celsius reading]")
  }

  @Test
  fun `falls back to title when no label or value`() {
    val win =
      node(
        detail =
          DriverNodeDetail.IosAxe(
            role = "AXWindow",
            type = "Window",
            title = "Settings",
          ),
      )
    val root = node(children = listOf(win))
    val result = IosAxeCompactElementList.build(root)

    assertContains(result.text, "Window \"Settings\"")
  }

  @Test
  fun `distinct contact rows with same category label get distinct refs`() {
    // The three "home" rows in Contacts carry different AXValues — the ref key must
    // include the composite (label+value), not just the label, or they'd collide.
    val rows =
      listOf(
        DriverNodeDetail.IosAxe(
          role = "AXStaticText", type = "StaticText",
          label = "home", value = "(555) 478-7672",
        ),
        DriverNodeDetail.IosAxe(
          role = "AXStaticText", type = "StaticText",
          label = "home", value = "d-higgins@mac.com",
        ),
        DriverNodeDetail.IosAxe(
          role = "AXStaticText", type = "StaticText",
          label = "home", value = "332 Laguna Street",
        ),
      )
    val root =
      node(
        children =
          rows.mapIndexed { i, d ->
            node(detail = d, bounds = TrailblazeNode.Bounds(0, i * 100, 100, 50))
          },
      )
    val result = IosAxeCompactElementList.build(root)

    assertEquals(3, result.refMapping.size, "three distinct refs expected, got: $result")
  }

  @Test
  fun `type-only node is emitted under normal filtering`() {
    // Node has no label/value/uniqueId/title but has a non-blank type — should still
    // show up in the compact list (used to be filtered out by hasIdentifiableProperties).
    val structural =
      node(
        detail = DriverNodeDetail.IosAxe(role = "AXGroup", type = "Group"),
      )
    val root = node(children = listOf(structural))
    val result = IosAxeCompactElementList.build(root)

    assertContains(result.text, "Group")
  }

  @Test
  fun `ALL_ELEMENTS emits unfiltered descriptors`() {
    // Node has nothing identifiable and nothing to describe — under ALL_ELEMENTS it
    // still shouldn't crash. Verify no elements are reported.
    val blank = node(detail = DriverNodeDetail.IosAxe())
    val root = node(children = listOf(blank))

    val normal = IosAxeCompactElementList.build(root)
    val all = IosAxeCompactElementList.build(root, setOf(SnapshotDetail.ALL_ELEMENTS))

    assertContains(normal.text, "no elements found")
    assertContains(all.text, "no elements found")
  }

  @Test
  fun `composeDisplayText handles empty strings as absent`() {
    assertEquals(
      "Send",
      IosAxeCompactElementList.composeDisplayText(
        DriverNodeDetail.IosAxe(label = "Send", value = ""),
      ),
    )
    assertEquals(
      "Body",
      IosAxeCompactElementList.composeDisplayText(
        DriverNodeDetail.IosAxe(label = "", value = "Body"),
      ),
    )
    assertEquals(
      null,
      IosAxeCompactElementList.composeDisplayText(
        DriverNodeDetail.IosAxe(label = "", value = "", title = ""),
      ),
    )
  }

  @Test
  fun `composite is truncated when value is very long`() {
    val longValue = "x".repeat(500)
    val detail = DriverNodeDetail.IosAxe(label = "notes", value = longValue)
    val composed = IosAxeCompactElementList.composeDisplayText(detail)!!
    assertTrue(composed.length <= 120, "composite should be <=120 chars, got ${composed.length}")
  }
}
