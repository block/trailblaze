package xyz.block.trailblaze.report.otel

import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The conversion from a recorded `trace.json` to OpenTelemetry spans, and the OTLP/JSON that comes
 * out the other side.
 *
 * Assertions are on what a collector or viewer sees: the parentage, the kinds, the timestamps, and
 * the two wire-format details (hex-string ids, string timestamps) that a consumer rejects or
 * silently rounds when they are wrong.
 */
class TraceEventSpansTest {

  private val trace1 = "1".repeat(32)
  private val trace2 = "2".repeat(32)
  private val toolSpan = "a".repeat(16)
  private val httpSpan = "b".repeat(16)

  private fun event(
    name: String,
    tsMicros: Long,
    durMicros: Long,
    sid: String? = null,
    psid: String? = null,
    trid: String? = trace1,
    kind: String? = null,
    cat: String = "app",
    ph: String = "X",
    pid: Long = 7,
    tid: Long = 1,
    args: Map<String, String> = emptyMap(),
  ): JsonObject = buildJsonObject {
    put("name", name)
    put("cat", cat)
    put("ph", ph)
    put("ts", tsMicros)
    put("dur", durMicros)
    put("pid", pid)
    put("tid", tid)
    putJsonObject("args") { args.forEach { (key, value) -> put(key, value) } }
    sid?.let { put("sid", it) }
    psid?.let { put("psid", it) }
    trid?.let { put("trid", it) }
    kind?.let { put("kind", it) }
  }

  @Test
  fun `a declared parent becomes the span's parent, with both ids intact`() {
    val spans = TraceEventSpans.fromEvents(
      listOf(
        event("LlmClient.execute", tsMicros = 1_000, durMicros = 500, sid = toolSpan),
        event("POST /v1/messages", tsMicros = 1_100, durMicros = 300, sid = httpSpan, psid = toolSpan, cat = "http"),
      ),
    )

    val http = spans.single { it.name == "POST /v1/messages" }
    assertEquals(httpSpan, http.spanId)
    assertEquals(toolSpan, http.parentSpanId)
    assertEquals(trace1, http.traceId)
    // A root's parent context is invalid rather than absent — that is how OTLP says "no parent".
    val tool = spans.single { it.name == "LlmClient.execute" }
    assertFalse(tool.parentSpanContext.isValid)
  }

  @Test
  fun `an outgoing HTTP span exports as CLIENT, not as its SERVER counterpart`() {
    // OpenTelemetry orders SERVER before CLIENT and reserves 0 for unspecified, while the recorded
    // enum declares CLIENT first. Mapping by ordinal relabels every remote span as the other side
    // of the call it describes.
    val spans = TraceEventSpans.fromEvents(
      listOf(event("POST /v1/messages", 1_000, 100, sid = httpSpan, kind = "CLIENT", cat = "http")),
    )
    assertEquals(SpanKind.CLIENT, spans.single().kind)
  }

  @Test
  fun `an event with no recorded kind exports as INTERNAL`() {
    // The recorder leaves INTERNAL out of the JSON, so absent has to mean INTERNAL and not
    // UNSPECIFIED — a viewer renders unspecified spans as untyped.
    val spans = TraceEventSpans.fromEvents(listOf(event("tool", 1_000, 100, sid = toolSpan)))
    assertEquals(SpanKind.INTERNAL, spans.single().kind)
  }

  @Test
  fun `timestamps convert from microseconds to nanoseconds, and the end is the start plus duration`() {
    val spans = TraceEventSpans.fromEvents(listOf(event("tool", tsMicros = 1_700_000_000_000_000, durMicros = 2_500, sid = toolSpan)))

    val span = spans.single()
    assertEquals(1_700_000_000_000_000_000L, span.startEpochNanos)
    assertEquals(1_700_000_000_002_500_000L, span.endEpochNanos)
  }

