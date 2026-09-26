package xyz.block.trailblaze.mcp.newtools

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Test
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.ScreenshotScalingConfig
import xyz.block.trailblaze.devices.TrailblazeConnectedDeviceSummary
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.mcp.AgentImplementation
import xyz.block.trailblaze.mcp.BoundDeviceRosterMember
import xyz.block.trailblaze.mcp.DeviceClaimRegistry
import xyz.block.trailblaze.mcp.McpDeviceContext
import xyz.block.trailblaze.mcp.TrailblazeMcpBridge
import xyz.block.trailblaze.mcp.TrailblazeMcpSessionContext
import xyz.block.trailblaze.mcp.android.ondevice.rpc.GetScreenStateResponse
import xyz.block.trailblaze.mcp.models.McpSessionId
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.toolcalls.TrailblazeTool

/**
 * Pins the observable contract of named device bindings: which names a session holds, which one is
 * ACTIVE (the device every subsequent tool call routes to), and what the `device` tool reports back.
 *
 * The active device is the load-bearing part. MCP dispatch reads
 * [TrailblazeMcpSessionContext.associatedDeviceId] on every `tools/call`, so a roster whose ACTIVE
 * name and `associatedDeviceId` disagree would silently drive the wrong device — these tests assert
 * both together rather than either alone.
 */
class DeviceManagerToolSetNamedBindingsTest {

  private val seller = androidDevice("emulator-5554")
  private val buyer = androidDevice("emulator-5556")
  private val kitchen = androidDevice("emulator-5558")

  @Test
  fun `the first bind becomes the active device`() = runBlocking {
    val f = fixture()

    val response = f.bind("seller", seller.instanceId)

    assertTrue(response.contains("now the ACTIVE device"), response)
    assertEquals(listOf("seller"), f.sessionContext.boundDeviceNames())
    assertEquals("seller", f.sessionContext.activeDeviceName())
    assertEquals(seller.trailblazeDeviceId, f.sessionContext.associatedDeviceId)
  }

  @Test
  fun `binding a second device keeps the first, and does not steal the active slot`() = runBlocking {
    val f = fixture()
    f.bind("seller", seller.instanceId)

    val response = f.bind("buyer", buyer.instanceId)

    assertFalse(response.contains("now the ACTIVE device"), response)
    assertEquals(listOf("seller", "buyer"), f.sessionContext.boundDeviceNames())
    assertEquals("seller", f.sessionContext.activeDeviceName())
    assertEquals(seller.trailblazeDeviceId, f.sessionContext.associatedDeviceId)
  }

  /**
   * `selectDevice` warms the bound device's driver AND points the bridge at it, so binding a
   * non-active name leaves the bridge on the wrong device unless the active one is re-selected.
   * A bridge/session disagreement here would send the next tool call to the device that was merely
   * bound most recently.
   */
  @Test
  fun `binding a non-active device restores the bridge to the active one`() = runBlocking {
    val f = fixture()
    f.bind("seller", seller.instanceId)

    f.bind("buyer", buyer.instanceId)

    assertEquals(buyer.trailblazeDeviceId, f.bridge.selected.last())
    assertEquals(seller.trailblazeDeviceId, f.bridge.sessionSelected.last())
  }

  /**
   * Bind order is the start-device marker, so it must be preserved as the roster grows. (The harder
   * case — a bind AFTER a switch moved the active device off the first entry — is pinned in
   * `TrailblazeMcpSessionContextNamedBindingsTest`, where a switch can be driven directly.)
   */
  @Test
  fun `binds accumulate in bind order`() = runBlocking {
    val f = fixture()
    f.bind("seller", seller.instanceId)
    f.bind("buyer", buyer.instanceId)

    f.bind("kitchen", kitchen.instanceId)

    assertEquals(listOf("seller", "buyer", "kitchen"), f.sessionContext.boundDeviceNames())
    assertEquals("seller", f.sessionContext.activeDeviceName())
  }

  @Test
  fun `binding an unknown device changes nothing`() = runBlocking {
    val f = fixture()
    f.bind("seller", seller.instanceId)

    val response = f.bind("buyer", "emulator-9999")

    assertTrue(response.startsWith("Error:"), response)
    assertEquals(listOf("seller"), f.sessionContext.boundDeviceNames())
    assertEquals(seller.trailblazeDeviceId, f.sessionContext.associatedDeviceId)
  }

