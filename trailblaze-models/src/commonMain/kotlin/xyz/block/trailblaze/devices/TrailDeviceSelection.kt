package xyz.block.trailblaze.devices

import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.yaml.TrailYamlItem
import xyz.block.trailblaze.yaml.TrailblazeYaml
import xyz.block.trailblaze.yaml.unified.TrailDocument
import xyz.block.trailblaze.yaml.unified.UnifiedTrailTargets

/**
 * Outcome of selecting which device(s) a single trail runs on — the device spec(s) to run, or a
 * signal that the run is ambiguous and the user must pick.
 */
sealed interface TrailDeviceSelection {
  /**
   * Run once per entry in [deviceSpecs] (usually one; several under `--device a,b` / `--all-devices`).
   * A `null` element means "no explicit device — let downstream device loading resolve it"
   * (single-connected-device already set the spec; web/compose use virtual devices).
   */
  data class Resolved(val deviceSpecs: List<String?>) : TrailDeviceSelection

  /**
   * More than one connected device could run the trail. [candidateSpecs] (non-empty) are the
   * fully-qualified device ids the user should choose between (`--device` on the CLI, the device
   * picker on the desktop app).
   */
  data class Ambiguous(val candidateSpecs: List<String>) : TrailDeviceSelection

  /**
   * No connected device to run on at all — distinct from [Ambiguous] so the caller can emit a
   * "no devices connected" message rather than "pick one of []". Currently reached only by
   * `--all-devices` with nothing connected (the non-fan-out path defers to downstream device
   * loading, which reports the no-devices error itself).
   */
  data object NoDevices : TrailDeviceSelection
}

/**
 * The pure device-selection policy every surface that runs a trail shares — CLI `trailblaze run`,
 * the daemon's `/cli/run` handler, and the desktop app's Run action — so the surfaces cannot
 * drift. No I/O: callers read files / enumerate devices and hand in plain values.
 */
object TrailDeviceSelector {

  /**
   * Pure device selection: decide which connected device(s) a single trail runs on. Returns
   * [TrailDeviceSelection.Resolved] with one or more device specs to run (a `null` element means
   * "no explicit device — let downstream device loading resolve it"), or
   * [TrailDeviceSelection.Ambiguous] when the user must disambiguate. Unit-testable with plain
   * strings, no daemon.
   *
   * [supportedPlatforms] is the platform filter derived by the caller from EXPLICIT signals only
   * (`--driver`'s platform, or the trail's declared platforms under `--all-devices`); it is empty
   * for the bare default path (= "any"), so on that path this narrows by nothing and the
   * single-vs-several check becomes a strict connected-device count.
   *
   * Precedence:
   *  1. [explicitDevices] (`--device`, comma-split) — run each, in order (fan out when >1).
   *  2. [allDevices] (`--all-devices`) — every connected device whose platform is in
   *     [supportedPlatforms] (all connected when the trail declares none); none matching → Ambiguous.
   *  3. [defaultDevice] (env / shell pin / single autodetect / persisted config) — single run.
   *  4. No default and no multi-device list ([connectedSpecs] null/empty) — a single deferred run.
   *  5. Multiple connected, nothing pinned — narrow by [supportedPlatforms] (a `--driver` platform,
   *     else empty): exactly one match runs; zero or several is Ambiguous, so a bare 2+-device shell
   *     requires an explicit choice, matching `tool`/`snapshot` fail-loud behavior.
   */
  fun selectDevicesToRun(
    explicitDevices: List<String>,
    allDevices: Boolean,
    defaultDevice: String?,
    connectedSpecs: List<String>?,
    supportedPlatforms: Set<TrailblazeDevicePlatform>,
  ): TrailDeviceSelection {
    if (explicitDevices.isNotEmpty()) return TrailDeviceSelection.Resolved(explicitDevices)
    if (allDevices) {
      val connected = connectedSpecs.orEmpty()
      // No devices at all is a different failure from "several, pick one" — surface it as such so
      // the caller prints "no devices connected" rather than a "pick one of []" envelope.
      if (connected.isEmpty()) return TrailDeviceSelection.NoDevices
      val matches = connected.filterSupported(supportedPlatforms)
      return if (matches.isNotEmpty()) {
        TrailDeviceSelection.Resolved(matches)
      } else {
        TrailDeviceSelection.Ambiguous(connected)
      }
    }
    if (!defaultDevice.isNullOrBlank()) return TrailDeviceSelection.Resolved(listOf(defaultDevice))
    if (connectedSpecs.isNullOrEmpty()) return TrailDeviceSelection.Resolved(listOf(null))
    val matches = connectedSpecs.filterSupported(supportedPlatforms)
    return if (matches.size == 1) {
      TrailDeviceSelection.Resolved(listOf(matches.single()))
    } else {
      TrailDeviceSelection.Ambiguous(matches.ifEmpty { connectedSpecs })
    }
  }

  /** Keep the specs whose platform is in [supportedPlatforms]; empty set = "any" (keep all). */
  private fun List<String>.filterSupported(
    supportedPlatforms: Set<TrailblazeDevicePlatform>,
  ): List<String> =
    if (supportedPlatforms.isEmpty()) {
      this
    } else {
      filter { spec -> TrailblazeDevicePlatform.fromString(spec)?.let { it in supportedPlatforms } ?: false }
    }

  /**
   * The set of device platforms a trail declares support for — used to narrow which connected
   * device a multi-device run targets. Version-aware and never throws (a parse failure yields
   * the empty set, which [selectDevicesToRun] treats as "runs anywhere"):
   *
   * - v1: the `platform:` hint and/or the `driver:`'s platform.
   * - unified: the platform prefix of every declared classifier — `config.devices` keys plus
   *   every step's and the trailhead's `recording:` classifiers (so `android-tablet` and
   *   `ios-iphone` contribute `ANDROID` and `IOS`).
   *
   * [trailblazeYaml] must carry the caller's tool serializers (`createTrailblazeYaml()` on JVM
   * hosts) so recorded tools inside the trail decode rather than falling into the catch.
   */
  fun supportedPlatformsForTrail(trailblazeYaml: TrailblazeYaml, yaml: String): Set<TrailblazeDevicePlatform> = try {
    when (val doc = trailblazeYaml.decodeTrailDocument(yaml)) {
      is TrailDocument.V1 -> {
        val config = doc.items.filterIsInstance<TrailYamlItem.ConfigTrailItem>().firstOrNull()?.config
        buildSet {
          config?.platform?.let { TrailblazeDevicePlatform.fromString(it)?.let(::add) }
          config?.driver?.let { TrailblazeDriverType.fromString(it)?.platform?.let(::add) }
        }
      }
      // Fold the trail's declared classifiers (`config.devices` keys + every step/trailhead
      // `recording:` key) up to platforms — the same coverage the desktop Trails browser displays.
      is TrailDocument.Unified -> UnifiedTrailTargets.declaredPlatforms(doc.trail)
    }
  } catch (t: Throwable) {
    // Never fail device selection over an unparseable trail — treat as "declares no platforms"
    // (runs anywhere). Log so a genuinely malformed file isn't completely silent.
    Console.log("[device-select] couldn't parse trail to determine supported platforms: ${t.message}")
    emptySet()
  }
}
