package xyz.block.trailblaze.mcp.newtools

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Test
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.ScreenshotScalingConfig
import xyz.block.trailblaze.devices.TrailblazeConnectedDeviceSummary
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.mcp.AgentImplementation
import xyz.block.trailblaze.mcp.DeviceClaimRegistry
import xyz.block.trailblaze.mcp.McpDeviceContext
import xyz.block.trailblaze.mcp.TrailblazeMcpBridge
import xyz.block.trailblaze.mcp.TrailblazeMcpSessionContext
import xyz.block.trailblaze.mcp.android.ondevice.rpc.GetScreenStateResponse
import xyz.block.trailblaze.mcp.models.McpSessionId
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.toolcalls.TrailblazeTool

/**
 * Pins the handover `switchDevice` performs on an MCP session.
 *
 * The load-bearing assertion in every success case is that three things move together: the roster's
 * ACTIVE name, [TrailblazeMcpSessionContext.associatedDeviceId] (what every subsequent `tools/call`
 * routes by), and the bridge's device selection. Any two of the three agreeing while the third lags
 * means the agent is told it switched and then drives the other device.
 *
 * Devices are bound through the real `device(action=BIND)` tool rather than by poking the session, so
 * these cover the bind → switch path an agent actually walks.
 */
class MultiDeviceToolSetTest {

  private val seller = androidDevice("emulator-5554")
  private val buyer = androidDevice("emulator-5556")

  @Test
  fun `a switch moves the active name, the routing id, and the bridge together`() = runBlocking {
    val f = fixture()
    f.bind("seller", seller.instanceId)
    f.bind("buyer", buyer.instanceId)

    val response = f.switchDevice("buyer")

    assertTrue(response.startsWith("Switched active device from 'seller' to 'buyer'"), response)
    assertEquals("buyer", f.sessionContext.activeDeviceName())
    assertEquals(buyer.trailblazeDeviceId, f.sessionContext.associatedDeviceId)
    assertEquals(buyer.trailblazeDeviceId, f.bridge.selected.last())
    assertEquals(buyer.trailblazeDeviceId, f.bridge.sessionSelected.last())
  }

  /**
   * The devices can differ in driver and per-device target, so the whole target-scoped tool surface
   * is resolved against the active one. Without the re-registration the client keeps calling the
   * PREVIOUS device's tools against the new device.
   */
  @Test
  fun `a switch re-registers the session's tools`() = runBlocking {
    val f = fixture()
    f.bind("seller", seller.instanceId)
    f.bind("buyer", buyer.instanceId)

    f.switchDevice("buyer")

    assertEquals(1, f.toolRefreshes)
  }

  @Test
  fun `switching to the already-active device is a no-op`() = runBlocking {
    val f = fixture()
    f.bind("seller", seller.instanceId)
    f.bind("buyer", buyer.instanceId)
    val selectionsBefore = f.bridge.selected.size

    val response = f.switchDevice("seller")

    assertTrue(response.contains("already active"), response)
    assertEquals("seller", f.sessionContext.activeDeviceName())
    assertEquals(selectionsBefore, f.bridge.selected.size, "a no-op must not re-select the device")
    assertEquals(0, f.toolRefreshes, "a no-op must not churn the client's tool list")
  }

  @Test
  fun `an unknown name errors, lists the bound names, and changes nothing`() = runBlocking {
    val f = fixture()
    f.bind("seller", seller.instanceId)
    f.bind("buyer", buyer.instanceId)

    val response = f.switchDevice("kitchen")

    assertTrue(response.startsWith("Error:"), response)
    assertTrue("seller" in response && "buyer" in response, "must list the bound names: $response")
    assertEquals("seller", f.sessionContext.activeDeviceName())
    assertEquals(seller.trailblazeDeviceId, f.sessionContext.associatedDeviceId)
  }

  /** Reachable from a client holding a stale tool list, so the error has to say how to bind. */
  @Test
  fun `a session with no bindings is told how to bind`() = runBlocking {
    val f = fixture()

    val response = f.switchDevice("buyer")

    assertTrue(response.startsWith("Error:"), response)
    assertTrue("BIND" in response, "must point at the bind action: $response")
  }

