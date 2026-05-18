package xyz.block.trailblaze.agent.trail

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.block.trailblaze.agent.ExecutionResult
import xyz.block.trailblaze.agent.UiActionExecutor
import xyz.block.trailblaze.api.DriverNodeDetail
import xyz.block.trailblaze.api.DriverNodeMatch
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.api.waypoint.WaypointDefinition
import xyz.block.trailblaze.api.waypoint.WaypointSelectorEntry
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.toolcalls.commands.InputTextTrailblazeTool
import xyz.block.trailblaze.yaml.DirectionStep
import xyz.block.trailblaze.yaml.PromptStep
import xyz.block.trailblaze.yaml.StepPostcondition
import xyz.block.trailblaze.yaml.ToolRecording
import xyz.block.trailblaze.yaml.TrailblazeToolYamlWrapper

/**
 * Integration test for [DeterministicTrailExecutor] postcondition enforcement.
 *
 * Each scenario drives the real executor with a stub [UiActionExecutor] and a fake screen
 * state. The four scenarios mirror the four [xyz.block.trailblaze.waypoint.StepPostconditionAsserter.Result]
 * shapes so a regression in either layer is caught here:
 *
 *  - Matched → trail succeeds and the next step runs
 *  - NotMatched → trail fails at the declaring step with the waypoint diff in the failure reason
 *  - WaypointNotFound → trail fails at the declaring step with an "unknown waypoint" reason
 *  - Provider/resolver not injected → postcondition silently skipped (back-compat path)
 *
 * Test scenarios deliberately avoid the LLM-mode soft-warn path; that flows through
 * [TrailGoalPlanner] and is covered in its own test suite.
 */
class DeterministicTrailExecutorPostconditionTest {

  /** Counting executor that always reports tool success. */
  private class CountingExecutor : UiActionExecutor {
    val executedTools = mutableListOf<String>()
    override suspend fun execute(toolName: String, args: JsonObject, traceId: TraceId?): ExecutionResult {
      executedTools.add(toolName)
      return ExecutionResult.Success(screenSummaryAfter = "fake", durationMs = 1L)
    }
    override suspend fun captureScreenState(): ScreenState? = null
  }

  @Test
  fun postcondition_matched_allows_trail_to_complete() = runBlocking {
    val executor = CountingExecutor()
    val waypoint = waypoint("test/landed-screen", required = listOf("Landed"))
    val screen = fakeScreen(listOf("Landed"))

    val deterministic = DeterministicTrailExecutor(
      executor = executor,
      screenStateProvider = { screen },
      waypointResolver = { id -> if (id == waypoint.id) waypoint else null },
    )

    val result = deterministic.execute(stepsWithPostcondition(waypointId = waypoint.id))

    assertTrue("trail should succeed: ${result.errorMessage}", result.success)
    assertEquals(1, executor.executedTools.size)
    assertEquals("inputText", executor.executedTools.first())
  }

  @Test
  fun postcondition_mismatch_fails_at_the_declaring_step_with_waypoint_diff() = runBlocking {
    val executor = CountingExecutor()
    val waypoint = waypoint(
      id = "test/landed-screen",
      required = listOf("Landed"),
      forbidden = listOf("Modal"),
    )
    // Wrong screen: missing "Landed", and the forbidden "Modal" is present.
    val screen = fakeScreen(listOf("Modal"))

    val deterministic = DeterministicTrailExecutor(
      executor = executor,
      screenStateProvider = { screen },
      waypointResolver = { id -> if (id == waypoint.id) waypoint else null },
    )

    val result = deterministic.execute(
      stepsWithPostcondition(
        waypointId = waypoint.id,
        // Tight timeout so the test doesn't sleep for the default 5s.
        timeoutMs = 200L,
        pollIntervalMs = 50L,
      ),
    )

    assertFalse("trail should fail when postcondition mismatches", result.success)
    val reason = result.state.failureReason
    assertNotNull("failureReason should be populated", reason)
    assertTrue("reason should reference the waypoint id: $reason", reason!!.contains("test/landed-screen"))
    assertTrue("reason should call out missing-required: $reason", reason.contains("Missing required"))
    assertTrue("reason should call out present-forbidden: $reason", reason.contains("Present forbidden"))
  }

