package xyz.block.trailblaze.mcp.newtools

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import xyz.block.trailblaze.logs.client.LogEmitter
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.SessionId
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
import xyz.block.trailblaze.toolcalls.DynamicTrailblazeToolRegistration
import xyz.block.trailblaze.toolcalls.ToolName
import xyz.block.trailblaze.toolcalls.TrailblazeKoogTool
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolDescriptor
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolParameterDescriptor
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.toolcalls.TrailblazeToolSet
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
        rawToolExecutor = { tool, _ ->
          executedTools.add(tool)
          "OK"
        },
      )

    val result =
      toolSet.step(
        objective ="Tap two points",
        tools = "- tapOnPoint:\n    x: 100\n    y: 200\n- tapOnPoint:\n    x: 300\n    y: 400",
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
    // Each tool's own output is surfaced, labeled with a 1-based ordinal + name so repeated
    // tools stay individually attributable, rather than a generic count.
    assertContains(result, "OK")
    assertContains(result, "[1] tapOnPoint:")
    assertContains(result, "[2] tapOnPoint:")
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
        rawToolExecutor = { tool, _ ->
          executedTools.add(tool)
          "OK"
        },
      )

    val result =
      toolSet.step(
        objective ="Tap a point",
        tools = "- tapOnPoint:\n    x: 100\n    y: 200",
      )

    assertEquals(1, executedTools.size)
    assertTrue(executedTools[0] is TapOnPointTrailblazeTool)
    assertContains(result, "OK")
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
        rawToolExecutor = { tool, _ ->
          executedTools.add(tool)
          "OK"
        },
      )

    val result =
      toolSet.step(
        objective ="Tap a point",
        tools = "- tapOnPoint:\n    x: 150\n    y: 250",
      )

    assertEquals(1, executedTools.size)
    val tap = executedTools[0] as TapOnPointTrailblazeTool
    assertEquals(150, tap.x)
    assertEquals(250, tap.y)
    assertContains(result, "OK")
  }

  // -- 3b. Tool output surfacing ----------------------------------------------
  // The observable contract this change adds: `trailblaze tool <name>` returns the
  // tool's OWN output (an installed-app list, a profile JSON, …) instead of a generic
  // "Executed N tools" count — 1:1 with what the agent sees. Driven through the real
  // rawToolExecutor seam with a fake that returns a known string; the tool identity is
  // irrelevant because the seam stands in for the device round-trip.

  @Test
  fun `direct tools - single tool output is surfaced in the result`() = runTest {
    val appListJson = """{"appIds":["com.example.one","com.example.two"]}"""
    val toolSet =
      StepToolSet(
        screenAnalyzer = throwingScreenAnalyzer,
        executor = throwingExecutor,
        screenStateProvider = { _, _, _ -> dummyScreenState },
        rawToolExecutor = { _, _ -> appListJson },
      )

    val result =
      toolSet.step(
        objective = "List installed apps",
        tools = "- tapOnPoint:\n    x: 100\n    y: 200",
      )

    assertContains(result, "Done")
    assertContains(result, appListJson)
  }

  @Test
  fun `direct tools - structured JSON output is surfaced verbatim`() = runTest {
    // A scripted tool's structured content arrives at this layer already serialized to
    // JSON; the direct-tool path must pass it through untouched.
    val structured = """{"merchantName":"Example Co","currency":"USD"}"""
    val toolSet =
      StepToolSet(
        screenAnalyzer = throwingScreenAnalyzer,
        executor = throwingExecutor,
        screenStateProvider = { _, _, _ -> dummyScreenState },
        rawToolExecutor = { _, _ -> structured },
      )

    val result =
      toolSet.step(
        objective = "Read profile",
        tools = "- tapOnPoint:\n    x: 5\n    y: 6",
      )

    assertContains(result, structured)
  }

  @Test
  fun `direct tools - each tool's output is labeled and surfaced for a multi-tool batch`() =
    runTest {
      val outputs = ArrayDeque(listOf("first-output", "second-output"))
      val toolSet =
        StepToolSet(
          screenAnalyzer = throwingScreenAnalyzer,
          executor = throwingExecutor,
          screenStateProvider = { _, _, _ -> dummyScreenState },
          rawToolExecutor = { _, _ -> outputs.removeFirst() },
        )

      val result =
        toolSet.step(
          objective = "Run two tools",
          tools =
            """
            - tapOnPoint:
                x: 1
                y: 2
            - tapOnPoint:
                x: 3
                y: 4
            """
              .trimIndent(),
        )

      assertContains(result, "first-output")
      assertContains(result, "second-output")
      // 1-based ordinals keep repeated tools individually attributable.
      assertContains(result, "[1] tapOnPoint:")
      assertContains(result, "[2] tapOnPoint:")
    }

  @Test
  fun `direct tools - blank output falls back to a concise executed line`() = runTest {
    // Action tools (tap/swipe) produce no payload; the result must still read sensibly.
    val toolSet =
      StepToolSet(
        screenAnalyzer = throwingScreenAnalyzer,
        executor = throwingExecutor,
        screenStateProvider = { _, _, _ -> dummyScreenState },
        rawToolExecutor = { _, _ -> "   " },
      )

    val result =
      toolSet.step(
        objective = "Tap a point",
        tools = "- tapOnPoint:\n    x: 1\n    y: 2",
      )

    assertContains(result, "Done")
    assertContains(result, "Executed tapOnPoint")
  }

  @Test
  fun `direct tools emit top level recordable tool logs`() = runTest {
    val emittedLogs = mutableListOf<TrailblazeLog>()
    val toolSet =
      StepToolSet(
        screenAnalyzer = throwingScreenAnalyzer,
        executor = throwingExecutor,
        screenStateProvider = { _, _, _ -> dummyScreenState },
        rawToolExecutor = { _, _ -> "OK" },
        logEmitter = LogEmitter { emittedLogs += it },
        sessionIdProvider = { SessionId("recording-session") },
      )

    val result =
      toolSet.step(
        objective ="Tap a point",
        tools = "- tapOnPoint:\n    x: 100\n    y: 200",
      )

    val toolLogs = emittedLogs.filterIsInstance<TrailblazeLog.TrailblazeToolLog>()
    assertEquals(1, toolLogs.size)
    assertEquals("tapOnPoint", toolLogs.single().toolName)
    assertTrue(toolLogs.single().isTopLevelToolCall)
    assertTrue(toolLogs.single().isRecordable)
    assertContains(result, "OK")
  }

  @Test
  fun `direct tools reuse one MCP trace across executor and top level tool log`() = runTest {
    val emittedLogs = mutableListOf<TrailblazeLog>()
    var executorTraceId: TraceId? = null
    val toolSet =
      StepToolSet(
        screenAnalyzer = throwingScreenAnalyzer,
        executor = throwingExecutor,
        screenStateProvider = { _, _, _ -> dummyScreenState },
        rawToolExecutor = { _, traceId ->
          executorTraceId = traceId
          "OK"
        },
        logEmitter = LogEmitter { emittedLogs += it },
        sessionIdProvider = { SessionId("trace-session") },
      )

    val result =
      toolSet.step(
        objective ="Tap a point",
        tools = "- tapOnPoint:\n    x: 100\n    y: 200",
      )

    val toolLog = emittedLogs.filterIsInstance<TrailblazeLog.TrailblazeToolLog>().single()
    assertEquals(toolLog.traceId, executorTraceId)
    assertTrue(toolLog.traceId?.traceId?.startsWith("mcp-") == true)
    assertContains(result, "OK")
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
        rawToolExecutor = { _, _ ->
          executorCalled = true
          "OK"
        },
      )

    val result =
      toolSet.step(
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
        rawToolExecutor = { _, _ ->
          executorCalled = true
          "OK"
        },
      )

    // Valid unified trail YAML with a single step and no recording (no tools).
    // decodeTrail will produce a PromptsTrailItem -- no ToolTrailItem.
    val result =
      toolSet.step(
        objective ="Do something",
        tools = "trail:\n  - step: Just a prompt step",
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
        rawToolExecutor = { _, _ ->
          executorCalled = true
          "OK"
        },
      )

    val result =
      toolSet.step(
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
        rawToolExecutor = { tool, _ ->
          val tap = tool as TapOnPointTrailblazeTool
          executedTools.add("tap(${tap.x},${tap.y})")
          if (tap.x == 300) {
            throw RuntimeException("Device disconnected")
          }
          "OK"
        },
      )

    val result =
      toolSet.step(
        objective ="Tap three points",
        tools =
          "- tapOnPoint:\n    x: 100\n    y: 200\n" +
            "- tapOnPoint:\n    x: 300\n    y: 400\n" +
            "- tapOnPoint:\n    x: 500\n    y: 600",
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

  // -- 6b. availableToolsProvider rejection ----------------------------------

  @Test
  fun `direct tools rejects tools not in availableToolsProvider for current device`() = runTest {
    // Simulate a non-empty tool catalog that does NOT include `tapOnPoint`. The user's
    // YAML asks for `tapOnPoint` — fail-fast with a "not valid for the current device/target"
    // message instead of letting the call fall through to a cryptic cast error.
    val executedTools = mutableListOf<TrailblazeTool>()
    val toolSet =
      StepToolSet(
        screenAnalyzer = throwingScreenAnalyzer,
        executor = throwingExecutor,
        screenStateProvider = { _, _, _ -> dummyScreenState },
        availableToolsProvider = {
          listOf(TrailblazeToolDescriptor(name = "web_click"))
        },
        rawToolExecutor = { tool, _ ->
          executedTools.add(tool)
          "OK"
        },
      )

    val result =
      toolSet.step(
        objective = "Tap on point",
        tools = "- tapOnPoint:\n    x: 100\n    y: 200",
      )

    assertContains(result, "not valid for the current device/target")
    assertContains(result, "tapOnPoint")
    // The rejected tool must not have reached rawToolExecutor.
    assertEquals(0, executedTools.size)
  }

  @Test
  fun `direct tools rejects openUrl on web with hint to use web_navigate`() = runTest {
    // openUrl is a mobile-only tool (Maestro-backed). On a Playwright web device it isn't
    // in the available tool catalog, so it must be rejected. The error message should include
    // a specific hint pointing the user to web_navigate rather than the generic fallback.
    val toolSet =
      StepToolSet(
        screenAnalyzer = throwingScreenAnalyzer,
        executor = throwingExecutor,
        screenStateProvider = { _, _, _ -> dummyScreenState },
        availableToolsProvider = {
          listOf(
            TrailblazeToolDescriptor(name = "web_navigate"),
            TrailblazeToolDescriptor(name = "web_click"),
          )
        },
        rawToolExecutor = { _, _ -> "OK" },
      )

    val result =
      toolSet.step(
        objective = "Open example.com",
        tools = "- openUrl:\n    url: https://example.com",
      )

    assertContains(result, "not valid for the current device/target")
    assertContains(result, "openUrl")
    assertContains(result, "web_navigate")
  }

  @Test
  fun `direct tools rejects openUrl without web hint when web_navigate not available`() = runTest {
    // Suppression branch: if openUrl is rejected on a device whose catalog does not include
    // web_navigate (e.g., an iOS target where openUrl is filtered out for some other reason),
    // the web-specific hint must NOT leak into the error message.
    val toolSet =
      StepToolSet(
        screenAnalyzer = throwingScreenAnalyzer,
        executor = throwingExecutor,
        screenStateProvider = { _, _, _ -> dummyScreenState },
        availableToolsProvider = {
          listOf(
            TrailblazeToolDescriptor(name = "tapOnElementWithText"),
            TrailblazeToolDescriptor(name = "inputText"),
          )
        },
        rawToolExecutor = { _, _ -> "OK" },
      )

    val result =
      toolSet.step(
        objective = "Open example.com",
        tools = "- openUrl:\n    url: https://example.com",
      )

    assertContains(result, "not valid for the current device/target")
    assertContains(result, "openUrl")
    assertFalse(result.contains("web_navigate"), "Web hint must not leak when web_navigate is unavailable")
  }

  // -- 6c. Scripted-tool arg-shape gate -----------------------------------------
  //
  // Dynamic/scripted tools carry raw JsonObject args with no deserializer to reject a
  // wrong shape, so parseAndValidateDirectTools runs JsScriptingCallbackArgumentValidator
  // over them. Regression coverage for `trailblaze tool <scripted-launch-tool> key=…`
  // (the arg shape of a sibling tool): the undefined email/password used to flow through
  // to the device and fail deep inside the tool as a BroadcastExtra decode error instead
  // of an up-front rejection.

  /** Repo with one dynamic tool whose descriptor declares the given parameter split. */
  private fun repoWithDynamicTool(
    name: String = "fake_scripted_login",
    required: List<String> = listOf("email", "password"),
    optional: List<String> = listOf("mode"),
    exhaustive: Boolean = false,
  ): TrailblazeToolRepo {
    val repo = TrailblazeToolRepo(
      trailblazeToolSet = TrailblazeToolSet.DynamicTrailblazeToolSet(
        name = "arg-gate-test-set",
        toolClasses = emptySet(),
        yamlToolNames = emptySet(),
      ),
    )
    val registeredName = name
    repo.addDynamicTools(
      listOf(
        object : DynamicTrailblazeToolRegistration {
          override val name: ToolName = ToolName(registeredName)
          override val declaresExhaustiveParameters: Boolean = exhaustive
          override val trailblazeDescriptor: TrailblazeToolDescriptor = TrailblazeToolDescriptor(
            name = registeredName,
            description = "fake scripted tool",
            requiredParameters = required.map { TrailblazeToolParameterDescriptor(name = it, type = "string") },
            optionalParameters = optional.map { TrailblazeToolParameterDescriptor(name = it, type = "string") },
          )

          override fun buildKoogTool(
            trailblazeToolContextProvider: () -> TrailblazeToolExecutionContext,
          ): TrailblazeKoogTool<out TrailblazeTool> =
            error("buildKoogTool not used — direct tools dispatch via decodeToolCall")

          // A real serializable tool so downstream logging/recording paths stay happy;
          // the identity is irrelevant to the gate under test.
          override fun decodeToolCall(argumentsJson: String): TrailblazeTool =
            TapOnPointTrailblazeTool(x = 0, y = 0)
        },
      ),
    )
    return repo
  }

  @Test
  fun `direct tools reject scripted tool called with unknown argument keys`() = runTest {
    var screenStateInvocations = 0
    var executorCalled = false
    val toolSet =
      StepToolSet(
        screenAnalyzer = throwingScreenAnalyzer,
        executor = throwingExecutor,
        screenStateProvider = { _, _, _ ->
          screenStateInvocations++
          dummyScreenState
        },
        rawToolExecutor = { _, _ ->
          executorCalled = true
          "OK"
        },
        dynamicToolRepoProvider = { repoWithDynamicTool() },
      )

    val result =
      toolSet.step(
        objective = "Launch signed in",
        tools = "- fake_scripted_login:\n    key: defaults/standard-merchant",
      )

    // "was called with unknown argument keys" is in the CLI's MISUSE_MARKERS list.
    assertContains(result, "was called with unknown argument keys")
    assertContains(result, "\"key\"")
    assertContains(result, "email")
    assertFalse(executorCalled, "rawToolExecutor must not run for a wrong-shaped scripted-tool call")
    assertEquals(
      0,
      screenStateInvocations,
      "the arg-shape rejection must short-circuit before awaitScreenState like the other pre-validation rejections",
    )
  }

  @Test
  fun `direct tools reject scripted tool missing required argument keys`() = runTest {
    var executorCalled = false
    val toolSet =
      StepToolSet(
        screenAnalyzer = throwingScreenAnalyzer,
        executor = throwingExecutor,
        screenStateProvider = { _, _, _ -> dummyScreenState },
        rawToolExecutor = { _, _ ->
          executorCalled = true
          "OK"
        },
        dynamicToolRepoProvider = { repoWithDynamicTool() },
      )

    val result =
      toolSet.step(
        objective = "Launch signed in",
        tools = "- fake_scripted_login:\n    email: merchant@example.com",
      )

    // "was called without required argument keys" is in the CLI's MISUSE_MARKERS list.
    assertContains(result, "was called without required argument keys")
    assertContains(result, "\"password\"")
    assertFalse(executorCalled, "rawToolExecutor must not run when a required arg is missing")
  }

  @Test
  fun `direct tools execute scripted tool whose args satisfy the schema`() = runTest {
    val executedTools = mutableListOf<TrailblazeTool>()
    val toolSet =
      StepToolSet(
        screenAnalyzer = throwingScreenAnalyzer,
        executor = throwingExecutor,
        screenStateProvider = { _, _, _ -> dummyScreenState },
        rawToolExecutor = { tool, _ ->
          executedTools.add(tool)
          "OK"
        },
        dynamicToolRepoProvider = { repoWithDynamicTool() },
      )

    val result =
      toolSet.step(
        objective = "Launch signed in",
        tools = "- fake_scripted_login:\n    email: merchant@example.com\n    password: hunter2",
      )

    assertEquals(1, executedTools.size)
    assertContains(result, "Done")
  }

  @Test
  fun `direct tools skip arg-shape gate for dynamic tool with no declared parameters`() = runTest {
    // A dynamic registration that advertises no schema WITHOUT claiming exhaustiveness
    // (subprocess MCP servers can legitimately do this — declaresExhaustiveParameters
    // defaults to false) must NOT be rejected — the validator's introspection returns
    // null and the call falls through unchanged.
    val executedTools = mutableListOf<TrailblazeTool>()
    val toolSet =
      StepToolSet(
        screenAnalyzer = throwingScreenAnalyzer,
        executor = throwingExecutor,
        screenStateProvider = { _, _, _ -> dummyScreenState },
        rawToolExecutor = { tool, _ ->
          executedTools.add(tool)
          "OK"
        },
        dynamicToolRepoProvider = {
          repoWithDynamicTool(name = "schemaless_tool", required = emptyList(), optional = emptyList())
        },
      )

    val result =
      toolSet.step(
        objective = "Run schemaless tool",
        tools = "- schemaless_tool:\n    anything: goes",
      )

    assertEquals(1, executedTools.size)
    assertContains(result, "Done")
  }

  @Test
  fun `direct tools reject stray keys on a no-arg tool whose schema is exhaustive`() = runTest {
    // The mirror image of the skip above: a scripted `.ts` tool with no arguments still
    // carries an exhaustive analyzer-generated schema (`properties: {}`), so its
    // registration sets declaresExhaustiveParameters = true and a stray key is the same
    // typo class the gate exists to catch — it must reject, not silently swallow.
    var executorCalled = false
    val toolSet =
      StepToolSet(
        screenAnalyzer = throwingScreenAnalyzer,
        executor = throwingExecutor,
        screenStateProvider = { _, _, _ -> dummyScreenState },
        rawToolExecutor = { _, _ ->
          executorCalled = true
          "OK"
        },
        dynamicToolRepoProvider = {
          repoWithDynamicTool(
            name = "no_arg_tool",
            required = emptyList(),
            optional = emptyList(),
            exhaustive = true,
          )
        },
      )

    val result =
      toolSet.step(
        objective = "Open the debug drawer",
        tools = "- no_arg_tool:\n    bogus: 1",
      )

    assertContains(result, "was called with unknown argument keys")
    assertContains(result, "\"bogus\"")
    assertContains(result, "no arguments")
    assertFalse(executorCalled, "rawToolExecutor must not run for a stray key on a no-arg exhaustive tool")
  }

  // -- 6e. Pre-validation: unknown / wrong-driver rejections short-circuit -----
  //
  // Regression coverage for the Web/Playwright bug where awaitScreenState could sit
  // in a transient state long enough to outlast the CLI socket timeout, swallowing
  // the canonical rejection inside executeDirectTools. The fix is to run the same
  // rejection BEFORE awaitScreenState. These tests pin that ordering by counting
  // screenStateProvider invocations and asserting they stay at zero.

  @Test
  fun `direct tools reject unknown tool name without invoking screenStateProvider`() = runTest {
    var screenStateInvocations = 0
    val toolSet =
      StepToolSet(
        screenAnalyzer = throwingScreenAnalyzer,
        executor = throwingExecutor,
        screenStateProvider = { _, _, _ ->
          screenStateInvocations++
          dummyScreenState
        },
        rawToolExecutor = { _, _ -> "OK" },
      )

    val result = toolSet.step(objective = "Tap nonexistent", tools = "- tap_on_text: {}")

    // The CLI's MISUSE_MARKERS contains "Unknown tool"; without this marker the
    // ToolCommand exit-code mapper would not produce EXIT=3.
    assertContains(result, "Unknown tool")
    assertContains(result, "tap_on_text")
    assertContains(result, "toolbox()")
    assertEquals(
      0,
      screenStateInvocations,
      "screenStateProvider must not be invoked when an unknown tool short-circuits — this is what makes the rejection robust to drivers where captureScreenState can hang.",
    )
  }

  @Test
  fun `direct tools reject wrong-driver tool without invoking screenStateProvider`() = runTest {
    var screenStateInvocations = 0
    val toolSet =
      StepToolSet(
        screenAnalyzer = throwingScreenAnalyzer,
        executor = throwingExecutor,
        screenStateProvider = { _, _, _ ->
          screenStateInvocations++
          dummyScreenState
        },
        availableToolsProvider = {
          listOf(
            TrailblazeToolDescriptor(name = "web_navigate"),
            TrailblazeToolDescriptor(name = "web_click"),
          )
        },
        rawToolExecutor = { _, _ -> "OK" },
      )

    val result =
      toolSet.step(
        objective = "Open example.com",
        tools = "- openUrl:\n    url: https://example.com",
      )

    assertContains(result, "not valid for the current device/target")
    assertContains(result, "openUrl")
    assertContains(result, "web_navigate")
    assertEquals(
      0,
      screenStateInvocations,
      "screenStateProvider must not be invoked when the wrong-driver rejection fires.",
    )
  }

  @Test
  fun `direct tools reject malformed YAML without invoking screenStateProvider`() = runTest {
    // Malformed YAML is the third rejection category surfaced by parseAndValidateDirectTools;
    // pinning it here ensures the Web/Playwright fail-fast guarantee holds for parse errors
    // too (previously the pre-pass returned null on parse failure and let awaitScreenState
    // run, which could hang).
    var screenStateInvocations = 0
    val toolSet =
      StepToolSet(
        screenAnalyzer = throwingScreenAnalyzer,
        executor = throwingExecutor,
        screenStateProvider = { _, _, _ ->
          screenStateInvocations++
          dummyScreenState
        },
        rawToolExecutor = { _, _ -> "OK" },
      )

    val result = toolSet.step(objective = "garbage in", tools = "this is not valid yaml {{")

    assertContains(result, "Failed to parse tools YAML")
    assertEquals(
      0,
      screenStateInvocations,
      "screenStateProvider must not be invoked when the YAML can't be parsed.",
    )
  }

  @Test
  fun `direct tools reject empty tool list without invoking screenStateProvider`() = runTest {
    // A YAML that parses cleanly but produces zero tools (e.g., only a prompts item) is the
    // fourth rejection category. Same Web/Playwright fail-fast guarantee.
    var screenStateInvocations = 0
    val toolSet =
      StepToolSet(
        screenAnalyzer = throwingScreenAnalyzer,
        executor = throwingExecutor,
        screenStateProvider = { _, _, _ ->
          screenStateInvocations++
          dummyScreenState
        },
        rawToolExecutor = { _, _ -> "OK" },
      )

    val result =
      toolSet.step(
        objective = "no tools",
        tools = "trail:\n  - step: Just a prompt step",
      )

    assertContains(result, "No tools found")
    assertEquals(
      0,
      screenStateInvocations,
      "screenStateProvider must not be invoked when the YAML has no tool items.",
    )
  }

  @Test
  fun `takeSnapshot bypasses pre-validation and uses captured screen state`() = runTest {
    // The pre-validation pass is intentionally skipped when `tools` contains the
    // substring `takeSnapshot` because the snapshot short-circuit consumes the captured
    // screen state directly. Pin that bypass: a `takeSnapshot` YAML must reach
    // screenStateProvider (i.e. NOT short-circuit pre-validation), otherwise a future
    // tightening of the substring condition would silently break the snapshot path.
    var screenStateInvocations = 0
    val toolSet =
      StepToolSet(
        screenAnalyzer = throwingScreenAnalyzer,
        executor = throwingExecutor,
        screenStateProvider = { _, _, _ ->
          screenStateInvocations++
          dummyScreenState
        },
        screenSummaryProvider = { "snapshot summary" },
        rawToolExecutor = { _, _ -> "OK" },
      )

    val result = toolSet.step(objective = "snap", tools = "- takeSnapshot: {}")

    assertContains(result, "Snapshot captured")
    assertEquals(
      1,
      screenStateInvocations,
      "takeSnapshot must consume the captured screen state — pre-validation should be skipped, awaitScreenState should run.",
    )
  }

  @Test
  fun `direct tools skip availability check when provider returns empty list`() = runTest {
    // Empty provider is the transient state during device boot — the gate logs and skips
    // rather than failing, so the call still proceeds to rawToolExecutor.
    val executedTools = mutableListOf<TrailblazeTool>()
    val toolSet =
      StepToolSet(
        screenAnalyzer = throwingScreenAnalyzer,
        executor = throwingExecutor,
        screenStateProvider = { _, _, _ -> dummyScreenState },
        availableToolsProvider = { emptyList() },
        rawToolExecutor = { tool, _ ->
          executedTools.add(tool)
          "OK"
        },
      )

    val result =
      toolSet.step(
        objective = "Tap on point",
        tools = "- tapOnPoint:\n    x: 100\n    y: 200",
      )

    assertEquals(1, executedTools.size)
    assertContains(result, "Done")
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
      toolSet.step(
        objective ="Tap a point",
        tools = "- tapOnPoint:\n    x: 100\n    y: 200",
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
          rawToolExecutor = { _, _ -> "tap executed" },
        )

      toolSet.step(
        objective ="Tap the login button",
        tools = "- tapOnPoint:\n    x: 100\n    y: 200",
      )

      val steps = sessionContext.getRecordedSteps()
      assertEquals(1, steps.size)

      val step = steps[0]
      assertEquals(RecordedStepType.STEP, step.type)
      assertEquals("Tap the login button", step.input)
      assertTrue(step.success)
      // The recorded step captures the tool's real output, not a generic count.
      assertContains(step.result, "tap executed")

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
        rawToolExecutor = { _, _ ->
          executorCalled = true
          "OK"
        },
      )

    val result =
      toolSet.step(
        objective ="Tap a point",
        tools = "- tapOnPoint:\n    x: 100\n    y: 200",
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
        rawToolExecutor = { _, _ -> "OK" },
      )

    val result =
      toolSet.step(
        objective ="Tap a point",
        tools = "- tapOnPoint:\n    x: 100\n    y: 200",
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
        rawToolExecutor = { tool, _ ->
          executedTools.add(tool)
          "OK"
        },
      )

    val result =
      toolSet.step(
        objective ="Tap a point",
        tools = "- tapOnPoint:\n    x: 100\n    y: 200",
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
        rawToolExecutor = { _, _ -> "OK" },
      )

    // tools=null means normal path -- analyzer gets called
    val result = toolSet.step(objective ="Do something", tools = null)

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
        rawToolExecutor = { _, _ -> "OK" },
      )

    val result = toolSet.step(objective ="Do something", tools = null)

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
        rawToolExecutor = { tool, _ ->
          executedTools.add(tool)
          "OK"
        },
      )

    val result =
      toolSet.step(
        objective ="Tap a point",
        tools = "- tapOnPoint:\n    x: 100\n    y: 200",
      )

    assertEquals(1, executedTools.size)
    assertContains(result, "OK")
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
        rawToolExecutor = { tool, _ ->
          executedTools.add(tool)
          "OK"
        },
      )

    val result =
      toolSet.step(
        objective ="Tap a point",
        tools = "- tapOnPoint:\n    x: 100\n    y: 200",
      )

    assertTrue(callCount >= 3, "screenStateProvider should have been called multiple times")
    assertEquals(1, executedTools.size, "Tool should execute after screen state becomes available")
    assertContains(result, "OK")
  }

  // -- 14. Screen summary included after direct tool execution -----------------

  @Test
  fun `direct tools - screen summary included in result when provider is set`() = runTest {
    val toolSet =
      StepToolSet(
        screenAnalyzer = throwingScreenAnalyzer,
        executor = throwingExecutor,
        screenStateProvider = { _, _, _ -> dummyScreenState },
        rawToolExecutor = { _, _ -> "OK" },
        screenSummaryProvider = { "Login screen | [button] Sign in | [input] Email" },
      )

    val result =
      toolSet.step(
        objective ="Tap a point",
        tools = "- tapOnPoint:\n    x: 100\n    y: 200",
      )

    assertContains(result, "Done")
    assertContains(result, "OK")
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
        rawToolExecutor = { _, _ -> "OK" },
        // screenSummaryProvider not provided (defaults to null)
      )

    val result =
      toolSet.step(
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
        rawToolExecutor = { _, _ -> "OK" },
      )

    val result =
      toolSet.step(
        objective ="Tap a point",
        tools = "- tapOnPoint:\n    x: 100\n    y: 200",
      )

    assertContains(result, "Device disconnected unexpectedly")
  }
}
