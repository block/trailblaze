package xyz.block.trailblaze.cli

import xyz.block.trailblaze.host.lease.BorrowedDeviceClassifiers
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import maestro.DeviceInfo
import maestro.device.Platform
import xyz.block.trailblaze.devices.DeviceClassifierKey
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.host.devices.TrailblazeHostDeviceClassifier
import xyz.block.trailblaze.host.ios.IosSimulatorScreenSize
import xyz.block.trailblaze.ui.TrailblazeDeviceManager
import xyz.block.trailblaze.util.AndroidHostAdbUtils
import xyz.block.trailblaze.util.Console

/**
 * Resolves the full classifier list (e.g. `[ios, iphone]`) for a `--device` spec or a
 * discovered device, **always via the canonical [TrailblazeHostDeviceClassifier]**. The
 * classifier needs only [DeviceInfo.widthPixels] / [DeviceInfo.heightPixels]; this resolver
 * probes those dims with lightweight OS calls (`adb shell wm size` for Android; for iOS, the
 * simulator's screen size from its device type on disk) and feeds them in. No
 * parallel classifier logic — same code path the runtime uses at trail-run time, same
 * answer.
 *
 * Returns:
 *   - The canonical classifier list when dims can be probed (`[ios, iphone]`, `[android, tablet]`, …).
 *   - `[platform]` (platform-only) when dims can't be probed — degrades safely; the runtime
 *     classifier still has the final say once the trail actually starts.
 *   - `emptyList()` when the platform can't be parsed from the spec.
 *
 * All probes are best-effort and time-bounded; failures fall back to platform-only. When a caller
 * needs to know whether an answer is a real classification or a best-effort guess, ask
 * [classificationFor] instead of [classifiersFor] — see [Classification].
 */
object DeviceClassifierResolver {

  /** Parses a CI/catalog device key into the classifiers used by unified trail recordings. */
  fun parseClassifierKey(key: String): List<TrailblazeDeviceClassifier> = DeviceClassifierKey.parse(key)

  /** See [DeviceClassifierKey.refine]. */
  fun resolveOverride(
    detected: List<TrailblazeDeviceClassifier>,
    requested: List<TrailblazeDeviceClassifier>,
  ): List<TrailblazeDeviceClassifier> = DeviceClassifierKey.refine(detected, requested)

  /** Behavior selection may only trust a measured shape; a failed probe contributes its platform. */
  internal fun trustedForBehavior(classification: Classification): List<TrailblazeDeviceClassifier> =
    if (classification.definitive) classification.classifiers else classification.classifiers.take(1)

  /**
   * Tuple of (instanceId, pixel-dimensions). Discovery returns `(udid, "iPhone 17 Pro")`
   * pairs today; we wrap them in this richer type only inside the resolver so the existing
   * discovery surface doesn't have to change. Tests inject this directly to skip the OS probe.
   */
  data class DeviceProbe(
    val widthPixels: Int,
    val heightPixels: Int,
    // Android display density in dpi (null for iOS or when it couldn't be probed). Feeds the
    // density-independent smallestWidthDp tablet rule in [TrailblazeHostDeviceClassifier].
    val densityDpi: Int? = null,
  )

  /** Function shape `(platform, instanceId) -> probed dims or null`. Tests inject a stub. */
  fun interface DimensionsProbe {
    fun probe(platform: TrailblazeDevicePlatform, instanceId: String): DeviceProbe?
  }

  /**
   * A classifier lookup's outcome: the classifiers, plus whether they are a *definitive*
   * classification of this device.
   *
   * `definitive = false` means the probe itself came back incomplete, so the classifiers are a
   * heuristic guess rather than a measurement — the dim probe failed (platform-only), or an Android
   * classification was computed without density (the raw-pixel heuristic misreads a 1920x1080
   * @160dpi tablet as a phone).
   *
   * A caller that only needs a *label* — `device list`, plan-time filename lookup — can use
   * [classifiers] as-is. A caller whose answer *selects behavior* should degrade to platform-only
   * when this is false: closest-wins recording-leg lowering will happily pick the `android-phone:`
   * leg from a guessed `phone`, and running the wrong recorded steps is worse than falling back to
   * the generic `android:` leg.
   *
   * Deliberately NOT the same question as "may the resolver memoize this" — see [ProbeOutcome].
   */
  data class Classification(
    val classifiers: List<TrailblazeDeviceClassifier>,
    val definitive: Boolean,
  )

