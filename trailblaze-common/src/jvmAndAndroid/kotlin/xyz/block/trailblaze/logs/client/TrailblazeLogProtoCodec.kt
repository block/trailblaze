package xyz.block.trailblaze.logs.client

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.ByteString.Companion.toByteString
import xyz.block.trailblaze.ondevice.rpc.proto.AgentLogUpload
import xyz.block.trailblaze.ondevice.rpc.proto.OnDeviceRpcProtoCodec

/** Keeps persisted log models unchanged while lifting large tree fields onto protobuf. */
object TrailblazeLogProtoCodec {
  private val wireJson by lazy { Json(TrailblazeJsonInstance) { prettyPrint = false } }

  fun TrailblazeLog.toProto(): AgentLogUpload {
    val trees = trees()
    val encoded =
      wireJson.encodeToJsonElement(TrailblazeLog.serializer(), withoutProtobufTrees()).jsonObject
    val metadata = JsonObject(encoded.filterKeys { it !in trees.protobufFieldNames })
    return OnDeviceRpcProtoCodec.run {
      AgentLogUpload(
        log_type = encoded["class"]?.jsonPrimitive?.content ?: this@toProto::class.simpleName.orEmpty(),
        session_id = session.value,
        timestamp_epoch_millis = timestamp.toEpochMilliseconds(),
        metadata_json = metadata.toString().encodeToByteArray().toByteString(),
        view_hierarchy = trees.viewHierarchy?.toProto(),
        view_hierarchy_filtered = trees.viewHierarchyFiltered?.toProto(),
        trailblaze_node_tree = trees.trailblazeNodeTree?.toProto(),
        driver_migration_tree_node = trees.driverMigrationTreeNode?.toProto(),
      )
    }
  }

  fun AgentLogUpload.toModel(): TrailblazeLog {
    val metadata = wireJson.parseToJsonElement(metadata_json.utf8()).jsonObject.toMutableMap()
    OnDeviceRpcProtoCodec.run {
      view_hierarchy?.let {
        metadata["viewHierarchy"] = wireJson.encodeToJsonElement(it.toModel())
      }
      view_hierarchy_filtered?.let {
        metadata["viewHierarchyFiltered"] = wireJson.encodeToJsonElement(it.toModel())
      }
      trailblaze_node_tree?.let {
        metadata["trailblazeNodeTree"] = wireJson.encodeToJsonElement(it.toModel())
      }
      driver_migration_tree_node?.let {
        metadata["driverMigrationTreeNode"] = wireJson.encodeToJsonElement(it.toModel())
      }
    }
    return wireJson.decodeFromJsonElement(TrailblazeLog.serializer(), JsonObject(metadata))
  }

  private fun TrailblazeLog.trees(): LogTrees = when (this) {
    is TrailblazeLog.TrailblazeLlmRequestLog -> LogTrees(
      viewHierarchy = viewHierarchy,
      viewHierarchyFiltered = viewHierarchyFiltered,
      trailblazeNodeTree = trailblazeNodeTree,
      driverMigrationTreeNode = driverMigrationTreeNode,
      protobufFieldNames = setOf(
        "viewHierarchy",
        "viewHierarchyFiltered",
        "trailblazeNodeTree",
        "driverMigrationTreeNode",
      ),
    )
    is TrailblazeLog.AgentDriverLog -> LogTrees(
      viewHierarchy = viewHierarchy,
      trailblazeNodeTree = trailblazeNodeTree,
      driverMigrationTreeNode = driverMigrationTreeNode,
      protobufFieldNames = setOf(
        "viewHierarchy",
        "trailblazeNodeTree",
        "driverMigrationTreeNode",
      ),
    )
    is TrailblazeLog.TrailblazeSnapshotLog -> LogTrees(
      viewHierarchy = viewHierarchy,
      trailblazeNodeTree = trailblazeNodeTree,
      driverMigrationTreeNode = driverMigrationTreeNode,
      protobufFieldNames = setOf(
        "viewHierarchy",
        "trailblazeNodeTree",
        "driverMigrationTreeNode",
      ),
    )
    is TrailblazeLog.McpSamplingLog -> LogTrees(
      viewHierarchy = viewHierarchy,
      viewHierarchyFiltered = viewHierarchyFiltered,
      protobufFieldNames = setOf("viewHierarchy", "viewHierarchyFiltered"),
    )
    else -> LogTrees()
  }

  /**
   * Avoid walking hierarchy trees through kotlinx.serialization only to discard that JSON. The
   * required hierarchy properties use an empty placeholder which is removed from the encoded
   * object; nullable properties can be cleared directly.
   */
  private fun TrailblazeLog.withoutProtobufTrees(): TrailblazeLog = when (this) {
    is TrailblazeLog.TrailblazeLlmRequestLog -> copy(
      viewHierarchy = EMPTY_VIEW_HIERARCHY,
      viewHierarchyFiltered = null,
      trailblazeNodeTree = null,
      driverMigrationTreeNode = null,
    )
    is TrailblazeLog.AgentDriverLog -> copy(
      viewHierarchy = null,
      trailblazeNodeTree = null,
      driverMigrationTreeNode = null,
    )
    is TrailblazeLog.TrailblazeSnapshotLog -> copy(
      viewHierarchy = EMPTY_VIEW_HIERARCHY,
      trailblazeNodeTree = null,
      driverMigrationTreeNode = null,
    )
    is TrailblazeLog.McpSamplingLog -> copy(
      viewHierarchy = null,
      viewHierarchyFiltered = null,
    )
    else -> this
  }

  private data class LogTrees(
    val viewHierarchy: xyz.block.trailblaze.api.ViewHierarchyTreeNode? = null,
    val viewHierarchyFiltered: xyz.block.trailblaze.api.ViewHierarchyTreeNode? = null,
    val trailblazeNodeTree: xyz.block.trailblaze.api.TrailblazeNode? = null,
    val driverMigrationTreeNode: xyz.block.trailblaze.api.TrailblazeNode? = null,
    val protobufFieldNames: Set<String> = emptySet(),
  )

  private val EMPTY_VIEW_HIERARCHY = xyz.block.trailblaze.api.ViewHierarchyTreeNode()
}