  @Test
  fun `a blank name errors`() = runBlocking {
    val f = fixture()
    f.bind("seller", seller.instanceId)
    f.bind("buyer", buyer.instanceId)

    val response = f.switchDevice("  ")

    assertTrue(response.startsWith("Error:"), response)
    assertEquals("seller", f.sessionContext.activeDeviceName())
  }

  /**
   * If the device can't be reached the session must stay where it was. Reporting a failed switch
   * while leaving the session pointed at the unreachable device would send every following tool call
   * to a device with no driver.
   */
  @Test
  fun `a failed device selection hands the session back`() = runBlocking {
    val f = fixture()
    f.bind("seller", seller.instanceId)
    f.bind("buyer", buyer.instanceId)
    f.bridge.failSelectFor = buyer.trailblazeDeviceId

    val response = f.switchDevice("buyer")

    assertTrue(response.startsWith("Error:"), response)
    assertTrue("Still active: seller" in response, response)
    assertEquals("seller", f.sessionContext.activeDeviceName())
    assertEquals(seller.trailblazeDeviceId, f.sessionContext.associatedDeviceId)
    assertEquals(0, f.toolRefreshes, "a failed switch must not re-register tools")
    assertEquals(
      seller.trailblazeDeviceId,
      f.bridge.sessionSelected.last(),
      "the bridge is pointed at the device before the connect fails, so it must be handed back too",
    )
  }

  /**
   * `selectDevice` can succeed off cached device state that outlived the device — an emulator that
   * dropped off adb while it sat inactive. Committing the switch and appending the bad news to a
   * success message would leave every following tool call routed at a device with no driver.
   */
  @Test
  fun `a device with a dead driver is refused, not made active`() = runBlocking {
    val f = fixture()
    f.bind("seller", seller.instanceId)
    f.bind("buyer", buyer.instanceId)
    f.bridge.driverStatuses[buyer.trailblazeDeviceId] =
      "Android device 'emulator-5556' is no longer connected to adb. Reconnect it and retry."

    val response = f.switchDevice("buyer")

    assertTrue(response.startsWith("Error:"), response)
    assertTrue("no longer connected to adb" in response, response)
    assertEquals("seller", f.sessionContext.activeDeviceName())
    assertEquals(seller.trailblazeDeviceId, f.sessionContext.associatedDeviceId)
    assertEquals(seller.trailblazeDeviceId, f.bridge.sessionSelected.last())
    assertEquals(0, f.toolRefreshes, "a refused switch must not re-register tools")
  }

  /** A driver on its way up is not a dead one: the switch commits and reports the status. */
  @Test
  fun `a still-initializing driver does not block the switch`() = runBlocking {
    val f = fixture()
    f.bind("seller", seller.instanceId)
    f.bind("buyer", buyer.instanceId)
    f.bridge.driverStatuses[buyer.trailblazeDeviceId] =
      "Device driver is still initializing (3s elapsed). Try again shortly."

    val response = f.switchDevice("buyer")

    assertTrue(response.startsWith("Switched active device"), response)
    assertTrue("still initializing" in response, "the status is still worth reporting: $response")
    assertEquals("buyer", f.sessionContext.activeDeviceName())
    assertEquals(buyer.trailblazeDeviceId, f.sessionContext.associatedDeviceId)
  }

  /**
   * Kotlin's `CancellationException` is an `Exception`, so a cancelled `tools/call` would otherwise
   * be reported as an unreachable device — and the caller that cancelled would never see it.
   */
  @Test
  fun `a cancelled selection propagates instead of reading as an unreachable device`() = runBlocking {
    val f = fixture()
    f.bind("seller", seller.instanceId)
    f.bind("buyer", buyer.instanceId)
    f.bridge.cancelSelectFor = buyer.trailblazeDeviceId

    assertFailsWith<kotlin.coroutines.cancellation.CancellationException> {
      f.switchDevice("buyer")
    }
    assertEquals("seller", f.sessionContext.activeDeviceName())
  }

