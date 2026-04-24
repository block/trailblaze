package xyz.block.trailblaze.api

/**
 * Compact element-list renderer for iOS trees backed by [DriverNodeDetail.IosAxe]
 * (AXe CLI path). Leans into AX-native fields that Maestro/XCUITest can't surface —
 * role + subrole for accurate typing, both AXLabel **and** AXValue for labeled fields,
 * custom actions for gesture-driven interaction, and AXUniqueId when the app set one.
 *
 * Emission shape:
 *   `[ref] Type "label: value" [id=foo] [subrole=…] [actions=…] [disabled] {bounds}`
 *
 * Label vs value:
 *   iOS Contacts-style rows carry both AXLabel (the *category*, e.g. "home") and
 *   AXValue (the *data*, e.g. "(555) 478-7672"). Maestro/XCUITest only sees one of
 *   them; AXe gives us both, so we emit `"home: (555) 478-7672"` when they differ and
 *   fall back to a single string when only one exists or they're identical.
 *
 * UniqueId:
 *   When the app sets `accessibilityIdentifier`, AXe surfaces it as AXUniqueId. We emit
 *   it as `[id=…]` so the LLM can prefer it for stable selectors (it survives copy
 *   changes, localization, and layout shifts in a way text-based matches don't).
 */
object IosAxeCompactElementList {

  private const val MAX_LABEL_LENGTH = 120

  data class CompactElements(
    val text: String,
    val elementNodeIds: List<Long>,
    val elementBounds: List<TrailblazeNode.Bounds> = emptyList(),
    val refMapping: Map<String, Long> = emptyMap(),
  )

  fun build(
    root: TrailblazeNode,
    details: Set<SnapshotDetail> = emptySet(),
    screenHeight: Int = 0,
    screenWidth: Int = 0,
  ): CompactElements {
    val includeBounds = SnapshotDetail.BOUNDS in details
    val includeOffscreen = SnapshotDetail.OFFSCREEN in details
    val includeAllElements = SnapshotDetail.ALL_ELEMENTS in details
    val lines = mutableListOf<String>()
    val elementNodeIds = mutableListOf<Long>()
    val elementBounds = mutableListOf<TrailblazeNode.Bounds>()
    val refMapping = mutableMapOf<String, Long>()
    val refTracker = ElementRef.RefTracker()
    var offscreenCount = 0
    walk(
      node = root,
      depth = 0,
      lines = lines,
      elementNodeIds = elementNodeIds,
      elementBounds = elementBounds,
      refMapping = refMapping,
      refTracker = refTracker,
      includeBounds = includeBounds,
      includeOffscreen = includeOffscreen,
      includeAllElements = includeAllElements,
      screenHeight = screenHeight,
      screenWidth = screenWidth,
      onOffscreen = { offscreenCount++ },
    )
    val text = buildString {
      if (lines.isEmpty()) append("(no elements found)")
      else append(lines.joinToString("\n"))
      if (!includeOffscreen && offscreenCount > 0) {
        append("\n($offscreenCount offscreen elements hidden — use --offscreen to show)")
      }
    }
    return CompactElements(
      text = text,
      elementNodeIds = elementNodeIds,
      elementBounds = elementBounds,
      refMapping = refMapping,
    )
  }