  @Test
  fun `a recorded error becomes an error status, so a viewer marks the span failed`() {
    val spans = TraceEventSpans.fromEvents(
      listOf(event("tool", 1_000, 100, sid = toolSpan, args = mapOf("error" to "boom"))),
    )

    val status = spans.single().status
    assertEquals(StatusCode.ERROR, status.statusCode)
    assertEquals("boom", status.description)
  }

  @Test
  fun `a span with no error is left unset rather than marked OK`() {
    // OK means an application explicitly declared success. Claiming it for every span that merely
    // did not throw destroys the distinction a viewer's error filter relies on.
    val spans = TraceEventSpans.fromEvents(listOf(event("tool", 1_000, 100, sid = toolSpan)))
    assertEquals(StatusCode.UNSET, spans.single().status.statusCode)
  }

  @Test
  fun `spans recorded before trace identity existed all join one trace`() {
    // A file written by an older build has no trid. One trace id per span would present a single
    // run as dozens of unrelated one-span traces, which is worse than an approximate grouping.
    val spans = TraceEventSpans.fromEvents(
      listOf(
        event("first", 1_000, 100, sid = toolSpan, trid = null),
        event("second", 1_200, 100, sid = httpSpan, trid = null),
      ),
    )

    assertEquals(1, spans.map { it.traceId }.distinct().size)
    assertTrue(spans.all { it.spanContext.isValid })
    assertTrue(spans.all { it.attributes.asMap().any { (key, value) -> key.key == "trailblaze.synthetic_trace_id" && value == true } })
  }

  @Test
  fun `an id-less event still exports, and says its id was minted`() {
    // Producers that build their own event may carry no sid. OTLP requires an id per span, so one
    // is minted — and flagged, because "has an id" otherwise reads as "the producer declared one",
    // which is what decides whether a missing parent means root or unknown.
    val spans = TraceEventSpans.fromEvents(listOf(event("legacy-http", 1_000, 100, sid = null)))

    val span = spans.single()
    assertTrue(span.spanContext.isValid)
    assertEquals(true, span.attributes.asMap().entries.single { it.key.key == "trailblaze.synthetic_span_id" }.value)
  }

  @Test
  fun `two id-less events do not collide`() {
    val spans = TraceEventSpans.fromEvents(
      listOf(event("one", 1_000, 100, sid = null), event("two", 1_200, 100, sid = null)),
    )
    assertNotEquals(spans[0].spanId, spans[1].spanId)
  }

  @Test
  fun `metadata records are not exported as spans`() {
    val spans = TraceEventSpans.fromEvents(
      listOf(
        event("process_name", 0, 0, ph = "M"),
        event("tool", 1_000, 100, sid = toolSpan),
      ),
    )
    assertEquals(listOf("tool"), spans.map { it.name })
  }

  @Test
  fun `separate processes export as separate resources, so a merged trace is not one impossible process`() {
    val spans = TraceEventSpans.fromEvents(
      listOf(
        event("host-work", 1_000, 100, sid = toolSpan, pid = 7),
        event("device-work", 1_050, 50, sid = httpSpan, psid = toolSpan, pid = 99),
      ),
    )

    val pids = spans.map { span -> span.resource.attributes.asMap().entries.single { it.key.key == "process.pid" }.value }
    assertEquals(listOf(7L, 99L), pids)
    // Same trace across both, or the merge this exists for is undone.
    assertEquals(1, spans.map { it.traceId }.distinct().size)
  }

  @Test
  fun `spans from different traces keep their own trace ids`() {
    val spans = TraceEventSpans.fromEvents(
      listOf(
        event("a", 1_000, 100, sid = toolSpan, trid = trace1),
        event("b", 1_000, 100, sid = httpSpan, trid = trace2),
      ),
    )
    assertEquals(listOf(trace1, trace2), spans.map { it.traceId })
  }

  @Test
  fun `a trace_json that is not an array converts to nothing instead of failing`() {
    assertEquals(emptyList(), TraceEventSpans.fromTraceJson("not json"))
    assertEquals(emptyList(), TraceEventSpans.fromTraceJson("""{"resourceSpans":[]}"""))
  }

