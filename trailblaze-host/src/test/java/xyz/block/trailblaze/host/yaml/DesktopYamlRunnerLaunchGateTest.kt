package xyz.block.trailblaze.host.yaml

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.exception.TrailblazeException
import xyz.block.trailblaze.host.MockRpcServer
import xyz.block.trailblaze.llm.RunYamlRequest
import xyz.block.trailblaze.llm.TrailblazeLlmModels
import xyz.block.trailblaze.llm.TrailblazeReferrer
import xyz.block.trailblaze.mcp.android.ondevice.rpc.GetScreenStateResponse
import xyz.block.trailblaze.mcp.android.ondevice.rpc.OnDeviceRpcClient
import xyz.block.trailblaze.mcp.android.ondevice.rpc.OnDeviceRunnerCapabilities
import xyz.block.trailblaze.mcp.android.ondevice.rpc.RpcResult
import xyz.block.trailblaze.model.TrailblazeConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Pins the launch-path wiring of the classifier-override capability gate: it is judged on the
 * readiness handshake's answer and issues no probe of its own.
 *
 * The regression this guards is a second `GetScreenState` sent right after the handshake. The
 * device is ready — the handshake proved it — but its screen-state capture can still answer
 * "not ready" for a moment, and a gate that probed again turned that moment into a hard rejection
 * of a working `--device-classifier` run. So the mock server here answers EVERY probe not-ready:
 * a launch that goes through means the gate never asked, and a launch that is refused means the
 * probe came back.
 */
class DesktopYamlRunnerLaunchGateTest {

  private val deviceId = TrailblazeDeviceId(
    instanceId = "test-device-launch-gate",
    trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
  )

  private val mockServer = MockRpcServer(deviceId)
  private lateinit var rpcClient: OnDeviceRpcClient

  @Before
  fun setUp() {
    mockServer.start()
    rpcClient = OnDeviceRpcClient(deviceId)
    // The window the old gate fell into: readiness has already been proven, but any fresh probe
    // still comes back "not ready". Installed for the whole test so a probe from anywhere fails.
    mockServer.onPost("/rpc/GetScreenStateRequest") {
      HttpStatusCode.InternalServerError to
        """{"errorType":"UNKNOWN_ERROR","message":"Screen state capture is not ready yet","details":null}"""
    }
    mockServer.onPost("/rpc/RunYamlRequest") {
      HttpStatusCode.OK to """{"sessionId":"session-launch-gate"}"""
    }
  }

  @After
  fun tearDown() {
    rpcClient.close()
    mockServer.stop()
  }

  private fun readiness(runnerCapabilities: List<String>?) = GetScreenStateResponse(
    viewHierarchy = ViewHierarchyTreeNode(),
    screenshotBase64 = null,
    deviceWidth = 1080,
    deviceHeight = 1920,
    runnerCapabilities = runnerCapabilities,
  )

  private fun request(deviceClassifierOverride: List<String>) = RunYamlRequest(
    testName = "launch-gate",
    yaml = "- pressBack",
    trailFilePath = null,
    targetAppName = null,
    useRecordedSteps = true,
    trailblazeDeviceId = deviceId,
    trailblazeLlmModel = TrailblazeLlmModels.GPT_4O_MINI,
    config = TrailblazeConfig(),
    referrer = TrailblazeReferrer.YAML_TAB,
    deviceClassifierOverride = deviceClassifierOverride,
  )

  private fun probesSent(): Int = mockServer.requestLog["/rpc/GetScreenStateRequest"]?.size ?: 0
  private fun launchesSent(): Int = mockServer.requestLog["/rpc/RunYamlRequest"]?.size ?: 0

  @Test
  fun `an override run launches on the handshake's word alone, with no second probe`() {
    val result = runBlocking {
      DesktopYamlRunner.startYamlAfterReadiness(
        onDeviceRpc = rpcClient,
        readiness = readiness(listOf(OnDeviceRunnerCapabilities.DEVICE_CLASSIFIER_OVERRIDE)),
        runYamlRequest = request(deviceClassifierOverride = listOf("android", "phone", "es")),
      )
    }

    assertIs<RpcResult.Success<*>>(result, "a runner that advertised the capability must be allowed to launch")
    assertEquals(0, probesSent(), "the gate must not send its own GetScreenState — every probe here answers not-ready")
    assertTrue(launchesSent() >= 1, "the launch request must reach the device")
  }

  @Test
  fun `an older runner is still refused, before anything is sent`() {
    val thrown = assertFailsWith<TrailblazeException> {
      runBlocking {
        DesktopYamlRunner.startYamlAfterReadiness(
          onDeviceRpc = rpcClient,
          readiness = readiness(runnerCapabilities = null),
          runYamlRequest = request(deviceClassifierOverride = listOf("android", "phone", "es")),
        )
      }
    }

    assertTrue(
      thrown.message.orEmpty().contains("predates device classifier overrides"),
      "the refusal must still tell the operator to rebuild the runner, got: ${thrown.message}",
    )
    assertEquals(0, launchesSent(), "a refused run must not be started on the device")
    assertEquals(0, probesSent())
  }

  @Test
  fun `a run without an override never consults the capability list`() {
    val result = runBlocking {
      DesktopYamlRunner.startYamlAfterReadiness(
        onDeviceRpc = rpcClient,
        readiness = readiness(runnerCapabilities = null),
        runYamlRequest = request(deviceClassifierOverride = emptyList()),
      )
    }

    assertIs<RpcResult.Success<*>>(result)
    assertEquals(0, probesSent())
  }
}
