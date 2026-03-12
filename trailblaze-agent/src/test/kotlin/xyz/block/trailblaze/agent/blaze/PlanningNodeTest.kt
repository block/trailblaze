package xyz.block.trailblaze.agent.blaze

import kotlinx.coroutines.runBlocking
import org.junit.Test
import xyz.block.trailblaze.agent.BlazeConfig
import xyz.block.trailblaze.agent.BlazeState
import xyz.block.trailblaze.agent.Confidence
import xyz.block.trailblaze.agent.DecompositionResult
import xyz.block.trailblaze.agent.ReplanResult
import xyz.block.trailblaze.agent.ScreenAnalysis
import xyz.block.trailblaze.agent.Subtask
import xyz.block.trailblaze.agent.TaskPlan
import xyz.block.trailblaze.agent.WorkingMemory
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import xyz.block.trailblaze.agent.RecordedAction
import xyz.block.trailblaze.logs.model.TraceId
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for [PlanningNode].
 *
 * Tests task decomposition, replanning, and progress tracking.
 */
class PlanningNodeTest {

  private val testTraceId = TraceId.generate(TraceId.Companion.TraceOrigin.LLM)

  private val config = BlazeConfig(
    maxIterations = 100,
    maxSubtasks = 5,
    maxActionsPerSubtask = 20,
    maxReplanAttempts = 2,
  )

  // Mock decomposer for testing
  private class MockTaskDecomposer : TaskDecomposer {
    var decomposeCalls = 0
    
    override suspend fun decompose(
      objective: String,
      screenContext: String?,
      maxSubtasks: Int,
      traceId: TraceId,
      screenshotBytes: ByteArray?,
    ): DecompositionResult {
      decomposeCalls++
      
      return DecompositionResult(
        plan = TaskPlan(
          objective = objective,
          subtasks = listOf(
            Subtask("Step 1", "Verify step 1", 5),
            Subtask("Step 2", "Verify step 2", 5),
            Subtask("Step 3", "Verify step 3", 5),
          ),
        ),
        reasoning = "Decomposed into 3 steps",
        confidence = Confidence.HIGH,
      )
    }
  }

  // Mock replanner for testing
  private class MockTaskReplanner : TaskReplanner {
    var replanCalls = 0
    var shouldFail = false
    
    override suspend fun replan(
      originalPlan: TaskPlan,
      blockReason: String,
      currentScreenSummary: String,
      progressIndicators: List<String>,
      potentialBlockers: List<String>,
      traceId: TraceId,
      screenshotBytes: ByteArray?,
    ): ReplanResult {
      replanCalls++
      
      return if (shouldFail) {
        ReplanResult(
          originalPlan = originalPlan,
          newSubtasks = emptyList(),
          reasoning = "Replan failed",
          blockReason = blockReason,
        )
      } else {
        ReplanResult(
          originalPlan = originalPlan,
          newSubtasks = listOf(
            Subtask("Alternative step", "Verify alternative", 10),
          ),
          reasoning = "Found alternative approach",
          blockReason = blockReason,
        )
      }
    }
  }

  private fun createScreenAnalysis(
    screenSummary: String = "Test screen",
    progressIndicators: List<String> = emptyList(),
    potentialBlockers: List<String> = emptyList(),
    objectiveAppearsImpossible: Boolean = false,
  ): ScreenAnalysis = ScreenAnalysis(
    screenSummary = screenSummary,
    recommendedTool = "tap",
    recommendedArgs = JsonObject(emptyMap()),
    reasoning = "Test analysis",
    confidence = Confidence.HIGH,
    objectiveAppearsAchieved = false,
    objectiveAppearsImpossible = objectiveAppearsImpossible,
    progressIndicators = progressIndicators,
    potentialBlockers = potentialBlockers,
    alternativeApproaches = emptyList(),
  )

  // ====== DECOMPOSITION TESTS ======

  @Test
  fun `should decompose complex objective into subtasks`() = runBlocking {
    val decomposer = MockTaskDecomposer()
    val replanner = MockTaskReplanner()
    val planningNode = PlanningNode(config, decomposer, replanner)
    
    val result = planningNode.decompose(
      objective = "Order a large pepperoni pizza from Domino's and apply a discount coupon",
      screenContext = "Pizza ordering app",
      traceId = testTraceId,
    )
    
    assertEquals(1, decomposer.decomposeCalls, "Decompose should be called once")
    assertEquals(3, result.plan.subtasks.size, "Should have 3 subtasks")
    assertEquals(Confidence.HIGH, result.confidence)
  }

