package xyz.block.trailblaze.cli

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform

/**
 * Pins the resolver that converts a `--device` spec or a discovered device into the
 * multi-segment classifier list. The resolver delegates the actual classification to
 * [xyz.block.trailblaze.host.devices.TrailblazeHostDeviceClassifier] — the canonical
 * classifier the runtime uses at trail-run time. These tests inject a stub probe so they
 * don't depend on the dev machine's connected devices, but the classification logic itself
 * is the production code path.
 *
 * Threshold pinned by the canonical classifier: `shortest side >= 1536 px → tablet/iPad`.
 */
class DeviceClassifierResolverTest {

  @BeforeTest
  fun resetCacheBetweenTests() {
    // Each test injects its own stub probe; the process-lifetime cache otherwise lets one
    // test's classified device pin the result for any later test that reuses the same
    // (platform, instanceId) — making the cross-test order-dependent.
    DeviceClassifierResolver.resetCacheForTesting()
    // The override is process-global; tests that exercise it install one and rely on this
    // teardown to clear it so it doesn't leak into adjacent tests.
    DeviceClassifierResolver.installOverride(null)
  }

  /**
   * Builds a probe that returns canned (width, height) for a specific (platform, instanceId)
   * pair and `null` otherwise — letting tests cover both the happy probe path and the
   * platform-only fallback that fires when a probe fails.
   */
  private fun probeOf(
    map: Map<Pair<TrailblazeDevicePlatform, String>, DeviceClassifierResolver.DeviceProbe>,
  ) = DeviceClassifierResolver.DimensionsProbe { platform, instanceId ->
    map[platform to instanceId]
  }

  @Test
  fun `fromDriver lowers a driver to its platform classifier`() {
    // An Android on-device driver → [android]; an iOS host driver → [ios]. Platform-only —
    // phone/tablet needs a real device to probe, which a driver-only (device-less) run lacks.
    assertEquals(
      listOf("android"),
      DeviceClassifierResolver.fromDriver("ANDROID_ONDEVICE_ACCESSIBILITY").map { it.classifier },
    )
    assertEquals(
      listOf("ios"),
      DeviceClassifierResolver.fromDriver("IOS_HOST").map { it.classifier },
    )
  }

  @Test
  fun `fromDriver returns empty for null, blank, or unknown driver`() {
    // No --driver, or a garbage value, must not synthesize a classifier — the caller then falls
    // back to the device-agnostic any-skip behavior rather than a wrong per-platform verdict.
    assertTrue(DeviceClassifierResolver.fromDriver(null).isEmpty())
    assertTrue(DeviceClassifierResolver.fromDriver("").isEmpty())
    assertTrue(DeviceClassifierResolver.fromDriver("   ").isEmpty())
    assertTrue(DeviceClassifierResolver.fromDriver("NOT_A_REAL_DRIVER").isEmpty())
  }

  @Test
  fun `iOS spec with iPhone-shaped dims yields ios iphone`() {
    // iPhone 17 Pro is 1206x2622-ish; min side ~1206, well under 1536 → iphone per canonical.
    val udid = "D27D6F3F-AAAA-BBBB-CCCC-1234567890AB"
    val classifiers = DeviceClassifierResolver.resolveFromSpec(
      "ios/$udid",
      probeOf(mapOf((TrailblazeDevicePlatform.IOS to udid) to DeviceClassifierResolver.DeviceProbe(1206, 2622))),
    )
    assertEquals(listOf("ios", "iphone"), classifiers.map { it.classifier })
  }

  @Test
  fun `iOS spec with iPad-shaped dims yields ios ipad`() {
    // iPad Pro 12.9" is 2048x2732; min side 2048, above the 1536 tablet threshold.
    val udid = "EEEEFFFF-1111-2222-3333-AAAABBBBCCCC"
    val classifiers = DeviceClassifierResolver.resolveFromSpec(
      "ios/$udid",
      probeOf(mapOf((TrailblazeDevicePlatform.IOS to udid) to DeviceClassifierResolver.DeviceProbe(2048, 2732))),
    )
    assertEquals(listOf("ios", "ipad"), classifiers.map { it.classifier })
  }

