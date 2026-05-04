package xyz.block.trailblaze.host.networkcapture

import xyz.block.trailblaze.devices.TrailblazeDeviceId
import java.io.File

/**
 * SPI for "spin up host-driven Android network capture for this session". The MCP bridge calls
 * [start] when an Android session has `TrailblazeConfig.captureNetworkTraffic` enabled, and [stop]
 * when the session ends.
 *
 * Implementations live out-of-tree because the on-device mechanics — debug-pref seeding,
 * the wire protocol, the abstract-socket name — are app-specific. The host stays engine-agnostic:
 * it knows there's *some* Android capture available and where to drop the NDJSON, nothing more.
 *
 * The activator is responsible for everything from reading `/proc/net/unix` to setting up
 * `adb forward` to writing `<sessionDir>/network.ndjson` in the canonical [NetworkEvent] schema.
 * One sink per session; [start] is idempotent for the same `sessionId`.
 */
interface AndroidNetworkCaptureActivator {

  /**
   * Spin up capture for the given session. Idempotent — calling [start] twice for the same
   * `sessionId` should be a no-op (the bridge dispatches per-tool, so we'll see this on every
   * call until the session ends).
   *
   * Implementations should not block — kick off the bridge thread and return. Failure to
   * connect is logged, not thrown, so a misbehaving target app can't take down the dispatch loop.
   */
  fun start(
    sessionId: String,
    sessionDir: File,
    deviceId: TrailblazeDeviceId,
    targetAppId: String?,
  )

  /**
   * Tear down capture for the given session. Closes the NDJSON sink, removes any `adb forward`
   * mapping, joins the worker thread. Idempotent.
   */
  fun stop(sessionId: String)
}

/**
 * Process-wide registry for the optional Android capture activator. Downstream desktop
 * apps may set this at startup; default distributions leave it null and the host gracefully
 * skips Android capture.
 *
 * Single-slot: at most one activator per process. We don't need fan-out for the foreseeable
 * future — when multi-engine Android capture lands, we'll route inside the activator itself.
 */
object AndroidNetworkCaptureRegistry {
  @Volatile
  var activator: AndroidNetworkCaptureActivator? = null
}
