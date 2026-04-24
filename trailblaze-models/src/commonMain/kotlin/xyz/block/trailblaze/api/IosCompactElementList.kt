package xyz.block.trailblaze.api

/**
 * Builds a compact, hierarchical element list from an iOS [TrailblazeNode] tree.
 *
 * Mirrors [AndroidCompactElementList] but for [DriverNodeDetail.IosMaestro]. Uses **native
 * UIKit class names** when available, otherwise just the element label. Each meaningful
 * element is tagged with its [TrailblazeNode.nodeId] as `[nID]` for future ref resolution.
 *
 * Example output:
 * ```
 * [n3] "Sign In"
 * [n4] "Dark Mode" [checked]
 * [n5] "Submit" [disabled]
 *   [n7] "Settings"
 *   [n12] "Privacy" [selected]
 * (3 offscreen elements hidden — use --offscreen to show)
 * ```
 */
object IosCompactElementList {

  /** Maximum label length before truncation. */
  private const val MAX_LABEL_LENGTH = 80

  data class CompactElements(
    val text: String,
    val elementNodeIds: List<Long>,
    /** Bounds for each element that was emitted with a ref, in emission order. */
    val elementBounds: List<TrailblazeNode.Bounds> = emptyList(),
    /** Maps ref slug (e.g., "ref:sign-in") to TrailblazeNode.nodeId for resolution. */
    val refMapping: Map<String, Long> = emptyMap(),
  )

  fun build(
    root: TrailblazeNode,
    details: Set<SnapshotDetail> = emptySet(),
    screenHeight: Int = 0,
    screenWidth: Int = 0,
  ): CompactElements {
    val lines = mutableListOf<String>()
    val elementNodeIds = mutableListOf<Long>()
    val elementBounds = mutableListOf<TrailblazeNode.Bounds>()
    val refMapping = mutableMapOf<String, Long>()
    val refTracker = ElementRef.RefTracker()
    val includeBounds = SnapshotDetail.BOUNDS in details
    val includeOffscreen = SnapshotDetail.OFFSCREEN in details
    val includeAllElements = SnapshotDetail.ALL_ELEMENTS in details
    var offscreenCount = 0
    val emittedLabels = mutableSetOf<String>()
    buildRecursive(
      root, 0, lines, elementNodeIds, elementBounds, refMapping, refTracker, emittedLabels,
      includeBounds = includeBounds,
      includeOffscreen = includeOffscreen,
      includeAllElements = includeAllElements,
      screenHeight = screenHeight,
      screenWidth = screenWidth,
      offscreenCounter = { offscreenCount++ },
    )

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
    return CompactElements(text = text, elementNodeIds = elementNodeIds, elementBounds = elementBounds, refMapping = refMapping)
  }

