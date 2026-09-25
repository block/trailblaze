package xyz.block.trailblaze.mcp.handlers

import android.app.ActivityManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Process
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import xyz.block.trailblaze.AdbCommandUtil
import xyz.block.trailblaze.InstrumentationUtil
import xyz.block.trailblaze.device.androidPackageNameViolation
import xyz.block.trailblaze.mcp.RpcHandler
import xyz.block.trailblaze.mcp.android.ondevice.rpc.AndroidMemoryReadCommands
import xyz.block.trailblaze.mcp.android.ondevice.rpc.GetMemoryInfoRequest
import xyz.block.trailblaze.mcp.android.ondevice.rpc.GetMemoryInfoResponse
import xyz.block.trailblaze.mcp.android.ondevice.rpc.RpcResult
import xyz.block.trailblaze.util.Console

/**
 * Answers [GetMemoryInfoRequest]: the app under test's memory, read from the device itself so the
 * host's memory capture needs no adb while a runner is installed.
 *
 * Two situations, decided by whether this instrumentation loads into the app's own process:
 *  - **In-process** (an in-process test APK targeting the app): `System.gc()` collects for real,
 *    and the app's own `Runtime` gives heap used (`totalMemory − freeMemory`) and the limit
 *    (`maxMemory`) exactly. `dumpsys meminfo` still supplies the PSS breakdown.
 *  - **Standalone runner**: the app is another process, so the reads are the shell ones —
 *    `pidof`, `run-as <app> kill -10 <pid>` for the GC (ART collects on SIGUSR1; `run-as` only
 *    works for a debuggable app, so a release build is not signalled and reports
 *    `gcForced = false`), `dumpsys meminfo <pid>`.
 *
 * Every shell read is ONE plain command. `UiAutomation.executeShellCommand` execs its argument
 * directly — there is no shell, so `||`, `&&`, redirection and `$var` are not available, and a
 * command that starts with `(` wedges the UiAutomation connection for the whole read bound.
 *
 * Heap limits come from `ActivityManager` (memory class / large memory class) and the app's
 * `largeHeap` manifest flag via `PackageManager`, which is exact where a `getprop` read is an
 * approximation. The dump text is returned verbatim: the host's one parser stays the single source
 * of truth for the numbers.
 *
 * [device] is the seam that makes this testable on the JVM, where there is no instrumentation.
 */