  @Test
  fun `should skip decomposition for simple objectives`() = runBlocking {
    val decomposer = MockTaskDecomposer()
    val replanner = MockTaskReplanner()
    val planningNode = PlanningNode(config, decomposer, replanner)
    
    val result = planningNode.decompose(
      objective = "tap button",
      screenContext = null,
      traceId = testTraceId,
    )
    
    assertEquals(0, decomposer.decomposeCalls, "Decompose should not be called for simple objectives")
    assertEquals(1, result.plan.subtasks.size, "Should create single-subtask plan")
  }

  @Test
  fun `should propagate decomposition failures`() = runBlocking {
    val failingDecomposer = object : TaskDecomposer {
      override suspend fun decompose(
        objective: String,
        screenContext: String?,
        maxSubtasks: Int,
        traceId: TraceId,
        screenshotBytes: ByteArray?,
      ): DecompositionResult {
        throw RuntimeException("LLM error")
      }
    }
    
    val replanner = MockTaskReplanner()
    val planningNode = PlanningNode(config, failingDecomposer, replanner)
    
    // PlanningNode doesn't catch exceptions — BlazeGoalPlanner handles that
    var threw = false
    try {
      planningNode.decompose(
        objective = "Order a large pepperoni pizza from Domino's and check out",
        screenContext = null,
        traceId = testTraceId,
      )
    } catch (e: RuntimeException) {
      threw = true
      assertEquals("LLM error", e.message)
    }
    assertTrue(threw, "Should propagate decomposition exception")
  }

  @Test
  fun `should normalize decomposition results`() = runBlocking {
    val decomposer = object : TaskDecomposer {
      override suspend fun decompose(
        objective: String,
        screenContext: String?,
        maxSubtasks: Int,
        traceId: TraceId,
        screenshotBytes: ByteArray?,
      ): DecompositionResult {
        return DecompositionResult(
          plan = TaskPlan(
            objective = objective,
            subtasks = listOf(
              Subtask("Step 1", "Verify", 100), // Exceeds maxActionsPerSubtask
              Subtask("Step 2", "Verify", 5),
              Subtask("Step 3", "Verify", 5),
              Subtask("Step 4", "Verify", 5),
              Subtask("Step 5", "Verify", 5),
              Subtask("Step 6", "Verify", 5), // Exceeds maxSubtasks
            ),
          ),
          reasoning = "Test",
          confidence = Confidence.HIGH,
        )
      }
    }
    
    val replanner = MockTaskReplanner()
    val planningNode = PlanningNode(config, decomposer, replanner)
    
    val result = planningNode.decompose(
      "Navigate to the store listing and compare all available products side by side", null,
      traceId = testTraceId,
    )
    
    assertEquals(5, result.plan.subtasks.size, "Should respect maxSubtasks limit")
    assertTrue(
      result.plan.subtasks[0].estimatedActions <= config.maxActionsPerSubtask,
      "Should normalize estimated actions"
    )
  }

  // ====== SUBTASK COMPLETION DETECTION TESTS ======

  @Test
  fun `should detect subtask completion by screen match`() = runBlocking {
    val decomposer = MockTaskDecomposer()
    val replanner = MockTaskReplanner()
    val planningNode = PlanningNode(config, decomposer, replanner)
    
    val taskPlan = TaskPlan(
      objective = "Test",
      subtasks = listOf(
        Subtask("Step 1", "Login screen visible", 5),
      ),
    )
    
    val state = BlazeState(
      objective = "Test",
      taskPlan = taskPlan,
      workingMemory = WorkingMemory.EMPTY,
    )
    
    val analysis = createScreenAnalysis(
      screenSummary = "Login screen visible with email field"
    )
    
    val isComplete = planningNode.isSubtaskComplete(state, analysis)
    
    assertTrue(isComplete, "Should detect subtask completion")
  }

