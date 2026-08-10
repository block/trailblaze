package xyz.block.trailblaze.model

import kotlinx.serialization.Serializable

/**
 * Pins the dispatch route of a single recorded selector-resolved tap, overriding the route the
 * Android accessibility driver would otherwise choose for it.
 *
 * That choice is made from the resolved node's static fields alone, before the tap. It is a
 * heuristic for one question — is this an interactive leaf, or a container whose real click
 * handler lives elsewhere — and two rows that answer it differently can be field-identical on a
 * given API level. When they are, no global predicate separates them and the recording is the only
 * place that knows which route actually actuates the row.
 *
 * Leave unset unless a specific step has been measured to need it. Only the leaf-vs-container
 * judgement is overridable: the conditions that make `ACTION_CLICK` dispatchable at all (the node
 * advertises the action, is enabled, visible, and not editable; the tap is not a long-press) still
 * apply, so a pin that contradicts one of them routes to gesture rather than dispatching an action
 * the node cannot answer.
 */
@Serializable
enum class TapRouteOverride {
  /**
   * Dispatch via `AccessibilityNodeInfo.ACTION_CLICK`.
   *
   * For a textless clickable container whose handler IS reachable via `View.performClick()` — a
   * Compose `selectable` / `toggleable` row installs role, state and click handler on one
   * semantics node — but which publishes no state the route decision can see, so it reads as an
   * inert wrapper and goes to gesture, where the tap is absorbed with no effect on the screen.
   */
  ACTION_CLICK,

  /**
   * Dispatch via coordinate gesture.
   *
   * For the mirror-image shape: a container that does publish state, and so would be granted
   * `ACTION_CLICK`, but whose `ACTION_CLICK` performs a *different* action than a real touch — an
   * accordion row that selects its option semantically while only a touch expands it to reveal the
   * sub-options the recording goes on to tap.
   */
  GESTURE,
}
