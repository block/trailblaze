package xyz.block.trailblaze.cli

import xyz.block.trailblaze.host.HostRunTraceRecording
import xyz.block.trailblaze.report.trace.SessionTraceFile
import xyz.block.trailblaze.tracing.TraceLevel
import xyz.block.trailblaze.tracing.TrailblazeTracer
import xyz.block.trailblaze.util.Console
import java.io.File
import java.util.concurrent.Executors

/**
 * Files the spans a daemon-forwarded CLI command recorded into its session's `trace.json`, so a
 * `trailblaze tool` call opens in Perfetto the way a trail run does.
 *
 * A CLI session never ends on its own, so nothing else would ever export it: each forwarded command
 * is recorded as its own run and merged into the file when it returns. The merge is written off the
 * response path; only the drain, which is a copy of the buffered events, happens before the reply.
 *
 * The recorder is process-wide. A command that finds a trail run already recording joins that
 * recording instead of taking it: what it records before the run ends is exported with the run, and
 * what it records after goes to its own session.
 */
internal object CliCommandTrace {

  const val CATEGORY = "cli"

  /**
   * The caller's trace level, forwarded by the launcher. A daemon's own level is whatever it started
   * with, so without this `TRAILBLAZE_TRACE_LEVEL=verbose trailblaze tool ...` would change nothing.
   */
  const val TRACE_LEVEL_ENV = "TRAILBLAZE_TRACE_LEVEL"

  /** Set by the CLI when it learns which session the command is driving. */
  @Volatile private var sessionId: String? = null

  private val writer = Executors.newSingleThreadExecutor { runnable ->
    Thread(runnable, "cli-trace-writer").apply { isDaemon = true }
  }

  /** Waits for every merge already handed to the writer. */
  internal fun awaitWritesForTest() {
    writer.submit {}.get()
  }

  /**
   * The level a forwarded command asked for, or null to keep the daemon's. An unrecognized value
   * goes to [warn] and records at [TraceLevel.NORMAL], as it would for a process started with it.
   */
  fun levelFor(raw: String?, warn: (String) -> Unit): TraceLevel? {
    val value = raw?.takeIf { it.isNotBlank() } ?: return null
    return TraceLevel.parse(value) ?: TraceLevel.NORMAL.also {
      warn("Ignoring unrecognized $TRACE_LEVEL_ENV \"$value\" — expected off, normal or verbose")
    }
  }

  fun noteSession(id: String) {
    sessionId = id
  }

  /**
   * Runs [block] as one recorded command named [name], at [level] when the caller asked for one.
   * Records nothing when [traceFileFor] is null: only the daemon knows where session logs live.
   */
  fun <T> record(
    name: String,
    args: Map<String, String>,
    level: TraceLevel?,
    traceFileFor: ((sessionId: String) -> File)?,
    block: () -> T,
  ): T {
    if (traceFileFor == null) return block()
    return TrailblazeTracer.withLevel(level) {
      if (!TrailblazeTracer.isEnabled) return@withLevel block()
      sessionId = null
      HostRunTraceRecording.begin()
      try {
        TrailblazeTracer.trace(name, CATEGORY, args) { block() }
      } finally {
        // Whoever finishes last drains, even a command that joined a trail run: the run drains when
        // it ends, so a command that outlives it would otherwise leave its tail in the recorder for
        // the next run to clear.
        val json = HostRunTraceRecording.endAndDrainIfLast()
        val session = sessionId
        sessionId = null
        if (json != null && session != null) {
          writer.execute {
            try {
              SessionTraceFile.merge(traceFileFor(session), json)
            } catch (e: Exception) {
              Console.log("Could not write the CLI trace for session $session: ${e.message}")
            }
          }
        }
      }
    }
  }
}
