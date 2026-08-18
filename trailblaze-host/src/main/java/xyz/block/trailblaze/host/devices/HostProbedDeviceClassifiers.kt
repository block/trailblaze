package xyz.block.trailblaze.host.devices

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xyz.block.trailblaze.cli.DeviceClassifierResolver
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.util.Console

/**
 * Host-side device classification for the runtime paths that lower a unified trail's per-classifier
 * recordings — the Android on-device-RPC runners (when the on-device probe returns nothing) and the
 * MCP trail executor (which has no on-device probe at all).
 *
 * Wraps [DeviceClassifierResolver] with the two properties those callers need and the resolver
 * itself deliberately doesn't provide:
 *
 *  1. **A bounded wait.** The probe can't be allowed to stall a run; see [forDevice].
 *  2. **Confidence-aware degradation.** A non-definitive classification
 *     ([DeviceClassifierResolver.Classification]) is discarded in favor of the bare platform.
 *     Vague resolves to the generic `android:` recording leg; confidently-wrong resolves to the
 *     `android-phone:` leg on a tablet and runs the wrong recorded steps.
 */
object HostProbedDeviceClassifiers {

  /**
   * Bound on the host-side classifier probe in [forDevice]. Sized as hang containment, not a
   * latency budget: the resolver's own `wm size`/`wm density` calls are bounded at 3s each with one
   * retry (~12s worst case), so a probe still running at 15s is wedged rather than slow.
   */
  private const val PROBE_TIMEOUT_MS = 15_000L

  private val probeThreadCount = AtomicInteger(0)

  /**
   * Dedicated executor for the detached probes, deliberately NOT `ForkJoinPool.commonPool()`.
   *
   * A probe abandoned by the timeout below stays parked forever (a wedged dadb read can't be
   * interrupted), so on the common pool each abandonment would permanently consume a worker shared
   * with every other user of that pool in the daemon process. Isolating them here means the only
   * thing an abandoned probe can starve is another probe. Daemon threads, so a parked one never
   * holds up JVM exit; cached so the steady-state cost is zero threads.
   */
  private val probeExecutor = Executors.newCachedThreadPool { runnable ->
    Thread(runnable, "trailblaze-classifier-probe-${probeThreadCount.incrementAndGet()}").apply {
      isDaemon = true
    }
  }

  /**
   * In-flight probe per device, so repeated lookups while one is wedged join the existing future
   * instead of spawning another thread each time.
   *
   * The resolver's own cache doesn't cover this case: a timed-out classification is never cached, so
   * without coalescing every replay against a wedged device would start a fresh probe and park a
   * fresh thread, accumulating without limit for as long as the wedge lasts. Joining a
   * still-running probe costs nothing — the caller's own `get(timeout)` bound still applies, so it
   * degrades to platform-only on schedule either way. Entries are removed on completion, so a
   * finished probe is never served as a stale answer.
   *
   * Keyed by device only, so two concurrent callers for one device share a probe regardless of which
   * [forDevice] `probe` argument they passed. Production callers all use the default, so this only
   * constrains tests: give each test its own instance id rather than relying on a shared one.
   */
  private val inFlightProbes = ConcurrentHashMap<TrailblazeDeviceId, CompletableFuture<DeviceClassifierResolver.Classification>>()