  @Test
  fun `should detect subtask completion by progress indicators`() = runBlocking {
    val decomposer = MockTaskDecomposer()
    val replanner = MockTaskReplanner()
    val planningNode = PlanningNode(config, decomposer, replanner)
    
    val taskPlan = TaskPlan(
      objective = "Test",
      subtasks = listOf(
        Subtask("Step 1", "Cart updated", 5),
      ),
    )
    
    val state = BlazeState(
      objective = "Test",
      taskPlan = taskPlan,
      workingMemory = WorkingMemory.EMPTY,
    )
    
    val analysis = createScreenAnalysis(
      progressIndicators = listOf("Cart updated with 1 item")
    )
    
    val isComplete = planningNode.isSubtaskComplete(state, analysis)
    
    assertTrue(isComplete, "Should detect completion via progress indicators")
  }

  @Test
  fun `should not detect completion when criteria not met`() = runBlocking {
    val decomposer = MockTaskDecomposer()
    val replanner = MockTaskReplanner()
    val planningNode = PlanningNode(config, decomposer, replanner)
    
    val taskPlan = TaskPlan(
      objective = "Test",
      subtasks = listOf(
        Subtask("Step 1", "Checkout page", 5),
      ),
    )
    
    val state = BlazeState(
      objective = "Test",
      taskPlan = taskPlan,
      workingMemory = WorkingMemory.EMPTY,
    )
    
    val analysis = createScreenAnalysis(
      screenSummary = "Shopping cart page"
    )
    
    val isComplete = planningNode.isSubtaskComplete(state, analysis)
    
    assertFalse(isComplete, "Should not detect completion when criteria not met")
  }

  @Test
  fun `should handle missing current subtask gracefully`() = runBlocking {
    val decomposer = MockTaskDecomposer()
    val replanner = MockTaskReplanner()
    val planningNode = PlanningNode(config, decomposer, replanner)
    
    val state = BlazeState(
      objective = "Test",
      taskPlan = null, // No task plan
      workingMemory = WorkingMemory.EMPTY,
    )
    
    val analysis = createScreenAnalysis()
    
    val isComplete = planningNode.isSubtaskComplete(state, analysis)
    
    assertFalse(isComplete, "Should return false when no current subtask")
  }

  // ====== STUCK DETECTION TESTS ======

  @Test
  fun `should detect stuck when action count exceeds limit`() = runBlocking {
    val decomposer = MockTaskDecomposer()
    val replanner = MockTaskReplanner()
    val planningNode = PlanningNode(config, decomposer, replanner)
    
    val taskPlan = TaskPlan(
      objective = "Test",
      subtasks = listOf(Subtask("Step", "Verify", 5)),
    )
    
    val state = BlazeState(
      objective = "Test",
      taskPlan = taskPlan,
      currentSubtaskActions = config.maxActionsPerSubtask + 1, // Exceeds limit
      workingMemory = WorkingMemory.EMPTY,
    )
    
    val analysis = createScreenAnalysis()
    
    val isStuck = planningNode.isSubtaskStuck(state, analysis)
    
    assertTrue(isStuck, "Should be stuck when actions exceed limit")
  }

  @Test
  fun `should detect stuck when objective appears impossible`() = runBlocking {
    val decomposer = MockTaskDecomposer()
    val replanner = MockTaskReplanner()
    val planningNode = PlanningNode(config, decomposer, replanner)
    
    val taskPlan = TaskPlan(
      objective = "Test",
      subtasks = listOf(Subtask("Step", "Verify", 5)),
    )
    
    val state = BlazeState(
      objective = "Test",
      taskPlan = taskPlan,
      workingMemory = WorkingMemory.EMPTY,
    )
    
    val analysis = createScreenAnalysis(
      objectiveAppearsImpossible = true
    )
    
    val isStuck = planningNode.isSubtaskStuck(state, analysis)
    
    assertTrue(isStuck, "Should be stuck when objective appears impossible")
  }

