package xyz.block.trailblaze.host.ios

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import xyz.block.trailblaze.util.SimctlCommand

/**
 * A simulator's screen size in pixels, read from disk instead of from a screenshot.
 *
 * The simulator's `device.plist` names its device type. The device type's bundle lists its
 * displays in `capabilities.plist`, and the `integrated` display has the same pixel width and
 * height as a `simctl io screenshot` (the screenshot may be rotated). Reading the two files takes
 * tens of milliseconds. The screenshot takes over a second.
 *
 * Every read is best-effort: a missing file, an unknown device type, or a failed command returns
 * null, and the device classifies as platform-only.
 */
class IosSimulatorScreenSize(
  private val runCommand: (args: List<String>, timeoutSeconds: Long) -> SimctlCommand.Result =
    { args, timeoutSeconds -> SimctlCommand.runCommand(args, timeoutSeconds) },
  private val userHome: () -> File = { File(System.getProperty("user.home")) },
) {

  data class Pixels(val width: Int, val height: Int)

  /** The screen size of the simulator [udid] in the default device set, or null when it can't be read from disk. */
  fun read(udid: String): Pixels? {
    val deviceType = deviceTypeOf(udid) ?: return null
    val capabilities = capabilitiesFileFor(deviceType) ?: return null
    return plistAsJson(capabilities)?.let(::parseIntegratedDisplay)
  }

  private fun deviceTypeOf(udid: String): String? {
    val plist = File(SimctlCli.deviceDirectory(udid, userHome()), "device.plist").takeIf { it.isFile } ?: return null
    val result = runCommand(listOf("plutil", "-extract", "deviceType", "raw", "-o", "-", plist.path), PLUTIL_TIMEOUT_SECONDS)
    return result.stdout.trim().takeIf { result.success && it.isNotEmpty() }
  }

  /**
   * Device type identifier → bundle path, from one `simctl list devicetypes -j` per process.
   * Device types change only when Xcode or a simulator runtime is installed or moved, so an
   * identifier that is missing from the cached map, or whose cached bundle is gone, triggers a
   * re-read. An identifier with no readable bundle after a successful re-read is remembered, so
   * its device doesn't re-list on every lookup.
   */
  @Volatile
  private var bundlePaths: Map<String, String>? = null
  private val unknownDeviceTypes: MutableSet<String> = ConcurrentHashMap.newKeySet()
  private val lock = Any()

  private fun capabilitiesFileFor(deviceType: String): File? {
    cachedCapabilitiesFile(deviceType)?.let { return it }
    if (deviceType in unknownDeviceTypes) return null
    synchronized(lock) {
      cachedCapabilitiesFile(deviceType)?.let { return it }
      if (deviceType in unknownDeviceTypes) return null
      val listing = runCommand(SimctlCommand.argv("list", "devicetypes", "-j"), SIMCTL_TIMEOUT_SECONDS)
      val parsed = listing.stdout.takeIf { listing.success }?.let(::parseDeviceTypeBundles).orEmpty()
      if (parsed.isEmpty()) return null
      bundlePaths = parsed
      val file = cachedCapabilitiesFile(deviceType)
      if (file == null) unknownDeviceTypes += deviceType
      return file
    }
  }

  private fun cachedCapabilitiesFile(deviceType: String): File? =
    bundlePaths?.get(deviceType)
      ?.let { File(it, "Contents/Resources/capabilities.plist") }
      ?.takeIf { it.isFile }

  private fun plistAsJson(plist: File): String? {
    val result = runCommand(listOf("plutil", "-convert", "json", "-o", "-", plist.path), PLUTIL_TIMEOUT_SECONDS)
    return result.stdout.takeIf { result.success }
  }

  companion object {
    private val default = IosSimulatorScreenSize()

    /** [read] on the shared, process-wide instance, whose device-type cache lives as long as the process. */
    fun of(udid: String): Pixels? = default.read(udid)

    internal fun parseDeviceTypeBundles(listingJson: String): Map<String, String> = runCatching {
      Json.parseToJsonElement(listingJson).jsonObject["devicetypes"]?.jsonArray.orEmpty()
        .map { it.jsonObject }
        .mapNotNull { type ->
          val id = type["identifier"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
          val path = type["bundlePath"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
          id to path
        }
        .toMap()
    }.getOrDefault(emptyMap())

    /** The `integrated` display from a `capabilities.plist` converted to JSON, or null when there is none. */
    internal fun parseIntegratedDisplay(capabilitiesJson: String): Pixels? = runCatching {
      val displays = Json.parseToJsonElement(capabilitiesJson).jsonObject["capabilities"]?.jsonObject
        ?.get("displays")?.jsonArray.orEmpty()
        .map { it.jsonObject }
      val main = displays.firstOrNull { it.string("displayType") == "integrated" } ?: return@runCatching null
      val width = main["width"]?.jsonPrimitive?.intOrNull ?: return@runCatching null
      val height = main["height"]?.jsonPrimitive?.intOrNull ?: return@runCatching null
      Pixels(width, height).takeIf { width > 0 && height > 0 }
    }.getOrNull()

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    private const val PLUTIL_TIMEOUT_SECONDS = 3L
    private const val SIMCTL_TIMEOUT_SECONDS = 5L
  }
}
