package xyz.block.trailblaze.android.accessibility

import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.text.Spanned
import android.text.style.ClickableSpan
import android.view.accessibility.AccessibilityNodeInfo
import xyz.block.trailblaze.android.AndroidSdkVersion

/**
 * Direct conversion from [AccessibilityNodeInfo] to [AccessibilityNode], bypassing Maestro's
 * `TreeNode` entirely. This captures the **full** richness of the accessibility framework
 * with zero data loss.
 *
 * Compare with [toTreeNode] in `AccessibilityServiceExt.kt` which converts to Maestro's
 * `TreeNode` and drops most of the accessibility-specific properties.
 */
internal fun AccessibilityNodeInfo.toAccessibilityNode(nodeIdCounter: NodeIdCounter = NodeIdCounter()): AccessibilityNode {
  val nodeRect = Rect().apply { getBoundsInScreen(this) }
  val bounds = AccessibilityNode.Bounds(
    left = nodeRect.left,
    top = nodeRect.top,
    right = nodeRect.right,
    bottom = nodeRect.bottom,
  )

  // Resolve labeledBy text — if this node has a labeling relationship, capture the label's text.
  val labeledByText = labeledBy?.let { node ->
    try { node.text?.toString() } finally { node.recycle() }
  }

  // Pull semantic role override + Compose testTag out of the extras Bundle. Both are
  // string-valued under stable AndroidX / Compose-UI keys; missing keys → null. Reading
  // extras allocates a per-call Bundle copy on some Android versions, but it's a single
  // accessor per node and the data is otherwise unrecoverable for selector use.
  val extras = extras
  val roleDescription = extras
    ?.getCharSequence(EXTRA_KEY_ROLE_DESCRIPTION)
    ?.toString()
    ?.takeIf { it.isNotBlank() }
  val composeTestTag = extras
    ?.getCharSequence(EXTRA_KEY_COMPOSE_TEST_TAG)
    ?.toString()
    ?.takeIf { it.isNotBlank() }

  // Capture the Compose-style hint text hoisting: when an editable/text-accepting node has no
  // text or hintText, use the first child's text as hint.
  val isTextAccepting = isTextAcceptingNode(
    isEditable = isEditable,
    className = className?.toString(),
    actionIds = actionList?.map { it.id } ?: emptyList(),
  )
  val resolvedHintText = hintText?.toString() ?: run {
    // Compose fallback: text-accepting field's placeholder rendered as child TextView
    if (isTextAccepting && text.isNullOrEmpty()) {
      (0 until childCount).firstNotNullOfOrNull { i ->
        val child = getChild(i)
        val childText = child?.text?.toString()
        child?.recycle()
        childText
      }
    } else {
      null
    }
  }

  // Map each action to a stable name that ends up in
  // DriverNodeDetail.AndroidAccessibility.actions. Three layers of fallback:
  //
  //   1. If the action's ID is one we recognize (ACTION_CLICK, ACTION_SET_TEXT,
  //      etc.), emit the standard constant name. Why: Compose apps can override
  //      the user-facing label on a standard action via
  //      `Modifier.semantics { onClick(label = "Add to cart") }`. If we
  //      emitted the label here, the same logical "click" action would show up
  //      as "Add to cart" on one screen and "Remove" on another, which makes
  //      snapshots diff-noisy and makes tool/inspector filters that look for
  //      "ACTION_CLICK" miss Compose nodes entirely.
  //   2. Otherwise, use the action's label if it has one — this preserves the
  //      human-readable name of truly custom actions (rare, but useful for
  //      debugging and for recording specific app-defined gestures).
  //   3. Otherwise, fall back to `ACTION_<id>` so a never-seen action ID at
  //      least produces a reproducible, non-null name.
  val actionNames = actionList?.map { action ->
    standardActionName(action.id)
      ?: action.label?.toString()
      ?: "ACTION_${action.id}"
  } ?: emptyList()

  // Recursively convert children (recycle each child's AccessibilityNodeInfo after conversion)
  val childNodes = (0 until childCount).mapNotNull { index ->
    val child = getChild(index) ?: return@mapNotNull null
    try {
      child.toAccessibilityNode(nodeIdCounter)
    } finally {
      child.recycle()
    }
  }

  // In-text links: the platform exposes tappable link ranges (Compose `LinkAnnotation`,
  // classic LinkMovementMethod spans) as ClickableSpans INSIDE the node's text, not as
  // nodes of their own — `text?.toString()` below would silently discard them, leaving
  // no addressable handle for "tap the Terms of Service link" inside a legal paragraph.
  // Synthesize one child node per span so links are selectable like any other element.
  val linkChildren = textLinkChildNodes(
    specs = extractTextLinkSpecs(),
    parentClassName = className?.toString(),
    parentPackageName = packageName?.toString(),
    parentIsVisibleToUser = isVisibleToUser,
    parentIsEnabled = isEnabled,
    nodeIdCounter = nodeIdCounter,
  )

  return AccessibilityNode(
    nodeId = nodeIdCounter.next(),

    // Identity
    className = className?.toString(),
    resourceId = viewIdResourceName,
    uniqueId = if (AndroidSdkVersion.isAtLeast(33)) uniqueId else null,
    packageName = packageName?.toString(),

    // Text content
    text = text?.toString(),
    contentDescription = contentDescription?.toString(),
    hintText = resolvedHintText,
    tooltipText = if (AndroidSdkVersion.isAtLeast(28)) tooltipText?.toString() else null,
    error = error?.toString(),
    paneTitle = if (AndroidSdkVersion.isAtLeast(28)) paneTitle?.toString() else null,
    stateDescription = if (AndroidSdkVersion.isAtLeast(30)) stateDescription?.toString() else null,
    roleDescription = roleDescription,
    composeTestTag = composeTestTag,
    isShowingHintText = isShowingHintText,

    // State
    isEnabled = isEnabled,
    isClickable = isClickable,
    isLongClickable = isLongClickable,
    isFocusable = isFocusable,
    isFocused = isFocused,
    isCheckable = isCheckable,
    isChecked = isChecked,
    isSelected = isSelected,
    isEditable = isEditable,
    isScrollable = isScrollable,
    isPassword = isPassword,
    isMultiLine = isMultiLine,
    isVisibleToUser = isVisibleToUser,
    isHeading = if (AndroidSdkVersion.isAtLeast(28)) isHeading else false,
    isContentInvalid = isContentInvalid,
    isTextSelectable = if (AndroidSdkVersion.isAtLeast(33)) isTextSelectable else false,
    isImportantForAccessibility = isImportantForAccessibility,

    // Input
    inputType = inputType,
    maxTextLength = maxTextLength,

    // Bounds
    boundsInScreen = bounds,
    drawingOrder = drawingOrder,

    // Relationships
    labeledByText = labeledByText,
    children = childNodes + linkChildren,

    // Actions
    actions = actionNames,

    // Collection semantics
    collectionInfo = collectionInfo?.let {
      AccessibilityNode.CollectionInfo(
        rowCount = it.rowCount,
        columnCount = it.columnCount,
        isHierarchical = it.isHierarchical,
      )
    },
    collectionItemInfo = collectionItemInfo?.let {
      AccessibilityNode.CollectionItemInfo(
        rowIndex = it.rowIndex,
        rowSpan = it.rowSpan,
        columnIndex = it.columnIndex,
        columnSpan = it.columnSpan,
        isHeading = it.isHeading,
      )
    },
    rangeInfo = rangeInfo?.let {
      AccessibilityNode.RangeInfo(
        type = it.type,
        min = it.min,
        max = it.max,
        current = it.current,
      )
    },
  )
}

