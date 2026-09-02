package xyz.block.trailblaze.android.test.tools

import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.performSemanticsAction
import xyz.block.trailblaze.android.test.AndroidTestTarget
import xyz.block.trailblaze.android.test.onMainThread

/**
 * Advances whichever scrollable region of the screen is showing the content, one step at a time.
 *
 * A lazy list only puts its visible window in the tree, so an element further down does not exist
 * for a selector to match — no amount of waiting produces it. Scrolling is the only way to bring it
 * into being, which is why it is a distinct primitive rather than something the resolver retries
 * into.
 *
 * Containers are read from the live UI (Espresso's view tree, the Compose rule's semantics) rather
 * than from a captured Trailblaze hierarchy: a scroll container is usually an anonymous layout with
 * no text, tag or id, so it is exactly the kind of node the hierarchy has no reason to describe.
 */
internal object AndroidScrollActions {

  /**
   * Scrolls the screen's main scrollable region forward by roughly one screenful.
   *
   * Returns false when nothing on screen can scroll any further, which is the caller's signal that
   * the content is exhausted and a missing element is missing for some other reason.
   */
  suspend fun scrollForward(target: AndroidTestTarget): Boolean {
    val compose = target.largestScrollableComposeNode()
    val view = target.largestScrollableView()
    // Largest wins across both halves. On a mixed screen the Compose list inside a View container
    // and the container itself can both report as scrollable; the one actually showing the content
    // is the bigger of the two, and scrolling an outer wrapper that merely *can* move would drag
    // the whole screen instead of the list the trail is reading.
    val composeArea = compose?.area() ?: 0
    val viewArea = view?.area() ?: 0
    return when {
      composeArea == 0L && viewArea == 0L -> false
      composeArea >= viewArea -> target.scroll(compose!!)
      else -> target.scroll(view!!)
    }
  }

  /**
   * What the screen offered when no container could be scrolled.
   *
   * "Nothing scrolled" is not self-explanatory on a screen that visibly has a list: the container
   * may be a Compose scrollable whose semantics say it is already at its end, or a View that
   * reports it cannot scroll. Naming which of those it was is the difference between a fixable
   * report and a re-run with print statements.
   */
  fun describeCandidates(target: AndroidTestTarget): String {
    val composeScrollables =
      target.composeTestRule
        ?.onAllNodes(SemanticsMatcher("has scroll action") {
          it.config.contains(SemanticsActions.ScrollBy)
        }, useUnmergedTree = true)
        ?.fetchSemanticsNodes()
        .orEmpty()
    val scrollableViews = target.scrollableViews()
    return "Compose nodes with a scroll action: ${composeScrollables.size}" +
      composeScrollables.joinToString("") { " [id=${it.id} ${it.boundsInWindow}]" } +
      "; Views that report they can scroll: ${scrollableViews.size}" +
      scrollableViews.joinToString("") { " [${it.javaClass.simpleName} ${it.width}x${it.height}]" }
  }

  private suspend fun AndroidTestTarget.scroll(node: SemanticsNode): Boolean {
    val delta = node.boundsInWindow.height * SCROLL_FRACTION.toFloat()
    dispatchAndAwaitSettle {
      requireNotNull(composeTestRule)
        .onNode(SemanticsMatcher("semantics id is ${node.id}") { it.id == node.id }, true)
        .performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, delta) }
    }
    return true
  }

  private suspend fun AndroidTestTarget.scroll(view: View): Boolean {
    val delta = (view.height * SCROLL_FRACTION).toInt()
    dispatchAndAwaitSettle {
      onMainThread {
        // AbsListView tracks its own scroll offset and ignores View.scrollBy; every other
        // scrollable container in the framework, RecyclerView included, overrides scrollBy.
        if (view is AbsListView) view.scrollListBy(delta) else view.scrollBy(0, delta)
      }
    }
    return true
  }

  private fun AndroidTestTarget.largestScrollableComposeNode(): SemanticsNode? {
    val rule = composeTestRule ?: return null
    return rule
      .onAllNodes(SemanticsMatcher("can scroll forward", ::canScrollForward), useUnmergedTree = true)
      .fetchSemanticsNodes()
      .maxByOrNull { it.area() }
  }

  private fun canScrollForward(node: SemanticsNode): Boolean {
    if (!node.config.contains(SemanticsActions.ScrollBy)) return false
    val range =
      node.config.getOrElseNullable(SemanticsProperties.VerticalScrollAxisRange) { null }
    if (range == null) {
      // A container declaring only a HORIZONTAL range is a carousel, and this tool scrolls down.
      // Without this it wins the largest-scrollable contest on any screen with a wide carousel and
      // then absorbs every scroll as a no-op vertical delta, so the list below the fold never
      // moves and the tool reports having scrolled 25 times.
      if (node.config.contains(SemanticsProperties.HorizontalScrollAxisRange)) return false
      // Otherwise: no declared range means the container never reports how far it can go — a lazy
      // list of unknown length is the common case. Treat it as scrollable and let the caller's
      // scroll cap stop the loop, rather than refusing to scroll something that plainly can.
      return true
    }
    return range.value() < range.maxValue() - SCROLL_EPSILON
  }

  private fun AndroidTestTarget.largestScrollableView(): View? =
    scrollableViews().maxByOrNull { it.area() }

  /**
   * Every shown View under the activity's decor that reports it can still scroll down.
   *
   * One walk shared by the chooser and the failure report, so the container that gets scrolled is
   * always one of the containers the report names. Two walks would be free to disagree, and the
   * disagreement would only ever show up in the message explaining why nothing scrolled.
   */
  private fun AndroidTestTarget.scrollableViews(): List<View> {
    // Resolved off the main thread on purpose: a host's activityProvider is free to reach for the
    // resumed Activity through `runOnMainSync`, which throws if it is already on the main thread.
    val decorView = currentActivity().window.decorView
    return onMainThread {
      val found = mutableListOf<View>()
      fun visit(view: View) {
        if (view.isShown && view.canScrollVertically(1)) found += view
        if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i))
      }
      visit(decorView)
      found
    }
  }

  private fun SemanticsNode.area(): Long =
    boundsInWindow.width.toLong() * boundsInWindow.height.toLong()

  private fun View.area(): Long = width.toLong() * height.toLong()

  /**
   * Less than a full screenful so a row straddling the fold lands fully on screen rather than
   * jumping from just-below to just-above it.
   */
  private const val SCROLL_FRACTION = 0.75

  /** A list scrolled to its end can report a fractional pixel short of its own maximum. */
  private const val SCROLL_EPSILON = 1f
}
