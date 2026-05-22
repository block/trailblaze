package xyz.block.trailblaze.cli.shortcut

import kotlinx.serialization.Serializable
import xyz.block.trailblaze.api.AgentDriverAction
import xyz.block.trailblaze.logs.client.TrailblazeJson
import java.io.File

/**
 * Reads the `action: AgentDriverAction` field from a session step's
 * `*_AgentDriverLog.json` file. The shared
 * [TrailblazeJson.defaultWithoutToolsInstance] is configured with
 * `ignoreUnknownKeys = true`, so declaring only the field we care about lets the
 * deserializer skip the heavy `viewHierarchy` / `trailblazeNodeTree` subtrees
 * entirely — no allocation of intermediate `JsonElement` wrappers.
 *
 * Returns null on:
 *  - any non-`AgentDriverLog` step (e.g. `_TrailblazeLlmRequestLog`,
 *    `_TrailblazeSnapshotLog`),
 *  - any parse failure (malformed json, missing field). Treating "no parseable
 *    action" as "transition has no recorded action" keeps the shortcut proposer
 *    robust to a single bad log file — same lenient policy the propose pipeline
 *    applies to session loading.
 */
object AgentDriverActionLoader {

  fun load(logFile: File): AgentDriverAction? {
    if (!logFile.isFile) return null
    if (!logFile.name.endsWith("_AgentDriverLog.json")) return null
    return try {
      val projection = TrailblazeJson.defaultWithoutToolsInstance
        .decodeFromString(ActionProjection.serializer(), logFile.readText())
      projection.action
    } catch (_: Exception) {
      null
    }
  }

  @Serializable
  private data class ActionProjection(
    val action: AgentDriverAction? = null,
  )
}
