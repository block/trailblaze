package xyz.block.trailblaze.host

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isLessThan
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Before
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.mcp.android.ondevice.rpc.OnDeviceRpcClient
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Pins the behavior of [OnDeviceRpcClient.waitForReady]. The method is load-bearing: every host
 * flow (CLI trail execution, MCP bridge device setup) gates trail dispatch on it, and its whole
 * point is to replace the older "ping + accessibility instance check" pair that lied about
 * readiness. A regression here would reintroduce the RPC flakiness the readiness handshake
 * was added to eliminate.
 */
class OnDeviceRpcClientReadinessTest {

  private val testDeviceId =
    TrailblazeDeviceId(
      instanceId = "test-device-readiness",
      trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
    )

  private val mockServer = MockRpcServer(testDeviceId)
  private lateinit var rpcClient: OnDeviceRpcClient

  @Before
  fun setUp() {
    mockServer.start()
    rpcClient = OnDeviceRpcClient(testDeviceId)
  }

  @After
  fun tearDown() {
    rpcClient.close()
    mockServer.stop()
  }

  /** Minimal [GetScreenStateResponse] JSON — just enough fields for successful deserialization. */
  private val successBody: String = """
    {
      "viewHierarchy": {},
      "screenshotBase64": null,
      "deviceWidth": 1080,
      "deviceHeight": 1920
    }
  """.trimIndent()

  @Test
  fun `waitForReady returns immediately on a warm connection`() {
    mockServer.onPost("/rpc/GetScreenStateRequest") {
      HttpStatusCode.OK to successBody
    }
    runBlocking { rpcClient.waitForReady(timeoutMs = 5_000L) }
    // One probe only — verifies the warm path doesn't sleep unnecessarily.
    assertThat(mockServer.requestLog["/rpc/GetScreenStateRequest"]?.size ?: 0)
      .isGreaterThanOrEqualTo(1)
  }

  @Test
  fun `waitForReady polls past transient failures and returns once the device is ready`() {
    var attempt = 0
    mockServer.onPost("/rpc/GetScreenStateRequest") {
      attempt++
      if (attempt < 3) {
        HttpStatusCode.InternalServerError to
          """{"errorType":"UNKNOWN_ERROR","message":"not ready yet","details":null}"""
      } else {
        HttpStatusCode.OK to successBody
      }
    }

    // Short poll interval so the test finishes in well under the default timeout.
    runBlocking { rpcClient.waitForReady(timeoutMs = 10_000L, pollIntervalMs = 50L) }
    assertThat(attempt).isGreaterThanOrEqualTo(3)
  }

  @Test
  fun `waitForReady throws IOException after timeoutMs expires`() {
    // Always fail — simulates a genuinely broken device.
    mockServer.responseStatus = HttpStatusCode.InternalServerError
    mockServer.responseBody =
      """{"errorType":"UNKNOWN_ERROR","message":"never ready","details":"stuck in boot"}"""

    val thrown = assertFailsWith<IOException> {
      runBlocking { rpcClient.waitForReady(timeoutMs = 500L, pollIntervalMs = 50L) }
    }
    // Include a probe count in the message so operators can tell "never started" from "slow".
    assertThat(thrown.message ?: "").contains("probe(s)")
    assertThat(thrown.message ?: "").contains("never ready")
  }

  @Test
  fun `waitForReady forwards requireAndroidAccessibilityService into the probe payload`() {
    // Capture the request body for the first probe so we can assert the flag made it through.
    mockServer.onPost("/rpc/GetScreenStateRequest") { HttpStatusCode.OK to successBody }

    runBlocking {
      rpcClient.waitForReady(timeoutMs = 5_000L, requireAndroidAccessibilityService = true)
    }
    val firstProbeBody = mockServer.requestLog["/rpc/GetScreenStateRequest"]!!.first()
    val requireAndroidAccessibilityServiceFlag = Json.parseToJsonElement(firstProbeBody)
      .jsonObject["requireAndroidAccessibilityService"]!!.jsonPrimitive.content
    // The whole point of the flag: handler must see `true` for accessibility-driver flows so
    // a UiAutomator fallback can't fake readiness.
    assertThat(requireAndroidAccessibilityServiceFlag).contains("true")
  }

  @Test
  fun `waitForReady enforces overall timeout even when the HTTP client would block longer`() {
    // Simulate a server that accepts connections but never responds — the HTTP client's
    // 300s timeout would otherwise swallow our budget. The per-probe withTimeoutOrNull should
    // unstick us so the overall timeoutMs is honored.
    mockServer.onPost("/rpc/GetScreenStateRequest") {
      // Block far longer than the test's timeoutMs (500ms) to prove the probe is force-aborted.
      Thread.sleep(10_000L)
      HttpStatusCode.OK to successBody
    }

    val startMs = System.currentTimeMillis()
    val thrown = assertFailsWith<IOException> {
      runBlocking { rpcClient.waitForReady(timeoutMs = 500L, pollIntervalMs = 50L) }
    }
    val elapsedMs = System.currentTimeMillis() - startMs
    // Must abort within the budget (plus a generous cushion for Ktor graceful stop).
    assertThat(elapsedMs).isLessThan(6_000L)
    assertThat(thrown.message ?: "").contains("probe timed out")
  }
}