  @Test
  fun `iOS spec with min-side exactly 1536 lands on ipad - the canonical boundary`() {
    // The canonical classifier uses `>=` for the 1536 boundary; pin that exact edge so a
    // future refactor that switches to `>` (or anywhere else) shows up as a test diff.
    val udid = "BOUNDARY-1111-2222-3333-444455556666"
    val classifiers = DeviceClassifierResolver.resolveFromSpec(
      "ios/$udid",
      probeOf(mapOf((TrailblazeDevicePlatform.IOS to udid) to DeviceClassifierResolver.DeviceProbe(1536, 2048))),
    )
    assertEquals(listOf("ios", "ipad"), classifiers.map { it.classifier })
  }

  @Test
  fun `platform-only iOS spec yields ios with no category - no probe needed`() {
    // No instance id → no probe → platform-only. This is the path tests of
    // expandTrailFiles take when they don't want any real device behavior bleeding in.
    val classifiers = DeviceClassifierResolver.resolveFromSpec(
      "ios",
      probeOf(emptyMap()),
    )
    assertEquals(listOf("ios"), classifiers.map { it.classifier })
  }

  @Test
  fun `iOS spec whose probe fails falls back to platform-only`() {
    // Probe returns null (e.g. xcrun unavailable, simulator shut down between discovery and
    // resolution). Without dims the canonical classifier has nothing to branch on; we
    // degrade to platform-only rather than fabricating a category.
    val classifiers = DeviceClassifierResolver.resolveFromSpec(
      "ios/D27D6F3F-AAAA-BBBB-CCCC-1234567890AB",
      probeOf(emptyMap()),
    )
    assertEquals(listOf("ios"), classifiers.map { it.classifier })
  }

  @Test
  fun `Android spec with phone-shaped dims yields android phone`() {
    val classifiers = DeviceClassifierResolver.resolveFromSpec(
      "android/emulator-5554",
      probeOf(mapOf((TrailblazeDevicePlatform.ANDROID to "emulator-5554") to DeviceClassifierResolver.DeviceProbe(1080, 2400))),
    )
    assertEquals(listOf("android", "phone"), classifiers.map { it.classifier })
  }

  @Test
  fun `Android spec with tablet-shaped dims yields android tablet`() {
    // Pixel Tablet is 1600x2560; min side 1600 ≥ 1536 → tablet per canonical.
    val classifiers = DeviceClassifierResolver.resolveFromSpec(
      "android/emulator-5556",
      probeOf(mapOf((TrailblazeDevicePlatform.ANDROID to "emulator-5556") to DeviceClassifierResolver.DeviceProbe(1600, 2560))),
    )
    assertEquals(listOf("android", "tablet"), classifiers.map { it.classifier })
  }

  @Test
  fun `null and blank specs return empty`() {
    assertEquals(emptyList(), DeviceClassifierResolver.resolveFromSpec(null, probeOf(emptyMap())))
    assertEquals(emptyList(), DeviceClassifierResolver.resolveFromSpec("", probeOf(emptyMap())))
    assertEquals(emptyList(), DeviceClassifierResolver.resolveFromSpec("   ", probeOf(emptyMap())))
  }

  @Test
  fun `classifiersFor goes straight to the canonical classifier with no spec parse`() {
    // The surface `device list` uses — caller already has (platform, instanceId) in hand.
    val classifiers = DeviceClassifierResolver.classifiersFor(
      platform = TrailblazeDevicePlatform.ANDROID,
      instanceId = "emulator-5554",
      dimensionsProbe = probeOf(mapOf((TrailblazeDevicePlatform.ANDROID to "emulator-5554") to DeviceClassifierResolver.DeviceProbe(1080, 2400))),
    )
    assertEquals(listOf("android", "phone"), classifiers.map { it.classifier })
  }

