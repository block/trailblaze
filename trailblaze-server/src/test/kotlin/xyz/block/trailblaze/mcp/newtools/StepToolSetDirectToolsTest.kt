package xyz.block.trailblaze.mcp.newtools

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import xyz.block.trailblaze.agent.Confidence
import org.junit.Test
import xyz.block.trailblaze.agent.ExecutionResult
import xyz.block.trailblaze.docs.Scenario
import xyz.block.trailblaze.agent.RecommendationContext
import xyz.block.trailblaze.agent.ScreenAnalysis
import xyz.block.trailblaze.agent.ScreenAnalyzer
import xyz.block.trailblaze.agent.UiActionExecutor
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.mcp.RecordedStepType
import xyz.block.trailblaze.mcp.TrailblazeMcpMode
import xyz.block.trailblaze.mcp.ViewHierarchyVerbosity
import xyz.block.trailblaze.mcp.TrailblazeMcpSessionContext
import xyz.block.trailblaze.mcp.models.McpSessionId
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolDescriptor
import xyz.block.trailblaze.toolcalls.commands.TapOnPointTrailblazeTool
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [StepToolSet] direct tool execution via the `tools` YAML parameter.
 *
 * Verifies that when blaze() receives a `tools` YAML string, tools are parsed and
 * executed directly via [rawToolExecutor], bypassing the AI agent pipeline.
 */
class StepToolSetDirectToolsTest {

  private val testSessionId = McpSessionId("test-session")

  /** Dummy screen state so the device-connected check passes. */
  private val dummyScreenState =
    object : ScreenState {
      override val screenshotBytes: ByteArray? = ByteArray(0)
      override val deviceWidth: Int = 1080
      override val deviceHeight: Int = 1920
      override val viewHierarchy: ViewHierarchyTreeNode = ViewHierarchyTreeNode()
      override val trailblazeDevicePlatform: TrailblazeDevicePlatform =
        TrailblazeDevicePlatform.ANDROID
      override val deviceClassifiers: List<TrailblazeDeviceClassifier> = emptyList()
    }

  /** Screen analyzer that throws if called (should not be reached in direct tool path). */
  private val throwingScreenAnalyzer =
    object : ScreenAnalyzer {
      override suspend fun analyze(
        context: RecommendationContext,
        screenState: ScreenState,
        traceId: TraceId?,
        availableTools: List<TrailblazeToolDescriptor>,
      ): ScreenAnalysis =
        throw AssertionError("ScreenAnalyzer should not be called for direct tools")
    }

  /** UI action executor that throws if called (should not be reached in direct tool path). */
  private val throwingExecutor =
    object : UiActionExecutor {
      override suspend fun execute(
        toolName: String,
        args: JsonObject,
        traceId: TraceId?,
      ): ExecutionResult =
        throw AssertionError("UiActionExecutor should not be called for direct tools")

      override suspend fun captureScreenState(): ScreenState? = null
    }

  private fun createSessionContext() =
    TrailblazeMcpSessionContext(
      mcpServerSession = null,
      mcpSessionId = testSessionId,
      mode = TrailblazeMcpMode.MCP_CLIENT_AS_AGENT,
    )

  // -- 1. Happy path: parse and execute YAML tools ----------------------------

