package xyz.block.trailblaze.android.accessibility

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import xyz.block.trailblaze.api.DriverNodeDetail
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.model.TapRouteOverride

/**
 * Pure-function coverage of [planActionClickRoute] — the gate that decides whether a
 * selector-resolved tap routes through `AccessibilityNodeInfo.ACTION_CLICK` (semantic
 * dispatch) or falls back to the coordinate gesture path.
 *
 * The gate must return non-null on the happy path (visible, clickable, non-editable,
 * non-long-press) and null on every disqualifying condition. The downstream
 * [TrailblazeAccessibilityService.tapByActionClickOnBounds] tree walk needs a live
 * `AccessibilityNodeInfo`, so its dispatch contract stays an integration concern; this
 * test pins only the upstream gating decision.
 */
class PlanActionClickRouteTest {

  @Test
  fun `routes to ACTION_CLICK when node is visible, clickable, non-editable, non-long-press`() {
    val plan = planActionClickRoute(
      node = node(
        bounds = TrailblazeNode.Bounds(100, 200, 300, 400),
        detail = androidA11y(
          className = "android.widget.Button",
          resourceId = "com.example.app:id/submit",
          text = "Submit",
          actions = listOf(ACTION_CLICK_NAME),
        ),
      ),
      longPress = false,
    )
    assertEquals(
      ActionClickPlan(
        bounds = TrailblazeNode.Bounds(100, 200, 300, 400),
        className = "android.widget.Button",
        resourceId = "com.example.app:id/submit",
      ),
      plan,
      "Happy path must produce a plan carrying the resolved node's identity.",
    )
  }

  @Test
  fun `routes to ACTION_CLICK when node has contentDescription but no text — canvas-widget virtual view shape`() {
    // `ExploreByTouchHelper` virtual views (the original motivating case for this routing —
    // canvas widgets that draw their own buttons and hand-roll hit-testing in `onTouchEvent`)
    // emit a per-button `contentDescription` ("1", "2", …) without setting `text`. The
    // ACTION_CLICK route is the ONLY way to dispatch these — gesture-path motion injection
    // hits the canvas's `onTouchEvent` which emits zero accessibility events, so the settle
    // layer's 50ms grace window expires and subsequent button presses fire too early.
    val plan = planActionClickRoute(
      node = node(
        bounds = TrailblazeNode.Bounds(0, 0, 100, 100),
        detail = androidA11y(
          className = "android.widget.Button",
          contentDescription = "1",
          actions = listOf(ACTION_CLICK_NAME),
        ),
      ),
      longPress = false,
    )
    assertEquals(
      ActionClickPlan(
        bounds = TrailblazeNode.Bounds(0, 0, 100, 100),
        className = "android.widget.Button",
        resourceId = null,
      ),
      plan,
      "Virtual views carrying only a contentDescription must keep the ACTION_CLICK route — " +
        "that's the original motivating case for this routing.",
    )
  }

  @Test
  fun `falls back to gesture for clickable wrapper containers with no direct text or contentDescription`() {
    // Some downstream apps surface row-shaped call-to-action buttons as an
    // `android.view.ViewGroup` with `clickable=true` and `ACTION_CLICK` advertised, but the
    // text lives on a child TextView and the wrapper's `View.performClick()` no-ops silently
    // (the real click handler isn't on the wrapper). Selectors of shape
    // `{containsChild: {textRegex: "<label>"}}` resolve to the wrapper — without this gate,
    // ACTION_CLICK dispatches "successfully" but the UI never advances and the LLM exhausts
    // its call budget. Gesture-path motion injection lands at whichever ancestor actually
    // owns the touch handler and works.
    val plan = planActionClickRoute(
      node = node(
        bounds = TrailblazeNode.Bounds(389, 1762, 692, 1810),
        detail = androidA11y(
          className = "android.view.ViewGroup",
          // No text, no contentDescription — wrapper inherits text via its children.
          actions = listOf(ACTION_CLICK_NAME),
        ),
      ),
      longPress = false,
    )
    assertNull(
      plan,
      "Clickable wrappers without direct text/contentDescription must use the gesture path — " +
        "the real click handler isn't reachable via `performClick()` on the wrapper.",
    )
  }

