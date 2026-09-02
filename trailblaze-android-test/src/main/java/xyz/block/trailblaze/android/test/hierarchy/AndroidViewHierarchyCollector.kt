package xyz.block.trailblaze.android.test.hierarchy

import android.graphics.Rect
import android.os.Build
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.AdapterView
import android.widget.Checkable
import android.widget.EditText
import android.widget.TextView
import androidx.compose.ui.node.RootForTest
import xyz.block.trailblaze.android.test.onMainThread
import xyz.block.trailblaze.api.DriverNodeDetail
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.api.toViewHierarchyTreeNode

/**
 * Collects the classic View hierarchy from the live `android.view.View` objects, without crossing
 * UiAutomation or the accessibility service.
 *
 * A Compose host is emitted as a structural boundary, and [AndroidHybridHierarchyCollector] hangs
 * that host's Compose semantics under it. The host's own child Views are still traversed, because
 * they are not Compose: an `AndroidView` composable parks a real View subtree under the host, and
 * Compose semantics stop at that boundary. Skipping them loses every screen an app builds by
 * embedding classic Views inside Compose — in a large mixed app that can be most of the body.
 *
 * [Collected.viewByNodeId] is what makes identity dispatch possible: a selector resolves to a
 * [TrailblazeNode], and this map hands back the exact `View` instance that node was built from, so
 * the action layer never has to re-describe the node and search for it again.
 */
object AndroidViewHierarchyCollector {
  data class Collected(
    val trailblazeTree: TrailblazeNode?,
    val legacyTree: ViewHierarchyTreeNode,
    val composeHosts: List<View>,
    val viewByNodeId: Map<Long, View>,
    /**
     * `nodeId` of a Compose host → the `SemanticsNode.id` of the semantics root that host owns.
     *
     * This is what lets the graft match a Compose tree to the host that actually composed it.
     * Bounds cannot: hosts nest, so an inner host's rectangle sits inside its outer host's and both
     * "match" every root in the region.
     */
    val composeRootSemanticsIdByNodeId: Map<Long, Int>,
  )

  /** [rootView] is the focused window's root — see `AndroidHybridHierarchyCollector.focusedWindowRoot`. */
  fun collect(rootView: View): Collected = onMainThread {
    val composeHosts = mutableListOf<View>()
    val viewByNodeId = mutableMapOf<Long, View>()
    val composeRootSemanticsIdByNodeId = mutableMapOf<Long, Int>()
    var nextId = 1L
    val tree =
      mapView(rootView, { nextId++ }, composeHosts, viewByNodeId, composeRootSemanticsIdByNodeId)
    Collected(
      trailblazeTree = tree,
      legacyTree = tree?.toViewHierarchyTreeNode() ?: ViewHierarchyTreeNode(),
      composeHosts = composeHosts,
      viewByNodeId = viewByNodeId,
      composeRootSemanticsIdByNodeId = composeRootSemanticsIdByNodeId,
    )
  }

