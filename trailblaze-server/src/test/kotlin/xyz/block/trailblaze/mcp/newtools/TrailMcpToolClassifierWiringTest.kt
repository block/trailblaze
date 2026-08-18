package xyz.block.trailblaze.mcp.newtools

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.ScreenshotScalingConfig
import xyz.block.trailblaze.devices.TrailblazeConnectedDeviceSummary
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.mcp.AgentImplementation
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.mcp.TrailblazeMcpBridge
import xyz.block.trailblaze.mcp.TrailblazeMcpMode
import xyz.block.trailblaze.mcp.TrailblazeMcpSessionContext
import xyz.block.trailblaze.mcp.android.ondevice.rpc.GetScreenStateResponse
import xyz.block.trailblaze.mcp.models.McpSessionId
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.toolcalls.TrailblazeTool

/**
 * Pins that the injected [DeviceClassifiersProvider] actually reaches the decision it exists for,
 * driven through the public `trail(action=RUN)` entry point an MCP client calls.
 *
 * [TrailExecutorClassifierLoweringTest] covers the lowering rule itself by constructing
 * [TrailExecutorImpl] directly, which leaves the delivery path — the constructor parameter, and the
 * `loadTrail` call inside `handleRun` — unasserted. That gap is not theoretical: removing the
 * forwarding from this class leaves the rest of the module's suite green, and a change that kept
 * every test green while silently reverting this behavior is exactly what review caught once
 * already.
 *
 * Note that `handleRun` lowers via its own `loadTrail` call and then hands the already-lowered items
 * to `TrailExecutor.execute`, so on the MCP path THIS is the call site that picks the recording leg —
 * the executor's own provider only applies to `executeFromFile`. Both are covered: here through the
 * tool, there through the executor.
 */
class TrailMcpToolClassifierWiringTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  /**
   * One step recorded under a platform key and a sub-category key. Which tool the device receives
   * names the leg that was chosen, so it reports the provider's answer without asserting any
   * plumbing.
   */
  private val bothLegsTrail = """
    config:
      id: test/mcp-run-lowering
      target: clock

    trail:
      - step: "Dismiss the keyboard"
        recording:
          android:
            - hideKeyboard: {}
          android-phone:
            - clearText: {}
  """.trimIndent()

  /** The same step recorded ONLY for a phone — a platform-only chain has nothing to resolve. */
  private val phoneOnlyTrail = """
    config:
      id: test/mcp-run-lowering-phone-only
      target: clock

    trail:
      - step: "Dismiss the keyboard"
        recording:
          android-phone:
            - clearText: {}
  """.trimIndent()

  @Test
  fun `trail RUN replays the sub-category leg the provider resolves`() = runTest {
    val (result, dispatched) = runTrail(bothLegsTrail, providerReturning("android", "phone"))

    assertTrue(result.success, "expected the run to pass; failed with: ${result.failureReason}")
    assertEquals(listOf("ClearTextTrailblazeTool"), dispatched)
  }

  @Test
  fun `trail RUN falls back to the generic leg for a platform-only provider`() = runTest {
    val (result, dispatched) = runTrail(bothLegsTrail, providerReturning("android"))

    assertTrue(result.success, "expected the run to pass; failed with: ${result.failureReason}")
    assertEquals(listOf("HideKeyboardTrailblazeTool"), dispatched)
  }

  /**
   * The regression guard. This is the failure fix 1 removes, so it has to be reachable through the
   * tool: a phone-only recording under platform-only classifiers dispatches nothing and reports
   * failure. If the provider ever stops being forwarded, the host-probed case below degrades into
   * exactly this.
   */
  @Test
  fun `trail RUN fails a sub-category-only recording under a platform-only provider`() = runTest {
    val (result, dispatched) = runTrail(phoneOnlyTrail, providerReturning("android"))

    assertEquals(false, result.success)
    assertEquals(0, result.failedAt)
    assertTrue(
      result.failureReason?.contains("No recording") == true,
      "expected a missing-recording failure, got: ${result.failureReason}",
    )
    assertEquals(emptyList(), dispatched)
  }

  @Test
  fun `trail RUN replays that same sub-category-only recording when the provider resolves a phone`() = runTest {
    val (result, dispatched) = runTrail(phoneOnlyTrail, providerReturning("android", "phone"))

    assertTrue(result.success, "expected the run to pass; failed with: ${result.failureReason}")
    assertEquals(listOf("ClearTextTrailblazeTool"), dispatched)
  }

  /**
   * An unwired tool keeps the platform-only default, so an embedder that passes no provider behaves
   * as it did before the seam existed — the generic leg for a bound Android device, never a guessed
   * sub-category.
   */
  @Test
  fun `an unwired tool defaults to platform-only lowering`() = runTest {
    val (result, dispatched) = runTrail(bothLegsTrail, provider = null)

    assertTrue(result.success, "expected the run to pass; failed with: ${result.failureReason}")
    assertEquals(listOf("HideKeyboardTrailblazeTool"), dispatched)
  }

  // ---- helpers ---------------------------------------------------------------------------------

  /** A provider that ignores the device id and answers with [segments], modeling a probe outcome. */
  private fun providerReturning(vararg segments: String): DeviceClassifiersProvider =
    { segments.map { TrailblazeDeviceClassifier(it) } }

  /**
   * Write [yaml] into a trails dir, run it through the tool's `RUN` action with [provider], and
   * return the tool's JSON result plus the ordered tool class names the device received.
   *
   * Invoked by `name`, the form an MCP client uses (`trail(action=RUN, name='login_flow')`), which
   * resolves through the trails directory. The `file` parameter resolves relative to the daemon's
   * working directory instead, so it isn't the shape to pin behavior on.
   */
  private suspend fun runTrail(
    yaml: String,
    provider: DeviceClassifiersProvider?,
  ): Pair<TrailRunResult, List<String>> {
    val trailsDir = tempFolder.newFolder()
    File(trailsDir, "lowering.trail.yaml").writeText(yaml)
    val bridge = DeviceBridge()
    // A bound device is the precondition for lowering at all — an unbound session is refused with
    // "bind a device first", which `TrailExecutorDeviceClassifiersTest` already pins. Binding one
    // here also means the provider is handed a real device id, as it is in production.
    val sessionContext = TrailblazeMcpSessionContext(
      mcpServerSession = null,
      mcpSessionId = McpSessionId("test-session"),
      mode = TrailblazeMcpMode.MCP_CLIENT_AS_AGENT,
    ).apply { setAssociatedDevice(bridge.deviceId) }
    val tool = if (provider != null) {
      TrailMcpTool(
        sessionContext = sessionContext,
        mcpBridge = bridge,
        trailsDirectory = trailsDir.absolutePath,
        deviceClassifiersProvider = provider,
      )
    } else {
      TrailMcpTool(
        sessionContext = sessionContext,
        mcpBridge = bridge,
        trailsDirectory = trailsDir.absolutePath,
      )
    }
    val json = tool.trail(action = TrailMcpTool.TrailAction.RUN, name = "lowering")
    return TrailblazeJsonInstance.decodeFromString(TrailRunResult.serializer(), json) to bridge.dispatched
  }

  /**
   * Reports one connected Android device so `handleRun`'s auto-connect precondition is satisfied,
   * and records the ordered tool class names dispatched to it. Every other method is inert.
   */
  private class DeviceBridge : TrailblazeMcpBridge {
    val dispatched = mutableListOf<String>()

    private val device = TrailblazeConnectedDeviceSummary(
      trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
      instanceId = "emulator-5554",
      description = "Pixel 6 API 34",
    )

    val deviceId: TrailblazeDeviceId get() = device.trailblazeDeviceId

    override suspend fun executeTrailblazeTool(
      tool: TrailblazeTool,
      blocking: Boolean,
      traceId: TraceId?,
    ): String {
      dispatched += tool::class.simpleName.orEmpty()
      return "OK" // non-JSON string → treated as success by the deterministic executor
    }

    override suspend fun getAvailableDevices(): Set<TrailblazeConnectedDeviceSummary> = setOf(device)
    override suspend fun selectDevice(trailblazeDeviceId: TrailblazeDeviceId): TrailblazeConnectedDeviceSummary = device
    override suspend fun getInstalledAppIds(): Set<String> = emptySet()
    override fun getAvailableAppTargets(): Set<TrailblazeHostAppTarget> = emptySet()
    override suspend fun runYaml(
      yaml: String,
      startNewSession: Boolean,
      agentImplementation: AgentImplementation,
    ): String = throw NotImplementedError()
    override fun getCurrentlySelectedDeviceId(): TrailblazeDeviceId? = null
    override suspend fun getCurrentScreenState(): ScreenState? = null
    override suspend fun endSession(): Boolean = false
    override fun selectAppTarget(appTargetId: String): String? = null
    override fun getCurrentAppTargetId(): String? = null
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
}
