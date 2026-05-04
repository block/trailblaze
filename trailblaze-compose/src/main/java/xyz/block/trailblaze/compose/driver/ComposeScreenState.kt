package xyz.block.trailblaze.compose.driver

import xyz.block.trailblaze.api.AnnotationElement
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.compose.target.ComposeTestTarget
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.setofmark.SetOfMarkAnnotator
import xyz.block.trailblaze.util.Console

/**
 * ScreenState implementation for Compose testing.
 *
 * Captures the UI state via a [ComposeTestTarget]'s semantics tree and screenshot APIs. The
 * semantics tree is mapped to [ViewHierarchyTreeNode] for compatibility with the existing LLM
 * prompt construction pipeline.
 */
class ComposeScreenState(
  private val target: ComposeTestTarget,
  private val viewportWidth: Int,
  private val viewportHeight: Int,
  private val requestedDetails: Set<ComposeViewHierarchyDetail> = emptySet(),
) : ScreenState {

  /** Shared lazy root node to avoid fetching the semantics tree multiple times. */
  private val rootSemanticsNode by lazy { target.rootSemanticsNode() }

  /** All root nodes (main window + popups/dialogs) for complete UI coverage. */
  private val allRootNodes by lazy { target.allRootSemanticsNodes() }

  /** Compact element list with element IDs for disambiguation. */
  val compactElements: ComposeSemanticTreeMapper.CompactComposeElements by lazy {
    val includeBounds = ComposeViewHierarchyDetail.BOUNDS in requestedDetails
    ComposeSemanticTreeMapper.buildCompactElementList(allRootNodes, includeBounds)
  }

  /** Maps element IDs (e.g., `"e1"`) to [ComposeSemanticTreeMapper.ComposeElementRef]s. */
  val elementIdMapping: Map<String, ComposeSemanticTreeMapper.ComposeElementRef>
    get() = compactElements.elementIdMapping

  /**
   * Resolves an element ID string to a [ComposeSemanticTreeMapper.ComposeElementRef].
   *
   * Accepts both `"e5"` and `"[e5]"` formats.
   */
  fun resolveElementId(elementId: String): ComposeSemanticTreeMapper.ComposeElementRef? {
    val normalizedId = elementId.trim().removePrefix("[").removeSuffix("]")
    return elementIdMapping[normalizedId]
  }

  /** Text representation of the semantics tree for LLM prompts. */
  val semanticsTreeText: String by lazy { compactElements.text }

  private val capturedImage by lazy {
    try {
      target.captureScreenshot()
    } catch (e: Exception) {
      Console.log("Warning: Screenshot capture failed: ${e.message}")
      null
    }
  }

  override val screenshotBytes: ByteArray? by lazy { capturedImage?.let { imageBitmapToPngBytes(it) } }

  /**
   * Set-of-mark annotation elements keyed to the same `[eN]` IDs the LLM sees
   * in [viewHierarchyTextRepresentation]. Bounds come straight from the
   * compact-list builder ([ComposeSemanticTreeMapper.appendCompactNode]
   * stores `boundsInRoot` on each [ComposeSemanticTreeMapper.ComposeElementRef]
   * at the moment the element is assigned its `eN` id), so we don't re-walk
   * the semantics tree here. Refs without bounds or with zero-size rects are
   * dropped — drawing a label on the wrong place is worse than skipping it.
   */
  override val annotationElements: List<AnnotationElement>? by lazy {
    val out = mutableListOf<AnnotationElement>()
    var nodeId = 1L
    for ((id, ref) in elementIdMapping) {
      val bounds = ref.bounds ?: continue
      if (bounds.width <= 0 || bounds.height <= 0) continue
      out.add(AnnotationElement(nodeId = nodeId++, bounds = bounds, refLabel = id))
    }
    out.takeIf { it.isNotEmpty() }
  }

  override val annotatedScreenshotBytes: ByteArray? by lazy {
    SetOfMarkAnnotator.annotate(
      screenshotBytes = screenshotBytes,
      screenWidth = deviceWidth,
      screenHeight = deviceHeight,
      platform = trailblazeDevicePlatform,
      annotationElements = annotationElements,
    )
  }

  override val deviceWidth: Int by lazy { capturedImage?.width ?: viewportWidth }

  override val deviceHeight: Int by lazy { capturedImage?.height ?: viewportHeight }

  override val trailblazeNodeTree: TrailblazeNode? by lazy {
    ComposeSemanticTreeMapper.mapToTrailblazeNode(allRootNodes)
  }

  override val viewHierarchy: ViewHierarchyTreeNode by lazy {
    ComposeSemanticTreeMapper.map(allRootNodes)
  }

  override val viewHierarchyTextRepresentation: String by lazy {
    semanticsTreeText
  }

  override val trailblazeDevicePlatform: TrailblazeDevicePlatform
    get() = TrailblazeDevicePlatform.WEB

  override val deviceClassifiers: List<TrailblazeDeviceClassifier>
    get() =
      listOf(TrailblazeDeviceClassifier("desktop"), TrailblazeDeviceClassifier("compose"))
}
