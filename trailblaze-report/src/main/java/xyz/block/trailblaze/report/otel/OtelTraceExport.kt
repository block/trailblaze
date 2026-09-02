package xyz.block.trailblaze.report.otel

import io.opentelemetry.exporter.logging.otlp.internal.traces.OtlpStdoutSpanExporter
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SpanExporter
import java.io.Closeable
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Ships recorded spans to an OpenTelemetry endpoint, or writes them as OTLP/JSON.
 *
 * Both come out of the SDK's own exporters, so the file and the wire carry the same bytes. Nothing
 * here is instrumentation: a run's spans are already finished by the time they get this far.
 *
 * Opt-in, and off unless an endpoint is configured. A run that exports nowhere still writes
 * `trace.json`, and `trailblaze otel` converts it on demand — pushing to an endpoint only removes
 * the manual step.
 */
object OtelTraceExport {

  /**
   * The endpoint every signal shares, with the signal's path appended — so a value here needs
   * `/v1/traces` added, and [TRACES_ENDPOINT_ENV] does not. OpenTelemetry's own variable names, so a
   * collector or viewer already running locally needs no Trailblaze-specific configuration.
   */
  const val ENDPOINT_ENV: String = "OTEL_EXPORTER_OTLP_ENDPOINT"
  const val TRACES_ENDPOINT_ENV: String = "OTEL_EXPORTER_OTLP_TRACES_ENDPOINT"
  const val PROTOCOL_ENV: String = "OTEL_EXPORTER_OTLP_PROTOCOL"
  const val TRACES_PROTOCOL_ENV: String = "OTEL_EXPORTER_OTLP_TRACES_PROTOCOL"

  /**
   * Headers every request carries — an authenticating collector accepts nothing without them. The
   * builders used here do not read the environment themselves, so these are parsed and applied.
   */
  const val HEADERS_ENV: String = "OTEL_EXPORTER_OTLP_HEADERS"
  const val TRACES_HEADERS_ENV: String = "OTEL_EXPORTER_OTLP_TRACES_HEADERS"

  /** OTLP/HTTP. Note the port: 4317 is the gRPC one. */
  const val DEFAULT_HTTP_ENDPOINT: String = "http://localhost:4318"

  const val TRACES_PATH: String = "/v1/traces"

  private const val GRPC_PORT = 4317

  /** Enough for a local viewer or a collector to close the connection; not enough to hold a run. */
  private const val SHUTDOWN_SECONDS = 2L

  enum class Protocol { GRPC, HTTP }

  /** Where a run should export itself, or null when nothing is configured and it should not. */
  fun configuredTarget(env: (String) -> String? = System::getenv): Target? {
    val signalSpecific = env(TRACES_ENDPOINT_ENV)?.takeIf { it.isNotBlank() }
    val shared = env(ENDPOINT_ENV)?.takeIf { it.isNotBlank() }
    val protocol = (env(TRACES_PROTOCOL_ENV) ?: env(PROTOCOL_ENV))?.takeIf { it.isNotBlank() }
    val headers = configuredHeaders(env)
    return when {
      // The signal-specific variable is a full URL including the path, per the OpenTelemetry
      // specification; the shared one is a base that each signal appends its own path to. Getting
      // this backwards produces `/v1/traces/v1/traces`, which a collector answers with a 404 that
      // reads like the endpoint is wrong.
      signalSpecific != null -> Target(
        endpoint = signalSpecific,
        protocol = protocolFor(protocol, signalSpecific),
        appendTracesPath = false,
        headers = headers,
      )
      shared != null -> Target(
        endpoint = shared,
        protocol = protocolFor(protocol, shared),
        appendTracesPath = true,
        headers = headers,
      )
      else -> null
    }
  }

  /**
   * The configured headers, signal-specific merged over shared.
   *
   * Merged rather than either-or: the specification treats the signal-specific variable as taking
   * precedence per key, so a collector configured with a shared `api-key` and a traces-specific
   * override gets both rather than losing one.
   */
  fun configuredHeaders(env: (String) -> String? = System::getenv): Map<String, String> =
    parseHeaders(env(HEADERS_ENV)) + parseHeaders(env(TRACES_HEADERS_ENV))

  /**
   * Parses the specification's `key1=value1,key2=value2` form.
   *
   * A value may itself contain `=` (a base64 padding, for instance), so only the FIRST separator
   * splits. Entries with no `=` or an empty key are dropped rather than sent as a malformed header,
   * which a collector answers with a 400 that says nothing about which header was wrong.
   */
  internal fun parseHeaders(raw: String?): Map<String, String> {
    if (raw.isNullOrBlank()) return emptyMap()
    return raw.split(',').mapNotNull { entry ->
      val separator = entry.indexOf('=')
      if (separator < 0) return@mapNotNull null
      val key = entry.substring(0, separator).trim()
      if (key.isEmpty()) return@mapNotNull null
      key to entry.substring(separator + 1).trim()
    }.toMap()
  }

