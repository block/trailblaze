package xyz.block.trailblaze.http

import java.net.URI

/**
 * Hosts that mean "the machine driving this device", read from on the device: the far end of an
 * `adb reverse`, and the emulator's alias for its host.
 */
private val DEVICE_LOCAL_LOG_HOSTS = setOf("localhost", "10.0.2.2", "::1")

/**
 * Whether the log channel to [logsBaseUrl] should ignore the process's proxy selection.
 *
 * True for the default endpoints — the host at the other end of an `adb reverse`, or the emulator's
 * host loopback. That is a control channel the device's HTTP proxy is not for, and a network
 * capture's proxy, live or left behind by a daemon that died mid-capture, must not be able to take
 * it down.
 *
 * False for everything else, because `trailblaze.logsEndpoint` may name a remote log server and a
 * device that reaches the outside world only through its proxy needs that proxy to get there. An
 * address this cannot parse counts as remote: it cannot be shown to be device-local.
 */
internal fun shouldBypassProxyForLogServer(logsBaseUrl: String): Boolean {
  val host = runCatching { URI(logsBaseUrl).host }.getOrNull()
    ?.lowercase()
    ?.trim('[', ']')
    ?: return false
  // Whole-host equality, never a suffix match: `localhost.logs.example.com` is someone else's host.
  return host in DEVICE_LOCAL_LOG_HOSTS || host.startsWith("127.")
}
