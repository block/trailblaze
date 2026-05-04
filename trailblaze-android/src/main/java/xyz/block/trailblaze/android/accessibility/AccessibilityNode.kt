package xyz.block.trailblaze.android.accessibility

/**
 * High-fidelity representation of an Android `AccessibilityNodeInfo`, capturing the full richness
 * of the accessibility framework without any data loss.
 *
 * This is intentionally **separate** from `ViewHierarchyTreeNode` — that shared model serves
 * production flows (set-of-mark, filtering, selector generation) and changing it risks regressions.
 * `AccessibilityNode` is the accessibility driver's native model, used for:
 * - Element resolution (matching `TrailblazeNodeSelector` against the tree)
 * - Rich assertions (state descriptions, form validation, range values)
 * - Smart interaction (input types, available actions, label relationships)
 *
 * ## What this captures that `ViewHierarchyTreeNode` drops
 *
 * ### Never captured (lost in AccessibilityNodeInfo → TreeNode):
 * - [isVisibleToUser]: whether the node is actually visible on screen
 * - [actions]: the full list of available actions (click, scroll, setText, etc.)
 * - [rangeInfo]: seekbar/progress bar values (current, min, max)
 * - [collectionInfo]: list/grid metadata (row count, column count)
 * - [collectionItemInfo]: item position in list (row, column, span)
 * - [isHeading]: structural heading for navigation
 * - [stateDescription]: rich state ("On", "50%", "Expanded")
 * - [labeledByText]: text of the label that describes this node
 * - [isContentInvalid]: form validation state
 * - [inputType]: input field type (email, password, number, etc.)
 * - [drawingOrder]: visual z-order for overlapping views
 * - [uniqueId]: developer-assigned unique ID (API 33+)
 * - [isShowingHintText]: whether currently showing hint vs actual input
 * - [paneTitle]: title for pane-like containers (dialogs, bottom sheets)
 * - [isCheckable]: whether the node can be checked
 * - [isMultiLine]: multi-line text field
 * - [maxTextLength]: maximum text length for input fields
 *
 * ### Captured in TreeNode attributes but dropped in → ViewHierarchyTreeNode:
 * - [isEditable], [isLongClickable], [packageName], [tooltipText], [error]
 */