  /**
   * A target from an endpoint given directly rather than through the environment.
   *
   * The traces path is appended only when the value does not already end with it, so both
   * `http://host:4318` and `http://host:4318/v1/traces` work — someone typing an endpoint on a
   * command line has no reason to know which of the two forms the environment variables distinguish.
   *
   * Headers still come from the environment: an endpoint given on the command line does not stop the
   * collector at the other end requiring authentication.
   */
  fun targetFor(endpoint: String, protocol: String? = null): Target = Target(
    endpoint = endpoint,
    protocol = protocolFor(protocol, endpoint),
    appendTracesPath = !endpoint.trimEnd('/').endsWith(TRACES_PATH),
    headers = configuredHeaders(),
  )

  /**
   * The configured protocol, or one inferred from the port.
   *
   * The specification's default is `http/protobuf`, but a bare `:4317` is the gRPC port and nothing
   * else, so honoring the default there would fail every request against a correctly configured
   * endpoint. An explicit protocol variable always wins.
   */
  private fun protocolFor(declared: String?, endpoint: String): Protocol = when {
    declared == null -> if (portOf(endpoint) == GRPC_PORT) Protocol.GRPC else Protocol.HTTP
    declared.equals("grpc", ignoreCase = true) -> Protocol.GRPC
    else -> Protocol.HTTP
  }

  private fun portOf(endpoint: String): Int? =
    runCatching { java.net.URI(endpoint).port.takeIf { it != -1 } }.getOrNull()

  data class Target(
    val endpoint: String,
    val protocol: Protocol,
    val appendTracesPath: Boolean,
    val headers: Map<String, String> = emptyMap(),
  ) {
    /** The URL requests actually go to. gRPC addresses the host, not a path, so it is never appended. */
    val resolvedEndpoint: String = when {
      protocol == Protocol.GRPC || !appendTracesPath -> endpoint
      else -> endpoint.trimEnd('/') + TRACES_PATH
    }
  }

  /**
   * One connection, reused across sends.
   *
   * Exists because building an exporter builds a connection pool. Converting a whole logs directory
   * one exporter at a time pays that per session, and against an endpoint that accepts the
   * connection and then stalls it pays the timeout per session too.
   */
  class Shipper internal constructor(
    private val exporter: SpanExporter,
    private val timeoutSeconds: Long,
  ) : Closeable {
    /** Null on success, or a message describing the failure. */
    fun send(spans: List<SpanData>): String? {
      if (spans.isEmpty()) return null
      return try {
        val result = exporter.export(spans)
        result.join(timeoutSeconds, TimeUnit.SECONDS)
        if (result.isSuccess) null else "the endpoint rejected the export or did not answer"
      } catch (e: Exception) {
        e.message ?: e::class.simpleName
      }
    }

    override fun close() {
      // A short, FIXED bound rather than the send budget. Joining shutdown for the full timeout too
      // would make the worst case twice what the caller asked for, and a caller that allowed ten
      // seconds would wait twenty.
      runCatching { exporter.shutdown().join(SHUTDOWN_SECONDS, TimeUnit.SECONDS) }
    }
  }

  /** A [Shipper] for [target]. The caller closes it. */
  fun openShipper(target: Target, timeoutSeconds: Long = 30): Shipper {
    val exporter = when (target.protocol) {
      Protocol.GRPC -> OtlpGrpcSpanExporter.builder()
        .setEndpoint(target.resolvedEndpoint)
        .setTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .apply { target.headers.forEach { (name, value) -> addHeader(name, value) } }
        .build()
      Protocol.HTTP -> OtlpHttpSpanExporter.builder()
        .setEndpoint(target.resolvedEndpoint)
        .setTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .apply { target.headers.forEach { (name, value) -> addHeader(name, value) } }
        .build()
    }
    return Shipper(exporter, timeoutSeconds)
  }

  /**
   * Sends [spans] to [target] and waits for the result. Returns null on success, or a message
   * describing the failure.
   *
   * For a single export. Sending more than once wants [openShipper], which does not rebuild the
   * connection each time.
   *
   * Exporting is best-effort at every call site: a viewer that is not running must not fail the run
   * that was trying to tell it something. Callers report the message and carry on.
   */
  fun export(spans: List<SpanData>, target: Target, timeoutSeconds: Long = 30): String? {
    if (spans.isEmpty()) return null
    return openShipper(target, timeoutSeconds).use { it.send(spans) }
  }

  /**
   * Writes [spans] to [file] as OTLP/JSON — one `ExportTraceServiceRequest`, the same body the
   * endpoint receives, so a viewer that ingests a file and a collector see identical data.
   *
   * Uses the SDK's stdout exporter pointed at the file. That class lives in an `internal` package,
   * which is the one compatibility risk taken here, and it is confined to this function: the
   * alternative is hand-writing the OTLP encoding, and a silently wrong encoding costs more than a
   * compile error on a version bump.
   */
  fun writeOtlpJson(spans: List<SpanData>, file: File, timeoutSeconds: Long = 30) {
    file.parentFile?.mkdirs()
    file.outputStream().use { out ->
      val exporter = OtlpStdoutSpanExporter.builder()
        .setOutput(out)
        // The wrapper object is what makes the file a request body rather than a bare span list.
        .setWrapperJsonObject(true)
        .build()
      try {
        exporter.export(spans).join(timeoutSeconds, TimeUnit.SECONDS)
      } finally {
        runCatching { exporter.shutdown().join(SHUTDOWN_SECONDS, TimeUnit.SECONDS) }
      }
    }
  }
}