  @Test
  fun `bind requires a name`() = runBlocking {
    val f = fixture()

    val response = f.device(DeviceManagerToolSet.DeviceAction.BIND, deviceId = seller.instanceId)

    assertTrue(response.startsWith("Error:"), response)
    assertTrue(response.contains("name required"), response)
    assertEquals(emptyList(), f.sessionContext.boundDeviceNames())
  }

  @Test
  fun `unbinding a non-active device leaves the active one alone and releases its claim`() = runBlocking {
    val f = fixture()
    f.bind("seller", seller.instanceId)
    f.bind("buyer", buyer.instanceId)

    val response = f.unbind("buyer")

    assertTrue(response.contains("Unbound 'buyer'"), response)
    assertEquals(listOf("seller"), f.sessionContext.boundDeviceNames())
    assertEquals("seller", f.sessionContext.activeDeviceName())
    assertNull(f.claims.getClaim(buyer.trailblazeDeviceId))
    assertEquals(SESSION_ID, f.claims.getClaim(seller.trailblazeDeviceId)?.mcpSessionId)
  }

  @Test
  fun `unbinding the active device promotes the first remaining name`() = runBlocking {
    val f = fixture()
    f.bind("seller", seller.instanceId)
    f.bind("buyer", buyer.instanceId)

    val response = f.unbind("seller")

    assertTrue(response.contains("'buyer' is now the ACTIVE device"), response)
    assertEquals("buyer", f.sessionContext.activeDeviceName())
    assertEquals(buyer.trailblazeDeviceId, f.sessionContext.associatedDeviceId)
    assertEquals(buyer.trailblazeDeviceId, f.bridge.sessionSelected.last())
  }

  @Test
  fun `unbinding the last device is refused`() = runBlocking {
    val f = fixture()
    f.bind("seller", seller.instanceId)

    val response = f.unbind("seller")

    assertTrue(response.startsWith("Error:"), response)
    assertEquals(listOf("seller"), f.sessionContext.boundDeviceNames())
    assertEquals(seller.trailblazeDeviceId, f.sessionContext.associatedDeviceId)
  }

  @Test
  fun `unbinding an unknown name reports the bound names`() = runBlocking {
    val f = fixture()
    f.bind("seller", seller.instanceId)

    val response = f.unbind("buyer")

    assertTrue(response.startsWith("Error:"), response)
    assertTrue(response.contains("seller"), response)
  }

  /**
   * CONNECT and friends REPLACE the session's device. A roster left behind would advertise
   * `switchDevice` names that route nowhere, so it is dropped and reported.
   */
  @Test
  fun `a replacing connect drops the named roster`() = runBlocking {
    val f = fixture()
    f.bind("seller", seller.instanceId)
    f.bind("buyer", buyer.instanceId)

    val response = f.device(
      DeviceManagerToolSet.DeviceAction.CONNECT,
      deviceId = kitchen.instanceId,
    )

    assertTrue(response.contains("Dropped this session's named device bindings"), response)
    assertEquals(emptyList(), f.sessionContext.boundDeviceNames())
    assertEquals(kitchen.trailblazeDeviceId, f.sessionContext.associatedDeviceId)
    assertNull(f.claims.getClaim(seller.trailblazeDeviceId))
    assertNull(f.claims.getClaim(buyer.trailblazeDeviceId))
  }

  @Test
  fun `info reports the roster and marks the active device`() = runBlocking {
    val f = fixture()
    f.bind("seller", seller.instanceId)
    f.bind("buyer", buyer.instanceId)

    val response = f.device(DeviceManagerToolSet.DeviceAction.INFO)

    assertTrue(response.contains("Named devices in this session:"), response)
    assertTrue(response.contains("seller: android/${seller.instanceId} [ACTIVE]"), response)
    assertTrue(response.contains("buyer: android/${buyer.instanceId}"), response)
    assertFalse(response.contains("buyer: android/${buyer.instanceId} [ACTIVE]"), response)
  }

