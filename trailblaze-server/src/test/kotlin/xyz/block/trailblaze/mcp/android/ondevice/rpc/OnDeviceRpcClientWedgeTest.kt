package xyz.block.trailblaze.mcp.android.ondevice.rpc

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.serializer
import org.junit.After
import org.junit.Before
import org.junit.Test
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDevicePort.getTrailblazeOnDeviceSpecificPort
import xyz.block.trailblaze.llm.RunYamlResponse
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.util.UiAutomationHandleErrors
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the single-chokepoint wedge breaker: every synchronous on-device RPC flows through
 * [OnDeviceRpcClient.rpcCall], so arming [OnDeviceRpcClient.onNonRecoverableWedge] there — rather
 * than at scattered call sites — is what guarantees a wedge surfacing on ANY path (including the
 * `launchApp` pre-action, which the session-status detection can't see) force-restarts the shared
 * on-device server next trail.
 *
 * The non-recoverable signature can land in either the failure `message` (handler-caught) or
 * `details` (HTTP-error body) — see [OnDeviceRpcClient.noteIfNonRecoverableWedge] — so both fields
 * are covered:
 *  - the HTTP-error→`details` path is exercised end-to-end through a real [rpcCall] against a bound
 *    server, proving the catch arm actually invokes the breaker (the wiring the fix adds);
 *  - the `message`-field branch is asserted on the breaker predicate directly, because no HTTP
 *    transport routes a server-supplied phrase into the synthesized `message` (it always carries a
 *    fixed prefix), so an end-to-end `message` case would only be testing the HTTP stack.
 *
 * The signature is reconstructed from the same shared phrases `InstrumentationUtil` throws and
 * `UiAutomationHandleErrors` matches on, so this test fails if the emitted text and the matcher
 * ever drift apart — same discipline as DesktopYamlRunnerWedgeDecisionTest.
 */
class OnDeviceRpcClientWedgeTest {

  private val testDeviceId =
    TrailblazeDeviceId(
      instanceId = "test-device-ondevice-rpc-wedge",
      trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
    )

  private val nonRecoverableWedgeMessage =
    "${UiAutomationHandleErrors.NON_RECOVERABLE_RETRY_FAILED_PHRASE}. The on-device server's " +
      "instrumentation is in a ${UiAutomationHandleErrors.NON_RECOVERABLE_STATE_PHRASE} — kill the " +
      "test APK process and re-launch the Trailblaze on-device server to recover. Original error: " +
      "UiAutomation not connected"

  private val port = testDeviceId.getTrailblazeOnDeviceSpecificPort()

  @Volatile
  private var responseBody: String = ""

  private val server =
    embeddedServer(CIO, port = port) {
      install(ContentNegotiation) { json(TrailblazeJsonInstance) }
      routing {
        post("/rpc/{path...}") {
          // Always a 5xx so the failure routes through the HTTP-error catch arm (the raw body
          // becomes RpcResult.Failure.details). Driving the IOException arm instead would invoke
          // the adb-subprocess recovery, which has no device under a JVM unit test.
          call.respondText(responseBody, ContentType.Application.Json, HttpStatusCode.InternalServerError)
        }
      }
    }

  @Before
  fun setUp() {
    server.start(wait = false)
    Thread.sleep(300)
  }

  @After
  fun tearDown() {
    server.stop(gracePeriodMillis = 0, timeoutMillis = 500)
  }

  @Test
  fun `rpcCall arms the breaker when the failure details carry the wedge signature`() {
    responseBody =
      """{"errorType":"UNKNOWN_ERROR","message":"Failed to capture screen state","details":${
        TrailblazeJsonInstance.encodeToString(String.serializer(), nonRecoverableWedgeMessage)
      }}"""

    var armed = false
    val client = OnDeviceRpcClient(testDeviceId, onNonRecoverableWedge = { armed = true })
    val result = runBlocking { client.rpcCall(GetScreenStateRequest(includeScreenshot = false)) }

    assertTrue(result is RpcResult.Failure, "expected an RPC failure")
    assertTrue(armed, "breaker must fire when the wedge signature is in details")
  }

  @Test
  fun `rpcCall does not arm the breaker on an ordinary failure`() {
    responseBody = """{"errorType":"UNKNOWN_ERROR","message":"Element not found","details":"Element not found"}"""

    var armed = false
    val client = OnDeviceRpcClient(testDeviceId, onNonRecoverableWedge = { armed = true })
    val result = runBlocking { client.rpcCall(GetScreenStateRequest(includeScreenshot = false)) }

    assertTrue(result is RpcResult.Failure, "expected an RPC failure")
    assertFalse(armed, "ordinary failures must never arm the breaker")
  }

  @Test
  fun `the breaker fires when the wedge signature is in the failure message`() {
    var armed = false
    val client = OnDeviceRpcClient(testDeviceId, onNonRecoverableWedge = { armed = true })
    // The handler-caught path lands the signature in `message` rather than `details`.
    client.noteIfNonRecoverableWedge(message = nonRecoverableWedgeMessage, details = null)
    assertTrue(armed, "breaker must fire when the wedge signature is in message")
  }

  @Test
  fun `the breaker does not fire when neither field carries the signature`() {
    var armed = false
    val client = OnDeviceRpcClient(testDeviceId, onNonRecoverableWedge = { armed = true })
    client.noteIfNonRecoverableWedge(message = "Element not found", details = "Element not found")
    assertFalse(armed, "ordinary text in either field must never arm the breaker")
  }

  // ── Typed RunYamlResponse overload ──────────────────────────────────────────────────────────
  //
  // A wedge tagged via the structured `nonRecoverableWedge` field arrives as an
  // RpcResult.Success carrying success=false — the string matches above never see it. The typed
  // overload is the single reader every inline-failure call site routes through; its errorMessage
  // deliberately carries NO wedge phrases so the field alone drives the arming.

  @Test
  fun `the typed RunYamlResponse overload arms the breaker and reports it`() {
    var armed = false
    val client = OnDeviceRpcClient(testDeviceId, onNonRecoverableWedge = { armed = true })
    val noted = client.noteIfNonRecoverableWedge(
      RunYamlResponse(
        sessionId = SessionId("typed-wedge-overload"),
        success = false,
        errorMessage = "on-device failure",
        nonRecoverableWedge = true,
      ),
    )
    assertTrue(noted, "typed overload must report that it armed")
    assertTrue(armed, "typed overload must arm the breaker on the structured field")
  }

  @Test
  fun `the typed RunYamlResponse overload does not arm on an ordinary inline failure`() {
    var armed = false
    val client = OnDeviceRpcClient(testDeviceId, onNonRecoverableWedge = { armed = true })
    val noted = client.noteIfNonRecoverableWedge(
      RunYamlResponse(
        sessionId = SessionId("typed-wedge-overload"),
        success = false,
        errorMessage = "Element not found",
      ),
    )
    assertFalse(noted, "an untagged inline failure must report not-armed")
    assertFalse(armed, "an untagged inline failure must never arm the breaker")
  }
}
