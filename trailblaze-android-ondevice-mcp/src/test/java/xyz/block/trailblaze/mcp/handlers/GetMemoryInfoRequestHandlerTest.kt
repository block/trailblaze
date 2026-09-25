package xyz.block.trailblaze.mcp.handlers

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import xyz.block.trailblaze.mcp.android.ondevice.rpc.GetMemoryInfoRequest
import xyz.block.trailblaze.mcp.android.ondevice.rpc.RpcResult

/**
 * JVM tests for [GetMemoryInfoRequestHandler] through its device seam: the handler's job is to
 * decide HOW to read (in-process `Runtime` + `System.gc()` versus shell `pidof` / `run-as kill` /
 * `dumpsys`) and to hand the host raw text plus the exact figures only the device can know.
 */
class GetMemoryInfoRequestHandlerTest {

  private class FakeDevice(
    override val targetPackage: String? = "xyz.block.trailblaze.runner",
    var appPid: Int? = 17220,
    var debuggable: Boolean = true,
    var shellThrows: Boolean = false,
    /** How long each command "takes", on this fake's own clock, so a budget test costs no wall time. */
    var msPerCommand: Long = 0,
  ) : GetMemoryInfoRequestHandler.DeviceMemoryReader {
    override val ownPid: Int = 4242
    val commands = mutableListOf<String>()
    val bounds = mutableListOf<Long>()
    var gcCalls = 0
    private var clock = 0L

    override fun elapsedMs(): Long = clock

    override fun shell(command: String, timeoutMs: Long): String {
      if (shellThrows) throw IllegalStateException("UiAutomation dead")
      commands += command
      bounds += timeoutMs
      clock += msPerCommand
      return when {
        command == "cat /proc/meminfo" -> "MemTotal: 2012188 kB\nMemAvailable: 360660 kB\n"
        command.startsWith("pidof ") -> appPid?.toString().orEmpty()
        command.startsWith("dumpsys meminfo ") -> "** MEMINFO in pid ${command.substringAfterLast(' ')} **\n TOTAL PSS: 36940\n"
        command.startsWith("run-as ") && command.contains(" kill -10 ") -> ""
        else -> error("unexpected: $command")
      }
    }

    override fun collectGarbage() {
      gcCalls++
    }

    override fun runtimeKb(): Triple<Long, Long, Long> = Triple(65_536, 20_480, 196_608)
    override fun memoryClassesMb(): Pair<Int, Int>? = 192 to 512
    override fun isLargeHeap(appId: String): Boolean? = appId == "com.example.large"
    override fun isDebuggable(appId: String): Boolean? = debuggable
  }

  private fun handle(device: FakeDevice, request: GetMemoryInfoRequest) =
    runBlocking { GetMemoryInfoRequestHandler(device).handle(request) }

  @Test
  fun `a standalone runner reads another app through the shell and signals it for a GC`() {
    val device = FakeDevice()
    val result = handle(device, GetMemoryInfoRequest(appId = "com.example.app"))
    assertTrue(result is RpcResult.Success)
    val response = result.data
    assertEquals(17220, response.pid)
    assertFalse(response.inProcess)
    assertEquals(true, response.gcForced)
    assertNull(response.runtimeTotalKb, "another process's Runtime is not readable")
    assertEquals(192, response.memoryClassMb)
    assertEquals(512, response.largeMemoryClassMb)
    assertEquals(false, response.largeHeap)
    assertTrue(response.dumpsysMeminfo!!.contains("pid 17220"))
    assertTrue(response.procMeminfo!!.contains("MemTotal"))
    assertEquals(0, device.gcCalls)
    // One plain command: UiAutomation execs it directly, so no shell operators are available.
    assertTrue(device.commands.contains("run-as com.example.app kill -10 17220"), device.commands.toString())
    assertTrue(device.commands.none { it.contains("&&") || it.contains("||") || it.contains("(") }, device.commands.toString())
    // The signal goes out before the dump is taken.
    assertTrue(device.commands.indexOfFirst { it.contains("kill -10") } < device.commands.indexOfFirst { it.startsWith("dumpsys") })
  }

  @Test
  fun `a runner sharing the app's process collects garbage itself and reports the app's own Runtime`() {
    val device = FakeDevice(targetPackage = "com.example.app")
    val response = (handle(device, GetMemoryInfoRequest(appId = "com.example.app")) as RpcResult.Success).data
    assertTrue(response.inProcess)
    assertEquals(4242, response.pid, "its own pid, no pidof needed")
    assertEquals(1, device.gcCalls)
    assertEquals(true, response.gcForced)
    assertEquals(65_536, response.runtimeTotalKb)
    assertEquals(20_480, response.runtimeFreeKb)
    assertEquals(196_608, response.runtimeMaxKb)
    assertTrue(device.commands.none { it.startsWith("pidof") || it.contains("kill") }, device.commands.toString())
  }

  @Test
  fun `a release app is not signalled and reads with gcForced false`() {
    val device = FakeDevice(debuggable = false)
    val response = (handle(device, GetMemoryInfoRequest(appId = "com.example.app")) as RpcResult.Success).data
    assertEquals(false, response.gcForced)
    assertTrue(response.dumpsysMeminfo != null)
    assertTrue(device.commands.none { it.contains("kill") }, "run-as would only fail: ${device.commands}")
  }

