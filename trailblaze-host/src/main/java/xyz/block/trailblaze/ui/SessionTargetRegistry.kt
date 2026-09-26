package xyz.block.trailblaze.ui

import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.logs.model.SessionId

/**
 * Per-Trailblaze-session target overrides — daemon-process state, keyed by
 * the recording's [SessionId] AND the device the override was set for. Set
 * when the CLI passes `--target X` on an action command (`tool`, `step`,
 * `snapshot`, `ask`, `verify`, `session start`). Cleared when the session
 * ends (any of `endSessionForDevice`, `cancelSessionForDevice`, or the
 * `clearEndedSessionFromDevice` hook on the `LogsRepo.sessionInfoFlow`
 * collector).
 *
 * The device is part of the key because one session can span several
 * devices: an MCP session that binds a named cast shares one Trailblaze
 * session across every member (see `TrailblazeDeviceManager.setDeviceRoster`),
 * and `--target` on the start device must not silently re-target the others.
 * A single-device session has one entry and behaves as before.
 *
 * Lifetime tied to the recording session — matches how `.trail.yaml`
 * targets are read per run. The container is intentionally narrow so the
 * mutation semantics can be unit-tested without spinning up a full
 * `TrailblazeDeviceManager`.
 *
 * Thread-safe: every access holds the registry's own monitor.
 */
internal class SessionTargetRegistry {
  /** Session → (device → target), devices in the order their overrides were first set. */
  private val overrides = LinkedHashMap<SessionId, LinkedHashMap<TrailblazeDeviceId, String>>()

  /**
   * Sets the override for [deviceId] within [sessionId]. Pass `null` or blank to clear. The
   * blank-as-clear branch keeps callers from accidentally writing empty strings into the map.
   */
  fun set(sessionId: SessionId, deviceId: TrailblazeDeviceId, appTargetId: String?) = synchronized(overrides) {
    if (appTargetId.isNullOrBlank()) {
      overrides[sessionId]?.let { perDevice ->
        perDevice.remove(deviceId)
        if (perDevice.isEmpty()) overrides.remove(sessionId)
      }
    } else {
      overrides.getOrPut(sessionId) { LinkedHashMap() }[deviceId] = appTargetId
    }
  }

  /** Returns the override for [deviceId] within [sessionId], or null if none is set. */
  fun get(sessionId: SessionId, deviceId: TrailblazeDeviceId): String? = synchronized(overrides) {
    overrides[sessionId]?.get(deviceId)
  }

  /**
   * Returns the override for [sessionId] without saying which device — the one a single-device
   * session has, or the first one set when the session spans several. For surfaces that report on
   * a session as a whole (`session info`, the stop hint); dispatch resolves per device with the
   * two-argument [get].
   */
  fun get(sessionId: SessionId): String? = synchronized(overrides) {
    overrides[sessionId]?.values?.firstOrNull()
  }

  /**
   * Removes every override for [sessionId]. No-op if none was set. Called from
   * every session-end path in `TrailblazeDeviceManager` so the registry
   * never accumulates stale entries.
   */
  fun clear(sessionId: SessionId) {
    synchronized(overrides) { overrides.remove(sessionId) }
  }

  /**
   * Snapshot of all currently-stored (sessionId, deviceId) → target entries. Visible for
   * test inspection only; production code resolves per session and device via [get].
   */
  internal fun snapshot(): Map<Pair<SessionId, TrailblazeDeviceId>, String> = synchronized(overrides) {
    overrides.flatMap { (session, perDevice) -> perDevice.map { (device, target) -> (session to device) to target } }.toMap()
  }
}
