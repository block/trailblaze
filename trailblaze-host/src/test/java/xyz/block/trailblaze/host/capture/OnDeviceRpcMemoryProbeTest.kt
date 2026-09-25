package xyz.block.trailblaze.host.capture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import xyz.block.trailblaze.capture.memory.MemoryChangeDetector
import xyz.block.trailblaze.capture.memory.MemoryProbe
import xyz.block.trailblaze.capture.memory.MemorySnapshot
import xyz.block.trailblaze.mcp.android.ondevice.rpc.GetMemoryInfoRequest
import xyz.block.trailblaze.mcp.android.ondevice.rpc.GetMemoryInfoResponse
import xyz.block.trailblaze.mcp.android.ondevice.rpc.RpcResult
import xyz.block.trailblaze.transport.AndroidWireTransportMode

class OnDeviceRpcMemoryProbeTest {

  private val dump = """
    ** MEMINFO in pid 17220 [com.example.app] **
                       Pss  Private  Private  SwapPss      Rss     Heap     Heap     Heap
                     Total    Dirty    Clean    Dirty    Total     Size    Alloc     Free
                    ------   ------   ------   ------   ------   ------   ------   ------
      Native Heap     5219     5168        8       85     6148    11612     4779     1243
      Dalvik Heap      780      692       20      246     1696     2923     2193      730
            TOTAL    36940     8104    24804      728    97060    14535     6972     1973

     App Summary
                           Pss(KB)                        Rss(KB)
               Java Heap:     1112                          11780
             Native Heap:     5168                           6148

               TOTAL PSS:    36940            TOTAL RSS:    97060       TOTAL SWAP PSS:      728

     Objects
             AppContexts:        3           Activities:        1
  """.trimIndent()

  private val proc = "MemTotal:        2012188 kB\nMemAvailable:     360660 kB\n"

  private fun response(inProcess: Boolean) = GetMemoryInfoResponse(
    appId = "com.example.app",
    pid = 17220,
    inProcess = inProcess,
    gcForced = true,
    runtimeTotalKb = if (inProcess) 65_536 else null,
    runtimeFreeKb = if (inProcess) 20_480 else null,
    runtimeMaxKb = if (inProcess) 196_608 else null,
    memoryClassMb = 192,
    largeMemoryClassMb = 512,
    largeHeap = false,
    dumpsysMeminfo = dump,
    procMeminfo = proc,
  )

  private class FakeFallback : MemoryProbe {
    val reads = mutableListOf<String?>()
    var closed = false
    override fun read(deviceId: String, appId: String?, forceGc: Boolean): MemorySnapshot? {
      reads += appId
      return MemorySnapshot(timeMs = 0, appId = appId, pid = null, app = null, device = null, source = MemoryProbe.SOURCE_ADB)
    }
    override fun close() {
      closed = true
    }
  }

  @Test
  fun `a runner reply becomes an on-device snapshot with the dump parsed and exact limits`() {
    val fallback = FakeFallback()
    val probe = OnDeviceRpcMemoryProbe(fallback = fallback, rpc = { _, _ -> RpcResult.Success(response(inProcess = false)) })
    val snapshot = assertNotNull(probe.read("emulator-5554", "com.example.app", forceGc = true))
    assertEquals(MemoryProbe.SOURCE_ONDEVICE, snapshot.source)
    assertEquals(17220, snapshot.pid)
    assertEquals(36_940, snapshot.app?.totalPssKb)
    assertEquals(2923 - 730, snapshot.app?.javaHeapUsedKb, "heap used from the dump's Dalvik Heap row")
    assertEquals(true, snapshot.gcForced)
    assertEquals(192 * 1024L, snapshot.limits?.heapLimitKb, "memory class is the limit without largeHeap")
    assertEquals(2_012_188, snapshot.device?.memTotalKb)
    assertTrue(fallback.reads.isEmpty(), "adb is not touched while the runner answers")
  }

  @Test
  fun `an in-process runner's Runtime figures override the dump's heap and set the exact limit`() {
    val probe = OnDeviceRpcMemoryProbe(fallback = FakeFallback(), rpc = { _, _ -> RpcResult.Success(response(inProcess = true)) })
    val snapshot = assertNotNull(probe.read("emulator-5554", "com.example.app", forceGc = true))
    assertEquals(65_536 - 20_480, snapshot.app?.javaHeapUsedKb, "Runtime.totalMemory − freeMemory")
    assertEquals(196_608, snapshot.limits?.heapLimitKb, "Runtime.maxMemory")
    assertEquals(196_608, snapshot.limits?.runtimeMaxKb)
  }