  @Test
  fun `classifiersFor with a failing probe degrades to platform-only`() {
    val classifiers = DeviceClassifierResolver.classifiersFor(
      platform = TrailblazeDevicePlatform.ANDROID,
      instanceId = "emulator-9999",
      dimensionsProbe = probeOf(emptyMap()),
    )
    assertEquals(listOf("android"), classifiers.map { it.classifier })
  }

  @Test
  fun `repeated lookups for the same device hit the cache - probe runs exactly once`() {
    // Without the cache, every plan-time resolution and every `device list` invocation
    // pays the simctl-screenshot / adb-shell cost. Pin that the resolver dedups the probe
    // by (platform, instanceId) so the daemon's long-lived lifecycle doesn't multiply that
    // cost across calls.
    val callCount = AtomicInteger(0)
    val countingProbe = DeviceClassifierResolver.DimensionsProbe { _, _ ->
      callCount.incrementAndGet()
      DeviceClassifierResolver.DeviceProbe(1206, 2622)
    }
    val first = DeviceClassifierResolver.classifiersFor(
      platform = TrailblazeDevicePlatform.IOS,
      instanceId = "CACHED-UDID-1111",
      dimensionsProbe = countingProbe,
    )
    val second = DeviceClassifierResolver.classifiersFor(
      platform = TrailblazeDevicePlatform.IOS,
      instanceId = "CACHED-UDID-1111",
      dimensionsProbe = countingProbe,
    )
    val third = DeviceClassifierResolver.resolveFromSpec(
      "ios/CACHED-UDID-1111",
      dimensionsProbe = countingProbe,
    )
    assertEquals(1, callCount.get(), "probe should run exactly once for repeated (platform, instanceId) lookups")
    assertEquals(listOf("ios", "iphone"), first.map { it.classifier })
    assertEquals(first, second)
    assertEquals(first, third)
  }

  @Test
  fun `cache key is per device - different instanceIds probe independently`() {
    // Two devices, one iPhone-sized and one iPad-sized — the cache must key on instanceId
    // so adding a second device doesn't poison the first device's entry.
    val callCount = AtomicInteger(0)
    val probe = DeviceClassifierResolver.DimensionsProbe { _, instanceId ->
      callCount.incrementAndGet()
      if (instanceId == "iphone-udid") DeviceClassifierResolver.DeviceProbe(1206, 2622)
      else DeviceClassifierResolver.DeviceProbe(2048, 2732)
    }
    val iphoneClassifiers = DeviceClassifierResolver.classifiersFor(
      TrailblazeDevicePlatform.IOS, "iphone-udid", probe,
    )
    val ipadClassifiers = DeviceClassifierResolver.classifiersFor(
      TrailblazeDevicePlatform.IOS, "ipad-udid", probe,
    )
    // Re-fetch — should serve from cache, not re-probe.
    val iphoneAgain = DeviceClassifierResolver.classifiersFor(
      TrailblazeDevicePlatform.IOS, "iphone-udid", probe,
    )
    assertEquals(listOf("ios", "iphone"), iphoneClassifiers.map { it.classifier })
    assertEquals(listOf("ios", "ipad"), ipadClassifiers.map { it.classifier })
    assertEquals(iphoneClassifiers, iphoneAgain)
    assertEquals(2, callCount.get(), "each device probes once; re-fetches hit the cache")
  }

