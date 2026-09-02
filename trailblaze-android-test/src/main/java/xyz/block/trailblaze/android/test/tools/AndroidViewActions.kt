package xyz.block.trailblaze.android.test.tools

import android.view.View
import android.view.ViewGroup
import android.widget.Checkable
import android.widget.TextView
import androidx.test.espresso.NoMatchingViewException
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.ViewActions
import androidx.test.espresso.assertion.ViewAssertions
import androidx.test.espresso.matcher.ViewMatchers
import org.hamcrest.CoreMatchers.sameInstance
import xyz.block.trailblaze.android.test.AndroidTestTarget
import xyz.block.trailblaze.android.test.hierarchy.AndroidViewHierarchyCollector
import xyz.block.trailblaze.android.test.onMainThread

/**
 * Acts on a classic Android View that has already been chosen, by identity.
 *
 * Espresso is handed `sameInstance(view)` and never a re-description of the view's properties. The
 * selector already decided which node the trail meant, against the live view tree; re-describing
 * that node as `withText(...) and withId(...)` would make Espresso search again under its own
 * matching semantics, and it can land on a different view than the one that was observed.
 */
internal object AndroidViewActions {

  /**
   * Clicks the view by the same ROUTE the driver these recordings were made under would have
   * chosen: the click action itself for a labelled interactive element, a touch gesture at its
   * center for anything else.
   *
   * The route is what decides whether an overlay can swallow the tap. Espresso's `click()` injects
   * a real touch, so it goes wherever the framework's hit test sends it — through a scrim, onto a
   * sheet, onto whatever was drawn last. The accessibility driver instead dispatches `ACTION_CLICK`
   * to the node, which the view handles regardless of what is drawn on top of it, so a recording
   * made there can encode a tap the touch route cannot reproduce.
   *
   * Case 5380680 is that divergence end to end. Its second-to-last step taps the Keypad tab while
   * the Current sale sheet is open over the screen. `ACTION_CLICK` switches the tab underneath and
   * leaves the sheet up, which is why the trail's last step asserts a "Custom amount" line that
   * only exists on the sheet. The touch lands on the scrim instead, dismisses the sheet, and the
   * final assertion then runs on a bare keypad that never had that line on it.
   *
   * [grantsClickAction] is the same gate, so the two drivers make the same choice per node rather
   * than this one always taking the occlusion-immune path: a container whose `isClickable` is
   * incidental answers `ACTION_CLICK` with nothing, and must keep the gesture.
   */
  suspend fun click(target: AndroidTestTarget, view: View): String? {
    val (site, relocation) = view.nearestWith("clickable") { it.isClickable }
    if (onMainThread { grantsClickAction(site) }) {
      target.dispatchAndAwaitSettle { onMainThread { site.performClick() } }
    } else {
      perform(target, site, ViewActions.click())
    }
    return relocation
  }

  /**
   * Whether the accessibility driver's `ACTION_CLICK` gate would grant this view the action route.
   *
   * Mirrors `AccessibilityDeviceManager.planActionClickRoute`, in its terms: the node advertises
   * the click action, is enabled, is visible to the user, is not a text field, and either carries
   * its own accessibility label or is checkable and so publishes a state.
   *
   * Read off the View rather than off a merged label, because the framework's View-to-accessibility
   * projection is one node per important view — there is no merging step to reproduce here, unlike
   * the Compose side. A clickable row whose only text sits on a child `TextView` therefore has no
   * label of its own in EITHER tree, fails this gate in both, and keeps the gesture in both.
   *
   * The editable exclusion is the gate's: `ACTION_CLICK` focuses a field without honoring a touch
   * offset, so it cannot place a caret where the recording put one.
   */
  private fun grantsClickAction(view: View): Boolean {
    if (!view.isClickable || !view.isEnabled || !view.isShown) return false
    if (view.onCheckIsTextEditor()) return false
    if (view is Checkable) return true
    val ownText = (view as? TextView)?.text?.toString().orEmpty()
    return ownText.isNotBlank() || !view.contentDescription.isNullOrBlank()
  }

  /**
   * A press held past the long-press timeout, dispatched at the resolved view itself.
   *
   * No [nearestWith] relocation, unlike [click]: Espresso's `longClick` is a coordinate gesture at
   * the view's center, so the framework's own hit test decides who reacts — the same thing that
   * happens on the driver these recordings were made under. Hunting for an `isLongClickable`
   * ancestor would move the press somewhere the recording never touched.
   */
  suspend fun longClick(target: AndroidTestTarget, view: View) {
    perform(target, view, ViewActions.longClick())
  }