  private fun walk(
    node: TrailblazeNode,
    depth: Int,
    lines: MutableList<String>,
    elementNodeIds: MutableList<Long>,
    elementBounds: MutableList<TrailblazeNode.Bounds>,
    refMapping: MutableMap<String, Long>,
    refTracker: ElementRef.RefTracker,
    includeBounds: Boolean,
    includeOffscreen: Boolean,
    includeAllElements: Boolean,
    screenHeight: Int,
    screenWidth: Int,
    onOffscreen: () -> Unit,
  ) {
    val detail = node.driverDetail as? DriverNodeDetail.IosAxe
    if (detail == null) {
      node.children.forEach {
        walk(it, depth, lines, elementNodeIds, elementBounds, refMapping, refTracker,
          includeBounds, includeOffscreen, includeAllElements, screenHeight, screenWidth, onOffscreen)
      }
      return
    }

    val offscreen = CompactElementListUtils.isOffscreen(node, screenHeight, screenWidth)
    if (offscreen && !includeOffscreen) {
      if (detail.hasIdentifiableProperties) onOffscreen()
      return
    }

    val composite = composeDisplayText(detail)
    val type = detail.type?.takeIf { it.isNotBlank() }
    val descriptor = when {
      composite != null && type != null -> "$type \"$composite\""
      composite != null -> "\"$composite\""
      type != null -> type
      else -> null
    }

    // Emit when:
    //  - there's something to describe (descriptor != null), AND
    //  - either the node has an identifiable AX property (label/value/uniqueId/title),
    //    or we have a non-blank type we can render, or ALL_ELEMENTS is on.
    // Bare descriptor without type is still allowed under ALL_ELEMENTS so `snapshot --all`
    // can see the full hierarchy — mirrors [IosCompactElementList]'s includeAllElements path.
    val shouldEmit = descriptor != null && (
      detail.hasIdentifiableProperties || type != null || includeAllElements
    )

    if (shouldEmit) {
      val indent = "  ".repeat(depth)
      val annotations = buildAnnotations(detail, composite)
      val boundsStr = if (includeBounds) CompactElementListUtils.boundsAnnotation(node) else ""
      val offscreenStr = if (includeOffscreen && offscreen) " (offscreen)" else ""
      val center = node.bounds?.let { it.centerX to it.centerY } ?: (0 to 0)
      // Use the composite (label+value) for ref keying so distinct rows with the same
      // category label (e.g. three "home" rows with different phone numbers) get distinct
      // refs and don't collide.
      val ref = refTracker.ref(composite, type, center.first, center.second)
      lines.add("$indent[$ref] $descriptor$annotations$boundsStr$offscreenStr")
      elementNodeIds.add(node.nodeId)
      refMapping[ref] = node.nodeId
      node.bounds?.let { elementBounds.add(it) }
      node.children.forEach {
        walk(it, depth + 1, lines, elementNodeIds, elementBounds, refMapping, refTracker,
          includeBounds, includeOffscreen, includeAllElements, screenHeight, screenWidth, onOffscreen)
      }
    } else {
      // Structural / empty container — skip, recurse at the same depth.
      node.children.forEach {
        walk(it, depth, lines, elementNodeIds, elementBounds, refMapping, refTracker,
          includeBounds, includeOffscreen, includeAllElements, screenHeight, screenWidth, onOffscreen)
      }
    }
  }

  /**
   * Compose AXLabel + AXValue into a single display string.
   *
   * Examples:
   * - label="home", value="(555) 478-7672"   → `"home: (555) 478-7672"`
   * - label="Send", value=null                → `"Send"`
   * - label=null, value="Enter email"         → `"Enter email"`
   * - label="5", value="5"                    → `"5"` (duplicate — dedupe)
   * - label="Password", value="••••"          → `"Password: ••••"`
   * - all null → falls back to title          → `"Contacts"`
   *
   * The composite is capped at [MAX_LABEL_LENGTH] so runaway AXValue strings
   * (e.g. a full paragraph inside a TextArea) don't blow up prompt size.
   */
  internal fun composeDisplayText(detail: DriverNodeDetail.IosAxe): String? {
    val label = detail.label?.takeIf { it.isNotBlank() }
    val value = detail.value?.takeIf { it.isNotBlank() }
    val primary = when {
      label != null && value != null && label != value -> "$label: $value"
      label != null -> label
      value != null -> value
      else -> detail.title?.takeIf { it.isNotBlank() }
    }
    return primary?.truncate(MAX_LABEL_LENGTH)
  }

  /**
   * Emits bracketed annotations in a priority order that surfaces the most actionable
   * signals first. Order: uniqueId (most stable selector when set by the app), subrole
   * (disambiguates AXSecureTextField etc.), customActions (gesture-driven interaction),
   * help/title (tooltip context when distinct from the visible text), disabled.
   *
   * [visibleText] is the composite the walker has already computed, passed in to avoid
   * recomputing on every annotation emission.
   */
  private fun buildAnnotations(detail: DriverNodeDetail.IosAxe, visibleText: String?): String {
    val parts = mutableListOf<String>()
    detail.uniqueId?.takeIf { it.isNotBlank() }?.let { parts += "[id=$it]" }
    detail.subrole?.takeIf { it.isNotBlank() }?.let { parts += "[subrole=$it]" }
    if (detail.customActions.isNotEmpty()) {
      parts += "[actions=${detail.customActions.joinToString(",")}]"
    }
    // help/title are supplementary — only emit when they add info beyond the composite.
    detail.help?.takeIf { it.isNotBlank() && it != visibleText }?.let { parts += "[help=$it]" }
    // title is usually the window/section title — only emit at leaves where it's extra info.
    detail.title
      ?.takeIf {
        it.isNotBlank() &&
          it != visibleText &&
          it != detail.label &&
          it != detail.value
      }
      ?.let { parts += "[title=$it]" }
    if (!detail.enabled) parts += "[disabled]"
    return if (parts.isEmpty()) "" else " ${parts.joinToString(" ")}"
  }

  private fun String.truncate(maxLength: Int): String =
    if (length <= maxLength) this else substring(0, maxLength - 1) + "…"
}
