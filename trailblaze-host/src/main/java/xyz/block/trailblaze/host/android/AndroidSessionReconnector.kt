package xyz.block.trailblaze.host.android

import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.util.AndroidHostAdbUtils

/**
 * Re-establishes the host's on-device RPC session with one Android device after that device
 * rebooted, for the `android_reconnectSession` primitive. Built by whoever ran the original
 * connect — it knows the instrumentation target and connect options the device was bound with —
 * and handed to the device's agent, which calls [reconnect] with what it holds that must be dropped
 * before the same connect is run again.
 *
 * [reconnect] runs the sequence in this order, and tests pin it:
 *  1. forget what this JVM cached about the device's adb transport (forwards, shell client), so the
 *     connect does not trust forwards the adb server dropped with the old transport;
 *  2. drop the caller's device-bound state ([reconnect]'s `beforeReconnect`);
 *  3. run [reconnectDevice] on the SAME RPC client the session already holds;
 *  4. only after that succeeded: [onReconnected], so the recording's device streams resume.
 *
 * A failed connect propagates to the step; [onReconnected] is not called for it.
 */
class AndroidSessionReconnector(
  /** Where progress is reported, so a watchdog keyed on progress does not abandon a slow connect. */
  val onProgressMessage: (String) -> Unit,
  /**
   * Re-runs the connect/readiness flow against the rebooted device on the SAME RPC client the
   * session already holds. Throws when the runner does not come back ready.
   */
  val reconnectDevice: suspend () -> Unit,
  /**
   * Called with the session's id once [reconnectDevice] succeeded, so what else the session held
   * open to the device, such as its log capture, can resume on the new boot. Default: nothing.
   */
  val onReconnected: (SessionId) -> Unit = {},
  /** Test seam: evicts this JVM's cached adb state for the device; see step 1 above. */
  private val forgetDeviceState: (TrailblazeDeviceId) -> Unit = AndroidHostAdbUtils::forgetDeviceStateAfterReboot,
) {

  /**
   * Brings the session with [deviceId] back on its current boot. Returns the summary the step
   * reports.
   *
   * @param sessionId the recording session to resume device streams for, read after reconnecting.
   * @param beforeReconnect what the caller holds that died with the old boot and must be dropped
   *   before [reconnectDevice] runs.
   */
  suspend fun reconnect(
    deviceId: TrailblazeDeviceId,
    sessionId: () -> SessionId,
    beforeReconnect: () -> Unit,
  ): String {
    onProgressMessage("Reconnecting session to ${deviceId.instanceId}.")
    forgetDeviceState(deviceId)
    beforeReconnect()
    reconnectDevice()
    onReconnected(sessionId())
    onProgressMessage("Reconnected to ${deviceId.instanceId}.")
    return "Session reconnected to ${deviceId.instanceId}."
  }
}