  // ---- helpers ---------------------------------------------------------------------------------

  /**
   * A handover commits in two steps around a suspension: the active name first, the routing id
   * after `selectDevice` returns. Two overlapping handovers in one MCP session can each commit one
   * half, leaving the session driving one device while reporting the other — the failure this whole
   * roster exists to prevent. MCP runs each `tools/call` independently, so the client can do this.
   */
  @Test
  fun `overlapping handovers cannot split the active name from the routing id`() = runBlocking {
    val f = fixture()
    f.bind("seller", seller.instanceId)
    f.bind("buyer", buyer.instanceId)
    f.bridge.blockSelectFor = buyer.trailblazeDeviceId

    val toBuyer = launch(Dispatchers.IO) { f.switchDevice("buyer") }
    withTimeout(SWITCH_TIMEOUT_MS) { f.bridge.selectStarted.await() }
    val toSeller = launch(Dispatchers.IO) { f.switchDevice("seller") }
    f.bridge.releaseSelect.complete(Unit)
    withTimeout(SWITCH_TIMEOUT_MS) { toBuyer.join(); toSeller.join() }

    val activeName = f.sessionContext.activeDeviceName()
    assertEquals(
      f.sessionContext.boundDevice(activeName!!)?.trailblazeDeviceId,
      f.sessionContext.associatedDeviceId,
      "the ACTIVE name and the id every subsequent tools/call routes by must name one device",
    )
  }

  /**
   * A `switchDevice` isn't the only writer of that pair: BIND, UNBIND and a replacing CONNECT all
   * move the roster and the routing id too, and MCP will dispatch one of them while a handover is
   * suspended selecting its device. An unbind promoting another name in that window leaves the
   * session reporting the promoted device and driving the one it just released.
   */
  @Test
  fun `an unbind cannot interleave with a handover`() = runBlocking {
    val f = fixture()
    f.bind("seller", seller.instanceId)
    f.bind("buyer", buyer.instanceId)
    f.bridge.blockSelectFor = buyer.trailblazeDeviceId

    val toBuyer = launch(Dispatchers.IO) { f.switchDevice("buyer") }
    withTimeout(SWITCH_TIMEOUT_MS) { f.bridge.selectStarted.await() }
    val unbind = launch(Dispatchers.IO) { f.unbind("buyer") }
    // The unbind has to be shut out of the window, not merely finish after it: everything it
    // touches is in-memory, so left unserialized it runs to completion here.
    repeat(50) { delay(2) }
    assertTrue(
      unbind.isActive,
      "an unbind must wait for a handover that is mid-flight, not promote a name underneath it",
    )

    f.bridge.releaseSelect.complete(Unit)
    withTimeout(SWITCH_TIMEOUT_MS) { toBuyer.join(); unbind.join() }

    val activeName = f.sessionContext.activeDeviceName()
    assertEquals(
      f.sessionContext.boundDevice(activeName!!)?.trailblazeDeviceId,
      f.sessionContext.associatedDeviceId,
      "the ACTIVE name and the id every subsequent tools/call routes by must name one device",
    )
  }

