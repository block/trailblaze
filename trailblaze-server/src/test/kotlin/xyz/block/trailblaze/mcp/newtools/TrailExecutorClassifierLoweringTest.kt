package xyz.block.trailblaze.mcp.newtools

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.ScreenshotScalingConfig
import xyz.block.trailblaze.devices.TrailblazeConnectedDeviceSummary
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.mcp.AgentImplementation
import xyz.block.trailblaze.mcp.TrailblazeMcpBridge
import xyz.block.trailblaze.mcp.android.ondevice.rpc.GetScreenStateResponse
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.toolcalls.TrailblazeTool

/**
 * Pins what the MCP trail executor's injected [DeviceClassifiersProvider] actually decides: which
 * recording leg of a unified trail gets replayed.
 *
 * The executor is deterministic — no LLM fallback — so a step whose only recording sits under a
 * sub-category key (`android-phone:`) doesn't degrade when the classifiers are platform-only, it
 * fails outright. These tests drive `executeFromFile` on a real trail file and assert the observable
 * outcome (which tool the device receives, or the failure) for each provider, which is the whole
 * reason the provider is injectable rather than hardcoded to the platform.
 */
class TrailExecutorClassifierLoweringTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  /**
   * A step recorded under both a platform key and a more specific sub-category key. Closest-wins
   * lowering picks `android-phone:` for a phone and falls back to `android:` for a bare
   * `[android]` chain, so the dispatched tool names the leg that was chosen.
   */
  private val bothLegsTrail = """
    config:
      id: test/classifier-lowering
      target: clock

    trail:
      - step: "Dismiss the keyboard"
        recording:
          android:
            - hideKeyboard: {}
          android-phone:
            - clearText: {}
  """.trimIndent()

  /** The same step recorded ONLY for a phone — nothing for a bare `[android]` chain to resolve. */
  private val phoneOnlyTrail = """
    config:
      id: test/classifier-lowering-phone-only
      target: clock

    trail:
      - step: "Dismiss the keyboard"
        recording:
          android-phone:
            - clearText: {}
  """.trimIndent()

  @Test
  fun `a host-probed phone classifier replays the phone leg`() {
    val (result, dispatched) = runTrail(bothLegsTrail, providerReturning("android", "phone"))

    assertTrue(result.passed, "expected the trail to replay; failed: ${result.failureReason}")
    assertEquals(listOf("ClearTextTrailblazeTool"), dispatched)
  }

  @Test
  fun `a platform-only classifier replays the generic leg`() {
    val (result, dispatched) = runTrail(bothLegsTrail, providerReturning("android"))

    assertTrue(result.passed, "expected the trail to replay; failed: ${result.failureReason}")
    assertEquals(listOf("HideKeyboardTrailblazeTool"), dispatched)
  }

  /**
   * The failure fix 1 is about. Platform-only classifiers can't resolve a phone-only recording, and
   * the deterministic executor has nothing to fall back on, so the step fails — no self-heal, no
   * silent skip.
   */
  @Test
  fun `a platform-only classifier cannot resolve a phone-only recording`() {
    val (result, dispatched) = runTrail(phoneOnlyTrail, providerReturning("android"))

    assertEquals(false, result.passed)
    assertEquals(0, result.failedAtStep)
    assertTrue(
      result.stepResults.single().error?.contains("No recording") == true,
      "expected a missing-recording failure, got: ${result.stepResults.single().error}",
    )
    assertEquals(emptyList(), dispatched)
  }

  @Test
  fun `a host-probed phone classifier resolves that same phone-only recording`() {
    val (result, dispatched) = runTrail(phoneOnlyTrail, providerReturning("android", "phone"))

    assertTrue(result.passed, "expected the trail to replay; failed: ${result.failureReason}")
    assertEquals(listOf("ClearTextTrailblazeTool"), dispatched)
  }

  /**
   * The default provider stays platform-only, so an embedder that wires nothing keeps exactly the
   * behavior it had before the seam existed (including the empty list for an unbound device, which
   * is what surfaces the "bind a device first" error).
   */
  @Test
  fun `the default provider is platform-only and empty without a device`() = runBlocking {
    assertEquals(emptyList(), platformOnlyDeviceClassifiers(null))
    assertEquals(
      listOf(TrailblazeDeviceClassifier("android")),
      platformOnlyDeviceClassifiers(
        TrailblazeDeviceId("emulator-5554", TrailblazeDevicePlatform.ANDROID),
      ),
    )
  }

  // ---- helpers ---------------------------------------------------------------------------------

  /** A provider that ignores the device id and answers with [segments], modeling a probe outcome. */
  private fun providerReturning(vararg segments: String): DeviceClassifiersProvider =
    { segments.map { TrailblazeDeviceClassifier(it) } }

  /**
   * Write [yaml] into a trails dir and replay it through [TrailExecutorImpl] with [provider],
   * returning the result plus the ordered tool class names the device received.
   */
  private fun runTrail(
    yaml: String,
    provider: DeviceClassifiersProvider,
  ): Pair<TrailExecutionResult, List<String>> {
    val trailsDir = tempFolder.newFolder()
    File(trailsDir, "lowering.trail.yaml").writeText(yaml)
    val bridge = RecordingBridge()
    val result = runBlocking {
      TrailExecutorImpl(
        mcpBridge = bridge,
        sessionContext = null,
        trailsDirectory = trailsDir.absolutePath,
        deviceClassifiersProvider = provider,
      ).executeFromFile("lowering.trail.yaml")
    }
    return result to bridge.dispatched
  }

  /** Captures the ordered tool class names dispatched to the device; every other method is inert. */
  private class RecordingBridge : TrailblazeMcpBridge {
    val dispatched = mutableListOf<String>()

    override suspend fun executeTrailblazeTool(
      tool: TrailblazeTool,
      blocking: Boolean,
      traceId: TraceId?,
    ): String {
      dispatched += tool::class.simpleName.orEmpty()
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
