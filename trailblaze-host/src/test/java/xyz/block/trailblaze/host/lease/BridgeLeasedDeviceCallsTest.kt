package xyz.block.trailblaze.host.lease

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.ScreenshotScalingConfig
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.devices.TrailblazeConnectedDeviceSummary
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.mcp.TrailblazeMcpBridge
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.commands.InputTextTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.AssertWaypointTrailblazeTool
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.minutes

/** How the lending machine carries out a borrower's call on its own bridge. */
class BridgeLeasedDeviceCallsTest {

  private val device = TrailblazeDeviceId("emulator-5554", TrailblazeDevicePlatform.ANDROID)

  /**
   * Runs each tool through [onTool]; captures the screen through [capture]; reports its driver's
   * setup through [driverStatus], as the daemon does while a browser downloads and starts.
   */
  private class FakeBridge(
    val onTool: (TrailblazeTool) -> TrailblazeToolResult = { TrailblazeToolResult.Success() },
    val capture: (() -> ScreenState)? = null,
    val driverStatus: () -> String? = { null },
  ) : TrailblazeMcpBridge {
    override fun getDriverConnectionStatus(deviceId: TrailblazeDeviceId?): String? = driverStatus()
    override suspend fun selectDevice(trailblazeDeviceId: TrailblazeDeviceId) =
      TrailblazeConnectedDeviceSummary(TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY, trailblazeDeviceId.instanceId, "")
    override suspend fun getAvailableDevices(): Set<TrailblazeConnectedDeviceSummary> = emptySet()
    override suspend fun getInstalledAppIds(): Set<String> = emptySet()
    override fun getAvailableAppTargets(): Set<TrailblazeHostAppTarget> = emptySet()
    override suspend fun runYaml(yaml: String, startNewSession: Boolean): String = ""
    override fun getCurrentlySelectedDeviceId(): TrailblazeDeviceId? = null
    override suspend fun getCurrentScreenState(): ScreenState? = fail("a borrower's screen read must not use the cached snapshot")
    override fun getDirectScreenStateProvider(skipScreenshot: Boolean): ((ScreenshotScalingConfig) -> ScreenState)? =
      capture?.let { c -> { _ -> c() } }
    override suspend fun executeTrailblazeTool(tool: TrailblazeTool, blocking: Boolean, traceId: xyz.block.trailblaze.logs.model.TraceId?): String = ""
    override suspend fun executeTrailblazeToolForResult(tool: TrailblazeTool, blocking: Boolean, traceId: xyz.block.trailblaze.logs.model.TraceId?) =
      onTool(tool)
    override suspend fun endSession(): Boolean = true
    override fun selectAppTarget(appTargetId: String): String? = null
    override fun getCurrentAppTargetId(): String? = null
  }

  private fun runTool(bridge: TrailblazeMcpBridge, call: RemoteDeviceCall) =
    runBlocking { BridgeLeasedDeviceCalls { bridge }.call(device, call) }

  private val inputText = BridgeLeasedDeviceCalls.encodeTool(InputTextTrailblazeTool(text = "hi"))

  @Test
  fun `a host tool sent for a leased device fails without running on the lender`() {
    var ran: TrailblazeTool? = null
    val bridge = FakeBridge(onTool = { ran = it; TrailblazeToolResult.Success() })

    val reply = runTool(
      bridge,
      RemoteDeviceCall(
        device = device.toFullyQualifiedDeviceId(),
        op = RemoteDeviceCall.Op.RUN_TOOL,
        toolYaml = BridgeLeasedDeviceCalls.encodeTool(AssertWaypointTrailblazeTool(waypoint = "example/android/home")),
      ),
    )

    assertEquals(null, ran, "the lender ran a host tool for a borrower")
    assertEquals(false, reply.toolResult?.success)
    assertTrue(reply.toolResult?.message.orEmpty().contains("device action"), "${reply.toolResult?.message}")
  }

  @Test
  fun `a tool name the lender does not know fails without running, since it could be a host tool of its target`() {
    var ran: TrailblazeTool? = null
    val bridge = FakeBridge(onTool = { ran = it; TrailblazeToolResult.Success() })

    val reply = runTool(
      bridge,
      RemoteDeviceCall(
        device = device.toFullyQualifiedDeviceId(),
        op = RemoteDeviceCall.Op.RUN_TOOL,
        toolYaml = "- myTarget_resetBackend:\n    account: sam\n",
      ),
    )

    assertEquals(null, ran, "the lender ran a tool it could not resolve")
    assertEquals(false, reply.toolResult?.success)
    assertTrue(reply.toolResult?.message.orEmpty().contains("myTarget_resetBackend"), "${reply.toolResult?.message}")
  }

  @Test
  fun `a device action still runs for the borrower`() {
    var ran: TrailblazeTool? = null
    val bridge = FakeBridge(onTool = { ran = it; TrailblazeToolResult.Success() })

    val reply = runTool(
      bridge,
      RemoteDeviceCall(device = device.toFullyQualifiedDeviceId(), op = RemoteDeviceCall.Op.RUN_TOOL, toolYaml = inputText),
    )

    assertEquals(InputTextTrailblazeTool(text = "hi"), ran)
    assertEquals(true, reply.toolResult?.success)
  }

