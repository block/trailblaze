package xyz.block.trailblaze.android.test.hierarchy

import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.findViewTreeLifecycleOwner
import xyz.block.trailblaze.api.DriverNodeDetail
import xyz.block.trailblaze.api.TrailblazeNode

/** Maps the app's live Android Compose test semantics into Trailblaze's Compose node dialect. */
object AndroidComposeHierarchyCollector {

  data class Collected(
    val trees: List<TrailblazeNode>,
    /**
     * `nodeId` → `SemanticsNode.id`. The semantics id is what the Compose test rule can look a
     * node up by, so this is the Compose half of identity dispatch: the resolver picks a
     * [TrailblazeNode], and this map turns it back into something `onNode` can address without
     * re-matching on properties.
     */
    val semanticsIdByNodeId: Map<Long, Int>,
  )

  fun collect(roots: List<SemanticsNode>, firstNodeId: Long): Collected {
    var nextId = firstNodeId
    val semanticsIdByNodeId = mutableMapOf<Long, Int>()
    // Failure-isolated PER ROOT, matching the View collector's read of the same objects: mapping
    // runs app code (a scroll range's value is an app lambda), and one root mid-disposal must cost
    // that root, not the whole capture — a capture is also the host's readiness probe, and a
    // thrown probe reads as a dead server. The graft already treats a missing root as "could not
    // be read", so a dropped root degrades to exactly that documented state.
    val trees =
      roots.mapNotNull { root ->
        runCatching { mapNode(root, { nextId++ }, semanticsIdByNodeId) }.getOrNull()
      }
    return Collected(trees = trees, semanticsIdByNodeId = semanticsIdByNodeId)
  }

  /**
   * Every Compose semantics root attached under [rootView], read straight off the view tree.
   *
   * [ViewRootForTest] is the interface Compose's own test infrastructure resolves a root through,
   * and `unmergedRootSemanticsNode` is the same node `fetchSemanticsNodes` would hand back — so
   * this differs from the rule's read in one respect only, that it asks nobody whether the app has
   * settled first. Nothing else here can ask: the rule's read synchronizes through Espresso, which
   * takes exclusive hold of the main looper, and a caller that may be running alongside a trail
   * cannot take that.
   *
   * The walk does NOT stop at a host. A Compose host is a `ViewGroup`, and an `AndroidView`
   * composable can park a whole View subtree — a further `ComposeView` included — beneath one, so
   * stopping there would drop a nested host's semantics that the rule's registry-backed read would
   * have reported.
   *
   * Only RESUMED roots are reported, which is the registry's own eligibility rule
   * (`ComposeRootRegistry` tracks a `resumedRoots` set). Attached-but-paused content is a real
   * state of a real screen — ViewPager2 keeps adjacent pages attached at STARTED — and its host
   * has no on-screen bounds, so its tree would graft nowhere and dangle at the snapshot root,
   * teaching selectors to resolve to controls nobody can see.
   *
   * Must be called on the UI thread: semantics belong to it, and this is the read that would
   * otherwise interleave with a recomposition.
   */
  fun rootsUnder(rootView: View): List<SemanticsNode> {
    val roots = mutableListOf<SemanticsNode>()
    fun visit(view: View) {
      if (view is ViewRootForTest && view.isLifecycleResumed()) {
        // Failure-isolated as in `AndroidViewHierarchyCollector.composeRootSemanticsId`, which
        // guards this same lookup: it runs app code, and a host mid-disposal throws rather than
        // answering. That host's semantics are lost either way; the rest of the screen is not.
        runCatching { view.semanticsOwner.unmergedRootSemanticsNode }.getOrNull()?.let { roots += it }
      }
      if (view is ViewGroup) {
        for (index in 0 until view.childCount) visit(view.getChildAt(index))
      }
    }
    visit(rootView)
    return roots
  }

  private fun View.isLifecycleResumed(): Boolean =
    findViewTreeLifecycleOwner()?.lifecycle?.currentState == Lifecycle.State.RESUMED