/**
 * Converts one or more window roots (in z-order; see
 * [TrailblazeAccessibilityService.getCaptureWindowRoots]) into a single [AccessibilityNode] tree.
 *
 * A single root converts exactly as [toAccessibilityNode] would on its own — identical to the
 * historical single-window capture. Two or more roots are gathered under a synthetic container
 * node whose children are each window's subtree in the given order, so dialog/popup/sub-panel
 * content from secondary windows is included after the base application window. A shared
 * [NodeIdCounter] keeps `nodeId` values unique across the merged windows.
 */
internal fun List<AccessibilityNodeInfo>.toMergedAccessibilityNode(): AccessibilityNode? =
  when (size) {
    0 -> null
    1 -> this[0].toAccessibilityNode()
    else -> {
      val counter = NodeIdCounter()
      AccessibilityNode(
        nodeId = counter.next(),
        children = map { it.toAccessibilityNode(counter) },
      )
    }
  }

/** Auto-incrementing counter for assigning node IDs within a single tree capture. Not thread-safe — intended for single-threaded recursive use only. */
internal class NodeIdCounter {
  private var counter = 0L
  fun next(): Long = ++counter
}

/**
 * One ClickableSpan range extracted from a node's text: the span's substring and its on-screen
 * bounds (null when per-character location data is unavailable — no synthetic child is
 * emitted then, see [textLinkChildNodes]).
 */
