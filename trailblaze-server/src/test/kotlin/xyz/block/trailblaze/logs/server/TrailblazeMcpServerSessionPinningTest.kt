package xyz.block.trailblaze.logs.server

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.ScreenshotScalingConfig
import xyz.block.trailblaze.devices.TrailblazeConnectedDeviceSummary
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.mcp.AgentImplementation
import xyz.block.trailblaze.mcp.DeviceClaimRegistry
import xyz.block.trailblaze.mcp.TRAILBLAZE_CLI_CLIENT_NAME
import xyz.block.trailblaze.mcp.TrailblazeMcpBridge
import xyz.block.trailblaze.mcp.TrailblazeMcpSessionContext
import xyz.block.trailblaze.mcp.newtools.DeviceManagerToolSet
import xyz.block.trailblaze.mcp.android.ondevice.rpc.GetScreenStateResponse
import xyz.block.trailblaze.mcp.models.McpSessionId
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertIs

/**
 * Unit tests for [TrailblazeMcpServer.pinMostRecentUnboundMcpSession].
 *
 * The MCP session-pinning chip wires `trailblaze device connect` to also
 * adopt a co-resident MCP client (Claude Desktop / Cursor / Goose) that
 * hasn't bound a device yet — so the agent's next tool call routes to the
 * same device the shell user just connected.
 *
 * These tests pin the filter logic (real MCP client + unbound), the recency
 * tie-break (highest `lastActive` wins), the explicit-session-id override,
 * and the device-not-found path.
 */
class TrailblazeMcpServerSessionPinningTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  private val androidDevice = TrailblazeDeviceId(
    instanceId = "emulator-5554",
    trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
  )

  private fun newServer(
    bridgeOverride: TrailblazeMcpBridge = TestPinBridge(setOf(androidDevice)),
  ): TrailblazeMcpServer = TrailblazeMcpServer(
    logsRepo = LogsRepo(logsDir = tempFolder.newFolder("logs"), watchFileSystem = false),
    mcpBridge = bridgeOverride,
    trailsDirProvider = { tempFolder.newFolder("trails") },
    targetTestAppProvider = { TrailblazeHostAppTarget.DefaultTrailblazeHostAppTarget },
    llmModelListsProvider = { emptySet() },
  )

  private fun ctx(
    clientName: String?,
    associatedDevice: TrailblazeDeviceId? = null,
    lastActive: Instant = Clock.System.now(),
    sessionId: String = "test",
  ): TrailblazeMcpSessionContext = TrailblazeMcpSessionContext(
    mcpServerSession = null,
    mcpSessionId = McpSessionId(sessionId),
  ).also {
    it.mcpClientName = clientName
    if (associatedDevice != null) it.setAssociatedDevice(associatedDevice)
    it.lastActive = lastActive
  }

  @Test
  fun `pins the most-recently-active unbound non-CLI session`() {
    val server = newServer()
    val now = Clock.System.now()
    val t0 = now
    val t1 = Instant.fromEpochMilliseconds(now.toEpochMilliseconds() + 1_000)
    val t2 = Instant.fromEpochMilliseconds(now.toEpochMilliseconds() + 2_000)

    val cliCtx = ctx(clientName = "TrailblazeCLI", lastActive = t2)
    val claudeCtx = ctx(clientName = "Claude Code", lastActive = t1)
    val cursorCtx = ctx(clientName = "Cursor", lastActive = t0) // older than claude

    server.installSessionContextForTest("cli-session", cliCtx)
    server.installSessionContextForTest("claude-session", claudeCtx)
    server.installSessionContextForTest("cursor-session", cursorCtx)

    val result = runBlocking { server.pinMostRecentUnboundMcpSession("android") }

    val pinned = assertIs<TrailblazeMcpServer.PinResult.Pinned>(result)
    assertEquals("claude-session", pinned.sessionId, "Most-recent non-CLI session must win")
    assertEquals("Claude Code", pinned.mcpClientName)
    assertEquals(androidDevice, pinned.deviceId)

    assertEquals(
      androidDevice,
      claudeCtx.associatedDeviceId,
      "Winning session must be pinned to the resolved device",
    )
    assertNull(cliCtx.associatedDeviceId, "CLI session must not be touched")
    assertNull(cursorCtx.associatedDeviceId, "Older non-CLI session must not be touched")
  }

  @Test
  fun `flips the recency tie-break when a newer non-CLI session is added`() {
    val server = newServer()
    val now = Clock.System.now()
    val t1 = Instant.fromEpochMilliseconds(now.toEpochMilliseconds() + 1_000)
    val t2 = Instant.fromEpochMilliseconds(now.toEpochMilliseconds() + 2_000)

    val claudeCtx = ctx(clientName = "Claude Code", lastActive = t1)
    val cursorCtx = ctx(clientName = "Cursor", lastActive = t2) // newer than claude

    server.installSessionContextForTest("claude-session", claudeCtx)
    server.installSessionContextForTest("cursor-session", cursorCtx)

    val result = runBlocking { server.pinMostRecentUnboundMcpSession("android") }

    val pinned = assertIs<TrailblazeMcpServer.PinResult.Pinned>(result)
    assertEquals("cursor-session", pinned.sessionId, "Highest lastActive must win")
    assertEquals(androidDevice, cursorCtx.associatedDeviceId)
    assertNull(claudeCtx.associatedDeviceId)
  }

  @Test
  fun `explicit mcp-session id pins that session even when older`() {
    val server = newServer()
    val now = Clock.System.now()
    val t1 = Instant.fromEpochMilliseconds(now.toEpochMilliseconds() + 1_000)
    val t2 = Instant.fromEpochMilliseconds(now.toEpochMilliseconds() + 2_000)

    // Cursor is newer, but we name claude explicitly.
    val claudeCtx = ctx(clientName = "Claude Code", lastActive = t1)
    val cursorCtx = ctx(clientName = "Cursor", lastActive = t2)
    server.installSessionContextForTest("claude-session", claudeCtx)
    server.installSessionContextForTest("cursor-session", cursorCtx)

    val result = runBlocking {
      server.pinMostRecentUnboundMcpSession(
        deviceSpec = "android",
        explicitSessionId = "claude-session",
      )
    }

    val pinned = assertIs<TrailblazeMcpServer.PinResult.Pinned>(result)
    assertEquals("claude-session", pinned.sessionId, "Named session id overrides recency tie-break")
    assertEquals(androidDevice, claudeCtx.associatedDeviceId)
    assertNull(cursorCtx.associatedDeviceId)
  }

  @Test
  fun `returns NoCandidates when only CLI sessions exist`() {
    val server = newServer()
    val cli1 = ctx(clientName = "TrailblazeCLI", lastActive = Clock.System.now())
    val cli2 = ctx(clientName = "TrailblazeCLI", lastActive = Clock.System.now())
    server.installSessionContextForTest("cli-1", cli1)
    server.installSessionContextForTest("cli-2", cli2)

    val result = runBlocking { server.pinMostRecentUnboundMcpSession("android") }

    assertEquals(TrailblazeMcpServer.PinResult.NoCandidates, result)
    assertNull(cli1.associatedDeviceId)
    assertNull(cli2.associatedDeviceId)
  }

  @Test
  fun `returns NoCandidates when every real-MCP-client session is already bound`() {
    val server = newServer()
    val claude = ctx(
      clientName = "Claude Code",
      associatedDevice = androidDevice, // already bound — must not be re-pinned
    )
    server.installSessionContextForTest("claude-session", claude)

    val result = runBlocking { server.pinMostRecentUnboundMcpSession("android") }

    assertEquals(TrailblazeMcpServer.PinResult.NoCandidates, result)
  }

  @Test
  fun `returns DeviceNotFound when the device spec does not resolve`() {
    val server = newServer(bridgeOverride = TestPinBridge(emptySet())) // no devices known
    val claude = ctx(clientName = "Claude Code")
    server.installSessionContextForTest("claude-session", claude)

    val result = runBlocking { server.pinMostRecentUnboundMcpSession("nonsense-instance-id") }

    val notFound = assertIs<TrailblazeMcpServer.PinResult.DeviceNotFound>(result)
    assertEquals("nonsense-instance-id", notFound.deviceSpec)
    assertNull(claude.associatedDeviceId, "Session must NOT be modified when device lookup fails")
  }

  @Test
  fun `target is applied via mcpBridge setSessionTargetForDevice when provided`() {
    val bridge = TestPinBridge(setOf(androidDevice))
    val server = newServer(bridgeOverride = bridge)
    val claude = ctx(clientName = "Claude Code")
    server.installSessionContextForTest("claude-session", claude)

    val result = runBlocking {
      server.pinMostRecentUnboundMcpSession(
        deviceSpec = "android",
        target = "myapp",
      )
    }

    assertIs<TrailblazeMcpServer.PinResult.Pinned>(result)
    assertEquals(
      androidDevice to "myapp",
      bridge.setSessionTargetCalls.single(),
      "setSessionTargetForDevice must be called with the resolved device id and target",
    )
  }

  @Test
  fun `target is not applied when blank or null`() {
    val bridge = TestPinBridge(setOf(androidDevice))
    val server = newServer(bridgeOverride = bridge)
    val claude = ctx(clientName = "Claude Code")
    server.installSessionContextForTest("claude-session", claude)

    runBlocking {
      server.pinMostRecentUnboundMcpSession(deviceSpec = "android", target = null)
      // First call bound claude-session; clear so the second call still has a
      // pinning candidate to exercise (the second call's purpose is to verify
      // that a blank-but-non-null target doesn't reach the bridge).
      claude.clearAssociatedDevice()
      server.pinMostRecentUnboundMcpSession(deviceSpec = "android", target = "   ")
    }

    assertTrue(
      bridge.setSessionTargetCalls.isEmpty(),
      "Bridge must not receive setSessionTargetForDevice when target is null/blank",
    )
  }

  @Test
  fun `session with null mcpClientName is not pinned`() {
    // Latent race: between session creation (mcpClientName = null) and the
    // initialize handshake completing (mcpClientName set), a `device connect`
    // could race and pick the unidentified session. The filter must require
    // a non-null name.
    val server = newServer()
    val unidentifiedCtx = ctx(clientName = null, lastActive = Clock.System.now())
    server.installSessionContextForTest("pending-init", unidentifiedCtx)

    val result = runBlocking { server.pinMostRecentUnboundMcpSession("android") }

    assertEquals(TrailblazeMcpServer.PinResult.NoCandidates, result)
    assertNull(
      unidentifiedCtx.associatedDeviceId,
      "Session whose mcpClientName has not yet been read from initialize must not be pinned",
    )
  }

  @Test
  fun `android-slash-typo instance id resolves to DeviceNotFound`() {
    // `android/does-not-exist` used to be accepted unconditionally — we'd
    // synthesize a TrailblazeDeviceId pointing at a non-existent device. Now
    // ANDROID instance ids must match an available device or the pin fails
    // cleanly. WEB instance ids stay virtual.
    val server = newServer(bridgeOverride = TestPinBridge(setOf(androidDevice)))
    val claude = ctx(clientName = "Claude Code")
    server.installSessionContextForTest("claude-session", claude)

    val result = runBlocking {
      server.pinMostRecentUnboundMcpSession("android/does-not-exist")
    }

    val notFound = assertIs<TrailblazeMcpServer.PinResult.DeviceNotFound>(result)
    assertEquals("android/does-not-exist", notFound.deviceSpec)
    assertNull(claude.associatedDeviceId, "Session must not be pinned to a synthetic device id")
  }

  @Test
  fun `web instance ids are virtual and accepted without bridge lookup`() {
    // WEB instance ids name a browser session (e.g. `web/checkout`) that may
    // not exist yet — we accept them unconditionally, same policy the CLI's
    // own --device flag uses.
    val server = newServer(bridgeOverride = TestPinBridge(emptySet()))
    val claude = ctx(clientName = "Claude Code")
    server.installSessionContextForTest("claude-session", claude)

    val result = runBlocking {
      server.pinMostRecentUnboundMcpSession("web/checkout")
    }

    val pinned = assertIs<TrailblazeMcpServer.PinResult.Pinned>(result)
    assertEquals("checkout", pinned.deviceId.instanceId)
    assertEquals(TrailblazeDevicePlatform.WEB, pinned.deviceId.trailblazeDevicePlatform)
  }

  @Test
  fun `successful pin registers a claim in the device-claim registry`() {
    // Without this claim, a competing session calling `device()` later would
    // see "no holder" and silently take over while the pinned client still
    // thinks it owns the device. The claim is what lets the busy-detection
    // logic fire for the pinned session.
    val server = newServer()
    val claude = ctx(clientName = "Claude Code")
    server.installSessionContextForTest("claude-session", claude)

    val result = runBlocking { server.pinMostRecentUnboundMcpSession("android") }

    assertIs<TrailblazeMcpServer.PinResult.Pinned>(result)
    val claim = server.deviceClaimRegistry.getClaim(androidDevice)
    assertNotNull(claim, "Pinning must register a device-claim entry")
    assertEquals("claude-session", claim.mcpSessionId, "Claim must belong to the pinned session")
  }

  @Test
  fun `explicit mcp-session id that has already bound a device returns ExplicitSessionNotFound when other candidates exist`() {
    // The explicit-session-id override still respects the unbound filter — a
    // session that already chose its own device must not be silently re-pinned
    // even when named explicitly. The user gets ExplicitSessionNotFound so
    // they know their --mcp-session flag pointed at something that's already
    // bound, rather than the silent NoCandidates case which hides typos.
    //
    // We include a second unbound session so the candidate list is non-empty
    // and the explicit-id branch is the path that fires (rather than the
    // empty-candidates early-return which would short-circuit to NoCandidates).
    val server = newServer()
    val claude = ctx(
      clientName = "Claude Code",
      associatedDevice = androidDevice, // already bound — won't survive filter
    )
    val cursor = ctx(clientName = "Cursor") // unbound, makes candidate list non-empty
    server.installSessionContextForTest("claude-session", claude)
    server.installSessionContextForTest("cursor-session", cursor)

    val result = runBlocking {
      server.pinMostRecentUnboundMcpSession(
        deviceSpec = "android",
        explicitSessionId = "claude-session",
      )
    }

    val miss = assertIs<TrailblazeMcpServer.PinResult.ExplicitSessionNotFound>(result)
    assertEquals("claude-session", miss.explicitSessionId)
    assertNull(cursor.associatedDeviceId, "Cursor must not be pinned just because claude wasn't a valid candidate")
  }

  @Test
  fun `explicit mcp-session id that matches no session returns ExplicitSessionNotFound`() {
    // Typo'd --mcp-session id must NOT collapse into NoCandidates (which the
    // CLI ignores silently). Surface as a distinct result so the CLI can
    // print a typo'd-id error.
    val server = newServer()
    val claude = ctx(clientName = "Claude Code")
    server.installSessionContextForTest("claude-session", claude)

    val result = runBlocking {
      server.pinMostRecentUnboundMcpSession(
        deviceSpec = "android",
        explicitSessionId = "nope-this-id-does-not-exist",
      )
    }

    val miss = assertIs<TrailblazeMcpServer.PinResult.ExplicitSessionNotFound>(result)
    assertEquals("nope-this-id-does-not-exist", miss.explicitSessionId)
    assertNull(
      claude.associatedDeviceId,
      "Other unbound real-MCP-client sessions must not be touched when --mcp-session misses",
    )
  }

  // ---------------------------------------------------------------------------
  // finalizeNewSessionClientName — post-handlePostRequest fix-up for the
  // SDK-callback race where `mcpClientName` lands null in the brief window
  // between `initialize` and the first `tools/call`. The early-return paths
  // are unit-testable here; the "actually set the name" path requires a real
  // ServerSession (constructed by the SDK) and is exercised by the live MCP
  // test in /tmp/mcp-live-test-v2.sh.
  // ---------------------------------------------------------------------------

  @Test
  fun `finalizeNewSessionClientName returns false when session does not exist`() {
    // Defensive guard: a typo'd or evicted session id should NOT crash the
    // POST handler. The post-handlePost call site reads transport.sessionId
    // which may not match a live entry in sessionContexts if the session was
    // torn down concurrently.
    val server = newServer()
    val result = server.finalizeNewSessionClientName("ghost-session-id")
    assertEquals(false, result, "Unknown session id must return false (no-op)")
  }

  @Test
  fun `finalizeNewSessionClientName returns false when mcpClientName is already set`() {
    // Re-entry case: a session that's already past the race window (e.g.
    // because the agent made a tool call and the lazy-populate fired) should
    // be left alone. The post-handlePost fix-up is a one-shot intent —
    // running it again after the name is set is a no-op.
    val server = newServer()
    val claude = ctx(clientName = "Claude Code")
    server.installSessionContextForTest("claude-session", claude)
    val result = server.finalizeNewSessionClientName("claude-session")
    assertEquals(false, result, "Already-set name must return false (no-op)")
    assertEquals("Claude Code", claude.mcpClientName, "Name must not be overwritten")
  }

  // ---------------------------------------------------------------------------
  // refreshToolsForSession + onSessionTargetChanged wiring — fires
  // `notifications/tools/list_changed` mid-session so a connected MCP client
  // (Claude Desktop / Cursor / Goose) refetches its tool list when the
  // per-device target changes. The SDK's `Server.addTool` / `removeTools`
  // calls inside `registerTools` do the actual notification dispatch; these
  // tests pin the *caller's* decision (refresh-or-don't) via the
  // [TrailblazeMcpServer.onToolsRefreshedForTest] probe.
  // ---------------------------------------------------------------------------

  @Test
  fun `refreshToolsForSession fires probe when pinning with a non-null target`() {
    val server = newServer()
    val claude = ctx(clientName = "Claude Code")
    server.installSessionContextForTest("claude-session", claude)

    val refreshed = mutableListOf<String>()
    server.onToolsRefreshedForTest = { refreshed += it }

    val result = runBlocking {
      server.pinMostRecentUnboundMcpSession(
        deviceSpec = "android",
        target = "myapp",
      )
    }

    assertIs<TrailblazeMcpServer.PinResult.Pinned>(result)
    assertEquals(
      listOf("claude-session"),
      refreshed,
      "Pinning with a non-null target must call refreshToolsForSession exactly once",
    )
  }

  @Test
  fun `refreshToolsForSession is NOT invoked when pinning with a null target`() {
    val server = newServer()
    val claude = ctx(clientName = "Claude Code")
    server.installSessionContextForTest("claude-session", claude)

    var refreshCount = 0
    // Hook refreshToolsForSession directly — if the caller-side gate works
    // (no target → no refresh), the helper is never reached. We assert on
    // the probe with a sentinel that would fire even if the Server lookup
    // returned null, so a zero count truly means "caller didn't try."
    server.onToolsRefreshedForTest = { refreshCount++ }

    runBlocking {
      server.pinMostRecentUnboundMcpSession(deviceSpec = "android", target = null)
    }

    assertEquals(
      0,
      refreshCount,
      "Caller must skip refresh when target was not provided",
    )
  }

  @Test
  fun `refreshToolsForSession is NOT invoked when the bridge rejects the target with a null return`() {
    // The bridge contract: a null return from `setSessionTargetForDevice`
    // means the target id wasn't recognized and no state was mutated. The
    // pinned session's exposed toolset therefore didn't actually change, so
    // emitting `tools/list_changed` here would force every connected client
    // to refetch for no observable reason. The pin still completes (the
    // device side already won the claim) — only the redundant refresh is
    // skipped.
    val bridge = TestPinBridge(
      knownDevices = setOf(androidDevice),
      setSessionTargetReturn = { _, _ -> null }, // simulate unknown target id
    )
    val server = newServer(bridgeOverride = bridge)
    val claude = ctx(clientName = "Claude Code")
    server.installSessionContextForTest("claude-session", claude)

    var refreshCount = 0
    server.onToolsRefreshedForTest = { refreshCount++ }

    val result = runBlocking {
      server.pinMostRecentUnboundMcpSession(
        deviceSpec = "android",
        target = "unknown-target",
      )
    }

    assertIs<TrailblazeMcpServer.PinResult.Pinned>(result)
    assertEquals(
      0,
      refreshCount,
      "Caller must skip refresh when bridge returned null (target rejected — toolset unchanged)",
    )
  }

  @Test
  fun `refreshToolsForSession is NOT invoked when the bridge throws on target apply`() {
    // The pin's bridge call is intentionally permissive (catch + log) because
    // failing the whole pin over a target write would leave the session in a
    // half-bound state. But a thrown exception still means the target write
    // didn't take effect, so the refresh must be gated on success the same
    // way the null-return branch is.
    val bridge = TestPinBridge(
      knownDevices = setOf(androidDevice),
      setSessionTargetReturn = { _, _ -> error("simulated bridge failure") },
    )
    val server = newServer(bridgeOverride = bridge)
    val claude = ctx(clientName = "Claude Code")
    server.installSessionContextForTest("claude-session", claude)

    var refreshCount = 0
    server.onToolsRefreshedForTest = { refreshCount++ }

    val result = runBlocking {
      server.pinMostRecentUnboundMcpSession(
        deviceSpec = "android",
        target = "myapp",
      )
    }

    assertIs<TrailblazeMcpServer.PinResult.Pinned>(result)
    assertEquals(
      0,
      refreshCount,
      "Caller must skip refresh when bridge threw (target apply didn't take effect)",
    )
  }

  @Test
  fun `refreshToolsForSession is a no-op for TrailblazeCLI sessions`() {
    val server = newServer()
    val cli = ctx(clientName = TRAILBLAZE_CLI_CLIENT_NAME)
    server.installSessionContextForTest("cli-session", cli)

    var refreshCount = 0
    server.onToolsRefreshedForTest = { refreshCount++ }

    // Call directly — even if a future caller forgets to filter, the helper
    // itself should refuse to act on TrailblazeCLI sessions.
    server.refreshToolsForSession("cli-session")

    assertEquals(
      0,
      refreshCount,
      "TrailblazeCLI sessions must not get a list_changed refresh — McpProxy never sees the notification",
    )
  }

  @Test
  fun `refreshToolsForSession silently no-ops on unknown session id`() {
    val server = newServer()
    var refreshCount = 0
    server.onToolsRefreshedForTest = { refreshCount++ }

    // No exception, no probe firing — just a quiet skip. Production callers
    // never branch on the outcome.
    server.refreshToolsForSession("does-not-exist")
    assertEquals(0, refreshCount, "Unknown session id must be silently ignored")
  }

  @Test
  fun `DeviceManagerToolSet fires onSessionTargetChanged after a successful set`() {
    // Wiring test for the second of the two production callsites — proves
    // that setSessionTargetForBoundDevice invokes the callback with the
    // session id so the server can route to refreshToolsForSession.
    val bridge = TestPinBridge(setOf(androidDevice))
    val sessionContext = ctx(
      clientName = "Claude Code",
      associatedDevice = androidDevice,
      sessionId = "claude-session",
    )
    val received = mutableListOf<String>()
    val toolSet = DeviceManagerToolSet(
      sessionContext = sessionContext,
      mcpBridge = bridge,
      deviceClaimRegistry = DeviceClaimRegistry(),
      onSessionTargetChanged = { received += it },
    )

    runBlocking { toolSet.setSessionTargetForBoundDevice("myapp") }

    assertEquals(
      listOf("claude-session"),
      received,
      "Callback must fire exactly once with the session id when target is set",
    )
  }

  @Test
  fun `DeviceManagerToolSet fires onSessionTargetChanged after clearing the target`() {
    // Symmetric to the set case — clearing the override also changes the
    // exposed toolset (target-scoped tools disappear), so the client must
    // refetch.
    val bridge = TestPinBridge(setOf(androidDevice))
    val sessionContext = ctx(
      clientName = "Claude Code",
      associatedDevice = androidDevice,
      sessionId = "claude-session",
    )
    val received = mutableListOf<String>()
    val toolSet = DeviceManagerToolSet(
      sessionContext = sessionContext,
      mcpBridge = bridge,
      deviceClaimRegistry = DeviceClaimRegistry(),
      onSessionTargetChanged = { received += it },
    )

    runBlocking { toolSet.setSessionTargetForBoundDevice("clear") }

    assertEquals(
      listOf("claude-session"),
      received,
      "Callback must fire exactly once with the session id when target is cleared",
    )
  }

  @Test
  fun `finalizeNewSessionClientName returns false when mcpServerSession is null`() {
    // Edge case in test fixtures (mcpServerSession is null when sessions are
    // installed via [installSessionContextForTest] without going through the
    // SDK). In production this branch is hit if the SDK has not yet wired the
    // serverSession into the context, which shouldn't happen by the time
    // handlePostRequest returns — but the guard is cheap insurance against a
    // future SDK lifecycle change. Without the null check we'd NPE.
    val server = newServer()
    val sessionWithoutServerSession = ctx(clientName = null)
    server.installSessionContextForTest("no-server-session", sessionWithoutServerSession)
    val result = server.finalizeNewSessionClientName("no-server-session")
    assertEquals(false, result, "Null mcpServerSession must return false (no-op)")
    assertNull(sessionWithoutServerSession.mcpClientName, "Name stays null")
  }
}