  @Test
  fun `a tool runs against the borrower's memory, and what it remembers goes back`() {
    var seen: Map<String, String>? = null
    var seenSensitive: Set<String>? = null
    val bridge = FakeBridge(
      onTool = {
        val memory = LeasedCall.current()!!.memory
        seen = memory.variables.toMap()
        seenSensitive = memory.sensitiveKeys.toSet()
        memory.remember("total", "$12.50")
        memory.rememberSensitive("otp", "991204")
        memory.delete("user")
        TrailblazeToolResult.Success()
      },
    )

    val reply = runTool(
      bridge,
      RemoteDeviceCall(
        device = device.toFullyQualifiedDeviceId(),
        op = RemoteDeviceCall.Op.RUN_TOOL,
        toolYaml = inputText,
        memory = mapOf("user" to "sam", "pin" to "4921"),
        sensitiveMemoryKeys = listOf("pin"),
      ),
    )

    assertEquals(mapOf("user" to "sam", "pin" to "4921"), seen)
    assertEquals(setOf("pin"), seenSensitive)
    assertEquals(mapOf("pin" to "4921", "total" to "$12.50", "otp" to "991204"), reply.memory)
    assertEquals(listOf("user"), reply.memoryDeletions)
    assertTrue(reply.sensitiveMemoryKeys.containsAll(listOf("pin", "otp")), "secrets stay marked: ${reply.sensitiveMemoryKeys}")
  }

  @Test
  fun `a tool run for a borrower leaves no memory behind for the next one`() {
    var calls = 0
    val bridge = FakeBridge(
      onTool = {
        if (calls++ == 0) LeasedCall.current()!!.memory.remember("left", "behind")
        TrailblazeToolResult.Success()
      },
    )
    runTool(bridge, RemoteDeviceCall(device.toFullyQualifiedDeviceId(), RemoteDeviceCall.Op.RUN_TOOL, toolYaml = inputText))

    val next = runTool(
      bridge,
      RemoteDeviceCall(device.toFullyQualifiedDeviceId(), RemoteDeviceCall.Op.RUN_TOOL, toolYaml = inputText, memory = mapOf("a" to "b")),
    )

    assertEquals(mapOf("a" to "b"), next.memory)
    assertEquals(null, LeasedCall.current(), "nothing outside a borrower's call sees its memory")
  }

  @Test
  fun `each screen read is a new capture, never the snapshot from the last tool`() {
    var captures = 0
    val bridge = FakeBridge(
      capture = {
        captures++
        RemoteScreenState(
          platform = TrailblazeDevicePlatform.ANDROID,
          deviceWidth = 1080,
          deviceHeight = 2400,
          viewHierarchy = ViewHierarchyTreeNode(text = "screen $captures"),
        ).toScreenState()
      },
    )
    val read = RemoteDeviceCall(device.toFullyQualifiedDeviceId(), RemoteDeviceCall.Op.SCREEN_STATE)

    val first = runTool(bridge, read).screenState!!
    val second = runTool(bridge, read).screenState!!

    assertEquals("screen 1", first.viewHierarchy.text)
    assertEquals("screen 2", second.viewHierarchy.text)
  }

  private val web = TrailblazeDeviceId("playwright-native", TrailblazeDevicePlatform.WEB)

  private val webRead = RemoteDeviceCall(web.toFullyQualifiedDeviceId(), RemoteDeviceCall.Op.SCREEN_STATE)

  private fun page(text: String): ScreenState = RemoteScreenState(
    platform = TrailblazeDevicePlatform.WEB,
    deviceWidth = 1280,
    deviceHeight = 800,
    viewHierarchy = ViewHierarchyTreeNode(text = text),
  ).toScreenState()

  @Test
  fun `a first screen read waits for the lender's browser to finish starting`() = runTest {
    var started = false
    var polls = 0
    val bridge = FakeBridge(
      capture = { if (started) page("example.com") else fail("captured before the browser was ready") },
      driverStatus = {
        if (++polls < 4) "Playwright browser installing (${polls}s elapsed): [40%] chromium" else null.also { started = true }
      },
    )

    val reply = BridgeLeasedDeviceCalls { bridge }.call(web, webRead)

    assertEquals("example.com", reply.screenState!!.toScreenState().viewHierarchy.text)
  }

  @Test
  fun `a browser that fails to start fails the call with the lender's reason`() = runTest {
    var polls = 0
    val bridge = FakeBridge(
      capture = { fail("captured from a browser that never started") },
      driverStatus = {
        if (++polls < 3) "Playwright browser installing (1s elapsed): [10%] chromium" else "Playwright browser installation failed: disk full"
      },
    )

    val failure = assertFailsWith<IllegalStateException> { BridgeLeasedDeviceCalls { bridge }.call(web, webRead) }

    assertTrue("installation failed: disk full" in failure.message.orEmpty(), failure.message)
  }

  @Test
  fun `a browser still starting at the limit fails the call, and the next call waits again`() = runTest {
    var ready = false
    val bridge = FakeBridge(
      capture = { page("example.com") },
      driverStatus = { if (ready) null else "Playwright browser installing (600s elapsed): [95%] chromium" },
    )
    val calls = BridgeLeasedDeviceCalls(readyTimeout = 1.minutes) { bridge }

    val failure = assertFailsWith<IllegalStateException> { calls.call(web, webRead) }
    assertTrue("still starting" in failure.message.orEmpty(), failure.message)

    ready = true
    assertEquals("example.com", calls.call(web, webRead).screenState!!.toScreenState().viewHierarchy.text)
  }

  @Test
  fun `a status that is not setup under way is left to the call, as for the lender's own`() = runTest {
    val bridge = FakeBridge(
      capture = { page("example.com") },
      driverStatus = { "Device emulator-5554 is not connected" },
    )

    assertEquals("example.com", BridgeLeasedDeviceCalls { bridge }.call(web, webRead).screenState!!.toScreenState().viewHierarchy.text)
  }
}