  @Test
  fun `the written file is an OTLP request whose ids are hex and whose timestamps are strings`() {
    // The two details a consumer gets wrong silently: protobuf-JSON would encode `bytes` ids as
    // base64 (collectors reject it), and nanosecond timestamps as JSON numbers exceed 2^53, so
    // every JavaScript viewer rounds them.
    val spans = TraceEventSpans.fromEvents(
      listOf(
        event("LlmClient.execute", 1_700_000_000_000_000, 500, sid = toolSpan),
        event("POST /v1/messages", 1_700_000_000_000_100, 300, sid = httpSpan, psid = toolSpan, cat = "http", kind = "CLIENT"),
      ),
    )
    val file = File.createTempFile("otel", ".json").also { it.deleteOnExit() }

    OtelTraceExport.writeOtlpJson(spans, file)

    val root = Json.parseToJsonElement(file.readText()).jsonObject
    val exported = root.getValue("resourceSpans").jsonArray
      .flatMap { it.jsonObject.getValue("scopeSpans").jsonArray }
      .flatMap { it.jsonObject.getValue("spans").jsonArray }
      .map { it.jsonObject }
    assertEquals(2, exported.size)

    val http = exported.single { it.getValue("name").jsonPrimitive.content == "POST /v1/messages" }
    assertEquals(httpSpan, http.getValue("spanId").jsonPrimitive.content)
    assertEquals(toolSpan, http.getValue("parentSpanId").jsonPrimitive.content)
    assertEquals(trace1, http.getValue("traceId").jsonPrimitive.content)
    // 3 is CLIENT in OTLP's numbering, which is not this enum's ordinal.
    assertEquals(3, http.getValue("kind").jsonPrimitive.content.toInt())
    val start = http.getValue("startTimeUnixNano").jsonPrimitive
    assertTrue(start.isString, "a uint64 nanosecond timestamp must be a JSON string, was ${start.content}")
    assertEquals("1700000000000100000", start.content)
  }

  @Test
  fun `an empty span list is not sent anywhere`() {
    // Nothing recorded is not a failure, and an empty request would make a viewer show an empty
    // trace that looks like a run that did nothing.
    val unreachable = OtelTraceExport.Target("http://localhost:1", OtelTraceExport.Protocol.HTTP, appendTracesPath = true)
    assertNull(OtelTraceExport.export(emptyList(), unreachable))
  }

  @Test
  fun `the shared endpoint variable gets the traces path appended, and the signal-specific one does not`() {
    // Appending to a signal-specific endpoint produces `/v1/traces/v1/traces`, which answers 404 —
    // a failure that reads like the endpoint itself is wrong.
    val shared = TraceEventSpansTestEnv(mapOf(OtelTraceExport.ENDPOINT_ENV to "http://localhost:4318"))
    assertEquals("http://localhost:4318/v1/traces", OtelTraceExport.configuredTarget(shared::get)!!.resolvedEndpoint)

    val specific = TraceEventSpansTestEnv(
      mapOf(OtelTraceExport.TRACES_ENDPOINT_ENV to "http://localhost:4318/v1/traces"),
    )
    assertEquals("http://localhost:4318/v1/traces", OtelTraceExport.configuredTarget(specific::get)!!.resolvedEndpoint)
  }

  @Test
  fun `the signal-specific endpoint wins over the shared one`() {
    val env = TraceEventSpansTestEnv(
      mapOf(
        OtelTraceExport.ENDPOINT_ENV to "http://shared:4318",
        OtelTraceExport.TRACES_ENDPOINT_ENV to "http://specific:4318/v1/traces",
      ),
    )
    assertEquals("http://specific:4318/v1/traces", OtelTraceExport.configuredTarget(env::get)!!.resolvedEndpoint)
  }