  @Test
  fun `warmCache probes all devices in parallel and populates the cache`() {
    // Pin the batch contract: warmCache fans out probes concurrently, every (platform,
    // instanceId) lands in the cache, and subsequent single-shot lookups return instantly
    // without re-probing.
    val callCount = AtomicInteger(0)
    val probe = DeviceClassifierResolver.DimensionsProbe { platform, instanceId ->
      callCount.incrementAndGet()
      when (platform to instanceId) {
        TrailblazeDevicePlatform.IOS to "phone-udid" -> DeviceClassifierResolver.DeviceProbe(1206, 2622)
        TrailblazeDevicePlatform.IOS to "tablet-udid" -> DeviceClassifierResolver.DeviceProbe(2048, 2732)
        TrailblazeDevicePlatform.ANDROID to "emulator-5554" -> DeviceClassifierResolver.DeviceProbe(1080, 2400)
        else -> null
      }
    }
    val devices = listOf(
      TrailblazeDevicePlatform.IOS to "phone-udid",
      TrailblazeDevicePlatform.IOS to "tablet-udid",
      TrailblazeDevicePlatform.ANDROID to "emulator-5554",
    )
    DeviceClassifierResolver.warmCache(devices, dimensionsProbe = probe)
    // Read all three from cache — counter should not advance.
    val phone = DeviceClassifierResolver.classifiersFor(TrailblazeDevicePlatform.IOS, "phone-udid", probe)
    val tablet = DeviceClassifierResolver.classifiersFor(TrailblazeDevicePlatform.IOS, "tablet-udid", probe)
    val android = DeviceClassifierResolver.classifiersFor(TrailblazeDevicePlatform.ANDROID, "emulator-5554", probe)
    assertEquals(listOf("ios", "iphone"), phone.map { it.classifier })
    assertEquals(listOf("ios", "ipad"), tablet.map { it.classifier })
    assertEquals(listOf("android", "phone"), android.map { it.classifier })
    assertEquals(3, callCount.get(), "warmCache should probe each device exactly once")
  }

  @Test
  fun `warmCache skips devices already in the cache`() {
    // Pre-populate one device via the single-shot path, then warmCache a batch that
    // includes it — the pre-cached entry must not re-probe.
    val callCount = AtomicInteger(0)
    val probe = DeviceClassifierResolver.DimensionsProbe { _, _ ->
      callCount.incrementAndGet()
      DeviceClassifierResolver.DeviceProbe(1206, 2622)
    }
    DeviceClassifierResolver.classifiersFor(TrailblazeDevicePlatform.IOS, "udid-a", probe)
    assertEquals(1, callCount.get())
    DeviceClassifierResolver.warmCache(
      listOf(
        TrailblazeDevicePlatform.IOS to "udid-a", // already cached
        TrailblazeDevicePlatform.IOS to "udid-b", // new
      ),
      dimensionsProbe = probe,
    )
    assertEquals(2, callCount.get(), "warmCache should only probe uncached entries")
  }

  @Test
  fun `warmCache caps parallelism at maxParallelism`() {
    // Drive a probe that blocks until told to release; assert that at most
    // maxParallelism threads make it past the latch at the same time. The cap exists for
    // the freak-many-devices scenario Sam called out; this regression-guards it so a
    // future refactor can't accidentally remove the bound.
    val maxParallelism = 3
    val deviceCount = 8
    val concurrent = AtomicInteger(0)
    val peakConcurrent = AtomicInteger(0)
    val release = CountDownLatch(1)
    val started = CountDownLatch(maxParallelism)
    val probe = DeviceClassifierResolver.DimensionsProbe { _, _ ->
      val now = concurrent.incrementAndGet()
      peakConcurrent.updateAndGet { peak -> maxOf(peak, now) }
      started.countDown()
      // Wait for the test to release; this only fires once maxParallelism probes are
      // active simultaneously, proving the pool actually saturates at the cap.
      release.await(5, TimeUnit.SECONDS)
      concurrent.decrementAndGet()
      DeviceClassifierResolver.DeviceProbe(1206, 2622)
    }
    val devices = (1..deviceCount).map { TrailblazeDevicePlatform.IOS to "udid-$it" }
    // Drive warmCache on a background thread so we can synchronize with `started`.
    val worker = Thread {
      DeviceClassifierResolver.warmCache(devices, maxParallelism = maxParallelism, dimensionsProbe = probe)
    }
    worker.start()
    assertTrue(
      started.await(5, TimeUnit.SECONDS),
      "expected $maxParallelism probes to be in-flight simultaneously within 5s",
    )
    release.countDown()
    worker.join(15_000)
    // Explicit liveness check after the timed join — without it, a future regression
    // that makes `warmCache` block would leak a live thread and the test would still
    // appear to pass. The assertion turns that scenario into a deterministic failure.
    assertTrue(!worker.isAlive, "warmCache worker did not finish within 15s join window")
    assertEquals(maxParallelism, peakConcurrent.get(), "peak concurrency should equal the cap")
  }

