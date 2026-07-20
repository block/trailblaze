package xyz.block.trailblaze.cli

import java.io.File
import java.io.FileInputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import maestro.DeviceInfo
import maestro.device.Platform
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.host.devices.TrailblazeHostDeviceClassifier
import xyz.block.trailblaze.ui.TrailblazeDeviceManager
import xyz.block.trailblaze.util.AndroidHostAdbUtils
import xyz.block.trailblaze.util.Console

/**
 * Resolves the full classifier list (e.g. `[ios, iphone]`) for a `--device` spec or a
 * discovered device, **always via the canonical [TrailblazeHostDeviceClassifier]**. The
 * classifier needs only [DeviceInfo.widthPixels] / [DeviceInfo.heightPixels]; this resolver
 * probes those dims with lightweight OS calls (`adb shell wm size` for Android,
 * `xcrun simctl io <UDID> screenshot` PNG-header parse for iOS) and feeds them in. No
 * parallel classifier logic — same code path the runtime uses at trail-run time, same
 * answer.
 *
 * Returns:
 *   - The canonical classifier list when dims can be probed (`[ios, iphone]`, `[android, tablet]`, …).
 *   - `[platform]` (platform-only) when dims can't be probed — degrades safely; the runtime
 *     classifier still has the final say once the trail actually starts.
 *   - `emptyList()` when the platform can't be parsed from the spec.
 *
 * All probes are best-effort and time-bounded; failures fall back to platform-only.
 */
object DeviceClassifierResolver {

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
      TrailblazeDevicePlatform.IOS -> probeIosDims(instanceId)
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
   * Process-lifetime cache keyed by (platform, instanceId). The simctl-screenshot probe
   * runs ~200 ms per call; even the `adb shell wm size` path costs a round-trip plus dadb
   * setup. Without this, every CLI invocation that touches a device pays the probe cost,
   * and the long-lived daemon would pay it again on each `device list` / plan-time
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

  @Volatile
  private var hostClassifierOverride: HostClassifierOverride? = null

  /**
   * Install (or remove, with `null`) the distribution-specific override. Called once at
   * startup from the distribution's entry point (e.g. `TrailblazeBlock.main`) — before any
   * CLI command runs, so every plan-time / `device list` lookup sees the override.
   *
   * Idempotent and thread-safe. Subsequent calls replace the override; tests that exercise
   * override behavior reset to null in their teardown.
   */
  fun installOverride(override: HostClassifierOverride?) {
    hostClassifierOverride = override
  }

  internal fun currentOverrideForTesting(): HostClassifierOverride? = hostClassifierOverride