  /**
   * Internal result of one classification pass: a [Classification] plus whether it may be cached.
   *
   * These are two different questions and only one branch separates them, but that branch matters.
   * "Don't cache this, an installed override may claim the device once its shell probe is ready" is
   * about the answer possibly being *superseded*; "don't trust this, the probe was incomplete" is
   * about the answer being a *guess*. A declined override with a fully successful `wm size` +
   * `wm density` probe is the first without being the second — the dim classification measured
   * everything it needed.
   *
   * Collapsing the two would silently strip the sub-category from nearly every device on any
   * distribution that installs an override, because an override recognizes only its own hardware and
   * declines everything else — every stock emulator and phone, and typically every non-Android
   * platform. A measured `[android, phone]` would come back as bare `[android]`: worse than the
   * value it replaced, and fatal for a recording keyed only by sub-category. Both flags are still
   * decided in one place ([computeClassification]), one `ProbeOutcome` per branch, so they can't
   * drift.
   */
  private data class ProbeOutcome(
    val classification: Classification,
    val cacheable: Boolean,
  )

  /**
   * Function shape `(platform) -> list of (instanceId, description)`. Used to resolve a
   * bare instance id (no `<platform>/` prefix) by searching iOS + Android device lists.
   * Tests inject a stub so the cross-platform search branches can be exercised without
   * depending on whatever the dev machine has plugged in.
   */
  fun interface DeviceListLookup {
    fun listFor(platform: TrailblazeDevicePlatform): List<Pair<String, String>>
  }

  private val defaultProbe = DimensionsProbe { platform, instanceId ->
    when (platform) {
      TrailblazeDevicePlatform.ANDROID -> probeAndroidDims(instanceId)
      TrailblazeDevicePlatform.IOS -> probeIosDimsFromDisk(instanceId)
      else -> null
    }
  }

  private val defaultDeviceListLookup = DeviceListLookup { platform ->
    when (platform) {
      TrailblazeDevicePlatform.IOS -> TrailblazeDeviceManager.listBootedIosSimulators()
      TrailblazeDevicePlatform.ANDROID -> TrailblazeDeviceManager.listConnectedAdbDevices()
      else -> emptyList()
    }
  }

  /**
   * Process-lifetime cache keyed by (platform, instanceId). Every probe spawns processes
   * (`plutil` on iOS; `adb shell wm size` plus dadb setup on Android). Without this, every CLI
   * invocation that touches a device pays the probe cost, and the long-lived daemon would pay it again on each `device list` / plan-time
   * resolution. We never invalidate inside a process: simulator dims don't change
   * meaningfully between probes (the phone-vs-tablet branch keys on `min(w, h)`, which is
   * rotation-stable), and the daemon's lifecycle already restarts on host-side code
   * changes. Tests use [resetCacheForTesting] so injected stubs aren't shadowed by a stale
   * cached value from a previous test.
   */
  private val classifierCache = ConcurrentHashMap<Pair<TrailblazeDevicePlatform, String>, List<TrailblazeDeviceClassifier>>()

  internal fun resetCacheForTesting() {
    classifierCache.clear()
  }

  /**
   * Distribution-specific classifier override consulted before the dim-based fallback.
   *
   * A distribution can install an override at startup to recognize platform-specific
   * hardware that screen-dimension probing alone can't identify (custom manufacturer,
   * specific device-model identifiers, etc.) and produce a multi-segment classifier list
   * that matches whatever the on-device classifier writes into session logs — so CLI
   * plan-time and `device list` use the same filename convention as the recording save
   * path.
   *
   * Returning `null` from the override means "I don't recognize this device" — the resolver
   * then falls through to the canonical [TrailblazeHostDeviceClassifier] with screen-dim
   * probing. Distributions that don't install an override stay on the dim-based path.
   */
  fun interface HostClassifierOverride {
    fun classify(platform: TrailblazeDevicePlatform, instanceId: String): List<TrailblazeDeviceClassifier>?
  }