/**
 * Minimal bridge that knows about [knownDevices] and records calls to
 * `setSessionTargetForDevice` so tests can assert the daemon side of the
 * pin flow without booting a real driver stack.
 */
private class TestPinBridge(
  private val knownDevices: Set<TrailblazeDeviceId>,
  /**
   * Optional override for the value returned by [setSessionTargetForDevice].
   * Defaults to echoing the requested `appTargetId` back (the production-style
   * "target accepted" shape). Pass a custom lambda to simulate the bridge
   * rejecting an unknown target id (return `null`) or throwing.
   */
  private val setSessionTargetReturn: (TrailblazeDeviceId, String?) -> String? = { _, appTargetId -> appTargetId },
) : TrailblazeMcpBridge {
  val setSessionTargetCalls = mutableListOf<Pair<TrailblazeDeviceId, String?>>()

  override suspend fun selectDevice(trailblazeDeviceId: TrailblazeDeviceId): TrailblazeConnectedDeviceSummary =
    error("not used")

  override suspend fun getAvailableDevices(): Set<TrailblazeConnectedDeviceSummary> =
    knownDevices.map {
      TrailblazeConnectedDeviceSummary(
        trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
        instanceId = it.instanceId,
        description = "test-${it.instanceId}",
      )
    }.toSet()

  override suspend fun executeTrailblazeTool(
    tool: TrailblazeTool,
    blocking: Boolean,
    traceId: TraceId?,
  ): String = "[OK]"

  override suspend fun getInstalledAppIds(): Set<String> = emptySet()
  override fun getAvailableAppTargets(): Set<TrailblazeHostAppTarget> = emptySet()
  override suspend fun runYaml(
    yaml: String,
    startNewSession: Boolean,
    agentImplementation: AgentImplementation,
  ): String = ""

  override fun getCurrentlySelectedDeviceId(): TrailblazeDeviceId? = null
  override suspend fun getCurrentScreenState(): ScreenState? = null
  override fun getDirectScreenStateProvider(skipScreenshot: Boolean): ((ScreenshotScalingConfig) -> ScreenState)? = null
  override suspend fun endSession(): Boolean = true
  override fun isOnDeviceInstrumentation(): Boolean = false
  override fun getDriverType(): TrailblazeDriverType? = null
  override fun getDriverConnectionStatus(deviceId: TrailblazeDeviceId?): String? = null
  override suspend fun getScreenStateViaRpc(
    includeScreenshot: Boolean,
    screenshotScalingConfig: ScreenshotScalingConfig,
    includeAnnotatedScreenshot: Boolean,
    includeAllElements: Boolean,
  ): GetScreenStateResponse? = null

  override fun getActiveSessionId(): SessionId? = null
  override fun cancelAutomation(deviceId: TrailblazeDeviceId) {}
  override fun selectAppTarget(appTargetId: String): String? = null
  override fun getCurrentAppTargetId(): String? = null

  override fun setSessionTargetForDevice(deviceId: TrailblazeDeviceId, appTargetId: String?): String? {
    setSessionTargetCalls += deviceId to appTargetId
    return setSessionTargetReturn(deviceId, appTargetId)
  }
}
