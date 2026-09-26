package xyz.block.trailblaze.mcp.utils

import io.ktor.util.decodeBase64Bytes
import kotlinx.coroutines.withTimeoutOrNull
import xyz.block.trailblaze.api.AndroidCompactElementList
import xyz.block.trailblaze.api.AnnotationElement
import xyz.block.trailblaze.api.EffectiveScreenshotScalingConfig
import xyz.block.trailblaze.api.MigrationScreenState
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.ScreenshotScalingConfig
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.mcp.TrailblazeMcpBridge
import xyz.block.trailblaze.mcp.android.ondevice.rpc.GetScreenStateResponse
import xyz.block.trailblaze.setofmark.SetOfMarkAnnotator
import java.awt.image.BufferedImage

/**
 * Adapter that wraps [GetScreenStateResponse] from RPC to the [ScreenState] interface.
 *
 * This enables on-device instrumentation screen state responses to be used
 * interchangeably with HOST mode screen state throughout the MCP server.
 *
 * For migration capture (`trailblaze.captureSecondaryTree=true`), construct via
 * [Companion.from] which transparently wraps the adapter in [MigrationScreenState] when
 * the response carries [GetScreenStateResponse.driverMigrationTreeNode]. Direct
 * construction skips that wrap and ignores the migration tree — only do that in tests
 * that don't exercise the migration path.
 */
class RpcScreenStateAdapter(
  private val response: GetScreenStateResponse,
  private val encodeAnnotated: ((BufferedImage) -> ByteArray)? = null,
) : ScreenState {

  companion object {
    /**
     * Builds a [ScreenState] from [response], wrapping with [MigrationScreenState] iff
     * [GetScreenStateResponse.driverMigrationTreeNode] is non-null. The wrap only
     * exposes the extra tree to call sites that opt in via `is MigrationScreenState`
     * — runtime tools and reports see the same plain [ScreenState] they always have.
     *
     * @param encodeAnnotated Writes the annotated screenshot this adapter draws when the device
     *   sent none. Pass one for the capture's own format; without it the drawing is a PNG.
     */
    fun from(
      response: GetScreenStateResponse,
      encodeAnnotated: ((BufferedImage) -> ByteArray)? = null,
    ): ScreenState {
      val base = RpcScreenStateAdapter(response, encodeAnnotated)
      return response.driverMigrationTreeNode?.let { MigrationScreenState.wrap(base, it) }
        ?: base
    }
  }

  private val _screenshotBytes: ByteArray? by lazy {
    response.screenshotBytes ?: response.screenshotBase64?.decodeBase64Bytes()
  }

  private val _annotatedScreenshotBytes: ByteArray? by lazy {
    response.annotatedScreenshotBytes
      ?: response.annotatedScreenshotBase64?.decodeBase64Bytes()
      ?: drawAnnotations()
  }

  private fun drawAnnotations(): ByteArray? {
    val elements = annotationElements
    // Nothing to mark: the screenshot is the answer, without a decode and re-encode.
    if (elements.isNullOrEmpty()) return _screenshotBytes
    return SetOfMarkAnnotator.annotate(
      screenshotBytes = _screenshotBytes,
      screenWidth = response.deviceWidth,
      screenHeight = response.deviceHeight,
      platform = TrailblazeDevicePlatform.ANDROID,
      annotationElements = elements,
      encode = encodeAnnotated ?: SetOfMarkAnnotator::encodePng,
    )
  }

  override val screenshotBytes: ByteArray?
    get() = _screenshotBytes

  /**
   * Annotated (set-of-mark) screenshot bytes: the device's rendering when the request asked for
   * one, otherwise drawn here on first read from the screenshot and [annotationElements].
   *
   * Only an LLM prompt (and the log of that request) reads these, so a capture that never reaches
   * the LLM — a recorded replay, a CLI `tool` call — never pays to render them.
   */
  override val annotatedScreenshotBytes: ByteArray
    get() = _annotatedScreenshotBytes ?: ByteArray(0)

  override val viewHierarchy: ViewHierarchyTreeNode
    get() = response.viewHierarchy

  override val deviceWidth: Int
    get() = response.deviceWidth

  override val deviceHeight: Int
    get() = response.deviceHeight

  /** Cached compact elements result — shared between text representation and annotation elements. */
  private val compactElements by lazy {
    val tree = response.trailblazeNodeTree ?: return@lazy null
    AndroidCompactElementList.build(tree, screenHeight = response.deviceHeight)
  }

  override val trailblazeNodeTree: TrailblazeNode? by lazy {
    val tree = response.trailblazeNodeTree ?: return@lazy null
    val elements = compactElements ?: return@lazy tree
    val nodeIdToRef = elements.refMapping.entries.associate { (ref, nodeId) -> nodeId to ref }
    tree.withRefs(nodeIdToRef)
  }

  override val viewHierarchyTextRepresentation: String? by lazy {
    val elements = compactElements ?: return@lazy null
    val header = response.pageContextSummary ?: ""
    if (header.isNotEmpty()) "$header\n\n${elements.text}" else elements.text
  }

  override val annotationElements: List<AnnotationElement>? by lazy {
    val elements = compactElements ?: return@lazy null
    val nodeIdToRef = elements.refMapping.entries.associate { (ref, nodeId) -> nodeId to ref }
    elements.elementNodeIds.zip(elements.elementBounds).map { (id, bounds) ->
      AnnotationElement(nodeId = id, bounds = bounds, refLabel = nodeIdToRef[id])
    }
  }

  override val trailblazeDevicePlatform: TrailblazeDevicePlatform
    get() = TrailblazeDevicePlatform.ANDROID

  override val deviceClassifiers: List<TrailblazeDeviceClassifier>
    get() = response.deviceClassifiers?.map { TrailblazeDeviceClassifier(it) } ?: emptyList()

  /**
   * Completeness of the capture the device made, forwarded verbatim. Null (unknown) when the
   * device didn't report one — an older on-device server, or a request that asked for no tree —
   * which keeps every host-side consumer on its pre-existing behaviour.
   */
  override val droppedNodeFetches: Int?
    get() = response.droppedNodeFetches
}