  /**
   * Classify [deviceId] from the host via the canonical [DeviceClassifierResolver] (adb
   * `wm size`/`wm density`, or the iOS screenshot-header probe), so the session — and the
   * test-result telemetry and recording-leg lowering built from it — gets a specific classifier
   * (e.g. `android-phone`) instead of the bare platform.
   *
   * Never empty and never stalls: degrades to the platform-only classifier when the probe fails,
   * times out, yields nothing, or yields an answer the resolver itself can't stand behind. That
   * worst case is exactly the bare `[android]` this replaced.
   *
   * [probe] and [probeTimeoutMs] are injectable so the timeout and degrade branches are unit
   * testable without a device (and without a real 15s wait); production callers use the defaults.
   */
  suspend fun forDevice(
    deviceId: TrailblazeDeviceId,
    probeTimeoutMs: Long = PROBE_TIMEOUT_MS,
    probe: (TrailblazeDeviceId) -> DeviceClassifierResolver.Classification = { id ->
      DeviceClassifierResolver.classificationFor(
        platform = id.trailblazeDevicePlatform,
        instanceId = id.instanceId,
      )
    },
  ): List<TrailblazeDeviceClassifier> {
    val platformOnly = listOf(deviceId.trailblazeDevicePlatform.asTrailblazeDeviceClassifier())
    Console.log("[DeviceClassifierResolver] Classifying ${deviceId.instanceId} via host-side probe")
    // The probe must be bounded, and the bound has to be on the WAIT rather than the work: the
    // resolver consults any installed distribution override first, and an override may shell out
    // via the UNBOUNDED `execAdbShellCommand` (a `getprop` pair is the typical shape). A wedged
    // dadb transport hangs on read instead of throwing, so neither `withDadb`'s IOException retry
    // nor coroutine cancellation can unwind it — `withTimeout` around a blocking body would just
    // wait for that body anyway. On the runner path this fallback runs precisely when the device
    // RPC already failed, i.e. exactly when a wedged transport is most likely, so an unbounded wait
    // here would trade a wrong-but-instant classifier for a stalled run. Bounding the wait via a
    // detached future (the same idiom `DeviceClassifierResolver.warmCache` uses) guarantees we
    // proceed; an abandoned probe thread is the acceptable cost of that guarantee, contained to
    // [probeExecutor] and coalesced per device by [inFlightProbes] so repeated replays against a
    // wedged device can't accumulate threads.
    val classification = withContext(Dispatchers.IO) {
      val pending = inFlightProbes.computeIfAbsent(deviceId) { id ->
        CompletableFuture.supplyAsync({ probe(id) }, probeExecutor)
      }
      // Registered OUTSIDE `computeIfAbsent` — a `ConcurrentHashMap` mapping function must not touch
      // the map, and this callback fires on whichever thread completes the probe. The two-arg
      // `remove` only clears our own future, so a probe that finished while a later caller was
      // installing its replacement can't evict it.
      pending.whenComplete { _, _ -> inFlightProbes.remove(deviceId, pending) }
      try {
        pending.get(probeTimeoutMs, TimeUnit.MILLISECONDS)
      } catch (e: Exception) {
        Console.log(
          "[DeviceClassifierResolver] Host-side probe failed for ${deviceId.instanceId} " +
            "(${e::class.simpleName}: ${e.message}); using platform-only classifier",
        )
        null
      }
    }
    val resolved = when {
      classification == null -> platformOnly
      // A best-effort answer is worse than a vague one here: closest-wins lowering would select the
      // guessed sub-category's recording leg and run steps recorded for a different device shape.
      !classification.definitive -> {
        Console.log(
          "[DeviceClassifierResolver] Host-side probe for ${deviceId.instanceId} returned a " +
            "NON-definitive classification (${classification.classifiers.joinToString("-") { it.classifier }}); " +
            "using platform-only classifier instead of guessing a sub-category",
        )
        platformOnly
      }
      // An override is free to return an empty list, which downstream reads as device-AGNOSTIC (a
      // `resolveSkip` on any classifier would then apply). The expression this replaced was
      // unconditionally non-empty, so keep that contract.
      else -> classification.classifiers.ifEmpty { platformOnly }
    }
    // Log the OUTCOME, not just the attempt: a probe that degrades to platform-only reproduces the
    // very bare-`android` row this fallback exists to eliminate, and that has to be visible when
    // triaging a mis-classified telemetry row or an unexpectedly-generic recording leg.
    if (resolved == platformOnly) {
      Console.log(
        "[DeviceClassifierResolver] Host-side probe did not resolve a specific classifier for " +
          "${deviceId.instanceId}; telemetry and recording lowering will use the bare platform",
      )
    } else {
      Console.log(
        "[DeviceClassifierResolver] Host-side probe resolved ${deviceId.instanceId} " +
          "as ${resolved.joinToString("-") { it.classifier }}",
      )
    }
    return resolved
  }
}