  @Scenario(
    title = "MCP: Execute YAML tools directly via blaze",
    commands =
      [
        "blaze(objective=\"Sign in\", tools=\"- tap: {x: 100, y: 200}\\n- tap: {x: 300, y: 400}\")"
      ],
    description =
      "MCP clients pass YAML tool sequences to blaze(). Tools execute sequentially, bypassing the AI agent. The step is recorded with the NL objective for trail quality.",
    category = "Direct Tool Execution",
  )
  @Test
  fun `direct tools - happy path executes tools and returns success`() = runTest {
    val executedTools = mutableListOf<TrailblazeTool>()
    val toolSet =
      StepToolSet(
        screenAnalyzer = throwingScreenAnalyzer,
        executor = throwingExecutor,
        screenStateProvider = { _, _, _ -> dummyScreenState },
        rawToolExecutor = { tool ->
          executedTools.add(tool)
          "OK"
        },
      )

    val result =
      toolSet.blaze(
        objective ="Tap two points",
        tools =
          """
          - tools:
              - tapOnPoint:
                  x: 100
                  y: 200
              - tapOnPoint:
                  x: 300
                  y: 400
          """
            .trimIndent(),
      )

    assertEquals(2, executedTools.size)
    assertTrue(executedTools[0] is TapOnPointTrailblazeTool)
    assertTrue(executedTools[1] is TapOnPointTrailblazeTool)
    val tap1 = executedTools[0] as TapOnPointTrailblazeTool
    assertEquals(100, tap1.x)
    assertEquals(200, tap1.y)
    val tap2 = executedTools[1] as TapOnPointTrailblazeTool
    assertEquals(300, tap2.x)
    assertEquals(400, tap2.y)
    assertContains(result, "Done")
    assertContains(result, "Executed 2 tools")
  }

  // -- 2. YAML parsing: unwrapped format --------------------------------------

  @Test
  fun `direct tools - unwrapped YAML format auto-wraps and parses`() = runTest {
    val executedTools = mutableListOf<TrailblazeTool>()
    val toolSet =
      StepToolSet(
        screenAnalyzer = throwingScreenAnalyzer,
        executor = throwingExecutor,
        screenStateProvider = { _, _, _ -> dummyScreenState },
        rawToolExecutor = { tool ->
          executedTools.add(tool)
          "OK"
        },
      )

    val result =
      toolSet.blaze(
        objective ="Tap a point",
        tools = "- tapOnPoint:\n    x: 100\n    y: 200",
      )

    assertEquals(1, executedTools.size)
    assertTrue(executedTools[0] is TapOnPointTrailblazeTool)
    assertContains(result, "Executed 1 tools")
  }

  // -- 3. YAML parsing: already-wrapped format --------------------------------

  @Test
  fun `direct tools - already wrapped YAML format parses directly`() = runTest {
    val executedTools = mutableListOf<TrailblazeTool>()
    val toolSet =
      StepToolSet(
        screenAnalyzer = throwingScreenAnalyzer,
        executor = throwingExecutor,
        screenStateProvider = { _, _, _ -> dummyScreenState },
        rawToolExecutor = { tool ->
          executedTools.add(tool)
          "OK"
        },
      )

    val result =
      toolSet.blaze(
        objective ="Tap a point",
        tools =
          """
          - tools:
              - tapOnPoint:
                  x: 150
                  y: 250
          """
            .trimIndent(),
      )

    assertEquals(1, executedTools.size)
    val tap = executedTools[0] as TapOnPointTrailblazeTool
    assertEquals(150, tap.x)
    assertEquals(250, tap.y)
    assertContains(result, "Executed 1 tools")
  }

  // -- 4. Invalid YAML --------------------------------------------------------

  @Test
  fun `direct tools - invalid YAML returns parse error`() = runTest {
    var executorCalled = false
    val toolSet =
      StepToolSet(
        screenAnalyzer = throwingScreenAnalyzer,
        executor = throwingExecutor,
        screenStateProvider = { _, _, _ -> dummyScreenState },
        rawToolExecutor = { _ ->
          executorCalled = true
          "OK"
        },
      )

    val result =
      toolSet.blaze(
        objective ="Do something",
        tools = "this is not valid yaml {{",
      )

    assertFalse(executorCalled, "rawToolExecutor should not be called for invalid YAML")
    assertContains(result, "Error")
    assertContains(result, "parse")
  }

  // -- 5. Empty / no-tools YAML -----------------------------------------------

