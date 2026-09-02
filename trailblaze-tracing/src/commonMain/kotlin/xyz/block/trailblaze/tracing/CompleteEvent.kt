package xyz.block.trailblaze.tracing

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.time.Duration

/**
 * One Chrome Trace "X" (Complete) event.
 *
 * [trid]/[sid]/[psid] are ours, not part of the Chrome Trace format: the trace this span belongs to,
 * the span's own identity, and its PARENT's — recorded by the enclosing `trace { }` frame rather
 * than inferred from timestamps. Consumers that understand them (the `trailblaze profile`
 * extractor) get exact nesting; Perfetto ignores the unknown keys and falls back to its own
 * (pid, tid) + containment nesting, so a trace stays Perfetto-loadable. They are omitted from the
 * JSON when absent — a root span has no [psid], and an event added straight through
 * [TrailblazeTraceRecorder.add] has no [sid] (the recorder still stamps its [trid]).
 *
 * [trid] is what makes spans from separate processes mergeable: a host run, the daemon it calls and
 * an on-device run each record into their own recorder, and a shared trace id plus globally-unique
 * span ids let their events land in one tree instead of overwriting each other.
 */
@OptIn(kotlin.time.ExperimentalTime::class)
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
  /** This span's id: 16 lowercase hex characters, unique across processes. */
  val sid: String? = null,
  /** The id of the span this one was opened inside, or null when it is a root. */
  val psid: String? = null,
  /**
   * The trace this span belongs to: 32 lowercase hex characters, one per recording.
   *
   * After [sid] and [psid], deliberately. Any nullable field placed ahead of them would let
   * existing positional construction compile unchanged while silently reading the old span id as
   * something else — so append new fields here rather than inserting them above.
   */
  val trid: String? = null,
  /** This span's role in a call. Null means [SpanKind.INTERNAL] — see that enum for why. */
  val kind: SpanKind? = null,
) {
  fun toJsonObject(): JsonObject {
    TRACING_JSON_INSTANCE.encodeToString(this@CompleteEvent).let {
      return TRACING_JSON_INSTANCE.parseToJsonElement(it).jsonObject
    }
  }
}
