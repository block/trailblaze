package xyz.block.trailblaze.host.capture

import kotlinx.coroutines.runBlocking
import xyz.block.trailblaze.capture.memory.AdbMemoryProbe
import xyz.block.trailblaze.capture.memory.AppMemoryLimits
import xyz.block.trailblaze.capture.memory.AppMemorySample
import xyz.block.trailblaze.capture.memory.DumpsysMeminfoParser
import xyz.block.trailblaze.capture.memory.MemoryProbe
import xyz.block.trailblaze.capture.memory.MemorySnapshot
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.mcp.android.ondevice.rpc.GetMemoryInfoRequest
import xyz.block.trailblaze.mcp.android.ondevice.rpc.GetMemoryInfoResponse
import xyz.block.trailblaze.mcp.android.ondevice.rpc.OnDeviceRpcClient
import xyz.block.trailblaze.mcp.android.ondevice.rpc.RpcResult
import xyz.block.trailblaze.transport.AndroidWireTransportMode
import xyz.block.trailblaze.util.Console

/**
 * Reads an Android app's memory by asking the Trailblaze on-device runner ([GetMemoryInfoRequest])
 * — no adb round trips while a runner is installed — and falls back to [fallback] (adb) when it
 * is not.
 *
 * A device without a reachable runner is remembered for [backoffMs] so a session on a plain adb
 * device does not pay a failed connection before every sample. The backoff is short because the
 * first samples of a session usually land while the host is still installing and starting the
 * runner; after [MAX_UNREACHABLE_ATTEMPTS] misses in a row the device is read over adb for the
 * rest of the probe's life, as is a runner too old to know the request (HTTP 404).
 *
 * [rpc] is injectable so the routing is unit-tested without a device.
 */