  @Test
  fun `direct tools - YAML with only prompts item returns no-tools error`() = runTest {
    var executorCalled = false
    val toolSet =
      StepToolSet(
        screenAnalyzer = throwingScreenAnalyzer,
        executor = throwingExecutor,
        screenStateProvider = { _, _, _ -> dummyScreenState },
        rawToolExecutor = { _ ->
          executorCalled = true
          "OK"
        },
      )

    // Valid trail YAML containing only a prompts item, no tools item.
    // decodeTrail will produce a PromptsTrailItem -- no ToolTrailItem.
    val result =
      toolSet.blaze(
        objective ="Do something",
        tools =
          """
          - prompts:
              - step: Just a prompt step
          """
            .trimIndent(),
      )

    assertFalse(executorCalled, "rawToolExecutor should not be called when no tools found")
    assertContains(result, "No tools found")
  }

  @Test
  fun `direct tools - empty string returns parse error`() = runTest {
    var executorCalled = false
    val toolSet =
      StepToolSet(
        screenAnalyzer = throwingScreenAnalyzer,
        executor = throwingExecutor,
        screenStateProvider = { _, _, _ -> dummyScreenState },
        rawToolExecutor = { _ ->
          executorCalled = true
          "OK"
        },
      )

    val result =
      toolSet.blaze(
        objective ="Do something",
        tools = "",
      )

    assertFalse(executorCalled, "rawToolExecutor should not be called for empty input")
    // Empty string may parse as empty trail or error -- either way no tools found
    assertContains(result, "Error")
  }

  // -- 6. Tool execution failure mid-sequence ---------------------------------

  @Test
  fun `direct tools - failure mid-sequence stops execution and records error`() = runTest {
    val executedTools = mutableListOf<String>()
    val sessionContext = createSessionContext()
    sessionContext.startImplicitRecording()

    val toolSet =
      StepToolSet(
        screenAnalyzer = throwingScreenAnalyzer,
        executor = throwingExecutor,
        screenStateProvider = { _, _, _ -> dummyScreenState },
        sessionContext = sessionContext,
        rawToolExecutor = { tool ->
          val tap = tool as TapOnPointTrailblazeTool
          executedTools.add("tap(${tap.x},${tap.y})")
          if (tap.x == 300) {
            throw RuntimeException("Device disconnected")
          }
          "OK"
        },
      )

    val result =
      toolSet.blaze(
        objective ="Tap three points",
        tools =
          """
          - tools:
              - tapOnPoint:
                  x: 100
                  y: 200
              - tapOnPoint:
                  x: 300
                  y: 400
              - tapOnPoint:
                  x: 500
                  y: 600
          """
            .trimIndent(),
      )

    // First tool executed, second failed, third not reached
    assertEquals(listOf("tap(100,200)", "tap(300,400)"), executedTools)
    assertContains(result, "Error")
    assertContains(result, "tapOnPoint")
    assertContains(result, "Device disconnected")

    // Verify step was recorded as failed
    val steps = sessionContext.getRecordedSteps()
    assertEquals(1, steps.size)
    assertFalse(steps[0].success)
    assertEquals("Tap three points", steps[0].input)
    // The first successful tool + the second failed one are both recorded
    // (actual code records only the successful ones before failure, so 1)
    assertTrue(steps[0].toolCalls.isNotEmpty())
  }

  // -- 7. No rawToolExecutor provided -----------------------------------------

  @Test
  fun `direct tools - no rawToolExecutor returns not available error`() = runTest {
    val toolSet =
      StepToolSet(
        screenAnalyzer = throwingScreenAnalyzer,
        executor = throwingExecutor,
        screenStateProvider = { _, _, _ -> dummyScreenState },
        // rawToolExecutor not provided (defaults to null)
      )

    val result =
      toolSet.blaze(
        objective ="Tap a point",
        tools =
          """
          - tools:
              - tapOnPoint:
                  x: 100
                  y: 200
          """
            .trimIndent(),
      )

    assertContains(result, "not available")
  }

  // -- 8. Step recording ------------------------------------------------------

