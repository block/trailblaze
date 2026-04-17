package xyz.block.trailblaze.api

import kotlin.math.absoluteValue

/**
 * Generates stable, short element reference IDs by hashing element properties.
 *
 * Format: 1 letter + 1-3 digits (e.g., `a1`, `k42`, `z103`).
 * The hash is computed from the element's text, class name, and screen position,
 * making it **stable across captures** of the same screen — a toast appearing or
 * element loading async doesn't shift other IDs.
 *
 * Collisions (same hash for different elements) are resolved with a letter suffix:
 * `k42`, `k42b`, `k42c`.
 *
 * 26,000 possible values (26×1000) — negligible collision rate for typical
 * screens with 20-100 elements.
 */
object ElementRef {

  /**
   * Tracks emitted refs within a single snapshot to detect and resolve collisions.
   */
  class RefTracker {
    private val emitted = mutableMapOf<String, Int>()

    /**
     * Generates a stable ref for an element. Same properties → same ref across captures.
     * @param label The element's display text (text, contentDescription, etc.)
     * @param className Short class name (e.g., "Button", "EditText")
     * @param centerX Center X coordinate (rounded to nearest 10 for minor shift tolerance)
     * @param centerY Center Y coordinate (rounded to nearest 10 for minor shift tolerance)
     */
    fun ref(label: String?, className: String?, centerX: Int = 0, centerY: Int = 0): String {
      val base = computeHash(label, className, centerX, centerY)
      val count = (emitted[base] ?: 0) + 1
      emitted[base] = count
      return if (count == 1) base else "$base${('a' + count - 1)}"
    }
  }

  /**
   * Resolves a ref back to its TrailblazeNode by re-walking the tree with the same
   * hash computation used during snapshot generation. Both the compact element list
   * and the `tap` tool use this, ensuring consistent resolution.
   */
  fun resolveRef(root: TrailblazeNode, targetRef: String): TrailblazeNode? {
    val tracker = RefTracker()
    return resolveInTree(root, tracker, targetRef)
  }

  private fun resolveInTree(
    node: TrailblazeNode,
    tracker: RefTracker,
    targetRef: String,
  ): TrailblazeNode? {
    val detail = node.driverDetail

    // Skip system UI (Android)
    if (detail is DriverNodeDetail.AndroidAccessibility) {
      if (detail.packageName?.startsWith("com.android.systemui") == true) return null
      if (!detail.isVisibleToUser) return null
    }
    // Skip non-visible (iOS)
    if (detail is DriverNodeDetail.IosMaestro && !detail.visible) return null

    val label = when (detail) {
      is DriverNodeDetail.AndroidAccessibility -> {
        val rawLabel = detail.text?.takeIf { it.isNotBlank() }
          ?: detail.contentDescription?.takeIf { it.isNotBlank() }
          ?: detail.hintText?.takeIf { it.isNotBlank() }
        // Normalize whitespace like the compact list does
        rawLabel?.replace('\n', ' ')?.replace(Regex("\\s+"), " ")?.trim()
      }
      is DriverNodeDetail.AndroidMaestro -> {
        val rawLabel = detail.text?.takeIf { it.isNotBlank() }
          ?: detail.accessibilityText?.takeIf { it.isNotBlank() }
          ?: detail.hintText?.takeIf { it.isNotBlank() }
        rawLabel?.replace('\n', ' ')?.replace(Regex("\\s+"), " ")?.trim()
      }
      is DriverNodeDetail.IosMaestro ->
        detail.text?.takeIf { it.isNotBlank() }
          ?: detail.accessibilityText?.takeIf { it.isNotBlank() }
          ?: detail.hintText?.takeIf { it.isNotBlank() }
      else -> null
    }
    val className = when (detail) {
      is DriverNodeDetail.AndroidAccessibility ->
        detail.className?.substringAfterLast('.')
      is DriverNodeDetail.AndroidMaestro ->
        detail.className?.substringAfterLast('.')
      is DriverNodeDetail.IosMaestro ->
        detail.className?.substringAfterLast('.')
      else -> null
    }
    // Absorb child label for clickable Android containers without direct text
    val effectiveLabel = label ?: when (detail) {
      is DriverNodeDetail.AndroidAccessibility -> {
        if (detail.isClickable || detail.isCheckable) {
          node.children.firstNotNullOfOrNull { child ->
            val cd = child.driverDetail as? DriverNodeDetail.AndroidAccessibility
            cd?.text?.takeIf { it.isNotBlank() }
              ?: cd?.contentDescription?.takeIf { it.isNotBlank() }
              ?: cd?.hintText?.takeIf { it.isNotBlank() }
          }?.replace('\n', ' ')?.replace(Regex("\\s+"), " ")?.trim()
        } else null
      }
      is DriverNodeDetail.AndroidMaestro -> {
        if (detail.clickable) {
          node.children.firstNotNullOfOrNull { child ->
            val cd = child.driverDetail as? DriverNodeDetail.AndroidMaestro
            cd?.text?.takeIf { it.isNotBlank() }
              ?: cd?.accessibilityText?.takeIf { it.isNotBlank() }
              ?: cd?.hintText?.takeIf { it.isNotBlank() }
          }?.replace('\n', ' ')?.replace(Regex("\\s+"), " ")?.trim()
        } else null
      }
      else -> null
    }

    val isMeaningful = when (detail) {
      is DriverNodeDetail.AndroidAccessibility ->
        detail.isClickable || detail.isEditable || detail.isCheckable ||
          (detail.isHeading && effectiveLabel != null) || detail.isFocused ||
          (detail.isScrollable && effectiveLabel != null) ||
          detail.error != null ||
          (effectiveLabel != null && detail.isImportantForAccessibility)
      is DriverNodeDetail.AndroidMaestro ->
        detail.clickable || detail.focused || detail.checked || detail.selected ||
          effectiveLabel != null
      is DriverNodeDetail.IosMaestro ->
        detail.clickable || detail.checked || detail.selected ||
          detail.focused || effectiveLabel != null
      else -> false
    }

    if (isMeaningful) {
      val center = node.bounds?.let { it.centerX to it.centerY } ?: (0 to 0)
      val computedRef = tracker.ref(effectiveLabel, className, center.first, center.second)
      if (computedRef == targetRef) return node
    }

    for (child in node.children) {
      val result = resolveInTree(child, tracker, targetRef)
      if (result != null) return result
    }
    return null
  }

  /**
   * Computes a short hash: 1 letter + 1-3 digits (e.g., `a1`, `k42`, `z103`).
   * Input is the element's identity properties; output is deterministic.
   */
  private fun computeHash(label: String?, className: String?, centerX: Int, centerY: Int): String {
    // Round position to nearest 10px for tolerance against minor layout shifts
    val roundedX = (centerX / 10) * 10
    val roundedY = (centerY / 10) * 10
    val identity = "${className ?: ""}|${label ?: ""}|$roundedX,$roundedY"
    val hash = identity.hashCode().absoluteValue.toUInt()

    val letter = 'a' + (hash % 26u).toInt()
    val number = ((hash / 26u) % 1000u).toInt()
    return "$letter$number"
  }
}
