package xyz.block.trailblaze.cli

import xyz.block.trailblaze.devices.TrailDeviceSelection
import xyz.block.trailblaze.devices.TrailDeviceSelector
import xyz.block.trailblaze.devices.TrailblazeConnectedDeviceSummary
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType

/** Outcome of [CliRunDeviceResolver.resolve] — the device a run request runs on, or why not. */
sealed interface CliRunDeviceResolution {
  data class Selected(val device: TrailblazeConnectedDeviceSummary) : CliRunDeviceResolution

  /**
   * Several real devices could run the trail and nothing picked one — the caller must fail loud
   * (never silently run on the first device). [candidates] are the physical devices to choose
   * between with `--device <platform>/<instanceId>`.
   */
  data class MultipleDevices(val candidates: List<TrailblazeConnectedDeviceSummary>) : CliRunDeviceResolution

  /** No connected device can satisfy the request; [reason] is the user-facing explanation. */
  data class NoMatch(val reason: String) : CliRunDeviceResolution
}

/**
 * Device concretization shared by the daemon's `/cli/run` handler AND the CLI's in-process
 * (`--no-daemon`) run path, built on the CLI's selection policy ([TrailDeviceSelector]) so the
 * two paths cannot drift:
 *
 *  1. An explicit device id (the CLI resolves one before delegating) is honored; when the same
 *     physical device is listed once per driver variant, a requested driver picks its variant.
 *  2. An explicit driver (request flag or the trail's v1 `driver:`) narrows to its device.
 *  3. A web/compose-only trail routes to its virtual device regardless of connected real devices.
 *  4. Otherwise the shared policy applies to the REAL connected devices: exactly one runs;
 *     two or more is [CliRunDeviceResolution.MultipleDevices] (fail loud, matching the CLI's
 *     `--device`-required error); zero falls back to the trail's declared platforms, then the
 *     ever-present virtual device (so a deviceless natural-language run still lands on web).
 *
 * "Real" here means [TrailblazeDevicePlatform.usesVirtualDevice] is false — every WEB and
 * DESKTOP entry is excluded from the count. That is deliberately broader than the CLI
 * autodetect's `filterRealDevices` (CliMcpClient), which only drops the single ever-present
 * `web/playwright-native` entry from `device list` output: the list here comes from
 * `loadDevicesSuspend`, whose web/compose entries are all downstream-provisioned virtual
 * devices, never something a run should implicitly pin as "the one connected device".
 *
 * Pure — the caller enumerates devices and reads the trail's platforms; no I/O here.
 */
object CliRunDeviceResolver {

  fun resolve(
    devices: List<TrailblazeConnectedDeviceSummary>,
    requestedDeviceId: String?,
    requestedDriverType: TrailblazeDriverType?,
    trailPlatforms: Set<TrailblazeDevicePlatform>,
  ): CliRunDeviceResolution {
    if (requestedDeviceId != null) return resolveExplicit(devices, requestedDeviceId, requestedDriverType)

    if (requestedDriverType != null) {
      // A driver narrows, it doesn't pick: with several devices exposing the driver, fail loud
      // exactly like the CLI's --driver path (which narrows to the driver's platform and then
      // applies the same one-runs / several-ambiguous rule). One physical device appears at most
      // once per driver type, so no variant dedupe is needed here.
      val matches = devices.filter { it.trailblazeDriverType == requestedDriverType }
      return when {
        matches.isEmpty() -> CliRunDeviceResolution.NoMatch("No connected device for driver '$requestedDriverType'")
        matches.size == 1 -> CliRunDeviceResolution.Selected(matches.single())
        else -> CliRunDeviceResolution.MultipleDevices(matches)
      }
    }

    // A web/compose trail runs on a virtual device provisioned on demand, independent of the
    // connected real devices — mirroring the CLI's web/compose defer in `resolveDevicesForFile`.
    if (trailPlatforms.isNotEmpty() && trailPlatforms.all { it.usesVirtualDevice }) {
      return devices.filter { it.platform in trailPlatforms }.pickPreferringNativeBrowser()
        ?.let { CliRunDeviceResolution.Selected(it) }
        ?: CliRunDeviceResolution.NoMatch(
          "No virtual device available for platforms $trailPlatforms",
        )
    }

    // Default path: the shared strict policy over the REAL connected devices. The same physical
    // device can be listed once per driver variant (e.g. one emulator as instrumentation AND
    // accessibility), so group by fully-qualified id first — the count that matters is physical
    // devices, and the run request carries only (platform, instanceId), not the variant.
    val realDeviceBySpec = devices
      .filterNot { it.platform.usesVirtualDevice }
      .groupBy { it.platform.toFullyQualifiedDeviceId(it.instanceId) }
      .mapValues { (_, variants) -> variants.first() }
    val selection = TrailDeviceSelector.selectDevicesToRun(
      explicitDevices = emptyList(),
      allDevices = false,
      defaultDevice = null,
      connectedSpecs = realDeviceBySpec.keys.toList(),
      supportedPlatforms = emptySet(),
    )
    return when (selection) {
      is TrailDeviceSelection.Resolved -> {
        val spec = selection.deviceSpecs.single()
        if (spec != null) {
          CliRunDeviceResolution.Selected(realDeviceBySpec.getValue(spec))
        } else {
          // Zero real devices — this IS the "downstream device loading" the CLI defers to, so
          // concretize: a device matching the trail's declared platforms, else the ever-present
          // virtual device (keeps deviceless natural-language runs landing on web).
          if (trailPlatforms.isNotEmpty()) {
            devices.filter { it.platform in trailPlatforms }.pickPreferringNativeBrowser()
              ?.let { CliRunDeviceResolution.Selected(it) }
              ?: CliRunDeviceResolution.NoMatch(
                "No connected device for the trail's platforms " +
                  "(${trailPlatforms.joinToString(", ") { it.name }})",
              )
          } else {
            devices.pickPreferringNativeBrowser()
              ?.let { CliRunDeviceResolution.Selected(it) }
              ?: CliRunDeviceResolution.NoMatch("No devices connected")
          }
        }
      }
      is TrailDeviceSelection.Ambiguous ->
        CliRunDeviceResolution.MultipleDevices(selection.candidateSpecs.map { realDeviceBySpec.getValue(it) })
      // Unreachable without --all-devices; kept exhaustive for the shared sealed type.
      is TrailDeviceSelection.NoDevices -> CliRunDeviceResolution.NoMatch("No devices connected")
    }
  }