internal data class TextLinkSpec(
  val text: String,
  val bounds: AccessibilityNode.Bounds?,
)

/**
 * Extracts a [TextLinkSpec] per ClickableSpan in this node's text. Spans arrive on the
 * accessibility-service side as `AccessibilityClickableSpan`/`AccessibilityURLSpan` (both
 * `ClickableSpan` subclasses); per-span bounds come from the platform's per-character location
 * extra when the node advertises it (Compose and TextView both do).
 */
private fun AccessibilityNodeInfo.extractTextLinkSpecs(): List<TextLinkSpec> {
  val spanned = text as? Spanned ?: return emptyList()
  val spans = spanned.getSpans(0, spanned.length, ClickableSpan::class.java)
  if (spans.isEmpty()) return emptyList()
  val supportsCharLocations =
    availableExtraData.contains(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY)
  return spans.mapNotNull { span ->
    val start = spanned.getSpanStart(span)
    val end = spanned.getSpanEnd(span)
    if (start < 0 || end <= start) return@mapNotNull null
    val linkText = spanned.subSequence(start, end).toString()
    if (linkText.isBlank()) return@mapNotNull null
    val spanBounds = if (supportsCharLocations) charRangeBounds(start, end - start) else null
    TextLinkSpec(text = linkText, bounds = spanBounds)
  }
}

/**
 * On-screen bounds of the largest single-line fragment of the character range starting at
 * [startIndex] (see [largestLineRunBounds]) — NOT a full-range bounding box: a wrapped range
 * yields a rect covering only its largest fragment, so don't trust this for full-range
 * hit-testing or scroll-into-view. Per-char rects come via
 * `refreshWithExtraData(EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY)` — an IPC round-trip to the
 * app, so callers only invoke this for nodes that actually carry ClickableSpans. Null when
 * the refresh fails or no character in the range has a visible location.
 */
internal fun AccessibilityNodeInfo.charRangeBounds(startIndex: Int, length: Int): AccessibilityNode.Bounds? {
  val args = Bundle().apply {
    putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX, startIndex)
    putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH, length)
  }
  if (!refreshWithExtraData(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY, args)) {
    return null
  }
  val charBounds = extras
    .getParcelableArray(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY)
    ?.map { rect ->
      (rect as? RectF)?.let {
        AccessibilityNode.Bounds(
          left = it.left.toInt(),
          top = it.top.toInt(),
          right = it.right.toInt(),
          bottom = it.bottom.toInt(),
        )
      }
    }
    ?: return null
  return largestLineRunBounds(charBounds)
}

/**
 * Bounds of the largest contiguous same-line run of visible characters (entries are null for
 * characters the platform reports no visible location for; they are skipped without breaking
 * a run). A wrapped multi-line range must NOT be unioned into one rectangle: the union covers
 * plain text and whitespace between the line fragments, and a synthetic link child carrying
 * that envelope steals recorded-tap hit-testing from non-link text inside it. The largest
 * fragment contains only link characters, so it is a truthful clickable rect; a tap on a
 * smaller fragment hit-tests to the parent and replays as a plain gesture at the recorded
 * point, which still lands on the link. Pure function — see [AccessibilityNodeExtTest].
 */
internal fun largestLineRunBounds(charBounds: List<AccessibilityNode.Bounds?>): AccessibilityNode.Bounds? {
  val runs = mutableListOf<MutableList<AccessibilityNode.Bounds>>()
  for (rect in charBounds) {
    if (rect == null) continue
    val run = runs.lastOrNull()
    // Same line iff the character's vertical extent overlaps the run's previous character.
    if (run != null && rect.top < run.last().bottom && rect.bottom > run.last().top) {
      run.add(rect)
    } else {
      runs.add(mutableListOf(rect))
    }
  }
  return runs
    .map { run ->
      AccessibilityNode.Bounds(
        left = run.minOf { it.left },
        top = run.minOf { it.top },
        right = run.maxOf { it.right },
        bottom = run.maxOf { it.bottom },
      )
    }
    .maxByOrNull { (it.right - it.left).toLong() * (it.bottom - it.top) }
}

