package xyz.block.trailblaze.host.driver

import xyz.block.trailblaze.devices.TrailblazeDriverType

/**
 * The set of [HostDriverDescriptor]s one app has plugged in, keyed by driver type.
 *
 * An app config declares its descriptors and this holds them; nothing registers itself, so what a
 * given distribution supports is readable in one place rather than assembled by whichever classes
 * happened to load. That also means two apps can differ: a distribution that doesn't ship a driver
 * simply doesn't register it, and the driver is absent rather than half-present.
 *
 * Every driver now resolves through here — the `when (driverType)` arms that host discovery,
 * screen-state capture and host runs used to carry are gone.
 *
 * Deliberate trade: the compiler no longer proves every site handles every driver, which an
 * exhaustive `when` did. [forDriver] replaces that with a throw that names the driver and the
 * remedy rather than falling through, and construction rejects a descriptor that declines a host
 * run body for a driver dispatch routes to one. That swap is the point of the registry: the cost of
 * adding a driver stops scaling with the number of call sites.
 */
class HostDriverDescriptorRegistry(
  val descriptors: Set<HostDriverDescriptor> = emptySet(),
) {

  private val byDriverType: Map<TrailblazeDriverType, HostDriverDescriptor> =
    descriptors.flatMap { descriptor -> descriptor.driverTypes.map { it to descriptor } }
      .groupBy({ it.first }, { it.second })
      .also { grouped ->
        val duplicated = grouped.filterValues { it.size > 1 }
        require(duplicated.isEmpty()) {
          "Two descriptors claim the same driver, so which one runs would depend on set order: " +
            duplicated.entries.joinToString(", ") { (driverType, claimants) ->
              "$driverType claimed by ${claimants.map { it::class.simpleName }}"
            }
        }
      }
      .mapValues { (_, claimants) -> claimants.single() }
      .also { byType ->
        // The type-level "no host run body" declaration, checked against the enum that decides
        // where the driver actually dispatches. A driver may skip a run body only when BOTH
        // properties hold: `DesktopDispatchDecision` sends `!executesToolsOnDevice` to
        // HOST_IN_PROCESS_KOOG and `!hostRpcReachable` to HOST_DEFAULT, and both of those call
        // `runHostYaml`. The two coincide today; requiring the conjunction means a driver that
        // splits them later fails here rather than at the first host run.
        val cannotRun = byType.filterValues { it is HostDriverDescriptor.OnDeviceTools }
          .filterKeys { !(it.executesToolsOnDevice && it.hostRpcReachable) }
        require(cannotRun.isEmpty()) {
          "These descriptors declare HostDriverDescriptor.OnDeviceTools for a driver that " +
            "dispatch routes to runHostYaml, so a host run of it would throw instead of running: " +
            cannotRun.entries.joinToString(", ") { (driverType, descriptor) ->
              "$driverType via ${descriptor::class.simpleName}"
            } +
            ". Implement HostDriverDescriptor directly and give it a runYaml body."
        }
      }

  /** The descriptor for [driverType], or null when this app hasn't plugged that driver in. */
  fun forDriverOrNull(driverType: TrailblazeDriverType): HostDriverDescriptor? =
    byDriverType[driverType]

  /**
   * The descriptor for [driverType].
   *
   * Throws when there is none — the driver is enabled in this app without being plugged into it.
   */
  fun forDriver(driverType: TrailblazeDriverType): HostDriverDescriptor =
    byDriverType[driverType] ?: error(
      "No HostDriverDescriptor is registered for $driverType. Add one to this app config's " +
        "hostDriverDescriptors, or stop listing $driverType among its supported drivers. " +
        "Registered: ${byDriverType.keys.map { it.name }.sorted()}",
    )

  companion object {
    /** No descriptors — no driver resolves, which is what tests of the failure path want. */
    val EMPTY = HostDriverDescriptorRegistry()
  }
}
