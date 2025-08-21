@file:OptIn(ExperimentalSerializationApi::class)

package xyz.block.trailblaze.logs.client.temp

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import xyz.block.trailblaze.toolcalls.TrailblazeTool

/**
 * Holds the raw JSON for commands that are not on the classpath.
 *
 * This happens when the server or logs sees a client defined command.
 */
@Serializable(with = OtherTrailblazeToolFlatSerializer::class)
data class OtherTrailblazeTool(
  val toolName: String,
  val raw: JsonObject = JsonObject(emptyMap()),
) : TrailblazeTool