  @Test
  fun `an in-process reply whose dump never arrived still carries the app's heap`() {
    // The shell budget can expire before `dumpsys` returns, and the runner answers anyway — its
    // `Runtime` figures need no dump. Dropping the reading for the missing dump put a hole in the
    // app's memory series right where the heap number was available and exact.
    val noDump = response(inProcess = true).copy(dumpsysMeminfo = null)
    val probe = OnDeviceRpcMemoryProbe(fallback = FakeFallback(), rpc = { _, _ -> RpcResult.Success(noDump) })
    val snapshot = assertNotNull(probe.read("emulator-5554", "com.example.app", forceGc = true))
    assertEquals(65_536 - 20_480, snapshot.app?.javaHeapUsedKb, "Runtime.totalMemory − freeMemory")
    assertEquals(196_608, snapshot.limits?.heapLimitKb, "Runtime.maxMemory, which also needs no dump")
    assertEquals(17220, snapshot.pid, "the runner resolved the pid, so the app is still known to be alive")
    // Only the dump's own figures are missing, and the report reads none of them.
    assertNull(snapshot.app?.totalPssKb, "there is no PSS to report without a dump")
    assertEquals(360_660, snapshot.device?.memAvailableKb, "the device reading is independent of the app dump")
  }

  @Test
  fun `an out-of-process reply whose dump never arrived has no app sample to invent`() {
    // Without the dump AND without Runtime access there is no heap figure at all, so the reading
    // must stay app-less rather than be filled in with zeroes.
    val noDump = response(inProcess = false).copy(dumpsysMeminfo = null)
    val probe = OnDeviceRpcMemoryProbe(fallback = FakeFallback(), rpc = { _, _ -> RpcResult.Success(noDump) })
    val snapshot = assertNotNull(probe.read("emulator-5554", "com.example.app", forceGc = true))
    assertNull(snapshot.app)
    assertNull(snapshot.limits)
  }

  @Test
  fun `the request carries the app id and the GC choice`() {
    var seen: GetMemoryInfoRequest? = null
    val probe = OnDeviceRpcMemoryProbe(fallback = FakeFallback(), rpc = { _, request -> seen = request; RpcResult.Success(response(false)) })
    probe.read("emulator-5554", "com.example.app", forceGc = false)
    assertEquals(GetMemoryInfoRequest(appId = "com.example.app", forceGc = false), seen)
  }

  @Test
  fun `a runner that is not running yields the app as not running rather than a bogus pid`() {
    val idle = GetMemoryInfoResponse(appId = "com.example.app", pid = null, inProcess = false, gcForced = null, procMeminfo = proc)
    val snapshot = OnDeviceRpcMemoryProbe.toSnapshot(idle)
    assertNull(snapshot.pid)
    assertNull(snapshot.app)
    assertNull(snapshot.limits)
    assertEquals(360_660, snapshot.device?.memAvailableKb)
  }

  @Test
  fun `a dump the parser cannot read keeps the runner's pid rather than reporting a death`() {
    val unreadable = response(inProcess = false).copy(
      dumpsysMeminfo = "meminfo: a shape this parser does not recognise\n",
    )
    val snapshot = OnDeviceRpcMemoryProbe.toSnapshot(unreadable)
    assertEquals(17220, snapshot.pid, "the runner resolved the pid, so the app is alive")
    assertNull(snapshot.app, "nothing parseable to report")
    assertNull(
      MemoryChangeDetector.changeReason(OnDeviceRpcMemoryProbe.toSnapshot(response(inProcess = false)), snapshot),
      "an unreadable dump must not be reported as the process dying",
    )
  }

  @Test
  fun `no reachable runner falls back to adb and is not retried until the backoff passes`() {
    var now = 1_000_000L
    var rpcCalls = 0
    val fallback = FakeFallback()
    val probe = OnDeviceRpcMemoryProbe(
      fallback = fallback,
      rpc = { _, _ -> rpcCalls++; RpcResult.Failure(RpcResult.ErrorType.NETWORK_ERROR, "connection refused") },
      clock = { now },
      backoffMs = 60_000,
    )
    repeat(3) { assertEquals(MemoryProbe.SOURCE_ADB, probe.read("emulator-5554", "com.example.app", true)?.source) }
    assertEquals(1, rpcCalls, "one failed connection, then adb for the backoff window")
    assertEquals(3, fallback.reads.size)
    now += 60_001
    probe.read("emulator-5554", "com.example.app", true)
    assertEquals(2, rpcCalls, "the runner is tried again after the backoff")
  }

  @Test
  fun `a runner that stays unreachable is given up on after three misses`() {
    var now = 1_000_000L
    var rpcCalls = 0
    val probe = OnDeviceRpcMemoryProbe(
      fallback = FakeFallback(),
      rpc = { _, _ -> rpcCalls++; RpcResult.Failure(RpcResult.ErrorType.NETWORK_ERROR, "connection refused") },
      clock = { now },
      backoffMs = 15_000,
    )
    repeat(3) {
      probe.read("emulator-5554", "com.example.app", true)
      now += 15_001
    }
    assertEquals(3, rpcCalls)
    now += 3_600_000
    probe.read("emulator-5554", "com.example.app", true)
    assertEquals(3, rpcCalls, "after three misses in a row the runner is not asked again")
  }

