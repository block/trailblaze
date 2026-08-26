package xyz.block.trailblaze.android.accessibility

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import xyz.block.trailblaze.api.DriverNodeDetail
import xyz.block.trailblaze.api.TrailblazeNode

/**
 * Pure-function coverage of [planActionFocusRoute] — the gate that decides how a
 * selector-bearing [AccessibilityAction.InputText] gives its named field input focus before
 * typing.
 *
 * The gate must produce a dispatch plan for a focusable editable field, short-circuit a field
 * that already holds focus, and decline anything typing into would land text somewhere the trail
 * didn't name. The dispatch itself needs a live `AccessibilityNodeInfo`
 * ([TrailblazeAccessibilityService.focusByActionFocusOnBounds]) and stays an integration
 * concern; this test pins only the upstream decision.
 */
class PlanActionFocusRouteTest {

  @Test
  fun `dispatches ACTION_FOCUS for an enabled unfocused editable field`() {
    val plan = planActionFocusRoute(
      node(
        bounds = TrailblazeNode.Bounds(40, 300, 1040, 400),
        detail = androidA11y(
          className = "android.widget.EditText",
          resourceId = "com.example.app:id/password",
          isEditable = true,
          actions = listOf(ACTION_FOCUS_NAME, "ACTION_SET_TEXT"),
        ),
      ),
    )

    assertEquals(
      FocusPlan.DispatchActionFocus(
        bounds = TrailblazeNode.Bounds(40, 300, 1040, 400),
        className = "android.widget.EditText",
        resourceId = "com.example.app:id/password",
      ),
      plan,
      "A focusable editable field must produce a plan carrying the resolved node's identity.",
    )
  }

  @Test
  fun `short-circuits a field that already holds input focus`() {
    // The platform swaps ACTION_FOCUS for ACTION_CLEAR_FOCUS once a view holds focus, so the
    // field a previous step focused advertises no ACTION_FOCUS. Checking `isFocused` first is
    // what keeps re-naming that same field from reading as un-focusable.
    val plan = planActionFocusRoute(
      node(
        bounds = TrailblazeNode.Bounds(40, 300, 1040, 400),
        detail = androidA11y(
          className = "android.widget.EditText",
          isEditable = true,
          isFocused = true,
          actions = listOf("ACTION_CLEAR_FOCUS", "ACTION_SET_TEXT"),
        ),
      ),
    )

    assertEquals(
      FocusPlan.AlreadyFocused,
      plan,
      "An already-focused field needs no dispatch — the focused-node input path finds it.",
    )
  }

  @Test
  fun `declines a non-editable node so the text cannot silently land elsewhere`() {
    // The motivating misuse: a selector aimed at the field's label or the row around it. A
    // non-editable node answers ACTION_FOCUS without becoming the input target, so the text
    // would go to whichever field was already focused while the step reported success.
    val plan = planActionFocusRoute(
      node(
        bounds = TrailblazeNode.Bounds(40, 240, 400, 290),
        detail = androidA11y(
          className = "android.widget.TextView",
          text = "Password",
          actions = listOf(ACTION_FOCUS_NAME),
        ),
      ),
    )

    val declined = assertIs<FocusPlan.NotFocusable>(plan)
    assertTrue(
      "android.widget.TextView" in declined.reason,
      "The failure must name the class that was matched so the selector can be fixed: " +
        declined.reason,
    )
  }

  @Test
  fun `declines a disabled field`() {
    // A disabled field's requestFocus() returns false, so the focus dispatch would report a miss
    // — but failing here names the real cause instead of "no live node matched".
    val plan = planActionFocusRoute(
      node(
        bounds = TrailblazeNode.Bounds(40, 300, 1040, 400),
        detail = androidA11y(
          className = "android.widget.EditText",
          isEditable = true,
          isEnabled = false,
          actions = listOf(ACTION_FOCUS_NAME),
        ),
      ),
    )

    assertIs<FocusPlan.NotFocusable>(plan)
  }

  @Test
  fun `declines an editable field that advertises no focus action`() {
    val plan = planActionFocusRoute(
      node(
        bounds = TrailblazeNode.Bounds(40, 300, 1040, 400),
        detail = androidA11y(
          className = "android.widget.EditText",
          isEditable = true,
          actions = listOf("ACTION_SET_TEXT"),
        ),
      ),
    )

    assertIs<FocusPlan.NotFocusable>(plan)
  }

  @Test
  fun `declines an editable field with no bounds because the live lookup is bounds-keyed`() {
    val plan = planActionFocusRoute(
      TrailblazeNode(
        bounds = null,
        driverDetail = androidA11y(
          className = "android.widget.EditText",
          isEditable = true,
          actions = listOf(ACTION_FOCUS_NAME),
        ),
      ),
    )

    assertIs<FocusPlan.NotFocusable>(plan)
  }

  @Test
  fun `declines a node captured by another driver`() {
    // The accessibility fields this gate reads only exist on the accessibility capture. A node
    // from any other driver's tree can't be judged, so it declines rather than assuming.
    val plan = planActionFocusRoute(
      TrailblazeNode(
        bounds = TrailblazeNode.Bounds(0, 0, 10, 10),
        driverDetail = DriverNodeDetail.AndroidMaestro(resourceId = "com.example.app:id/password"),
      ),
    )

    assertIs<FocusPlan.NotFocusable>(plan)
  }

  // --- Test helpers ---

  private fun node(
    bounds: TrailblazeNode.Bounds,
    detail: DriverNodeDetail,
  ): TrailblazeNode = TrailblazeNode(bounds = bounds, driverDetail = detail)

  private fun androidA11y(
    className: String,
    resourceId: String? = null,
    text: String? = null,
    actions: List<String> = emptyList(),
    isEnabled: Boolean = true,
    isEditable: Boolean = false,
    isFocused: Boolean = false,
  ): DriverNodeDetail.AndroidAccessibility = DriverNodeDetail.AndroidAccessibility(
    className = className,
    resourceId = resourceId,
    text = text,
    actions = actions,
    isEnabled = isEnabled,
    isEditable = isEditable,
    isFocused = isFocused,
  )
}
