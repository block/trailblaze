package xyz.block.trailblaze.host.capture

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import xyz.block.trailblaze.capture.memory.AdbMemoryProbe
import xyz.block.trailblaze.capture.memory.MemoryProbe
import xyz.block.trailblaze.capture.memory.MemorySnapshot
import xyz.block.trailblaze.capture.memory.OnDeviceMemorySnapshot
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.mcp.android.ondevice.rpc.GetMemoryInfoRequest
import xyz.block.trailblaze.mcp.android.ondevice.rpc.GetMemoryInfoResponse
import xyz.block.trailblaze.mcp.android.ondevice.rpc.OnDeviceRpcClient
import xyz.block.trailblaze.mcp.android.ondevice.rpc.RpcResult
import xyz.block.trailblaze.transport.AndroidWireTransportMode
import xyz.block.trailblaze.util.Console

/**
 * Reads an Android app's memory through the Trailblaze on-device runner ([GetMemoryInfoRequest])
 * when the runner shares the app's process (the in-process driver), and over [fallback] (adb)
 * otherwise.
 *
 * Only an in-process runner adds anything: the app's own `Runtime` heap and exact heap limit. A
 * runner in its own process reads the same `dumpsys` adb does, and costs the
 * trail time doing it: the runner's shell holds the process-wide UiAutomation lock for the whole
 * read (~360 ms on a large app), and every step's screenshot waits on that lock. So the first
 * reply that says the runner is outside the app's process ([GetMemoryInfoResponse.inProcess]
 * false) sends the device to adb until a runner connects again ([runnerReady]), which may be an
 * in-process one. Measured on a large production app with the accessibility driver: per-step
 * screen capture took 529 ms with every reading through the runner, 158 ms over adb, and 139 ms
 * with memory capture off.
 *
 * A device without a reachable runner is remembered for [backoffMs] so a session on a plain adb
 * device does not pay a failed connection before every sample. The backoff is short because the
 * first samples of a session usually land while the host is still installing and starting the
 * runner; after [MAX_UNREACHABLE_ATTEMPTS] misses in a row the device is read over adb for the
 * rest of the probe's life, as is a runner too old to know the request (HTTP 404). A run that
 * connects the runner calls [runnerReady], which ends either, so samples taken while the device was
 * still connecting, or against the runner the connect replaced, do not cost the session its readings.
 *
 * [rpc] is injectable so the routing is unit-tested without a device.
 */