  @Test
  fun `routes to ACTION_CLICK for a checked option row whose label lives on a child node`() {
    // An unmerged-semantics option row: the node owns the checked state but not the label, so
    // it fails the leaf-text check while being exactly the shape ACTION_CLICK handles best.
    // A Compose `selectable` row surfaces this way — `android.view.View` carrying
    // isCheckable/isChecked, with the label on a child text node — so a
    // `{containsChild: {textRegex: "<label>"}}` selector resolves to the textless parent.
    val plan = planActionClickRoute(
      node = node(
        bounds = TrailblazeNode.Bounds(40, 1720, 1040, 1860),
        detail = androidA11y(
          className = "android.view.View",
          actions = listOf(ACTION_CLICK_NAME),
          isCheckable = true,
          isChecked = true,
        ),
      ),
      longPress = false,
    )
    assertEquals(
      ActionClickPlan(
        bounds = TrailblazeNode.Bounds(40, 1720, 1040, 1860),
        className = "android.view.View",
        resourceId = null,
      ),
      plan,
      "A checkable node publishing its state must keep ACTION_CLICK even with no text of its own.",
    )
  }

  @Test
  fun `routes to ACTION_CLICK for an unchecked option row that publishes a stateDescription`() {
    // The other half of the population: a not-currently-selected row reports isChecked=false but
    // still advertises its state via stateDescription. Every textless checkable node in the
    // committed downstream android waypoint fixtures carries "Selected" or "Not selected" this
    // way, so gating on isChecked alone would send the unselected ones back to gesture.
    val plan = planActionClickRoute(
      node = node(
        bounds = TrailblazeNode.Bounds(40, 1720, 1040, 1860),
        detail = androidA11y(
          className = "android.view.View",
          actions = listOf(ACTION_CLICK_NAME),
          isCheckable = true,
          isChecked = false,
          stateDescription = "Not selected",
        ),
      ),
      longPress = false,
    )
    assertEquals(
      ActionClickPlan(
        bounds = TrailblazeNode.Bounds(40, 1720, 1040, 1860),
        className = "android.view.View",
        resourceId = null,
      ),
      plan,
      "An unselected option row that publishes stateDescription still qualifies.",
    )
  }

  @Test
  fun `declines a checkable container that publishes no checked state`() {
    // The exemption's justification is that ACTION_CLICK toggles a node which owns the state it
    // claims. A textless container that sets isCheckable but reports neither isChecked nor a
    // stateDescription has made no such claim, and its real handler may sit elsewhere. An
    // accordion option row is this shape: ACTION_CLICK selects the option while only a real
    // touch expands the row holding the sub-options, so a recorded follow-up tap on a
    // sub-option finds nothing.
    val plan = planActionClickRoute(
      node = node(
        bounds = TrailblazeNode.Bounds(80, 1115, 1000, 1260),
        detail = androidA11y(
          className = "android.view.View",
          actions = listOf(ACTION_CLICK_NAME),
          isCheckable = true,
        ),
      ),
      longPress = false,
    )
    assertNull(
      plan,
      "A stateless checkable container must use the gesture path like any other wrapper.",
    )
  }

  @Test
  fun `checkable exemption does not bypass the other gate conditions`() {
    // The exemption is scoped to the leaf-text check alone. A checkable node that fails a
    // different condition must still decline — here `isVisibleToUser=false`, the overlay gate,
    // where ACTION_CLICK would bypass the OS hit-test's z-order and fire an occluded node.
    val plan = planActionClickRoute(
      node = node(
        bounds = TrailblazeNode.Bounds(40, 1720, 1040, 1860),
        detail = androidA11y(
          className = "android.view.View",
          actions = listOf(ACTION_CLICK_NAME),
          isCheckable = true,
          isChecked = true,
          isVisibleToUser = false,
        ),
      ),
      longPress = false,
    )
    assertNull(
      plan,
      "Checkable only exempts the leaf-text check — every other condition still gates.",
    )
  }