  private fun buildRecursive(
    node: TrailblazeNode,
    depth: Int,
    lines: MutableList<String>,
    elementNodeIds: MutableList<Long>,
    elementBounds: MutableList<TrailblazeNode.Bounds>,
    refMapping: MutableMap<String, Long>,
    refTracker: ElementRef.RefTracker,
    emittedLabels: MutableSet<String>,
    parentLabel: String? = null,
    includeBounds: Boolean = false,
    includeOffscreen: Boolean = false,
    includeAllElements: Boolean = false,
    screenHeight: Int = 0,
    screenWidth: Int = 0,
    offscreenCounter: () -> Unit = {},
  ) {
    val detail = node.driverDetail as? DriverNodeDetail.IosMaestro ?: return

    // Skip non-visible nodes
    if (!detail.visible) {
      if (!includeOffscreen && detail.hasIdentifiableProperties) offscreenCounter()
      return
    }

    // Filter system UI and decorative noise
    if (isSystemUi(detail, node)) return

    val shortClass = detail.className?.substringAfterLast('.') ?: ""
    val rawLabel = resolveLabel(detail)
    val label = rawLabel?.truncate(MAX_LABEL_LENGTH)
    val isContainer = isContainer(detail, shortClass)
    val indent = "  ".repeat(depth)

    // Skip if label duplicates parent or was already emitted at this scope
    val isDuplicate = label != null && (label == parentLabel || label in emittedLabels)

    // Track offscreen
    val offscreen = isOffscreen(node, screenHeight, screenWidth)
    if (offscreen && !includeOffscreen && label != null) {
      offscreenCounter()
      return
    }

    if (isContainer && hasVisibleDescendants(node)) {
      val containerLabel =
        if (shortClass.isNotEmpty()) shortClass
        else if (label != null && !isDuplicate) "\"$label\""
        else {
          for (child in node.children) {
            buildRecursive(child, depth, lines, elementNodeIds, elementBounds, refMapping, refTracker, emittedLabels, label, includeBounds, includeOffscreen, includeAllElements, screenHeight, screenWidth, offscreenCounter)
          }
          return
        }
      lines.add("$indent$containerLabel:")
      val childLabels = mutableSetOf<String>()
      for (child in node.children) {
        buildRecursive(child, depth + 1, lines, elementNodeIds, elementBounds, refMapping, refTracker, childLabels, label, includeBounds, includeOffscreen, includeAllElements, screenHeight, screenWidth, offscreenCounter)
      }
    } else if (!isDuplicate && (includeAllElements || isMeaningful(detail, label))) {
      val descriptor =
        if (label != null && shortClass.isNotEmpty()) "$shortClass \"$label\""
        else if (label != null) "\"$label\""
        else if (shortClass.isNotEmpty()) shortClass
        else {
          for (child in node.children) {
            buildRecursive(child, depth, lines, elementNodeIds, elementBounds, refMapping, refTracker, emittedLabels, parentLabel, includeBounds, includeOffscreen, includeAllElements, screenHeight, screenWidth, offscreenCounter)
          }
          return
        }
      val annotations = buildAnnotations(detail)
      val boundsStr = if (includeBounds) boundsAnnotation(node) else ""
      val offscreenStr = if (includeOffscreen && offscreen) " (offscreen)" else ""
      val center = node.bounds?.let { it.centerX to it.centerY } ?: (0 to 0)
      val ref = refTracker.ref(label, detail.className?.substringAfterLast('.'), center.first, center.second)
      lines.add("$indent[$ref] $descriptor$annotations$boundsStr$offscreenStr")
      elementNodeIds.add(node.nodeId)
      refMapping[ref] = node.nodeId
      node.bounds?.let { elementBounds.add(it) }
      if (label != null) emittedLabels.add(label)

      // Emit child text as quoted strings under the parent
      for (child in node.children) {
        val childDetail = child.driverDetail as? DriverNodeDetail.IosMaestro ?: continue
        if (!childDetail.visible && !includeOffscreen) continue
        if (isSystemUi(childDetail, child)) continue
        val childLabel = resolveLabel(childDetail)?.truncate(MAX_LABEL_LENGTH)
        // Dedupe against the parent label whether the parent's label is raw
        // ("Sign In") or a composite ("mobile: (408) 555-5270"). Without the
        // suffix check, an XCUIElement row wrapping a single text node would
        // redundantly echo the value as a child quoted string.
        if (childLabel != null &&
          (childLabel == label || label?.endsWith(": $childLabel") == true)
        ) {
          continue
        }
        val childInteractive = childDetail.clickable || childDetail.checked || childDetail.selected
        if (childLabel != null && !childInteractive) {
          // Non-interactive text → quoted string (skip if already emitted)
          if (childLabel !in emittedLabels) {
            lines.add("$indent  \"$childLabel\"")
            emittedLabels.add(childLabel)
          }
        } else if (childLabel == null || childLabel != label) {
          buildRecursive(child, depth + 1, lines, elementNodeIds, elementBounds, refMapping, refTracker, emittedLabels, label, includeBounds, includeOffscreen, includeAllElements, screenHeight, screenWidth, offscreenCounter)
        }
      }
    } else {
      // Structural/transparent: skip this node, recurse children at same depth
      for (child in node.children) {
        buildRecursive(child, depth, lines, elementNodeIds, elementBounds, refMapping, refTracker, emittedLabels, label ?: parentLabel, includeBounds, includeOffscreen, includeAllElements, screenHeight, screenWidth, offscreenCounter)
      }
    }
  }