  @Test
  fun `forceGc false sends no signal and says so`() {
    val device = FakeDevice()
    val response = (handle(device, GetMemoryInfoRequest(appId = "com.example.app", forceGc = false)) as RpcResult.Success).data
    assertEquals(false, response.gcForced)
    assertTrue(device.commands.none { it.contains("kill") })
  }

  @Test
  fun `an app that is not running yields device memory only`() {
    val device = FakeDevice(appPid = null)
    val response = (handle(device, GetMemoryInfoRequest(appId = "com.example.app")) as RpcResult.Success).data
    assertNull(response.pid)
    assertNull(response.gcForced)
    assertNull(response.dumpsysMeminfo)
    assertTrue(response.procMeminfo!!.contains("MemAvailable"))
  }

  @Test
  fun `no app id reads proc meminfo only`() {
    val device = FakeDevice()
    val response = (handle(device, GetMemoryInfoRequest(appId = null)) as RpcResult.Success).data
    assertNull(response.appId)
    assertNull(response.pid)
    assertEquals(listOf("cat /proc/meminfo"), device.commands)
  }

  @Test
  fun `a largeHeap app is reported as such`() {
    val response = (handle(FakeDevice(), GetMemoryInfoRequest(appId = "com.example.large")) as RpcResult.Success).data
    assertEquals(true, response.largeHeap)
  }

  @Test
  fun `a shell that throws becomes a Failure the host can fall back from`() {
    val result = handle(FakeDevice(shellThrows = true), GetMemoryInfoRequest(appId = "com.example.app"))
    assertTrue(result is RpcResult.Failure)
    assertEquals(RpcResult.ErrorType.UNKNOWN_ERROR, result.errorType)
  }

  @Test
  fun `a wedged reading ends before the host stops waiting for it`() {
    // The shell reads here hold the process-wide UiAutomation monitor, and the read is the only
    // bound that ENDS one — the host giving up at `TIMEOUT_MS` just abandons the reply and leaves
    // the monitor held, queueing the trail's own device actions behind a sample nobody wants. So
    // the read bound has to land first. Both constants live together for exactly this reason;
    // this asserts the order they are kept in.
    assertTrue(
      GetMemoryInfoRequest.SHELL_READ_BUDGET_MS < GetMemoryInfoRequest.TIMEOUT_MS,
      "shell read budget ${GetMemoryInfoRequest.SHELL_READ_BUDGET_MS}ms must be under the " +
        "request timeout ${GetMemoryInfoRequest.TIMEOUT_MS}ms",
    )
  }

  @Test
  fun `the budget covers the whole reading, not each command in it`() {
    // Answering takes up to four sequential commands. Bounding each one separately bounds nothing:
    // four slow reads add up well past the point the host abandoned the request, and every one of
    // those milliseconds is the UiAutomation monitor held away from the trail. Here each command
    // eats most of the budget, so only the first two can run.
    val budget = GetMemoryInfoRequest.SHELL_READ_BUDGET_MS
    val device = FakeDevice(msPerCommand = budget - 1)
    val response = (handle(device, GetMemoryInfoRequest(appId = "com.example.app")) as RpcResult.Success).data

    assertEquals(listOf("cat /proc/meminfo", "pidof com.example.app"), device.commands)
    assertNull(response.dumpsysMeminfo, "the dump is skipped rather than run past the deadline")
    assertEquals(false, response.gcForced, "no collection was signalled, and the answer says so")
    // What did run says so too, rather than the caller having to infer it from an absence.
    assertEquals(17220, response.pid)
    assertTrue(response.procMeminfo!!.contains("MemTotal"))
    // Each command is bounded by what is LEFT, so the sequence cannot outlast the budget.
    assertEquals(listOf(budget, 1L), device.bounds)
  }

  @Test
  fun `a fast reading still makes every call`() {
    // The budget must not be a behaviour change on the ordinary path — a device answering in
    // milliseconds still gets the GC signal and the dump.
    val device = FakeDevice(msPerCommand = 5)
    val response = (handle(device, GetMemoryInfoRequest(appId = "com.example.app")) as RpcResult.Success).data
    assertEquals(true, response.gcForced)
    assertTrue(response.dumpsysMeminfo != null)
    assertEquals(4, device.commands.size, device.commands.toString())
  }

  @Test
  fun `an app id carrying shell metacharacters is never interpolated into a command`() {
    val device = FakeDevice()
    val response = (handle(device, GetMemoryInfoRequest(appId = "x; touch /sdcard/pwn")) as RpcResult.Success).data
    // The id would otherwise land in `pidof <id>` and the `run-as` signal, so the assertion is on
    // the commands the device was actually handed.
    assertEquals(listOf("cat /proc/meminfo"), device.commands)
    assertTrue(device.commands.none { it.contains("touch") }, device.commands.toString())
    // Answered as the no-app reading rather than an error: device memory is still returned.
    assertNull(response.appId)
    assertNull(response.pid)
    assertTrue(response.procMeminfo!!.contains("MemTotal"))
  }
}