/**
 * Picks which of several same-text link spans a tap targeted: the first candidate whose
 * bounds contain the tap point, or null when none do (a click-time bounds re-derivation
 * can transiently fail, or the tap point can sit outside every candidate after the tree
 * shifted). Null means the duplicates are positionally indistinguishable — clicking an
 * arbitrary one could activate the wrong link, so the caller must treat it as a miss and
 * let the gesture fallback aim at the resolved child's own coordinates instead. Pure
 * function — see [AccessibilityNodeExtTest].
 */
internal fun pickTextLinkSpanIndex(
  candidateBounds: List<AccessibilityNode.Bounds?>,
  targetX: Int,
  targetY: Int,
): Int? {
  val hit = candidateBounds.indexOfFirst {
    it != null && targetX in it.left..it.right && targetY in it.top..it.bottom
  }
  return if (hit >= 0) hit else null
}

/**
 * Builds the synthetic child node per extracted link span. `isClickable = true` because the
 * span IS an activation target — it gives the child its own ref in the compact element list
 * and lets `isClickable`-based selectors match. `actions` stays empty on purpose: the child
 * is not a live node, so the ACTION_CLICK dispatch route must decline it; activation goes
 * through the span-click tap route (keyed on [AccessibilityNode.isTextLink]) with a gesture
 * fallback at the child's bounds. Pure function — see [AccessibilityNodeExtTest].
 *
 * Specs without bounds (no character-location extras, or the whole range scrolled out of
 * view) emit NO child rather than one with fabricated bounds. A clickable child carrying its
 * parent's whole-paragraph bounds poisons recorded-tap hit-testing in both directions: under
 * a non-interactive parent it shadows the paragraph, so a tap on plain text records link #1;
 * under an interactive parent it loses the equal-bounds tie, so link taps record the
 * paragraph. Every supported text surface (TextView and Compose, API 26+) advertises
 * character locations, and capture re-runs per resolution attempt, so a transient extras
 * failure only affects that one snapshot.
 */
internal fun textLinkChildNodes(
  specs: List<TextLinkSpec>,
  parentClassName: String?,
  parentPackageName: String?,
  parentIsVisibleToUser: Boolean,
  parentIsEnabled: Boolean,
  nodeIdCounter: NodeIdCounter,
): List<AccessibilityNode> = specs.mapNotNull { spec ->
  val bounds = spec.bounds ?: return@mapNotNull null
  AccessibilityNode(
    nodeId = nodeIdCounter.next(),
    className = parentClassName,
    packageName = parentPackageName,
    text = spec.text,
    isClickable = true,
    isTextLink = true,
    isVisibleToUser = parentIsVisibleToUser,
    isEnabled = parentIsEnabled,
    boundsInScreen = bounds,
  )
}

/**
 * Stable AndroidX key for the `EXTRA_ROLE_DESCRIPTION` extras entry. Referenced as a
 * string literal rather than `AccessibilityNodeInfoCompat.EXTRA_ROLE_DESCRIPTION_KEY`
 * so this module doesn't pull in the AndroidX core compat dependency for one constant —
 * the key has been stable since the constant was introduced in AndroidX core.
 *
 * Stable since AndroidX core 1.0.0 (the constant has been part of the public surface
 * since the AndroidX migration in 2018). If a future AndroidX release changes the
 * value (extremely unlikely — would break every app using `setRoleDescription`), the
 * `EXTRA_KEY_ROLE_DESCRIPTION` literal here would silently stop matching at runtime.
 * Mitigation: any team adding `androidx.core` to this module's test classpath should
 * also add an assertion that this literal equals
 * `AccessibilityNodeInfoCompat.EXTRA_ROLE_DESCRIPTION_KEY`. We don't pull AndroidX
 * core into this module's test deps just for that — the cost outweighs the risk
 * given how stable the key has been.
 */
internal const val EXTRA_KEY_ROLE_DESCRIPTION: String = "AccessibilityNodeInfo.roleDescription"

