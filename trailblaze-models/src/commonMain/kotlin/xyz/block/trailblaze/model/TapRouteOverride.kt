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
 * Trails reach it only through two Android-only tools: `android_accessibilityClick` pins
 * [ACTION_CLICK] and `android_gestureTap` pins [GESTURE]; the shared `tapOnElementBySelector`
 * carries no pin. Leave unset unless a specific step has been measured to need it.
 *
 * The two pins reach different amounts of the decision. [GESTURE] is unconditional: a touch needs
 * nothing from the node beyond its bounds, so a gesture-pinned tap touches the node's centre even
 * when it is disabled, editable, not visible to the user or does not advertise ACTION_CLICK.
 * [ACTION_CLICK] overrides only the leaf-vs-container judgement: the conditions that make
 * `ACTION_CLICK` dispatchable at all (the node advertises the action, is enabled, visible, and not
 * editable; the tap is not a long-press) still apply, so an ACTION_CLICK pin that contradicts one
 * of them routes to gesture rather than dispatching an action the node cannot answer.
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
   * Dispatch via coordinate gesture. Pinned by `android_gestureTap`.
   *
   * For the mirror-image shape: a node the gate grants `ACTION_CLICK`, because it carries its own
   * text or publishes state, but whose `ACTION_CLICK` performs a *different* action than a real
   * touch. A read-only field that shows its value, where the action only focuses it while a touch
   * opens the editor for it; an accordion row that selects its option semantically while only a
   * touch expands it to reveal the sub-options the recording goes on to tap.
   */
  GESTURE,
}
