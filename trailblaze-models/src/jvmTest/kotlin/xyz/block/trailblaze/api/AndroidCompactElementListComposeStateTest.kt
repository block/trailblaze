package xyz.block.trailblaze.api

import kotlin.test.Test
import kotlin.test.assertContains

import kotlin.test.assertTrue

/**
 * The compact hierarchy is what a Compose selector gets authored from. A property the collector
 * captures and this rendering drops is a property no trail can be written against, however well
 * the resolver supports it — so every field on [DriverNodeDetail.Compose] that has a slot here has
 * to reach it.
 */
class AndroidCompactElementListComposeStateTest {

  private var nextId = 0L

  private fun node(
    detail: DriverNodeDetail.Compose,
    children: List<TrailblazeNode> = emptyList(),
  ): TrailblazeNode = TrailblazeNode(
    nodeId = nextId++,
    driverDetail = detail,
    bounds = TrailblazeNode.Bounds(0, 0, 100, 50),
    children = children,
  )

  private fun root(vararg children: TrailblazeNode) =
    node(DriverNodeDetail.Compose(testTag = "root"), children = children.toList())

  @Test
  fun `two rows differing only by state description are distinguishable`() {
    // The case the field exists for: same role, same label, same everything a selector could name
    // — except the state each one is in. Rendered without it, the agent has two identical lines and
    // no way to author `stateDescriptionRegex`, which the resolver supports.
    val text = AndroidCompactElementList.build(
      root(
        node(
          DriverNodeDetail.Compose(
            role = "Button",
            text = "Details",
            stateDescription = "Expanded",
            hasClickAction = true,
          ),
        ),
        node(
          DriverNodeDetail.Compose(
            role = "Button",
            text = "Details",
            stateDescription = "Collapsed",
            hasClickAction = true,
          ),
        ),
      ),
    ).text

    assertContains(text, "Expanded")
    assertContains(text, "Collapsed")
  }

  @Test
  fun `a toggle with no state description still reports its toggle state`() {
    // The fallback. `toggleableState` held the state slot outright before, and a checkbox that
    // stopped saying whether it is on would be a regression traded for the fix above.
    val text = AndroidCompactElementList.build(
      root(
        node(
          DriverNodeDetail.Compose(
            role = "Checkbox",
            text = "Remember me",
            toggleableState = "On",
            hasClickAction = true,
          ),
        ),
      ),
    ).text

    assertContains(text, "On")
  }

  @Test
  fun `an app-authored state description wins over the derived toggle label`() {
    val text = AndroidCompactElementList.build(
      root(
        node(
          DriverNodeDetail.Compose(
            role = "Switch",
            text = "Notifications",
            stateDescription = "Muted until tomorrow",
            toggleableState = "Off",
            hasClickAction = true,
          ),
        ),
      ),
    ).text

    assertContains(text, "Muted until tomorrow")
  }

  @Test
  fun `heading, pane title and error reach the agent`() {
    // Three more fields the collector captures and this mapper used to hardcode away. Grouped
    // because they are one defect, not three: a Compose property with a slot here that never
    // reached it.
    val text = AndroidCompactElementList.build(
      root(
        node(DriverNodeDetail.Compose(text = "Payment methods", isHeading = true)),
        node(
          DriverNodeDetail.Compose(
            role = "TextField",
            text = "Card number",
            editableText = "4242",
            errorText = "Card expired",
          ),
        ),
        // No text of its own — the pane title is the only name this container has, which is the
        // case the field exists for (a dialog or bottom sheet).
        node(
          DriverNodeDetail.Compose(paneTitle = "Checkout"),
          children = listOf(DriverNodeDetail.Compose(text = "Total", hasClickAction = true).let(::node)),
        ),
      ),
    ).text

    assertTrue("[heading]" in text, "A Compose heading must render as one:\n$text")
    assertContains(text, "Card expired")
    assertContains(text, "Checkout")
  }

  @Test
  fun `toggle state still renders when a state description takes the state slot`() {
    // The two are independent readings of the same control, and the checked marker is derived from
    // `toggleableState` rather than from the rendered state label — so a control that has both must
    // print both, not trade one for the other.
    val text = AndroidCompactElementList.build(
      root(
        node(
          DriverNodeDetail.Compose(
            role = "Checkbox",
            text = "Remember me",
            stateDescription = "Saved for 30 days",
            toggleableState = "On",
            hasClickAction = true,
          ),
        ),
      ),
    ).text

    val line = text.lines().single { "Remember me" in it }
    assertContains(line, "[checked]")
    assertContains(line, "Saved for 30 days")
  }
}
