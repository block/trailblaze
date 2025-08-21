package xyz.block.trailblaze.tracing

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.time.Duration

@Serializable
data class CompleteEvent(
  val name: String,
  val cat: String = "app",
  @Serializable(with = InstantMicrosSerializer::class) val ts: Instant,
  @Serializable(with = DurationMicrosSerializer::class) val dur: Duration,
  val pid: Long,
  val tid: Long,
  val ph: String = "X",
  val args: Map<String, String> = emptyMap(),
) {
  fun toJsonObject(): JsonObject {
    TRACING_JSON_INSTANCE.encodeToString(this@CompleteEvent).let {
      return TRACING_JSON_INSTANCE.parseToJsonElement(it).jsonObject
    }
  }
}
