package xyz.block.trailblaze.api

import kotlinx.serialization.json.JsonObject

data class FunctionCall(
  val name: String,
  val args: JsonObject,
)
