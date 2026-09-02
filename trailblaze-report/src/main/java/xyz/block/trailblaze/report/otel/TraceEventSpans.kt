package xyz.block.trailblaze.report.otel

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.SpanContext
import io.opentelemetry.api.trace.TraceFlags
import io.opentelemetry.api.trace.TraceState
import io.opentelemetry.sdk.common.InstrumentationLibraryInfo
import io.opentelemetry.sdk.common.InstrumentationScopeInfo
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.sdk.trace.IdGenerator
import io.opentelemetry.sdk.trace.data.EventData
import io.opentelemetry.sdk.trace.data.LinkData
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.data.StatusData
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import xyz.block.trailblaze.tracing.SpanKind as TrailblazeSpanKind
import io.opentelemetry.api.trace.SpanKind as OtelSpanKind

/**
 * Reads a session's `trace.json` — the Chrome Trace "X" events `TrailblazeTraceRecorder` writes —
 * as OpenTelemetry [SpanData], so OpenTelemetry's own exporters can serialize and ship it.
 *
 * We model the adapter and nothing else. The recorded spans have already finished, which is exactly
 * what [SpanData] describes, so there is no tracer, no sampler and no span processor in the way: the
 * events become [SpanData] and go straight to an exporter. Everything downstream of that — the OTLP
 * wire format, gRPC and HTTP transport, compression, retries — is the SDK's, and deliberately not
 * ours to maintain.
 *
 * Only the nesting a producer *declared* (`psid`) survives the conversion. The timestamp-and-thread
 * inference that `trailblaze profile` falls back to stays in the profiler: a guessed parent written
 * into `parentSpanContext` is indistinguishable from a recorded one downstream, and a viewer would
 * present it as fact.
 */
object TraceEventSpans {

  /** Names the producing service in the emitted resource — what a viewer groups and colors by. */
  const val DEFAULT_SERVICE_NAME: String = "trailblaze"

  private val SERVICE_NAME: AttributeKey<String> = AttributeKey.stringKey("service.name")
  private val PROCESS_PID: AttributeKey<Long> = AttributeKey.longKey("process.pid")
  private val THREAD_ID: AttributeKey<Long> = AttributeKey.longKey("thread.id")
  private val CATEGORY: AttributeKey<String> = AttributeKey.stringKey("trailblaze.category")
  private val SYNTHETIC_SPAN_ID: AttributeKey<Boolean> = AttributeKey.booleanKey("trailblaze.synthetic_span_id")
  private val SYNTHETIC_TRACE_ID: AttributeKey<Boolean> = AttributeKey.booleanKey("trailblaze.synthetic_trace_id")

  private val scope: InstrumentationScopeInfo = InstrumentationScopeInfo.create("xyz.block.trailblaze.tracing")

  private val ids: IdGenerator = IdGenerator.random()

  /**
   * Converts a `trace.json` document. Returns an empty list when the text is not a JSON array, which
   * is how an absent or half-written trace file reads — a session with no spans is not an error.
   */
  fun fromTraceJson(traceJson: String, serviceName: String = DEFAULT_SERVICE_NAME): List<SpanData> {
    val parsed = runCatching { Json.parseToJsonElement(traceJson) }.getOrNull() as? JsonArray ?: return emptyList()
    return fromEvents(parsed.mapNotNull { it as? JsonObject }, serviceName)
  }

  fun fromEvents(events: List<JsonObject>, serviceName: String = DEFAULT_SERVICE_NAME): List<SpanData> {
    // Metadata ("M") and instant events are not spans: they have no duration, so a trace viewer has
    // nothing to draw. The Chrome Trace file keeps them because Perfetto labels its lanes with them.
    val spanEvents = events.filter { it.str("ph") == "X" }
    // One id for every event recorded before trace identity existed, so an older file converts into
    // one connected trace rather than one trace per span.
    val fallbackTraceId = ids.generateTraceId()
    // Resources are cached per pid, because the exporter groups spans into `resourceSpans` by
    // resource IDENTITY. A fresh equal-but-distinct Resource per span still groups correctly (it
    // compares by value), but it means one allocation per span for no gain.
    val resources = mutableMapOf<Long?, Resource>()
    return spanEvents.map { event ->
      val pid = event.num("pid")
      toSpanData(
        event = event,
        fallbackTraceId = fallbackTraceId,
        resource = resources.getOrPut(pid) { resourceFor(serviceName, pid) },
      )
    }
  }

  private fun resourceFor(serviceName: String, pid: Long?): Resource {
    // Split by pid: a resource is the entity that produced the telemetry, and a merged trace
    // spanning a host run, its daemon and a device is several of them. Together they would read as
    // one process with impossible thread interleaving.
    val attributes = Attributes.builder().put(SERVICE_NAME, serviceName)
    if (pid != null) attributes.put(PROCESS_PID, pid)
    return Resource.getDefault().merge(Resource.create(attributes.build()))
  }

