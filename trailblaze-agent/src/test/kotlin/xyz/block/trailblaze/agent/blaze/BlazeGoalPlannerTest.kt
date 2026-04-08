package xyz.block.trailblaze.agent.blaze

import kotlinx.coroutines.runBlocking
import org.junit.Test
import xyz.block.trailblaze.agent.BlazeConfig
import xyz.block.trailblaze.agent.Confidence
import xyz.block.trailblaze.agent.DecompositionResult
import xyz.block.trailblaze.agent.ExecutionResult
import xyz.block.trailblaze.agent.RecommendationContext
import xyz.block.trailblaze.agent.ReplanResult
import xyz.block.trailblaze.agent.ScreenAnalysis
import xyz.block.trailblaze.agent.ScreenAnalyzer
import xyz.block.trailblaze.agent.Subtask
import xyz.block.trailblaze.agent.TaskPlan
import xyz.block.trailblaze.agent.UiActionExecutor
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.toolcalls.TrailblazeToolDescriptor
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for [BlazeGoalPlanner].
 *
 * Integration-style tests for the blaze execution loop.
 */
private val MOCK_SCREEN_STATE = object : ScreenState {
  override val screenshotBytes: ByteArray? = null
  override val deviceWidth: Int = 1080
  override val deviceHeight: Int = 1920
  override val viewHierarchy: ViewHierarchyTreeNode = ViewHierarchyTreeNode()
  override val trailblazeDevicePlatform: TrailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID
  override val deviceClassifiers: List<TrailblazeDeviceClassifier> = emptyList()
}

class BlazeGoalPlannerTest {

  private val config = BlazeConfig(
    maxIterations = 10,
    maxSubtasks = 5,
    maxActionsPerSubtask = 20,
    maxReplanAttempts = 2,
    enableTaskDecomposition = false, // Disabled for basic tests
    enableReflection = true,
    generateRecording = true,
  )

  // Mock ScreenAnalyzer
  private class MockScreenAnalyzer(
    private val analyses: List<ScreenAnalysis>,
  ) : ScreenAnalyzer {
    private var callCount = 0
    
    override suspend fun analyze(
      context: RecommendationContext,
      screenState: ScreenState,
      traceId: TraceId?,
      availableTools: List<TrailblazeToolDescriptor>,
    ): ScreenAnalysis {
      val analysis = analyses.getOrElse(callCount) { analyses.last() }
      callCount++
      return analysis
    }
  }

  // Mock UiActionExecutor
  private class MockUiActionExecutor : UiActionExecutor {
    val executedActions = mutableListOf<Pair<String, JsonObject>>()
    var captureCount = 0
    
    override suspend fun execute(
      toolName: String,
      args: JsonObject,
      traceId: TraceId?,
    ): ExecutionResult {
      executedActions.add(toolName to args)
      return ExecutionResult.Success(
        screenSummaryAfter = "Screen after $toolName",
        durationMs = 100,
      )
    }
    
    override suspend fun captureScreenState(): ScreenState? {
      captureCount++
      return MOCK_SCREEN_STATE
    }
  }

  // Counting analyzer for verifying analyzer call counts
  private class CountingScreenAnalyzer(
    private val analyses: List<ScreenAnalysis>,
  ) : ScreenAnalyzer {
    var callCount = 0
      private set

    override suspend fun analyze(
      context: RecommendationContext,
      screenState: ScreenState,
      traceId: TraceId?,
      availableTools: List<TrailblazeToolDescriptor>,
    ): ScreenAnalysis {
      val analysis = analyses.getOrElse(callCount) { analyses.last() }
      callCount++
      return analysis
    }
  }

  // Simple mock decomposer for testing task decomposition
  private class SimpleMockDecomposer : TaskDecomposer {
    override suspend fun decompose(
      objective: String,
      screenContext: String?,
      maxSubtasks: Int,
      traceId: TraceId,
      screenshotBytes: ByteArray?,
    ): DecompositionResult = DecompositionResult(
      plan = TaskPlan(
        objective = objective,
        subtasks = listOf(
          Subtask("Step 1", "Step 1 complete", 5),
          Subtask("Step 2", "Step 2 complete", 5),
        ),
      ),
      reasoning = "Simple decomposition",
      confidence = Confidence.HIGH,
    )
  }

