package xyz.block.trailblaze.android.test.hierarchy

import android.app.Activity
import android.os.Build
import android.view.View
import android.view.inspector.WindowInspector
import xyz.block.trailblaze.android.test.AndroidTestTarget
import xyz.block.trailblaze.android.test.onMainThread
import xyz.block.trailblaze.android.test.onMainThreadForCapture
import xyz.block.trailblaze.api.DriverNodeDetail
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.ViewHierarchyTreeNode

/** Builds one non-overlapping hierarchy from native View and Compose ownership regions. */
object AndroidHybridHierarchyCollector {
  data class Collected(
    val trailblazeTree: TrailblazeNode,
    val legacyViewTree: ViewHierarchyTreeNode,
    val viewByNodeId: Map<Long, View>,
    val semanticsIdByNodeId: Map<Long, Int>,
  )

  /**
   * One snapshot of a mixed screen, taken entirely on the thread that owns it.
   *
   * Both halves of this tree belong to the UI thread — composition, layout and View mutation are
   * all its work — so every read happens inside ONE block posted to it. A block running there
   * cannot interleave with a recomposition, which makes the snapshot a picture taken between two
   * frames rather than across one, and makes the View half and the Compose half the same frame.
   *
   * That is what lets a capture overlap a running trail, and overlap is the requirement. The
   * in-process ANDROID_TEST server answers screen-state requests on a Ktor worker while a trail
   * drives Espresso from the instrumentation thread, because the host's readiness probe IS a
   * screen-state read: queueing capture behind the trail would make a healthy server look dead for
   * as long as a run lasts. Neither of those threads owns this tree, so before this, a capture
   * taken mid-interaction walked semantics the instrumentation thread was recomposing.
   *
   * Compose roots are read straight off the view tree rather than through
   * [AndroidTestTarget.composeRoots], and that is the other half of the same requirement. The
   * rule-backed read synchronizes first, and Espresso's idle wait takes exclusive hold of the main
   * looper: two threads asking for it at once is not a stale read but a thrown
   * `TestLooperManager already held for this looper` — on whichever of them lost, which includes
   * the trail's own next step. Capture therefore stays out of Espresso entirely. It gives up
   * nothing it needs, because reading on the UI thread is a stronger guarantee than waiting for
   * idle and then reading from somewhere else, and every caller of a capture polls.
   */
  fun collect(activity: Activity, target: AndroidTestTarget): Collected {
    // Only the READS hold the UI thread. The graft below is pure transformation of the immutable
    // nodes these two collectors already copied out, so running it after the block costs the
    // app's frame budget nothing — and a capture is polled, so its main-thread bite repeats.
    //
    // The hop is the BOUNDED one because this is the screen-read entry point — for the host's
    // readiness probe AND for the agent's own mid-trail reads (screenStateProvider, the polling
    // tools). An unbounded hop would let a wedged main thread park every one of those forever
    // with nothing logged on the device; expiry throws the not-ready classification instead, so
    // a wedge mid-trail is a diagnosed step failure rather than an eternal park.
    val (views, compose) = onMainThreadForCapture {
      val windowRoot = focusedWindowRoot(activity)
      val views = AndroidViewHierarchyCollector.collect(windowRoot)
      val maxViewId = views.trailblazeTree?.aggregate()?.maxOfOrNull(TrailblazeNode::nodeId) ?: 0L
      views to
        AndroidComposeHierarchyCollector.collect(
          target.composeRootsIn(windowRoot),
          firstNodeId = maxViewId + 1,
        )
    }
    val viewTree = views.trailblazeTree
    val composeTrees = compose.trees

    val hybrid =
      when {
        viewTree == null && composeTrees.isEmpty() -> syntheticRoot(emptyList())
        viewTree == null -> syntheticRoot(composeTrees)
        composeTrees.isEmpty() -> viewTree
        else ->
          graftComposeTrees(
            viewTree = viewTree,
            composeTrees = composeTrees,
            hostSemanticsIdByNodeId = views.composeRootSemanticsIdByNodeId,
            semanticsIdByNodeId = compose.semanticsIdByNodeId,
          )
      }
    return Collected(
      trailblazeTree = hybrid,
      legacyViewTree = views.legacyTree,
      viewByNodeId = views.viewByNodeId,
      semanticsIdByNodeId = compose.semanticsIdByNodeId,
    )
  }

