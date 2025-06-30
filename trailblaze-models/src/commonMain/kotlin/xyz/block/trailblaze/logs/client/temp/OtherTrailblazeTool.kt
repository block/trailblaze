package xyz.block.trailblaze.logs.client.temp

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import xyz.block.trailblaze.toolcalls.TrailblazeTool

/**
 * Holds the raw JSON for commands that are not on the classpath.
 *
 * This happens when the server or logs sees a client defined command.
 */
@Serializable
data class OtherTrailblazeTool(
  val raw: JsonObject,
) : TrailblazeTool