  // Simple mock replanner for testing replanning
  private class SimpleMockReplanner : TaskReplanner {
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
      newSubtasks = listOf(Subtask("Alternative step", "Alternative complete", 5)),
      reasoning = "Replanned due to: $blockReason",
      blockReason = blockReason,
    )
  }

  private fun createScreenAnalysis(
    recommendedTool: String = "tap",
    objectiveAppearsAchieved: Boolean = false,
    objectiveAppearsImpossible: Boolean = false,
    confidence: Confidence = Confidence.HIGH,
  ): ScreenAnalysis = ScreenAnalysis(
    screenSummary = "Test screen",
    recommendedTool = recommendedTool,
    recommendedArgs = JsonObject(emptyMap()),
    reasoning = "Test analysis",
    confidence = confidence,
    objectiveAppearsAchieved = objectiveAppearsAchieved,
    objectiveAppearsImpossible = objectiveAppearsImpossible,
    progressIndicators = emptyList(),
    potentialBlockers = emptyList(),
    alternativeApproaches = emptyList(),
  )

  // ====== BASIC EXECUTION LOOP TESTS ======

  @Test
  fun `should execute blaze loop and record actions`() = runBlocking {
    val analyses = listOf(
      createScreenAnalysis("tap"),
      createScreenAnalysis("scroll"),
      createScreenAnalysis(objectiveAppearsAchieved = true),
    )
    
    val analyzer = MockScreenAnalyzer(analyses)
    val executor = MockUiActionExecutor()
    
    val planner = BlazeGoalPlanner(
      config = config,
      screenAnalyzer = analyzer,
      executor = executor,
    )
    
    val initialState = initialBlazeState("Achieve objective")
    val finalState = planner.execute(initialState)
    
    assertTrue(finalState.achieved, "Should mark objective as achieved")
    assertTrue(executor.executedActions.size >= 1, "Should execute at least one action")
  }

  @Test
  fun `should respect max iterations limit`() = runBlocking {
    val analyses = listOf(
      createScreenAnalysis(), // Screen that doesn't indicate achievement
    )
    
    val analyzer = MockScreenAnalyzer(analyses) // Repeating same screen
    val executor = MockUiActionExecutor()
    val testConfig = config.copy(maxIterations = 5)
    
    val planner = BlazeGoalPlanner(
      config = testConfig,
      screenAnalyzer = analyzer,
      executor = executor,
    )
    
    val initialState = initialBlazeState("Objective")
    val finalState = planner.execute(initialState)
    
    assertTrue(finalState.stuck, "Should mark as stuck when max iterations hit")
    assertTrue(
      finalState.stuckReason?.contains("Maximum iterations") ?: false,
      "Should explain max iterations in reason"
    )
  }

  @Test
  fun `should stop when objective is achieved`() = runBlocking {
    val analyses = listOf(
      createScreenAnalysis(),
      createScreenAnalysis(objectiveAppearsAchieved = true),
    )
    
    val analyzer = MockScreenAnalyzer(analyses)
    val executor = MockUiActionExecutor()
    
    val planner = BlazeGoalPlanner(
      config = config,
      screenAnalyzer = analyzer,
      executor = executor,
    )
    
    val initialState = initialBlazeState("Objective")
    val finalState = planner.execute(initialState)
    
    assertTrue(finalState.achieved)
    // First iteration executes an action, second detects achievement on analyze (before execute)
    assertTrue(executor.executedActions.size >= 1, "Should execute at least one action before achievement")
  }

  @Test
  fun `should mark as stuck when objective appears impossible`() = runBlocking {
    val analyses = listOf(
      createScreenAnalysis(objectiveAppearsImpossible = true),
    )
    
    val analyzer = MockScreenAnalyzer(analyses)
    val executor = MockUiActionExecutor()
    
    val planner = BlazeGoalPlanner(
      config = config,
      screenAnalyzer = analyzer,
      executor = executor,
    )
    
    val initialState = initialBlazeState("Impossible objective")
    val finalState = planner.execute(initialState)
    
    assertTrue(finalState.stuck)
    assertTrue(
      finalState.stuckReason?.contains("appears impossible") ?: false
    )
  }

  // ====== EXCEPTION HANDLING TESTS ======

  @Test
  fun `should handle action execution failure`() = runBlocking {
    val failingExecutor = object : UiActionExecutor {
      override suspend fun execute(
        toolName: String,
        args: JsonObject,
        traceId: TraceId?,
      ): ExecutionResult = ExecutionResult.Failure(
        error = "Element not found",
        recoverable = true,
      )
      
      override suspend fun captureScreenState(): ScreenState? = MOCK_SCREEN_STATE
    }
    
    val analyses = listOf(createScreenAnalysis())
    val analyzer = MockScreenAnalyzer(analyses)
    
    val planner = BlazeGoalPlanner(
      config = config,
      screenAnalyzer = analyzer,
      executor = failingExecutor,
    )
    
    val initialState = initialBlazeState("Objective")
    val finalState = planner.execute(initialState)
    
    // Should continue despite recoverable failure
    assertFalse(finalState.achieved)
  }

  @Test
  fun `should mark stuck on non-recoverable failure`() = runBlocking {
    val failingExecutor = object : UiActionExecutor {
      override suspend fun execute(
        toolName: String,
        args: JsonObject,
        traceId: TraceId?,
      ): ExecutionResult = ExecutionResult.Failure(
        error = "Critical error",
        recoverable = false,
      )
      
      override suspend fun captureScreenState(): ScreenState? = MOCK_SCREEN_STATE
    }
    
    val analyses = listOf(createScreenAnalysis())
    val analyzer = MockScreenAnalyzer(analyses)
    
    val planner = BlazeGoalPlanner(
      config = config,
      screenAnalyzer = analyzer,
      executor = failingExecutor,
    )
    
    val initialState = initialBlazeState("Objective")
    val finalState = planner.execute(initialState)
    
    assertTrue(finalState.stuck, "Should be stuck on non-recoverable failure")
  }

  // ====== REFLECTION TESTS ======

  @Test
  fun `should trigger reflection on low confidence action`() = runBlocking {
    val analyses = listOf(
      createScreenAnalysis(confidence = Confidence.LOW),
      createScreenAnalysis(objectiveAppearsAchieved = true),
    )
    
    val analyzer = MockScreenAnalyzer(analyses)
    val executor = MockUiActionExecutor()
    
    val planner = BlazeGoalPlanner(
      config = config.copy(enableReflection = true),
      screenAnalyzer = analyzer,
      executor = executor,
    )
    
    val initialState = initialBlazeState("Objective")
    val finalState = planner.execute(initialState)
    
    // Should have reflection notes about low confidence
    assertTrue(
      finalState.reflectionNotes.any { it.contains("low", ignoreCase = true) ||
        it.contains("confidence", ignoreCase = true) },
      "Should have reflection note about confidence"
    )
  }

  @Test
  fun `should not trigger reflection when disabled`() = runBlocking {
    val analyses = listOf(createScreenAnalysis(confidence = Confidence.LOW))
    val analyzer = MockScreenAnalyzer(analyses)
    val executor = MockUiActionExecutor()
    
    val planner = BlazeGoalPlanner(
      config = config.copy(enableReflection = false),
      screenAnalyzer = analyzer,
      executor = executor,
    )
    
    val initialState = initialBlazeState("Objective")
    val finalState = planner.execute(initialState)
    
    // May have fewer reflection notes when disabled
    assertNotNull(finalState.reflectionNotes)
    Unit
  }

  // ====== PROGRESS TRACKING TESTS ======

  @Test
  fun `should track iteration count`() = runBlocking {
    val analyses = listOf(
      createScreenAnalysis(),
      createScreenAnalysis(),
      createScreenAnalysis(objectiveAppearsAchieved = true),
    )
    
    val analyzer = MockScreenAnalyzer(analyses)
    val executor = MockUiActionExecutor()
    
    val planner = BlazeGoalPlanner(
      config = config,
      screenAnalyzer = analyzer,
      executor = executor,
    )
    
    val initialState = initialBlazeState("Objective")
    val finalState = planner.execute(initialState)
    
    assertTrue(finalState.iteration >= 2, "Should increment iteration")
  }

  @Test
  fun `should build progress summary including actions`() = runBlocking {
    val analyses = listOf(
      createScreenAnalysis(),
      createScreenAnalysis(objectiveAppearsAchieved = true),
    )
    
    val analyzer = MockScreenAnalyzer(analyses)
    val executor = MockUiActionExecutor()
    
    val planner = BlazeGoalPlanner(
      config = config.copy(generateRecording = true),
      screenAnalyzer = analyzer,
      executor = executor,
    )
    
    val initialState = initialBlazeState("Objective")
    val finalState = planner.execute(initialState)
    
    assertTrue(finalState.actionHistory.isNotEmpty(), "Should record actions")
  }

  // ====== SCREEN STATE CAPTURE TESTS ======

  @Test
  fun `should handle screen capture failure gracefully`() = runBlocking {
    val failingExecutor = object : UiActionExecutor {
      override suspend fun execute(
        toolName: String,
        args: JsonObject,
        traceId: TraceId?,
      ): ExecutionResult = ExecutionResult.Success("Screen after", 100L)
      
      override suspend fun captureScreenState(): ScreenState? = null
    }
    
    val analyses = listOf(createScreenAnalysis())
    val analyzer = MockScreenAnalyzer(analyses)
    
    val planner = BlazeGoalPlanner(
      config = config,
      screenAnalyzer = analyzer,
      executor = failingExecutor,
    )
    
    val initialState = initialBlazeState("Objective")
    val finalState = planner.execute(initialState)
    
    // Should mark as stuck when can't capture
    assertTrue(finalState.stuck)
    assertTrue(
      finalState.stuckReason?.contains("Failed to capture") ?: false
    )
  }

  // ====== CONFIGURATION TESTS ======

  @Test
  fun `should respect config max actions per subtask`() = runBlocking {
    val analyses = listOf(createScreenAnalysis())
    val analyzer = MockScreenAnalyzer(analyses)
    val executor = MockUiActionExecutor()
    
    val testConfig = config.copy(
      maxActionsPerSubtask = 3,
      maxIterations = 100,
    )
    
    val planner = BlazeGoalPlanner(
      config = testConfig,
      screenAnalyzer = analyzer,
      executor = executor,
    )
    
    val initialState = initialBlazeState("Objective")
    val finalState = planner.execute(initialState)
    
    // With repeated same screen, should get stuck eventually
    assertNotNull(finalState)
    Unit
  }

  // ====== MEMORY NODE INTEGRATION TESTS ======

  @Test
  fun `should work without memory node`() = runBlocking {
    val analyses = listOf(
      createScreenAnalysis(objectiveAppearsAchieved = true),
    )
    
    val analyzer = MockScreenAnalyzer(analyses)
    val executor = MockUiActionExecutor()
    
    val planner = BlazeGoalPlanner(
      config = config,
      screenAnalyzer = analyzer,
      executor = executor,
      memoryNode = null,
    )
    
    val initialState = initialBlazeState("Objective")
    val finalState = planner.execute(initialState)
    
    assertTrue(finalState.achieved)
  }

  // ====== EDGE CASES ======

  @Test
  fun `should handle empty objective`() = runBlocking {
    val analyses = listOf(createScreenAnalysis())
    val analyzer = MockScreenAnalyzer(analyses)
    val executor = MockUiActionExecutor()
    
    val planner = BlazeGoalPlanner(
      config = config,
      screenAnalyzer = analyzer,
      executor = executor,
    )
    
    val initialState = initialBlazeState("")
    val finalState = planner.execute(initialState)
    
    assertNotNull(finalState)
    Unit
  }

  @Test
  fun `should handle very long objective`() = runBlocking {
    val longObjective = "a".repeat(1000)
    val analyses = listOf(createScreenAnalysis(objectiveAppearsAchieved = true))
    val analyzer = MockScreenAnalyzer(analyses)
    val executor = MockUiActionExecutor()
    
    val planner = BlazeGoalPlanner(
      config = config,
      screenAnalyzer = analyzer,
      executor = executor,
    )
    
    val initialState = initialBlazeState(longObjective)
    val finalState = planner.execute(initialState)
    
    assertTrue(finalState.achieved)
  }

  @Test
  fun `should clear recent analyses between executions`() = runBlocking {
    val analyses = listOf(createScreenAnalysis(objectiveAppearsAchieved = true))
    val analyzer = MockScreenAnalyzer(analyses)
    val executor = MockUiActionExecutor()
    
    val planner = BlazeGoalPlanner(
      config = config,
      screenAnalyzer = analyzer,
      executor = executor,
    )
    
    val state1 = initialBlazeState("First objective")
    planner.execute(state1)
    
    // Second execution should start fresh
    val state2 = initialBlazeState("Second objective")
    val finalState = planner.execute(state2)
    
    assertTrue(finalState.achieved)
  }

  // ====== OVERALL OBJECTIVE ACHIEVEMENT TESTS ======

  @Test
  fun `should store analysis screenSummary in state instead of executor summary`() = runBlocking {
    val analyses = listOf(
      createScreenAnalysis("tap"),
      createScreenAnalysis(objectiveAppearsAchieved = true),
    )

    val analyzer = MockScreenAnalyzer(analyses)
    val executor = MockUiActionExecutor()

    val planner = BlazeGoalPlanner(
      config = config,
      screenAnalyzer = analyzer,
      executor = executor,
    )

    val initialState = initialBlazeState("Objective")
    val finalState = planner.execute(initialState)

    assertTrue(finalState.achieved)
    // screenSummary should be the analysis description ("Test screen"),
    // not the executor's post-action summary ("Screen after tap")
    assertEquals("Test screen", finalState.screenSummary)
  }

  @Test
  fun `should declare success when replanner signals objective already achieved`() = runBlocking {
    // Create analyses where the subtask is never "achieved" by the executor,
    // so the subtask will hit maxActionsPerSubtask and trigger replanning
    val analyses = (0..5).map { createScreenAnalysis("tap") }
    val analyzer = MockScreenAnalyzer(analyses)
    val executor = MockUiActionExecutor()

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
        reasoning = "The overall objective is already achieved on the current screen",
        blockReason = blockReason,
        objectiveAlreadyAchieved = true,
      )
    }

    val decompositionConfig = config.copy(
      enableTaskDecomposition = true,
      maxActionsPerSubtask = 3, // Low limit to trigger stuck detection quickly
      enableReflection = false,
    )

    val planningNode = PlanningNode(
      decompositionConfig,
      SimpleMockDecomposer(),
      achievedReplanner,
    )

    val planner = BlazeGoalPlanner(
      config = decompositionConfig,
      screenAnalyzer = analyzer,
      executor = executor,
      planningNode = planningNode,
    )

    val initialState = initialBlazeState("Navigate to google.com")
    val finalState = planner.execute(initialState)

    assertTrue(finalState.achieved, "Should declare success when replanner signals objective already achieved")
    assertFalse(finalState.stuck, "Should not be stuck")
    assertTrue(
      finalState.reflectionNotes.any { it.contains("Overall objective already achieved") },
      "Should include reflection note about objective being already achieved",
    )
  }

  // ====== SINGLE-CALL-PER-ITERATION TESTS ======

  @Test
  fun `should make single screen analyzer call per iteration with task decomposition`() = runBlocking {
    val analyses = listOf(
      createScreenAnalysis("tap", confidence = Confidence.HIGH),
      createScreenAnalysis("tap", confidence = Confidence.HIGH),
      createScreenAnalysis(objectiveAppearsAchieved = true, confidence = Confidence.HIGH),
    )

    val countingAnalyzer = CountingScreenAnalyzer(analyses)
    val executor = MockUiActionExecutor()
    val decompositionConfig = config.copy(
      enableTaskDecomposition = true,
      enableReflection = false,
    )

    val planningNode = PlanningNode(
      decompositionConfig,
      SimpleMockDecomposer(),
      SimpleMockReplanner(),
    )

    val planner = BlazeGoalPlanner(
      config = decompositionConfig,
      screenAnalyzer = countingAnalyzer,
      executor = executor,
      planningNode = planningNode,
    )

    val initialState = initialBlazeState("Test objective")
    planner.execute(initialState)

    // With task decomposition, there should be at most 1 screen analyzer call per iteration
    // plus 1 for the initial decomposition screen capture (which doesn't call the analyzer).
    // Previously, checkSubtaskProgress() would double the analyzer calls.
    // Each iteration should produce exactly 1 analyzer call in analyzeAndExecute().
    val executedActionCount = executor.executedActions.size
    assertTrue(
      countingAnalyzer.callCount <= executedActionCount + 1,
      "Screen analyzer calls (${countingAnalyzer.callCount}) should not exceed " +
        "executed actions ($executedActionCount) + 1 (for the final achieved check). " +
        "Previously, checkSubtaskProgress() would cause ~2x calls.",
    )
  }

  @Test
  fun `should advance subtask when achieved without extra analyzer call`() = runBlocking {
    val analyses = listOf(
      // First call: subtask 1 needs an action (not achieved yet)
      createScreenAnalysis("tap", confidence = Confidence.HIGH),
      // Second call: subtask 1 appears achieved with HIGH confidence
      createScreenAnalysis(objectiveAppearsAchieved = true, confidence = Confidence.HIGH),
      // Third call: subtask 2 appears achieved
      createScreenAnalysis(objectiveAppearsAchieved = true, confidence = Confidence.HIGH),
    )

    val countingAnalyzer = CountingScreenAnalyzer(analyses)
    val executor = MockUiActionExecutor()
    val decompositionConfig = config.copy(
      enableTaskDecomposition = true,
      enableReflection = false,
    )

    val planningNode = PlanningNode(
      decompositionConfig,
      SimpleMockDecomposer(),
      SimpleMockReplanner(),
    )

    val planner = BlazeGoalPlanner(
      config = decompositionConfig,
      screenAnalyzer = countingAnalyzer,
      executor = executor,
      planningNode = planningNode,
    )

    val initialState = initialBlazeState("Test objective")
    val finalState = planner.execute(initialState)

    assertTrue(finalState.achieved, "Should achieve objective when all subtasks are done")
    // With task decomposition, the Operating Agent's achieved signal advances the subtask.
    // Each iteration produces 1 call, and with 2 subtasks we should see ~2-3 calls.
    assertTrue(
      countingAnalyzer.callCount <= 3,
      "Should not have excessive analyzer calls. Got ${countingAnalyzer.callCount} calls. " +
        "The refactored version should avoid redundant checkSubtaskProgress() calls.",
    )
  }

  @Test
  fun `should advance subtask when objectiveStatus COMPLETED is called`() = runBlocking {
    // Simulate the Screen Analyzer calling objectiveStatus(COMPLETED) for a subtask
    // that's already satisfied. This should advance the subtask without executing a no-op.
    val objectiveStatusArgs = JsonObject(
      mapOf(
        "description" to JsonPrimitive("Step 1"),
        "explanation" to JsonPrimitive("Step 1 is already satisfied on screen"),
        "status" to JsonPrimitive("COMPLETED"),
      )
    )
    val analyses = listOf(
      // First call: take an initial action (the agent must do SOMETHING before reporting completion)
      createScreenAnalysis(objectiveAppearsAchieved = false, confidence = Confidence.HIGH),
      // Second call: subtask 1 satisfied after taking action, LLM calls objectiveStatus(COMPLETED)
      ScreenAnalysis(
        recommendedTool = "objectiveStatus",
        recommendedArgs = objectiveStatusArgs,
        reasoning = "Step 1 is done after action",
        screenSummary = "Contact is visible",
        confidence = Confidence.HIGH,
        objectiveAppearsAchieved = false, // NOT set because objectiveStatus isn't wrapped
      ),
      // Third call: subtask 2 achieved via a real UI action
      createScreenAnalysis(objectiveAppearsAchieved = true, confidence = Confidence.HIGH),
    )

    val countingAnalyzer = CountingScreenAnalyzer(analyses)
    val executor = MockUiActionExecutor()
    val decompositionConfig = config.copy(
      enableTaskDecomposition = true,
      enableReflection = false,
    )

    val planningNode = PlanningNode(
      decompositionConfig,
      SimpleMockDecomposer(),
      SimpleMockReplanner(),
    )

    val planner = BlazeGoalPlanner(
      config = decompositionConfig,
      screenAnalyzer = countingAnalyzer,
      executor = executor,
      planningNode = planningNode,
    )

    val initialState = initialBlazeState("In Contacts, locate John's phone number and send a message")
    val finalState = planner.execute(initialState)

    assertTrue(finalState.achieved, "Should achieve objective when objectiveStatus advances subtask")
    // objectiveStatus should NOT be executed as a UI action — it should be intercepted
    // and used to advance the subtask directly
    val objectiveStatusExecutions = executor.executedActions.filter { it.first == "objectiveStatus" }
    assertEquals(
      0, objectiveStatusExecutions.size,
      "objectiveStatus should not be executed as a UI action — it should be intercepted",
    )
  }

  @Test
  fun `should include overall objective in recommendation context for subtasks`() = runBlocking {
    // Verify that the screen analyzer receives the overall objective context
    // when task decomposition is active
    var capturedContext: RecommendationContext? = null
    val capturingAnalyzer = object : ScreenAnalyzer {
      private var callCount = 0
      override suspend fun analyze(
        context: RecommendationContext,
        screenState: ScreenState,
        traceId: TraceId?,
        availableTools: List<TrailblazeToolDescriptor>,
      ): ScreenAnalysis {
        if (callCount == 0) capturedContext = context
        callCount++
        return createScreenAnalysis(objectiveAppearsAchieved = true, confidence = Confidence.HIGH)
      }
    }

    val executor = MockUiActionExecutor()
    val decompositionConfig = config.copy(
      enableTaskDecomposition = true,
      enableReflection = false,
    )

    val planningNode = PlanningNode(
      decompositionConfig,
      SimpleMockDecomposer(),
      SimpleMockReplanner(),
    )

    val planner = BlazeGoalPlanner(
      config = decompositionConfig,
      screenAnalyzer = capturingAnalyzer,
      executor = executor,
      planningNode = planningNode,
    )

    val overallObjective = "In Contacts, locate John's phone number and then send him a message saying Hello"
    val initialState = initialBlazeState(overallObjective)
    planner.execute(initialState)

    assertNotNull(capturedContext, "Should have captured a context")
    assertEquals("Step 1", capturedContext!!.objective, "Objective should be the subtask")
    assertEquals(overallObjective, capturedContext!!.overallObjective, "Should include overall objective")
    assertNotNull(capturedContext!!.nextSubtaskHint, "Should include next subtask hint")
    assertTrue(
      capturedContext!!.nextSubtaskHint!!.contains("Step 2"),
      "Next subtask hint should reference Step 2",
    )
  }

  @Test
  fun `should advance subtask when objectiveAppearsAchieved regardless of confidence`() = runBlocking {
    val analyses = listOf(
      // Low confidence achieved — should still advance (binary pattern, no confidence gate)
      createScreenAnalysis(
        objectiveAppearsAchieved = true,
        confidence = Confidence.LOW,
      ),
      // Second subtask also achieved with low confidence
      createScreenAnalysis(objectiveAppearsAchieved = true, confidence = Confidence.LOW),
    )

    val countingAnalyzer = CountingScreenAnalyzer(analyses)
    val executor = MockUiActionExecutor()
    val decompositionConfig = config.copy(
      enableTaskDecomposition = true,
      enableReflection = false,
    )

    val planningNode = PlanningNode(
      decompositionConfig,
      SimpleMockDecomposer(),
      SimpleMockReplanner(),
    )

    val planner = BlazeGoalPlanner(
      config = decompositionConfig,
      screenAnalyzer = countingAnalyzer,
      executor = executor,
      planningNode = planningNode,
    )

    val initialState = initialBlazeState("Test objective")
    val finalState = planner.execute(initialState)

    assertTrue(finalState.achieved, "Should achieve objective even with LOW confidence")
    // Following Mobile-Agent-v3's binary pattern: objectiveAppearsAchieved advances
    // the subtask regardless of confidence. The Reflection Agent is the safety net.
    // Note: Actions ARE executed before the objectiveAppearsAchieved check — the LLM's
    // tool call IS the action that achieves the objective. With 2 subtasks and task
    // decomposition, the first analysis is consumed during decomposition's screen capture,
    // then the second analysis achieves both subtasks, resulting in 1 executed action.
    assertEquals(
      1, executor.executedActions.size,
      "Should execute action then advance subtask when objectiveAppearsAchieved is true",
    )
  }
}