class OnDeviceRpcMemoryProbe(
  private val fallback: MemoryProbe = AdbMemoryProbe(),
  /** Null means the real runner; tests script this. */
  rpc: ((deviceId: String, request: GetMemoryInfoRequest) -> RpcResult<GetMemoryInfoResponse>)? = null,
  private val clock: () -> Long = System::currentTimeMillis,
  private val backoffMs: Long = DEFAULT_BACKOFF_MS,
) : MemoryProbe {

  private val clients = mutableMapOf<String, OnDeviceRpcClient>()
  private val rpc: (deviceId: String, request: GetMemoryInfoRequest) -> RpcResult<GetMemoryInfoResponse> = rpc ?: ::callRunner
  private val backoffUntilByDevice = mutableMapOf<String, Long>()
  private val unreachableCountByDevice = mutableMapOf<String, Int>()
  private var announced = false

  override fun read(deviceId: String, appId: String?, forceGc: Boolean): MemorySnapshot? {
    val now = clock()
    if ((backoffUntilByDevice[deviceId] ?: 0L) > now) return fallback.read(deviceId, appId, forceGc)
    return when (val result = rpc(deviceId, GetMemoryInfoRequest(appId = appId, forceGc = forceGc))) {
      is RpcResult.Success -> {
        unreachableCountByDevice.remove(deviceId)
        if (!announced) {
          announced = true
          Console.log("[memory-capture] reading memory through the on-device runner on $deviceId")
        }
        toSnapshot(result.data)
      }
      is RpcResult.Failure -> {
        val misses = (unreachableCountByDevice[deviceId] ?: 0) + 1
        unreachableCountByDevice[deviceId] = misses
        val giveUp = tooOldForThisRequest(result) || misses >= MAX_UNREACHABLE_ATTEMPTS
        val until = if (giveUp) Long.MAX_VALUE else now + backoffMs
        backoffUntilByDevice[deviceId] = until
        Console.log(
          "[memory-capture] on-device runner unavailable on $deviceId (${result.errorType}: ${result.message}); " +
            "reading memory over adb" + if (until == Long.MAX_VALUE) " for this session" else " for the next ${backoffMs / 1000}s",
        )
        fallback.read(deviceId, appId, forceGc)
      }
    }
  }

  override fun close() {
    synchronized(clients) {
      clients.values.forEach { runCatching { it.close() } }
      clients.clear()
    }
    fallback.close()
  }

  /**
   * Whether this failure says the runner will NEVER know this request, as opposed to failing it
   * this time.
   *
   * A runner older than [GetMemoryInfoRequest] answers 404 from the catch-all route, and that is
   * worth giving up on for good. But the runner also answers its own handler-side failures with
   * 500, and every non-2xx status arrives here as the same `HTTP_ERROR` — so treating the error
   * TYPE as "too old" makes one transient device-side exception cost the session every reading
   * the runner could have given, including the in-process `Runtime` figures this probe exists
   * for. The status is read out of the failure text because it is not carried structurally; an
   * unreadable one counts as transient, which is the safe direction (a strike, not a life
   * sentence).
   */
  private fun tooOldForThisRequest(failure: RpcResult.Failure): Boolean {
    if (failure.errorType != RpcResult.ErrorType.HTTP_ERROR) return false
    val status = HTTP_STATUS.find(failure.message.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
    return status == 404 || status == 501
  }

  private fun callRunner(deviceId: String, request: GetMemoryInfoRequest): RpcResult<GetMemoryInfoResponse> {
    val client = synchronized(clients) {
      clients.getOrPut(deviceId) { newClient(deviceId) }
    }
    return runBlocking { client.rpcCall<GetMemoryInfoResponse, GetMemoryInfoRequest>(request) }
  }

  /** Internal so a test can assert the wire and the repair opt-out this probe pins, without a device. */
  internal fun newClient(deviceId: String): OnDeviceRpcClient = OnDeviceRpcClient(
    trailblazeDeviceId = TrailblazeDeviceId(deviceId, TrailblazeDevicePlatform.ANDROID),
    wireTransportMode = WIRE_TRANSPORT_MODE,
    // A memory sample is a background diagnostic. The ordinary failure here is "the runner is not
    // listening", which arrives as an IOException — and the default response to that is to tear
    // down and re-establish the adb forward and evict the shared adb client for the serial. Done
    // on behalf of a reading nobody is waiting for, that pulls the transport out from under the
    // trail running on the same device, mid-step. This probe takes the failure and falls back to
    // adb instead — the same protection `AdbMemoryProbe` gets from `evictClientOnTimeout = false`.
    repairTransportOnNetworkError = false,
  )

  companion object {
    /**
     * The wire this probe's RPC uses, pinned rather than taken from the environment switch.
     *
     * `OnDeviceRpcProtoCodec` has no mapping for [GetMemoryInfoRequest], and an unmapped request
     * asks the generic client to fall back to HTTP — which `TRAILBLAZE_ANDROID_WIRE_TRANSPORT=protobuf`
     * turns into an outright failure. Left on the switch, every reading in that mode would count a
     * strike and the probe would give up on the runner for the session after three, losing the
     * in-process `Runtime` figures for no reason. Same call as `TrailblazeDriverType.protoWireSafe`
     * makes for the drivers whose screen state the codec cannot encode: pin the wire where protobuf
     * cannot carry the payload at all. Teaching the codec this request is the real fix, and would
     * let this go back to the switch.
     */
    internal val WIRE_TRANSPORT_MODE: AndroidWireTransportMode = AndroidWireTransportMode.JSON

    /** How long a device with no reachable runner is read over adb before the runner is tried again. */
    const val DEFAULT_BACKOFF_MS: Long = 15_000

    /** Consecutive unreachable attempts after which the runner is not asked again by this probe. */
    const val MAX_UNREACHABLE_ATTEMPTS: Int = 3

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

    /** The status code inside an HTTP failure's message, which carries it as plain text. */
    private val HTTP_STATUS = Regex("HTTP (\\d{3})")

    /**
     * The runner's reply as the capture's snapshot. The dump goes through the same parser as the
     * adb path; when the runner shares the app's process, its `Runtime` figures replace the dump's
     * heap size/free (they are the app's own numbers, read after a real `System.gc()`), and
     * `Runtime.maxMemory()` is the heap limit.
     *
     * Those `Runtime` figures do not need the dump. They are the whole of what the report graphs, so
     * a reading whose dump never arrived — the shell budget expiring is enough — still yields a
     * sample carrying the heap, rather than a hole in the series next to a device row that read
     * fine. Only the PSS breakdown is lost with the dump, and nothing reads it today.
     */
    fun toSnapshot(response: GetMemoryInfoResponse): MemorySnapshot {
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
  }
}