  suspend fun replaceText(target: AndroidTestTarget, view: View, value: String): String? {
    // replaceText, not clearText + typeText: a field that re-renders between the two calls drops
    // the typed value and leaves a validation error behind.
    val (site, relocation) = view.nearestWith("a text input") { it.onCheckIsTextEditor() }
    perform(target, site, ViewActions.replaceText(value))
    return relocation
  }

  fun assertDisplayed(target: AndroidTestTarget, view: View) {
    guardingDetachment {
      target.checkView(sameInstance(view), ViewAssertions.matches(ViewMatchers.isDisplayed()))
    }
  }

  private suspend fun perform(target: AndroidTestTarget, view: View, action: ViewAction) {
    target.dispatchAndAwaitSettle {
      guardingDetachment { target.performViewAction(sameInstance(view), action) }
    }
  }

  /**
   * Espresso reports a view it cannot find in the hierarchy the same way it reports a selector that
   * never matched, which is misleading here: this view WAS in the hierarchy a moment ago, by
   * identity. It is gone because the screen moved on.
   */
  private inline fun <R> guardingDetachment(block: () -> R): R =
    try {
      block()
    } catch (e: NoMatchingViewException) {
      throw IllegalStateException(
        "The element left the screen between observation and action.",
        e,
      )
    }

  /**
   * Finds the node that can actually take the action, starting from the one the selector resolved.
   *
   * Self, then ancestors, then descendants. Both relocations are ordinary app structure rather than
   * edge cases: a `TextView` inside a clickable row is the tappable thing, and a text-input wrapper
   * carrying the id or hint holds the editable child. The relocation is returned so the tool
   * surfaces it in its result — a silent jump to a different view is how a passing test ends up
   * asserting nothing.
   *
   * Runs on the UI thread: it reads `isClickable` / `onCheckIsTextEditor()` and walks
   * parent/child links, all of which a layout pass mutates.
   *
   * **Ancestors are a chain, descendants are a tree.** "Nearest ancestor that can take it" is
   * well-defined; "nearest descendant" is not, once two of them are equally near. The resolver
   * refuses an ambiguous selector rather than guessing, and picking the first of several equally
   * close descendants here would put that guess straight back — a non-clickable row with two
   * clickable children would tap an arbitrary one and report success. So: descend breadth-first,
   * stop at the shallowest depth that has any candidate, and fail if that depth has more than one.
   */
  private fun View.nearestWith(capability: String, has: (View) -> Boolean): Pair<View, String?> =
    onMainThread {
      if (has(this)) return@onMainThread this to null

      var ancestor = parent as? View
      while (ancestor != null) {
        if (has(ancestor)) {
          return@onMainThread ancestor to relocationNote(capability, "ancestor", ancestor)
        }
        ancestor = ancestor.parent as? View
      }

      val nearest = shallowestDescendantsWith(has)
      when (nearest.size) {
        0 ->
          // Nothing in the chain can take it. Act on the resolved view anyway and let Espresso's
          // own constraint failure name what it wanted — inventing an error here would hide that.
          this to null
        1 -> nearest[0] to relocationNote(capability, "descendant", nearest[0])
        else -> error(
          "${describeForLog()} is not $capability and ${nearest.size} of its descendants are, " +
            "all equally close: ${nearest.joinToString { it.describeForLog() }}. Refusing to pick " +
            "one — narrow the selector to the element the action should land on.",
        )
      }
    }

  /**
   * Every descendant satisfying [has] at the shallowest depth where any does, so a capable child
   * that itself contains a capable grandchild still resolves to the child.
   */
  private fun View.shallowestDescendantsWith(has: (View) -> Boolean): List<View> {
    var level = childrenOf(this)
    while (level.isNotEmpty()) {
      level.filter(has).takeIf { it.isNotEmpty() }?.let { return it }
      level = level.flatMap(::childrenOf)
    }
    return emptyList()
  }

  /**
   * A view's children as relocation candidates, filtered by exactly the predicate the hierarchy
   * collector captures with. Skipping an off-screen child also skips its subtree, which is what the
   * collector does — it stops descending at the same point.
   *
   * Ancestors need no such filter: a view that is `isShown` has no hidden ancestor.
   */
  private fun childrenOf(view: View): List<View> {
    val group = view as? ViewGroup ?: return emptyList()
    return (0 until group.childCount)
      .map { group.getChildAt(it) }
      .filter(AndroidViewHierarchyCollector::isOnScreen)
  }

  private fun View.relocationNote(capability: String, direction: String, site: View) =
    "${describeForLog()} is not $capability, so the action ran on its $direction " +
      "${site.describeForLog()}."

  private fun View.describeForLog(): String {
    val entry =
      if (id == View.NO_ID) {
        null
      } else {
        runCatching { resources.getResourceEntryName(id) }.getOrNull()
      }
    return if (entry != null) "${javaClass.simpleName}#$entry" else javaClass.simpleName
  }
}
