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
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.mcp.AgentImplementation
import xyz.block.trailblaze.mcp.TrailblazeMcpBridge
import xyz.block.trailblaze.mcp.android.ondevice.rpc.GetScreenStateResponse
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.toolcalls.TrailblazeTool

/**
 * Pins which recording leg the MCP executor replays for a trail that declares a multi-device
 * CONFIGURATION.
 *
 * A configuration's name is invisible to classifier lineage — it resolves by exact selection or not
 * at all. So the failure this guards against is quiet and misleading: a fully recorded two-device
 * trail decoded without its configuration lowers to the single-device leg, or to no recording at
 * all, and the deterministic executor (no LLM to fall back on) reports "no recording" or replays the
 * wrong tools. Each test therefore asserts the tool the device actually received, not just pass/fail.
 */
class TrailExecutorDeviceConfigurationTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  /**
   * One configuration alongside a single-device leg. The two legs record DIFFERENT tools, so the
   * dispatched tool names which one was resolved.
   */
  private val oneConfigurationTrail = """
    config:
      id: test/pos-pair
      target: clock
      devices:
        android:
          driver: ANDROID_ONDEVICE_INSTRUMENTATION
        pos-pair:
          description: Dual-display pair
          devices:
            seller:
              classifier: lab-a
            buyer:
              classifier: lab-b

    trail:
      - step: "Refund on the seller display"
        recording:
          android:
            - hideKeyboard: {}
          pos-pair:
            - clearText: {}
  """.trimIndent()

  /** Two configurations: nothing in the trail says which one a run binds. */
  private val twoConfigurationsTrail = """
    config:
      id: test/two-pairs
      target: clock
      devices:
        pos-pair:
          devices:
            seller:
              classifier: lab-a
            buyer:
              classifier: lab-b
        kitchen-pair:
          devices:
            seller:
              classifier: lab-a
            kitchen:
              classifier: lab-c

    trail:
      - step: "Send the order"
        recording:
          pos-pair:
            - clearText: {}
          kitchen-pair:
            - hideKeyboard: {}
  """.trimIndent()

  /** An ordinary single-device trail — the shape every existing MCP replay uses. */
  private val singleDeviceTrail = """
    config:
      id: test/single-device
      target: clock

    trail:
      - step: "Dismiss the keyboard"
        recording:
          android:
            - hideKeyboard: {}
  """.trimIndent()

  /**
   * The whole point of deriving: an agent that says `trail(action=RUN, name=…)` on a trail with one
   * pairing means that pairing. Naming it as well is ceremony over a choice with a single option.
   */
  @Test
  fun `a trail declaring one configuration binds it without being asked to`() {
    val (result, dispatched) = runTrail(oneConfigurationTrail)

    assertTrue(result.passed, "expected the trail to replay; failed: ${result.failureReason}")
    assertEquals(
      listOf("ClearTextTrailblazeTool"),
      dispatched,
      "the configuration's leg must be replayed, not the single-device `android:` leg",
    )
  }

  @Test
  fun `an explicitly named configuration replays that configuration's leg`() {
    val (posPair, posPairDispatched) = runTrail(twoConfigurationsTrail, deviceConfiguration = "pos-pair")
    val (kitchen, kitchenDispatched) = runTrail(twoConfigurationsTrail, deviceConfiguration = "kitchen-pair")

    assertTrue(posPair.passed, "failed: ${posPair.failureReason}")
    assertTrue(kitchen.passed, "failed: ${kitchen.failureReason}")
    assertEquals(listOf("ClearTextTrailblazeTool"), posPairDispatched)
    assertEquals(listOf("HideKeyboardTrailblazeTool"), kitchenDispatched)
  }

  /**
   * Guessing here would replay a different device set than the session bound, and the damage would
   * surface as unrelated steps failing on the wrong screen — so the run is refused before step 1.
   */
  @Test
  fun `more than one configuration and no choice is refused, naming the options`() {
    val (result, dispatched) = runTrail(twoConfigurationsTrail)

    assertEquals(false, result.passed)
    assertEquals(0, result.stepsExecuted)
    assertEquals(emptyList(), dispatched, "nothing may run before the configuration is settled")
    val reason = result.failureReason.orEmpty()
    assertTrue("pos-pair" in reason && "kitchen-pair" in reason, "must name the options, was: $reason")
    assertTrue(
      "kitchen-pair, pos-pair" in reason,
      "declared names come from a Set, so the message sorts them to stay stable, was: $reason",
    )
  }

  @Test
  fun `a configuration the trail does not declare is refused, naming the declared ones`() {
    val (result, dispatched) = runTrail(twoConfigurationsTrail, deviceConfiguration = "warehouse-pair")

    assertEquals(false, result.passed)
    assertEquals(emptyList(), dispatched)
    val reason = result.failureReason.orEmpty()
    assertTrue("warehouse-pair" in reason, "must repeat what was asked for, was: $reason")
    assertTrue("pos-pair" in reason, "must name what IS declared, was: $reason")
  }

  /** A single-device trail must replay exactly as it did before configurations existed. */
  @Test
  fun `a single-device trail is unaffected`() {
    val (result, dispatched) = runTrail(singleDeviceTrail)

    assertTrue(result.passed, "expected the trail to replay; failed: ${result.failureReason}")
    assertEquals(listOf("HideKeyboardTrailblazeTool"), dispatched)
  }

  /**
   * Naming a configuration a single-device trail doesn't declare is an error rather than an ignored
   * argument: the caller named it for THIS trail, and a silent single-device run is exactly the
   * confusion the argument exists to prevent.
   */
  @Test
  fun `naming a configuration on a single-device trail is refused`() {
    val (result, dispatched) = runTrail(singleDeviceTrail, deviceConfiguration = "pos-pair")

    assertEquals(false, result.passed)
    assertEquals(emptyList(), dispatched)
    assertTrue(
      "pos-pair" in result.failureReason.orEmpty(),
      "was: ${result.failureReason}",
    )
  }

  /**
   * A trail that cannot be parsed at all must report that, whether or not a configuration was named.
   * Selection reads the same YAML first, and answering "declares no configuration named 'pos-pair'"
   * for a file with a syntax error sends the caller after the wrong problem.
   */
  @Test
  fun `malformed YAML reports the parse error, not a configuration error`() {
    val malformed = """
      config:
        id: test/broken
        devices: [ this: is not a map
      trail:
        - step: "Never runs"
    """.trimIndent()

    val (requested, requestedDispatched) = runTrail(malformed, deviceConfiguration = "pos-pair")
    val (derived, derivedDispatched) = runTrail(malformed)

    assertEquals(emptyList(), requestedDispatched)
    assertEquals(emptyList(), derivedDispatched)
    listOf(requested, derived).forEach { result ->
      assertEquals(false, result.passed)
      val reason = result.failureReason.orEmpty()
      assertTrue("Failed to parse trail YAML" in reason, "was: $reason")
      assertTrue(
        "configuration" !in reason.substringBefore("Failed to parse"),
        "the configuration message must not stand in for the parse error, was: $reason",
      )
    }
  }

  // ---- helpers ---------------------------------------------------------------------------------

  /**
   * Write [yaml] into a trails dir and replay it, returning the result plus the ordered tool class
   * names the device received. Classifiers are platform-only (`[android]`), which is what an MCP
   * session bound to one Android device resolves.
   */
  private fun runTrail(
    yaml: String,
    deviceConfiguration: String? = null,
  ): Pair<TrailExecutionResult, List<String>> {
    val trailsDir = tempFolder.newFolder()
    File(trailsDir, "pair.trail.yaml").writeText(yaml)
    val bridge = RecordingBridge()
    val result = runBlocking {
      TrailExecutorImpl(
        mcpBridge = bridge,
        sessionContext = null,
        trailsDirectory = trailsDir.absolutePath,
        deviceClassifiersProvider = { listOf(TrailblazeDeviceClassifier("android")) },
      ).executeFromFile("pair.trail.yaml", deviceConfiguration = deviceConfiguration)
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
