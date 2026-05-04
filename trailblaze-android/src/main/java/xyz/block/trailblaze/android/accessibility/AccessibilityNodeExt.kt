package xyz.block.trailblaze.android.accessibility

import android.graphics.Rect
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
    children = childNodes,

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

/** Auto-incrementing counter for assigning node IDs within a single tree capture. Not thread-safe — intended for single-threaded recursive use only. */
internal class NodeIdCounter {
  private var counter = 0L
  fun next(): Long = ++counter
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
 * Maps standard [AccessibilityNodeInfo] action IDs to their constant names.
 * Returns null when the ID is not a known standard action — callers decide
 * whether to fall back to a custom label or a generic `ACTION_<id>` form.
 */
internal fun standardActionName(actionId: Int): String? = when (actionId) {
  AccessibilityNodeInfo.ACTION_CLICK -> "ACTION_CLICK"
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
