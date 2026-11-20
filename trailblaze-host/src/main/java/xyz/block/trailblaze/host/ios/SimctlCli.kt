package xyz.block.trailblaze.host.ios

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.util.IosHostSimctlUtils
import xyz.block.trailblaze.util.SimctlCommand
import xyz.block.trailblaze.util.SimctlCommand.Result

/**
 * Thin wrapper around `xcrun simctl` for iOS Simulator app-lifecycle operations that AXe
 * intentionally does not cover (launch, terminate, install, uninstall, openurl).
 *
 * Maestro uses the same tool internally on iOS — we just call it directly from the AXe
 * driver path.
 */
object SimctlCli {

  /** Pure argv build for [launch], extracted so tests can pin that [launchArguments] reach the command. */
  internal fun launchCommand(udid: String, bundleId: String, launchArguments: List<String>): List<String> =
    listOf("launch", udid, bundleId) + launchArguments

  fun launch(udid: String, bundleId: String, launchArguments: List<String> = emptyList(), timeoutSeconds: Long = 15): Result =
    run(launchCommand(udid, bundleId, launchArguments), timeoutSeconds)

  fun terminate(udid: String, bundleId: String, timeoutSeconds: Long = 10): Result =
    run(listOf("terminate", udid, bundleId), timeoutSeconds)

  fun uninstall(udid: String, bundleId: String, timeoutSeconds: Long = 20): Result =
    run(listOf("uninstall", udid, bundleId), timeoutSeconds)

  // 60s default: 30s proved tight for large (GB-plus) production app bundles.
  fun install(udid: String, appBundlePath: String, timeoutSeconds: Long = 60): Result =
    run(listOf("install", udid, appBundlePath), timeoutSeconds)

  /** Path of the installed .app bundle — the explicit `app` container arg (also simctl's default). */
  fun getAppContainer(udid: String, bundleId: String, timeoutSeconds: Long = 10): Result =
    run(listOf("get_app_container", udid, bundleId, "app"), timeoutSeconds)

  /** Path of the app's writable data container. */
  fun getAppDataContainer(udid: String, bundleId: String, timeoutSeconds: Long = 10): Result =
    run(listOf("get_app_container", udid, bundleId, "data"), timeoutSeconds)

  /**
   * The name iOS shows for [bundleId] (`CFBundleDisplayName`, else `CFBundleName`, from
   * `simctl appinfo`), which is the name its notification alert quotes. Null when the app isn't
   * installed or the lookup fails.
   */
  fun appDisplayName(udid: String, bundleId: String, timeoutSeconds: Long = 10): String? {
    val info = run(listOf("appinfo", udid, bundleId), timeoutSeconds)
    return if (info.success) parseAppDisplayName(info.stdout) else null
  }

  /** Reads the display name out of `simctl appinfo`'s plist-style output. */
  internal fun parseAppDisplayName(appinfo: String): String? {
    fun field(key: String) = Regex("^\\s*$key = \"?(.*?)\"?;\\s*$", RegexOption.MULTILINE)
      .find(appinfo)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
    return field("CFBundleDisplayName") ?: field("CFBundleName")
  }

  fun openUrl(udid: String, url: String, timeoutSeconds: Long = 10): Result =
    run(listOf("openurl", udid, url), timeoutSeconds)

  /**
   * `simctl privacy` — grants/revokes/resets a TCC service for an app without prompting
   * (`action` is `grant`/`revoke`/`reset`; `service` is e.g. `all`, `photos`,
   * `location-always`). What Maestro's pre-launch permission setup uses for location; the AXe
   * driver uses it for every service simctl supports (see [IosSimulatorPermissions]).
   */
  fun privacy(udid: String, action: String, service: String, bundleId: String, timeoutSeconds: Long = 10): Result =
    run(listOf("privacy", udid, action, service, bundleId), timeoutSeconds)

  /** Runs an arbitrary command inside the simulator via `simctl spawn` (e.g. `defaults write`). */
  fun spawn(udid: String, command: List<String>, timeoutSeconds: Long = 10): Result =
    run(listOf("spawn", udid) + command, timeoutSeconds)

  /**
   * Shuts the simulator down, boots it again and returns once it has finished booting, running
   * [afterBoot] once it has. An interrupt leaves the boot and [afterBoot] to a background thread, so
   * a cancelled run never leaves the device shut down.
   */
  fun reboot(udid: String, timeoutSeconds: Long = 120, afterBoot: () -> Unit = {}): Result =
    try {
      val shutdown = run(listOf("shutdown", udid), timeoutSeconds)
      bootAfterShutdown(udid, shutdown, timeoutSeconds).also { if (it.success) afterBoot() }
    } catch (e: InterruptedException) {
      thread(isDaemon = true, name = "simctl-reboot-$udid") {
        runCatching { if (bootAfterShutdown(udid, shutdown = null, timeoutSeconds).success) afterBoot() }
      }
      throw e
    }

