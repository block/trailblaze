package xyz.block.trailblaze.devices

/**
 * Manages port allocation for Trailblaze devices.
 * Provides deterministic port assignment based on device IDs while avoiding reserved ports.
 */
object TrailblazeDevicePort {

  const val INSTRUMENTATION_ARG_KEY = "trailblaze.ondevice.server.port"

  /**
   * Instrumentation arg carrying the host daemon's resolved HTTPS port to the device.
   *
   * The on-device runner POSTs session logs to this port (via `adb reverse` under
   * `trailblaze.reverseProxy`, or `10.0.2.2` on an emulator), so it must always equal the
   * port the daemon's HTTPS server actually bound — not [TRAILBLAZE_DEFAULT_HTTPS_PORT],
   * which is wrong whenever `TRAILBLAZE_PORT`/`TRAILBLAZE_HTTPS_PORT` or a persisted setting
   * moved the daemon. `HostAndroidDeviceConnectUtils` sets it from the same value it
   * reverse-forwards; device-side readers (`InstrumentationArgUtil.logsEndpoint`,
   * `AndroidTestInstrumentation.logsEndpoint`) fall back to the default only when absent.
   */
  const val HTTPS_PORT_INSTRUMENTATION_ARG_KEY = "trailblaze.httpsPort"

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
   * Every port [getPortForDevice] can hand out — currently 52530-59529.
   *
   * A device's port is also a *host* port: the host bridges to the device with
   * `adb forward tcp:<port> tcp:<port>`, and `adb forward` takes a host port that is already
   * bound without failing. So nothing else on the host may listen inside this range unless it
   * is in [RESERVED_PORTS], or a device will silently steal it.
   *
   * Use [requirePortOutsideDeviceAllocationRange] to enforce that for a port whose value is
   * only known at runtime.
   */
  val DEVICE_ALLOCATION_PORT_RANGE: IntRange =
    PORT_RANGE_START until PORT_RANGE_START + PORT_RANGE_SIZE

  /**
   * Ports that are reserved for other purposes and should not be used
   * for device-specific port allocation.
   *
   * Only entries inside [DEVICE_ALLOCATION_PORT_RANGE] can ever be hashed onto, so only those
   * make the skip loop in [portForHash] do work. The out-of-range entries earn their place by
   * documenting which ports are spoken for, and by staying correct if the range moves.
   *
   * Reserving works only for ports known at compile time. A port resolved at runtime — the
   * daemon's own HTTP/HTTPS port, which comes from a `-p` flag, a persisted `serverPort`, or
   * `TRAILBLAZE_PORT` — cannot be added here: this is `commonMain`, and two of those three
   * sources are host state it cannot read. Such ports are kept safe by staying *outside*
   * [DEVICE_ALLOCATION_PORT_RANGE] instead. See [requirePortOutsideDeviceAllocationRange].
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
   * Fails when [port] is anywhere inside [DEVICE_ALLOCATION_PORT_RANGE], so a host-side listener
   * never picks a port that `adb forward` will later take from it.
   *
   * Rejects [RESERVED_PORTS] too, which is the point for a *configured* port: reserving a port
   * keeps devices off it, it does not make the port free. `TRAILBLAZE_PORT=52600` would put the
   * daemon on top of the Compose driver's RPC server. A server that owns a reserved port calls
   * [requirePortNotAllocatableToDevices] instead.
   *
   * Call this before binding a server on a port whose value comes from configuration.
   * [portDescription] names the port in the failure message (e.g. `"TRAILBLAZE_PORT"`).
   */
  fun requirePortOutsideDeviceAllocationRange(port: Int, portDescription: String) {
    if (port in DEVICE_ALLOCATION_PORT_RANGE) {
      throw portRangeConflict(port, portDescription)
    }
  }

  /**
   * Whether [port] is one the daemon may be configured to use: a real TCP port, and not one a
   * device could be allocated.
   *
   * The single definition for every surface that *saves* a daemon port — the Settings UI, the
   * Settings REST/RPC patch, `trailblaze config`. A saved port outranks every source but the
   * runtime `-p` flag, so a surface that accepts one the daemon will refuse
   * ([requireDaemonPortsOutsideDeviceAllocationRange]) leaves the next launch dead with no UI left
   * to undo it. Callers may narrow further (the Settings UI also requires >= 1024); none may widen.
   */
  fun isSelectableDaemonPort(port: Int): Boolean =
    port in 1..65535 && port !in DEVICE_ALLOCATION_PORT_RANGE

