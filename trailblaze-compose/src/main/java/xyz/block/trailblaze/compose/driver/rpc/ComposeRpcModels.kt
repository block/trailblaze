package xyz.block.trailblaze.compose.driver.rpc

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.compose.driver.ComposeViewHierarchyDetail
import xyz.block.trailblaze.mcp.android.ondevice.rpc.RpcRequest
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult

/** Request to capture the current Compose screen state. */
@Serializable
data class GetScreenStateRequest(
  val requestedDetails: Set<ComposeViewHierarchyDetail> = emptySet(),
) : RpcRequest<GetScreenStateResponse>

/** Serializable element reference for RPC transport. */
@Serializable
data class SerializableComposeElementRef(
  val descriptor: String,
  val nthIndex: Int,
  val testTag: String?,
  /**
   * Screen-coordinate bounds carried over RPC so the host's set-of-mark
   * annotator can label refs without re-walking the on-device tree.
   * Nullable for back-compat with older payloads.
   */
  val bounds: TrailblazeNode.Bounds? = null,
)

/** Response containing the captured screen state. */
@Serializable
data class GetScreenStateResponse(
  val screenshotBase64: String?,
  val viewHierarchy: ViewHierarchyTreeNode,
  val semanticsTreeText: String,
  val width: Int,
  val height: Int,
  val elementIdMapping: Map<String, SerializableComposeElementRef> = emptyMap(),
  val trailblazeNodeTree: TrailblazeNode? = null,
)

/** Request to execute a batch of Compose tools. */
@Serializable
data class ExecuteToolsRequest(
  val tools: List<@Contextual TrailblazeTool>,
) : RpcRequest<ExecuteToolsResponse>

/** Response containing the results of executed tools. */
@Serializable
data class ExecuteToolsResponse(
  val results: List<TrailblazeToolResult>,
)