  /** A session that never binds a name must read exactly as it did before rosters existed. */
  @Test
  fun `info says nothing about bindings for a single-device session`() = runBlocking {
    val f = fixture()
    f.device(DeviceManagerToolSet.DeviceAction.CONNECT, deviceId = seller.instanceId)

    val response = f.device(DeviceManagerToolSet.DeviceAction.INFO)

    assertFalse(response.contains("Named devices"), response)
    assertFalse(response.contains("Dropped"), response)
    assertNull(f.sessionContext.namedDeviceBindings)
  }

  /**
   * A roster of two names on one device is a cast of two that is really one: `switchDevice` between
   * them reports a handover and moves nothing, and an agent only finds out when the other device
   * never reacts. `SessionDeviceBindings` rejects that roster outright, so naming an already-bound
   * device moves its name rather than adding a second one — and the device keeps its claim, since
   * it never leaves the session.
   */
  @Test
  fun `naming an already-bound device renames it instead of binding it twice`() = runBlocking {
    val f = fixture()
    f.bind("seller", seller.instanceId)

    val response = f.bind("register", seller.instanceId)

    assertFalse(response.startsWith("Error:"), response)
    assertTrue(response.contains("Renamed 'seller' to 'register'"), response)
    assertEquals(listOf("register"), f.sessionContext.boundDeviceNames())
    assertEquals("register", f.sessionContext.activeDeviceName())
    assertEquals(seller.trailblazeDeviceId, f.sessionContext.associatedDeviceId)
    assertEquals(SESSION_ID, f.claims.getClaim(seller.trailblazeDeviceId)?.mcpSessionId)
    assertEquals(emptyList(), f.bridge.endedSessionsOn, "a rename doesn't let the device go")
  }

  /**
   * Renaming is the only way out of a name an agent regrets: it can't bind the device again under
   * the new name (one device, one name) and it can't unbind the old one first when it is the
   * session's only binding. Without this the sole binding of a session is unnameable for good.
   */
  @Test
  fun `the sole binding of a session can be renamed`() = runBlocking {
    val f = fixture()
    f.bind("seller", seller.instanceId)

    assertTrue(f.unbind("seller").startsWith("Error:"), "the last binding cannot be unbound")
    val response = f.bind("buyer", seller.instanceId)

    assertFalse(response.startsWith("Error:"), response)
    assertEquals(listOf("buyer"), f.sessionContext.boundDeviceNames())
  }

  /** Bind order marks the start device, so a rename must not move the device to the end. */
  @Test
  fun `a rename keeps bind order and the active name`() = runBlocking {
    val f = fixture()
    f.bind("seller", seller.instanceId)
    f.bind("buyer", buyer.instanceId)

    f.bind("register", seller.instanceId)

    assertEquals(listOf("register", "buyer"), f.sessionContext.boundDeviceNames())
    assertEquals("register", f.sessionContext.activeDeviceName())
    assertEquals(seller.trailblazeDeviceId, f.sessionContext.associatedDeviceId)
  }

  /**
   * Renaming onto a name another device holds would silently unbind that device — two operations
   * behind one call. Refused, with the two steps that express it.
   */
  @Test
  fun `renaming onto a name another device holds is refused`() = runBlocking {
    val f = fixture()
    f.bind("seller", seller.instanceId)
    f.bind("buyer", buyer.instanceId)

    val response = f.bind("buyer", seller.instanceId)

    assertTrue(response.startsWith("Error:"), response)
    assertTrue(response.contains("UNBIND"), "must name the way through: $response")
    assertEquals(listOf("seller", "buyer"), f.sessionContext.boundDeviceNames())
    assertEquals(buyer.trailblazeDeviceId, f.sessionContext.boundDevice("buyer")?.trailblazeDeviceId)
    assertEquals(SESSION_ID, f.claims.getClaim(buyer.trailblazeDeviceId)?.mcpSessionId)
  }

  /** Rebinding the SAME name to the same device is not a duplicate — it is a no-op re-bind. */
  @Test
  fun `rebinding a name to the device it already holds is allowed`() = runBlocking {
    val f = fixture()
    f.bind("seller", seller.instanceId)

    val response = f.bind("seller", seller.instanceId)

    assertFalse(response.startsWith("Error:"), response)
    assertEquals(listOf("seller"), f.sessionContext.boundDeviceNames())
    assertEquals(SESSION_ID, f.claims.getClaim(seller.trailblazeDeviceId)?.mcpSessionId)
  }

