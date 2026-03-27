package xyz.block.trailblaze.mcp

import io.ktor.util.decodeBase64Bytes
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.compose.driver.rpc.GetScreenStateResponse as ComposeGetScreenStateResponse
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform

/**
 * Adapter that wraps [ComposeGetScreenStateResponse] from the Compose RPC server to the
 * [ScreenState] interface.
 *
 * Exposes the compact element list ([semanticsTreeText]) as [viewHierarchyTextRepresentation]
 * so the inner agent shows `[eN]` element IDs to the LLM. These IDs match the element mapping
 * in [ComposeScreenState], enabling `compose_click(elementId="e5")` to resolve correctly.
 */
class ComposeRpcScreenStateAdapter(
  private val response: ComposeGetScreenStateResponse,
) : ScreenState {

  private val _screenshotBytes: ByteArray? by lazy {
    response.screenshotBase64?.decodeBase64Bytes()
  }

  override val screenshotBytes: ByteArray?
    get() = _screenshotBytes

  override val annotatedScreenshotBytes: ByteArray
    get() = _screenshotBytes ?: ByteArray(0)

  override val viewHierarchy: ViewHierarchyTreeNode
    get() = response.viewHierarchy

  /**
   * Returns the compact element list with `[eN]` IDs from the Compose RPC server.
   *
   * This is critical for Compose tool resolution: the inner agent shows this text to the LLM,
   * which then references elements by their `eN` IDs in tool calls like
   * `compose_click(elementId="e5")`. Without this, the inner agent falls back to `[nodeId:X]`
   * format from the ViewHierarchyTreeNode, which uses a different numbering system and causes
   * element resolution failures.
   */
  override val viewHierarchyTextRepresentation: String
    get() = response.semanticsTreeText

  override val trailblazeNodeTree: TrailblazeNode?
    get() = response.trailblazeNodeTree

  override val deviceWidth: Int
    get() = response.width

  override val deviceHeight: Int
    get() = response.height

  override val trailblazeDevicePlatform: TrailblazeDevicePlatform
    get() = TrailblazeDevicePlatform.WEB

  override val deviceClassifiers: List<TrailblazeDeviceClassifier>
    get() =
      listOf(TrailblazeDeviceClassifier("desktop"), TrailblazeDeviceClassifier("compose"))
}
