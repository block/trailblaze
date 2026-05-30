package xyz.block.trailblaze.api

/**
 * Internal helpers backing the [TrailblazeNode.toMatchDescriptor] extension
 * (declared in `MatchDescriptor.kt`). Exposed at the object level — rather than
 * as top-level `private fun`s — so unit tests can exercise the index-path walk
 * and the per-driver extraction matrix independently of building a full
 * descriptor. Anything that needs the descriptor itself should call the
 * receiver-extension, not these helpers.
 */
object MatchDescriptorBuilder {

  /**
   * Pre-order DFS walking child indices. Returns the index sequence from [root]
   * to [target] (`emptyList()` if target IS root), or null if target isn't in
   * the subtree.
   *
   * Assumes nodeIds are unique within the tree — behaviour is undefined when
   * multiple nodes share a nodeId. Production drivers assign unique IDs at
   * capture time; the only realistic source of collisions is hand-constructed
   * test fixtures that forget to set distinct ids (the default is `0L`).
   */
  fun indexPathOf(root: TrailblazeNode, target: TrailblazeNode): List<Int>? {
    if (root.nodeId == target.nodeId) return emptyList()
    root.children.forEachIndexed { i, child ->
      val sub = indexPathOf(child, target)
      if (sub != null) return listOf(i) + sub
    }
    return null
  }

  data class Identity(
    val matchedText: String?,
    val accessibilityId: String?,
    val resourceId: String?,
  )

  fun extractIdentity(detail: DriverNodeDetail): Identity = when (detail) {
    is DriverNodeDetail.AndroidAccessibility -> Identity(
      matchedText = detail.resolveText(),
      accessibilityId = detail.contentDescription,
      resourceId = detail.resourceId,
    )
    is DriverNodeDetail.AndroidMaestro -> Identity(
      matchedText = detail.resolveText(),
      accessibilityId = detail.accessibilityText,
      resourceId = detail.resourceId,
    )
    is DriverNodeDetail.IosMaestro -> Identity(
      matchedText = detail.resolveText(),
      accessibilityId = detail.accessibilityText,
      resourceId = detail.resourceId,
    )
    is DriverNodeDetail.IosAxe -> Identity(
      matchedText = detail.resolveText(),
      accessibilityId = detail.uniqueId,
      resourceId = detail.uniqueId,
    )
    is DriverNodeDetail.Compose -> Identity(
      matchedText = detail.resolveText(),
      accessibilityId = detail.contentDescription,
      resourceId = detail.testTag,
    )
    is DriverNodeDetail.Web -> Identity(
      matchedText = detail.ariaName,
      accessibilityId = detail.ariaDescriptor,
      resourceId = detail.dataTestId,
    )
  }
}
