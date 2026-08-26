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

  internal const val PORT_RANGE_START = 52530
  internal const val PORT_RANGE_SIZE = 7000

  /**
   * Ports that are reserved for other purposes and should not be used
   * for device-specific port allocation.
   *
   * Only entries inside `[PORT_RANGE_START, PORT_RANGE_START + PORT_RANGE_SIZE)` can ever be
   * hashed onto, so only those make the skip loop in [portForHash] do work. The out-of-range
   * entries earn their place by documenting which ports are spoken for, and by staying correct
   * if the range moves.
   */
  internal val RESERVED_PORTS = setOf(
    TRAILBLAZE_DEFAULT_HTTP_PORT, // host-side HTTP server
    TRAILBLAZE_DEFAULT_HTTPS_PORT, // host-side HTTPS server (adb-reverse target)
    TRAILBLAZE_DEFAULT_ON_DEVICE_RPC_PORT, // on-device RPC server (adb-forward target)
    COMPOSE_DEFAULT_RPC_PORT, // Compose Desktop driver RPC server — in range, so a real collision
    7001, // Used by default by Maestro
  )

  /**
   * Generates a deterministic port number for the given device based on its instanceId.
   * The port will be in the range [PORT_RANGE_START, PORT_RANGE_START + PORT_RANGE_SIZE)
   * (currently 52530-59529, 7000 unique ports).
   * The same device ID will always generate the same port number.
   * Reserved ports are automatically skipped.
   *
   * The optional [namespace] disambiguates daemons that are talking to *different* physical
   * devices that nonetheless report the same `instanceId` — the common case being two emulators
   * each named `emulator-5554` reachable via independent ADB-server tunnels (e.g. one local +
   * one remote workstation). The default reads from [HostPortNamespace.current]: on JVM hosts
   * that is `ANDROID_ADB_SERVER_PORT` (so two daemons with distinct ADB ports get distinct
   * hashes); on Android (on-device APK) and wasmJs it is `""` (the on-device runner reads its
   * port from an instrumentation arg, not from this hash). Single-daemon deployments with the
   * default ADB port get `""` and the historical hash is preserved.
   */
  fun getPortForDevice(
    trailblazeDeviceId: TrailblazeDeviceId,
    suffix: String,
    namespace: String = HostPortNamespace.current,
  ): Int {
    val instanceId = trailblazeDeviceId.instanceId +
      trailblazeDeviceId.trailblazeDevicePlatform.name + suffix + namespace

    return portForHash(instanceId.hashCode())
  }

  /**
   * Maps an arbitrary hash into the allocatable range, skipping [RESERVED_PORTS].
   *
   * Split out from the [String.hashCode] call in [getPortForDevice] so the arithmetic can be
   * exercised with a chosen hash — a hash that lands on a reserved port, or one whose negation
   * overflows — without reverse-engineering a device id that happens to produce it.
   */
  internal fun portForHash(hash: Int): Int {
    // Widen before negating: as an Int, -Int.MIN_VALUE overflows back to Int.MIN_VALUE, leaving
    // a negative offset and a port below PORT_RANGE_START. The wraparound below cannot recover
    // one either, since a negative offset stays negative under `% PORT_RANGE_SIZE`.
    val positiveHash = hash.toLong().let { if (it < 0) -it else it }

    // Start with hash-based port and find the next non-reserved port
    var offset = (positiveHash % PORT_RANGE_SIZE).toInt()
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
    error("Unable to find an unreserved port for hash $hash")
  }

  /**
   * Extension function to get a device-specific port.
   * Delegates to [TrailblazeDevicePort.getPortForDevice]. See that function for [namespace].
   */
  fun TrailblazeDeviceId.getTrailblazeOnDeviceSpecificPort(
    namespace: String = HostPortNamespace.current,
  ): Int = getPortForDevice(this, "trailblaze", namespace)

  /**
   * The port that the on-device Maestro RPC server should run on
   */
  fun TrailblazeDeviceId.getMaestroOnDeviceSpecificPort(
    namespace: String = HostPortNamespace.current,
  ): Int = getPortForDevice(this, "maestro", namespace)

}