  @Test
  fun `should detect stuck with repeated actions`() = runBlocking {
    val decomposer = MockTaskDecomposer()
    val replanner = MockTaskReplanner()
    val planningNode = PlanningNode(config, decomposer, replanner)
    
    val taskPlan = TaskPlan(
      objective = "Test",
      subtasks = listOf(Subtask("Step", "Verify", 5)),
    )
    
    // Create repeated tap actions
    val actionHistory = (0..4).map { index ->
      RecordedAction(
        toolName = "tap",
        toolArgs = JsonObject(emptyMap()),
        reasoning = "Tap action",
        screenSummaryBefore = "Screen",
        screenSummaryAfter = "Screen",
        confidence = Confidence.HIGH,
        durationMs = 100,
        success = true,
        errorMessage = null,
      )
    }
    
    val state = BlazeState(
      objective = "Test",
      taskPlan = taskPlan,
      actionHistory = actionHistory,
      currentSubtaskActions = actionHistory.size,
      workingMemory = WorkingMemory.EMPTY,
    )
    
    val analysis = createScreenAnalysis()
    
    val isStuck = planningNode.isSubtaskStuck(state, analysis)
    
    assertTrue(isStuck, "Should detect stuck with repeated same tool")
  }

  @Test
  fun `should detect stuck with 3 consecutive identical actions`() = runBlocking {
    val decomposer = MockTaskDecomposer()
    val replanner = MockTaskReplanner()
    val planningNode = PlanningNode(config, decomposer, replanner)

    val taskPlan = TaskPlan(
      objective = "Test",
      subtasks = listOf(Subtask("Step", "Verify", 5)),
    )

    // Create 3 identical actions (same tool + same args) — should trigger early stuck
    // This is tool-agnostic: works for any tool, not just scroll/click
    val identicalArgs = JsonObject(mapOf("direction" to JsonPrimitive("down")))
    val actionHistory = (0..2).map {
      RecordedAction(
        toolName = "scroll",
        toolArgs = identicalArgs,
        reasoning = "Scroll down",
        screenSummaryBefore = "Screen",
        screenSummaryAfter = "Screen",
        confidence = Confidence.HIGH,
        durationMs = 100,
        success = true,
        errorMessage = null,
      )
    }

    val state = BlazeState(
      objective = "Test",
      taskPlan = taskPlan,
      actionHistory = actionHistory,
      currentSubtaskActions = 3,
      workingMemory = WorkingMemory.EMPTY,
    )

    val analysis = createScreenAnalysis()

    val isStuck = planningNode.isSubtaskStuck(state, analysis)

    assertTrue(isStuck, "Should detect stuck with 3 consecutive identical actions (same tool + same args)")
  }

  @Test
  fun `should not detect stuck with different args on same tool`() = runBlocking {
    val decomposer = MockTaskDecomposer()
    val replanner = MockTaskReplanner()
    val planningNode = PlanningNode(config, decomposer, replanner)

    val taskPlan = TaskPlan(
      objective = "Test",
      subtasks = listOf(Subtask("Step", "Verify", 10)),
    )

    // Create 3 actions with same tool but different args — should NOT trigger
    val actionHistory = listOf(
      RecordedAction(
        toolName = "click",
        toolArgs = JsonObject(mapOf("x" to kotlinx.serialization.json.JsonPrimitive("100"), "y" to kotlinx.serialization.json.JsonPrimitive("200"))),
        reasoning = "Click field 1",
        screenSummaryBefore = "Screen",
        screenSummaryAfter = "Screen",
        confidence = Confidence.HIGH,
        durationMs = 100,
        success = true,
        errorMessage = null,
      ),
      RecordedAction(
        toolName = "click",
        toolArgs = JsonObject(mapOf("x" to kotlinx.serialization.json.JsonPrimitive("300"), "y" to kotlinx.serialization.json.JsonPrimitive("400"))),
        reasoning = "Click field 2",
        screenSummaryBefore = "Screen",
        screenSummaryAfter = "Screen",
        confidence = Confidence.HIGH,
        durationMs = 100,
        success = true,
        errorMessage = null,
      ),
      RecordedAction(
        toolName = "click",
        toolArgs = JsonObject(mapOf("x" to kotlinx.serialization.json.JsonPrimitive("500"), "y" to kotlinx.serialization.json.JsonPrimitive("600"))),
        reasoning = "Click field 3",
        screenSummaryBefore = "Screen",
        screenSummaryAfter = "Screen",
        confidence = Confidence.HIGH,
        durationMs = 100,
        success = true,
        errorMessage = null,
      ),
    )

    val state = BlazeState(
      objective = "Test",
      taskPlan = taskPlan,
      actionHistory = actionHistory,
      currentSubtaskActions = 3,
      workingMemory = WorkingMemory.EMPTY,
    )

    val analysis = createScreenAnalysis()

    val isStuck = planningNode.isSubtaskStuck(state, analysis)

    assertFalse(isStuck, "Should NOT detect stuck when same tool is used with different args")
  }