  @Scenario(
    title = "MCP: Recorded steps include tool call details",
    commands = ["blaze(objective=\"Tap the login button\", tools=\"- tapOnPoint:\\n    x: 100\\n    y: 200\")"],
    description =
      "When recording is active, each blaze() call records the objective, executed tools, and success/failure status for trail replay.",
    category = "Trail Management",
  )
  @Test
  fun `direct tools - successful execution records step with correct type and tool calls`() =
    runTest {
      val sessionContext = createSessionContext()
      sessionContext.startImplicitRecording()

      val toolSet =
        StepToolSet(
          screenAnalyzer = throwingScreenAnalyzer,
          executor = throwingExecutor,
          screenStateProvider = { _, _, _ -> dummyScreenState },
          sessionContext = sessionContext,
          rawToolExecutor = { _ -> "tap executed" },
        )

      toolSet.blaze(
        objective ="Tap the login button",
        tools =
          """
          - tools:
              - tapOnPoint:
                  x: 100
                  y: 200
          """
            .trimIndent(),
      )

      val steps = sessionContext.getRecordedSteps()
      assertEquals(1, steps.size)

      val step = steps[0]
      assertEquals(RecordedStepType.STEP, step.type)
      assertEquals("Tap the login button", step.input)
      assertTrue(step.success)
      assertContains(step.result, "Executed 1 tools")

      assertEquals(1, step.toolCalls.size)
      assertEquals("tapOnPoint", step.toolCalls[0].toolName)
    }

  // -- 9. No device connected -------------------------------------------------

  @Test
  fun `direct tools - no device connected returns error`() = runTest {
    var executorCalled = false
    val toolSet =
      StepToolSet(
        screenAnalyzer = throwingScreenAnalyzer,
        executor = throwingExecutor,
        screenStateProvider = { _, _, _ -> null }, // No device
        rawToolExecutor = { _ ->
          executorCalled = true
          "OK"
        },
      )

    val result =
      toolSet.blaze(
        objective ="Tap a point",
        tools =
          """
          - tools:
              - tapOnPoint:
                  x: 100
                  y: 200
          """
            .trimIndent(),
      )

    assertFalse(executorCalled, "rawToolExecutor should not be called when no device connected")
    assertContains(result, "No device connected")
  }

  // -- 10. No device connected uses driverStatusProvider ----------------------

  @Test
  fun `direct tools - no device uses driver status provider message`() = runTest {
    val toolSet =
      StepToolSet(
        screenAnalyzer = throwingScreenAnalyzer,
        executor = throwingExecutor,
        screenStateProvider = { _, _, _ -> null },
        driverStatusProvider = { "Connecting to emulator-5554..." },
        rawToolExecutor = { _ -> "OK" },
      )

    val result =
      toolSet.blaze(
        objective ="Tap a point",
        tools =
          """
          - tools:
              - tapOnPoint:
                  x: 100
                  y: 200
          """
            .trimIndent(),
      )

    assertContains(result, "Connecting to emulator-5554")
  }

  // -- 11. No session context -- execution still succeeds ---------------------

  @Test
  fun `direct tools - null session context does not prevent execution`() = runTest {
    val executedTools = mutableListOf<TrailblazeTool>()
    val toolSet =
      StepToolSet(
        screenAnalyzer = throwingScreenAnalyzer,
        executor = throwingExecutor,
        screenStateProvider = { _, _, _ -> dummyScreenState },
        sessionContext = null, // No session context
        rawToolExecutor = { tool ->
          executedTools.add(tool)
          "OK"
        },
      )

    val result =
      toolSet.blaze(
        objective ="Tap a point",
        tools =
          """
          - tools:
              - tapOnPoint:
                  x: 100
                  y: 200
          """
            .trimIndent(),
      )

    assertEquals(1, executedTools.size)
    assertContains(result, "Done")
  }

  // -- 12. Tools param null falls through to normal blaze path ----------------

