package xyz.block.trailblaze.api

/**
 * Shared compact element result used by all [ScreenState] implementations to derive
 * [ScreenState.viewHierarchyTextRepresentation], [ScreenState.annotationElements],
 * and ref-labeled [ScreenState.trailblazeNodeTree].
 *
 * Created by platform-specific builders ([buildForAndroid], [buildForIos]) and then
 * consumed via the helper methods, eliminating duplicated code across
 * `HostMaestroDriverScreenState`, `AccessibilityServiceScreenState`, and
 * `AndroidOnDeviceUiAutomatorScreenState`.
 */
data class CompactScreenElements(
  val text: String,
  val elementNodeIds: List<Long>,
  val elementBounds: List<TrailblazeNode.Bounds>,
  val refMapping: Map<String, Long>,
) {

  /** Builds the `"App: ...\n\n<elements>"` text representation for LLM consumption. */
  fun buildTextRepresentation(
    foregroundAppId: String?,
    currentActivity: String? = null,
  ): String {
    val header = buildList {
        foregroundAppId?.let { add("App: $it") }
        currentActivity?.let { add("Activity: $it") }
      }
      .joinToString("\n")
    return if (header.isNotEmpty()) "$header\n\n$text" else text
  }

  /** Builds the [AnnotationElement] list for set-of-mark screenshot overlays. */
  fun buildAnnotationElements(): List<AnnotationElement> {
    val nodeIdToRef = refMapping.entries.associate { (ref, nodeId) -> nodeId to ref }
    return elementNodeIds.zip(elementBounds).map { (id, bounds) ->
      AnnotationElement(nodeId = id, bounds = bounds, refLabel = nodeIdToRef[id])
    }
  }

  /** Returns [tree] with stable hash refs applied from [refMapping]. */
  fun applyRefsToTree(tree: TrailblazeNode): TrailblazeNode {
    val nodeIdToRef = refMapping.entries.associate { (ref, nodeId) -> nodeId to ref }
    return tree.withRefs(nodeIdToRef)
  }

  companion object {

    fun buildForAndroid(
      tree: TrailblazeNode,
      details: Set<SnapshotDetail> = emptySet(),
      screenHeight: Int = 0,
    ): CompactScreenElements {
      val r = AndroidCompactElementList.build(tree, details, screenHeight)
      return CompactScreenElements(r.text, r.elementNodeIds, r.elementBounds, r.refMapping)
    }

    fun buildForIos(
      tree: TrailblazeNode,
      details: Set<SnapshotDetail> = emptySet(),
      screenHeight: Int = 0,
      screenWidth: Int = 0,
    ): CompactScreenElements {
      val r = IosCompactElementList.build(tree, details, screenHeight, screenWidth)
      return CompactScreenElements(r.text, r.elementNodeIds, r.elementBounds, r.refMapping)
    }
  }
}