  /**
   * CONNECT-then-BIND is the mirror of the drop-the-roster case: the connected device has no name,
   * so once a bind takes over the active slot nothing in the session can address it. Holding its
   * claim to the end of the session would keep it away from every other session for nothing.
   */
  @Test
  fun `a first bind releases the device that was connected without a name`() = runBlocking {
    val f = fixture()
    f.device(DeviceManagerToolSet.DeviceAction.CONNECT, deviceId = kitchen.instanceId)

    val response = f.bind("seller", seller.instanceId)

    assertTrue(response.contains("Released ${kitchen.instanceId}"), response)
    assertNull(f.claims.getClaim(kitchen.trailblazeDeviceId))
    assertEquals(seller.trailblazeDeviceId, f.sessionContext.associatedDeviceId)
    assertEquals(SESSION_ID, f.claims.getClaim(seller.trailblazeDeviceId)?.mcpSessionId)
  }

  /**
   * The exact tool-call sequence `trailblaze session start --bind seller=… --bind buyer=…`
   * makes: an ordinary connect of the start device (the CLI's `ensureDevice`), then a BIND
   * for every entry in declared order, start device first. The context it leaves behind is
   * what later `step` / `verify` invocations reattaching to the same MCP session dispatch
   * against, so the full roster, first-bind-active, and both claims are asserted together.
   */
  @Test
  fun `the CLI session-start bind sequence yields the roster with the first bind active`() = runBlocking {
    val f = fixture()

    f.device(DeviceManagerToolSet.DeviceAction.CONNECT, deviceId = seller.instanceId)
    f.bind("seller", seller.instanceId)
    f.bind("buyer", buyer.instanceId)

    assertEquals(listOf("seller", "buyer"), f.sessionContext.boundDeviceNames())
    assertEquals("seller", f.sessionContext.activeDeviceName())
    assertEquals(seller.trailblazeDeviceId, f.sessionContext.associatedDeviceId)
    assertEquals(SESSION_ID, f.claims.getClaim(seller.trailblazeDeviceId)?.mcpSessionId)
    assertEquals(SESSION_ID, f.claims.getClaim(buyer.trailblazeDeviceId)?.mcpSessionId)
    // What `trailblaze session info -d <start>` renders comes from this INFO response.
    val info = f.device(DeviceManagerToolSet.DeviceAction.INFO)
    assertTrue(info.contains("Named devices in this session:"), info)
    assertTrue(info.contains("seller: android/${seller.instanceId} [ACTIVE]"), info)
    assertTrue(info.contains("buyer: android/${buyer.instanceId}"), info)
  }

  /** Binding the device already connected keeps it — it is the same device, now under a name. */
  @Test
  fun `binding the already-connected device releases nothing`() = runBlocking {
    val f = fixture()
    f.device(DeviceManagerToolSet.DeviceAction.CONNECT, deviceId = seller.instanceId)

    val response = f.bind("seller", seller.instanceId)

    assertFalse(response.contains("Released"), response)
    assertEquals(SESSION_ID, f.claims.getClaim(seller.trailblazeDeviceId)?.mcpSessionId)
  }

  /**
   * A released device drops out of the set session teardown walks, so whatever ends its session
   * has to be the release itself. Otherwise the session stays open forever and the next MCP
   * session to claim that device logs into it.
   */
  @Test
  fun `a first bind ends the displaced device's session, not just its claim`() = runBlocking {
    val f = fixture()
    f.device(DeviceManagerToolSet.DeviceAction.CONNECT, deviceId = kitchen.instanceId)
    f.bridge.devicesRunningASession += kitchen.trailblazeDeviceId

    f.bind("seller", seller.instanceId)

    assertEquals(listOf(kitchen.trailblazeDeviceId), f.bridge.endedSessionsOn)
    assertNull(f.claims.getClaim(kitchen.trailblazeDeviceId))
  }

  /** Same rule on unbind: the device leaves the session, so its session ends with it. */
  @Test
  fun `unbinding ends the released device's session`() = runBlocking {
    val f = fixture()
    f.bind("seller", seller.instanceId)
    f.bind("buyer", buyer.instanceId)
    f.bridge.devicesRunningASession += buyer.trailblazeDeviceId

    f.unbind("buyer")

    assertEquals(listOf(buyer.trailblazeDeviceId), f.bridge.endedSessionsOn)
  }

