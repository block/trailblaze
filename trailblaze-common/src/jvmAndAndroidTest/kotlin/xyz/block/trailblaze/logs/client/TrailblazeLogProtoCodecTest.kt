package xyz.block.trailblaze.logs.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import xyz.block.trailblaze.api.DriverNodeDetail
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.ondevice.rpc.proto.LogUploadEnvelope
import xyz.block.trailblaze.ondevice.rpc.proto.OnDeviceRpcProtoCodec

class TrailblazeLogProtoCodecTest {
  @Test
  fun `tree-bearing log round trips without duplicating hierarchy JSON`() {
    val original = TrailblazeLog.TrailblazeSnapshotLog(
      displayName = "checkout",
      screenshotFile = "checkout.png",
      viewHierarchy = ViewHierarchyTreeNode(
        text = "Checkout",
        children = listOf(ViewHierarchyTreeNode(text = "Pay now", clickable = true)),
      ),
      trailblazeNodeTree = TrailblazeNode(
        nodeId = 1,
        children = listOf(
          TrailblazeNode(
            nodeId = 2,
            driverDetail = DriverNodeDetail.AndroidAccessibility(text = "Pay now"),
          ),
        ),
        driverDetail = DriverNodeDetail.AndroidAccessibility(text = "Checkout"),
      ),
      viewHierarchyText = "Checkout\n  Pay now",
      deviceWidth = 1080,
      deviceHeight = 1920,
      session = SessionId("proto-log"),
      timestamp = Clock.System.now(),
    )

    val proto = TrailblazeLogProtoCodec.run { original.toProto() }
    val metadata = Json.parseToJsonElement(proto.metadata_json.utf8()).jsonObject
    val decoded = TrailblazeLogProtoCodec.run { proto.toModel() }

    assertFalse("viewHierarchy" in metadata)
    assertFalse("trailblazeNodeTree" in metadata)
    assertNotNull(proto.view_hierarchy)
    assertNotNull(proto.trailblaze_node_tree)
    assertEquals(original, decoded)
  }

  @Test
  fun `protobuf substantially reduces a hierarchy-heavy log payload`() {
    val children = (1..1_000).map { index ->
      ViewHierarchyTreeNode(
        nodeId = index.toLong(),
        text = "Item $index with a representative accessibility label",
        resourceId = "example:id/item_$index",
        className = "android.widget.TextView",
        x1 = 0,
        y1 = index * 10,
        x2 = 1080,
        y2 = index * 10 + 50,
        clickable = index % 3 == 0,
        enabled = true,
      )
    }
    val log = TrailblazeLog.TrailblazeSnapshotLog(
      displayName = "large",
      screenshotFile = "large.png",
      viewHierarchy = ViewHierarchyTreeNode(text = "Root", children = children),
      deviceWidth = 1080,
      deviceHeight = 1920,
      session = SessionId("large-log"),
      timestamp = Clock.System.now(),
    )
    val jsonBytes = TrailblazeJsonInstance
      .encodeToString(TrailblazeLog.serializer(), log)
      .encodeToByteArray()
    val protoBytes = OnDeviceRpcProtoCodec.encode(
      LogUploadEnvelope(
        upload_id = 1,
        agent_log = TrailblazeLogProtoCodec.run { log.toProto() },
      ),
    )

    println("hierarchy log bytes: JSON=${jsonBytes.size}, protobuf=${protoBytes.size}")
    assertTrue(protoBytes.size < jsonBytes.size / 2)
  }
}
