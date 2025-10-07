package xyz.block.trailblaze.logs.model

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@Serializable
@JvmInline
value class TraceId @PublishedApi internal constructor(
  val traceId: String,
) {
  companion object {
    enum class TraceOrigin {
      LLM,
      TOOL,
      MAESTRO,
    }

    @OptIn(ExperimentalUuidApi::class)
    fun generate(
      /** Where the trace originated from. */
      origin: TraceOrigin,
    ) = TraceId(
      traceId = "${origin.name.lowercase()}-${Uuid.random()}",
    )
  }
}
