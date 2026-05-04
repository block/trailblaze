package xyz.block.trailblaze.config

import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType

/**
 * Maps YAML driver-type key strings to sets of [TrailblazeDriverType].
 *
 * Used by `.app.yaml` and `.toolset.yaml` files to express which driver types
 * a tool or toolset applies to. Supports both platform-level shorthands
 * (e.g. "android" → on-device Android drivers) and specific driver keys
 * (derived from [TrailblazeDriverType.yamlKey]).
 *
 * Revyl drivers are intentionally excluded from platform shorthands — they are
 * cloud-hosted devices and must be referenced explicitly via "revyl-android"
 * or "revyl-ios".
 */
object DriverTypeKey {

  /** Revyl drivers are cloud-hosted and excluded from platform-level shorthands. */
  private val REVYL_DRIVERS = setOf(
    TrailblazeDriverType.REVYL_ANDROID,
    TrailblazeDriverType.REVYL_IOS,
  )

  private val KEY_MAP: Map<String, Set<TrailblazeDriverType>> = buildMap {
    // Per-driver keys — derived from each driver's yamlKey
    for (dt in TrailblazeDriverType.entries) {
      put(dt.yamlKey, setOf(dt))
    }

    // Platform-level shorthands — derived from TrailblazeDevicePlatform,
    // excluding Revyl (cloud-hosted) drivers.
    for (platform in TrailblazeDevicePlatform.entries) {
      val drivers = TrailblazeDriverType.entries
        .filter { it.platform == platform && it !in REVYL_DRIVERS }
        .toSet()
      if (drivers.isNotEmpty()) {
        put(platform.name.lowercase(), drivers)
      }
    }

    put("all", TrailblazeDriverType.entries.toSet())
  }

  /**
   * Every YAML key string accepted by [resolve], in lowercase. Includes per-driver
   * keys (e.g., `playwright-native`), platform-level shorthands (e.g., `android`),
   * and the meta-key `all`.
   *
   * Exposed for compile-time reference validators (e.g., `TrailblazeCompiler`) that
   * need to check whether a `drivers:` entry in a target manifest names a real
   * driver before emitting the resolved target. Using this set is preferable to
   * try/catching [resolve] — that path allocates an exception on every miss and
   * loses the call site of the typo.
   */
  val knownKeys: Set<String> = KEY_MAP.keys.toSet()

  /**
   * Resolves a YAML key to a set of [TrailblazeDriverType]s.
   * Keys are case-insensitive.
   *
   * @throws IllegalArgumentException if the key is unknown
   */
  fun resolve(key: String): Set<TrailblazeDriverType> {
    return KEY_MAP[key.lowercase()]
      ?: throw IllegalArgumentException(
        "Unknown driver type key: '$key'. Valid keys: ${KEY_MAP.keys.sorted()}"
      )
  }

  /**
   * Whether [key] (case-insensitive) is a known driver-type key. Cheap membership
   * check that doesn't allocate or throw — pair this with [resolve] only after a
   * caller has confirmed the key is valid.
   */
  fun isKnown(key: String): Boolean = key.lowercase() in knownKeys

  /**
   * Returns all driver types that match ANY of the given keys.
   */
  fun resolveAll(keys: List<String>): Set<TrailblazeDriverType> =
    keys.flatMap { resolve(it) }.toSet()

  /**
   * Returns all YAML keys that include the given [driverType].
   * Useful for reverse-mapping a driver type to matching YAML sections.
   */
  fun keysContaining(driverType: TrailblazeDriverType): Set<String> =
    KEY_MAP.filterValues { driverType in it }.keys
}
