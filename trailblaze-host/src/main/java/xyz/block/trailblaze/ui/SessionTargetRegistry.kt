package xyz.block.trailblaze.ui

import java.util.concurrent.ConcurrentHashMap
import xyz.block.trailblaze.logs.model.SessionId

/**
 * Per-Trailblaze-session target overrides — daemon-process state, keyed by
 * the recording's [SessionId]. Set when the CLI passes `--target X` on an
 * action command (`tool`, `step`, `snapshot`, `ask`, `verify`, `session
 * start`). Cleared when the session ends (any of `endSessionForDevice`,
 * `cancelSessionForDevice`, or the `clearEndedSessionFromDevice` hook on
 * the `LogsRepo.sessionInfoFlow` collector).
 *
 * Lifetime tied to the recording session — matches how `.trail.yaml`
 * targets are read per run. The container is intentionally narrow so the
 * mutation semantics can be unit-tested without spinning up a full
 * `TrailblazeDeviceManager`.
 *
 * Thread-safe via the underlying [ConcurrentHashMap].
 */
internal class SessionTargetRegistry {
  private val overrides = ConcurrentHashMap<SessionId, String>()

  /**
   * Sets the override for [sessionId]. Pass `null` or blank to clear. The
   * blank-as-clear branch keeps callers from accidentally writing empty
   * strings into the map.
   */
  fun set(sessionId: SessionId, appTargetId: String?) {
    if (appTargetId.isNullOrBlank()) {
      overrides.remove(sessionId)
    } else {
      overrides[sessionId] = appTargetId
    }
  }

  /** Returns the override for [sessionId], or null if none is set. */
  fun get(sessionId: SessionId): String? = overrides[sessionId]

  /**
   * Removes any override for [sessionId]. No-op if none was set. Called from
   * every session-end path in `TrailblazeDeviceManager` so the registry
   * never accumulates stale entries.
   */
  fun clear(sessionId: SessionId) {
    overrides.remove(sessionId)
  }

  /**
   * Snapshot of all currently-stored (sessionId, target) pairs. Visible for
   * test inspection only; production code resolves per-session via [get].
   */
  internal fun snapshot(): Map<SessionId, String> = overrides.toMap()
}