  /**
   * Resolves the best display label for a node.
   *
   * Composes a category + value when both are available — the AX *label* (category) goes
   * in front of the AX *value* (data). Two places the category can come from:
   *
   *   1. `hintText` — Maestro maps iOS `placeholderValue` here. For a UITextField with
   *      placeholder "Email" and current input "user@example.com" this composes to
   *      `"Email: user@example.com"`.
   *
   *   2. `accessibilityText` — Maestro maps iOS AX `label` here (see
   *      [IOSDriver.mapViewHierarchy](https://github.com/mobile-dev-inc/Maestro/blob/main/maestro-client/src/main/java/maestro/drivers/IOSDriver.kt)
   *      : `attributes["accessibilityText"] = element.label`). For a Contacts row with
   *      label "mobile" and value "(408) 555-5270" this composes to
   *      `"mobile: (408) 555-5270"` — same shape we get on the AXe path.
   *
   *   Maestro itself prefers `title ?: value` for `attributes["text"]`, so whichever
   *   of value or title is present ends up in [DriverNodeDetail.IosMaestro.text]. The
   *   AX *label* always lives in `accessibilityText`. Either category source composes
   *   cleanly — this was the trapped data that made Host rows render as
   *   `"(408) 555-5270"` with no idea it was the *mobile* number.
   *
   * Fallback when no category is present: text > hintText > accessibilityText. Whitespace
   * normalized.
   */
  private fun resolveLabel(detail: DriverNodeDetail.IosMaestro): String? {
    val text = detail.text?.takeIf { it.isNotBlank() }?.normalize()
    val hint = detail.hintText?.takeIf { it.isNotBlank() }?.normalize()
    val ax = detail.accessibilityText?.takeIf { it.isNotBlank() }?.normalize()

    val category = when {
      hint != null && hint != text -> hint
      ax != null && ax != text -> ax
      else -> null
    }
    if (category != null && text != null) {
      return "$category: $text"
    }
    return text ?: hint ?: ax
  }

  private fun String.normalize(): String =
    replace('\n', ' ').replace(Regex("\\s+"), " ").trim()

  /** Truncates a string to [maxLength] with "..." suffix. */
  private fun String.truncate(maxLength: Int): String {
    return if (length <= maxLength) this else substring(0, maxLength - 1) + "…"
  }

  /**
   * Filters iOS system UI using bounds-based detection.
   *
   * Status bar: small non-interactive leaf elements in the top ~50px.
   * Also filters decorative elements (chevrons) and non-interactive tiny elements
   * that are clearly chrome (scroll indicators, battery badges).
   */
  private fun isSystemUi(detail: DriverNodeDetail.IosMaestro, node: TrailblazeNode): Boolean {
    val label = detail.text ?: detail.accessibilityText
    // Chevron disclosure indicators — decorative
    if (label == "chevron") return true

    val bounds = node.bounds ?: return false
    // Zero-size structural containers — not system UI, just wrappers
    if (bounds.width == 0 || bounds.height == 0) return false

    // Status bar: small non-interactive leaf elements in the top ~50px
    if (bounds.top < 50 && bounds.height < 30 && !detail.clickable && node.children.isEmpty()) {
      return true
    }

    // Scroll indicators: non-interactive elements with extreme aspect ratios
    // (very tall+narrow = vertical scrollbar, very wide+short = horizontal scrollbar)
    if (!detail.clickable && node.children.isEmpty()) {
      val ratio = if (bounds.height > 0) bounds.width.toFloat() / bounds.height else 1f
      // Vertical scrollbar: ratio < 0.1 (e.g., 30px wide x 696px tall)
      // Horizontal scrollbar: ratio > 10 (e.g., 278px wide x 30px tall)
      if (ratio < 0.1f || ratio > 10f) return true
      // Tiny decorative elements (< 5px in either dimension)
      if (bounds.width < 5 || bounds.height < 5) return true
    }

    return false
  }

