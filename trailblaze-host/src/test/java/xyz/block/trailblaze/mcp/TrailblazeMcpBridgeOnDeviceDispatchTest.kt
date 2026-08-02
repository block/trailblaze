package xyz.block.trailblaze.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.llm.OnDeviceRpcTimeouts
import xyz.block.trailblaze.llm.RunYamlResponse
import xyz.block.trailblaze.llm.TrailblazeLlmModels
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.mcp.android.ondevice.rpc.OnDeviceRpcClient
import xyz.block.trailblaze.toolcalls.commands.TapOnPointTrailblazeTool
import xyz.block.trailblaze.ui.TrailblazeDeviceManager.DeviceSessionResolution
import xyz.block.trailblaze.util.UiAutomationHandleErrors

/**
 * Pins the wire contract of a single on-device MCP tool dispatch
 * ([TrailblazeMcpBridgeImpl.buildOnDeviceToolRunYamlRequest]).
 *
 * The load-bearing field is `awaitCompletion`. It used to be derived from
 * `executeTrailblazeTool`'s `blocking` flag, whose interface default is `false` — and every
 * direct-MCP dispatcher (`TrailblazeToolToMcpBridge`, `DirectMcpToolExecutor`, `TrailExecutor`,
 * `BridgeTrailblazeAgent`) takes that default. A fire-and-forget dispatch gets back a response
 * with no `success`, no `errorMessage`, and no `nonRecoverableWedge`, so those calls reported
 * phantom success and could never arm the terminal-wedge recovery added in #219.
 */
class TrailblazeMcpBridgeOnDeviceDispatchTest {

  private val deviceId = TrailblazeDeviceId(
    instanceId = "emulator-5554",
    trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
  )

  private fun buildRequest(isNewSession: Boolean = true) =
    TrailblazeMcpBridgeImpl.buildOnDeviceToolRunYamlRequest(
      tool = TapOnPointTrailblazeTool(x = 10, y = 20),
      yaml = "- tapOnPoint:\n    x: 10\n    y: 20\n",
      trailblazeDeviceId = deviceId,
      driverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
      traceId = null,
      targetAppId = "com.example.app",
      trailblazeLlmModel = TrailblazeLlmModels.GPT_4O_MINI,
      sessionResolution = DeviceSessionResolution(
        sessionId = SessionId("session-under-test"),
        isNewSession = isNewSession,
      ),
      captureNetworkTraffic = false,
    )

  /**
   * The regression this locks down: the request must ask the runner to hold the response until
   * the job reaches a terminal state, regardless of the caller's `blocking` flag.
   */
  @Test
  fun `on-device tool dispatch always awaits completion`() {
    assertTrue(
      buildRequest().awaitCompletion,
      "Direct-MCP dispatchers use blocking=false; without awaitCompletion the response carries " +
        "no terminal state and a UiAutomation wedge can never be armed",
    )
  }

  /**
   * The consequence that makes awaiting safe: `awaitCompletion = true` widens the host's socket
   * timeout past the on-device handler's own await cap, so the host reads the runner's terminal
   * response (including a wedge tag) instead of tearing the socket down first.
   */
  @Test
  fun `awaiting dispatch outlives the on-device handler await cap`() {
    val request = buildRequest()

    assertEquals(OnDeviceRpcTimeouts.HTTP_REQUEST_CAP_MS, request.requestTimeoutMs)
    assertTrue(
      request.requestTimeoutMs!! > OnDeviceRpcTimeouts.HANDLER_AWAIT_CAP_MS,
      "The host must outlast the device's own await cap so the terminal response is read",
    )
  }

  /** The rest of the single-tool dispatch envelope the host runners and session wiring rely on. */
  @Test
  fun `on-device tool dispatch keeps its MCP session envelope`() {
    val newSession = buildRequest(isNewSession = true)
    assertEquals(SessionId("session-under-test"), newSession.config.overrideSessionId)
    // The host owns session lifecycle: emit start only for a session this dispatch created,
    // and never let a single tool call end the session.
    assertTrue(newSession.config.sendSessionStartLog)
    assertFalse(newSession.config.sendSessionEndLog)
    assertFalse(buildRequest(isNewSession = false).config.sendSessionStartLog)
  }

  /**
   * End of the chain: given the awaited response above, a terminal wedge — tagged (#219's typed
   * field) or untagged (an older runner returning only the signature text) — arms the pooled
   * client's recovery callback. This is the branch a `blocking = false` dispatch could not reach
   * before, because the fire-and-forget response had `success == null`.
   */
  @Test
  fun `terminal wedge on an awaited response arms recovery`() {
    val taggedWedge = RunYamlResponse(
      sessionId = SessionId("session-under-test"),
      success = false,
      errorMessage = "on-device failure",
      nonRecoverableWedge = true,
    )
    val untaggedWedge = RunYamlResponse(
      sessionId = SessionId("session-under-test"),
      success = false,
      errorMessage = "UiAutomation is not connected and the " +
        "${UiAutomationHandleErrors.NON_RECOVERABLE_CACHE_CLEAR_FAILED_PHRASE}.",
    )
    val fireAndForget = RunYamlResponse(
      sessionId = SessionId("session-under-test"),
      memorySnapshot = emptyMap(),
    )

    assertTrue(armedBy(taggedWedge), "Typed wedge tag must arm recovery")
    assertTrue(armedBy(untaggedWedge), "Older untagged runner signature must arm recovery")
    assertFalse(
      armedBy(fireAndForget),
      "A fire-and-forget response has no terminal state — this is why the dispatch must await",
    )
  }

  private fun armedBy(response: RunYamlResponse): Boolean {
    var armed = false
    OnDeviceRpcClient(
      trailblazeDeviceId = deviceId,
      onNonRecoverableWedge = { armed = true },
    ).use { it.noteIfNonRecoverableWedge(response) }
    return armed
  }
}