/**
 * Utility for capturing screen state using the best available method.
 *
 * This centralizes the screen capture logic used by both DeviceManagerToolSet
 * and SubagentOrchestrator, ensuring consistent behavior across the MCP server.
 */
object ScreenStateCaptureUtil {

  /** Default timeout for screen state capture operations */
  private const val CAPTURE_TIMEOUT_MS = 10_000L

  /**
   * Captures the current screen state using the best available method:
   *
   * 1. **On-device instrumentation**: Use RPC to query the on-device agent directly
   * 2. **HOST mode**: Use the direct screen state provider (Maestro driver)
   * 3. **Fallback**: Session-based capture
   *
   * @param mcpBridge The MCP bridge for device communication
   * @param timeoutMs Timeout for the capture operation (default: 10 seconds)
   * @param screenshotScalingConfig Configuration for scaling/compressing screenshots.
   *                                For on-device mode, scaling happens on-device before transfer
   *                                which saves bandwidth and tokens.
   * @return The captured screen state, or null if capture failed
   */
  suspend fun captureScreenState(
    mcpBridge: TrailblazeMcpBridge,
    timeoutMs: Long = CAPTURE_TIMEOUT_MS,
    screenshotScalingConfig: ScreenshotScalingConfig = EffectiveScreenshotScalingConfig.effective,
    fast: Boolean = false,
    includeAnnotatedScreenshot: Boolean = false,
    includeAllElements: Boolean = false,
  ): ScreenState? {
    return withTimeoutOrNull(timeoutMs) {
      // Priority 1: RPC for on-device instrumentation (most reliable for Android)
      if (mcpBridge.isOnDeviceInstrumentation()) {
        mcpBridge.getScreenStateViaRpc(
          includeScreenshot = !fast,
          screenshotScalingConfig = screenshotScalingConfig,
          includeAnnotatedScreenshot = includeAnnotatedScreenshot,
          includeAllElements = includeAllElements,
        )?.let { rpcResponse ->
          return@withTimeoutOrNull RpcScreenStateAdapter.from(rpcResponse)
        }
      }

      // Priority 2: Direct provider (most reliable for HOST mode)
      val directProvider = mcpBridge.getDirectScreenStateProvider(skipScreenshot = fast)
      directProvider?.let { provider ->
        try {
          return@withTimeoutOrNull provider(screenshotScalingConfig)
        } catch (_: Exception) {
          // Fall through to session-based capture
        }
      }

      // Priority 3: Session-based capture (fallback)
      try {
        mcpBridge.getCurrentScreenState()
      } catch (_: Exception) {
        null
      }
    }
  }
}
