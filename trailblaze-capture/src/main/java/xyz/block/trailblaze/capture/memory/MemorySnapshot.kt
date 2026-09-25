package xyz.block.trailblaze.capture.memory

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * One reading of an Android app's memory, as `dumpsys meminfo <pid>` reports it. This is the full
 * model — every figure the dump has — so a richer event can be mapped from it later without
 * re-reading anything. The `memory` event itself carries only [javaHeapUsedKb] (see
 * [MemorySnapshot.toEventPayload]).
 *
 * All sizes are kilobytes, matching the dump. The PSS (proportional set size) breakdown comes from
 * the "App Summary" block — the same numbers Android Studio's Memory Profiler shows — the heap
 * size/alloc/free triples from the "Dalvik Heap" / "Native Heap" rows of the main table, and the
 * object counts from the "Objects" block, where a climbing [activities] count across screens is the
 * classic leak signal.
 *
 * [javaHeapUsedKb] is what the VM has actually handed out: heap size minus heap free — the
 * `Runtime.totalMemory() - Runtime.freeMemory()` figure an app would compute about itself. Compare
 * it with [AppMemoryLimits.heapLimitKb] to see how close the app is to an OutOfMemoryError.
 */
data class AppMemorySample(
  /**
   * Null when the reading did not come from a dump. A probe running inside the app reads the Java
   * heap from `Runtime` directly and has no PSS to report — and that is still the app's own heap,
   * which is the one figure the `memory` event carries, so such a reading is kept rather than
   * thrown away for missing a number nothing downstream asks for.
   */
  val totalPssKb: Long?,
  val totalRssKb: Long?,
  val totalSwapPssKb: Long?,
  val javaHeapKb: Long?,
  val nativeHeapKb: Long?,
  val codeKb: Long?,
  val stackKb: Long?,
  val graphicsKb: Long?,
  val privateOtherKb: Long?,
  val systemKb: Long?,
  val views: Int?,
  val viewRootImpls: Int?,
  val appContexts: Int?,
  val activities: Int?,
  val javaHeapSizeKb: Long? = null,
  val javaHeapAllocKb: Long? = null,
  val javaHeapFreeKb: Long? = null,
  val nativeHeapSizeKb: Long? = null,
  val nativeHeapAllocKb: Long? = null,
  val nativeHeapFreeKb: Long? = null,
) {
  val javaHeapUsedKb: Long?
    get() = if (javaHeapSizeKb != null && javaHeapFreeKb != null) javaHeapSizeKb - javaHeapFreeKb else null

  val nativeHeapUsedKb: Long?
    get() = if (nativeHeapSizeKb != null && nativeHeapFreeKb != null) nativeHeapSizeKb - nativeHeapFreeKb else null
}

/**
 * How much the OS will let the app's Java heap grow before it throws OutOfMemoryError, in
 * kilobytes. Static for the life of a process, so it is read once and repeated on every event.
 *
 * [heapGrowthLimitKb] is `dalvik.vm.heapgrowthlimit` (what `ActivityManager.getMemoryClass()`
 * reports), [heapMaxKb] is `dalvik.vm.heapsize` (`getLargeMemoryClass()`); the app gets the latter
 * only when its manifest declares `android:largeHeap`. [heapLimitKb] is whichever applies — the
 * number to compare [AppMemorySample.javaHeapUsedKb] against — and equals `Runtime.maxMemory()`,
 * which [runtimeMaxKb] carries directly when a probe could read it inside the app.
 */
data class AppMemoryLimits(
  val heapGrowthLimitKb: Long?,
  val heapMaxKb: Long?,
  val largeHeap: Boolean?,
  /** `Runtime.maxMemory()` read inside the app itself, when a probe had that access; exact, so it wins. */
  val runtimeMaxKb: Long? = null,
) {
  val heapLimitKb: Long?
    get() = when {
      runtimeMaxKb != null -> runtimeMaxKb
      largeHeap == true && heapMaxKb != null -> heapMaxKb
      heapGrowthLimitKb != null -> heapGrowthLimitKb
      else -> heapMaxKb
    }
}

/**
 * One reading of an iOS Simulator app's memory. Simulator apps are host processes, so this is what
 * macOS `footprint -p <pid>` reports: [footprintKb] is the physical footprint — the number iOS
 * itself uses for memory limits and Jetsam kills — and [categories] its per-region breakdown
 * (`MALLOC_SMALL`, `__DATA`, `CoreAnimation`, …), dirty bytes only. [mallocKb] sums the `MALLOC_*`
 * categories: the heap the app allocated itself. [rssKb] is the resident set when a probe read
 * it; the simulator probe does not (it is not in the event).
 */