  /**
   * The mirror case: a device the session can still address keeps working, so ending its session
   * would kill live work — the same reason its claim survives. Reached here by binding the device
   * the session was already connected to, which displaces the unnamed association onto a device
   * that is now in the roster.
   */
  /** Another client's session on a device this session bound is that client's to end, not an unbind's. */
  @Test
  fun `unbinding a device whose session was found running leaves that session running`() = runBlocking {
    val f = fixture()
    f.sessionContext.activeRecordingOnDevice = { id ->
      if (id in f.bridge.devicesRunningASession) SessionId("session-on-${id.instanceId}") else null
    }
    f.bridge.devicesRunningASession += seller.trailblazeDeviceId // the other client's session
    f.bind("seller", seller.instanceId)
    f.bind("buyer", buyer.instanceId)

    f.unbind("seller")

    assertFalse(seller.trailblazeDeviceId in f.bridge.endedSessionsOn, "${f.bridge.endedSessionsOn}")
    assertNull(f.claims.getClaim(seller.trailblazeDeviceId), "the claim is still released")
  }

  @Test
  fun `a device that keeps a name through a bind keeps its session`() = runBlocking {
    val f = fixture()
    f.device(DeviceManagerToolSet.DeviceAction.CONNECT, deviceId = seller.instanceId)
    f.bridge.devicesRunningASession += seller.trailblazeDeviceId

    f.bind("seller", seller.instanceId)

    assertEquals(emptyList(), f.bridge.endedSessionsOn)
    assertEquals(SESSION_ID, f.claims.getClaim(seller.trailblazeDeviceId)?.mcpSessionId)
  }

  /** Dropping a roster lets the same devices go, so it owes them the same cleanup. */
  @Test
  fun `a replacing connect ends the sessions of the devices it drops`() = runBlocking {
    val f = fixture()
    f.bind("seller", seller.instanceId)
    f.bind("buyer", buyer.instanceId)
    f.bridge.devicesRunningASession += setOf(seller.trailblazeDeviceId, buyer.trailblazeDeviceId)

    f.device(DeviceManagerToolSet.DeviceAction.CONNECT, deviceId = kitchen.instanceId)

    assertEquals(
      listOf(seller.trailblazeDeviceId, buyer.trailblazeDeviceId),
      f.bridge.endedSessionsOn,
    )
  }

  /**
   * A cancelled request doesn't put the device back in this session's reach, so the claim still
   * has to go — otherwise it stays locked to a session that can never address it again.
   */
  @Test
  fun `a cancelled session end still releases the claim`() = runBlocking {
    val f = fixture()
    f.bind("seller", seller.instanceId)
    f.bind("buyer", buyer.instanceId)
    f.bridge.devicesRunningASession += buyer.trailblazeDeviceId
    f.bridge.cancelEndSessionOn = buyer.trailblazeDeviceId

    assertFailsWith<CancellationException> { f.unbind("buyer") }

    assertNull(f.claims.getClaim(buyer.trailblazeDeviceId))
  }

  /**
   * The roster snapshots the target at bind time, but `switchTargetApp` and
   * `setSessionTargetForBoundDevice` both move a device's target without rebuilding it. Reporting
   * the snapshot would tell an agent its tools resolve against a target they don't.
   */
  @Test
  fun `the roster reports the target a device resolves against now, not at bind time`() = runBlocking {
    val f = fixture()
    f.bridge.currentTarget = "myApp"
    f.bind("seller", seller.instanceId)

    f.bridge.currentTarget = "otherApp"
    val response = f.device(DeviceManagerToolSet.DeviceAction.INFO)

    assertTrue(response.contains("seller: android/${seller.instanceId} (target: otherApp)"), response)
    assertFalse(response.contains("target: myApp"), response)
  }