class GetMemoryInfoRequestHandler(
  private val device: DeviceMemoryReader = AndroidDeviceMemoryReader,
) : RpcHandler<GetMemoryInfoRequest, GetMemoryInfoResponse> {

  /** What the handler needs from the device; production reads Android, tests script it. */
  interface DeviceMemoryReader {
    /** The package this instrumentation targets, or null when unknown. */
    val targetPackage: String?
    val ownPid: Int

    /** Run one command, giving up after [timeoutMs] — the caller's remaining share of the budget. */
    fun shell(command: String, timeoutMs: Long): String
    fun collectGarbage()

    /**
     * A monotonic millisecond reading, used only to spend the read budget. Overridable so a test
     * can make the budget run out without taking that long.
     */
    fun elapsedMs(): Long = System.nanoTime() / 1_000_000

    /** `Runtime` total / free / max, in kilobytes. */
    fun runtimeKb(): Triple<Long, Long, Long>

    /** `ActivityManager` memory class / large memory class, in megabytes. */
    fun memoryClassesMb(): Pair<Int, Int>?
    fun isLargeHeap(appId: String): Boolean?

    /** Whether [appId] is a debuggable build — the only kind `run-as` may signal. */
    fun isDebuggable(appId: String): Boolean?
  }

  override suspend fun handle(request: GetMemoryInfoRequest): RpcResult<GetMemoryInfoResponse> {
    return try {
      RpcResult.Success(read(request))
    } catch (e: CancellationException) {
      throw e
    } catch (t: Throwable) {
      Console.log("❌ GetMemoryInfoRequestHandler: ${t::class.java.simpleName}: ${t.message}")
      RpcResult.Failure(
        errorType = RpcResult.ErrorType.UNKNOWN_ERROR,
        message = "Could not read memory on device: ${t.message}",
        details = t.stackTraceToString(),
      )
    }
  }

  private suspend fun read(request: GetMemoryInfoRequest): GetMemoryInfoResponse {
    // ONE budget across every command this answer makes, so the sequence as a whole finishes
    // before the host gives up — see [GetMemoryInfoRequest.SHELL_READ_BUDGET_MS]. Once it is spent
    // no further device call is made and the answer goes back with whatever was read, because a
    // partial reading nobody waits on beats holding the UiAutomation monitor away from the trail.
    val deadline = device.elapsedMs() + GetMemoryInfoRequest.SHELL_READ_BUDGET_MS
    var ranDry = false
    fun shell(command: String): String? {
      val remaining = deadline - device.elapsedMs()
      if (remaining <= 0) {
        if (!ranDry) {
          ranDry = true
          Console.log(
            "⏱️ GetMemoryInfoRequestHandler: ${GetMemoryInfoRequest.SHELL_READ_BUDGET_MS}ms read budget " +
              "spent before `$command`; answering with a partial reading",
          )
        }
        return null
      }
      return device.shell(command, remaining)
    }

    val procMeminfo = shell("cat /proc/meminfo")
    // The id arrives over RPC and is interpolated into `pidof` and the `run-as` GC signal below, so
    // it is held to the Android package-name grammar first — the canonical rule, not a second
    // escaping scheme. An id outside it is answered as "no app", the same reading as a request that
    // named none: the device figures are still worth returning, and a bad id must not fail a trail.
    val violation = request.appId?.let(::androidPackageNameViolation)
    if (violation != null) {
      Console.log("⚠️ GetMemoryInfoRequestHandler: not reading an app for this request: $violation")
    }
    val appId = request.appId.takeIf { violation == null }
      ?: return GetMemoryInfoResponse(appId = null, pid = null, inProcess = false, gcForced = null, procMeminfo = procMeminfo)
    val inProcess = device.targetPackage == appId
    val pid = if (inProcess) device.ownPid else AndroidMemoryReadCommands.firstPid(shell("pidof $appId"))
    var gcForced: Boolean? = null
    if (pid != null && request.forceGc) {
      gcForced = if (inProcess) {
        device.collectGarbage()
        true
      } else if (device.isDebuggable(appId) == true) {
        // `run-as` prints nothing on success and its refusal to stderr, which the shell read does
        // not carry — so success is "debuggable, and nothing came back on stdout". A skipped call
        // reports no collection, which is what happened.
        shell(AndroidMemoryReadCommands.gcCommand(appId, pid.toString()))?.isBlank() == true
      } else {
        false
      }
      if (gcForced) delay(AndroidMemoryReadCommands.GC_SETTLE_MS)
    } else if (pid != null) {
      gcForced = false
    }
    val dumpsys = pid?.let { shell("dumpsys meminfo $it") }
    val runtime = if (inProcess) device.runtimeKb() else null
    val classes = device.memoryClassesMb()
    return GetMemoryInfoResponse(
      appId = appId,
      pid = pid,
      inProcess = inProcess,
      gcForced = gcForced,
      runtimeTotalKb = runtime?.first,
      runtimeFreeKb = runtime?.second,
      runtimeMaxKb = runtime?.third,
      memoryClassMb = classes?.first,
      largeMemoryClassMb = classes?.second,
      largeHeap = device.isLargeHeap(appId),
      dumpsysMeminfo = dumpsys,
      procMeminfo = procMeminfo,
    )
  }

  /** The real device, reached through the instrumentation this runner is. */
  object AndroidDeviceMemoryReader : DeviceMemoryReader {
    override val targetPackage: String?
      get() = runCatching { InstrumentationUtil.withInstrumentation { targetContext.packageName } }.getOrNull()

    override val ownPid: Int get() = Process.myPid()

    // The bound comes from the caller, which is spending one budget across the whole reading:
    // these reads hold the process-wide UiAutomation monitor, and the default bound is minutes
    // long. A sample the host stopped waiting for must not go on blocking the trail's device
    // actions — see [GetMemoryInfoRequest.SHELL_READ_BUDGET_MS].
    override fun shell(command: String, timeoutMs: Long): String =
      AdbCommandUtil.execShellCommand(command, timeoutMs)

    override fun collectGarbage() {
      System.gc()
      System.runFinalization()
    }

    override fun runtimeKb(): Triple<Long, Long, Long> {
      val rt = Runtime.getRuntime()
      return Triple(rt.totalMemory() / 1024, rt.freeMemory() / 1024, rt.maxMemory() / 1024)
    }

    override fun memoryClassesMb(): Pair<Int, Int>? = runCatching {
      InstrumentationUtil.withInstrumentation {
        val am = targetContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        am.memoryClass to am.largeMemoryClass
      }
    }.getOrNull()

    override fun isLargeHeap(appId: String): Boolean? = hasFlag(appId, ApplicationInfo.FLAG_LARGE_HEAP)

    override fun isDebuggable(appId: String): Boolean? = hasFlag(appId, ApplicationInfo.FLAG_DEBUGGABLE)

    private fun hasFlag(appId: String, flag: Int): Boolean? = runCatching {
      InstrumentationUtil.withInstrumentation {
        targetContext.packageManager.getApplicationInfo(appId, 0).flags and flag != 0
      }
    }.getOrNull()
  }

}