  /**
   * Max simultaneous device probes when warming the cache from a batch. iOS `simctl
   * screenshot` and `adb shell wm size` are both per-device IO; running them concurrently
   * is safe (Apple's tooling parallelizes simctl by design; dadb keeps per-device
   * connections). The cap exists only so a freak scenario with many devices connected
   * can't spawn a thread per device. 5 is generous for realistic local setups (~1-2
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
   * (up to [maxParallelism]). After this returns, every entry in [devices] is in the cache
   * and subsequent [classifiersFor] / [resolveFromSpec] calls for those (platform,
   * instanceId) pairs return without IO.
   *
   * Probe results are stored via the same [classifierCache] used by the single-device API,
   * so callers don't have to thread anything through. Caller can intermix warmCache and
   * single-shot lookups freely.
   *
   * Failures inside a probe are caught and treated as "no dims" — the affected device gets
   * a platform-only classifier in the cache, same as the synchronous fallback. We never
   * propagate per-device exceptions out of the warm-up because a single bad probe (e.g. a
   * simulator that shut down between discovery and probe) should not abort the whole
   * batch.
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
      // simulator. Per-probe timeouts (`probeIosDims`/`probeAndroidDims`) are tighter
      // (5s/3s); this is a "something is very wrong" backstop.
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
    return classifyByProbing(platform, instanceId, dimensionsProbe)
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
  ): List<TrailblazeDeviceClassifier> = classifyByProbing(platform, instanceId, dimensionsProbe)

  private fun classifyByProbing(
    platform: TrailblazeDevicePlatform,
    instanceId: String,
    dimensionsProbe: DimensionsProbe,
  ): List<TrailblazeDeviceClassifier> {
    val cacheKey = platform to instanceId
    classifierCache[cacheKey]?.let { return it }
    val platformClassifier = platform.asTrailblazeDeviceClassifier()
    // Distribution-specific override wins when it recognizes the device. A throwing
    // override would otherwise propagate out of `warmCache` (skipping the per-future
    // result for that device) or out of `classifiersFor` (crashing the caller); catch
    // it, log, and fall through to the dim-based path so a buggy override degrades
    // gracefully instead of breaking `device list` / plan-time.
    val overrideResult = try {
      hostClassifierOverride?.classify(platform, instanceId)
    } catch (e: Exception) {
      Console.log("[DeviceClassifierResolver] override threw for $platform/$instanceId: ${e.message}")
      null
    }
    if (overrideResult != null) {
      // Definitive answer from a distribution that recognizes the device → cache it.
      classifierCache[cacheKey] = overrideResult
      return overrideResult
    }
    val driverType = canonicalDriverTypeFor(platform)
    val maestroPlatform = maestroPlatformFor(platform)
    if (driverType == null || maestroPlatform == null) {
      // Platforms without a host classifier path (WEB, DESKTOP). Deterministic — cache
      // the platform-only fallback so subsequent lookups skip the override-and-probe work.
      val deterministicFallback = listOf(platformClassifier)
      classifierCache[cacheKey] = deterministicFallback
      return deterministicFallback
    }
    // Dim probe is the only path where a *transient* failure (xcrun/adb hiccup,
    // simulator booting, device disconnected mid-probe) can leave us without a real
    // answer. Returning platform-only here is the right user-facing fallback, but we
    // intentionally do NOT cache it — otherwise one transient failure would lock the
    // device into a degraded classifier for the daemon's lifetime, silently breaking
    // `<platform>-<category>.trail.yaml` recording-pick (exactly the bug this resolver
    // is fixing). The next call retries the probe.
    val dims = dimensionsProbe.probe(platform, instanceId) ?: return listOf(platformClassifier)
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
    // failure above: return the best-effort classifier but do NOT cache it, or one hiccup would lock
    // the device into the wrong `android-phone` slot for the daemon's lifetime. The next call retries.
    if (platform == TrailblazeDevicePlatform.ANDROID && dims.densityDpi == null) {
      return classifiers
    }
    classifierCache[cacheKey] = classifiers
    return classifiers
  }

  /**
   * The classifier reads `trailblazeDriverType.platform` to branch iOS/Android. Any driver
   * for the given platform works — pick the canonical one so the classifier sees the right
   * platform without us having to thread an actual choice.
   */
  private fun canonicalDriverTypeFor(platform: TrailblazeDevicePlatform): TrailblazeDriverType? = when (platform) {
    TrailblazeDevicePlatform.IOS -> TrailblazeDriverType.IOS_HOST
    TrailblazeDevicePlatform.ANDROID -> TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION
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
   * Captures a screenshot via `xcrun simctl io <UDID> screenshot <tempfile> --type=PNG` and
   * reads the IHDR chunk for pixel dimensions. simctl's stdout form (`-`) doesn't actually
   * stream the bytes — it expects a real file path — so we write to a temp file and read
   * the first 24 bytes for the PNG header.
   *
   * PNG layout: 8-byte signature, then IHDR chunk (4-byte length, 4-byte type "IHDR",
   * 4-byte width, 4-byte height). Bytes 16..19 = width, 20..23 = height, both big-endian.
   *
   * Bounded with a hard process timeout so a stuck simulator doesn't stall `device list`.
   */
  private fun probeIosDims(instanceId: String): DeviceProbe? {
    val tempFile = try {
      // `deleteOnExit()` is a safety net for normal JVM shutdown — the `finally` block
      // deletes the file in the success/failure paths, but a process killed before
      // `finally` runs (e.g., a JVM exception in the parent code path before we enter
      // the try) would otherwise leak `/tmp/trailblaze-dims-probe-*.png` files. Doesn't
      // help on `kill -9` (shutdown hooks don't fire); a startup sweep would cover that,
      // but the per-file cost is small (~375KB) and `kill -9` is rare enough that the
      // hook alone is sufficient defense.
      File.createTempFile("trailblaze-dims-probe-$instanceId-", ".png").apply { deleteOnExit() }
    } catch (e: Exception) {
      Console.log("[DeviceClassifierResolver] ios temp file create failed for $instanceId: ${e.message}")
      return null
    }
    val process = try {
      // Redirect both stdout and stderr to DISCARD. We never read either (the screenshot
      // goes to `tempFile`), and the previous `redirectErrorStream(true)` left the merged
      // pipe undrained — if simctl ever emitted enough output, the child would block on
      // pipe backpressure and we'd hit the 5s timeout for no reason.
      ProcessBuilder("xcrun", "simctl", "io", instanceId, "screenshot", tempFile.absolutePath, "--type=PNG")
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start()
    } catch (e: Exception) {
      Console.log("[DeviceClassifierResolver] ios screenshot launch failed for $instanceId: ${e.message}")
      tempFile.delete()
      return null
    }
    return try {
      val finished = process.waitFor(5, TimeUnit.SECONDS)
      if (!finished) {
        Console.log("[DeviceClassifierResolver] ios screenshot timed out for $instanceId")
        return null
      }
      if (process.exitValue() != 0) {
        Console.log("[DeviceClassifierResolver] ios screenshot exit=${process.exitValue()} for $instanceId")
        return null
      }
      readPngDimensions(tempFile)
    } catch (e: Exception) {
      Console.log("[DeviceClassifierResolver] ios dims probe failed for $instanceId: ${e.message}")
      null
    } finally {
      // Always reap the child + delete the temp file on every exit path — timeout, exit
      // code != 0, exception, AND the `warmCache` `shutdownNow()` interrupt path (which
      // would otherwise interrupt `process.waitFor`, throw `InterruptedException`, and
      // leave the xcrun subprocess running in the background).
      if (process.isAlive) process.destroyForcibly()
      tempFile.delete()
    }
  }

  internal fun readPngDimensions(file: File): DeviceProbe? {
    if (!file.exists() || file.length() < 24) return null
    return FileInputStream(file).use { stream ->
      val header = ByteArray(24)
      var read = 0
      while (read < header.size) {
        val n = stream.read(header, read, header.size - read)
        if (n < 0) break
        read += n
      }
      if (read < 24) return@use null
      parsePngHeader(header)
    }
  }

  /**
   * Pure PNG-header parse: validates the signature AND the IHDR chunk-type field, then
   * extracts width/height. Extracted out of [readPngDimensions] so unit tests can build a
   * 24-byte header in memory and pin the offset/validation contract without a real PNG file.
   */
  internal fun parsePngHeader(header: ByteArray): DeviceProbe? {
    if (header.size < 24) return null
    // PNG signature: 89 50 4E 47 0D 0A 1A 0A.
    val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    for (i in signature.indices) if (header[i] != signature[i]) return null
    // After the 8-byte signature: 4-byte chunk length, then 4-byte chunk type. For a valid
    // PNG, the first chunk must be IHDR (`0x49484452`). Without this check, a non-PNG file
    // that happens to share the 8-byte signature would parse to garbage width/height.
    if (readBigEndianInt(header, 12) != 0x49484452) return null
    val width = readBigEndianInt(header, 16)
    val height = readBigEndianInt(header, 20)
    if (width <= 0 || height <= 0) return null
    return DeviceProbe(widthPixels = width, heightPixels = height)
  }

  private fun readBigEndianInt(bytes: ByteArray, offset: Int): Int =
    ((bytes[offset].toInt() and 0xFF) shl 24) or
      ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
      ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
      (bytes[offset + 3].toInt() and 0xFF)
}