  @Test
  fun `warmCache with empty list is a no-op`() {
    val callCount = AtomicInteger(0)
    val probe = DeviceClassifierResolver.DimensionsProbe { _, _ ->
      callCount.incrementAndGet()
      DeviceClassifierResolver.DeviceProbe(1206, 2622)
    }
    DeviceClassifierResolver.warmCache(emptyList(), dimensionsProbe = probe)
    assertEquals(0, callCount.get())
  }

  @Test
  fun `installed override wins over the dim-based fallback - no probe runs`() {
    // Distribution-side override is consulted before the dim probe and replaces the
    // dim-based answer entirely when it returns non-null. Generic classifier names below;
    // distributions plug in their own multi-segment naming.
    val probeCount = AtomicInteger(0)
    val probe = DeviceClassifierResolver.DimensionsProbe { _, _ ->
      probeCount.incrementAndGet()
      DeviceClassifierResolver.DeviceProbe(1080, 2400)  // would be android-phone
    }
    DeviceClassifierResolver.installOverride { platform, _ ->
      if (platform != TrailblazeDevicePlatform.ANDROID) null
      else listOf(TrailblazeDeviceClassifier("acme"), TrailblazeDeviceClassifier("widget"))
    }
    val classifiers = DeviceClassifierResolver.classifiersFor(
      TrailblazeDevicePlatform.ANDROID, "WIDGET-FAKE-SERIAL", probe,
    )
    assertEquals(listOf("acme", "widget"), classifiers.map { it.classifier })
    assertEquals(0, probeCount.get(), "override should short-circuit before the dim probe runs")
  }

  @Test
  fun `override returning null falls through to the dim-based fallback`() {
    // Override that doesn't recognize the device (e.g. consumer Android in Block dist) returns
    // null; resolver then falls through to TrailblazeHostDeviceClassifier with dim probing.
    val probe = DeviceClassifierResolver.DimensionsProbe { _, _ ->
      DeviceClassifierResolver.DeviceProbe(1080, 2400)
    }
    DeviceClassifierResolver.installOverride { _, _ -> null }
    val classifiers = DeviceClassifierResolver.classifiersFor(
      TrailblazeDevicePlatform.ANDROID, "consumer-pixel", probe,
    )
    assertEquals(listOf("android", "phone"), classifiers.map { it.classifier })
  }

  @Test
  fun `override result is cached - second lookup does not re-invoke the override`() {
    val overrideCount = AtomicInteger(0)
    DeviceClassifierResolver.installOverride { _, _ ->
      overrideCount.incrementAndGet()
      listOf(TrailblazeDeviceClassifier("acme"), TrailblazeDeviceClassifier("gizmo"))
    }
    val probe = DeviceClassifierResolver.DimensionsProbe { _, _ -> null }
    val first = DeviceClassifierResolver.classifiersFor(
      TrailblazeDevicePlatform.ANDROID, "custom-android-udid", probe,
    )
    val second = DeviceClassifierResolver.classifiersFor(
      TrailblazeDevicePlatform.ANDROID, "custom-android-udid", probe,
    )
    assertEquals(listOf("acme", "gizmo"), first.map { it.classifier })
    assertEquals(first, second)
    assertEquals(1, overrideCount.get(), "override should run exactly once per (platform, instanceId)")
  }

