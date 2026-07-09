package xyz.block.trailblaze.host.networkcapture

import xyz.block.trailblaze.devices.TrailblazeDeviceId
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Routes each Android capture session to [proxy] or [fallback] based on [useProxy], evaluated
 * per-session at [start] time (so the choice can be toggled on a running daemon).
 *
 * The [AndroidNetworkCaptureRegistry] is single-slot, so this is how the opt-in mitmproxy MITM
 * capture coexists with whatever default capture a distribution ships:
 * - **OSS / external users** (the primary audience for the proxy path — third-party apps, no in-app
 *   capture plugin): `fallback = null`. With the opt-in off, Android capture is simply a no-op; with
 *   it on, sessions route to [proxy].
 * - **Internal distributions** may pass a `fallback` (a distribution-specific in-app capture
 *   activator) so non-opt-in sessions keep that default behavior, while the opt-in switches to the
 *   universal proxy path.
 *
 * The delegate that handled a `sessionId` is remembered so [stop] tears down the right one.
 */
class CompositeAndroidNetworkCaptureActivator(
  private val proxy: AndroidNetworkCaptureActivator,
  private val fallback: AndroidNetworkCaptureActivator?,
  private val useProxy: () -> Boolean,
) : AndroidNetworkCaptureActivator {

  private val routed: MutableMap<String, AndroidNetworkCaptureActivator> = ConcurrentHashMap()

  override fun start(
    sessionId: String,
    sessionDir: File,
    deviceId: TrailblazeDeviceId,
    targetAppId: String?,
  ) {
    // Idempotent per the SPI (the MCP bridge calls start() per-tool until the session ends): the
    // FIRST call for a sessionId picks the delegate and records it; later calls route to that SAME
    // delegate. Re-evaluating useProxy() each call would let a mid-session opt-in flip switch
    // delegates and leave the first one running (stop() only tears down the recorded one).
    val delegate = routed[sessionId] ?: run {
      val chosen = (if (useProxy()) proxy else fallback)
        ?: return // OSS with the opt-in off → no Android capture (no-op).
      routed[sessionId] = chosen
      chosen
    }
    delegate.start(sessionId, sessionDir, deviceId, targetAppId)
  }

  override fun stop(sessionId: String) {
    routed.remove(sessionId)?.stop(sessionId)
  }

  companion object {
    /** Opt-in env flag selecting the mitmproxy MITM capture path. Off unless `1`/`true`. */
    const val ENV_ANDROID_PROXY_CAPTURE: String = "TRAILBLAZE_ANDROID_PROXY_CAPTURE"

    fun proxyCaptureEnabledFromEnv(): Boolean {
      val v = System.getenv(ENV_ANDROID_PROXY_CAPTURE)?.trim()?.lowercase()
      return v == "1" || v == "true"
    }
  }
}