class OnDeviceRpcMemoryProbe(
  private val fallback: MemoryProbe = AdbMemoryProbe(),
  /** Null means the real runner; tests script this. */
  rpc: ((deviceId: String, request: GetMemoryInfoRequest) -> RpcResult<GetMemoryInfoResponse>)? = null,
  private val clock: () -> Long = System::currentTimeMillis,
  private val backoffMs: Long = DEFAULT_BACKOFF_MS,
  private val finalReadBudgetMs: Long = FINAL_READ_BUDGET_MS,
) : MemoryProbe {

  private val clients = mutableMapOf<String, OnDeviceRpcClient>()
  private val rpc: (deviceId: String, request: GetMemoryInfoRequest) -> RpcResult<GetMemoryInfoResponse> = rpc ?: ::callRunner
  private val backoffUntilByDevice = mutableMapOf<String, Long>()
  private val unreachableCountByDevice = mutableMapOf<String, Int>()
  /** The [runnerReady] count each device's backoff was set under; a newer count ends it. */
  private val backoffReadinessByDevice = mutableMapOf<String, Long>()
  /**
   * Devices whose runner said it is outside the app's process, with the [runnerReady] count it said
   * so under. Read over adb while the count is unchanged; a newer runner is asked again.
   */
  private val outOfProcessReadinessByDevice = mutableMapOf<String, Long>()
  private var announced = false

  override fun read(deviceId: String, appId: String?, forceGc: Boolean): MemorySnapshot? =
    readWithin(deviceId, appId, forceGc, budgetMs = null)

  /**
   * The closing reading gets [finalReadBudgetMs] instead of the request's full timeout. A runner
   * that has not answered by then is not going to answer quickly, and the session's end is waiting.
   * Measured on an in-process runner after the trail: the request took the whole 5 s
   * [GetMemoryInfoRequest.TIMEOUT_MS] and then the final row came from adb in about 0.5 s anyway.
   */
  override fun readFinal(deviceId: String, appId: String?, forceGc: Boolean): MemorySnapshot? =
    readWithin(deviceId, appId, forceGc, budgetMs = finalReadBudgetMs)

  private fun readWithin(deviceId: String, appId: String?, forceGc: Boolean, budgetMs: Long?): MemorySnapshot? {
    val now = clock()
    val readiness = readinessOf(deviceId)
    if (backoffReadinessByDevice[deviceId]?.let { it != readiness } == true) {
      backoffUntilByDevice.remove(deviceId)
      unreachableCountByDevice.remove(deviceId)
      backoffReadinessByDevice.remove(deviceId)
    }
    if (outOfProcessReadinessByDevice[deviceId]?.let { it != readiness } == true) {
      outOfProcessReadinessByDevice.remove(deviceId)
    }
    if (deviceId in outOfProcessReadinessByDevice) return fallback.read(deviceId, appId, forceGc)
    if ((backoffUntilByDevice[deviceId] ?: 0L) > now) return fallback.read(deviceId, appId, forceGc)
    val request = GetMemoryInfoRequest(appId = appId, forceGc = forceGc)
    val result = if (budgetMs == null) rpc(deviceId, request) else rpcWithin(budgetMs) { rpc(deviceId, request) }
    return when (result) {
      is RpcResult.Success -> {
        unreachableCountByDevice.remove(deviceId)
        if (result.data.inProcess) {
          if (!announced) {
            announced = true
            Console.log("[memory-capture] reading memory through the in-process runner on $deviceId")
          }
        } else {
          outOfProcessReadinessByDevice[deviceId] = readiness
          Console.log(
            "[memory-capture] the on-device runner on $deviceId runs outside the app's process; " +
              "reading memory over adb so readings never hold the runner's UiAutomation lock",
          )
        }
        toSnapshot(result.data)
      }
      is RpcResult.Failure -> {
        val misses = (unreachableCountByDevice[deviceId] ?: 0) + 1
        unreachableCountByDevice[deviceId] = misses
        val giveUp = tooOldForThisRequest(result) || misses >= MAX_UNREACHABLE_ATTEMPTS
        val until = if (giveUp) Long.MAX_VALUE else now + backoffMs
        backoffUntilByDevice[deviceId] = until
        backoffReadinessByDevice[deviceId] = readiness
        Console.log(
          "[memory-capture] on-device runner unavailable on $deviceId (${result.errorType}: ${result.message}); " +
            "reading memory over adb" + if (until == Long.MAX_VALUE) " for this session" else " for the next ${backoffMs / 1000}s",
        )
        fallback.read(deviceId, appId, forceGc)
      }
    }
  }

  /**
   * [call] with a deadline. The call blocks a thread, so it runs interruptibly and is interrupted at
   * the deadline: the real runner call is a `runBlocking`, which an interrupt cancels.
   */
  private fun rpcWithin(
    budgetMs: Long,
    call: () -> RpcResult<GetMemoryInfoResponse>,
  ): RpcResult<GetMemoryInfoResponse> = runBlocking {
    withTimeoutOrNull(budgetMs) { runInterruptible(Dispatchers.IO) { call() } }
  } ?: RpcResult.Failure(
    errorType = RpcResult.ErrorType.NETWORK_ERROR,
    message = "no answer within ${budgetMs}ms",
  )

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
    private val readinessByDevice = ConcurrentHashMap<String, Long>()

    private fun readinessOf(deviceId: String): Long = readinessByDevice[deviceId] ?: 0L

    /**
     * Says [deviceId]'s runner just connected. Every probe then asks it again on its next reading,
     * rather than waiting out a backoff set while the runner was still starting. That includes one
     * a runner too old for the request set: the connect may have replaced it with one that knows it.
     */
    fun runnerReady(deviceId: String) {
      readinessByDevice.merge(deviceId, 1L, Long::plus)
    }

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

    /**
     * How long the closing reading waits for the runner before reading over adb. A healthy runner
     * answers in a few hundred milliseconds (about 0.1 s standalone, 0.4 s in-process).
     */
    const val FINAL_READ_BUDGET_MS: Long = 1_500

    /** Consecutive unreachable attempts after which the runner is not asked again by this probe. */
    const val MAX_UNREACHABLE_ATTEMPTS: Int = 3

    /** The status code inside an HTTP failure's message, which carries it as plain text. */
    private val HTTP_STATUS = Regex("HTTP (\\d{3})")

    /** The runner's reply as the capture's snapshot; see [OnDeviceMemorySnapshot.from]. */
    fun toSnapshot(response: GetMemoryInfoResponse): MemorySnapshot = OnDeviceMemorySnapshot.from(response)
  }
}