  @Test
  fun `web spec yields only web - canonical classifier has no category for browsers`() {
    // Web/Compose platforms hit the `else -> {}` arm in the canonical classifier, so it
    // emits only the platform classifier. The resolver's `canonicalDriverTypeFor` returns
    // null for web, so we short-circuit to platform-only without calling the classifier at
    // all — same observable result.
    val classifiers = DeviceClassifierResolver.resolveFromSpec(
      "web/checkout",
      probeOf(emptyMap()),
    )
    assertEquals(listOf("web"), classifiers.map { it.classifier })
  }

  @Test
  fun `transient probe failure is NOT cached - next call retries`() {
    // PR #3299 review (Codex P1 / Copilot): if a probe momentarily fails, the platform-only
    // fallback used to be cached for the daemon's lifetime, permanently losing phone/tablet
    // disambiguation for that device and breaking the very recording-pick this resolver is
    // supposed to fix. Pin that transient failures stay un-cached so a recovered device
    // resolves correctly on the next call.
    val callCount = AtomicInteger(0)
    val probe = DeviceClassifierResolver.DimensionsProbe { _, _ ->
      // First call: simulate failure (xcrun hiccup, sim booting, etc.). Subsequent calls:
      // return real dims. Without the fix, call 1 caches `[android]` and call 2 returns
      // that stale value without ever consulting the probe again.
      if (callCount.incrementAndGet() == 1) null
      else DeviceClassifierResolver.DeviceProbe(1080, 2400)
    }
    val first = DeviceClassifierResolver.classifiersFor(
      TrailblazeDevicePlatform.ANDROID, "transient-failure-udid", probe,
    )
    val second = DeviceClassifierResolver.classifiersFor(
      TrailblazeDevicePlatform.ANDROID, "transient-failure-udid", probe,
    )
    assertEquals(listOf("android"), first.map { it.classifier }, "first call falls back to platform-only")
    assertEquals(listOf("android", "phone"), second.map { it.classifier }, "second call retries and gets the real classifier")
    assertEquals(2, callCount.get(), "probe should be retried — the failure must not be cached")
  }

  @Test
  fun `deterministic non-probe platforms (web) ARE cached`() {
    // Counterpoint to the transient-failure test: WEB has no canonical driver/maestro
    // platform mapping, so the result is *deterministic* — caching it is correct. Pin that
    // the resolver distinguishes "transient probe failure" (don't cache) from "platform
    // has no host classifier path" (cache).
    val probeCount = AtomicInteger(0)
    val probe = DeviceClassifierResolver.DimensionsProbe { _, _ ->
      probeCount.incrementAndGet(); null
    }
    DeviceClassifierResolver.classifiersFor(TrailblazeDevicePlatform.WEB, "checkout", probe)
    DeviceClassifierResolver.classifiersFor(TrailblazeDevicePlatform.WEB, "checkout", probe)
    assertEquals(0, probeCount.get(), "WEB never hits the probe path; deterministic fallback should be cached")
  }

  @Test
  fun `override that throws is caught - resolver falls through to dim-based path`() {
    // PR #3299 lead-dev review: today's BlockHostDeviceClassifiersProvider doesn't throw,
    // but a future override could (e.g. an `IllegalStateException` on a stale executor
    // reference). The resolver must catch it and degrade gracefully — otherwise one bad
    // override breaks the whole `device list` or aborts a `warmCache` batch partway.
    DeviceClassifierResolver.installOverride { _, _ ->
      throw IllegalStateException("override blew up")
    }
    val probe = DeviceClassifierResolver.DimensionsProbe { _, _ ->
      DeviceClassifierResolver.DeviceProbe(1080, 2400)
    }
    val classifiers = DeviceClassifierResolver.classifiersFor(
      TrailblazeDevicePlatform.ANDROID, "any-udid", probe,
    )
    // Falls through to the dim-based classifier — same as if the override had returned null.
    assertEquals(listOf("android", "phone"), classifiers.map { it.classifier })
  }

