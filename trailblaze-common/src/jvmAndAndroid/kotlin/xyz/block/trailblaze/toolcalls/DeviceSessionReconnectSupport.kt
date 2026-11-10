package xyz.block.trailblaze.toolcalls

/**
 * Implemented by an agent that can re-establish the transport it drives its device through after
 * the device's running system was replaced underneath it — a reboot — so the steps after it run in
 * the same session.
 *
 * Only a host-driven agent can offer this. An agent running ON the device dies with the reboot, so
 * it never implements the interface and
 * [xyz.block.trailblaze.mobile.tools.AndroidReconnectSessionTrailblazeTool] fails with a clear
 * message there instead of hanging.
 */
interface DeviceSessionReconnectSupport {

  /**
   * Drops what the agent held open to the device's previous boot and reconnects to it. The device
   * must already be back and booted; this does not wait for it.
   *
   * @return A short human-readable summary (for the tool result).
   * @throws Exception when the device did not come back ready.
   */
  suspend fun reconnectSession(): String
}