  /**
   * Boots [udid] once its shutdown has finished. The device's state decides, not [shutdown]'s exit:
   * a shutdown that timed out may still be under way. A device still booted after a failed
   * [shutdown] was never shut down, so that failure is returned. A null [shutdown] (interrupted)
   * leaves a booted device alone. A failed boot is tried again while the device stays shut down.
   */
  internal fun bootAfterShutdown(
    udid: String,
    shutdown: Result?,
    timeoutSeconds: Long,
    state: () -> String? = { deviceState(udid) },
    boot: () -> Result = { run(listOf("bootstatus", udid, "-b"), timeoutSeconds) },
    nanoTime: () -> Long = System::nanoTime,
    sleep: (Long) -> Unit = Thread::sleep,
  ): Result {
    val deadline = nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
    var failedBoot: Result? = null
    while (true) {
      val current = state()
      when (current) {
        "Shutdown" -> boot().let { if (it.success) return it else failedBoot = it }
        "Booted" -> return when {
          failedBoot != null -> boot()
          shutdown == null -> Result(0, "", "")
          shutdown.success -> boot()
          else -> shutdown
        }
      }
      if (nanoTime() >= deadline) {
        return failedBoot
          ?: Result(-1, "", "simulator $udid still `${current ?: "missing"}` after ${timeoutSeconds}s of rebooting")
      }
      sleep(REBOOT_POLL_INTERVAL_MS)
    }
  }

  /** The folder holding simulator [udid]'s files in the default device set, the only set Trailblaze drives. */
  internal fun deviceDirectory(udid: String, userHome: File): File =
    File(userHome, "Library/Developer/CoreSimulator/Devices/$udid")

  /** The state `simctl list devices` shows for [udid] (`Booted`, `Shutdown`, `Shutting Down`, ...), or null. */
  fun deviceState(udid: String): String? = parseDeviceState(run(listOf("list", "devices"), 15).stdout, udid)

  internal fun parseDeviceState(listing: String, udid: String): String? =
    Regex("\\(${Regex.escape(udid)}\\) \\(([^)]+)\\)").find(listing)?.groupValues?.get(1)

  private const val REBOOT_POLL_INTERVAL_MS = 500L

  /** The device type `simctl` reports for [udid], such as `com.apple.CoreSimulator.SimDeviceType.iPad-A16`, or null. */
  fun deviceTypeIdentifier(udid: String): String? {
    val listing = run(listOf("list", "devices", "-j"), 15)
    return listing.stdout.takeIf { listing.success }?.let { parseDeviceTypeIdentifier(it, udid) }
  }

  internal fun parseDeviceTypeIdentifier(listingJson: String, udid: String): String? = runCatching {
    Json.parseToJsonElement(listingJson).jsonObject["devices"]?.jsonObject?.values
      ?.flatMap { it.jsonArray }
      ?.map { it.jsonObject }
      ?.firstOrNull { it["udid"]?.jsonPrimitive?.contentOrNull == udid }
      ?.get("deviceTypeIdentifier")?.jsonPrimitive?.contentOrNull
  }.getOrNull()

  /** Sets the simulated GPS location, `<latitude>,<longitude>`. It lasts until the device shuts down. */
  fun setLocation(udid: String, latitudeLongitude: String, timeoutSeconds: Long = 15): Result =
    run(listOf("location", udid, "set", latitudeLongitude), timeoutSeconds)

  /**
   * Resets the simulator's keychain — the whole device keychain, same as Maestro's
   * `clearKeychain` on simulators. iOS keychain entries survive app uninstall/reinstall, so
   * [clearAppState] alone can leave an app "signed in" after a wipe; this is the missing half.
   */
  fun keychainReset(udid: String, timeoutSeconds: Long = 10): Result =
    run(listOf("keychain", udid, "reset"), timeoutSeconds)

