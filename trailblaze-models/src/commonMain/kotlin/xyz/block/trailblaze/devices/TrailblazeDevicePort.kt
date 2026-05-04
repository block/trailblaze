package xyz.block.trailblaze.devices

/**
 * Manages port allocation for Trailblaze devices.
 * Provides deterministic port assignment based on device IDs while avoiding reserved ports.
 */
object TrailblazeDevicePort {

  const val INSTRUMENTATION_ARG_KEY = "trailblaze.ondevice.server.port"

  /**
   * Default HTTP port for the host-side Trailblaze server (MCP, CLI IPC, /ping).
   *
   * The two sibling ports below derive from this with +1 / +2 offsets so that a
   * single `TRAILBLAZE_PORT` override is enough to run multiple daemons in
   * isolation. Override either via the `TRAILBLAZE_PORT` env var or by editing
   * the wrapper script.
   */
  const val TRAILBLAZE_DEFAULT_HTTP_PORT = 52525

  /**
   * Default HTTPS port for the host-side Trailblaze server.
   *
   * Reachable from the device via `adb reverse` for log uploads from the
   * on-device runner. (HTTPS is required because Android disallows cleartext
   * traffic to localhost without explicit allowlisting; we ship a
   * trust-anything HTTPS client to make this work.)
   */
  const val TRAILBLAZE_DEFAULT_HTTPS_PORT = TRAILBLAZE_DEFAULT_HTTP_PORT + 1

  /**
   * Default port for the on-device RPC server inside the runner instrumentation.
   *
   * The host bridges to this port (today via `adb forward`; a dadb-based
   * alternative is being explored) so that calls to `localhost:<port>` on the host
   * reach the on-device server. This is a *device-side* listening port — it
   * lives inside the emulator/device, not on the host. The host-side bridge
   * happens to use the same port number for symmetry.
   *
   * Previously named `TRAILBLAZE_DEFAULT_ADB_REVERSE_PORT`, which was a
   * misnomer — this is the *forward* destination, not anything reverse.
   */
  const val TRAILBLAZE_DEFAULT_ON_DEVICE_RPC_PORT = TRAILBLAZE_DEFAULT_HTTP_PORT + 2

  /** Default MCP endpoint URL for self-connection (localhost with default HTTP port) */
  const val DEFAULT_MCP_URL = "http://localhost:$TRAILBLAZE_DEFAULT_HTTP_PORT/mcp"

  /** Default RPC port for the Compose Desktop driver */
  const val COMPOSE_DEFAULT_RPC_PORT = 52600

  private const val PORT_RANGE_START = 52530
  private const val PORT_RANGE_SIZE = 7000

  /**
   * Ports that are reserved for other purposes and should not be used
   * for device-specific port allocation.
   */
  private val RESERVED_PORTS = setOf(
    TRAILBLAZE_DEFAULT_HTTP_PORT, // host-side HTTP server
    TRAILBLAZE_DEFAULT_HTTPS_PORT, // host-side HTTPS server (adb-reverse target)
    TRAILBLAZE_DEFAULT_ON_DEVICE_RPC_PORT, // on-device RPC server (adb-forward target)
    7001, // Used by default by Maestro
  )

  /**
   * Generates a deterministic port number for the given device based on its instanceId.
   * The port will be in the range [PORT_RANGE_START, PORT_RANGE_START + PORT_RANGE_SIZE)
   * (currently 52530-59529, 7000 unique ports).
   * The same device ID will always generate the same port number.
   * Reserved ports are automatically skipped.
   */
  fun getPortForDevice(trailblazeDeviceId: TrailblazeDeviceId, suffix: String): Int {
    val instanceId = trailblazeDeviceId.instanceId + trailblazeDeviceId.trailblazeDevicePlatform.name + suffix

    // Use the absolute value of hashCode to ensure positive number
    val hash = instanceId.hashCode().let { if (it < 0) -it else it }

    // Start with hash-based port and find the next non-reserved port
    var offset = hash % PORT_RANGE_SIZE
    var attempts = 0

    while (attempts < PORT_RANGE_SIZE) {
      val port = PORT_RANGE_START + offset
      if (port !in RESERVED_PORTS) {
        return port
      }
      // Move to next port in range (with wraparound)
      offset = (offset + 1) % PORT_RANGE_SIZE
      attempts++
    }

    // This should never happen unless all ports are reserved
    error("Unable to find available port for device $instanceId")
  }

  /**
   * Extension function to get a device-specific port.
   * Delegates to [TrailblazeDevicePort.getPortForDevice].
   */
  fun TrailblazeDeviceId.getTrailblazeOnDeviceSpecificPort(): Int = getPortForDevice(this, "trailblaze")

  /**
   * The port that the on-device Maestro RPC server should run on
   */
  fun TrailblazeDeviceId.getMaestroOnDeviceSpecificPort(): Int = getPortForDevice(this, "maestro")

}
