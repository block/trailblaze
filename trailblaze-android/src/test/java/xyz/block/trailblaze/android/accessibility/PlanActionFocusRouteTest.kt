package xyz.block.trailblaze.android.accessibility

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import xyz.block.trailblaze.api.DriverNodeDetail
import xyz.block.trailblaze.api.TrailblazeNode

/**
 * Pure-function coverage of [planActionFocusRoute] and [planSelectorInputFocus] — the gates that
 * decide where a selector-bearing [AccessibilityAction.InputText] types after tapping its selector.
 *
 * A named editable field is focused directly and never passed over for another field. A selector
 * that names a label, or a hint the focus hid, types into the field the tap focused, as the
 * tap-then-type pair it replaces did. With no field focused, the step declines. The dispatch itself needs a live `AccessibilityNodeInfo`
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
  fun `declines to focus a non-editable node`() {
    // A non-editable node answers ACTION_FOCUS without becoming the input target, so there is
    // nothing to dispatch. Whether a field the tap focused takes the text instead is
    // planSelectorInputFocus's call, covered below.
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

    val declined = assertIs<FocusPlan.NotEditable>(plan)
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

    assertIs<FocusPlan.Declined>(plan)
  }

  // --- planSelectorInputFocus: where the text goes after the tap ---

  @Test
  fun `types into the field the tap focused when the selector names its label`() {
    // The common recorded shape: the selector names the "Search" label drawn over the field, and
    // tapping it focuses the field. The replaced tap-then-type pair typed there, so this must too.
    val label = node(bounds = LABEL_BOUNDS, detail = androidA11y("android.widget.TextView", text = "Search"))
    val field = focusedField()

    val plan = planSelectorInputFocus(resolved = label, root = screen(label, field), captureComplete = true)

    val typed = assertIs<FocusPlan.FocusedByTap>(plan)
    assertEquals(field, typed.field)
    assertTrue("android.widget.TextView" in typed.because, typed.because)
  }

  @Test
  fun `types into the field the tap focused when focusing hid the matched hint`() {
    // A hint selector matches until the field takes focus and the hint disappears, so after the
    // tap nothing matches — yet the field it named is the one holding focus.
    val field = focusedField()

    val plan = planSelectorInputFocus(resolved = null, root = screen(field), captureComplete = true)

    assertEquals(field, assertIs<FocusPlan.FocusedByTap>(plan).field)
  }

  @Test
  fun `does not read no match in a partial capture as a hidden hint`() {
    // A capture that dropped a subtree may have dropped the named field; the focused field it
    // kept could be a different one, so the step polls again instead of typing there.
    val plan = planSelectorInputFocus(resolved = null, root = screen(focusedField()), captureComplete = false)

    val declined = assertIs<FocusPlan.Declined>(plan)
    assertTrue("partial capture" in declined.reason, declined.reason)
  }

  @Test
  fun `declines when the tap left no editable field focused`() {
    // A focused node that isn't editable (the tapped button itself) is not somewhere to type.
    val label = node(bounds = LABEL_BOUNDS, detail = androidA11y("android.widget.TextView", text = "Search"))
    val focusedButton = node(
      bounds = FIELD_BOUNDS,
      detail = androidA11y("android.widget.Button", isFocused = true),
    )

    val plan = planSelectorInputFocus(resolved = label, root = screen(label, focusedButton), captureComplete = true)

    val declined = assertIs<FocusPlan.Declined>(plan)
    assertTrue("no editable field holds input focus" in declined.reason, declined.reason)
  }

  @Test
  fun `declines with no tree to look in`() {
    assertIs<FocusPlan.Declined>(planSelectorInputFocus(resolved = null, root = null, captureComplete = true))
  }

  @Test
  fun `focuses the named field rather than one that already holds focus`() {
    // Naming an editable field must put the text there, even when a different field has focus.
    val named = node(
      bounds = FIELD_BOUNDS,
      detail = androidA11y(
        "android.widget.EditText",
        resourceId = "com.example.app:id/name",
        isEditable = true,
        actions = listOf(ACTION_FOCUS_NAME),
      ),
    )
    val other = focusedField(bounds = OTHER_FIELD_BOUNDS)

    val plan = planSelectorInputFocus(resolved = named, root = screen(named, other), captureComplete = true)

    assertEquals(
      FocusPlan.DispatchActionFocus(FIELD_BOUNDS, "android.widget.EditText", "com.example.app:id/name"),
      plan,
    )
  }

  @Test
  fun `a named editable field that cannot take focus does not fall back to another field`() {
    // The selector named this field; typing into the one that happens to be focused would report
    // success with the text in the wrong field.
    val disabled = node(
      bounds = FIELD_BOUNDS,
      detail = androidA11y(
        "android.widget.EditText",
        isEditable = true,
        isEnabled = false,
        actions = listOf(ACTION_FOCUS_NAME),
      ),
    )
    val other = focusedField(bounds = OTHER_FIELD_BOUNDS)

    val plan = planSelectorInputFocus(resolved = disabled, root = screen(disabled, other), captureComplete = true)

    assertIs<FocusPlan.NotFocusable>(plan)
  }

  // --- Test helpers ---

  private fun focusedField(bounds: TrailblazeNode.Bounds = FIELD_BOUNDS): TrailblazeNode = node(
    bounds = bounds,
    detail = androidA11y(
      "android.widget.EditText",
      isEditable = true,
      isFocused = true,
      actions = listOf("ACTION_CLEAR_FOCUS", "ACTION_SET_TEXT"),
    ),
  )

  /** A window whose field sits one container down, so the focused-field lookup has to walk. */
  private fun screen(vararg nodes: TrailblazeNode): TrailblazeNode = TrailblazeNode(
    bounds = TrailblazeNode.Bounds(0, 0, 1080, 1920),
    driverDetail = androidA11y("android.widget.FrameLayout"),
    children = listOf(
      TrailblazeNode(
        bounds = TrailblazeNode.Bounds(0, 0, 1080, 1920),
        driverDetail = androidA11y("android.widget.LinearLayout"),
        children = nodes.toList(),
      ),
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

  private companion object {
    val LABEL_BOUNDS = TrailblazeNode.Bounds(40, 240, 400, 290)
    val FIELD_BOUNDS = TrailblazeNode.Bounds(40, 300, 1040, 400)
    val OTHER_FIELD_BOUNDS = TrailblazeNode.Bounds(40, 500, 1040, 600)
  }
}
