package xyz.block.trailblaze.ui

import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.model.AppVersionInfo
import xyz.block.trailblaze.util.Console

/**
 * On-demand per-device app inventory: installed app IDs plus version info for target-relevant
 * apps.
 *
 * Device discovery used to probe this eagerly for every device on every enumeration pass, which
 * taxed every `device LIST` / CLI autodetect with a `pm list packages` per Android device and a
 * multi-second `xcrun simctl listapps` per booted iOS simulator — the dominant cost that made a
 * plain Android `snapshot` seconds slower whenever an iOS simulator happened to be booted
 * (OSS issue block/trailblaze#216). Inventory is now pulled only by the consumers that
 * actually need it (the Run Configuration dialog, `device INFO detail=APPS`,
 * `getInstalledApps`), via [refresh].
 *
 * Contract:
 * - Concurrent [refresh] calls for the same device coalesce onto one in-flight probe; different
 *   devices probe independently. Probes run on [scope], so a cancelled caller never cancels a
 *   probe another caller has joined. [scope] must be supervisor-scoped and long-lived (an
 *   unrelated child failure on a plain [kotlinx.coroutines.Job] would cancel it and with it
 *   every future probe).
 * - No TTL: a [refresh] that starts a probe reads current device state — callers ask when they
 *   need fresh data (e.g. the agent may have just installed an app) — and nobody else pays
 *   anything. A [refresh] that joins an in-flight probe returns that probe's result, which read
 *   the device at the moment the probe started (this includes the moments right around the
 *   probe's completion — joining is by identity in [inFlight], not by wall clock). Coalescing
 *   is keyed by device, not by probe mode, so a version-probing caller that joins an in-flight
 *   ID-only probe gets no NEW version entries from it (already-known ones survive).
 * - A failed, timed-out, or cancelled probe returns **null** and publishes nothing, leaving any
 *   previously-known inventory intact. Null is distinct from an empty set, which means the
 *   device genuinely reports no installed apps — callers that gate on installation (e.g.
 *   `launchApp` validation) must not treat a failed probe as "nothing is installed". Failure is
 *   not sticky; the next [refresh] probes again.
 * - Version info is probed only for `installed ∩ relevantAppIds`. A published probe drops a
 *   device's version entry only when its app is no longer installed or the entry was just
 *   re-probed; entries this probe didn't cover survive (so an ID-only probe skips the version
 *   fan-out rather than clearing the cache).
 */
