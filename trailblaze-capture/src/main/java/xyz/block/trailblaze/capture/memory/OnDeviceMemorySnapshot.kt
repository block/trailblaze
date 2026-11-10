package xyz.block.trailblaze.capture.memory

import xyz.block.trailblaze.mcp.android.ondevice.rpc.GetMemoryInfoResponse

/**
 * The on-device runner's memory answer ([GetMemoryInfoResponse]) as a [MemorySnapshot]. Shared by
 * the host, which asks the runner over RPC, and the on-device runner itself, which answers its own
 * request when there is no host — so both write the same `memory` rows from the same reading.
 */
object OnDeviceMemorySnapshot {

  /**
   * The dump goes through the same parser as the adb path; when the runner shares the app's
   * process, its `Runtime` figures replace the dump's heap size/free (they are the app's own
   * numbers, read after a real `System.gc()`), and `Runtime.maxMemory()` is the heap limit.
   *
   * Those `Runtime` figures do not need the dump. They are the whole of what the report graphs, so
   * a reading whose dump never arrived — the shell budget expiring is enough — still yields a
   * sample carrying the heap, rather than a hole in the series next to a device row that read
   * fine. Only the PSS breakdown is lost with the dump, and nothing reads it today.
   */
  fun from(response: GetMemoryInfoResponse): MemorySnapshot {
    val parsed = response.dumpsysMeminfo?.let(DumpsysMeminfoParser::parseAppSample)
    val runtimeHeap = response.inProcess && response.runtimeTotalKb != null && response.runtimeFreeKb != null
    val app = when {
      runtimeHeap -> (parsed ?: HEAP_ONLY_SAMPLE)
        .copy(javaHeapSizeKb = response.runtimeTotalKb, javaHeapFreeKb = response.runtimeFreeKb)
      else -> parsed
    }
    val limits = if (app != null) {
      AppMemoryLimits(
        heapGrowthLimitKb = response.memoryClassMb?.let { it * 1024L },
        heapMaxKb = response.largeMemoryClassMb?.let { it * 1024L },
        largeHeap = response.largeHeap,
        runtimeMaxKb = response.runtimeMaxKb,
      ).takeIf { it.heapLimitKb != null }
    } else {
      null
    }
    return MemorySnapshot(
      timeMs = 0,
      appId = response.appId,
      // The runner resolved the pid itself, so it is the liveness answer; a dump that did not
      // parse must not null it, or a healthy app reads as `process_died`. Same reasoning as the
      // adb probe.
      pid = response.pid,
      app = app,
      device = response.procMeminfo?.let(DumpsysMeminfoParser::parseDeviceSample),
      limits = limits,
      gcForced = if (response.pid != null) response.gcForced else null,
      source = MemoryProbe.SOURCE_ONDEVICE,
    )
  }

  /**
   * The shape a dump-free reading fills in: every dump-only figure absent, so the only numbers
   * the caller sees are the ones it copies in from `Runtime`.
   */
  private val HEAP_ONLY_SAMPLE = AppMemorySample(
    totalPssKb = null,
    totalRssKb = null,
    totalSwapPssKb = null,
    javaHeapKb = null,
    nativeHeapKb = null,
    codeKb = null,
    stackKb = null,
    graphicsKb = null,
    privateOtherKb = null,
    systemKb = null,
    views = null,
    viewRootImpls = null,
    appContexts = null,
    activities = null,
  )
}