  /**
   * A device move ends in a bridge connect, and a cold one — a browser downloading Chromium, an
   * instrumentation APK installing — holds the turn for minutes. An MCP client gets no progress from
   * a `tools/call`, so waiting it out reads as a hung tool; the blocked caller is told what is in
   * flight and that it can retry, and the roster it couldn't touch is left alone.
   */
  @Test
  fun `a device move that cannot get its turn reports instead of waiting out a cold connect`() = runBlocking {
    val f = fixture()
    f.bind("seller", seller.instanceId)
    f.bind("buyer", buyer.instanceId)
    f.sessionContext.deviceMoveWaitMs = 50
    f.bridge.blockSelectFor = buyer.trailblazeDeviceId

    val toBuyer = launch(Dispatchers.IO) { f.switchDevice("buyer") }
    withTimeout(SWITCH_TIMEOUT_MS) { f.bridge.selectStarted.await() }
    // Bounded so an unbounded wait fails this test rather than hanging it.
    val response = withTimeout(BUSY_REPORT_TIMEOUT_MS) { f.unbind("buyer") }

    assertTrue(response.startsWith("Error:"), response)
    assertTrue(response.contains("in flight"), "must say what is holding the session: $response")
    assertTrue(response.contains("switchDevice"), "must name the operation in flight: $response")
    assertEquals(
      listOf("seller", "buyer"),
      f.sessionContext.boundDeviceNames(),
      "a refused move must not half-apply",
    )

    f.bridge.releaseSelect.complete(Unit)
    withTimeout(SWITCH_TIMEOUT_MS) { toBuyer.join() }
  }

  /**
   * The re-registration resolves the driver and target through the per-call
   * `McpDeviceContext.currentDeviceId`, which dispatch set to the device being handed OFF. Unscoped,
   * the client is handed the previous device's tools for the device it now drives.
   */
  @Test
  fun `a switch re-registers tools against the device it switched to`() = runBlocking {
    val f = fixture()
    f.bind("seller", seller.instanceId)
    f.bind("buyer", buyer.instanceId)

    withContext(McpDeviceContext.currentDeviceId.asContextElement(seller.trailblazeDeviceId)) {
      f.switchDevice("buyer")
    }

    assertEquals(buyer.trailblazeDeviceId, f.refreshedForDevices.last())
  }

  /**
   * The roster snapshots a device's target at bind time, but a per-device or daemon-wide target
   * change doesn't rebuild it. Naming a target the device no longer resolves against is worse than
   * naming none.
   */
  @Test
  fun `a switch reports the target the device resolves against now`() = runBlocking {
    val f = fixture()
    f.bridge.currentTarget = "myApp"
    f.bind("seller", seller.instanceId)
    f.bind("buyer", buyer.instanceId)

    f.bridge.currentTarget = "otherApp"
    val response = f.switchDevice("buyer")

    assertTrue(response.contains("Target: otherApp."), response)
    assertTrue(!response.contains("Target: myApp."), response)
  }

  private fun androidDevice(instanceId: String) = TrailblazeConnectedDeviceSummary(
    trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
    instanceId = instanceId,
    description = "Fake $instanceId",
  )

  private fun fixture(): Fixture {
    val bridge = FakeBridge(setOf(seller, buyer))
    val sessionContext = TrailblazeMcpSessionContext(
      mcpServerSession = null,
      mcpSessionId = McpSessionId("mcp-session-under-test"),
    )
    val fixture = Fixture(bridge = bridge, sessionContext = sessionContext)
    fixture.deviceTools = DeviceManagerToolSet(
      sessionContext = sessionContext,
      mcpBridge = bridge,
      deviceClaimRegistry = DeviceClaimRegistry(),
    )
    fixture.switchTools = MultiDeviceToolSet(
      sessionContext = sessionContext,
      mcpBridge = bridge,
      onActiveDeviceChanged = {
        fixture.toolRefreshes++
        // What the production callback resolves the tool surface against.
        fixture.refreshedForDevices += McpDeviceContext.currentDeviceId.get()
      },
    )
    return fixture
  }

  private class Fixture(
    val bridge: FakeBridge,
    val sessionContext: TrailblazeMcpSessionContext,
  ) {
    lateinit var deviceTools: DeviceManagerToolSet
    lateinit var switchTools: MultiDeviceToolSet
    var toolRefreshes = 0
    val refreshedForDevices = mutableListOf<TrailblazeDeviceId?>()

    suspend fun bind(name: String, deviceId: String): String = deviceTools.device(
      action = DeviceManagerToolSet.DeviceAction.BIND,
      deviceId = deviceId,
      name = name,
    )

    suspend fun unbind(name: String): String = deviceTools.device(
      action = DeviceManagerToolSet.DeviceAction.UNBIND,
      name = name,
    )

    suspend fun switchDevice(name: String): String = switchTools.switchDevice(name = name)
  }

