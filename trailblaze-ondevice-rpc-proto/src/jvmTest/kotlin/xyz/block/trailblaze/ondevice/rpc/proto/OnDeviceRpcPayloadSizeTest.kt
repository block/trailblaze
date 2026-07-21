package xyz.block.trailblaze.ondevice.rpc.proto

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertTrue
import xyz.block.trailblaze.api.DriverNodeDetail
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.mcp.android.ondevice.rpc.GetScreenStateResponse

class OnDeviceRpcPayloadSizeTest {

  @Test
  fun `protobuf avoids base64 and repeated JSON field names`() {
    val screenshot = ByteArray(16_384) { (it % 251).toByte() }
    val nodes = (1L..120L).map { id ->
      TrailblazeNode(
        nodeId = id,
        bounds = TrailblazeNode.Bounds(0, id.toInt(), 1080, id.toInt() + 40),
        driverDetail = DriverNodeDetail.AndroidAccessibility(
          className = "android.widget.TextView",
          resourceId = "example:id/item_$id",
          text = "Catalog item $id",
          isEnabled = true,
          isClickable = true,
          isVisibleToUser = true,
          isImportantForAccessibility = true,
          actions = listOf("ACTION_CLICK", "ACTION_ACCESSIBILITY_FOCUS"),
        ),
      )
    }
    val tree = TrailblazeNode(
      nodeId = 0,
      children = nodes,
      driverDetail = DriverNodeDetail.AndroidAccessibility(className = "android.view.ViewGroup"),
    )
    val viewHierarchy = ViewHierarchyTreeNode(
      children = nodes.map { node ->
        ViewHierarchyTreeNode(
          nodeId = node.nodeId,
          text = "Catalog item ${node.nodeId}",
          resourceId = "example:id/item_${node.nodeId}",
          className = "android.widget.TextView",
          clickable = true,
        )
      },
    )
    val binaryModel = GetScreenStateResponse(
      viewHierarchy = viewHierarchy,
      screenshotBase64 = null,
      deviceWidth = 1080,
      deviceHeight = 1920,
      trailblazeNodeTree = tree,
    ).apply { screenshotBytes = screenshot }
    val legacyJsonModel = binaryModel.copy(
      screenshotBase64 = Base64.getEncoder().encodeToString(screenshot),
    )

    val binaryBytes = OnDeviceRpcProtoCodec.encode(
      RpcResponseEnvelope(
        request_id = 1,
        get_screen_state = OnDeviceRpcProtoCodec.run { binaryModel.toProto() },
      ),
    ).size
    val jsonBytes = TrailblazeJsonInstance.encodeToString(legacyJsonModel).encodeToByteArray().size

    assertTrue(
      binaryBytes * 2 < jsonBytes,
      "expected protobuf to be less than half of JSON ($binaryBytes vs $jsonBytes bytes)",
    )
  }
}
