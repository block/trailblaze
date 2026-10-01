package xyz.block.trailblaze.decision

import ai.koog.prompt.message.Message
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import xyz.block.trailblaze.logs.client.TrailblazeLog

/**
 * Marks an LLM-client response that no LLM produced. A client that answers some requests itself,
 * from a decision request or by a fixed rule, says so in the response's metadata. The session log
 * keeps the response verbatim, so the report can show the request as answered that way instead of
 * as a zero-token call to the configured model.
 */
object AnsweredWithoutLlm {
  /** Response metadata key. Its value says what answered. */
  const val METADATA_KEY = "trailblaze.answeredBy"

  /** Answered from a decision request logged under the same trace. */
  const val DECISION = "decision"

  /** Response metadata recording that [answeredBy] answered, not an LLM. */
  fun metadata(answeredBy: String): JsonObject = buildJsonObject { put(METADATA_KEY, answeredBy) }

  /** What answered [response], or null when an LLM did. */
  fun of(response: Message.Assistant): String? =
    (response.metaInfo.metadata?.get(METADATA_KEY) as? JsonPrimitive)?.contentOrNull

  /** What answered the request [log] records, or null when an LLM did. */
  fun of(log: TrailblazeLog.TrailblazeLlmRequestLog): String? = log.llmResponse.firstNotNullOfOrNull { of(it) }

  /** The LLM request logs in [logs] that a model answered: what LLM call counts and costs cover. */
  fun modelRequests(logs: List<TrailblazeLog>): List<TrailblazeLog.TrailblazeLlmRequestLog> =
    logs.filterIsInstance<TrailblazeLog.TrailblazeLlmRequestLog>().filter { of(it) == null }
}