  private fun toSpanData(event: JsonObject, fallbackTraceId: String, resource: Resource): SpanData {
    val args = event["args"] as? JsonObject ?: JsonObject(emptyMap())
    val startMicros = event.num("ts") ?: 0L
    val durationMicros = event.num("dur") ?: 0L
    val declaredTraceId = event.str("trid")
    val declaredSpanId = event.str("sid")
    val traceId = declaredTraceId ?: fallbackTraceId
    // A minted id is referenced by nothing, and cannot be: a producer that did not record its own
    // id was never named as anyone's parent either.
    val spanId = declaredSpanId ?: ids.generateSpanId()

    val attributes = Attributes.builder()
    // Category stays an attribute rather than being folded into the span name. The profiler's
    // display name does prefix it, but viewers group and filter on attributes, and a name that is
    // qualified for some categories and bare for others cannot be grouped at all.
    event.str("cat")?.let { attributes.put(CATEGORY, it) }
    event.num("tid")?.let { attributes.put(THREAD_ID, it) }
    // Says when the id came from this conversion rather than the producer. Without it, "has an id"
    // reads as "declared one" — which is what decides whether a missing parent means "root" or
    // "unknown".
    if (declaredSpanId == null) attributes.put(SYNTHETIC_SPAN_ID, true)
    if (declaredTraceId == null) attributes.put(SYNTHETIC_TRACE_ID, true)
    args.forEach { (key, value) ->
      value.jsonPrimitive.contentOrNull?.let { attributes.put(AttributeKey.stringKey("trailblaze.$key"), it) }
    }

    return TraceEventSpanData(
      name = event.str("name") ?: "unnamed",
      // Mapped by branch, not by ordinal or name: OpenTelemetry reserves 0 for unspecified and
      // orders SERVER before CLIENT, while our enum declares CLIENT first. Ordinals would silently
      // relabel every remote span as its counterpart.
      kind = when (event.str("kind")) {
        TrailblazeSpanKind.CLIENT.name -> OtelSpanKind.CLIENT
        TrailblazeSpanKind.SERVER.name -> OtelSpanKind.SERVER
        TrailblazeSpanKind.PRODUCER.name -> OtelSpanKind.PRODUCER
        TrailblazeSpanKind.CONSUMER.name -> OtelSpanKind.CONSUMER
        // Absent means INTERNAL — which is why the recorder leaves it out of the JSON entirely.
        else -> OtelSpanKind.INTERNAL
      },
      spanContext = spanContext(traceId, spanId),
      parentSpanContext = event.str("psid")?.let { spanContext(traceId, it) } ?: SpanContext.getInvalid(),
      // `args.error` is where the recorder puts a thrown message, so a failed span arrives in a
      // viewer as an error rather than as an ordinary span with an easily missed attribute.
      status = args.str("error")?.let { StatusData.create(io.opentelemetry.api.trace.StatusCode.ERROR, it) }
        ?: StatusData.unset(),
      startEpochNanos = startMicros * 1_000L,
      endEpochNanos = (startMicros + durationMicros) * 1_000L,
      attributes = attributes.build(),
      resource = resource,
    )
  }

  private fun spanContext(traceId: String, spanId: String): SpanContext = SpanContext.create(
    traceId,
    spanId,
    // Recorded means kept: the recorder has no sampler, so an unsampled flag would tell a collector
    // to drop the only copy of this span.
    TraceFlags.getSampled(),
    TraceState.getDefault(),
  )

  private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

  private fun JsonObject.num(key: String): Long? = this[key]?.jsonPrimitive?.longOrNull

  /**
   * A finished span, straight from the recorded event.
   *
   * `events` and `links` are empty and stay that way: the recorder has no equivalent of either, and
   * the totals report the same zero rather than a count the collection does not back up.
   */
  private class TraceEventSpanData(
    private val name: String,
    private val kind: OtelSpanKind,
    private val spanContext: SpanContext,
    private val parentSpanContext: SpanContext,
    private val status: StatusData,
    private val startEpochNanos: Long,
    private val endEpochNanos: Long,
    private val attributes: Attributes,
    private val resource: Resource,
  ) : SpanData {
    override fun getName(): String = name
    override fun getKind(): OtelSpanKind = kind
    override fun getSpanContext(): SpanContext = spanContext
    override fun getParentSpanContext(): SpanContext = parentSpanContext
    override fun getStatus(): StatusData = status
    override fun getStartEpochNanos(): Long = startEpochNanos
    override fun getEndEpochNanos(): Long = endEpochNanos
    override fun getAttributes(): Attributes = attributes
    override fun getEvents(): List<EventData> = emptyList()
    override fun getLinks(): List<LinkData> = emptyList()
    override fun hasEnded(): Boolean = true
    override fun getTotalRecordedEvents(): Int = 0
    override fun getTotalRecordedLinks(): Int = 0
    override fun getTotalAttributeCount(): Int = attributes.size()
    override fun getInstrumentationScopeInfo(): InstrumentationScopeInfo = scope

    @Deprecated("Superseded by getInstrumentationScopeInfo, but still abstract on SpanData.")
    @Suppress("DEPRECATION")
    override fun getInstrumentationLibraryInfo(): InstrumentationLibraryInfo =
      InstrumentationLibraryInfo.create(scope.name, scope.version)

    override fun getResource(): Resource = resource
  }
}
