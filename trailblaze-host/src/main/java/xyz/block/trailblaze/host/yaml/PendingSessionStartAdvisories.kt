package xyz.block.trailblaze.host.yaml

import java.util.concurrent.atomic.AtomicReference
import kotlinx.datetime.Clock
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.SessionId

/**
 * Pre-run advisories waiting for a session to attach to.
 *
 * Messages like the daemon's unresolved-`config.target` retargeting warning are raised while a
 * run is still being assembled — before any session id exists — so they reach the console and the
 * CLI progress stream but not the session log, leaving a CI run whose only surviving artifact is
 * the session JSON with no record of them. [logTo] lands each advisory in the session log as a
 * [TrailblazeLog.TrailblazeProgressLog] with eventType [EVENT_TYPE].
 *
 * Draining is once-only across invocations: the runner's session-started callback legitimately
 * fires more than once per run (its capture wiring is idempotent), and the runner's finally-block
 * backstop calls [logTo] again for branches that only learn the session id after the trail
 * completes (web/Electron/Compose/Revyl) — including when the trail throws mid-run, where the
 * advisory matters most. An empty advisory list writes nothing, so it never creates a session
 * directory.
 */
internal class PendingSessionStartAdvisories(
  advisories: List<String>,
  private val saveLog: (TrailblazeLog) -> Unit,
) {
  private val pending = AtomicReference(advisories.toList())

  /**
   * Stamped when this holder is built — before the run creates its session — not at drain time.
   * Session logs render in timestamp order, and the two drain sites fire at opposite ends of a
   * run: `captureSessionStarted` at session start, the runner's `finally` after the terminal
   * status. A drain-time stamp put the same warning in a different place depending on which
   * fired, and on the failure path (the one the finally-block drain exists for) always after the
   * Ended card, where a reader triaging the run is least likely to see it.
   */
  private val raisedAt = Clock.System.now()

  fun logTo(sessionId: SessionId) {
    pending.getAndSet(emptyList()).forEach { advisory ->
      saveLog(
        TrailblazeLog.TrailblazeProgressLog(
          eventType = EVENT_TYPE,
          description = advisory,
          session = sessionId,
          timestamp = raisedAt,
        ),
      )
    }
  }

  companion object {
    const val EVENT_TYPE = "PreRunAdvisory"
  }
}