  /**
   * Fails when either resolved daemon port is one a device could be allocated.
   *
   * Call this before *any* use of the resolved ports, including the `/ping` probe that decides
   * whether a daemon is already running: a device's `adb forward` on the configured port answers
   * that probe, so probing first turns a configuration error into "already running — attaching to
   * it". The bind-time check is the backstop, not the first line.
   *
   * The HTTPS half names the setting that actually moves it, which depends on how it was derived.
   * A derived port is nobody's explicit choice — `TRAILBLAZE_PORT` one below the range passes its
   * own check and fails here — so it points at `TRAILBLAZE_PORT`. An explicitly configured HTTPS
   * port takes precedence over that derivation, so lowering `TRAILBLAZE_PORT` would leave startup
   * failing on the same port; that case points at the HTTPS setting instead.
   */
  fun requireDaemonPortsOutsideDeviceAllocationRange(httpPort: Int, httpsPort: Int) {
    requirePortOutsideDeviceAllocationRange(
      httpPort,
      "The daemon HTTP port (from TRAILBLAZE_PORT, or serverPort in $SETTINGS_FILE_NAME, which " +
        "outranks the env var)",
    )
    val httpsWasDerivedFromHttp = httpsPort == httpPort + 1
    requirePortOutsideDeviceAllocationRange(
      httpsPort,
      if (httpsWasDerivedFromHttp) {
        "The daemon HTTPS port (derived as the HTTP port + 1, so TRAILBLAZE_PORT must be below " +
          "${DEVICE_ALLOCATION_PORT_RANGE.first - 1})"
      } else {
        "The daemon HTTPS port (set explicitly, so lowering TRAILBLAZE_PORT will not move it — " +
          "change TRAILBLAZE_HTTPS_PORT, or serverHttpsPort in $SETTINGS_FILE_NAME, which " +
          "outranks it)"
      },
    )
  }

  /**
   * Named in daemon port failures because it is the only place a *persisted* port can be changed.
   *
   * There is no `--port` flag and no `trailblaze config` key for the daemon ports, and a persisted
   * value outranks `TRAILBLAZE_PORT` — so for a saved port, the env var the user reaches for first
   * cannot fix it. The Settings UI and `PUT /api/settings` can, but both are served by the daemon
   * that is refusing to start.
   */
  private const val SETTINGS_FILE_NAME = "trailblaze-settings.json"

  /**
   * Fails when [port] is one a device could actually be allocated — inside
   * [DEVICE_ALLOCATION_PORT_RANGE] and not in [RESERVED_PORTS].
   *
   * For a server whose own port is reserved: the reservation is what keeps devices off it, so its
   * default must pass, while an override into the rest of the range must not.
   */
  fun requirePortNotAllocatableToDevices(port: Int, portDescription: String) {
    if (port in DEVICE_ALLOCATION_PORT_RANGE && port !in RESERVED_PORTS) {
      throw portRangeConflict(port, portDescription)
    }
  }

  private fun portRangeConflict(port: Int, portDescription: String) =
    TrailblazePortRangeConflictException(
      "$portDescription is $port, which is inside the range Trailblaze allocates device " +
        "ports from (${DEVICE_ALLOCATION_PORT_RANGE.first}-${DEVICE_ALLOCATION_PORT_RANGE.last}). " +
        "A connected device can be assigned this port, and `adb forward` would take it from " +
        "this server without reporting an error, leaving it unreachable. Pick a port below " +
        "${DEVICE_ALLOCATION_PORT_RANGE.first}. Above the range is worse, not better: it is " +
        "further into the OS ephemeral range, where an unrelated outbound connection can be " +
        "assigned the port as its source and the bind then fails outright.",
    )

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

/**
 * A configured port cannot be used because devices are allocated from that range.
 *
 * Distinct from a bind failure on purpose: nothing is wrong with the machine, and no rival daemon
 * is involved, so a caller must not treat it as "someone else owns this port" and attach or exit
 * quietly. The configuration has to change.
 */
class TrailblazePortRangeConflictException(message: String) : IllegalStateException(message)