  @Test
  fun `routes to ACTION_CLICK when node has both text and contentDescription`() {
    // The most common real-world shape: `<ImageButton android:contentDescription="…"/>`
    // with explicit text, or a Compose `Button { Text("Save") }` whose merged-semantics node
    // surfaces both the merged label text and a separate contentDescription. The two fields
    // are OR'd in the gate, so the intersection case must keep the ACTION_CLICK route.
    val plan = planActionClickRoute(
      node = node(
        bounds = TrailblazeNode.Bounds(0, 0, 100, 100),
        detail = androidA11y(
          className = "android.widget.ImageButton",
          text = "Save",
          contentDescription = "Save changes",
          actions = listOf(ACTION_CLICK_NAME),
        ),
      ),
      longPress = false,
    )
    assertEquals(
      ActionClickPlan(
        bounds = TrailblazeNode.Bounds(0, 0, 100, 100),
        className = "android.widget.ImageButton",
        resourceId = null,
      ),
      plan,
      "Nodes carrying both text and contentDescription must route to ACTION_CLICK — the OR " +
        "is between fields, so either-or-both is sufficient.",
    )
  }

  @Test
  fun `treats blank text or contentDescription as missing`() {
    // Defensive: an a11y node could surface an empty-but-non-null `text` or
    // `contentDescription` (e.g. an explicitly-cleared field). Don't let that slip through
    // as "has text" — semantically it's a wrapper with no identifying content, same as null.
    val plan = planActionClickRoute(
      node = node(
        bounds = TrailblazeNode.Bounds(0, 0, 100, 100),
        detail = androidA11y(
          className = "android.view.ViewGroup",
          text = "   ",
          contentDescription = "",
          actions = listOf(ACTION_CLICK_NAME),
        ),
      ),
      longPress = false,
    )
    assertNull(plan, "Blank/whitespace text and empty contentDescription must be treated as absent.")
  }

  @Test
  fun `falls back to gesture for long-press`() {
    // ACTION_LONG_CLICK is its own routing decision — out of scope for this gate, even when
    // the node advertises ACTION_CLICK.
    val plan = planActionClickRoute(
      node = clickableNode(),
      longPress = true,
    )
    assertNull(plan, "Long-press must always use the gesture path.")
  }

  @Test
  fun `falls back to gesture when bounds are unknown`() {
    // The live-tree lookup is bounds-keyed; without bounds we can't identify the target
    // node in the live a11y tree.
    val plan = planActionClickRoute(
      node = TrailblazeNode(
        bounds = null,
        driverDetail = androidA11y(
          className = "android.widget.Button",
          text = "Submit",
          actions = listOf(ACTION_CLICK_NAME),
        ),
      ),
      longPress = false,
    )
    assertNull(plan, "No bounds means no identity to look up in the live tree.")
  }

  @Test
  fun `falls back to gesture when driver detail is not Android accessibility`() {
    // Defensive: the resolver could conceivably return a node whose detail is some other
    // sealed branch (Maestro, Compose, Web). Without AndroidAccessibility we have no
    // `actions` to check.
    val plan = planActionClickRoute(
      node = TrailblazeNode(
        bounds = TrailblazeNode.Bounds(0, 0, 100, 100),
        driverDetail = DriverNodeDetail.AndroidMaestro(),
      ),
      longPress = false,
    )
    assertNull(plan, "Non-Android-accessibility detail must skip the ACTION_CLICK route.")
  }

  @Test
  fun `falls back to gesture when the node does not advertise ACTION_CLICK`() {
    // A non-clickable text label resolved by selector — gesture path was correct before this
    // change and stays correct.
    val plan = planActionClickRoute(
      node = node(
        bounds = TrailblazeNode.Bounds(0, 0, 100, 100),
        detail = androidA11y(
          className = "android.widget.TextView",
          text = "Submit",
          actions = emptyList(),
        ),
      ),
      longPress = false,
    )
    assertNull(plan, "Without ACTION_CLICK in the action list the node has nothing to dispatch.")
  }

  @Test
  fun `falls back to gesture for editable fields`() {
    // EditText caret placement requires the touch offset; ACTION_CLICK only focuses the
    // field without honoring it, which breaks selection / cursor positioning.
    val plan = planActionClickRoute(
      node = node(
        bounds = TrailblazeNode.Bounds(0, 0, 100, 100),
        detail = androidA11y(
          className = "android.widget.EditText",
          text = "user@example.com",
          actions = listOf(ACTION_CLICK_NAME),
          isEditable = true,
        ),
      ),
      longPress = false,
    )
    assertNull(plan, "Editable fields need physical-touch semantics for caret placement.")
  }