  @Test
  fun `blaze with null tools parameter uses normal agent path`() = runTest {
    // Verify that when tools=null, the normal ScreenAnalyzer path is invoked
    var analyzerCalled = false
    val screenAnalyzer =
      object : ScreenAnalyzer {
        override suspend fun analyze(
          context: RecommendationContext,
          screenState: ScreenState,
          traceId: TraceId?,
          availableTools: List<TrailblazeToolDescriptor>,
        ): ScreenAnalysis {
          analyzerCalled = true
          throw RuntimeException("Intentional test exception")
        }
      }

    val toolSet =
      StepToolSet(
        screenAnalyzer = screenAnalyzer,
        executor = throwingExecutor,
        screenStateProvider = { _, _, _ -> dummyScreenState },
        rawToolExecutor = { _ -> "OK" },
      )

    // tools=null means normal path -- analyzer gets called
    val result = toolSet.blaze(objective ="Do something", tools = null)

    assertTrue(analyzerCalled, "ScreenAnalyzer should be called when tools parameter is null")
    assertContains(result, "Intentional test exception")
  }

  // -- 12b. No LLM configured: blaze without tools returns clear error ----------

  @Test
  fun `blaze without tools and no LLM returns LLM-not-configured error`() = runTest {
    val toolSet =
      StepToolSet(
        screenAnalyzer = null, // No LLM configured
        executor = throwingExecutor,
        screenStateProvider = { _, _, _ -> dummyScreenState },
        rawToolExecutor = { _ -> "OK" },
      )

    val result = toolSet.blaze(objective ="Do something", tools = null)

    assertContains(result, "No AI provider configured")
    assertContains(result, "trailblaze tool")
  }

  // -- 12c. No LLM configured: blaze WITH tools still works --------------------

  @Test
  fun `blaze with tools and no LLM executes tools successfully`() = runTest {
    val executedTools = mutableListOf<TrailblazeTool>()
    val toolSet =
      StepToolSet(
        screenAnalyzer = null, // No LLM configured
        executor = throwingExecutor,
        screenStateProvider = { _, _, _ -> dummyScreenState },
        rawToolExecutor = { tool ->
          executedTools.add(tool)
          "OK"
        },
      )

    val result =
      toolSet.blaze(
        objective ="Tap a point",
        tools = "- tapOnPoint:\n    x: 100\n    y: 200",
      )

    assertEquals(1, executedTools.size)
    assertContains(result, "Executed 1 tools")
  }

  // -- 12d. No LLM configured: ask returns raw screen state --------------------

  @Test
  fun `ask without LLM returns raw screen state with guidance`() = runTest {
    val toolSet =
      StepToolSet(
        screenAnalyzer = null, // No LLM configured
        executor = throwingExecutor,
        screenStateProvider = { _, _, _ -> dummyScreenState },
        screenSummaryProvider = { "Login screen | [button] Sign in | [input] Email" },
      )

    val result = toolSet.ask(question = "What is on the screen?")

    assertContains(result, "No AI provider configured")
    assertContains(result, "trailblaze config")
    assertContains(result, "Login screen")
    assertContains(result, "[button] Sign in")
  }

  @Test
  fun `ask without LLM and no device returns device error`() = runTest {
    val toolSet =
      StepToolSet(
        screenAnalyzer = null,
        executor = throwingExecutor,
        screenStateProvider = { _, _, _ -> null },
      )

    val result = toolSet.ask(question = "What is on the screen?")

    assertContains(result, "No device connected")
  }

  // -- 12e. ask with includeScreenshot returns file path -----------------------

  @Test
  fun `ask with includeScreenshot returns screenshot path`() = runTest {
    val toolSet =
      StepToolSet(
        screenAnalyzer = null,
        executor = throwingExecutor,
        screenStateProvider = { _, _, _ -> dummyScreenState },
        screenSummaryProvider = { "Login screen" },
        screenshotSaver = { _ -> "/tmp/screenshots/screen_001.png" },
      )

    val result = toolSet.ask(question = "What's on screen?", includeScreenshot = true)

    assertContains(result, "Login screen")
    assertContains(result, "/tmp/screenshots/screen_001.png")
  }