  /**
   * Matches an explicit device id in the same three formats as the CLI `--device` flag:
   * `platform/instance-id`, bare `platform` (auto-select within it), or a raw instance id
   * (exact, then substring). A blank instance id after the slash (`web/`) is treated as
   * platform-only rather than letting `contains("")` silently match the first candidate.
   *
   * The same physical instanceId is listed once per driver variant (Android exposes
   * ANDROID_ONDEVICE_INSTRUMENTATION and ANDROID_ONDEVICE_ACCESSIBILITY for one emulator), so
   * among the instance matches a [requestedDriverType] (the trail's `devices:` pin / `--driver`
   * / app setting) picks its variant rather than whichever variant happens to be listed first.
   */
  private fun resolveExplicit(
    devices: List<TrailblazeConnectedDeviceSummary>,
    requestedDeviceId: String,
    requestedDriverType: TrailblazeDriverType?,
  ): CliRunDeviceResolution {
    val parts = requestedDeviceId.split("/", limit = 2)
    val specPlatform = TrailblazeDevicePlatform.fromString(parts[0])
    val specInstanceId = (if (specPlatform != null) parts.getOrNull(1) else requestedDeviceId)
      ?.takeIf { it.isNotBlank() }

    if (specInstanceId != null) {
      val candidates = if (specPlatform != null) devices.filter { it.platform == specPlatform } else devices
      val instanceMatches = candidates.filter { it.trailblazeDeviceId.instanceId == specInstanceId }
        .ifEmpty { candidates.filter { it.trailblazeDeviceId.instanceId.contains(specInstanceId) } }
      return (
        requestedDriverType?.let { dt -> instanceMatches.find { it.trailblazeDriverType == dt } }
          ?: instanceMatches.firstOrNull()
        )
        ?.let { CliRunDeviceResolution.Selected(it) }
        ?: CliRunDeviceResolution.NoMatch("No connected device matches '$requestedDeviceId'")
    }
    if (specPlatform == null) {
      // Neither a known platform nor a usable instance id (e.g. a blank string).
      return CliRunDeviceResolution.NoMatch("No connected device matches '$requestedDeviceId'")
    }
    // Platform only — auto-select within the platform.
    return devices.filter { it.platform == specPlatform }.pickPreferringNativeBrowser()
      ?.let { CliRunDeviceResolution.Selected(it) }
      ?: CliRunDeviceResolution.NoMatch("No connected device for platform '${parts[0]}'")
  }
}

/**
 * The native Playwright browser is the canonical pick when several devices are eligible and the
 * choice is platform-driven (web-trail routing, platform-only `--device web` specs, the desktop
 * picker's web-trail pre-check); otherwise the first candidate. Shared so the daemon resolver
 * and the desktop Run picker can't drift on which web driver wins.
 */
internal fun List<TrailblazeConnectedDeviceSummary>.pickPreferringNativeBrowser(): TrailblazeConnectedDeviceSummary? =
  find { it.trailblazeDriverType == TrailblazeDriverType.PLAYWRIGHT_NATIVE } ?: firstOrNull()