class DeviceAppInventory(
  private val scope: CoroutineScope,
  /** Returns the device's installed app IDs, or null if the probe failed (see [refresh]). */
  private val installedAppIdsProvider: (TrailblazeDeviceId) -> Set<String>?,
  private val appVersionInfoProvider: (TrailblazeDeviceId, String) -> AppVersionInfo?,
) {

  private val _installedAppIdsByDeviceFlow =
    MutableStateFlow<Map<TrailblazeDeviceId, Set<String>>>(emptyMap())

  /** Last-probed installed app IDs per device. Devices never probed are absent. */
  val installedAppIdsByDeviceFlow: StateFlow<Map<TrailblazeDeviceId, Set<String>>> =
    _installedAppIdsByDeviceFlow.asStateFlow()

  private val _appVersionInfoByDeviceFlow =
    MutableStateFlow<Map<DeviceAppKey, AppVersionInfo>>(emptyMap())

  /** Last-probed version info per (device, app), covering target-relevant apps only. */
  val appVersionInfoByDeviceFlow: StateFlow<Map<DeviceAppKey, AppVersionInfo>> =
    _appVersionInfoByDeviceFlow.asStateFlow()

  /** In-flight probes by device. A probe's value is null when it failed — see [refresh]. */
  private val inFlight = ConcurrentHashMap<TrailblazeDeviceId, Deferred<Set<String>?>>()

  /**
   * Probes [deviceId]'s installed apps (and version info for `installed ∩ relevantAppIds`),
   * publishes both flows, and returns the installed app IDs — or **null** if the probe failed,
   * in which case nothing is published. Joins an already-in-flight probe for the same device
   * instead of starting a second one.
   */
  suspend fun refresh(deviceId: TrailblazeDeviceId, relevantAppIds: Set<String>): Set<String>? {
    val deferred = inFlight.computeIfAbsent(deviceId) {
      // The probe body must not run inline on the calling thread: it removes its own [inFlight]
      // entry in `finally`, which inside `computeIfAbsent` would be a recursive update
      // ConcurrentHashMap forbids. [scope]'s dispatcher must therefore not be immediate /
      // Unconfined (production wires Dispatchers.IO).
      scope.async {
        try {
          probeAndPublish(deviceId, relevantAppIds)
        } finally {
          inFlight.remove(deviceId)
        }
      }
    }
    return try {
      deferred.await()
    } catch (e: CancellationException) {
      // If WE were cancelled, propagate. Otherwise the probe's side died (e.g. [scope] torn
      // down): evict the dead entry so it can't wedge the map, and report a probe failure
      // rather than surfacing a cancellation the caller didn't cause.
      currentCoroutineContext().ensureActive()
      inFlight.remove(deviceId, deferred)
      null
    }
  }

  /**
   * Drops flow entries for devices no longer in [connectedDeviceIds]. Called from discovery to
   * clear a disconnected device's stale inventory; retains entries for still-connected devices
   * (discovery itself never probes). Best-effort, not a barrier: a probe already in flight when
   * this runs can republish its device afterwards, so an entry may outlive the device until the
   * next pass. Harmless because readers render from the live device list, not from these maps'
   * key sets.
   */
  fun prune(connectedDeviceIds: Set<TrailblazeDeviceId>) {
    _installedAppIdsByDeviceFlow.update { current ->
      current.filterKeys { it in connectedDeviceIds }
    }
    _appVersionInfoByDeviceFlow.update { current ->
      current.filterKeys { it.deviceId in connectedDeviceIds }
    }
  }

  private fun probeAndPublish(
    deviceId: TrailblazeDeviceId,
    relevantAppIds: Set<String>,
  ): Set<String>? {
    // A throwing provider must not escape (joined callers expect the null failure signal) — but
    // only plain exceptions are a "probe failure": cancellation must unwind normally and fatal
    // Errors must not be masked. A failed probe publishes NOTHING: overwriting good inventory
    // with an empty set would make an installed app read as uninstalled everywhere downstream.
    val installed = try {
      installedAppIdsProvider(deviceId)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      null
    } ?: run {
      // The only breadcrumb for a device stuck rendering as "app not installed" — the provider's
      // own timeout log is written under discovery's prefix.
      Console.log("[app-inventory] probe failed for ${deviceId.toFullyQualifiedDeviceId()}; keeping previously-known inventory")
      return null
    }
    _installedAppIdsByDeviceFlow.update { current -> current + (deviceId to installed) }

    val probedAppIds = installed.intersect(relevantAppIds)
    val versionInfoForDevice = probedAppIds.mapNotNull { appId ->
      try {
        appVersionInfoProvider(deviceId, appId)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        null
      }?.let { DeviceAppKey(deviceId, appId) to it }
    }.toMap()
    _appVersionInfoByDeviceFlow.update { current ->
      // Drop only what this probe proved stale: an entry for an app that is no longer installed,
      // or one we just re-probed (the fresh value overlays below; a re-probe that came back null
      // means the device stopped reporting a version). An entry we did NOT probe survives while
      // its app is still installed — an ID-only probe skips the version fan-out, it does not
      // invalidate versions already known.
      current.filterKeys { key ->
        key.deviceId != deviceId || (key.appId in installed && key.appId !in probedAppIds)
      } + versionInfoForDevice
    }
    return installed
  }
}
