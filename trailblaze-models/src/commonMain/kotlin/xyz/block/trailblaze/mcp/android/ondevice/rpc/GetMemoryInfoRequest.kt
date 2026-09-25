package xyz.block.trailblaze.mcp.android.ondevice.rpc

import kotlinx.serialization.Serializable

/**
 * Host -> on-device RPC asking the Trailblaze runner to read the app under test's memory, so the
 * host's memory capture needs no adb round trips while a runner is installed.
 *
 * The runner answers from wherever it is:
 *  - **Sharing the app's process** (an in-process test APK): it collects garbage with
 *    `System.gc()` and reads the app's own `Runtime` — heap used is exactly
 *    `totalMemory() - freeMemory()` and the limit exactly `maxMemory()`.
 *  - **Standalone runner**: it shells out on the device (`pidof`, `run-as … kill -10` for the
 *    GC, `dumpsys meminfo`) — the same reads the host would otherwise make over adb, minus the
 *    adb hop each.
 *
 * Compatibility is the failure fallback, not a handshake capability: a runner older than this
 * request answers 404 (or 501) from its catch-all route, the host reads that device over adb for
 * the rest of the session, and every other failure is retried a few times first. There is
 * deliberately no advertised capability id to check — the host would have to try the request
 * anyway to find out whether the runner on THIS device can answer it, and a second version signal
 * is a second thing to keep true.
 *
 * @param appId the app under test, or null for device-wide memory only.
 * @param forceGc ask the app to collect garbage before reading, so heap figures are live objects.
 */
@Serializable
data class GetMemoryInfoRequest(
  val appId: String?,
  val forceGc: Boolean = true,
) : RpcRequest<GetMemoryInfoResponse> {
  override val requestTimeoutMs: Long get() = TIMEOUT_MS

  companion object {
    /** A reading is a few shell commands; a runner that takes longer is wedged and adb should take over. */
    const val TIMEOUT_MS: Long = 5_000L

    /**
     * The budget for ALL the shell reads one answer makes, together — not a per-command bound.
     * Kept BELOW [TIMEOUT_MS] and derived from it so the order cannot drift.
     *
     * Answering takes up to four sequential commands (`/proc/meminfo`, `pidof`, the GC signal,
     * `dumpsys meminfo`). Bounding each one separately bounds nothing that matters: four reads at
     * the full bound each run well past [TIMEOUT_MS], so the host would have abandoned the request
     * while the runner was still working. And these reads hold the process-wide UiAutomation
     * monitor, which every device action queues behind — the trail's own commands get a bound
     * minutes long (`AndroidShellBounds.SHELL_READ_TIMEOUT_MS`) because a slow `pm clear` must not
     * red a build, but a memory sample is a background diagnostic nobody is waiting for. So the
     * whole reading shares one budget and answers partially rather than stalling the trail.
     */
    const val SHELL_READ_BUDGET_MS: Long = TIMEOUT_MS - 1_000L
  }
}

/**
 * The runner's reading. Raw dump text travels as-is so the host's one parser stays the single
 * source of truth for the numbers; the `Runtime` figures are extra precision available only when
 * the runner shares the app's process.
 *
 * All sizes are kilobytes except the memory classes, which Android reports in megabytes.
 *
 * @param pid the app's pid, or null when it is not running.
 * @param inProcess whether the runner shares the app's process (so the `runtime*` figures are the
 *   app's own).
 * @param gcForced whether a collection was actually triggered before the reading.
 * @param runtimeTotalKb `Runtime.totalMemory()` — the heap the VM has claimed so far.
 * @param runtimeFreeKb `Runtime.freeMemory()` — the unused part of that; used = total − free.
 * @param runtimeMaxKb `Runtime.maxMemory()` — the ceiling the VM will grow to (the OOM line).
 * @param memoryClassMb `ActivityManager.getMemoryClass()` — the per-app heap limit without
 *   `largeHeap`.
 * @param largeMemoryClassMb `ActivityManager.getLargeMemoryClass()` — the limit with `largeHeap`.
 * @param largeHeap whether the app's manifest declares `android:largeHeap`.
 * @param dumpsysMeminfo verbatim `dumpsys meminfo <pid>` output, or null when the app is not running.
 * @param procMeminfo verbatim `/proc/meminfo`.
 */
@Serializable
data class GetMemoryInfoResponse(
  val appId: String?,
  val pid: Int?,
  val inProcess: Boolean,
  val gcForced: Boolean?,
  val runtimeTotalKb: Long? = null,
  val runtimeFreeKb: Long? = null,
  val runtimeMaxKb: Long? = null,
  val memoryClassMb: Int? = null,
  val largeMemoryClassMb: Int? = null,
  val largeHeap: Boolean? = null,
  val dumpsysMeminfo: String? = null,
  val procMeminfo: String? = null,
)