  @Test
  fun `falls back to gesture for disabled clickable nodes`() {
    // A disabled-but-clickable node's `performAction(ACTION_CLICK)` returns false silently
    // and the gesture-path fallback is also a no-op. Sending these to gesture from the start
    // means the caller's normal timeout-retry mechanic surfaces "this element isn't
    // interactable right now" instead of producing a misleading "tap succeeded" outcome on a
    // node that didn't actually fire.
    val plan = planActionClickRoute(
      node = node(
        bounds = TrailblazeNode.Bounds(0, 0, 100, 100),
        detail = androidA11y(
          className = "android.widget.Button",
          text = "Submit",
          actions = listOf(ACTION_CLICK_NAME),
          isEnabled = false,
        ),
      ),
      longPress = false,
    )
    assertNull(plan, "Disabled nodes route to gesture so timeout-retry can surface the state.")
  }

  @Test
  fun `falls back to gesture for nodes that are not visible to the user`() {
    // A background button under an in-app overlay can match the selector but a real touch
    // wouldn't reach it. Gesture defers to the OS hit-test (z-order aware); ACTION_CLICK
    // would bypass the overlay and fire the hidden node directly.
    val plan = planActionClickRoute(
      node = node(
        bounds = TrailblazeNode.Bounds(0, 0, 100, 100),
        detail = androidA11y(
          className = "android.widget.Button",
          text = "Submit",
          actions = listOf(ACTION_CLICK_NAME),
          isVisibleToUser = false,
        ),
      ),
      longPress = false,
    )
    assertNull(plan, "Hidden background nodes must defer to the OS z-order via the gesture path.")
  }

  @Test
  fun `an ACTION_CLICK pin promotes a stateless checkable container the gate declines`() {
    // The shape that needs the pin: an option row inside a dropdown sheet, textless because the
    // label is on a child node, checkable, and publishing no state at all because the driver
    // reads `stateDescription` only from API 30 (below that the platform field doesn't exist and
    // Compose's backport lands in the node's extras, which the driver doesn't read). On such a
    // device the row is field-identical to a stateless wrapper whose handler lives elsewhere, so
    // no predicate over these fields can separate them — only the recording knows.
    val row = node(
      bounds = TrailblazeNode.Bounds(0, 1200, 1080, 1340),
      detail = androidA11y(
        className = "android.view.View",
        actions = listOf(ACTION_CLICK_NAME),
        isCheckable = true,
      ),
    )
    assertEquals(
      ActionClickPlan(
        bounds = TrailblazeNode.Bounds(0, 1200, 1080, 1340),
        className = "android.view.View",
        resourceId = null,
      ),
      planActionClickRoute(node = row, longPress = false, tapRoute = TapRouteOverride.ACTION_CLICK),
      "An ACTION_CLICK pin must reach past the leaf-vs-container judgement.",
    )
    assertNull(
      planActionClickRoute(node = row, longPress = false, tapRoute = null),
      "Negative control — the same node with no pin must still decline, so every unpinned tap " +
        "in every other recording keeps the route it has today.",
    )
  }

  @Test
  fun `a GESTURE pin declines a node the gate would otherwise route semantically`() {
    // The mirror-image shape: a row that publishes state (so the gate grants ACTION_CLICK) but
    // whose semantic click performs a different action than a real touch — an accordion header
    // whose ACTION_CLICK selects while only a touch expands the sub-options a later step taps.
    // Such a row publishes "Collapsed"/"Expanded" natively from API 30, so the pin is how a
    // recording holds its route when the same trail moves to a newer device.
    val row = node(
      bounds = TrailblazeNode.Bounds(0, 1100, 1080, 1240),
      detail = androidA11y(
        className = "android.view.View",
        actions = listOf(ACTION_CLICK_NAME),
        isCheckable = true,
        stateDescription = "Collapsed",
      ),
    )
    assertNull(
      planActionClickRoute(node = row, longPress = false, tapRoute = TapRouteOverride.GESTURE),
      "A GESTURE pin must decline even a node that satisfies every gate condition.",
    )
    assertEquals(
      ActionClickPlan(
        bounds = TrailblazeNode.Bounds(0, 1100, 1080, 1240),
        className = "android.view.View",
        resourceId = null,
      ),
      planActionClickRoute(node = row, longPress = false, tapRoute = null),
      "Negative control — without the pin this node routes semantically, which is what makes " +
        "the assertion above about the pin rather than about the node.",
    )
  }