  /**
   * Dispatch pins each call to the device it arrived for. A `switchDevice` landing between dispatch
   * and execution moves the session's active device, not the call — so the override is the
   * dispatched device's, not whichever device is active by the time the tool runs.
   */
  @Test
  fun `a session target is set on the device the call was dispatched for`() = runBlocking {
    val f = fixture()
    f.bind("seller", seller.instanceId)
    f.bind("buyer", buyer.instanceId)
    f.sessionContext.setAssociatedDevice(buyer.trailblazeDeviceId) // the handover won the race

    withContext(McpDeviceContext.currentDeviceId.asContextElement(seller.trailblazeDeviceId)) {
      f.toolSet.setSessionTargetForBoundDevice("myApp")
    }

    assertEquals<List<Pair<TrailblazeDeviceId, String?>>>(listOf(seller.trailblazeDeviceId to "myApp"), f.bridge.sessionTargetsSet)
  }

  /** FULL is documented as SUMMARY plus apps, so the roster belongs in it too. */
  @Test
  fun `full info reports the roster`() = runBlocking {
    val f = fixture()
    f.bind("seller", seller.instanceId)
    f.bind("buyer", buyer.instanceId)

    val response = f.device(
      DeviceManagerToolSet.DeviceAction.INFO,
      detail = DeviceManagerToolSet.DeviceDetail.FULL,
    )

    assertTrue(response.contains("Named devices in this session:"), response)
    assertTrue(response.contains("seller: android/${seller.instanceId} [ACTIVE]"), response)
    assertTrue(response.contains("buyer: android/${buyer.instanceId}"), response)
  }

  /**
   * Whether `switchDevice` is advertised is decided at registration from the roster's size, so every
   * roster change has to chain a re-registration — including an unbind that leaves the ACTIVE device
   * alone, which is exactly when the tool must be retracted.
   */
  @Test
  fun `every bind and unbind re-registers the session's tools`() = runBlocking {
    val f = fixture()

    f.bind("seller", seller.instanceId)
    assertEquals(1, f.toolRefreshes, "the first bind")
    f.bind("buyer", buyer.instanceId)
    assertEquals(2, f.toolRefreshes, "a second bind makes switchDevice advertisable")

    f.unbind("buyer")

    assertEquals(
      3,
      f.toolRefreshes,
      "unbinding the INACTIVE device drops the roster to one, which must retract switchDevice",
    )
  }

  /**
   * The re-registration resolves the session's driver and target through the per-call
   * `McpDeviceContext.currentDeviceId`, which dispatch set to the device this `tools/call` arrived
   * for — the one the bind moves OFF. Unscoped, the client is handed the previous device's tools
   * for the device it now drives, and the two can differ in driver and in target.
   */
  @Test
  fun `a bind that changes the active device refreshes tools against it`() = runBlocking {
    val f = fixture()
    f.device(DeviceManagerToolSet.DeviceAction.CONNECT, deviceId = kitchen.instanceId)

    withContext(McpDeviceContext.currentDeviceId.asContextElement(kitchen.trailblazeDeviceId)) {
      f.bind("seller", seller.instanceId)
    }

    assertEquals(seller.trailblazeDeviceId, f.refreshedForDevices.last())
  }

  /**
   * `connectToDevice` predates named bindings and is still advertised beside `device(action=…)`, so
   * it can replace the session's device under a roster. Left alone it kept the names advertised
   * while dispatch went to the replacement, and held the dropped devices' claims for the life of
   * the session.
   */
  @Test
  fun `the standalone connect tool drops the named roster too`() = runBlocking {
    val f = fixture()
    f.bind("seller", seller.instanceId)
    f.bind("buyer", buyer.instanceId)
    f.bridge.devicesRunningASession += buyer.trailblazeDeviceId

    f.toolSet.connectToDevice(kitchen.trailblazeDeviceId)

    assertEquals(emptyList(), f.sessionContext.boundDeviceNames())
    assertEquals(kitchen.trailblazeDeviceId, f.sessionContext.associatedDeviceId)
    assertNull(f.claims.getClaim(seller.trailblazeDeviceId))
    assertNull(f.claims.getClaim(buyer.trailblazeDeviceId))
    assertEquals(listOf(buyer.trailblazeDeviceId), f.bridge.endedSessionsOn)
  }