  @Test
  fun `should not falsely detect stuck across subtask boundaries`() = runBlocking {
    val decomposer = MockTaskDecomposer()
    val replanner = MockTaskReplanner()
    val planningNode = PlanningNode(config, decomposer, replanner)

    val taskPlan = TaskPlan(
      objective = "Test",
      subtasks = listOf(Subtask("Step A", "Verify A", 5), Subtask("Step B", "Verify B", 5)),
      currentSubtaskIndex = 1, // Now on subtask B
    )

    // Subtask A ended with 2 scroll-down actions, subtask B has 1 scroll-down.
    // Without proper scoping, this would look like 3 consecutive identical actions.
    val scrollArgs = JsonObject(mapOf("direction" to JsonPrimitive("down")))
    val actionHistory = (0..2).map {
      RecordedAction(
        toolName = "scroll",
        toolArgs = scrollArgs,
        reasoning = "Scroll down",
        screenSummaryBefore = "Screen",
        screenSummaryAfter = "Screen",
        confidence = Confidence.HIGH,
        durationMs = 100,
        success = true,
        errorMessage = null,
      )
    }

    val state = BlazeState(
      objective = "Test",
      taskPlan = taskPlan,
      actionHistory = actionHistory,
      currentSubtaskActions = 1, // Only 1 action in current subtask B
      workingMemory = WorkingMemory.EMPTY,
    )

    val analysis = createScreenAnalysis()

    val isStuck = planningNode.isSubtaskStuck(state, analysis)

    assertFalse(isStuck, "Should NOT detect stuck when identical actions span subtask boundary")
  }

  // ====== REPLANNING TESTS ======

  @Test
  fun `should replan when subtask is blocked`() = runBlocking {
    val decomposer = MockTaskDecomposer()
    val replanner = MockTaskReplanner()
    val planningNode = PlanningNode(config, decomposer, replanner)
    
    val taskPlan = TaskPlan(
      objective = "Test",
      subtasks = listOf(
        Subtask("Step 1", "Verify", 5),
        Subtask("Step 2", "Verify", 5),
      ),
    )
    
    val state = BlazeState(
      objective = "Test",
      taskPlan = taskPlan,
      workingMemory = WorkingMemory.EMPTY,
    )
    
    val analysis = createScreenAnalysis()
    
    val result = planningNode.replan(
      state = state,
      blockReason = "Element not found",
      currentScreen = analysis,
      traceId = testTraceId,
    )
    
    assertEquals(1, replanner.replanCalls, "Replanner should be called")
    assertEquals(1, result.newSubtasks.size, "Should provide alternative subtasks")
  }

  @Test
  fun `should not replan when plan is null`() = runBlocking {
    val decomposer = MockTaskDecomposer()
    val replanner = MockTaskReplanner()
    val planningNode = PlanningNode(config, decomposer, replanner)
    
    val state = BlazeState(
      objective = "Test objective",
      taskPlan = null, // No plan
      workingMemory = WorkingMemory.EMPTY,
    )
    
    val analysis = createScreenAnalysis()
    
    val result = planningNode.replan(
      state = state,
      blockReason = "Blocked",
      currentScreen = analysis,
      traceId = testTraceId,
    )
    
    assertEquals(0, replanner.replanCalls, "Replanner should not be called")
    assertEquals(1, result.newSubtasks.size, "Should provide fallback subtask")
  }

  @Test
  fun `should fail when max replan attempts exceeded`() = runBlocking {
    val decomposer = MockTaskDecomposer()
    val replanner = MockTaskReplanner()
    val planningNode = PlanningNode(config, decomposer, replanner)
    
    val taskPlan = TaskPlan(
      objective = "Test",
      subtasks = listOf(Subtask("Step", "Verify", 5)),
      replanCount = config.maxReplanAttempts, // Already at limit
    )
    
    val state = BlazeState(
      objective = "Test",
      taskPlan = taskPlan,
      workingMemory = WorkingMemory.EMPTY,
    )
    
    val analysis = createScreenAnalysis()
    
    val result = planningNode.replan(
      state = state,
      blockReason = "Blocked",
      currentScreen = analysis,
      traceId = testTraceId,
    )
    
    assertEquals(0, replanner.replanCalls, "Should not call replanner at max attempts")
    assertEquals(0, result.newSubtasks.size, "Should not provide alternatives")
  }