/**
 * Compose-UI's extras key for `Modifier.testTag(...)` values. Populated by
 * `AndroidComposeViewAccessibilityDelegateCompat` when an app does not opt into
 * `Modifier.semantics { testTagsAsResourceId = true }` (the opt-in surfaces testTag
 * as the accessibility node's `viewIdResourceName` instead, which we already capture
 * as `resourceId`). Reading this key is best-effort: missing on classic-View screens,
 * Compose versions that didn't expose it, and apps that route via testTagsAsResourceId.
 *
 * The literal mirrors `SemanticsProperties.TestTag.name` in
 * `androidx.compose.ui.semantics`. We don't import it here to avoid pulling
 * compose-ui into the accessibility module for one constant. A Compose-UI rename
 * would silently drop testTag capture; revisit if a Compose major version churns
 * the property name (none have so far).
 */
internal const val EXTRA_KEY_COMPOSE_TEST_TAG: String = "androidx.compose.ui.semantics.testTag"

/**
 * Returns true if this node accepts text input.
 *
 * Broadened beyond just EditText: Compose text fields in some apps (e.g., Google Contacts)
 * are exposed as `android.view.View` without `isEditable = true`, but they advertise
 * `ACTION_SET_TEXT` in their action list. Treating those as text-accepting lets the
 * hint-text hoisting pull a placeholder TextView up to the parent so the agent sees a
 * usable label.
 *
 * Pure function on plain types so it can be unit-tested without Robolectric.
 */
internal fun isTextAcceptingNode(
  isEditable: Boolean,
  className: String?,
  actionIds: Collection<Int>,
): Boolean = isEditable ||
  className == "android.widget.EditText" ||
  AccessibilityNodeInfo.ACTION_SET_TEXT in actionIds

/**
 * Canonical name [standardActionName] emits for [AccessibilityNodeInfo.ACTION_CLICK].
 * Shared so callers that need to test for ACTION_CLICK against
 * [DriverNodeDetail.AndroidAccessibility.actions] (the captured list of action *names*)
 * do not duplicate the string literal.
 */
internal const val ACTION_CLICK_NAME = "ACTION_CLICK"

/**
 * Maps standard [AccessibilityNodeInfo] action IDs to their constant names.
 * Returns null when the ID is not a known standard action — callers decide
 * whether to fall back to a custom label or a generic `ACTION_<id>` form.
 */
internal fun standardActionName(actionId: Int): String? = when (actionId) {
  AccessibilityNodeInfo.ACTION_CLICK -> ACTION_CLICK_NAME
  AccessibilityNodeInfo.ACTION_LONG_CLICK -> "ACTION_LONG_CLICK"
  AccessibilityNodeInfo.ACTION_FOCUS -> "ACTION_FOCUS"
  AccessibilityNodeInfo.ACTION_CLEAR_FOCUS -> "ACTION_CLEAR_FOCUS"
  AccessibilityNodeInfo.ACTION_SELECT -> "ACTION_SELECT"
  AccessibilityNodeInfo.ACTION_CLEAR_SELECTION -> "ACTION_CLEAR_SELECTION"
  AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> "ACTION_SCROLL_FORWARD"
  AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> "ACTION_SCROLL_BACKWARD"
  AccessibilityNodeInfo.ACTION_COPY -> "ACTION_COPY"
  AccessibilityNodeInfo.ACTION_PASTE -> "ACTION_PASTE"
  AccessibilityNodeInfo.ACTION_CUT -> "ACTION_CUT"
  AccessibilityNodeInfo.ACTION_SET_SELECTION -> "ACTION_SET_SELECTION"
  AccessibilityNodeInfo.ACTION_EXPAND -> "ACTION_EXPAND"
  AccessibilityNodeInfo.ACTION_COLLAPSE -> "ACTION_COLLAPSE"
  AccessibilityNodeInfo.ACTION_SET_TEXT -> "ACTION_SET_TEXT"
  AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id -> "ACTION_SCROLL_UP"
  AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id -> "ACTION_SCROLL_DOWN"
  AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id -> "ACTION_SCROLL_LEFT"
  AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id -> "ACTION_SCROLL_RIGHT"
  AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id -> "ACTION_SHOW_ON_SCREEN"
  AccessibilityNodeInfo.AccessibilityAction.ACTION_CONTEXT_CLICK.id -> "ACTION_CONTEXT_CLICK"
  AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id -> "ACTION_SET_PROGRESS"
  AccessibilityNodeInfo.AccessibilityAction.ACTION_DISMISS.id -> "ACTION_DISMISS"
  else -> null
}