  @Test
  fun `ask without includeScreenshot does not return screenshot path`() = runTest {
    var saverCalled = false
    val toolSet =
      StepToolSet(
        screenAnalyzer = null,
        executor = throwingExecutor,
        screenStateProvider = { _, _, _ -> dummyScreenState },
        screenSummaryProvider = { "Login screen" },
        screenshotSaver = { _ ->
          saverCalled = true
          "/tmp/screenshots/screen_001.png"
        },
      )

    val result = toolSet.ask(question = "What's on screen?")

    assertFalse(saverCalled, "screenshotSaver should not be called when includeScreenshot is false")
    assertFalse(result.contains("Screenshot"), "Result should not contain screenshot path")
  }

  // -- 12f. ask with viewHierarchy returns hierarchy at requested verbosity ----

  @Test
  fun `ask with viewHierarchy MINIMAL returns interactable elements`() = runTest {
    val toolSet =
      StepToolSet(
        screenAnalyzer = null,
        executor = throwingExecutor,
        screenStateProvider = { _, _, _ -> dummyScreenState },
        screenSummaryProvider = { "Login screen" },
      )

    val result =
      toolSet.ask(
        question = "What's on screen?",
        viewHierarchy = ViewHierarchyVerbosity.MINIMAL,
      )

    assertContains(result, "View Hierarchy")
  }

  // -- 12g. ask with LLM still includes screenshot/hierarchy when requested ---

  @Test
  fun `ask with LLM and includeScreenshot returns both answer and screenshot`() = runTest {
    val screenAnalyzer =
      object : ScreenAnalyzer {
        override suspend fun analyze(
          context: RecommendationContext,
          screenState: ScreenState,
          traceId: TraceId?,
          availableTools: List<TrailblazeToolDescriptor>,
        ): ScreenAnalysis {
          return ScreenAnalysis(
            recommendedTool = "none",
            recommendedArgs = JsonObject(emptyMap()),
            reasoning = "Login button is visible on screen",
            screenSummary = "Login screen",
            answer = "The login button is visible",
            confidence = Confidence.HIGH,
          )
        }
      }

    val toolSet =
      StepToolSet(
        screenAnalyzer = screenAnalyzer,
        executor = throwingExecutor,
        screenStateProvider = { _, _, _ -> dummyScreenState },
        screenshotSaver = { _ -> "/tmp/screenshots/screen_002.png" },
      )

    val result = toolSet.ask(question = "Is the login button visible?", includeScreenshot = true)

    assertContains(result, "The login button is visible")
    assertContains(result, "/tmp/screenshots/screen_002.png")
  }

  // -- 13. awaitScreenState: transient failure recovers -------------------------

  @Test
  fun `blaze retries when screen state is transiently null`() = runTest {
    var callCount = 0
    val executedTools = mutableListOf<TrailblazeTool>()
    val toolSet =
      StepToolSet(
        screenAnalyzer = throwingScreenAnalyzer,
        executor = throwingExecutor,
        screenStateProvider = { _, _, _ ->
          callCount++
          if (callCount <= 2) null else dummyScreenState // Succeeds on 3rd call
        },
        rawToolExecutor = { tool ->
          executedTools.add(tool)
          "OK"
        },
      )

    val result =
      toolSet.blaze(
        objective ="Tap a point",
        tools =
          """
          - tools:
              - tapOnPoint:
                  x: 100
                  y: 200
          """
            .trimIndent(),
      )

    assertTrue(callCount >= 3, "screenStateProvider should have been called multiple times")
    assertEquals(1, executedTools.size, "Tool should execute after screen state becomes available")
    assertContains(result, "Executed 1 tools")
  }

  // -- 14. Screen summary included after direct tool execution -----------------

  @Test
  fun `direct tools - screen summary included in result when provider is set`() = runTest {
    val toolSet =
      StepToolSet(
        screenAnalyzer = throwingScreenAnalyzer,
        executor = throwingExecutor,
        screenStateProvider = { _, _, _ -> dummyScreenState },
        rawToolExecutor = { _ -> "OK" },
        screenSummaryProvider = { "Login screen | [button] Sign in | [input] Email" },
      )

    val result =
      toolSet.blaze(
        objective ="Tap a point",
        tools = "- tapOnPoint:\n    x: 100\n    y: 200",
      )

    assertContains(result, "Done")
    assertContains(result, "Executed 1 tools")
    assertContains(result, "**Screen:**")
    assertContains(result, "Login screen")
    assertContains(result, "[button] Sign in")
  }