  private fun mapNode(
    node: SemanticsNode,
    nextId: () -> Long,
    semanticsIdByNodeId: MutableMap<Long, Int>,
  ): TrailblazeNode {
    val config = node.config
    val bounds = node.boundsInWindow
    val nodeId = nextId()
    semanticsIdByNodeId[nodeId] = node.id
    val collectionItem = config.getOrNull(SemanticsProperties.CollectionItemInfo)
    val progress = config.getOrNull(SemanticsProperties.ProgressBarRangeInfo)
      ?.takeIf { it != ProgressBarRangeInfo.Indeterminate }
    val verticalScroll = config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)
    val horizontalScroll = config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange)
    return TrailblazeNode(
      nodeId = nodeId,
      bounds =
        TrailblazeNode.Bounds(
          left = bounds.left.toInt(),
          top = bounds.top.toInt(),
          right = bounds.right.toInt(),
          bottom = bounds.bottom.toInt(),
        ),
      children = node.children.map { mapNode(it, nextId, semanticsIdByNodeId) },
      driverDetail =
        DriverNodeDetail.Compose(
          testTag = config.getOrNull(SemanticsProperties.TestTag),
          role = config.getOrNull(SemanticsProperties.Role)?.toString(),
          text = config.getOrNull(SemanticsProperties.Text)?.joinToString(", ") { it.text },
          editableText = config.getOrNull(SemanticsProperties.EditableText)?.text,
          contentDescription =
            config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(", "),
          toggleableState =
            when (config.getOrNull(SemanticsProperties.ToggleableState)) {
              ToggleableState.On -> "On"
              ToggleableState.Off -> "Off"
              ToggleableState.Indeterminate -> "Indeterminate"
              null -> null
            },
          isEnabled = !config.contains(SemanticsProperties.Disabled),
          isFocused = config.getOrNull(SemanticsProperties.Focused) ?: false,
          isSelected = config.getOrNull(SemanticsProperties.Selected) ?: false,
          isPassword = config.getOrNull(SemanticsProperties.Password) != null,
          collectionItemRowIndex = collectionItem?.rowIndex,
          collectionItemColumnIndex = collectionItem?.columnIndex,
          stateDescription = config.getOrNull(SemanticsProperties.StateDescription),
          isHeading = config.contains(SemanticsProperties.Heading),
          paneTitle = config.getOrNull(SemanticsProperties.PaneTitle),
          isDialog = config.contains(SemanticsProperties.IsDialog),
          isPopup = config.contains(SemanticsProperties.IsPopup),
          errorText = config.getOrNull(SemanticsProperties.Error),
          hasSetTextAction = config.getOrNull(SemanticsActions.SetText) != null,
          accessibilityClassName = accessibilityClassNameOf(node),
          hasClickAction = config.getOrNull(SemanticsActions.OnClick) != null,
          hasScrollAction = config.getOrNull(SemanticsActions.ScrollBy) != null,
          hasLongClickAction = config.getOrNull(SemanticsActions.OnLongClick) != null,
          progressValue = progress?.current,
          progressMax = progress?.range?.endInclusive,
          verticalScrollValue = verticalScroll?.value?.invoke(),
          verticalScrollMax = verticalScroll?.maxValue?.invoke(),
          horizontalScrollValue = horizontalScroll?.value?.invoke(),
          horizontalScrollMax = horizontalScroll?.maxValue?.invoke(),
        ),
    )
  }

  /**
   * The class name Compose's accessibility delegate would publish for [node], reproducing
   * `AndroidComposeViewAccessibilityDelegateCompat`'s own assignment order: the plain-view default,
   * then a role's widget class, then the text-field and text overrides that take precedence over
   * the role.
   *
   * Reproduced rather than read, because there is nothing to read it from — the projection happens
   * inside the delegate on the way out to an `AccessibilityNodeInfo`, and the in-process driver
   * never builds one. Reproducing it is what makes a canonical `classNameRegex` answerable on this
   * tree at all, and the ORDER is the substance: a `Role.Button` that also carries `Text` publishes
   * `android.widget.TextView`, so applying the role last would name a class the accessibility tree
   * never showed and silently match the wrong nodes.
   *
   * The role only applies to a node with no children of its own, matching the delegate's
   * `replacedChildren.isEmpty()` guard: a role on a container describes the container's purpose,
   * and the a11y tree keeps that node a plain view.
   *
   * Null for a node the delegate would not publish AT ALL, which is most of this tree: the
   * unmerged semantics tree carries a layout node per modifier, and the delegate keeps only the
   * ones that are "important for accessibility" — the ones whose config says something an
   * assistive technology could use. Naming a class for the rest claims the a11y tree showed a node
   * it never did, so a `classNameRegex` selector matches nodes no recording could have named.
   * [IMPORTANT_FOR_ACCESSIBILITY] is that config test. A merging node is published whatever it
   * carries, since merging is itself an instruction to the delegate.
   *
   * It does NOT make this tree index-compatible with an accessibility one, and nothing short of
   * reproducing the delegate node-for-node would. `classNameRegex: android.view.View` qualified
   * only by `index` — how the recorder names a row with no text, id or content description of its
   * own — counts every published node, and three things beyond importance still move that count:
   * each Compose host contributes a semantics root that the a11y tree represents as the host View
   * instead, the delegate's role branch keys off MERGED children where this keys off unmerged, and
   * the message bar's own contents differ between two devices anyway. On the Settings list of cases
   * 5380716 and 5380717 that leaves index 6 on the mode-selector row rather than Checkout, two
   * places short.
   *
   * Those two were the only trails here whose recording carried such a step, and they no longer do:
   * the row is qualified by its label and the Compose host it hangs under, which is something both
   * trees state. `CrossDriverSelectorPortabilityTest` holds a capture from each driver and pins
   * that. An `index` was never going to survive this move — it is unstable on the recording driver
   * too, since the row it lands on depends on what the status bar is showing — so what this filter
   * buys is a `classNameRegex` that means the same thing on both trees, not a count that agrees.
   */
  private fun accessibilityClassNameOf(node: SemanticsNode): String? {
    val config = node.config
    if (
      !config.isMergingSemanticsOfDescendants &&
      config.none { it.key in IMPORTANT_FOR_ACCESSIBILITY }
    ) {
      return null
    }
    var className = PLAIN_VIEW_CLASS
    if (node.children.isEmpty()) {
      when (config.getOrNull(SemanticsProperties.Role)) {
        Role.Button -> className = "android.widget.Button"
        Role.Checkbox -> className = "android.widget.CheckBox"
        Role.RadioButton -> className = "android.widget.RadioButton"
        Role.Image -> className = "android.widget.ImageView"
        Role.DropdownList -> className = "android.widget.Spinner"
        else -> Unit
      }
    }
    if (config.contains(SemanticsActions.SetText)) className = "android.widget.EditText"
    if (config.contains(SemanticsProperties.Text)) className = "android.widget.TextView"
    return className
  }

  /** What Compose publishes for a semantics node it has nothing more specific to say about. */
  private const val PLAIN_VIEW_CLASS = "android.view.View"

  /**
   * The semantics keys whose presence puts a node in the accessibility tree.
   *
   * Enumerated because the flag the delegate actually reads —
   * `SemanticsPropertyKey.isImportantForAccessibility` — is internal to Compose. The list is the
   * public keys that carry one: everything this collector projects into a
   * [DriverNodeDetail.Compose], which is the same thing said twice — a node this collector can
   * describe is a node the delegate could describe.
   *
   * `TestTag` is deliberately absent, and it is the one that matters. A test tag reaches the
   * accessibility tree only under `testTagsAsResourceId`, which this app does not set — build
   * 9900's captured tree gives its Compose nodes no resource ids at all — so a tagged-but-otherwise
   * bare wrapper is invisible to a recording. Keeping it out here is what makes the counts agree.
   * The node itself stays in the tree with its tag, so a `composeTestTagRegex` selector still
   * resolves against it; only the claim that the a11y tree published a class for it is withdrawn.
   *
   * Square's own custom keys (`MarketId`, `Shape`) are absent for the same reason: a key declared
   * outside Compose defaults to unimportant, and a node carrying nothing else is a layout detail.
   */
  private val IMPORTANT_FOR_ACCESSIBILITY: Set<SemanticsPropertyKey<*>> =
    setOf(
      SemanticsProperties.Text,
      SemanticsProperties.EditableText,
      SemanticsProperties.ContentDescription,
      SemanticsProperties.StateDescription,
      SemanticsProperties.Role,
      SemanticsProperties.Disabled,
      SemanticsProperties.Focused,
      SemanticsProperties.Selected,
      SemanticsProperties.ToggleableState,
      SemanticsProperties.Password,
      SemanticsProperties.Error,
      SemanticsProperties.Heading,
      SemanticsProperties.PaneTitle,
      SemanticsProperties.IsDialog,
      SemanticsProperties.IsPopup,
      SemanticsProperties.ProgressBarRangeInfo,
      SemanticsProperties.CollectionInfo,
      SemanticsProperties.CollectionItemInfo,
      SemanticsProperties.VerticalScrollAxisRange,
      SemanticsProperties.HorizontalScrollAxisRange,
      SemanticsActions.OnClick,
      SemanticsActions.OnLongClick,
      SemanticsActions.SetText,
      SemanticsActions.ScrollBy,
      SemanticsActions.ScrollToIndex,
      SemanticsActions.RequestFocus,
    )
}