  internal fun graftForTest(
    viewTree: TrailblazeNode,
    composeTrees: List<TrailblazeNode>,
    hostSemanticsIdByNodeId: Map<Long, Int> = emptyMap(),
    semanticsIdByNodeId: Map<Long, Int> = emptyMap(),
  ): TrailblazeNode =
    graftComposeTrees(viewTree, composeTrees, hostSemanticsIdByNodeId, semanticsIdByNodeId)

  /**
   * Hangs each Compose tree under the host that composed it.
   *
   * Matched by semantics-root identity where both sides know it, and by bounds otherwise. Identity
   * is what makes nesting come out right: Compose hosts contain other Compose hosts, so every
   * ancestor's rectangle also contains a descendant's root and bounds alone cannot say which host
   * owns what. Bounds remain the fallback for a host whose own semantics root could not be read —
   * that lookup runs app code and is failure-isolated, so it can come back empty.
   *
   * Grafted children are added ALONGSIDE the host's own View children rather than replacing them.
   * Those children are the View subtrees an `AndroidView` composable embeds, and Compose semantics
   * stop at that boundary, so they are the only record of that content.
   */
  private fun graftComposeTrees(
    viewTree: TrailblazeNode,
    composeTrees: List<TrailblazeNode>,
    hostSemanticsIdByNodeId: Map<Long, Int>,
    semanticsIdByNodeId: Map<Long, Int>,
  ): TrailblazeNode {
    val remaining = composeTrees.toMutableList()

    fun graft(node: TrailblazeNode): TrailblazeNode {
      // Depth first, so an inner host claims its own tree before an outer one can absorb it by
      // bounds. Only matters on the bounds fallback; identity matching is order-independent.
      val children = node.children.map(::graft)
      // Keyed off the View collector's own shape. If that collector's emit changes and this cast
      // is not changed with it, grafting stops silently and every Compose node dangles as a
      // sibling root — the on-device hybridScreenStateContainsBothBackends test is the guard.
      val detail = node.driverDetail as? DriverNodeDetail.AndroidView
      val isComposeHost = detail?.className == ANDROID_COMPOSE_VIEW_CLASS
      if (!isComposeHost) return node.copy(children = children)

      val hostSemanticsId = hostSemanticsIdByNodeId[node.nodeId]
      val inside =
        if (hostSemanticsId != null) {
          remaining.filter { semanticsIdByNodeId[it.nodeId] == hostSemanticsId }
        } else {
          remaining.filter { compose ->
            val composeBounds = compose.bounds
            val hostBounds = node.bounds
            composeBounds != null && hostBounds != null && hostBounds.intersects(composeBounds)
          }
        }
      remaining.removeAll(inside.toSet())
      return node.copy(children = inside + children)
    }

    val grafted = graft(viewTree)
    return if (remaining.isEmpty()) grafted else grafted.copy(children = grafted.children + remaining)
  }

  /**
   * The root view of the window an interaction would actually reach — the FOCUSED one.
   *
   * A modal dialog (a bottom sheet, a confirmation) is its own window, not a subtree of the
   * activity's decor, so a tree built from the decor alone cannot see it at all — and worse,
   * still sees everything the dialog covers. Recordings were made against trees with the
   * opposite semantics: UiAutomator dumps the active window, and an accessibility projection
   * hides what a modal window occludes. Case 4837703's status filter is the concrete failure:
   * with a sheet open, the decor-only tree offered the OCCLUDED filter chips and never the
   * sheet's own rows. Windows are checked topmost-first, and the activity's decor is the
   * fallback for the moments (transitions) where no window reports focus.
   */
  private fun focusedWindowRoot(activity: Activity): View = onMainThread {
    val decor = activity.window.decorView
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@onMainThread decor
    WindowInspector.getGlobalWindowViews().lastOrNull { it.hasWindowFocus() } ?: decor
  }

  private fun syntheticRoot(children: List<TrailblazeNode>) =
    TrailblazeNode(
      nodeId = 0,
      children = children,
      driverDetail = DriverNodeDetail.AndroidView(className = "AndroidDisplay"),
    )

  private const val ANDROID_COMPOSE_VIEW_CLASS = "androidx.compose.ui.platform.AndroidComposeView"
}