  @Test
  fun postcondition_with_unknown_waypoint_id_fails_with_unknown_message() = runBlocking {
    val executor = CountingExecutor()
    val deterministic = DeterministicTrailExecutor(
      executor = executor,
      screenStateProvider = { fakeScreen(listOf("anything")) },
      waypointResolver = { null }, // every lookup misses
    )

    val result = deterministic.execute(
      stepsWithPostcondition(waypointId = "test/does-not-exist"),
    )

    assertFalse("trail should fail when waypoint id is unknown", result.success)
    val reason = result.state.failureReason
    assertNotNull(reason)
    assertTrue(
      "reason should mention 'unknown waypoint': $reason",
      reason!!.contains("unknown waypoint"),
    )
  }

  @Test
  fun postcondition_is_silently_skipped_when_dependencies_are_not_wired() = runBlocking {
    val executor = CountingExecutor()
    // Notice: no screenStateProvider / waypointResolver — the legacy constructor path.
    val deterministic = DeterministicTrailExecutor(executor = executor)

    val result = deterministic.execute(stepsWithPostcondition(waypointId = "test/anything"))

    // Postcondition is declared but the runner has no way to evaluate it — the step's
    // tools still succeeded, so the trail should pass. This preserves existing-callsite
    // behavior for trails that adopt postconditions before every runner has been wired up.
    assertTrue("trail should pass when postcondition cannot be evaluated", result.success)
    assertEquals(1, executor.executedTools.size)
  }

  // ---- helpers ----

  private fun stepsWithPostcondition(
    waypointId: String,
    timeoutMs: Long = StepPostcondition.DEFAULT_TIMEOUT_MS,
    pollIntervalMs: Long = StepPostcondition.DEFAULT_POLL_INTERVAL_MS,
  ): List<PromptStep> = listOf(
    DirectionStep(
      step = "Type something",
      recording = ToolRecording(
        tools = listOf(
          TrailblazeToolYamlWrapper(
            name = "inputText",
            trailblazeTool = InputTextTrailblazeTool(text = "anything"),
          ),
        ),
      ),
      postcondition = StepPostcondition(
        waypoint = waypointId,
        timeoutMs = timeoutMs,
        pollIntervalMs = pollIntervalMs,
      ),
    ),
  )

  private fun waypoint(
    id: String,
    required: List<String> = emptyList(),
    forbidden: List<String> = emptyList(),
  ): WaypointDefinition = WaypointDefinition(
    id = id,
    required = required.map { text ->
      WaypointSelectorEntry(
        selector = TrailblazeNodeSelector(
          androidAccessibility = DriverNodeMatch.AndroidAccessibility(textRegex = text),
        ),
      )
    },
    forbidden = forbidden.map { text ->
      WaypointSelectorEntry(
        selector = TrailblazeNodeSelector(
          androidAccessibility = DriverNodeMatch.AndroidAccessibility(textRegex = text),
        ),
      )
    },
  )

  private fun nodeTreeWithTexts(texts: List<String>): TrailblazeNode = TrailblazeNode(
    nodeId = 1,
    children = texts.mapIndexed { i, text ->
      TrailblazeNode(
        nodeId = (i + 2).toLong(),
        driverDetail = DriverNodeDetail.AndroidAccessibility(text = text),
      )
    },
    driverDetail = DriverNodeDetail.AndroidAccessibility(),
  )

  private fun fakeScreen(texts: List<String>): ScreenState = object : ScreenState {
    override val screenshotBytes: ByteArray? = null
    override val deviceWidth: Int = 1080
    override val deviceHeight: Int = 1920
    override val viewHierarchy: ViewHierarchyTreeNode = ViewHierarchyTreeNode()
    override val trailblazeDevicePlatform: TrailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID
    override val deviceClassifiers: List<TrailblazeDeviceClassifier> = emptyList()
    override val trailblazeNodeTree: TrailblazeNode = nodeTreeWithTexts(texts)
  }
}