  /**
   * An installed override and the platforms it can recognize hardware on (null means every
   * platform). One holder, so a lookup never pairs one install's override with another's scope.
   */
  private class InstalledOverride(
    val override: HostClassifierOverride,
    val platforms: Set<TrailblazeDevicePlatform>?,
  )

  @Volatile
  private var installedOverride: InstalledOverride? = null

  /**
   * Install (or remove, with `null`) the distribution-specific override. Called once at
   * startup from the distribution's entry point (e.g. `TrailblazeBlock.main`) — before any
   * CLI command runs, so every plan-time / `device list` lookup sees the override.
   *
   * Idempotent and thread-safe. Subsequent calls replace the override and drop every cached
   * answer, since each was decided under the previous one; tests that exercise override behavior
   * reset to null in their teardown.
   */
  fun installOverride(override: HostClassifierOverride?) {
    installedOverride = override?.let { InstalledOverride(it, platforms = null) }
    classifierCache.clear()
  }

  /**
   * [installOverride] for an override that only recognizes hardware on [platforms]. Devices on any
   * other platform skip it and get a cached dimension-based answer.
   */
  fun installOverride(platforms: Set<TrailblazeDevicePlatform>, override: HostClassifierOverride) {
    installedOverride = InstalledOverride(override, platforms.toSet())
    classifierCache.clear()
  }

  internal fun currentOverrideForTesting(): HostClassifierOverride? = installedOverride?.override

  /**
   * Max simultaneous device probes when warming the cache from a batch. iOS `plutil` reads
   * and `adb shell wm size` are both per-device IO; running them concurrently is safe (dadb
   * keeps per-device connections). The cap exists only so a freak scenario with many devices
   * connected can't spawn a thread per device. 5 is generous for realistic local setups (~1-2
   * devices) and bounded for outliers.
   */
  private const val DEFAULT_PROBE_PARALLELISM = 5

  /**
   * Outer bound for the [warmCache] batch wait — a safety net beyond the tighter per-probe
   * timeouts (3s Android, 5s iOS). If this ever fires the per-probe layer has already
   * failed to bound itself, so this is a "something is very wrong" guard rather than a
   * latency budget. Named const for consistency with [AndroidHostAdbUtils]'s timeout
   * pattern and so future tuning is a single-point edit.
   */
  private const val WARM_CACHE_OUTER_TIMEOUT_MS = 30_000L