  /**
   * Cheap clearState: terminate, then delete the *children* of the app's data container AND of
   * each of its app-group containers, in place. Skips the uninstall + bundle copy + reinstall
   * that [clearAppState] pays (measured ~9s for a 1.3GB bundle vs ~0.1s for this), at the cost
   * of not resetting the bundle container itself. The group-container wipe is load-bearing:
   * uninstall removes group containers, and an app whose group-container DB survives a
   * data-container-only wipe + keychain reset comes back with undecryptable residue (observed
   * on a large production app: "Failed to decrypt encrypted SyncEntity" + a sign-in flow that
   * skips its verification gate). Also resets the app's TCC privacy grants (`simctl privacy
   * reset all`), which uninstall would have dropped implicitly. Keychain is handled separately
   * by callers (clearKeychain), matching [clearAppState]'s contract.
   */
  fun clearAppDataContainer(udid: String, bundleId: String): Result {
    terminate(udid, bundleId) // nonzero when the app isn't running — a fine starting state
    // Same reason [clearAppState] polls: `terminate` returns as soon as the signal is sent, and a
    // still-dying process can flush state back to disk after the wipe. That matters MORE here —
    // there is no reinstall behind this wipe to overwrite whatever got flushed.
    ensureStopped(udid, bundleId)
    val container = getAppDataContainer(udid, bundleId)
    if (!container.success) return container
    val dataPath = Paths.get(container.stdout.trim())
    // An earlier wipe that deleted the owner record orphaned this container, so the simulator
    // drops it on reboot; a reinstall is the only way to get an owned container back.
    if (IosHostSimctlUtils.needsContainerRepair(bundleId, dataPath)) {
      Console.log("[SimctlCli] $bundleId's data container has no owner record; reinstalling to repair it")
      return clearAppState(udid, bundleId)
    }
    // `groups` lists one `<group-id>\t<path>` per app-group container and exits 0 with EMPTY
    // stdout when the app declares none (verified against simctl on a live device), so a
    // nonzero exit is always a real failure (unknown bundle, timeout) — propagate it rather
    // than silently skip a load-bearing wipe.
    val groups = run(listOf("get_app_container", udid, bundleId, "groups"), 10)
    if (!groups.success) return groups
    val wipeRoots = listOf(dataPath) +
      groups.stdout.lineSequence()
        .mapNotNull { line -> line.substringAfter('\t', "").trim().takeIf { it.isNotEmpty() } }
        .map { Paths.get(it) }
        .filter { Files.isDirectory(it) }
    val undeleted = wipeRoots.flatMap { IosHostSimctlUtils.deleteChildrenWithoutFollowingLinks(it) }
    if (undeleted.isNotEmpty()) {
      // Same hard-fail contract as clearAppState: a partial wipe that reports success would
      // launch the next trail against dirty state, which is the exact bug clearState closes.
      return Result(-1, "", "failed to delete: ${undeleted.joinToString(", ")}")
    }
    IosHostSimctlUtils.recreateStockAppDataDirectories(dataPath)
    // Uninstall implicitly drops the app's TCC privacy grants (location, camera, ...), so flows
    // that expect a fresh permission prompt would silently keep a prior trail's grant under an
    // in-place wipe. Reset them explicitly to keep [clearAppState]'s fresh-install semantics.
    return run(listOf("privacy", udid, "reset", "all", bundleId), 10)
  }

  /**
   * Clears app state the way Maestro does on simulators (`LocalSimulatorUtils.clearAppState`):
   * terminate, then reinstall the app from its own installed bundle — reinstalling is the most
   * stable way to wipe the data container, and `get_app_container` recovers the .app path so no
   * external artifact is needed. Returns the first failing step's [Result] so callers can decide
   * severity. Not usable on iOS system apps (the OS prohibits uninstalling them) — callers
   * upstream already downgrade REINSTALL for `com.apple.*` bundle ids.
   */
  fun clearAppState(udid: String, bundleId: String): Result {
    terminate(udid, bundleId) // nonzero when the app isn't running — a fine starting state
    // `terminate` returns as soon as the signal is sent; a still-dying process can flush
    // state back to disk after the wipe. Poll until it's actually gone (Maestro does the same).
    ensureStopped(udid, bundleId)
    // The shared reinstall retries the install, finishes it even when the uninstall fails or the
    // run is cancelled, and keeps the bundle copy if every install fails, so a failed clearState
    // can't leave the app missing for every later trail (POC finding 9).
    return runCatching { runBlocking { IosHostSimctlUtils.reinstallFromInstalledBundle(udid, bundleId) } }
      .fold(onSuccess = { Result(0, "", "") }, onFailure = { Result(-1, "", it.message ?: it.toString()) })
  }

  /**
   * Polls (up to [timeoutMs]) until the app's process is actually gone, via `launchctl list`
   * inside the simulator. Best-effort: a poll that can't read launchctl, or one that times
   * out, proceeds anyway — matching [terminate]'s best-effort contract.
   */
  fun ensureStopped(udid: String, bundleId: String, timeoutMs: Long = 10_000) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
      val list = spawn(udid, listOf("launchctl", "list"))
      if (!list.success || !isAppRunning(list.stdout, bundleId)) return
      Thread.sleep(250)
    }
  }

  /**
   * Pure parse of `launchctl list` output (`PID\tStatus\tLabel` rows): the app is running
   * while its `UIKitApplication:<bundleId>[…]` job is listed with a numeric PID — launchctl
   * shows `-` for a loaded-but-not-running job. The `[` terminator keeps `com.example.app`
   * from matching `com.example.app2`.
   */
  internal fun isAppRunning(launchctlList: String, bundleId: String): Boolean =
    launchctlList.lineSequence().any { line ->
      line.contains("UIKitApplication:$bundleId[") &&
        line.substringBefore('\t').trim().toIntOrNull() != null
    }

  private fun run(args: List<String>, timeoutSeconds: Long): Result = SimctlCommand.run(args, timeoutSeconds)
}