data class AccessibilityNode(
  // --- Identity ---
  /** Auto-assigned ID for this node within the tree. Not stable across captures. */
  val nodeId: Long = 0,

  /** Fully qualified class name (e.g., "android.widget.Button", "android.widget.EditText"). */
  val className: String? = null,

  /** The resource ID of the view (e.g., "com.example:id/btn_continue"). */
  val resourceId: String? = null,

  /** Developer-assigned unique ID (API 33+). More stable than resourceId across builds. */
  val uniqueId: String? = null,

  /** The app package that owns this view. */
  val packageName: String? = null,

  // --- Text content ---
  /** The primary text content of the node. */
  val text: String? = null,

  /** The content description (accessibility label). */
  val contentDescription: String? = null,

  /** Hint text for input fields. */
  val hintText: String? = null,

  /** Tooltip text shown on long-hover (API 28+). */
  val tooltipText: String? = null,

  /** Error text for invalid input (e.g., "Invalid email address"). */
  val error: String? = null,

  /** Title of pane-like containers such as dialogs and bottom sheets (API 28+). */
  val paneTitle: String? = null,

  /**
   * Rich state description (API 30+). Provides custom state beyond checked/selected,
   * e.g., "On", "50%", "Expanded", "3 of 10".
   */
  val stateDescription: String? = null,

  /**
   * Semantic role override set by the app via `EXTRA_ROLE_DESCRIPTION` in extras
   * (e.g., "Toggle", "Tab", "Star Rating"). Fed by `ViewCompat.setAccessibilityDelegate`
   * on the View path and by Compose's `Modifier.semantics { role = ... }` on the Compose
   * path. Stable, app-defined, matchable.
   */
  val roleDescription: String? = null,

  /**
   * Compose `Modifier.testTag(...)` value, when the app has not opted into
   * `Modifier.semantics { testTagsAsResourceId = true }` (which would surface it as
   * [resourceId] instead). Read from extras under `androidx.compose.ui.semantics.testTag`
   * if Compose UI populated it. Null on classic-View screens and on Compose screens
   * whose authors did opt into the resource-id route.
   */
  val composeTestTag: String? = null,

  /** Whether the node is currently showing hint text rather than actual input (API 26+). */
  val isShowingHintText: Boolean = false,

  // --- State ---
  val isEnabled: Boolean = true,
  val isClickable: Boolean = false,
  val isLongClickable: Boolean = false,
  val isFocusable: Boolean = false,
  val isFocused: Boolean = false,
  val isCheckable: Boolean = false,
  val isChecked: Boolean = false,
  val isSelected: Boolean = false,
  val isEditable: Boolean = false,
  val isScrollable: Boolean = false,
  val isPassword: Boolean = false,
  val isMultiLine: Boolean = false,

  /** Whether this node is actually visible on screen (not off-screen/hidden/covered). */
  val isVisibleToUser: Boolean = true,

  /** Whether this node is a structural heading (API 28+). */
  val isHeading: Boolean = false,

  /** Whether the content is currently invalid (form validation) (API 19+). */
  val isContentInvalid: Boolean = false,

  /** Whether the text is selectable (API 33+). */
  val isTextSelectable: Boolean = false,

  /** Whether the node is important for accessibility. */
  val isImportantForAccessibility: Boolean = true,

  // --- Input ---
  /**
   * The input type for editable fields, matching Android's `InputType` constants.
   * E.g., `InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS`, `InputType.TYPE_CLASS_NUMBER`.
   */
  val inputType: Int = 0,

  /** Maximum text length for input fields. 0 means no limit. */
  val maxTextLength: Int = 0,

  // --- Bounds ---
  /** Bounding rect in screen coordinates: left, top, right, bottom. */
  val boundsInScreen: Bounds? = null,

  /** Visual z-order among siblings. Higher values are drawn on top. */
  val drawingOrder: Int = 0,

  // --- Relationships ---
  /**
   * The text of the label node that describes this input (resolved from `getLabeledBy()`).
   * E.g., for a password field, this might be "Password".
   */
  val labeledByText: String? = null,

  /** Child nodes in the accessibility tree. */
  val children: List<AccessibilityNode> = emptyList(),

  // --- Actions ---
  /**
   * The list of standard accessibility actions available on this node.
   * Uses string names (e.g., "ACTION_CLICK", "ACTION_SCROLL_FORWARD", "ACTION_SET_TEXT")
   * rather than int IDs for readability and serialization.
   */
  val actions: List<String> = emptyList(),

  // --- Collection semantics ---
  /** For list/grid containers: row count, column count, and selection mode. */
  val collectionInfo: CollectionInfo? = null,

  /** For items inside a list/grid: row, column, span, and whether it's a header. */
  val collectionItemInfo: CollectionItemInfo? = null,

  /** For seekbar/progress/rating: current value, min, max. */
  val rangeInfo: RangeInfo? = null,
) {

  /** Resolves the best text for matching, following Maestro's priority: text > hintText > contentDescription. */
  fun resolveText(): String? = text ?: hintText ?: contentDescription

  /** Returns the center point of this node's bounds, or null if bounds are unknown. */
  fun centerPoint(): Pair<Int, Int>? = boundsInScreen?.let {
    Pair((it.left + it.right) / 2, (it.top + it.bottom) / 2)
  }

  /** Flattens this node and all descendants into a single list (pre-order DFS). */
  fun aggregate(): List<AccessibilityNode> =
    listOf(this) + children.flatMap { it.aggregate() }

  /** Finds the first node matching [predicate] via DFS, or null. */
  fun findFirst(predicate: (AccessibilityNode) -> Boolean): AccessibilityNode? {
    if (predicate(this)) return this
    for (child in children) {
      child.findFirst(predicate)?.let { return it }
    }
    return null
  }

  /** Finds all nodes matching [predicate] in the tree. */
  fun findAll(predicate: (AccessibilityNode) -> Boolean): List<AccessibilityNode> {
    val results = mutableListOf<AccessibilityNode>()
    if (predicate(this)) results.add(this)
    children.forEach { results.addAll(it.findAll(predicate)) }
    return results
  }

  /** Screen-coordinate bounding rectangle. */
  data class Bounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
  ) {
    val width get() = right - left
    val height get() = bottom - top
    val centerX get() = (left + right) / 2
    val centerY get() = (top + bottom) / 2

    /** Returns true if this bounds fully contains [other]. */
    fun contains(other: Bounds): Boolean =
      left <= other.left && top <= other.top && right >= other.right && bottom >= other.bottom

    /** Returns true if point (x, y) is within this bounds. */
    fun containsPoint(x: Int, y: Int): Boolean =
      x in left..right && y in top..bottom

    /** Returns true if this bounds overlaps with [other]. */
    fun intersects(other: Bounds): Boolean =
      left < other.right && right > other.left && top < other.bottom && bottom > other.top
  }

  /** Metadata for list/grid containers. */
  data class CollectionInfo(
    val rowCount: Int,
    val columnCount: Int,
    val isHierarchical: Boolean,
  )

  /** Position metadata for items within a list/grid. */
  data class CollectionItemInfo(
    val rowIndex: Int,
    val rowSpan: Int,
    val columnIndex: Int,
    val columnSpan: Int,
    val isHeading: Boolean,
  )

  /** Range metadata for seekbar/progress/rating nodes. */
  data class RangeInfo(
    val type: Int,
    val min: Float,
    val max: Float,
    val current: Float,
  )
}
