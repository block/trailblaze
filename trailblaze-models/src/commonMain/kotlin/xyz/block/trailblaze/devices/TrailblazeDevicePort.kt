package xyz.block.trailblaze.devices

/**
 * Manages port allocation for Trailblaze devices.
 * Provides deterministic port assignment based on device IDs while avoiding reserved ports.
 */
object TrailblazeDevicePort {

  const val INSTRUMENTATION_ARG_KEY = "trailblaze.ondevice.server.port"

  /** Android on-device uses this to route calls back to host */
  const val TRAILBLAZE_DEFAULT_ADB_REVERSE_PORT = 52526

  private const val PORT_RANGE_START = 52527
  private const val PORT_RANGE_SIZE = 7000

  /**
   * Ports that are reserved for other purposes and should not be used
   * for device-specific port allocation.
   */
  private val RESERVED_PORTS = setOf(
    52525, // Reserved for other Trailblaze services
    7001, // Used by default by Maestro
    TRAILBLAZE_DEFAULT_ADB_REVERSE_PORT, // Android on-device uses this to route calls back to host
  )

  /**
   * Generates a deterministic port number for the given device based on its instanceId.
   * The port will be in the range 52500-53499 (7000 unique ports).
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