  @Test
  fun `parseWmSize handles physical, override, both, and malformed inputs`() {
    // Physical only — common case for emulators without size override.
    assertEquals(
      DeviceClassifierResolver.DeviceProbe(1080, 2400),
      DeviceClassifierResolver.parseWmSize("Physical size: 1080x2400\n"),
    )
    // Override only — defensive: if a device ever reports just the override line.
    assertEquals(
      DeviceClassifierResolver.DeviceProbe(720, 1440),
      DeviceClassifierResolver.parseWmSize("Override size: 720x1440\n"),
    )
    // Both lines — runtime classifier sees the override, so the parser must too.
    assertEquals(
      DeviceClassifierResolver.DeviceProbe(720, 1440),
      DeviceClassifierResolver.parseWmSize("Physical size: 1080x2400\nOverride size: 720x1440\n"),
    )
    // Malformed / unrelated output.
    kotlin.test.assertNull(DeviceClassifierResolver.parseWmSize(""))
    kotlin.test.assertNull(DeviceClassifierResolver.parseWmSize("error: device offline\n"))
  }

  @Test
  fun `parsePngHeader validates signature plus IHDR chunk type`() {
    // Valid PNG header for a 1206x2622 iPhone screenshot.
    val valid = pngHeader(width = 1206, height = 2622)
    assertEquals(
      DeviceClassifierResolver.DeviceProbe(1206, 2622),
      DeviceClassifierResolver.parsePngHeader(valid),
    )
    // Wrong signature → null. Without this guard, the parser would happily read garbage
    // dims from any 24-byte buffer.
    val wrongSig = valid.copyOf().also { it[0] = 0x00 }
    kotlin.test.assertNull(DeviceClassifierResolver.parsePngHeader(wrongSig))
    // Right signature, but the chunk type isn't "IHDR" — pinned by PR review (lead-dev
    // finding #7). A non-PNG file that happens to share the 8-byte signature must not
    // parse to garbage.
    val wrongChunkType = valid.copyOf().also {
      it[12] = 0x00; it[13] = 0x00; it[14] = 0x00; it[15] = 0x00
    }
    kotlin.test.assertNull(DeviceClassifierResolver.parsePngHeader(wrongChunkType))
    // Header too short.
    kotlin.test.assertNull(DeviceClassifierResolver.parsePngHeader(ByteArray(10)))
    // Zero dimensions — defensive.
    val zeroWidth = pngHeader(width = 0, height = 100)
    kotlin.test.assertNull(DeviceClassifierResolver.parsePngHeader(zeroWidth))
  }

  /**
   * Builds a 24-byte buffer matching the leading bytes of a real PNG: 8-byte signature, the
   * IHDR chunk-length field (always 13 for IHDR but we don't validate it), the 4-byte
   * "IHDR" type, and the 4-byte big-endian width + height. The full chunk would include
   * bit depth/color type/CRC after this, but [DeviceClassifierResolver.parsePngHeader]
   * only reads the first 24 bytes.
   */
  private fun pngHeader(width: Int, height: Int): ByteArray {
    val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    val length = intArrayOf(0, 0, 0, 13).map { it.toByte() }.toByteArray()
    val ihdrType = byteArrayOf(0x49, 0x48, 0x44, 0x52)
    val widthBytes = byteArrayOf(
      (width ushr 24).toByte(), (width ushr 16).toByte(), (width ushr 8).toByte(), width.toByte(),
    )
    val heightBytes = byteArrayOf(
      (height ushr 24).toByte(), (height ushr 16).toByte(), (height ushr 8).toByte(), height.toByte(),
    )
    return signature + length + ihdrType + widthBytes + heightBytes
  }

