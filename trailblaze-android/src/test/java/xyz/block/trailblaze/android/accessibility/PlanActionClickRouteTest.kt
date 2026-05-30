package xyz.block.trailblaze.android.accessibility

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import xyz.block.trailblaze.api.DriverNodeDetail
import xyz.block.trailblaze.api.TrailblazeNode

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
  ): DriverNodeDetail.AndroidAccessibility = DriverNodeDetail.AndroidAccessibility(
    className = className,
    resourceId = resourceId,
    text = text,
    contentDescription = contentDescription,
    actions = actions,
    isEnabled = isEnabled,
    isEditable = isEditable,
    isVisibleToUser = isVisibleToUser,
  )
}