  /**
   * Builds state annotation string. Only non-default states are shown.
   *
   * Leads with `[id=…]` when the element has an `accessibilityIdentifier` (exposed as
   * `resourceId` on [DriverNodeDetail.IosMaestro]). That's the most stable selector an
   * iOS app can provide; exposing it in the compact text lets the LLM prefer it over
   * brittle text matches.
   */
  private fun buildAnnotations(detail: DriverNodeDetail.IosMaestro): String {
    val parts = mutableListOf<String>()
    detail.resourceId?.takeIf { it.isNotBlank() }?.let { parts.add("[id=$it]") }
    if (detail.checked) parts.add("[checked]")
    if (detail.selected) parts.add("[selected]")
    if (detail.focused) parts.add("[focused]")
    if (!detail.enabled) parts.add("[disabled]")
    if (detail.password) parts.add("[password]")
    return if (parts.isEmpty()) "" else " ${parts.joinToString(" ")}"
  }

  /** Whether this node is a structural container. */
  private fun isContainer(detail: DriverNodeDetail.IosMaestro, shortClass: String): Boolean {
    if (detail.scrollable) return true
    if (shortClass in CONTAINER_CLASSES) return true
    return false
  }

  /** Whether this node is meaningful enough to show with an element ref. */
  private fun isMeaningful(detail: DriverNodeDetail.IosMaestro, label: String?): Boolean {
    if (detail.clickable) return true
    if (detail.checked || detail.selected) return true
    if (detail.focused) return true
    if (detail.scrollable && label != null) return true
    if (label != null) return true
    return false
  }

  /** Checks if any descendant has visible, non-system-UI content. */
  private fun hasVisibleDescendants(node: TrailblazeNode): Boolean {
    for (child in node.children) {
      val detail = child.driverDetail as? DriverNodeDetail.IosMaestro ?: continue
      if (!detail.visible) continue
      if (isSystemUi(detail, child)) continue
      if (detail.hasIdentifiableProperties) return true
      if (hasVisibleDescendants(child)) return true
    }
    return false
  }

  /** Formats bounds as `{x,y,w,h}` annotation. */
  private fun boundsAnnotation(node: TrailblazeNode): String =
    CompactElementListUtils.boundsAnnotation(node)

  /** Checks if an element is outside the visible screen area. */
  private fun isOffscreen(node: TrailblazeNode, screenHeight: Int, screenWidth: Int = 0): Boolean =
    CompactElementListUtils.isOffscreen(node, screenHeight, screenWidth)

  /**
   * iOS container classes that get a "ClassName:" header with indented children.
   *
   * To keep up to date: check Apple's UIKit documentation for new container types.
   * SwiftUI views surface through accessibility with UIKit class names (e.g.,
   * `UICollectionView` backs `List`/`LazyVStack`). Use Xcode's Accessibility
   * Inspector or `xcrun simctl io <device> enumerate` to discover class names
   * on real screens. Consider adding `UIPickerView`, `UISplitViewController`
   * containers, or new SwiftUI-backed classes as they appear.
   *
   * Ref: https://developer.apple.com/documentation/uikit/views_and_controls
   */
  private val CONTAINER_CLASSES =
    setOf(
      "UITableView",
      "UICollectionView",
      "UIScrollView",
      "UITabBar",
      "UINavigationBar",
      "UIToolbar",
      "UIPageViewController",
    )
}