data class IosAppMemorySample(
  val footprintKb: Long,
  val rssKb: Long?,
  val mallocKb: Long?,
  val categories: Map<String, Long>,
) {}

/** Device-wide memory from `/proc/meminfo`, in kilobytes. */
data class DeviceMemorySample(
  val memTotalKb: Long,
  val memAvailableKb: Long,
)


/**
 * Everything one sampling pass observed. [pid], [app] and [iosApp] are null while the app under
 * test is not running (before the trail launches it, or after it died); [device] is null when
 * device-wide memory is not readable (always, for a simulator — the host's RAM is not the device's).
 *
 * [source] names where the reading came from (`adb`, `ondevice`, `simulator`) and [gcForced]
 * whether the app was asked to collect garbage right before the reading, so a reader knows
 * whether a heap figure is live objects or live objects plus uncollected garbage.
 *
 * The snapshot holds the whole reading; the event written to the session
 * ([toEventPayload]) is deliberately the few numbers asked for — heap used, the heap limit, and
 * whether a GC ran — so a session does not fill up with a memory dump per tool call. Widen the
 * payload here if a richer event is ever wanted; nothing upstream needs to change.
 */
data class MemorySnapshot(
  val timeMs: Long,
  val appId: String?,
  val pid: Int?,
  val app: AppMemorySample?,
  val device: DeviceMemorySample?,
  val iosApp: IosAppMemorySample? = null,
  val limits: AppMemoryLimits? = null,
  val gcForced: Boolean? = null,
  val source: String? = null,
) {
  /**
   * The one number the event carries and change detection follows: Java heap used (heap size −
   * heap free, i.e. `Runtime.totalMemory() − freeMemory()`) on Android; the physical footprint on
   * iOS, which has no managed heap.
   */
  val usedKb: Long?
    get() = app?.javaHeapUsedKb ?: iosApp?.footprintKb

  /** Whether the app under test's process was observed running. */
  val processRunning: Boolean
    get() = pid != null

  /**
   * The `data` payload of one `memory` session event — the minimum that answers "how much heap is
   * the app using, how much may it use, and was that measured after a GC":
   *
   * ```
   * { "reason": "after_tool", "tool": "tapOnElement", "traceId": "tool-3f9c…", "appId": "…",
   *   "pid": 17220, "heapUsedKb": 41200, "heapLimitKb": 196608, "deltaKb": 2048,
   *   "deviceAvailableKb": 1268432, "gcForced": true, "source": "ondevice", "readMs": 210 }
   * ```
   *
   * `heapUsedKb` is heap size − heap free (Android); iOS writes `footprintKb` instead and has no
   * limit to report. `deltaKb` is the movement of that number since [previous] — the last EMITTED
   * event — and is left out across a process boundary. [reason] names why this snapshot was worth
   * an event (see [MemoryChangeDetector]); [tool] is the tool a `before_tool` / `after_tool` sample
   * brackets; [readMs] is how long the reading itself took, so the cost of a forced GC is visible.
   *
   * [traceId] is the trace of the dispatch that tool belongs to, so a row joins to that tool's
   * session logs instead of being matched on name and timestamp. Tool boundaries carry it; the
   * periodic samples belong to no tool and have none.
   *
   * `deviceAvailableKb` is how much memory the DEVICE had left at that moment (`MemAvailable`),
   * which is what decides whether the system was about to reclaim: an app killed with a healthy
   * heap and 80 MB free device-wide was killed by the low-memory killer, and without this number
   * that row is indistinguishable from a crash. Absent on a simulator, where the host's RAM is not
   * the device's. Adding another metric here means adding it to [MemoryChangeDetector] too, or a
   * session only ever gets it on rows some other metric already earned.
   */
  fun toEventPayload(
    reason: String,
    previous: MemorySnapshot?,
    tool: String? = null,
    readMs: Long? = null,
    traceId: String? = null,
  ): JsonObject = buildJsonObject {
    put("reason", reason)
    tool?.let { put("tool", it) }
    traceId?.let { put("traceId", it) }
    appId?.let { put("appId", it) }
    pid?.let { put("pid", it) }
    app?.javaHeapUsedKb?.let { put("heapUsedKb", it) }
    limits?.heapLimitKb?.let { put("heapLimitKb", it) }
    iosApp?.footprintKb?.let { put("footprintKb", it) }
    if (previous != null && previous.pid == pid && pid != null) {
      val current = usedKb
      val before = previous.usedKb
      if (current != null && before != null) put("deltaKb", current - before)
    }
    device?.memAvailableKb?.let { put("deviceAvailableKb", it) }
    gcForced?.let { put("gcForced", it) }
    source?.let { put("source", it) }
    readMs?.let { put("readMs", it) }
  }
}
