package xyz.block.trailblaze.toolcalls

import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo

/**
 * Session-scoped registry of the devices a multi-device session holds, with exactly one
 * **active** device at a time. This is the state the `switchDevice` tool mutates and the
 * indirection host runners read so screen capture and tool dispatch follow the handover
 * (see `SwitchDeviceTrailblazeTool` for the interaction model).
 *
 * Devices are keyed by the names declared in the trail's multi-device configuration (the
 * named entries of a configuration's `devices:` map). Map order is meaningful: the FIRST
 * entry is the device the trail starts on. Replays start there too, because the host runner
 * constructs a fresh bindings instance per session — there is no reset-and-reuse across
 * runs. There is no reserved name.
 *
 * ## Thread-safety
 * [activeName] is `@Volatile` so reads from capture lambdas on other threads observe a
 * switch, but switching itself is expected to happen only from the sequential tool-dispatch
 * loop — the same single-threaded contract [TrailblazeToolExecutionContext] documents.
 */
class SessionDeviceBindings(
  /**
   * Every bound device keyed by its declared name, in declaration order — the first entry
   * is where the trail starts.
   */
  devices: Map<String, BoundDevice>,
) {

  /**
   * One bound device. Identity only: screen capture reads the ACTIVE device's agent through the
   * host runner's own indirection, not through the binding.
   *
   * Identity is [trailblazeDeviceId] and nothing else, because that is all a binding needs to be
   * usable — routing a handover only ever needs to know WHICH device, not how big its screen is.
   * [trailblazeDeviceInfo] is the optional richer probe.
   */
  class BoundDevice(
    /** Which device this is. The one field a binding cannot do without. */
    val trailblazeDeviceId: TrailblazeDeviceId,
    /**
     * The device's probed properties, or null when identity is known but nothing was probed.
     *
     * Null is a real state, not a degenerate one. A caller that binds a device it was handed by
     * name — an interactive MCP session, for instance — has no screen to measure at bind time,
     * and requiring info there produced fabricated geometry rather than honest absence.
     * Consumers must degrade: describe the device by id and drop whatever the probe would have
     * added, rather than treating a zero as a measurement.
     */
    val trailblazeDeviceInfo: TrailblazeDeviceInfo?,
    /** Human-readable role description declared by the selected configuration. */
    val description: String?,
    /** Effective app target id for this device (its override, else the session target). */
    val targetId: String?,
  ) {
    init {
      // Two fields can name the device, so they must never disagree: `switchDevice` resolves one
      // and the prompt roster describes the other, and a mismatch would let the agent hand over
      // to a device the prompt told it something else about.
      val infoDeviceId = trailblazeDeviceInfo?.trailblazeDeviceId
      require(infoDeviceId == null || infoDeviceId == trailblazeDeviceId) {
        "bound device identity disagrees with its probed info: id is " +
          "${trailblazeDeviceId.toFullyQualifiedDeviceId()} but info reports " +
          "${infoDeviceId?.toFullyQualifiedDeviceId()}"
      }
    }
  }

  init {
    require(devices.isNotEmpty()) { "a multi-device session must bind at least one device" }
    // Sibling invariant, one level up from [BoundDevice]'s: two NAMES must not resolve to the same
    // device. Binding by identity alone makes this reachable on the by-name path — hand the same
    // serial in twice and the roster advertises two devices that are one, so `switchDevice` is a
    // silent no-op and every later assertion runs on the display the agent thinks it left.
    // Indistinguishable from a working handover in any log, which is why it fails at bind time.
    val namesByDeviceId = devices.entries.groupBy({ it.value.trailblazeDeviceId }, { it.key })
    val collision = namesByDeviceId.entries.firstOrNull { it.value.size > 1 }
    require(collision == null) {
      "device ${collision!!.key.toFullyQualifiedDeviceId()} is bound to more than one name " +
        "(${collision.value.joinToString()}) — a handover between them would do nothing"
    }
  }

  private val devicesByName: Map<String, BoundDevice> = LinkedHashMap(devices)

  /** Every bound name in declaration order. Stable order for error messages and prompts. */
  val names: Set<String> get() = devicesByName.keys

  /** The name of the device the trail starts on — the first declared entry. */
  val startName: String = devicesByName.keys.first()

  /** The name whose device currently receives capture and dispatch. */
  @Volatile
  var activeName: String = startName
    private set

  /** The currently-active device. */
  val active: BoundDevice get() = devicesByName.getValue(activeName)

  fun deviceFor(name: String): BoundDevice? = devicesByName[name]

  /**
   * Make the device named [name] active and return its binding.
   *
   * @throws IllegalArgumentException when [name] is not bound — the caller (the
   * `switchDevice` tool) converts this into a structured tool error listing [names].
   */
  fun switchTo(name: String): BoundDevice {
    val bound = requireNotNull(devicesByName[name]) {
      "no device bound for name '$name' — bound devices: ${names.joinToString()}"
    }
    activeName = name
    return bound
  }
}
