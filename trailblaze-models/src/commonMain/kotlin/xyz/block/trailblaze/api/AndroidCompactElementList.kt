package xyz.block.trailblaze.api

/**
 * Builds a compact, hierarchical element list from an Android [TrailblazeNode] tree.
 *
 * Mirrors the Playwright ARIA snapshot compact format but uses **native Android class names**
 * (e.g., `EditText`, `Button`, `RecyclerView`) instead of ARIA roles. Each meaningful element
 * is tagged with its [TrailblazeNode.nodeId] as `[nID]` for future ref resolution.
 *
 * Supports both [DriverNodeDetail.AndroidAccessibility] (from the accessibility service) and
 * [DriverNodeDetail.AndroidMaestro] (from UiAutomator via Maestro) via a common [AndroidNodeProps]
 * adapter.
 *
 * Example output:
 * ```
 * [n3] EditText "Search Settings" [focused]
 * [n5] Switch "Dark Mode" [checked]
 * [n6] Button "Submit" [disabled]
 * RecyclerView [12 items]:
 *   [n7] LinearLayout "Google"
 *     "Services & preferences"
 *   [n12] LinearLayout "Network & internet"
 *     "Mobile, Wi-Fi, hotspot"
 * ```
 */
object AndroidCompactElementList {

  data class CompactElements(
    val text: String,
    val elementNodeIds: List<Long>,
    /** Bounds for each element that was emitted with an [nID] ref, in emission order. */
    val elementBounds: List<TrailblazeNode.Bounds> = emptyList(),
    /** Maps ref slug (e.g., "ref:sign-in") to TrailblazeNode.nodeId for resolution. */
    val refMapping: Map<String, Long> = emptyMap(),
  )

  fun build(
    root: TrailblazeNode,
    details: Set<SnapshotDetail> = emptySet(),
    screenHeight: Int = 0,
  ): CompactElements {
    val lines = mutableListOf<String>()
    val elementNodeIds = mutableListOf<Long>()
    val elementBounds = mutableListOf<TrailblazeNode.Bounds>()
    val refMapping = mutableMapOf<String, Long>()
    val refTracker = ElementRef.RefTracker()
    val includeBounds = SnapshotDetail.BOUNDS in details
    val includeOffscreen = SnapshotDetail.OFFSCREEN in details
    var offscreenCount = 0
    buildRecursive(
      root, depth = 0, lines, elementNodeIds, elementBounds, refMapping, refTracker,
      includeBounds, includeOffscreen, screenHeight,
    ) {
      offscreenCount++
    }

    val text = buildString {
      if (lines.isEmpty()) {
        append("(no elements found)")
      } else {
        append(lines.joinToString("\n"))
      }
      if (!includeOffscreen && offscreenCount > 0) {
        append("\n($offscreenCount offscreen elements hidden — use --offscreen to show)")
      }
    }
    return CompactElements(
      text = text, elementNodeIds = elementNodeIds,
      elementBounds = elementBounds, refMapping = refMapping,
    )
  }

  // ---------------------------------------------------------------------------
  // Private adapter — normalizes AndroidAccessibility and AndroidMaestro
  // ---------------------------------------------------------------------------

