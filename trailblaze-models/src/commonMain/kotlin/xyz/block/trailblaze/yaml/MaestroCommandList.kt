package xyz.block.trailblaze.yaml

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class MaestroCommandList(
  val maestroCommands: List<JsonObject>,
)
