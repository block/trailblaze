package xyz.block.trailblaze.compose.driver

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.util.Console

/**
 * ScreenState implementation for Compose testing.
 *
 * Captures the UI state via ComposeUiTest's semantics tree and screenshot APIs.
 * The semantics tree is mapped to [ViewHierarchyTreeNode] for compatibility with
 * the existing LLM prompt construction pipeline.
 */
@OptIn(ExperimentalTestApi::class)
class ComposeScreenState(
  private val composeUiTest: ComposeUiTest,
  private val viewportWidth: Int,
  private val viewportHeight: Int,
  private val requestedDetails: Set<ComposeViewHierarchyDetail> = emptySet(),
) : ScreenState {

  /** Shared lazy root node to avoid fetching the semantics tree multiple times. */
  private val rootSemanticsNode by lazy { composeUiTest.onRoot().fetchSemanticsNode() }

  /** Compact element list with element IDs for disambiguation. */
  val compactElements: ComposeSemanticTreeMapper.CompactComposeElements by lazy {
    val includeBounds = ComposeViewHierarchyDetail.BOUNDS in requestedDetails
    ComposeSemanticTreeMapper.buildCompactElementList(rootSemanticsNode, includeBounds)
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
      composeUiTest.onRoot().captureToImage()
    } catch (e: Exception) {
      Console.log("Warning: Screenshot capture failed: ${e.message}")
      null
    }
  }

  override val screenshotBytes: ByteArray? by lazy { capturedImage?.let { imageBitmapToPngBytes(it) } }

  override val deviceWidth: Int by lazy { capturedImage?.width ?: viewportWidth }

  override val deviceHeight: Int by lazy { capturedImage?.height ?: viewportHeight }

  override val trailblazeNodeTree: TrailblazeNode? by lazy {
    ComposeSemanticTreeMapper.mapToTrailblazeNode(rootSemanticsNode)
  }

  override val viewHierarchyOriginal: ViewHierarchyTreeNode by lazy {
    ComposeSemanticTreeMapper.map(rootSemanticsNode)
  }

  override val viewHierarchy: ViewHierarchyTreeNode by lazy {
    viewHierarchyOriginal
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
