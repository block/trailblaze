package xyz.block.trailblaze.cli

import java.io.File
import java.util.concurrent.Callable
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import xyz.block.trailblaze.report.otel.OtelTraceExport
import xyz.block.trailblaze.report.otel.TraceEventSpans
import xyz.block.trailblaze.ui.TrailblazeDesktopUtil
import xyz.block.trailblaze.util.Console

/**
 * Convert a session's recorded spans to OpenTelemetry, so they can be opened in an OTel trace viewer
 * or sent to a collector.
 *
 * Reads the `trace.json` a run wrote and writes `otel.json` beside it — one OTLP/JSON request, which
 * is what an OTLP-aware viewer ingests. With `--post` the spans also go to a live endpoint, which is
 * the same thing a run does for itself when `OTEL_EXPORTER_OTLP_ENDPOINT` is set; this command is
 * how you export a run that finished before the endpoint existed, or one from CI artifacts.
 *
 * Examples:
 *   trailblaze otel                                 - convert every session in the configured logs directory
 *   trailblaze otel ./logs/my-session               - convert one session
 *   trailblaze otel --post                          - ...and send it to the configured (or default) endpoint
 *   trailblaze otel --endpoint http://host:4317     - ...and send it there (4317 is gRPC, 4318 is HTTP)
 */
@Command(
  name = "otel",
  mixinStandardHelpOptions = true,
  description = [
    "Convert recorded spans to OpenTelemetry. Writes <session>/otel.json (OTLP/JSON) for every " +
      "session that recorded a trace, and with --post also sends them to an OTLP endpoint. " +
      "Defaults to the configured logs directory when <dir> is omitted.",
  ],
)
class OtelCommand : Callable<Int> {

  @Parameters(
    index = "0",
    arity = "0..1",
    paramLabel = "<dir>",
    description = [
      "A logs directory (holding per-session subdirectories) or a single session directory. " +
        "Defaults to the configured logs directory.",
    ],
  )
  var dir: File? = null

  /**
   * Boolean on purpose. As an optional-value option it swallowed the positional directory:
   * `trailblaze otel --post ./logs/my-session` bound the path to the option, left `<dir>` null, and
   * then converted the default logs directory while trying to use a directory as an OTLP endpoint.
   * The endpoint has its own option.
   */
  @Option(
    names = ["--post"],
    description = [
      "Also send the spans to an OTLP endpoint. Uses --endpoint if given, otherwise " +
        "OTEL_EXPORTER_OTLP_TRACES_ENDPOINT / OTEL_EXPORTER_OTLP_ENDPOINT, falling back to " +
        "http://localhost:4318.",
    ],
  )
  var post: Boolean = false

  @Option(
    names = ["--endpoint"],
    paramLabel = "<url>",
    description = [
      "OTLP endpoint to send to. Implies --post. Port 4317 is treated as gRPC, anything else as " +
        "OTLP/HTTP.",
    ],
  )
  var endpoint: String? = null

  @Option(
    names = ["--service-name"],
    description = ["Service name recorded in the exported resource. Defaults to `trailblaze`."],
  )
  var serviceName: String = TraceEventSpans.DEFAULT_SERVICE_NAME

  override fun call(): Int {
    val root = dir ?: File(TrailblazeDesktopUtil.getEffectiveLogsDirectory(CliConfigHelper.getOrCreateConfig()))
    if (!root.isDirectory) {
      reportCliError(
        verb = "Export",
        target = root.path,
        reason = "directory not found",
        hint = "pass a logs directory or a single session directory, e.g. `trailblaze otel ./logs`",
      )
      return TrailblazeExitCode.MISUSE.code
    }

    // A session directory holds the trace; a logs directory holds session directories. Accepting
    // both means you can point this at what you already have open rather than at its parent.
    val sessionDirs = if (File(root, TRACE_FILE).isFile) {
      listOf(root)
    } else {
      root.listFiles()?.filter { it.isDirectory && File(it, TRACE_FILE).isFile }?.sortedBy { it.name } ?: emptyList()
    }
    if (sessionDirs.isEmpty()) {
      reportCliError(
        verb = "Export",
        target = root.path,
        reason = "no session in this directory recorded a $TRACE_FILE",
        hint = "run a trail first — only runs with in-process tracing write one",
      )
      return TrailblazeExitCode.MISUSE.code
    }

    val target = when {
      endpoint != null -> OtelTraceExport.targetFor(endpoint!!)
      // `--post` alone: whatever the environment already points at, so the flag and the automatic
      // push a run does agree rather than quietly disagreeing.
      post -> OtelTraceExport.configuredTarget() ?: OtelTraceExport.targetFor(OtelTraceExport.DEFAULT_HTTP_ENDPOINT)
      else -> null
    }

    // One shipper for the whole invocation, not one per session: building an exporter builds a
    // connection pool, and against an endpoint that accepts the connection and then stalls, a
    // per-session exporter pays the timeout again for every session.
    val shipper = target?.let { OtelTraceExport.openShipper(it) }
    var failedToPost = false
    try {
      sessionDirs.forEach { sessionDir ->
        val spans = TraceEventSpans.fromTraceJson(File(sessionDir, TRACE_FILE).readText(), serviceName)
        if (spans.isEmpty()) {
          Console.info("${sessionDir.name}: no spans recorded, skipped")
          return@forEach
        }
        val otelFile = File(sessionDir, OTEL_FILE)
        OtelTraceExport.writeOtlpJson(spans, otelFile)
        Console.info("${sessionDir.name}: ${spans.size} spans -> file://${otelFile.absolutePath}")
        // Stops after the first failure. Every remaining session would fail the same way and take
        // the same timeout doing it, and the files are written regardless.
        if (shipper != null && target != null && !failedToPost) {
          val failure = shipper.send(spans)
          if (failure == null) {
            Console.info("${sessionDir.name}: sent to ${target.resolvedEndpoint} (${target.protocol})")
          } else {
            failedToPost = true
            Console.error("${sessionDir.name}: could not send to ${target.resolvedEndpoint} — $failure")
            Console.error("Skipping the remaining sends; the otel.json files were still written.")
          }
        }
      }
    } finally {
      shipper?.close()
    }

    // The files were written either way, so this is a partial success: report it as a failure so a
    // script that asked for a push learns the push did not happen.
    return if (failedToPost) TrailblazeExitCode.INFRA_FAILED.code else TrailblazeExitCode.SUCCESS.code
  }

  companion object {
    private const val TRACE_FILE = "trace.json"
    private const val OTEL_FILE = "otel.json"
  }
}
