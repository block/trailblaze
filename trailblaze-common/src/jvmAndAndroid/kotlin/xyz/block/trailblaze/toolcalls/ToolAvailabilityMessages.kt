package xyz.block.trailblaze.toolcalls

import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType

/**
 * The wording for "this tool can't run on this device", shared by the up-front rejection a
 * direct tool call gets and the error a tool returns when it reaches a device it has no path on.
 *
 * Both name the device the call actually landed on. Without that, a call routed to the wrong
 * device (a saved default, another session's selection) reads as a tool limitation rather than a
 * routing mistake. They name drivers only when the tool is limited to some of the drivers on a
 * platform the device has, which is the one case where the platform alone doesn't explain the
 * rejection.
 */
object ToolAvailabilityMessages {

  /** The platforms the `mobile_*` primitives have an implementation for. */
  val MOBILE_PLATFORMS: Set<TrailblazeDevicePlatform> =
    setOf(TrailblazeDevicePlatform.ANDROID, TrailblazeDevicePlatform.IOS)

  /** `Android`, `Android and iOS`, `Android, iOS and Web Browser`, in enum order. */
  fun platformList(platforms: Collection<TrailblazeDevicePlatform>): String {
    val names = platforms.distinct().sortedBy { it.ordinal }.map { it.displayName }
    return when (names.size) {
      0 -> ""
      1 -> names.single()
      else -> names.dropLast(1).joinToString(", ") + " and " + names.last()
    }
  }

  /** `web/playwright-native (Web Browser)`, or just `Web Browser` without an instance id. */
  fun describeDevice(platform: TrailblazeDevicePlatform, deviceId: TrailblazeDeviceId?): String =
    if (deviceId != null) "${deviceId.toFullyQualifiedDeviceId()} (${platform.displayName})" else platform.displayName

  /**
   * The error a platform-switching tool returns from `execute` when the device it was handed is on
   * a platform it has no implementation for.
   */
  fun unsupportedPlatform(
    toolName: String,
    supported: Collection<TrailblazeDevicePlatform>,
    deviceInfo: TrailblazeDeviceInfo,
  ): String =
    "$toolName runs on ${platformList(supported)} only, and this device is " +
      "${describeDevice(deviceInfo.platform, deviceInfo.trailblazeDeviceId)}."

  /**
   * Where [toolName] is available according to the toolsets that declare it, phrased against the
   * current device, or null when the toolsets don't restrict it by driver (a toolset with no
   * `drivers:` is available everywhere, so the rejection came from the target, not the device).
   */
  fun whereAvailable(
    toolName: String,
    entries: List<ToolSetCatalogEntry>,
    currentDriver: TrailblazeDriverType?,
    currentDeviceId: TrailblazeDeviceId?,
  ): String? {
    val declaring = entries.filter { toolName in it.toolNames }
    if (declaring.isEmpty() || declaring.any { it.compatibleDriverTypes.isEmpty() }) return null
    val drivers = declaring.flatMap { it.compatibleDriverTypes }.toSet()
    // The device's driver can run it, so the target filtered it out (e.g. `excluded_tools:`).
    // A device sentence here would blame the device for a target-scope rejection.
    if (currentDriver != null && currentDriver in drivers) return null
    val platforms = drivers.map { it.platform }.toSet()
    val device = currentDriver?.let { describeDevice(it.platform, currentDeviceId) }

    if (currentDriver != null && currentDriver.platform in platforms && currentDriver !in drivers) {
      val platformDrivers = drivers.filter { it.platform == currentDriver.platform }.sortedBy { it.ordinal }
      val switchHint = platformDrivers.firstNotNullOfOrNull { it.cliShortName }?.let { shortName ->
        " Switch with `trailblaze config ${currentDriver.platform.name.lowercase()}-driver $shortName`, " +
          "then reconnect the device."
      }.orEmpty()
      val deviceSubject = if (currentDeviceId != null) device else "this ${currentDriver.platform.displayName} device"
      return "$toolName runs on ${currentDriver.platform.displayName} only with the " +
        "${platformDrivers.joinToString(" or ") { it.yamlKey }} driver, and $deviceSubject uses " +
        "${currentDriver.yamlKey}.$switchHint"
    }
    return "$toolName runs on ${platformList(platforms)} only" +
      (device?.let { ", and this device is $it." } ?: ".")
  }
}