  @Test
  fun `port 4317 exports over gRPC, and keeps the endpoint pathless`() {
    // 4317 is the gRPC port and nothing else. Honoring the specification's http default there would
    // fail every request against a correctly configured endpoint.
    val env = TraceEventSpansTestEnv(mapOf(OtelTraceExport.ENDPOINT_ENV to "http://localhost:4317"))

    val target = OtelTraceExport.configuredTarget(env::get)!!
    assertEquals(OtelTraceExport.Protocol.GRPC, target.protocol)
    assertEquals("http://localhost:4317", target.resolvedEndpoint)
  }

  @Test
  fun `an explicit protocol overrides what the port implies`() {
    val env = TraceEventSpansTestEnv(
      mapOf(
        OtelTraceExport.ENDPOINT_ENV to "http://localhost:4317",
        OtelTraceExport.PROTOCOL_ENV to "http/protobuf",
      ),
    )
    assertEquals(OtelTraceExport.Protocol.HTTP, OtelTraceExport.configuredTarget(env::get)!!.protocol)
  }

  @Test
  fun `no endpoint configured means no export target at all`() {
    // The whole feature is opt-in: an unset environment must not start posting a run's spans
    // somewhere by default.
    assertNull(OtelTraceExport.configuredTarget(TraceEventSpansTestEnv(emptyMap())::get))
    assertNull(OtelTraceExport.configuredTarget(TraceEventSpansTestEnv(mapOf(OtelTraceExport.ENDPOINT_ENV to " "))::get))
  }

  // -- Headers --

  @Test
  fun `configured headers ride along on the target`() {
    // A collector that authenticates rejects every export without them, and the exporter builders do
    // not read the environment themselves.
    val env = TraceEventSpansTestEnv(
      mapOf(
        OtelTraceExport.ENDPOINT_ENV to "http://localhost:4318",
        OtelTraceExport.HEADERS_ENV to "api-key=secret,x-tenant=block",
      ),
    )

    assertEquals(
      mapOf("api-key" to "secret", "x-tenant" to "block"),
      OtelTraceExport.configuredTarget(env::get)!!.headers,
    )
  }

  @Test
  fun `the traces-specific header overrides the shared one of the same name`() {
    val env = TraceEventSpansTestEnv(
      mapOf(
        OtelTraceExport.ENDPOINT_ENV to "http://localhost:4318",
        OtelTraceExport.HEADERS_ENV to "api-key=shared,keep=me",
        OtelTraceExport.TRACES_HEADERS_ENV to "api-key=traces",
      ),
    )

    val headers = OtelTraceExport.configuredTarget(env::get)!!.headers
    assertEquals("traces", headers["api-key"])
    assertEquals("me", headers["keep"], "a shared header with no override must survive")
  }

  @Test
  fun `a header value may contain the separator`() {
    // Base64 padding and bearer tokens both contain `=`; splitting on every one of them truncates
    // the credential and the collector answers 401.
    assertEquals(
      mapOf("authorization" to "Bearer abc=="),
      OtelTraceExport.parseHeaders("authorization=Bearer abc=="),
    )
  }

  @Test
  fun `malformed header entries are dropped rather than sent`() {
    // A collector answers a malformed header with a 400 that names nothing.
    assertEquals(
      mapOf("good" to "1"),
      OtelTraceExport.parseHeaders("good=1,novalue,=orphan"),
    )
  }

  @Test
  fun `no header variable means no headers`() {
    assertEquals(emptyMap(), OtelTraceExport.parseHeaders(null))
    assertEquals(emptyMap(), OtelTraceExport.parseHeaders("  "))
  }

  @Test
  fun `whitespace around a header pair is trimmed`() {
    assertEquals(
      mapOf("api-key" to "secret"),
      OtelTraceExport.parseHeaders(" api-key = secret "),
    )
  }

  private class TraceEventSpansTestEnv(private val values: Map<String, String>) {
    fun get(name: String): String? = values[name]
  }
}
