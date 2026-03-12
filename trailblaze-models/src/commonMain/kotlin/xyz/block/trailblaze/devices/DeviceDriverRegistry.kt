package xyz.block.trailblaze.devices

/**
 * Registry for device drivers, providing namespaced access to different
 * device interaction backends.
 *
 * Tools can query the registry for the specific driver type they need
 * (e.g., ADB, Maestro, Playwright) without coupling to concrete implementations.
 */
class DeviceDriverRegistry {
  @PublishedApi
  internal val drivers = mutableMapOf<String, DeviceDriver>()

  /** Registers a device driver. */
  fun register(driver: DeviceDriver) {
    drivers[driver.driverName] = driver
  }

  /** Gets a driver by its name. */
  fun get(name: String): DeviceDriver? = drivers[name]

  /** Gets a driver by type. */
  inline fun <reified T : DeviceDriver> get(): T? =
    drivers.values.filterIsInstance<T>().firstOrNull()

  /** Returns names of all registered drivers. */
  fun available(): Set<String> = drivers.keys.toSet()

  /** Convenience: get the ADB driver if registered. */
  val adb: AdbDeviceDriver?
    get() = get<AdbDeviceDriver>()
}