  @Test
  fun `should handle replanner failure gracefully`() = runBlocking {
    val decomposer = MockTaskDecomposer()
    val replanner = MockTaskReplanner().apply { shouldFail = true }
    val planningNode = PlanningNode(config, decomposer, replanner)
    
    val taskPlan = TaskPlan(
      objective = "Test",
      subtasks = listOf(Subtask("Step", "Verify", 5)),
    )
    
    val state = BlazeState(
      objective = "Test",
      taskPlan = taskPlan,
      workingMemory = WorkingMemory.EMPTY,
    )
    
    val analysis = createScreenAnalysis()
    
    val result = planningNode.replan(
      state = state,
      blockReason = "Blocked",
      currentScreen = analysis,
      traceId = testTraceId,
    )
    
    assertEquals(0, result.newSubtasks.size, "Should handle replanner failure")
  }

  @Test
  fun `should propagate objectiveAlreadyAchieved from replanner`() = runBlocking {
    val decomposer = MockTaskDecomposer()
    // Create a replanner that signals the objective is already achieved
    val achievedReplanner = object : TaskReplanner {
      override suspend fun replan(
        originalPlan: TaskPlan,
        blockReason: String,
        currentScreenSummary: String,
        progressIndicators: List<String>,
        potentialBlockers: List<String>,
        traceId: TraceId,
        screenshotBytes: ByteArray?,
      ): ReplanResult = ReplanResult(
        originalPlan = originalPlan,
        newSubtasks = emptyList(),
        reasoning = "The screen shows the Google homepage — the overall objective of navigating to google.com is already achieved",
        blockReason = blockReason,
        objectiveAlreadyAchieved = true,
      )
    }
    val planningNode = PlanningNode(config, decomposer, achievedReplanner)

    val taskPlan = TaskPlan(
      objective = "Open Chrome and navigate to google.com",
      subtasks = listOf(
        Subtask("Submit the navigation", "Google homepage is visible", 5),
      ),
    )

    val state = BlazeState(
      objective = "Open Chrome and navigate to google.com",
      taskPlan = taskPlan,
      workingMemory = WorkingMemory.EMPTY,
    )

    val analysis = createScreenAnalysis(
      screenSummary = "Chrome is open on the Google homepage with the address bar showing google.com",
    )

    val result = planningNode.replan(
      state = state,
      blockReason = "Subtask took too many actions",
      currentScreen = analysis,
      traceId = testTraceId,
    )

    assertTrue(result.objectiveAlreadyAchieved, "Should propagate objectiveAlreadyAchieved")
    assertTrue(result.newSubtasks.isEmpty(), "Should have no new subtasks when objective already achieved")
  }

  // ====== EDGE CASES ======

  @Test
  fun `should create valid single subtask for simple objectives`() = runBlocking {
    val decomposer = MockTaskDecomposer()
    val replanner = MockTaskReplanner()
    val planningNode = PlanningNode(config, decomposer, replanner)
    
    val result = planningNode.decompose("Click button", null, traceId = testTraceId)
    
    assertEquals(1, result.plan.subtasks.size)
    assertNotNull(result.plan.subtasks[0].description)
    assertNotNull(result.plan.subtasks[0].successCriteria)
    Unit
  }

  @Test
  fun `should handle very long objectives`() = runBlocking {
    val decomposer = MockTaskDecomposer()
    val replanner = MockTaskReplanner()
    val planningNode = PlanningNode(config, decomposer, replanner)
    
    val longObjective = "Login with email test@example.com, navigate to settings, " +
      "change password, verify change was successful, and then logout"
    
    val result = planningNode.decompose(longObjective, null, traceId = testTraceId)
    
    assertTrue(result.plan.subtasks.isNotEmpty(), "Should decompose long objectives")
  }
}
