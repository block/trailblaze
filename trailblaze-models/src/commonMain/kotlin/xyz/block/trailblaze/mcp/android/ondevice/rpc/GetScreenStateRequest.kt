package xyz.block.trailblaze.mcp.android.ondevice.rpc

import kotlinx.serialization.Serializable
import xyz.block.trailblaze.api.TrailblazeImageFormat
import xyz.block.trailblaze.api.ViewHierarchyTreeNode

/**
 * RPC request to get the current screen state from the on-device agent.
 * 
 * This provides a synchronous way to capture screen state without executing
 * any tool/action. The response includes the view hierarchy and optional
 * screenshot data.
 * 
 * @param includeScreenshot Whether to include the screenshot bytes in the response.
 *                          Default is true. Set to false for faster, text-only responses.
 * @param filterViewHierarchy Whether to filter the view hierarchy to interactable elements only.
 *                            Default is true for smaller, more focused responses.
 * @param setOfMarkEnabled Whether to include set-of-mark annotations on the screenshot.
 *                         Default is false to reduce processing time.
 * @param screenshotMaxDimension1 Maximum dimension for the longer side of the screenshot.
 *                                Default is 1536. Screenshots are scaled down (never up) to fit.
 * @param screenshotMaxDimension2 Maximum dimension for the shorter side of the screenshot.
 *                                Default is 768. Screenshots are scaled down (never up) to fit.
 * @param screenshotImageFormat Format for the screenshot output ("PNG" or "JPEG").
 *                              Default is JPEG for smaller file sizes.
 * @param screenshotCompressionQuality Compression quality (0.0 to 1.0) for JPEG format.
 *                                     Default is 0.80.
 */
@Serializable
data class GetScreenStateRequest(
  val includeScreenshot: Boolean = true,
  val filterViewHierarchy: Boolean = true,
  val setOfMarkEnabled: Boolean = false,
  val screenshotMaxDimension1: Int = 1536,
  val screenshotMaxDimension2: Int = 768,
  val screenshotImageFormat: TrailblazeImageFormat = TrailblazeImageFormat.JPEG,
  val screenshotCompressionQuality: Float = 0.80f,
) : RpcRequest<GetScreenStateResponse>

/**
 * Response containing the current screen state.
 */
@Serializable
data class GetScreenStateResponse(
  /** The view hierarchy of the current screen. */
  val viewHierarchy: ViewHierarchyTreeNode,
  
  /** Base64-encoded screenshot bytes, or null if not requested or capture failed. */
  val screenshotBase64: String?,
  
  /** Device screen width in pixels. */
  val deviceWidth: Int,
  
  /** Device screen height in pixels. */
  val deviceHeight: Int,
)