  /** Teardown iterates this, so a bound-but-inactive device must appear in it. */
  @Test
  fun `every bound device is addressed for teardown, not just the active one`() = runBlocking {
    val f = fixture()
    f.bind("seller", seller.instanceId)
    f.bind("buyer", buyer.instanceId)

    assertEquals(
      listOf(seller.trailblazeDeviceId, buyer.trailblazeDeviceId),
      f.sessionContext.addressedDeviceIds(),
    )
  }

  // ---- the cast the host records -----------------------------------------------------------------

  /**
   * The host runs ONE Trailblaze session for a cast and records every member into it, so it has to
   * be told who the cast is — in bind order, the start device first, under this MCP session's id.
   */
  @Test
  fun `every bind hands the host the whole cast in bind order`() = runBlocking {
    val f = fixture()
    f.bind("seller", seller.instanceId)

    val response = f.bind("buyer", buyer.instanceId)

    val (rosterId, members) = f.bridge.rosters.last()
    assertEquals(SESSION_ID, rosterId)
    assertEquals(listOf("seller" to seller.trailblazeDeviceId, "buyer" to buyer.trailblazeDeviceId), members.map { it.name to it.trailblazeDeviceId })
    assertTrue(response.contains("Session shared-session covers the whole cast"), response)
  }

  @Test
  fun `the cast carries the target each device resolves against`() = runBlocking {
    val f = fixture()
    f.bridge.currentTarget = "pos"
    f.bind("seller", seller.instanceId)

    f.bind("buyer", buyer.instanceId)

    assertEquals(listOf("pos", "pos"), f.bridge.rosters.last().second.map { it.targetId })
  }

  @Test
  fun `an unbind hands the host the cast without that name, before the device is released`() = runBlocking {
    val f = fixture()
    f.bind("seller", seller.instanceId)
    f.bind("buyer", buyer.instanceId)
    f.bridge.devicesRunningASession += buyer.trailblazeDeviceId
    // A host that has taken the buyer out of the shared session reports no session on it.
    f.bridge.rostersSeenBeforeEnd = mutableListOf()

    f.unbind("buyer")

    assertEquals(listOf("seller"), f.bridge.rosters.last().second.map { it.name })
    assertEquals(
      listOf("seller"),
      f.bridge.rostersSeenBeforeEnd?.lastOrNull()?.map { it.name },
      "the host must know the buyer left BEFORE its session is ended, or the end would take the shared session down",
    )
  }

  @Test
  fun `a rename hands the host the cast under the new name`() = runBlocking {
    val f = fixture()
    f.bind("seller", seller.instanceId)
    f.bind("buyer", buyer.instanceId)

    f.bind("customer", buyer.instanceId)

    assertEquals(listOf("seller", "customer"), f.bridge.rosters.last().second.map { it.name })
  }

  @Test
  fun `a replacing connect dissolves the cast on the host`() = runBlocking {
    val f = fixture()
    f.bind("seller", seller.instanceId)
    f.bind("buyer", buyer.instanceId)

    f.device(DeviceManagerToolSet.DeviceAction.CONNECT, deviceId = kitchen.instanceId)

    assertEquals(emptyList(), f.bridge.rosters.last().second)
  }

  /** A single bind is not a cast yet, but the host is still told — a later bind grows what it holds. */
  @Test
  fun `a first bind declares a cast of one without claiming a shared session`() = runBlocking {
    val f = fixture()

    val response = f.bind("seller", seller.instanceId)

    assertEquals(listOf("seller"), f.bridge.rosters.last().second.map { it.name })
    assertFalse(response.contains("covers the whole cast"), response)
  }

  // ---- helpers ---------------------------------------------------------------------------------

  private fun androidDevice(instanceId: String) = TrailblazeConnectedDeviceSummary(
    trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
    instanceId = instanceId,
    description = "Fake $instanceId",
  )