  /**
   * Warms the classifier cache for [devices] by probing any uncached entries concurrently
   * (up to [maxParallelism]). After this returns, every entry that reached a *definitive*
   * classification is in the cache, and subsequent [classifiersFor] / [resolveFromSpec] calls
   * for those (platform, instanceId) pairs return without IO.
   *
   * Non-cacheable results are deliberately NOT cached, so a warmed device can still re-probe
   * on the next lookup — [computeClassification] leaves the cache untouched for a transient probe
   * failure (platform-only fallback, missing Android density) AND for a device an installed
   * distribution override declined (indistinguishable from a raced/blank probe; the latter answer
   * is still definitive, just not permanent — see [ProbeOutcome]). This is the same self-healing property the
   * single-device API has: the cost is a re-probe on the next call, the payoff is that one
   * transient miss can't lock a device into a degraded classifier for the daemon's lifetime.
   *
   * Probe results are stored via the same [classifierCache] used by the single-device API,
   * so callers don't have to thread anything through. Caller can intermix warmCache and
   * single-shot lookups freely.
   *
   * Failures inside a probe are caught and treated as "no dims" — the affected device falls
   * back to a platform-only classifier for this call (uncached, per above). We never propagate
   * per-device exceptions out of the warm-up because a single bad probe (e.g. a simulator that
   * shut down between discovery and probe) should not abort the whole batch.
   */
  fun warmCache(
    devices: List<Pair<TrailblazeDevicePlatform, String>>,
    maxParallelism: Int = DEFAULT_PROBE_PARALLELISM,
    dimensionsProbe: DimensionsProbe = defaultProbe,
  ) {
    // Skip the work entirely when there's nothing to do — keeps the no-op `device list`
    // with zero devices from doing any scheduling work.
    val toProbe = devices.distinct().filter { it !in classifierCache.keys }
    if (toProbe.isEmpty()) return
    // Use the shared ForkJoinPool with a Semaphore-bound concurrency cap instead of
    // creating a dedicated FixedThreadPool per call. This matches the pattern used by
    // `TrailblazeDeviceManager.loadDevicesSuspendImpl` (CompletableFuture.supplyAsync
    // against the default pool) and eliminates pool create/destroy churn when a
    // long-lived daemon services many `device list` invocations. `coerceAtLeast(1)`
    // defends against a caller passing 0 or negative — Semaphore(0) would deadlock
    // every probe, so the clamp is load-bearing for defensive correctness, not just
    // for the existing `newFixedThreadPool(0)` IllegalArgumentException.
    val concurrencyBound = Semaphore(maxParallelism.coerceAtLeast(1))
    try {
      val futures = toProbe.map { (platform, instanceId) ->
        CompletableFuture.runAsync(
          {
            concurrencyBound.acquire()
            try {
              classifyByProbing(platform, instanceId, dimensionsProbe)
            } finally {
              concurrencyBound.release()
            }
          },
          ForkJoinPool.commonPool(),
        )
      }
      // Bound the overall wait so the daemon's `device list` doesn't hang on a stuck
      // simulator. Per-probe timeouts are tighter (Android 3s; iOS two 3s `plutil` reads plus,
      // once per process, a 5s `simctl list devicetypes`); this is a "something is very wrong" backstop.
      CompletableFuture.allOf(*futures.toTypedArray()).get(WARM_CACHE_OUTER_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    } catch (e: Exception) {
      Console.log("[DeviceClassifierResolver] warmCache: ${e.message}")
    }
  }

  fun resolveFromSpec(
    deviceSpec: String?,
    dimensionsProbe: DimensionsProbe = defaultProbe,
    deviceListLookup: DeviceListLookup = defaultDeviceListLookup,
  ): List<TrailblazeDeviceClassifier> {
    if (deviceSpec.isNullOrBlank()) return emptyList()
    val parts = deviceSpec.split("/", limit = 2)
    val specPlatform = TrailblazeDevicePlatform.fromString(parts[0])
    val specInstanceId =
      if (specPlatform != null) parts.getOrNull(1)?.takeIf { it.isNotEmpty() } else deviceSpec

    // Resolve which platform we're looking at. Either the spec named it explicitly
    // (`ios/...`) or we have to guess from a raw instance id by checking both OS lists.
    val (platform, instanceId) = when {
      specPlatform != null && specInstanceId == null -> return listOf(specPlatform.asTrailblazeDeviceClassifier())
      specPlatform != null -> specPlatform to specInstanceId!!
      specInstanceId != null -> findPlatformForInstanceId(specInstanceId, deviceListLookup) ?: return emptyList()
      else -> return emptyList()
    }
    return classifyByProbing(platform, instanceId, dimensionsProbe).classifiers
  }

  /**
   * Resolve the classifier list for a `--driver` value when no `--device` spec is available.
   * A driver names its platform ([TrailblazeDriverType.platform]), so `ANDROID_ONDEVICE_*` lowers
   * to `[android]` and `IOS_HOST` to `[ios]`. Used as a pre-flight fallback for per-classifier
   * `skip:`/`tags:` resolution on a driver-pinned, device-less run — so an `android`-only skip
   * halts an android run but not an `IOS_HOST` one, instead of the device-agnostic any-skip
   * fallback. Returns the platform-only classifier (no phone/tablet category — that needs a real
   * device to probe); `emptyList()` when [driverSpec] is null/blank or names no known
   * [TrailblazeDriverType].
   */
  fun fromDriver(driverSpec: String?): List<TrailblazeDeviceClassifier> {
    if (driverSpec.isNullOrBlank()) return emptyList()
    val driver = runCatching { TrailblazeDriverType.valueOf(driverSpec) }.getOrNull() ?: return emptyList()
    return listOf(driver.platform.asTrailblazeDeviceClassifier())
  }

  /**
   * Direct entry point for callers that already hold a [TrailblazeDevicePlatform] + instance ID
   * pair (e.g. `device list` walking the discovered device summaries). Skips the spec parse
   * and goes straight to the canonical classifier.
   */
  fun classifiersFor(
    platform: TrailblazeDevicePlatform,
    instanceId: String,
    dimensionsProbe: DimensionsProbe = defaultProbe,
  ): List<TrailblazeDeviceClassifier> = classifyByProbing(platform, instanceId, dimensionsProbe).classifiers

  /**
   * Same lookup as [classifiersFor], but also reports whether the answer is definitive (see
   * [Classification]). Use this when a non-definitive classifier would select the wrong *behavior*
   * rather than just print a vague label.
   */
  fun classificationFor(
    platform: TrailblazeDevicePlatform,
    instanceId: String,
    dimensionsProbe: DimensionsProbe = defaultProbe,
  ): Classification = classifyByProbing(platform, instanceId, dimensionsProbe)

  private fun classifyByProbing(
    platform: TrailblazeDevicePlatform,
    instanceId: String,
    dimensionsProbe: DimensionsProbe,
  ): Classification {
    val cacheKey = platform to instanceId
    // Only a cacheable outcome is ever written (below), and every cacheable branch is also
    // definitive, so a hit is definitive by construction.
    classifierCache[cacheKey]?.let { return Classification(it, definitive = true) }
    val outcome = computeClassification(platform, instanceId, dimensionsProbe)
    if (outcome.cacheable) {
      classifierCache[cacheKey] = outcome.classification.classifiers
    }
    return outcome.classification
  }

  private fun computeClassification(
    platform: TrailblazeDevicePlatform,
    instanceId: String,
    dimensionsProbe: DimensionsProbe,
  ): ProbeOutcome {
    val platformClassifier = platform.asTrailblazeDeviceClassifier()
    // A device borrowed from another machine can't be probed from here; its lender measured it.
    BorrowedDeviceClassifiers.lookup(platform, instanceId)?.let { measured ->
      return if (measured.isNotEmpty()) {
        ProbeOutcome(Classification(measured, definitive = true), cacheable = true)
      } else {
        ProbeOutcome(Classification(listOf(platformClassifier), definitive = false), cacheable = false)
      }
    }
    // Distribution-specific override wins when it recognizes the device. A throwing
    // override would otherwise propagate out of `warmCache` (skipping the per-future
    // result for that device) or out of `classifiersFor` (crashing the caller); catch
    // it, log, and fall through to the dim-based path so a buggy override degrades
    // gracefully instead of breaking `device list` / plan-time.
    // An override is only consulted on the platforms it can recognize hardware on. Elsewhere it would
    // decline every time, and a decline is never cached (below), so the dimension probe would
    // re-run on every call.
    val applicableOverride = installedOverride
      ?.takeIf { it.platforms?.contains(platform) ?: true }
      ?.override
    val overrideResult = try {
      applicableOverride?.classify(platform, instanceId)
    } catch (e: Exception) {
      Console.log("[DeviceClassifierResolver] override threw for $platform/$instanceId: ${e.message}")
      null
    }
    if (overrideResult != null) {
      // Definitive answer from a distribution that recognizes the device.
      return ProbeOutcome(Classification(overrideResult, definitive = true), cacheable = true)
    }
    val driverType = canonicalDriverTypeFor(platform)
    val maestroPlatform = maestroPlatformFor(platform)
    if (driverType == null || maestroPlatform == null) {
      // Platforms without a host classifier path (WEB, DESKTOP). Deterministic — cache it so
      // subsequent lookups skip the override-and-probe work.
      return ProbeOutcome(Classification(listOf(platformClassifier), definitive = true), cacheable = true)
    }
    // Dim probe is the only path where a *transient* failure (xcrun/adb hiccup,
    // simulator booting, device disconnected mid-probe) can leave us without a real
    // answer. Returning platform-only here is the right user-facing fallback, but it is NOT
    // definitive — so it isn't cached (otherwise one transient failure would lock the device into
    // a degraded classifier for the daemon's lifetime, silently breaking
    // `<platform>-<category>.trail.yaml` recording-pick, exactly the bug this resolver is fixing)
    // and callers that select behavior on the answer can see it's a guess. The next call retries
    // the probe.
    val dims = dimensionsProbe.probe(platform, instanceId)
      ?: return ProbeOutcome(Classification(listOf(platformClassifier), definitive = false), cacheable = false)
    val classifiers = TrailblazeHostDeviceClassifier(
      trailblazeDriverType = driverType,
      maestroDeviceInfoProvider = {
        DeviceInfo(
          platform = maestroPlatform,
          widthPixels = dims.widthPixels,
          heightPixels = dims.heightPixels,
          widthGrid = dims.widthPixels,
          heightGrid = dims.heightPixels,
        )
      },
      androidDensityDpi = dims.densityDpi,
    ).getDeviceClassifiers()
    // An Android classification computed WITHOUT density fell back to the raw-pixel heuristic, which
    // misreads a low-density tablet (1920x1080 @160dpi) as a phone. A missing density here is a
    // *transient* `wm density` failure (timeout / malformed output), so treat it like the dim-probe
    // failure above: return the best-effort classifier as NON-definitive. It isn't cached (one
    // hiccup would otherwise lock the device into the wrong `android-phone` slot for the daemon's
    // lifetime; the next call retries), and a caller that lowers recording legs off this answer can
    // fall back to platform-only rather than confidently running a phone recording on a tablet.
    if (platform == TrailblazeDevicePlatform.ANDROID && dims.densityDpi == null) {
      return ProbeOutcome(Classification(classifiers, definitive = false), cacheable = false)
    }
    // A distribution override is installed but declined this probe. That is indistinguishable from a
    // *transient* miss: an override that recognizes hardware via a `getprop`/shell probe declines
    // whenever that probe comes back blank — which happens not only for a device the override
    // genuinely doesn't recognize, but also when the shell isn't ready yet (right after boot, or when
    // the device-connect races the probe). It returns the same "I don't recognize this" (null) in
    // both cases. Caching the dim-based fallback here would lock a device the override *would* have
    // claimed into the wrong `<platform>-<category>` slot for the daemon's entire lifetime, poisoning
    // every later run's classifier lookup — exactly the failure the transient guards above avoid. So
    // when an override is present but declined, skip the cache write and let the next call
    // re-consult the override once the probe is reliable.
    //
    // The dim classification itself is still DEFINITIVE here: `wm size` and `wm density` both
    // succeeded, so nothing was guessed. Only its permanence is in question, which is exactly the
    // distinction [ProbeOutcome] draws — marking it non-definitive would strip the sub-category
    // from every device an installed override doesn't claim, which for an override that recognizes
    // only its own hardware is nearly all of them, handing back a bare platform in place of a real
    // measurement.
    if (applicableOverride != null) {
      return ProbeOutcome(Classification(classifiers, definitive = true), cacheable = false)
    }
    // No override installed: the dim answer is the final word.
    return ProbeOutcome(Classification(classifiers, definitive = true), cacheable = true)
  }

  /**
   * The classifier reads `trailblazeDriverType.platform` to branch iOS/Android. Any driver
   * for the given platform works — pick the canonical one so the classifier sees the right
   * platform without us having to thread an actual choice.
   */
  private fun canonicalDriverTypeFor(platform: TrailblazeDevicePlatform): TrailblazeDriverType? = when (platform) {
    TrailblazeDevicePlatform.IOS -> TrailblazeDriverType.IOS_HOST
    TrailblazeDevicePlatform.ANDROID -> TrailblazeDriverType.DEFAULT_ANDROID
    else -> null
  }

  private fun maestroPlatformFor(platform: TrailblazeDevicePlatform): Platform? = when (platform) {
    TrailblazeDevicePlatform.IOS -> Platform.IOS
    TrailblazeDevicePlatform.ANDROID -> Platform.ANDROID
    else -> null
  }

  /**
   * Cross-platform lookup for bare instance IDs (no `<platform>/` prefix). Searches iOS
   * first, then Android — if a UDID happens to appear in both lists (improbable but not
   * forbidden), iOS wins; document the deterministic order here so a future caller doesn't
   * assume "best match" semantics.
   *
   * Match policy mirrors `CliRunDeviceResolver`'s explicit-id matching: exact equality first, then
   * fall back to `.contains()` so partial UDID prefixes still resolve. The substring match
   * is by design — too short an `instanceId` could match an unintended device, but the
   * resolver only feeds plan-time file lookup (a wrong match still falls back to
   * `blaze.yaml` gracefully) so the user-friendliness wins over strictness.
   *
   * [DeviceListLookup] is injectable so tests can stub the iOS/Android device lists
   * without depending on whatever the dev machine has plugged in.
   */
  internal fun findPlatformForInstanceId(
    instanceId: String,
    deviceListLookup: DeviceListLookup = defaultDeviceListLookup,
  ): Pair<TrailblazeDevicePlatform, String>? {
    val iosMatch = deviceListLookup.listFor(TrailblazeDevicePlatform.IOS)
      .firstOrNull { it.first == instanceId || it.first.contains(instanceId) }
    if (iosMatch != null) return TrailblazeDevicePlatform.IOS to iosMatch.first
    val androidMatch = deviceListLookup.listFor(TrailblazeDevicePlatform.ANDROID)
      .firstOrNull { it.first == instanceId || it.first.contains(instanceId) }
    if (androidMatch != null) return TrailblazeDevicePlatform.ANDROID to androidMatch.first
    return null
  }

  /**
   * `adb shell wm size` returns either "Physical size: 1080x2400" or
   * "Override size: 1080x2400" (when the user has set a scaling override), or both
   * lines back-to-back. We prefer the override when present — that's what the runtime
   * classifier sees — and fall back to the physical size otherwise. Bounded to a short
   * timeout so a wedged device doesn't stall `device list`.
   */
  private fun probeAndroidDims(instanceId: String): DeviceProbe? {
    return try {
      val deviceId = TrailblazeDeviceId(instanceId, TrailblazeDevicePlatform.ANDROID)
      val raw = AndroidHostAdbUtils.execAdbShellCommandWithTimeout(
        deviceId = deviceId,
        args = listOf("wm", "size"),
        timeoutMs = 3_000,
      )?.takeIf { it.isNotBlank() } ?: return null
      val size = parseWmSize(raw) ?: return null
      // Density decides phone-vs-tablet in a density-independent way (smallestWidthDp). A failed
      // density probe is non-fatal — the classifier falls back to the pixel heuristic.
      val densityRaw = AndroidHostAdbUtils.execAdbShellCommandWithTimeout(
        deviceId = deviceId,
        args = listOf("wm", "density"),
        timeoutMs = 3_000,
      )
      size.copy(densityDpi = densityRaw?.let { parseWmDensity(it) })
    } catch (e: Exception) {
      Console.log("[DeviceClassifierResolver] android dims probe failed for $instanceId: ${e.message}")
      null
    }
  }

  /**
   * Pure parse of `wm density` output. Returns the override density when present (last match wins,
   * mirroring [parseWmSize]) else the physical density. Extracted so unit tests can pin every shape
   * — physical-only, override-only, both, malformed — without an adb device.
   */
  internal fun parseWmDensity(raw: String): Int? {
    val regex = Regex("""(?:Override|Physical)\s+density:\s*(\d+)""")
    val match = regex.findAll(raw).lastOrNull() ?: regex.find(raw) ?: return null
    return match.groupValues[1].toIntOrNull()
  }

  /**
   * Pure parse of `wm size` output. Returns the override size when present (last match
   * wins, since the override line appears after the physical one in real output) else the
   * physical size. Extracted from [probeAndroidDims] so unit tests can pin every shape of
   * the output — physical-only, override-only, both, malformed — without an adb device.
   */
  internal fun parseWmSize(raw: String): DeviceProbe? {
    val regex = Regex("""(?:Override|Physical)\s+size:\s*(\d+)x(\d+)""")
    val match = regex.findAll(raw).lastOrNull() ?: regex.find(raw) ?: return null
    val width = match.groupValues[1].toIntOrNull() ?: return null
    val height = match.groupValues[2].toIntOrNull() ?: return null
    return DeviceProbe(widthPixels = width, heightPixels = height)
  }

  /**
   * The simulator's screen size from its device type's `capabilities.plist`: the same pixels a
   * screenshot has, without the screenshot. Null when the files can't be read (for example a
   * device outside the default device set), so the device classifies as platform-only.
   */
  private fun probeIosDimsFromDisk(instanceId: String): DeviceProbe? = try {
    IosSimulatorScreenSize.of(instanceId)?.let { DeviceProbe(widthPixels = it.width, heightPixels = it.height) }
  } catch (e: Exception) {
    Console.log("[DeviceClassifierResolver] ios screen size read failed for $instanceId: ${e.message}")
    null
  }
}
