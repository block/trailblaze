package xyz.block.trailblaze.report.otel

import xyz.block.trailblaze.util.Console
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * The automatic half of OTel export: when an endpoint is configured, a run sends its own spans there
 * as soon as its trace is written, so they appear in a local viewer with no separate step.
 *
 * Called from wherever a `trace.json` is written — a host run, and a device run's spans arriving at
 * the server. Does nothing at all unless `OTEL_EXPORTER_OTLP_ENDPOINT` (or the traces-specific
 * variant) is set. The file is still written either way, and `trailblaze otel` converts it on
 * demand, so nothing here is the only path to the data.
 */
object SessionOtelExport {

  /**
   * A run cannot afford the exporter's default patience: this is on the path that finishes a
   * session, so an endpoint that accepts connections and then stalls would hold the run open. Local
   * viewers answer in milliseconds and a closed port fails immediately, so the only case this cuts
   * short is one that was going to be reported as a failure anyway.
   */
  private const val TIMEOUT_SECONDS = 10L

  /**
   * One thread, unbounded queue, daemon.
   *
   * Single-threaded so a burst of sessions cannot open a connection pool each, and daemon so a
   * pending export can never keep a CLI process alive after its run finished.
   *
   * Built directly rather than through `Executors.newSingleThreadExecutor`, which returns a wrapper
   * around the pool rather than the pool itself: casting that to [ThreadPoolExecutor] throws, and
   * the throw lands where every queue attempt is already best-effort, so the symptom would be
   * uploaded traces that are simply never exported. [awaitQuiet] needs the real type.
   */
  private val background: ThreadPoolExecutor by lazy {
    ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue()) { runnable ->
      Thread(runnable, "trailblaze-otel-export").apply { isDaemon = true }
    }
  }

  /**
   * Exports now, on the calling thread.
   *
   * For a host run, whose next act is to finish: waiting here is what makes the spans arrive at all,
   * since the process may exit immediately afterwards.
   */
  fun pushIfConfigured(sessionId: String, traceJson: String, env: (String) -> String? = System::getenv) {
    val target = OtelTraceExport.configuredTarget(env) ?: return
    val spans = TraceEventSpans.fromTraceJson(traceJson)
    if (spans.isEmpty()) return
    // Best effort, always: a viewer that is not listening must not fail the run that was trying to
    // tell it something.
    val failure = runCatching { OtelTraceExport.export(spans, target, TIMEOUT_SECONDS) }
      .getOrElse { it.message ?: it::class.simpleName }
    if (failure == null) {
      Console.info("OpenTelemetry: sent ${spans.size} spans for $sessionId to ${target.resolvedEndpoint}")
    } else {
      Console.error("OpenTelemetry: could not send spans for $sessionId to ${target.resolvedEndpoint} — $failure")
    }
  }

  /**
   * Hands the export to a background thread and returns immediately.
   *
   * For a request handler. A device's trace upload has its own two-second timeout, so exporting
   * inline would let a stalled collector make the upload look failed — and the sender then retries
   * an upload that was in fact persisted, which is how the same spans get exported twice. The upload
   * is written and acknowledged on its own terms; this happens afterwards or not at all.
   *
   * The queue is deliberately unbounded: the work is one export per finished session, and dropping
   * the export of a session that just completed to protect a queue that is never deep is the wrong
   * trade.
   */
  fun pushInBackgroundIfConfigured(sessionId: String, traceJson: String, env: (String) -> String? = System::getenv) {
    // Checked here rather than on the worker so the common case — no endpoint configured — costs
    // nothing and starts no thread.
    if (OtelTraceExport.configuredTarget(env) == null) return
    runCatching { background.execute { pushIfConfigured(sessionId, traceJson, env) } }
      .onFailure { Console.error("OpenTelemetry: could not queue the export for $sessionId — ${it.message}") }
  }

  /** Waits for queued exports to finish. For tests, and for a shutdown that wants them flushed. */
  fun awaitQuiet(timeoutSeconds: Long = TIMEOUT_SECONDS): Boolean {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
    while (System.nanoTime() < deadline) {
      if (background.activeCount == 0 && background.queue.isEmpty()) return true
      Thread.sleep(10)
    }
    return false
  }
}