  private fun fixture(): Fixture {
    val bridge = FakeBridge(setOf(seller, buyer, kitchen))
    val sessionContext = TrailblazeMcpSessionContext(
      mcpServerSession = null,
      mcpSessionId = McpSessionId(SESSION_ID),
    )
    val claims = DeviceClaimRegistry()
    val fixture = Fixture(
      bridge = bridge,
      sessionContext = sessionContext,
      claims = claims,
    )
    fixture.toolSet = DeviceManagerToolSet(
      sessionContext = sessionContext,
      mcpBridge = bridge,
      deviceClaimRegistry = claims,
      onDeviceConnected = {
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
    val claims: DeviceClaimRegistry,
  ) {
    lateinit var toolSet: DeviceManagerToolSet
    var toolRefreshes = 0
    val refreshedForDevices = mutableListOf<TrailblazeDeviceId?>()

    suspend fun device(
      action: DeviceManagerToolSet.DeviceAction,
      deviceId: String? = null,
      name: String? = null,
      detail: DeviceManagerToolSet.DeviceDetail = DeviceManagerToolSet.DeviceDetail.SUMMARY,
    ): String = toolSet.device(action = action, deviceId = deviceId, name = name, detail = detail)

    suspend fun bind(name: String, deviceId: String): String =
      device(DeviceManagerToolSet.DeviceAction.BIND, deviceId = deviceId, name = name)

    suspend fun unbind(name: String): String =
      device(DeviceManagerToolSet.DeviceAction.UNBIND, name = name)
  }

  /** Records the device the bridge was pointed at; every other method is inert. */
  private class FakeBridge(
    private val devices: Set<TrailblazeConnectedDeviceSummary>,
  ) : TrailblazeMcpBridge {
    val selected = mutableListOf<TrailblazeDeviceId>()
    val sessionSelected = mutableListOf<TrailblazeDeviceId>()
    val devicesRunningASession = mutableSetOf<TrailblazeDeviceId>()
    val endedSessionsOn = mutableListOf<TrailblazeDeviceId>()
    /** Every cast the toolset declared to the host, in order — the last one is what the host holds now. */
    val rosters = mutableListOf<Pair<String, List<BoundDeviceRosterMember>>>()
    var currentTarget: String? = null
    var cancelEndSessionOn: TrailblazeDeviceId? = null

    override suspend fun selectDevice(
      trailblazeDeviceId: TrailblazeDeviceId,
    ): TrailblazeConnectedDeviceSummary {
      selected += trailblazeDeviceId
      sessionSelected += trailblazeDeviceId
      return devices.first { it.trailblazeDeviceId == trailblazeDeviceId }
    }

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
    /**
     * Both of these read the device context the caller established, exactly as the real bridge
     * resolves the device a session call acts on. A fake that ignored it would report the same
     * answer whichever device was being released, and could not tell the two apart.
     */
    override fun getActiveSessionId(): SessionId? = McpDeviceContext.currentDeviceId.get()
      ?.takeIf { it in devicesRunningASession }
      ?.let { SessionId("session-on-${it.instanceId}") }

    /** When non-null, records the cast the host held at each endSession — see the unbind ordering test. */
    var rostersSeenBeforeEnd: MutableList<List<BoundDeviceRosterMember>>? = null

    override suspend fun endSession(): Boolean {
      val deviceId = McpDeviceContext.currentDeviceId.get() ?: return false
      rostersSeenBeforeEnd?.add(rosters.lastOrNull()?.second.orEmpty())
      endedSessionsOn += deviceId
      if (deviceId == cancelEndSessionOn) throw CancellationException("request cancelled")
      return devicesRunningASession.remove(deviceId)
    }

    override fun getCurrentAppTargetId(): String? = currentTarget
    override fun selectAppTarget(appTargetId: String): String? = null
    override fun getDriverType(): TrailblazeDriverType? = null
    override suspend fun getScreenStateViaRpc(
      includeScreenshot: Boolean,
      screenshotScalingConfig: ScreenshotScalingConfig,
      includeAnnotatedScreenshot: Boolean,
      includeAllElements: Boolean,
    ): GetScreenStateResponse? = null
    override suspend fun ensureSessionAndGetId(testName: String?): SessionId? = null

    val sessionTargetsSet = mutableListOf<Pair<TrailblazeDeviceId, String?>>()
    override fun setSessionTargetForDevice(deviceId: TrailblazeDeviceId, appTargetId: String?): String? {
      sessionTargetsSet += deviceId to appTargetId
      return appTargetId
    }

    override fun setBoundDeviceRoster(rosterId: String, members: List<BoundDeviceRosterMember>): SessionId? {
      rosters += rosterId to members
      return if (members.isEmpty()) null else SessionId("shared-session")
    }
  }

  private companion object {
    const val SESSION_ID = "mcp-session-under-test"
  }
}