  @Test
  fun `a reply resets the unreachable count`() {
    var now = 1_000_000L
    var fail = true
    var rpcCalls = 0
    val probe = OnDeviceRpcMemoryProbe(
      fallback = FakeFallback(),
      rpc = { _, _ ->
        rpcCalls++
        if (fail) RpcResult.Failure(RpcResult.ErrorType.NETWORK_ERROR, "starting") else RpcResult.Success(response(false))
      },
      clock = { now },
      backoffMs = 15_000,
    )
    repeat(2) { probe.read("emulator-5554", "com.example.app", true); now += 15_001 }
    fail = false
    assertEquals(MemoryProbe.SOURCE_ONDEVICE, probe.read("emulator-5554", "com.example.app", true)?.source)
    fail = true
    repeat(2) { probe.read("emulator-5554", "com.example.app", true); now += 15_001 }
    assertEquals(5, rpcCalls, "two misses, a reply, then two more misses — none of them the third strike")
    probe.read("emulator-5554", "com.example.app", true)
    assertEquals(6, rpcCalls, "still asking: the reply reset the count")
  }

  @Test
  fun `a runner too old to know the request is not asked again`() {
    var now = 1_000_000L
    var rpcCalls = 0
    val probe = OnDeviceRpcMemoryProbe(
      fallback = FakeFallback(),
      // The shape the client actually produces for a 404 off the catch-all route.
      rpc = { _, _ ->
        rpcCalls++
        RpcResult.Failure(RpcResult.ErrorType.HTTP_ERROR, "Server error during RPC call: HTTP 404: Not Found")
      },
      clock = { now },
      backoffMs = 60_000,
    )
    probe.read("emulator-5554", "com.example.app", true)
    now += 3_600_000
    probe.read("emulator-5554", "com.example.app", true)
    assertEquals(1, rpcCalls)
  }

  @Test
  fun `one device-side error does not cost the session the runner's readings`() {
    // A handler-side exception comes back as 500, the same HTTP_ERROR type as the 404 that means
    // "this runner will never know this request". Reading the type alone gives up for the session
    // on a failure that may well be over by the next sample — and the in-process `Runtime` figures
    // this probe exists for are exactly what is lost. One 500 is a strike; three are a give-up.
    var now = 1_000_000L
    var rpcCalls = 0
    var failing = true
    val probe = OnDeviceRpcMemoryProbe(
      fallback = FakeFallback(),
      rpc = { _, _ ->
        rpcCalls++
        if (failing) {
          RpcResult.Failure(RpcResult.ErrorType.HTTP_ERROR, "Server error during RPC call: HTTP 500: Internal Server Error")
        } else {
          RpcResult.Success(response(false))
        }
      },
      clock = { now },
      backoffMs = 60_000,
    )
    probe.read("emulator-5554", "com.example.app", true)
    failing = false
    now += 60_001

    val snapshot = probe.read("emulator-5554", "com.example.app", true)
    assertEquals(2, rpcCalls, "the runner is asked again once the backoff passes")
    assertEquals(MemoryProbe.SOURCE_ONDEVICE, snapshot?.source, "and its answer is used")
  }

  @Test
  fun `a background reading may not repair the device transport`() {
    // The ordinary failure here is "the runner is not listening", which arrives as an IOException —
    // and the client's default response to that is to tear down and re-establish the adb forward
    // and evict the shared adb client for the serial. On behalf of a sample nobody is waiting for,
    // that pulls the transport out from under the trail running on the same device, mid-step.
    val client = OnDeviceRpcMemoryProbe(fallback = FakeFallback()).newClient("emulator-5554")
    assertEquals(
      false,
      client.repairTransportOnNetworkError,
      "a background diagnostic must take the failure and fall back, not heal the device",
    )
    client.close()
  }

  @Test
  fun `closing the probe closes the fallback too`() {
    val fallback = FakeFallback()
    OnDeviceRpcMemoryProbe(fallback = fallback, rpc = { _, _ -> RpcResult.Success(response(false)) }).close()
    assertTrue(fallback.closed)
  }

  @Test
  fun `the reading is pinned to the wire the binary codec can carry`() {
    // `OnDeviceRpcProtoCodec` has no mapping for GetMemoryInfoRequest, and strict protobuf mode
    // turns the client's HTTP fallback into a failure — so on the environment switch this RPC would
    // fail three times and the probe would drop to adb for the session. The pin is what stops that,
    // and it has to hold even when the switch says protobuf.
    assertEquals(AndroidWireTransportMode.JSON, OnDeviceRpcMemoryProbe.WIRE_TRANSPORT_MODE)
    val client = OnDeviceRpcMemoryProbe(fallback = FakeFallback()).newClient("emulator-5554")
    assertEquals(
      AndroidWireTransportMode.JSON,
      client.wireTransportMode,
      "the client this probe builds must not read the wire off the environment switch",
    )
    client.close()
  }
}
