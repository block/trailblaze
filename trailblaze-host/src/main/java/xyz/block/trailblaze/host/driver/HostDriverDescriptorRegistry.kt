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
 * **Conversion state.** Drivers are moving onto descriptors one group at a time. Call sites ask
 * [forDriverOrNull] first and fall back to their pre-existing `when (driverType)` arms, so an
 * unconverted driver behaves exactly as before. As each group converts, its arms are deleted; the
 * last one to go turns the remaining `when` into a plain registry lookup.
 *
 * Deliberate trade: for a converted driver the compiler no longer proves every site handles it —
 * an exhaustive `when` did. [validateCovers] replaces that with a startup check, and [forDriver]
 * throws a remedy rather than falling through. That swap is the point of the registry: the cost of
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

  /** The descriptor for [driverType], or null when that driver hasn't been converted yet. */
  fun forDriverOrNull(driverType: TrailblazeDriverType): HostDriverDescriptor? =
    byDriverType[driverType]

  /**
   * The descriptor for [driverType].
   *
   * Throws when there is none — reaching this from a converted call site means the driver is
   * enabled without being plugged in, which [validateCovers] is meant to catch at startup.
   */
  fun forDriver(driverType: TrailblazeDriverType): HostDriverDescriptor =
    byDriverType[driverType] ?: error(
      "No HostDriverDescriptor is registered for $driverType. Add one to this app config's " +
        "hostDriverDescriptors, or stop listing $driverType among its supported drivers. " +
        "Registered: ${byDriverType.keys.map { it.name }.sorted()}",
    )

  /**
   * Fails when a driver the app says it supports has no descriptor, so the mismatch surfaces at
   * startup instead of when someone finally runs on that driver.
   *
   * Only checks drivers that have finished converting ([convertedDriverTypes]) — an unconverted
   * one still has its `when` arms and needs no descriptor. And only in the direction that can
   * strand a user: registering a descriptor for a driver the app doesn't currently enable is fine,
   * since that is how a driver stays plugged in while switched off in settings.
   */
  fun validateCovers(supportedDriverTypes: Set<TrailblazeDriverType>) {
    val missing = supportedDriverTypes
      .filter { it in convertedDriverTypes && it !in byDriverType }
      .map { it.name }
      .sorted()
    check(missing.isEmpty()) {
      "These drivers are supported but have no HostDriverDescriptor: $missing. " +
        "Register one per driver in this app config's hostDriverDescriptors."
    }
  }

  companion object {
    /** No descriptors — every driver takes its pre-existing `when` arm. */
    val EMPTY = HostDriverDescriptorRegistry()

    /**
     * Drivers that have finished converting and must therefore have a descriptor wherever they're
     * supported. Grows as each group in the conversion lands; when it holds every entry, the
     * fallback `when` arms are gone and this can be replaced by `TrailblazeDriverType.entries`.
     */
    val convertedDriverTypes: Set<TrailblazeDriverType> = setOf(
      TrailblazeDriverType.REVYL_ANDROID,
      TrailblazeDriverType.REVYL_IOS,
      TrailblazeDriverType.COMPOSE,
      TrailblazeDriverType.PLAYWRIGHT_NATIVE,
      TrailblazeDriverType.PLAYWRIGHT_ELECTRON,
    )
  }
}