  private fun mapView(
    view: View,
    nextId: () -> Long,
    composeHosts: MutableList<View>,
    viewByNodeId: MutableMap<Long, View>,
    composeRootSemanticsIdByNodeId: MutableMap<Long, Int>,
  ): TrailblazeNode? {
    val bounds = view.onScreenBounds() ?: return null
    val isComposeHost = view.javaClass.name == ANDROID_COMPOSE_VIEW_CLASS
    if (isComposeHost) composeHosts += view

    val children =
      (view as? ViewGroup)?.let { group ->
        buildList {
          for (index in 0 until group.childCount) {
            mapView(
              group.getChildAt(index),
              nextId,
              composeHosts,
              viewByNodeId,
              composeRootSemanticsIdByNodeId,
            )?.let(::add)
          }
        }
      } ?: emptyList()

    val nodeId = nextId()
    if (isComposeHost) {
      view.composeRootSemanticsId()?.let { composeRootSemanticsIdByNodeId[nodeId] = it }
    }
    viewByNodeId[nodeId] = view
    val textView = view as? TextView
    val collectionItem = view.collectionItemInfoOrNull()
    return TrailblazeNode(
      nodeId = nodeId,
      bounds = bounds,
      children = children,
      driverDetail =
        DriverNodeDetail.AndroidView(
          className = view.javaClass.name,
          accessibilityClassName = view.accessibilityClassName?.toString()?.takeIf(String::isNotBlank),
          resourceId = view.resourceEntryName(),
          tag = view.stringTag(),
          text = textView?.text?.toString()?.takeIf(String::isNotBlank),
          contentDescription = view.contentDescription?.toString()?.takeIf(String::isNotBlank),
          hintText = textView?.hint?.toString()?.takeIf(String::isNotBlank),
          stateDescription = view.stateDescriptionOrNull(),
          errorText = textView?.error?.toString()?.takeIf(String::isNotBlank),
          isEnabled = view.isEnabled,
          isClickable = view.isClickable,
          isChecked = (view as? Checkable)?.isChecked,
          isSelected = view.isSelected,
          isFocused = view.isFocused,
          isEditable = view.isTextEditable(),
          isPassword = textView?.transformationMethod is PasswordTransformationMethod,
          inputType = textView?.inputType ?: InputType.TYPE_NULL,
          isFocusable = view.isFocusable,
          isScrollable = view.canScrollAnyAxis(),
          alpha = view.alpha,
          isShown = view.isShown,
          collectionItemRowIndex = collectionItem?.rowIndex,
          collectionItemColumnIndex = collectionItem?.columnIndex,
        ),
    )
  }

  /**
   * This view's position in the collection holding it, or null when it is not a collection item.
   *
   * Read out of the view's own [AccessibilityNodeInfo] because that is where the position comes
   * from on the accessibility driver too — `AbsListView` fills `CollectionItemInfo` in for its
   * children and nothing on the View itself carries a row or a column — so a grid selector recorded
   * there evaluates here against the same numbers rather than a reconstruction of them.
   *
   * Gated on the parent being a collection, and that gate is the point: building a node info
   * allocates and runs the view's accessibility delegate chain, which is not worth paying on every
   * view of a several-hundred-node tree, on every snapshot, to answer a question only the items of
   * a collection can answer at all.
   */
  private fun View.collectionItemInfoOrNull(): AccessibilityNodeInfo.CollectionItemInfo? {
    val parent = parent
    if (parent !is AdapterView<*> && !parent.isRecyclerViewLike()) return null
    val info = createAccessibilityNodeInfo() ?: return null
    return try {
      info.collectionItemInfo
    } finally {
      @Suppress("DEPRECATION")
      info.recycle()
    }
  }

  /**
   * RecyclerView by class NAME: it ships in androidx and this module cannot take a dependency on it
   * just to recognise one. A false positive costs a single node info on a view that then reports no
   * collection position anyway.
   *
   * The SUPERCLASS CHAIN is walked rather than the leaf class, and that direction is the one that
   * costs something. An app subclass need not keep the name — Square's grids are one — while it
   * still carries RecyclerView's item accessibility delegate, so a leaf-name test leaves its items
   * with no `collectionItemRowIndex` at all. The strict bridge then turns every recorded
   * grid-position selector into a failed match, and a grid of untitled tiles has no other handle
   * (case 5921801's empty favourites tiles are exactly that). The false-positive direction the
   * paragraph above tolerates is cheap; this one silently loses the only selector that works.
   */
  private fun Any?.isRecyclerViewLike(): Boolean {
    var cls: Class<*>? = this?.let { it::class.java }
    while (cls != null) {
      if (cls.name.endsWith("RecyclerView")) return true
      cls = cls.superclass
    }
    return false
  }

  /**
   * Whether [view] is part of the tree a selector can match against — the one gate [mapView] drops
   * a subtree on.
   *
   * Action relocation walks the LIVE view tree rather than the captured snapshot, so it has to
   * apply this same test: an off-screen child was never a node any trail could name, and treating
   * one as a relocation candidate either lands the action somewhere invisible or manufactures
   * ambiguity against a visible sibling and refuses a tap that had exactly one real target.
   */
  internal fun isOnScreen(view: View): Boolean = view.onScreenBounds() != null