  @Test
  fun `direct tools - no screen summary when provider is null`() = runTest {
    val toolSet =
      StepToolSet(
        screenAnalyzer = throwingScreenAnalyzer,
        executor = throwingExecutor,
        screenStateProvider = { _, _, _ -> dummyScreenState },
        rawToolExecutor = { _ -> "OK" },
        // screenSummaryProvider not provided (defaults to null)
      )

    val result =
      toolSet.blaze(
        objective ="Tap a point",
        tools = "- tapOnPoint:\n    x: 100\n    y: 200",
      )

    assertContains(result, "Done")
    assertFalse(result.contains("**Screen:**"), "Should not include screen summary when provider is null")
  }

  // -- 14.5 resolveAwaitTimeoutMs: driver-status classification -----------------
  // Pure helper test — verifies the branch selection in awaitScreenState.
  // Covering this directly is the only practical way to assert that Playwright
  // "installing" gets the longer timeout without running the coroutine loop.

  @Test
  fun `resolveAwaitTimeoutMs picks Playwright timeout when status mentions installing`() {
    val result = StepToolSet.resolveAwaitTimeoutMs(
      "Playwright browser installing (12s elapsed, timeout in 888s): [42%] Downloading Chromium",
    )
    assertEquals(StepToolSet.PLAYWRIGHT_INSTALL_TIMEOUT_MS, result)
  }

  @Test
  fun `resolveAwaitTimeoutMs picks driver-init timeout when status mentions initializing`() {
    val result = StepToolSet.resolveAwaitTimeoutMs(
      "Device driver is still initializing (8s elapsed). Try again shortly.",
    )
    assertEquals(StepToolSet.DRIVER_INIT_TIMEOUT_MS, result)
  }

  @Test
  fun `resolveAwaitTimeoutMs picks short retry when status is null`() {
    val result = StepToolSet.resolveAwaitTimeoutMs(null)
    assertEquals(StepToolSet.SCREEN_CAPTURE_RETRY_MS, result)
  }

  @Test
  fun `resolveAwaitTimeoutMs returns null for non-transient driver error`() {
    // Unknown/terminal status — caller must return null immediately instead of looping.
    val result = StepToolSet.resolveAwaitTimeoutMs("Device disconnected unexpectedly")
    kotlin.test.assertNull(result)
  }

  @Test
  fun `resolveAwaitTimeoutMs picks Playwright over initializing when both words appear`() {
    // Order-sensitivity guard — the "installing" branch must be evaluated first
    // because the two driver states have distinct timeouts but status messages
    // could theoretically contain both strings (e.g., compound error paths).
    val result = StepToolSet.resolveAwaitTimeoutMs(
      "Playwright installing and Maestro initializing in parallel",
    )
    assertEquals(StepToolSet.PLAYWRIGHT_INSTALL_TIMEOUT_MS, result)
  }

  // -- 15. awaitScreenState: driver error returns immediately -------------------

  @Test
  fun `blaze returns immediately when driver reports a real error`() = runTest {
    val toolSet =
      StepToolSet(
        screenAnalyzer = throwingScreenAnalyzer,
        executor = throwingExecutor,
        screenStateProvider = { _, _, _ -> null },
        driverStatusProvider = { "Device disconnected unexpectedly" },
        rawToolExecutor = { _ -> "OK" },
      )

    val result =
      toolSet.blaze(
        objective ="Tap a point",
        tools =
          """
          - tools:
              - tapOnPoint:
                  x: 100
                  y: 200
          """
            .trimIndent(),
      )

    assertContains(result, "Device disconnected unexpectedly")
  }
}