  /**
   * Common property view over both Android [DriverNodeDetail] variants.
   *
   * [DriverNodeDetail.AndroidMaestro] lacks some fields that [DriverNodeDetail.AndroidAccessibility]
   * has (collectionInfo, isHeading, isEditable, etc.). Missing fields are approximated from the
   * available data or defaulted conservatively.
   */
  private class AndroidNodeProps private constructor(
    val className: String?,
    val packageName: String?,
    val text: String?,
    val contentDescription: String?,
    val hintText: String?,
    val stateDescription: String?,
    val paneTitle: String?,
    val error: String?,
    val isClickable: Boolean,
    val isCheckable: Boolean,
    val isChecked: Boolean,
    val isSelected: Boolean,
    val isFocused: Boolean,
    val isEditable: Boolean,
    val isEnabled: Boolean,
    val isScrollable: Boolean,
    val isPassword: Boolean,
    val isHeading: Boolean,
    val isVisibleToUser: Boolean,
    val isImportantForAccessibility: Boolean,
    val hasIdentifiableProperties: Boolean,
    val collectionInfo: DriverNodeDetail.AndroidAccessibility.CollectionInfo?,
    val rangeInfo: DriverNodeDetail.AndroidAccessibility.RangeInfo?,
  ) {
    companion object {
      fun from(d: DriverNodeDetail.AndroidAccessibility) = AndroidNodeProps(
        className = d.className,
        packageName = d.packageName,
        text = d.text,
        contentDescription = d.contentDescription,
        hintText = d.hintText,
        stateDescription = d.stateDescription,
        paneTitle = d.paneTitle,
        error = d.error,
        isClickable = d.isClickable,
        isCheckable = d.isCheckable,
        isChecked = d.isChecked,
        isSelected = d.isSelected,
        isFocused = d.isFocused,
        isEditable = d.isEditable,
        isEnabled = d.isEnabled,
        isScrollable = d.isScrollable,
        isPassword = d.isPassword,
        isHeading = d.isHeading,
        isVisibleToUser = d.isVisibleToUser,
        isImportantForAccessibility = d.isImportantForAccessibility,
        hasIdentifiableProperties = d.hasIdentifiableProperties,
        collectionInfo = d.collectionInfo,
        rangeInfo = d.rangeInfo,
      )

      fun from(d: DriverNodeDetail.AndroidMaestro) = AndroidNodeProps(
        className = d.className,
        packageName = d.resourceId?.substringBefore(':')?.takeIf { '.' in it },
        text = d.text,
        contentDescription = d.accessibilityText,
        hintText = d.hintText,
        stateDescription = null,
        paneTitle = null,
        error = null,
        isClickable = d.clickable,
        isCheckable = false,
        isChecked = d.checked,
        isSelected = d.selected,
        isFocused = d.focused,
        isEditable = d.className?.endsWith("EditText") == true,
        isEnabled = d.enabled,
        isScrollable = d.scrollable,
        isPassword = d.password,
        isHeading = false,
        isVisibleToUser = true,
        isImportantForAccessibility = true,
        hasIdentifiableProperties = d.hasIdentifiableProperties,
        collectionInfo = null,
        rangeInfo = null,
      )

      fun of(node: TrailblazeNode): AndroidNodeProps? = when (val d = node.driverDetail) {
        is DriverNodeDetail.AndroidAccessibility -> from(d)
        is DriverNodeDetail.AndroidMaestro -> from(d)
        else -> null
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Recursive builder
  // ---------------------------------------------------------------------------

  private fun buildRecursive(
    node: TrailblazeNode,
    depth: Int,
    lines: MutableList<String>,
    elementNodeIds: MutableList<Long>,
    elementBounds: MutableList<TrailblazeNode.Bounds>,
    refMapping: MutableMap<String, Long>,
    refTracker: ElementRef.RefTracker,
    includeBounds: Boolean = false,
    includeOffscreen: Boolean = false,
    screenHeight: Int = 0,
    offscreenCounter: () -> Unit = {},
  ) {
    val props = AndroidNodeProps.of(node) ?: return

    // Skip system UI
    if (props.packageName?.startsWith("com.android.systemui") == true) return
    // Skip non-visible nodes (count as offscreen if they have content)
    if (!props.isVisibleToUser && !includeOffscreen) {
      if (props.hasIdentifiableProperties) offscreenCounter()
      return
    }

    // Track offscreen-but-visible elements (e.g., below the fold in a scroll view)
    val offscreen = isOffscreen(node, screenHeight)
    if (offscreen && !includeOffscreen && isMeaningful(props, resolveLabel(props))) {
      offscreenCounter()
      return
    }

    val rawClass = props.className?.substringAfterLast('.') ?: ""
    // Suppress generic class names that add no information
    val shortClass = if (rawClass in GENERIC_CLASSES) "" else rawClass
    // For clickable containers without direct text, absorb the first child's label
    val label = resolveLabel(props) ?: resolveChildLabel(node, props)
    val isContainer = isContainer(props, shortClass)
    val isMeaningful = isMeaningful(props, label)
    val indent = "  ".repeat(depth)

    if (isContainer && hasVisibleDescendants(node)) {
      // Container: emit as "ClassName:" header with optional item count
      val containerLabel = when {
        label != null && shortClass.isNotEmpty() -> "$shortClass \"$label\""
        label != null -> "\"$label\""
        else -> shortClass
      }
      val itemCount = props.collectionInfo?.let { ci ->
        val count = if (ci.columnCount <= 1) ci.rowCount else ci.rowCount * ci.columnCount
        if (count > 0) " [$count items]" else ""
      } ?: ""
      lines.add("$indent$containerLabel$itemCount:")
      for (child in node.children) {
        buildRecursive(
          child, depth + 1, lines, elementNodeIds, elementBounds, refMapping,
          refTracker, includeBounds, includeOffscreen, screenHeight, offscreenCounter,
        )
      }
    } else if (isMeaningful) {
      // Meaningful element: emit with stable [ref:slug] and state annotations
      val descriptor = when {
        label != null && shortClass.isNotEmpty() -> "$shortClass \"$label\""
        label != null -> "\"$label\""
        shortClass.isNotEmpty() -> shortClass
        else -> ""
      }
      val center = node.bounds?.let { it.centerX to it.centerY } ?: (0 to 0)
      val ref = refTracker.ref(
        label, props.className?.substringAfterLast('.'), center.first, center.second,
      )
      val annotations = buildAnnotations(props, label)
      val boundsStr = if (includeBounds) boundsAnnotation(node) else ""
      val offscreenStr =
        if (includeOffscreen && isOffscreen(node, screenHeight)) " (offscreen)" else ""
      lines.add("$indent[$ref] $descriptor$annotations$boundsStr$offscreenStr")
      elementNodeIds.add(node.nodeId)
      node.bounds?.let { elementBounds.add(it) }
      refMapping[ref] = node.nodeId

      // Emit child content: non-interactive children become quoted strings under the
      // parent, interactive children recurse as separate elements
      for (child in node.children) {
        val childProps = AndroidNodeProps.of(child) ?: continue
        if (childProps.packageName?.startsWith("com.android.systemui") == true) continue
        if (!childProps.isVisibleToUser) continue
        val childLabel = resolveLabel(childProps)
        val childInteractive = childProps.isClickable || childProps.isEditable ||
          childProps.isCheckable || childProps.isHeading
        // Skip non-interactive children whose text was already absorbed into the parent
        // label. Interactive children (e.g., an EditText inside a FrameLayout with the
        // same hint text) must always be emitted so the agent knows it can interact.
        if (childLabel != null && childLabel == label && !childInteractive) continue
        if (childLabel != null && !childInteractive) {
          // Non-interactive text child → indented quoted string
          lines.add("$indent  \"$childLabel\"")
        } else {
          // Interactive or container child → recurse
          buildRecursive(
            child, depth + 1, lines, elementNodeIds, elementBounds, refMapping,
            refTracker, includeBounds, includeOffscreen, screenHeight, offscreenCounter,
          )
        }
      }
    } else {
      // Structural/transparent: skip this node, recurse children at same depth
      for (child in node.children) {
        buildRecursive(
          child, depth, lines, elementNodeIds, elementBounds, refMapping,
          refTracker, includeBounds, includeOffscreen, screenHeight, offscreenCounter,
        )
      }
    }
  }

  /** Resolves the best display label for a node. Normalizes whitespace to single line. */
  private fun resolveLabel(props: AndroidNodeProps): String? {
    val raw = props.text?.takeIf { it.isNotBlank() }
      ?: props.contentDescription?.takeIf { it.isNotBlank() }
      ?: props.hintText?.takeIf { it.isNotBlank() }
      ?: props.stateDescription?.takeIf { it.isNotBlank() }
      ?: props.paneTitle?.takeIf { it.isNotBlank() }
      ?: return null
    // Collapse newlines and multiple spaces into single spaces
    return raw.replace('\n', ' ').replace(Regex("\\s+"), " ").trim()
  }

  /**
   * For clickable/actionable containers without direct text (e.g., a clickable LinearLayout),
   * look at immediate children for a label to absorb. Returns the first child's text.
   * Checks all children regardless of visibility (the parent's visibility is what matters).
   */
  private fun resolveChildLabel(
    node: TrailblazeNode,
    props: AndroidNodeProps,
  ): String? {
    if (!props.isClickable && !props.isCheckable) return null
    for (child in node.children) {
      val childProps = AndroidNodeProps.of(child) ?: continue
      val childLabel = resolveLabel(childProps)
      if (childLabel != null) return childLabel
    }
    return null
  }

  /**
   * Builds state annotation string for a meaningful element.
   * Uses native Android accessibility terminology. Only non-default states are shown.
   */
  private fun buildAnnotations(
    props: AndroidNodeProps,
    label: String?,
  ): String {
    val parts = mutableListOf<String>()
    if (props.isCheckable && props.isChecked) parts.add("[checked]")
    if (props.isSelected) parts.add("[selected]")
    if (props.isFocused) parts.add("[focused]")
    if (!props.isEnabled) parts.add("[disabled]")
    if (props.isPassword) parts.add("[password]")
    if (props.isHeading) parts.add("[heading]")
    if (props.error != null) parts.add("[error: \"${props.error}\"]")
    props.rangeInfo?.let { ri ->
      val range = ri.max - ri.min
      if (range > 0) {
        val pct = ((ri.current - ri.min) / range * 100).toInt()
        parts.add("[value: $pct%]")
      }
    }
    // Show stateDescription only when it adds info beyond the label
    val sd = props.stateDescription?.takeIf { it.isNotBlank() }
    if (sd != null && sd != label && sd != props.text) {
      parts.add("[state: \"$sd\"]")
    }
    return if (parts.isEmpty()) "" else " ${parts.joinToString(" ")}"
  }

  /**
   * Whether this node should be treated as a structural container that gets a
   * "ClassName:" header with indented children, rather than an element with an [nID] ref.
   */
  private fun isContainer(props: AndroidNodeProps, shortClass: String): Boolean {
    // Scrollable nodes with collection info are lists/grids — show with item count
    if (props.isScrollable && props.collectionInfo != null) return true
    // Only show named container classes that carry collection semantics
    if (shortClass in LIST_CONTAINER_CLASSES && props.collectionInfo != null) return true
    // Pane-titled containers (dialogs, bottom sheets)
    if (props.paneTitle != null) return true
    return false
  }

  /**
   * Whether this node is meaningful enough to show with an element ref.
   * Includes interactive elements and content-bearing nodes.
   */
  private fun isMeaningful(props: AndroidNodeProps, label: String?): Boolean {
    // Interactive elements are always meaningful
    if (props.isClickable || props.isEditable || props.isCheckable) return true
    // Headings with text are meaningful (empty headings are just section dividers)
    if (props.isHeading && label != null) return true
    // Scrollable with a label is meaningful; bare scroll containers are transparent
    if (props.isScrollable && label != null) return true
    // Nodes with an error are meaningful (form validation)
    if (props.error != null) return true
    // Focused element is meaningful
    if (props.isFocused) return true
    // Named content (text or contentDescription) that is important for accessibility
    if (label != null && props.isImportantForAccessibility) return true
    return false
  }

  /** Checks if any descendant has visible, important content. */
  private fun hasVisibleDescendants(node: TrailblazeNode): Boolean {
    for (child in node.children) {
      val props = AndroidNodeProps.of(child) ?: continue
      if (!props.isVisibleToUser) continue
      if (props.packageName?.startsWith("com.android.systemui") == true) continue
      if (props.isImportantForAccessibility) return true
      if (hasVisibleDescendants(child)) return true
    }
    return false
  }

  /** Formats bounds as `{x,y,w,h}` annotation. */
  private fun boundsAnnotation(node: TrailblazeNode): String =
    CompactElementListUtils.boundsAnnotation(node)

  /** Checks if an element is below the visible screen area. */
  private fun isOffscreen(node: TrailblazeNode, screenHeight: Int): Boolean =
    CompactElementListUtils.isOffscreen(node, screenHeight)

  /**
   * Android scrollable container classes that get a "ClassName [N items]:" header.
   *
   * To keep up to date: check `android.widget` and `androidx.viewpager2` for new
   * scrollable containers. Run `adb shell dumpsys accessibility` on a device to see
   * class names used in practice. Consider adding `GridView`, `ExpandableListView`,
   * or any new Jetpack Compose lazy containers that surface via accessibility.
   *
   * Ref: https://developer.android.com/reference/android/widget/package-summary
   */
  private val LIST_CONTAINER_CLASSES = setOf(
    "RecyclerView",
    "ListView",
    "ViewPager",
    "ViewPager2",
  )

  /**
   * Class names too generic to be informative — suppress from output.
   *
   * To keep up to date: when new layout classes are added to AndroidX or Material,
   * check if they carry semantic meaning. Generic containers that only provide
   * positioning (not content semantics) belong here. Run
   * `adb shell uiautomator dump /dev/tty` on representative screens to see which
   * class names appear frequently as non-informative wrappers.
   *
   * Ref: https://developer.android.com/reference/android/view/ViewGroup subclasses
   */
  private val GENERIC_CLASSES = setOf(
    "View",
    "ViewGroup",
    "FrameLayout",
    "LinearLayout",
    "RelativeLayout",
    "ConstraintLayout",
    "CoordinatorLayout",
    "CardView",
    "MaterialCardView",
  )
}
