package xyz.block.trailblaze.compose.driver.rpc

import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.compose.driver.ComposeSemanticTreeMapper
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * [ScreenState] implementation built from a [GetScreenStateResponse] received via RPC.
 *
 * Mirrors [xyz.block.trailblaze.compose.driver.ComposeScreenState] but reconstructs
 * the state from serialized RPC data rather than from a live [ComposeUiTest] instance.
 */
class ComposeRpcScreenState(
  private val response: GetScreenStateResponse,
) : ScreenState {

  /** Maps element IDs (e.g., `"e1"`) to [ComposeSemanticTreeMapper.ComposeElementRef]s. */
  val elementIdMapping: Map<String, ComposeSemanticTreeMapper.ComposeElementRef> by lazy {
    response.elementIdMapping.mapValues { (_, ref) ->
      ComposeSemanticTreeMapper.ComposeElementRef(
        descriptor = ref.descriptor,
        nthIndex = ref.nthIndex,
        testTag = ref.testTag,
      )
    }
  }

  /**
   * Resolves an element ID string to a [ComposeSemanticTreeMapper.ComposeElementRef].
   *
   * Accepts both `"e5"` and `"[e5]"` formats.
   */
  fun resolveElementId(elementId: String): ComposeSemanticTreeMapper.ComposeElementRef? {
    val normalizedId = elementId.trim().removePrefix("[").removeSuffix("]")
    return elementIdMapping[normalizedId]
  }

  @OptIn(ExperimentalEncodingApi::class)
  override val screenshotBytes: ByteArray? by lazy {
    response.screenshotBase64?.let { Base64.decode(it) }
  }

  override val deviceWidth: Int
    get() = response.width

  override val deviceHeight: Int
    get() = response.height

  override val trailblazeNodeTree: TrailblazeNode?
    get() = response.trailblazeNodeTree

  override val viewHierarchyOriginal: ViewHierarchyTreeNode
    get() = response.viewHierarchy

  override val viewHierarchy: ViewHierarchyTreeNode by lazy {
    viewHierarchyOriginal
  }

  override val viewHierarchyTextRepresentation: String
    get() = response.semanticsTreeText

  override val trailblazeDevicePlatform: TrailblazeDevicePlatform
    get() = TrailblazeDevicePlatform.WEB

  override val deviceClassifiers: List<TrailblazeDeviceClassifier>
    get() =
      listOf(TrailblazeDeviceClassifier("desktop"), TrailblazeDeviceClassifier("compose"))
}