  /**
   * The id of the semantics root this Compose host owns, or null if it owns none.
   *
   * Failure-isolated: the host is identified by class name, so a class that merely shares the name
   * must not take the whole capture down.
   */
  private fun View.composeRootSemanticsId(): Int? =
    (this as? RootForTest)?.let {
      runCatching { it.semanticsOwner.unmergedRootSemanticsNode.id }.getOrNull()
    }

  /**
   * The view's on-screen rectangle, or null when it has none. `getGlobalVisibleRect` is geometry
   * only — it does not consult the visibility flag — so an INVISIBLE view still reports the rect it
   * would occupy, and the flags have to be checked separately.
   */
  private fun View.onScreenBounds(): TrailblazeNode.Bounds? {
    if (visibility != View.VISIBLE || !isShown) return null
    val visible = Rect()
    if (!getGlobalVisibleRect(visible) || visible.width() <= 0 || visible.height() <= 0) return null
    return TrailblazeNode.Bounds(visible.left, visible.top, visible.right, visible.bottom)
  }

  private fun View.resourceEntryName(): String? {
    if (id == View.NO_ID) return null
    return runCatching { resources.getResourceName(id) }.getOrNull()
  }

  /**
   * Only a [CharSequence] tag is reported. `setTag(Object)` is widely used to park arbitrary
   * model objects on a view, and stringifying one of those would put an unbounded blob of an
   * app's internal state into every hierarchy snapshot. A string tag is the developer-set
   * identifier this field is for — minus the ones the build tools set, see [isGeneratedViewTag].
   */
  private fun View.stringTag(): String? = (tag as? CharSequence)
    ?.toString()
    ?.takeIf { it.isNotBlank() && !isGeneratedViewTag(it) }

  private fun View.isTextEditable(): Boolean =
    this is EditText || ((this as? TextView)?.inputType ?: InputType.TYPE_NULL) != InputType.TYPE_NULL

  private fun View.canScrollAnyAxis(): Boolean =
    canScrollVertically(1) || canScrollVertically(-1) ||
      canScrollHorizontally(1) || canScrollHorizontally(-1)

  /**
   * State description is the one property read through the accessibility contract rather than a
   * View getter, because it is where a custom view publishes its own state ("Expanded", "3 of 10")
   * without any test-only opt-in.
   *
   * Two sources, cheap first: `View.getStateDescription()` covers everything set via
   * `setStateDescription`, and only when that is empty do we build a node info, which is what
   * runs the view's `onInitializeAccessibilityNodeInfo` — the older and more common place a
   * custom view sets this. Building a node info runs app code, so it is failure-isolated.
   *
   * The node-info leg is restricted to **app** classes. It is the expensive one — it allocates and
   * runs an app callback per view — and a tool call rebuilds the whole snapshot every
   * `RESOLVE_POLL_MS` for up to the resolve timeout, so leaving it ungated ran it across the entire
   * tree tens of times per action. A platform or AndroidX widget publishes state description
   * through `setStateDescription`, which the cheap leg above already reads; overriding
   * `onInitializeAccessibilityNodeInfo` to set one is a custom-view idiom, and custom views are
   * exactly what this leg exists for.
   *
   * Both APIs are 30+; below that the platform has no state description to report at all, so
   * there is nothing to fall back to and no node info worth building.
   */
  private fun View.stateDescriptionOrNull(): String? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
    stateDescription?.toString()?.takeIf(String::isNotBlank)?.let { return it }
    if (!isAppClass()) return null
    return runCatching {
      val info = createAccessibilityNodeInfo() ?: return@runCatching null
      try {
        info.stateDescription?.toString()?.takeIf(String::isNotBlank)
      } finally {
        @Suppress("DEPRECATION")
        info.recycle()
      }
    }.getOrNull()
  }

  /** A view whose class ships with the platform or AndroidX, rather than with the app under test. */
  private fun View.isAppClass(): Boolean =
    FRAMEWORK_CLASS_PREFIXES.none { javaClass.name.startsWith(it) }

  private val FRAMEWORK_CLASS_PREFIXES = listOf("android.", "androidx.", "com.google.android.material.")

  private const val ANDROID_COMPOSE_VIEW_CLASS = "androidx.compose.ui.platform.AndroidComposeView"
}
