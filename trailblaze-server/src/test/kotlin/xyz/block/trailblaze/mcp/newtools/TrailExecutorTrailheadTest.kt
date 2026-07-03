package xyz.block.trailblaze.mcp.newtools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.ScreenshotScalingConfig
import xyz.block.trailblaze.devices.TrailblazeConnectedDeviceSummary
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.client.temp.OtherTrailblazeTool
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.mcp.AgentImplementation
import xyz.block.trailblaze.mcp.TrailblazeMcpBridge
import xyz.block.trailblaze.mcp.android.ondevice.rpc.GetScreenStateResponse
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.yaml.TrailblazeToolYamlWrapper
import xyz.block.trailblaze.yaml.unified.UnifiedTrail
import xyz.block.trailblaze.yaml.unified.UnifiedTrailAdapter
import xyz.block.trailblaze.yaml.unified.UnifiedTrailConfig
import xyz.block.trailblaze.yaml.unified.UnifiedTrailStep

/**
 * Execution-level coverage for the `trailhead:` element: instead of only checking that it *parses*
 * (that lives in the models tests), this runs a unified trail through the deterministic
 * [TrailExecutorImpl] with a recording [TrailblazeMcpBridge] and asserts the **observable dispatch
 * order** — the exact tools the device receives, in order. That is the contract every runner shares
 * (they all lower `trailhead.toPromptStep()` to the leading step 0), so pinning it here proves the
 * paradigm works end-to-end, not just on the one clock smoke-test trail.
 *
 * The pattern (lower a [UnifiedTrail] → `execute()` → assert the recorded tool sequence) is the
 * template for more execution tests; add cases by building a different [UnifiedTrail] in [dispatchedTools].
 */
class TrailExecutorTrailheadTest {

  @Test
  fun `trailhead runs first, before the trail steps`() {
    val dispatched = dispatchedTools(
      trailhead = trailheadStep(step = "Launch fresh", tool = "clock_launchApp"),
      steps = listOf(recordedStep(step = "Tap ALARM", tool = "tapOnAlarm")),
    )
    // Step 0 is the trailhead's tool; the trail's own step follows.
    assertEquals(listOf("clock_launchApp", "tapOnAlarm"), dispatched)
  }

  @Test
  fun `a trail with no trailhead dispatches only its own steps (backwards compatible)`() {
    val dispatched = dispatchedTools(
      trailhead = null,
      steps = listOf(recordedStep(step = "Tap ALARM", tool = "tapOnAlarm")),
    )
    // No leading bootstrap tool — a trailhead-less trail behaves exactly as before the element existed.
    assertEquals(listOf("tapOnAlarm"), dispatched)
  }

  @Test
  fun `per-platform trailhead runs the tool for the device under test`() {
    val trailhead = UnifiedTrailStep(
      step = "Sign in fresh",
      recordings = linkedMapOf(
        "android" to listOf(toolWrapper("android_signIn")),
        "ios" to listOf(toolWrapper("ios_signIn")),
      ),
    )
    val androidDispatched = dispatchedTools(
      trailhead = trailhead,
      steps = listOf(recordedStep(step = "Tap Pay", tool = "tapOnPay")),
      classifiers = listOf(TrailblazeDeviceClassifier("android"), TrailblazeDeviceClassifier("phone")),
    )
    assertEquals(listOf("android_signIn", "tapOnPay"), androidDispatched)
  }

  // ---- helpers: the reusable execution harness -------------------------------------------------

  /** Lower a unified trail for [classifiers], run it through [TrailExecutorImpl], and return the
   *  ordered list of tool names the bridge received (the observable device-dispatch sequence). */
  private fun dispatchedTools(
    trailhead: UnifiedTrailStep?,
    steps: List<UnifiedTrailStep>,
    classifiers: List<TrailblazeDeviceClassifier> = listOf(TrailblazeDeviceClassifier("android")),
  ): List<String> {
    val unified = UnifiedTrail(
      config = UnifiedTrailConfig(id = "test/trail", target = "clock"),
      trailhead = trailhead,
      trail = steps,
    )
    val trailItems = UnifiedTrailAdapter.lowerToTrailItems(unified, classifiers)
    val bridge = RecordingBridge()
    val result = runBlocking {
      TrailExecutorImpl(
        mcpBridge = bridge,
        sessionContext = null,
        trailsDirectory = "",
      ).execute(trailItems, trailName = "test", onProgress = null)
    }
    assertTrue(result.passed, "trail should replay deterministically; failed: ${result.failureReason}")
    return bridge.dispatched
  }

  private fun trailheadStep(step: String, tool: String) =
    UnifiedTrailStep(step = step, recordings = linkedMapOf("android" to listOf(toolWrapper(tool))))

  private fun recordedStep(step: String, tool: String) =
    UnifiedTrailStep(step = step, recordings = linkedMapOf("android" to listOf(toolWrapper(tool))))

  private fun toolWrapper(name: String) = TrailblazeToolYamlWrapper(
    name = name,
    trailblazeTool = OtherTrailblazeTool(toolName = name, raw = JsonObject(emptyMap())),
  )

  /** Captures the ordered tool names dispatched to the device; every other bridge method is inert. */
  private class RecordingBridge : TrailblazeMcpBridge {
    val dispatched = mutableListOf<String>()

    override suspend fun executeTrailblazeTool(
      tool: TrailblazeTool,
      blocking: Boolean,
      traceId: TraceId?,
    ): String {
      dispatched += (tool as? OtherTrailblazeTool)?.toolName ?: tool::class.simpleName.orEmpty()
      return "OK" // non-JSON string → treated as success by the deterministic executor
    }

    override suspend fun selectDevice(trailblazeDeviceId: TrailblazeDeviceId): TrailblazeConnectedDeviceSummary =
      throw NotImplementedError()
    override suspend fun getAvailableDevices(): Set<TrailblazeConnectedDeviceSummary> = emptySet()
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