  /** Records the device the bridge was pointed at, and can refuse a selection. */
  private class FakeBridge(
    private val devices: Set<TrailblazeConnectedDeviceSummary>,
  ) : TrailblazeMcpBridge {
    val selected = mutableListOf<TrailblazeDeviceId>()
    val sessionSelected = mutableListOf<TrailblazeDeviceId>()
    var failSelectFor: TrailblazeDeviceId? = null
    var cancelSelectFor: TrailblazeDeviceId? = null
    /** Holds `selectDevice` for this device open until [releaseSelect] completes. */
    var blockSelectFor: TrailblazeDeviceId? = null
    val selectStarted = CompletableDeferred<Unit>()
    val releaseSelect = CompletableDeferred<Unit>()
    var currentTarget: String? = null
    val driverStatuses = mutableMapOf<TrailblazeDeviceId, String>()

    override suspend fun selectDevice(
      trailblazeDeviceId: TrailblazeDeviceId,
    ): TrailblazeConnectedDeviceSummary {
      if (trailblazeDeviceId == blockSelectFor) {
        selectStarted.complete(Unit)
        releaseSelect.await()
      }
      if (trailblazeDeviceId == cancelSelectFor) {
        throw kotlin.coroutines.cancellation.CancellationException("call cancelled")
      }
      if (trailblazeDeviceId == failSelectFor) {
        // Production points the bridge at the device (`assertDeviceIsSelected`) before the driver
        // connect that throws, so the fake has to leave that trace too — otherwise a rollback that
        // forgets the bridge looks correct here.
        sessionSelected += trailblazeDeviceId
        throw IllegalStateException("device offline")
      }
      selected += trailblazeDeviceId
      sessionSelected += trailblazeDeviceId
      return devices.first { it.trailblazeDeviceId == trailblazeDeviceId }
    }

    override fun getDriverConnectionStatus(deviceId: TrailblazeDeviceId?): String? =
      deviceId?.let { driverStatuses[it] }

    override fun selectDeviceForSession(deviceId: TrailblazeDeviceId) {
      sessionSelected += deviceId
    }

    override suspend fun getAvailableDevices(): Set<TrailblazeConnectedDeviceSummary> = devices
    override fun getCurrentlySelectedDeviceId(): TrailblazeDeviceId? = sessionSelected.lastOrNull()
    override suspend fun getInstalledAppIds(): Set<String> = emptySet()
    override fun getAvailableAppTargets(): Set<TrailblazeHostAppTarget> = emptySet()
    override suspend fun runYaml(
      yaml: String,
      startNewSession: Boolean,
      agentImplementation: AgentImplementation,
    ): String = throw NotImplementedError()
    override suspend fun getCurrentScreenState(): ScreenState? = null
    override suspend fun executeTrailblazeTool(
      tool: TrailblazeTool,
      blocking: Boolean,
      traceId: TraceId?,
    ): String = throw NotImplementedError()
    override suspend fun endSession(): Boolean = false
    override fun selectAppTarget(appTargetId: String): String? = null
    override fun getCurrentAppTargetId(): String? = currentTarget
    override fun getDriverType(): TrailblazeDriverType? = null
    override suspend fun getScreenStateViaRpc(
      includeScreenshot: Boolean,
      screenshotScalingConfig: ScreenshotScalingConfig,
      includeAnnotatedScreenshot: Boolean,
      includeAllElements: Boolean,
    ): GetScreenStateResponse? = null
    override fun getActiveSessionId(): SessionId? = null
    override suspend fun ensureSessionAndGetId(testName: String?): SessionId? = null
  }

  private companion object {
    /** Generous: it only has to outlast a mutex handoff, never a real device. */
    const val SWITCH_TIMEOUT_MS = 10_000L

    /** Well past the 50ms wait the busy case is given, and well short of an unbounded one. */
    const val BUSY_REPORT_TIMEOUT_MS = 2_000L
  }
}
