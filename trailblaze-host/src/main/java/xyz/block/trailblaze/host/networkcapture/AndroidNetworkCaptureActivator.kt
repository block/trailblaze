package xyz.block.trailblaze.host.networkcapture

import xyz.block.trailblaze.devices.TrailblazeDeviceId
import java.io.File

/**
 * SPI for "spin up host-driven Android network capture for this session". The MCP bridge calls
 * [start] when an Android session has `TrailblazeConfig.captureNetworkTraffic` enabled — or when
 * the session is opted in another way ([isSessionCaptureOptedIn], or the proxy env opt-in on
 * [CompositeAndroidNetworkCaptureActivator]) — and [stop] when the session ends.
 *
 * Implementations live out-of-tree because the on-device mechanics — debug-pref seeding,
 * the wire protocol, the abstract-socket name — are app-specific. The host stays engine-agnostic:
 * it knows there's *some* Android capture available and where to drop the NDJSON, nothing more.
 *
 * The activator is responsible for everything from reading `/proc/net/unix` to setting up
 * `adb forward` to writing `<sessionDir>/network.ndjson` in the canonical [NetworkEvent] schema.
 * One sink per (session, device); [start] is idempotent for the same `sessionId` + `deviceId`.
 */
interface AndroidNetworkCaptureActivator {

  /**
   * Spin up capture for the given session's [deviceId]. Idempotent — calling [start] twice for the
   * same `sessionId` AND `deviceId` should be a no-op (the bridge dispatches per-tool, so we'll see
   * this on every call until the session ends). A multi-device session calls [start] once per bound
   * device, so implementations must key their per-run state on BOTH, not on `sessionId` alone.
   *
   * Implementations should not block — kick off the bridge thread and return. Failure to
   * connect is logged, not thrown, so a misbehaving target app can't take down the dispatch loop.
   *
   * [targetAppIds] carries EVERY application id the resolved target may run under on this
   * platform (a target can declare several build flavors — `com.example.app.dev`,
   * `com.example.app`, … — and which one is installed varies by lane). An activator that
   * validates the capture peer's identity must accept any of them.
   *
   * An empty list means the caller could not resolve a target, NOT "skip validation". An
   * activator that validates identity must refuse to start rather than attach to an unverified
   * peer — attaching would capture another instrumented app's traffic and report it as this
   * session's. Because callers log arming failures instead of propagating them (above), that
   * refusal surfaces when the session is torn down, not here. An activator that does not check
   * peer identity at all (e.g. a proxy) can ignore the list.
   *
   * [deviceLabel] is the name a multi-device session's configuration declares for this device
   * (`seller`, `buyer`, …) and is what makes a session's captured artifacts attributable to the
   * screen they came from. **`null` means single-device**: the session binds exactly one device, so
   * there is nothing to disambiguate and artifacts keep their unsuffixed names. A multi-device
   * session passes a label for EVERY bound device including the start device — in a session with
   * two displays there is no "the" network stream, and an unsuffixed file would silently mean
   * "whichever display happened to arm first", which is precisely the unattributed evidence a
   * label exists to remove.
   */
  fun start(
    sessionId: String,
    sessionDir: File,
    deviceId: TrailblazeDeviceId,
    targetAppIds: List<String>,
    deviceLabel: String? = null,
  )

  /**
   * Tear down capture for EVERY device of the given session. Closes the NDJSON sinks, removes any
   * `adb forward` mappings, joins the worker threads. Idempotent.
   *
   * Session-scoped rather than per-device on purpose: teardown is driven by the session finalizer,
   * which knows a session ended but does not enumerate the devices capture armed for it. An
   * implementation that keys per-run state on (session, device) must therefore fan out here.
   */
  fun stop(sessionId: String)

  /**
   * Whether this activator opts [sessionId] into capture ON ITS OWN, independent of
   * `TrailblazeConfig.captureNetworkTraffic` and the `TRAILBLAZE_ANDROID_PROXY_CAPTURE` env
   * opt-in. The bridge-start gates OR this into their capture checks, so a downstream activator
   * with its own per-run toggle (e.g. an env-var-driven capture mode read by the activator's
   * distribution) can engage without the operator also turning on network capture.
   *
   * Read per-call, not cached: a long-lived daemon dispatches many sessions and the toggle may
   * differ between them. Default false — activators without a self-contained opt-in never
   * change behavior.
   */
  fun isSessionCaptureOptedIn(sessionId: String): Boolean = false
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