  @Test
  fun `findPlatformForInstanceId matches iOS, Android, neither, and both with iOS-first determinism`() {
    // Pins the cross-platform lookup branches that `resolveFromSpec` falls back to when
    // the user passes a bare instance id (no `<platform>/` prefix) — the documented CLI
    // shorthand. Without these, a regression in the iOS-first ordering or the substring
    // match fallback wouldn't surface in CI.
    val iosOnly = DeviceClassifierResolver.DeviceListLookup { platform ->
      if (platform == TrailblazeDevicePlatform.IOS) listOf("ios-udid" to "iPhone 17 Pro") else emptyList()
    }
    val androidOnly = DeviceClassifierResolver.DeviceListLookup { platform ->
      if (platform == TrailblazeDevicePlatform.ANDROID) listOf("emulator-5554" to "Pixel_8") else emptyList()
    }
    val both = DeviceClassifierResolver.DeviceListLookup { platform ->
      when (platform) {
        TrailblazeDevicePlatform.IOS -> listOf("shared-id" to "iPhone")
        TrailblazeDevicePlatform.ANDROID -> listOf("shared-id" to "Pixel")
        else -> emptyList()
      }
    }
    val empty = DeviceClassifierResolver.DeviceListLookup { emptyList() }

    assertEquals(
      TrailblazeDevicePlatform.IOS to "ios-udid",
      DeviceClassifierResolver.findPlatformForInstanceId("ios-udid", iosOnly),
    )
    assertEquals(
      TrailblazeDevicePlatform.ANDROID to "emulator-5554",
      DeviceClassifierResolver.findPlatformForInstanceId("emulator-5554", androidOnly),
    )
    assertEquals(
      null,
      DeviceClassifierResolver.findPlatformForInstanceId("unknown-id", empty),
      "no match in either list returns null",
    )
    assertEquals(
      TrailblazeDevicePlatform.IOS to "shared-id",
      DeviceClassifierResolver.findPlatformForInstanceId("shared-id", both),
      "deterministic order: iOS-first wins when an id matches in both lists",
    )
    // Substring fallback: a UDID prefix should resolve to the full match.
    val prefixLookup = DeviceClassifierResolver.DeviceListLookup { platform ->
      if (platform == TrailblazeDevicePlatform.IOS) listOf("D27D6F3F-AAAA-BBBB-CCCC-1234567890AB" to "iPhone") else emptyList()
    }
    assertEquals(
      TrailblazeDevicePlatform.IOS to "D27D6F3F-AAAA-BBBB-CCCC-1234567890AB",
      DeviceClassifierResolver.findPlatformForInstanceId("D27D", prefixLookup),
    )
  }

  @Test
  fun `warmCache with maxParallelism zero or negative still runs without throwing`() {
    // Defensive: `coerceAtLeast(1)` clamps non-positive maxParallelism; if a future
    // refactor removes the clamp, `Semaphore(0)` (or the old `Executors.newFixedThreadPool(0)`)
    // would either deadlock or throw IllegalArgumentException. This pins the contract.
    val probe = DeviceClassifierResolver.DimensionsProbe { _, _ ->
      DeviceClassifierResolver.DeviceProbe(1080, 2400)
    }
    DeviceClassifierResolver.warmCache(
      devices = listOf(TrailblazeDevicePlatform.ANDROID to "clamp-test-udid"),
      maxParallelism = 0,
      dimensionsProbe = probe,
    )
    DeviceClassifierResolver.warmCache(
      devices = listOf(TrailblazeDevicePlatform.ANDROID to "neg-test-udid"),
      maxParallelism = -3,
      dimensionsProbe = probe,
    )
    // Both devices made it to the cache despite the non-positive parallelism.
    assertEquals(
      listOf("android", "phone"),
      DeviceClassifierResolver.classifiersFor(TrailblazeDevicePlatform.ANDROID, "clamp-test-udid", probe).map { it.classifier },
    )
    assertEquals(
      listOf("android", "phone"),
      DeviceClassifierResolver.classifiersFor(TrailblazeDevicePlatform.ANDROID, "neg-test-udid", probe).map { it.classifier },
    )
  }
}