  @Test
  fun `an ACTION_CLICK pin cannot dispatch an action the node cannot answer`() {
    // The pin overrides one judgement — leaf-vs-container — not the conditions that make
    // ACTION_CLICK dispatchable at all. A recording that pins a route the node can't honor gets
    // the gesture path rather than a dispatch that silently reports success.
    val pin = TapRouteOverride.ACTION_CLICK
    assertNull(
      planActionClickRoute(node = clickableNode(), longPress = true, tapRoute = pin),
      "Long-press has no ACTION_CLICK to pin.",
    )
    assertNull(
      planActionClickRoute(
        node = TrailblazeNode(bounds = null, driverDetail = androidA11y("android.view.View")),
        longPress = false,
        tapRoute = pin,
      ),
      "Without bounds there is no identity to find in the live tree.",
    )
    assertNull(
      planActionClickRoute(
        node = node(
          bounds = TrailblazeNode.Bounds(0, 0, 100, 100),
          detail = androidA11y(className = "android.view.View", actions = emptyList()),
        ),
        longPress = false,
        tapRoute = pin,
      ),
      "A node that doesn't advertise ACTION_CLICK has nothing to dispatch.",
    )
    assertNull(
      planActionClickRoute(
        node = node(
          bounds = TrailblazeNode.Bounds(0, 0, 100, 100),
          detail = androidA11y(
            className = "android.widget.EditText",
            text = "user@example.com",
            actions = listOf(ACTION_CLICK_NAME),
            isEditable = true,
          ),
        ),
        longPress = false,
        tapRoute = pin,
      ),
      "Editable fields still need the touch offset for caret placement.",
    )
    assertNull(
      planActionClickRoute(
        node = node(
          bounds = TrailblazeNode.Bounds(0, 0, 100, 100),
          detail = androidA11y(
            className = "android.widget.Button",
            text = "Submit",
            actions = listOf(ACTION_CLICK_NAME),
            isVisibleToUser = false,
          ),
        ),
        longPress = false,
        tapRoute = pin,
      ),
      "An occluded node must still defer to the OS z-order.",
    )
    assertNull(
      planActionClickRoute(
        node = node(
          bounds = TrailblazeNode.Bounds(0, 0, 100, 100),
          detail = androidA11y(
            className = "android.widget.Button",
            text = "Submit",
            actions = listOf(ACTION_CLICK_NAME),
            isEnabled = false,
          ),
        ),
        longPress = false,
        tapRoute = pin,
      ),
      "A disabled node's performAction returns false; route to gesture so retry surfaces it.",
    )
  }

  // --- Test helpers ---

  /**
   * Otherwise-routable node used to isolate single dis-qualifier conditions in the negative
   * tests. Carries `text` so the leaf-text gate is satisfied — each test that uses this
   * helper is asserting that **only** the condition under test is what flips the gate to
   * null, not the absence of any incidental field.
   */
  private fun clickableNode(): TrailblazeNode = node(
    bounds = TrailblazeNode.Bounds(0, 0, 100, 100),
    detail = androidA11y(
      className = "android.widget.Button",
      text = "Submit",
      actions = listOf(ACTION_CLICK_NAME),
    ),
  )

  private fun node(
    bounds: TrailblazeNode.Bounds,
    detail: DriverNodeDetail,
  ): TrailblazeNode = TrailblazeNode(bounds = bounds, driverDetail = detail)

  private fun androidA11y(
    className: String,
    resourceId: String? = null,
    text: String? = null,
    contentDescription: String? = null,
    actions: List<String> = emptyList(),
    isEnabled: Boolean = true,
    isEditable: Boolean = false,
    isVisibleToUser: Boolean = true,
    isCheckable: Boolean = false,
    isChecked: Boolean = false,
    stateDescription: String? = null,
  ): DriverNodeDetail.AndroidAccessibility = DriverNodeDetail.AndroidAccessibility(
    className = className,
    resourceId = resourceId,
    text = text,
    contentDescription = contentDescription,
    actions = actions,
    isEnabled = isEnabled,
    isEditable = isEditable,
    isVisibleToUser = isVisibleToUser,
    isCheckable = isCheckable,
    isChecked = isChecked,
    stateDescription = stateDescription,
  )
}
