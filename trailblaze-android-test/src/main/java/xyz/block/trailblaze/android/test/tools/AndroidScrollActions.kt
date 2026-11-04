package xyz.block.trailblaze.android.test.tools

import androidx.test.platform.app.InstrumentationRegistry
import android.view.MotionEvent
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import xyz.block.trailblaze.android.test.AndroidTestTarget
import xyz.block.trailblaze.android.test.hierarchy.AndroidHybridHierarchyCollector
import xyz.block.trailblaze.android.test.isIdleTimeout
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
  suspend fun scrollForward(target: AndroidTestTarget): Boolean = scroll(target, forward = true)

  /**
   * Scrolls the screen's main scrollable region back by roughly one screenful: what a finger
   * swiping DOWN does. Returns false when nothing on screen can scroll back, which for a list
   * already at its top is the expected answer.
   */
  suspend fun scrollBackward(target: AndroidTestTarget): Boolean = scroll(target, forward = false)

  private suspend fun scroll(target: AndroidTestTarget, forward: Boolean): Boolean {
    // The window a finger across the screen centre lands on: under a sheet or dialog, a page
    // behind it can scroll too, and moving it would leave what the trail is reading in place.
    val window = frontmostWindowAtCentre(target)
    val compose = target.largestScrollableComposeNode(window, forward)
    val view = target.largestScrollableView(window, forward)
    // Largest wins across both halves. On a mixed screen the Compose list inside a View container
    // and the container itself can both report as scrollable; the one actually showing the content
    // is the bigger of the two, and scrolling an outer wrapper that merely *can* move would drag
    // the whole screen instead of the list the trail is reading.
    val composeArea = compose?.area() ?: 0
    val viewArea = view?.area() ?: 0
    return when {
      composeArea == 0L && viewArea == 0L -> false
      composeArea >= viewArea -> target.scroll(compose!!, forward)
      else -> target.scroll(view!!, forward)
    }
  }

  /**
   * A real horizontal finger swipe across the middle of the screen: LEFT moves the finger right to
   * left, which pages a pager forward.
   *
   * A gesture rather than a programmatic scroll, because what a sideways swipe does is up to the
   * app — a pager snaps a page, a carousel flings, a row reveals actions — and only a real touch
   * gets the app's own answer. It goes to the frontmost window, because a dialog or popup in front
   * is what the finger would land on, not the screen behind it: through that window's largest
   * Compose root, which hands it on to any View (a `ViewPager2` in an `AndroidView`) underneath,
   * or as a raw touch across the window when it has no Compose root.
   */
  suspend fun swipeHorizontally(target: AndroidTestTarget, towardsLeft: Boolean) {
    val window = frontmostWindowAtCentre(target)
    val rule = target.composeTestRule
    // composeRootsIn, not the rule's own query, so a screen that never goes idle still yields a root.
    val root = rule?.let { target.composeRootsIn(window).maxByOrNull { it.area() } }
    val rawSwipe: suspend () -> Unit = {
      val (left, top, width, height) = root?.let {
        listOf(it.positionOnScreen.x, it.positionOnScreen.y, it.size.width.toFloat(), it.size.height.toFloat())
      } ?: window.screenBounds()
      val y = top + height / 2f
      val right = left + width * (1 - SWIPE_EDGE)
      val leftEdge = left + width * SWIPE_EDGE
      injectSwipe(
        target,
        from = androidx.compose.ui.geometry.Offset(if (towardsLeft) right else leftEdge, y),
        to = androidx.compose.ui.geometry.Offset(if (towardsLeft) leftEdge else right, y),
      )
    }
    if (rule == null || root == null) return rawSwipe()
    // A rule action synchronizes BEFORE it acts, so an idle timeout means the swipe never happened;
    // it is then injected as a raw touch, like a tap on such a screen.
    runCatching {
      target.dispatchAndAwaitSettle {
        rule.onNode(SemanticsMatcher("semantics id is ${root.id}") { it.id == root.id }, true)
          .performTouchInput { if (towardsLeft) swipeLeft() else swipeRight() }
      }
    }.onFailure {
      if (!it.isIdleTimeout()) throw it
      rawSwipe()
    }
  }

  /**
   * This app's frontmost window, or the frontmost one containing screen point ([x], [y]) when
   * given. A dialog or popup over the screen is where a touch lands, so the window behind it is
   * never the one to act on, even when only that one has a Compose root.
   */
  private fun frontmostWindow(target: AndroidTestTarget, x: Int? = null, y: Int? = null): View {
    val windows = AndroidHybridHierarchyCollector.windowRoots(target.currentActivity())
    return onMainThread {
      windows.lastOrNull { x == null || y == null || it.containsScreenPoint(x, y) }
    } ?: windows.last()
  }

  /** [frontmostWindow] at the centre of the activity's window, where a screen-wide swipe crosses. */
  private fun frontmostWindowAtCentre(target: AndroidTestTarget): View {
    val (left, top, width, height) = target.currentActivity().window.decorView.screenBounds()
    return frontmostWindow(target, (left + width / 2).toInt(), (top + height / 2).toInt())
  }

  /** Left, top, width and height on screen. */
  private fun View.screenBounds(): List<Float> = onMainThread {
    val origin = IntArray(2).also(::getLocationOnScreen)
    listOf(origin[0].toFloat(), origin[1].toFloat(), width.toFloat(), height.toFloat())
  }

  /** A down, moves and up from [from] to [to], with no Compose or Espresso synchronization. */
  private suspend fun injectSwipe(
    target: AndroidTestTarget,
    from: androidx.compose.ui.geometry.Offset,
    to: androidx.compose.ui.geometry.Offset,
  ) {
    target.dispatchAndAwaitSettle {
      val instrumentation = InstrumentationRegistry.getInstrumentation()
      val downAt = SystemClock.uptimeMillis()
      for (step in 0..SWIPE_STEPS) {
        val fraction = step.toFloat() / SWIPE_STEPS
        val action = when (step) {
          0 -> MotionEvent.ACTION_DOWN
          SWIPE_STEPS -> MotionEvent.ACTION_UP
          else -> MotionEvent.ACTION_MOVE
        }
        val at = downAt + SWIPE_MS * step / SWIPE_STEPS
        val x = from.x + (to.x - from.x) * fraction
        val y = from.y + (to.y - from.y) * fraction
        val event = MotionEvent.obtain(downAt, at, action, x, y, 0)
        try {
          instrumentation.sendPointerSync(event)
        } finally {
          event.recycle()
        }
      }
    }
  }

  /**
   * Scrolls the innermost vertical scroller containing screen point ([x], [y]) forward so the
   * point lands at the scroller's vertical centre, as far as its content allows — but only when
   * the point sits past [CENTRE_GATE] of the scroller's height. That is the accessibility driver's
   * rule: a target above the gate counts as centred, and a correction never scrolls back, so an
   * element the trail already sees is never moved off screen. Returns false when nothing moved.
   */
  suspend fun centreVertically(target: AndroidTestTarget, x: Int, y: Int): Boolean {
    // Only the window the point shows in: a scroller in an activity behind a popup also contains
    // the point, and moving it would leave the target where it was.
    val window = frontmostWindow(target, x, y)
    val compose = target.composeNodes(target.composeRootsIn(window))
      .filter { scrollsVertically(it) && it.containsScreenPoint(x, y) }
      .minByOrNull { it.area() }
    val view = onMainThread {
      val found = mutableListOf<View>()
      fun visit(v: View) {
        if (v.isShown && v !is ViewRootForTest &&
          (v.canScrollVertically(1) || v.canScrollVertically(-1)) && v.containsScreenPoint(x, y)
        ) {
          found += v
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) visit(v.getChildAt(i))
      }
      visit(window)
      found.minByOrNull { it.area() }
    }
    // Innermost wins: the list the element sits in, not a page that also scrolls around it.
    val useCompose = compose != null && (view == null || compose.area() <= view.area())
    return when {
      useCompose -> {
        val node = compose!!
        val top = node.positionOnScreen.y
        if (y - top < node.size.height * CENTRE_GATE) return false
        target.scrollBy(node, y - (top + node.size.height / 2f))
        true
      }
      view != null -> {
        val origin = IntArray(2).also { onMainThread { view.getLocationOnScreen(it) } }
        if (y - origin[1] < view.height * CENTRE_GATE) return false
        val delta = y - (origin[1] + view.height / 2)
        target.dispatchAndAwaitSettle {
          onMainThread { if (view is AbsListView) view.scrollListBy(delta) else view.scrollBy(0, delta) }
        }
        true
      }
      else -> false
    }
  }

  private fun scrollsVertically(node: SemanticsNode): Boolean =
    node.config.contains(SemanticsActions.ScrollBy) &&
      (node.config.contains(SemanticsProperties.VerticalScrollAxisRange) ||
        !node.config.contains(SemanticsProperties.HorizontalScrollAxisRange))

  private fun SemanticsNode.containsScreenPoint(x: Int, y: Int): Boolean {
    val origin = positionOnScreen
    return x >= origin.x && y >= origin.y && x <= origin.x + size.width && y <= origin.y + size.height
  }

  private fun View.containsScreenPoint(x: Int, y: Int): Boolean {
    val origin = IntArray(2).also(::getLocationOnScreen)
    return x >= origin[0] && y >= origin[1] && x <= origin[0] + width && y <= origin[1] + height
  }

  /**
   * A real tap at a screen point, for a node matched in the accessibility tree that no
   * live View or Compose node in this process owns. The accessibility driver taps every node this
   * way, at its centre. Injected through the largest Compose root containing the point in the
   * frontmost window at the point, which hands it to any View underneath; where no Compose root
   * there covers it (a View-only window), as a plain touch into this app's window.
   */
  suspend fun tapScreenPoint(target: AndroidTestTarget, x: Int, y: Int): Boolean {
    val rule = target.composeTestRule
    // composeRoots, not the rule's own query: that throws on a View-only screen, before the plain
    // touch below could tap it.
    val root = rule?.let {
      target.composeRootsIn(frontmostWindow(target, x, y)).filter { it.containsScreenPoint(x, y) }
    }?.maxByOrNull { it.area() }
    if (rule == null || root == null) {
      injectTap(target, androidx.compose.ui.geometry.Offset(x.toFloat(), y.toFloat()))
      return true
    }
    val origin = root.positionOnScreen
    // A rule action synchronizes BEFORE it acts, so an idle timeout here means the tap never
    // happened; the settle after it is absorbed by dispatchAndAwaitSettle. Same raw-touch fallback
    // as a Compose click on a screen that never goes idle.
    runCatching {
      target.dispatchAndAwaitSettle {
        rule.onNode(SemanticsMatcher("semantics id is ${root.id}") { it.id == root.id }, true)
          .performTouchInput { click(androidx.compose.ui.geometry.Offset(x - origin.x, y - origin.y)) }
      }
    }.onFailure {
      if (!it.isIdleTimeout()) throw it
      injectTap(target, androidx.compose.ui.geometry.Offset(x.toFloat(), y.toFloat()))
    }
    return true
  }

  /**
   * A plain down/up touch at [screenPoint] into this app's windows, with no Compose or Espresso
   * synchronization before it: the tap for a screen that never goes idle.
   */
  suspend fun injectTap(target: AndroidTestTarget, screenPoint: androidx.compose.ui.geometry.Offset) {
    target.dispatchAndAwaitSettle {
      val instrumentation = InstrumentationRegistry.getInstrumentation()
      val downAt = SystemClock.uptimeMillis()
      for ((action, at) in listOf(MotionEvent.ACTION_DOWN to downAt, MotionEvent.ACTION_UP to downAt + TAP_MS)) {
        val event = MotionEvent.obtain(downAt, at, action, screenPoint.x, screenPoint.y, 0)
        try {
          instrumentation.sendPointerSync(event)
        } finally {
          event.recycle()
        }
      }
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
    val scrollableViews = scrollableViews(frontmostWindowAtCentre(target))
    return "Compose nodes with a scroll action: ${composeScrollables.size}" +
      composeScrollables.joinToString("") { " [id=${it.id} ${it.boundsInWindow}]" } +
      "; Views that report they can scroll: ${scrollableViews.size}" +
      scrollableViews.joinToString("") { " [${it.javaClass.simpleName} ${it.width}x${it.height}]" }
  }

  private suspend fun AndroidTestTarget.scroll(node: SemanticsNode, forward: Boolean = true): Boolean {
    scrollBy(node, node.boundsInWindow.height * SCROLL_FRACTION.toFloat() * if (forward) 1 else -1)
    return true
  }

  /**
   * Scrolls [node] by [delta] through the Compose rule, or through the node's own `ScrollBy`
   * action when the screen never goes idle. The rule waits for idle before it acts, so a timeout
   * means nothing scrolled, as on a list that sits on such a screen. The action is the one the rule
   * would have invoked.
   */
  private suspend fun AndroidTestTarget.scrollBy(node: SemanticsNode, delta: Float) {
    val rule = requireNotNull(composeTestRule)
    val dispatched = runCatching {
      dispatchAndAwaitSettle {
        rule.onNode(SemanticsMatcher("semantics id is ${node.id}") { it.id == node.id }, true)
          .performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, delta) }
      }
    }
    val failure = dispatched.exceptionOrNull() ?: return
    if (!failure.isIdleTimeout()) throw failure
    val scrollBy = node.config.getOrNull(SemanticsActions.ScrollBy)?.action ?: throw failure
    dispatchAndAwaitSettle { onMainThread { scrollBy(0f, delta) } }
  }

  /**
   * Every node in the unmerged Compose tree, read through [AndroidTestTarget.composeRoots] so a
   * screen that never goes idle still answers rather than timing out.
   */
  private fun AndroidTestTarget.composeNodes(roots: List<SemanticsNode>? = null): List<SemanticsNode> {
    if (composeTestRule == null) return emptyList()
    val nodes = mutableListOf<SemanticsNode>()
    fun visit(node: SemanticsNode) {
      nodes += node
      node.children.forEach(::visit)
    }
    (roots ?: composeRoots()).forEach(::visit)
    return nodes
  }

  private suspend fun AndroidTestTarget.scroll(view: View, forward: Boolean = true): Boolean {
    val delta = (view.height * SCROLL_FRACTION).toInt() * if (forward) 1 else -1
    dispatchAndAwaitSettle {
      onMainThread {
        // AbsListView tracks its own scroll offset and ignores View.scrollBy; every other
        // scrollable container in the framework, RecyclerView included, overrides scrollBy.
        if (view is AbsListView) view.scrollListBy(delta) else view.scrollBy(0, delta)
      }
    }
    return true
  }

  private fun AndroidTestTarget.largestScrollableComposeNode(window: View, forward: Boolean = true): SemanticsNode? =
    composeNodes(composeRootsIn(window))
      .filter { if (forward) canScrollForward(it) else canScrollBackward(it) }
      .maxByOrNull { it.area() }

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

  /** [canScrollForward]'s mirror: a vertical scroller that is not already at its start. */
  private fun canScrollBackward(node: SemanticsNode): Boolean {
    if (!node.config.contains(SemanticsActions.ScrollBy)) return false
    val range =
      node.config.getOrElseNullable(SemanticsProperties.VerticalScrollAxisRange) { null }
    if (range == null) return !node.config.contains(SemanticsProperties.HorizontalScrollAxisRange)
    return range.value() > SCROLL_EPSILON
  }

  private fun AndroidTestTarget.largestScrollableView(window: View, forward: Boolean = true): View? =
    scrollableViews(window, forward).maxByOrNull { it.area() }

  /**
   * Every shown View in [window] that reports it can still scroll down.
   *
   * One walk shared by the chooser and the failure report, so the container that gets scrolled is
   * always one of the containers the report names. Two walks would be free to disagree, and the
   * disagreement would only ever show up in the message explaining why nothing scrolled.
   */
  private fun scrollableViews(window: View, forward: Boolean = true): List<View> =
    onMainThread {
      val found = mutableListOf<View>()
      fun visit(view: View) {
        // A Compose host answers canScrollVertically for the Compose content inside it, but its
        // own scrollBy moves nothing. It is also larger than the list it hosts, so letting it in
        // makes every scroll after the first a silent no-op. Compose content is scrolled through
        // its own semantics node instead.
        if (view.isShown && view !is ViewRootForTest && view.canScrollVertically(if (forward) 1 else -1)) found += view
        if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i))
      }
      visit(window)
      found
    }

  private fun SemanticsNode.area(): Long =
    boundsInWindow.width.toLong() * boundsInWindow.height.toLong()

  private fun View.area(): Long = width.toLong() * height.toLong()

  /**
   * Less than a full screenful so a row straddling the fold lands fully on screen rather than
   * jumping from just-below to just-above it.
   */
  private const val SCROLL_FRACTION = 0.75

  /** How far down the scroller, as a fraction of its height, a target can sit and count as centred. */
  private const val CENTRE_GATE = 0.7f

  /** Down-to-up time of an injected tap: well under a long press. */
  private const val TAP_MS = 50L

  /** Compose `swipeLeft`'s duration; the ends sit inside the root so the touch lands on it. */
  private const val SWIPE_MS = 200L
  private const val SWIPE_STEPS = 10
  private const val SWIPE_EDGE = 0.1f

  /** A list scrolled to its end can report a fractional pixel short of its own maximum. */
  private const val SCROLL_EPSILON = 1f
}
