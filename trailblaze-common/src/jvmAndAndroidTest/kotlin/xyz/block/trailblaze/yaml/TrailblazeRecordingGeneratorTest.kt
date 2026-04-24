package xyz.block.trailblaze.yaml

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.doesNotContain
import assertk.assertions.startsWith
import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test
import xyz.block.trailblaze.agent.model.AgentTaskStatus
import xyz.block.trailblaze.agent.model.AgentTaskStatusData
import xyz.block.trailblaze.api.TrailblazeElementSelector
import xyz.block.trailblaze.logs.model.TaskId
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.commands.AssertVisibleBySelectorTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.AssertVisibleWithTextTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.InputTextTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.LaunchAppTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.PasteClipboardTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.SwipeTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.TapOnByElementSelector
import xyz.block.trailblaze.toolcalls.commands.WaitForIdleSyncTrailblazeTool

/**
 * Tests for [generateRecordedYaml] — the recording generator that transforms
 * [TrailblazeLog] entries into trail YAML.
 *
 * These tests verify:
 * 1. Logs within objective windows produce prompts with recordings
 * 2. Orphaned tool logs (outside windows) attach to the last prompt step's recording
 * 3. Orphaned tool logs with no preceding prompt produce flat tool entries
 * 4. Non-recordable tools are excluded
 * 5. DelegatingTrailblazeToolLog entries are skipped
 * 6. Round-trip: trail YAML → simulated logs → generateRecordedYaml → matching YAML
 */
class TrailblazeRecordingGeneratorTest {
  private val trailblazeYaml = createTrailblazeYaml()
  private val testSession = SessionId("test-session")
  private val now = Clock.System.now()

  // -- Helpers --

  private fun maestroCommandLog() = TrailblazeLog.MaestroCommandLog(
    maestroCommandJsonObj = JsonObject(
      mapOf("tapOn" to JsonObject(mapOf("text" to JsonPrimitive("element"))))
    ),
    traceId = null,
    successful = true,
    trailblazeToolResult = TrailblazeToolResult.Success(),
    session = testSession,
    timestamp = now,
    durationMs = 50,
  )

  private fun objectiveStart(prompt: PromptStep) = TrailblazeLog.ObjectiveStartLog(
    promptStep = prompt,
    session = testSession,
    timestamp = now,
  )

  private fun objectiveComplete(prompt: PromptStep) = TrailblazeLog.ObjectiveCompleteLog(
    promptStep = prompt,
    objectiveResult = AgentTaskStatus.Success.ObjectiveComplete(
      llmExplanation = "Done",
      statusData = AgentTaskStatusData(
        taskId = TaskId.generate(),
        prompt = prompt.prompt,
        callCount = 1,
        taskStartTime = now,
        totalDurationMs = 100,
      ),
    ),
    session = testSession,
    timestamp = now,
  )

  private fun toolLog(
    tool: xyz.block.trailblaze.toolcalls.TrailblazeTool,
    toolName: String,
    isRecordable: Boolean = true,
  ) = TrailblazeLog.TrailblazeToolLog(
    trailblazeTool = tool,
    toolName = toolName,
    successful = true,
    traceId = null,
    durationMs = 100,
    session = testSession,
    timestamp = now,
    isRecordable = isRecordable,
  )

  private fun delegatingToolLog(
    tool: xyz.block.trailblaze.toolcalls.TrailblazeTool,
    toolName: String,
    executableTools: List<xyz.block.trailblaze.toolcalls.TrailblazeTool> = emptyList(),
  ) = TrailblazeLog.DelegatingTrailblazeToolLog(
    trailblazeTool = tool,
    toolName = toolName,
    executableTools = executableTools,
    session = testSession,
    timestamp = now,
    traceId = null,
  )

  // -- Tests --

  @Test
  fun promptWithRecordedToolsGeneratesPromptsYaml() {
    val step = DirectionStep(step = "Enter search text")
    val logs = listOf(
      objectiveStart(step),
      toolLog(InputTextTrailblazeTool(text = "hello"), "inputText"),
      objectiveComplete(step),
    )

    val yaml = logs.generateRecordedYaml(trailblazeYaml)

    val expected = """
      |- prompts:
      |  - step: Enter search text
      |    recording:
      |      tools:
      |      - inputText:
      |          text: hello
    """.trimMargin() + "\n"
    assertThat(yaml).isEqualTo(expected)
  }

  @Test
  fun verificationStepGeneratesVerifyYaml() {
    val step = VerificationStep(verify = "Search results visible")
    val logs = listOf(
      objectiveStart(step),
      toolLog(AssertVisibleWithTextTrailblazeTool(text = "Results"), "assertVisibleWithText"),
      objectiveComplete(step),
    )

    val yaml = logs.generateRecordedYaml(trailblazeYaml)

    val expected = """
      |- prompts:
      |  - verify: Search results visible
      |    recording:
      |      tools:
      |      - assertVisibleWithText:
      |          text: Results
    """.trimMargin() + "\n"
    assertThat(yaml).isEqualTo(expected)
  }

  @Test
  fun multiplePromptsAreMergedIntoSinglePromptsItem() {
    val step1 = DirectionStep(step = "Type hello")
    val step2 = DirectionStep(step = "Press back")
    val logs = listOf(
      objectiveStart(step1),
      toolLog(InputTextTrailblazeTool(text = "hello"), "inputText"),
      objectiveComplete(step1),
      objectiveStart(step2),
      toolLog(PasteClipboardTrailblazeTool, "pasteClipboard"),
      objectiveComplete(step2),
    )

    val yaml = logs.generateRecordedYaml(trailblazeYaml)

    val expected = """
      |- prompts:
      |  - step: Type hello
      |    recording:
      |      tools:
      |      - inputText:
      |          text: hello
      |  - step: Press back
      |    recording:
      |      tools:
      |      - pasteClipboard: {}
    """.trimMargin() + "\n"
    assertThat(yaml).isEqualTo(expected)
  }

  @Test
  fun nonRecordableToolsAreExcludedFromRecording() {
    val step = DirectionStep(step = "Tap login")
    val logs = listOf(
      objectiveStart(step),
      toolLog(InputTextTrailblazeTool(text = "user"), "takeSnapshot", isRecordable = false),
      toolLog(
        TapOnByElementSelector(
          reason = "Tap the login button",
          selector = TrailblazeElementSelector(textRegex = "Login"),
        ),
        "tapOnElementBySelector",
      ),
      objectiveComplete(step),
    )

    val yaml = logs.generateRecordedYaml(trailblazeYaml)

    // Only tapOnElementBySelector should appear, not takeSnapshot (non-recordable)
    assertThat(yaml).contains("tapOnElementBySelector")
    val decoded = trailblazeYaml.decodeTrail(yaml)
    assertThat(decoded.size).isEqualTo(1)
    val prompts = decoded[0] as TrailYamlItem.PromptsTrailItem
    assertThat(prompts.promptSteps[0].recording!!.tools.size).isEqualTo(1)
    assertThat(prompts.promptSteps[0].recording!!.tools[0].name).isEqualTo("tapOnElementBySelector")
  }

  @Test
  fun delegatingToolLogIsSkipped() {
    val step = DirectionStep(step = "Tap element")
    val selectorTool = TapOnByElementSelector(
      reason = "Tap login",
      selector = TrailblazeElementSelector(textRegex = "Login"),
    )
    val logs = listOf(
      objectiveStart(step),
      delegatingToolLog(
        tool = selectorTool,
        toolName = "tapOnElementByNodeId",
        executableTools = listOf(selectorTool),
      ),
      toolLog(selectorTool, "tapOnElementBySelector"),
      objectiveComplete(step),
    )

    val yaml = logs.generateRecordedYaml(trailblazeYaml)

    // Only the selector-based tool should appear
    assertThat(yaml).contains("tapOnElementBySelector")
    val decoded = trailblazeYaml.decodeTrail(yaml)
    val prompts = decoded[0] as TrailYamlItem.PromptsTrailItem
    assertThat(prompts.promptSteps[0].recording!!.tools.size).isEqualTo(1)
  }

  @Test
  fun toolsWithNoPrecedingPromptProduceFlatToolList() {
    // When there's no preceding prompt step, orphaned tools still produce a flat
    // ToolTrailItem (e.g., launchApp before any objectives)
    val logs = listOf(
      toolLog(InputTextTrailblazeTool(text = "hello"), "inputText"),
      toolLog(PasteClipboardTrailblazeTool, "pasteClipboard"),
    )

    val yaml = logs.generateRecordedYaml(trailblazeYaml)

    val expected = """
      |- tools:
      |  - inputText:
      |      text: hello
      |  - pasteClipboard: {}
    """.trimMargin() + "\n"
    assertThat(yaml).isEqualTo(expected)
  }

  @Test
  fun orphanedToolAfterObjectiveWindowAttachesToLastPromptStep() {
    // Simulates the MCP path where tool logs are emitted asynchronously and
    // land after the ObjectiveCompleteLog in the sorted log list
    val step = DirectionStep(step = "Tap the button")
    val logs = listOf(
      objectiveStart(step),
      objectiveComplete(step),
      // Tool logged after the window (async emission in MCP path)
      toolLog(
        TapOnByElementSelector(
          reason = "Tap button",
          selector = TrailblazeElementSelector(textRegex = "Button"),
        ),
        "tapOnElementBySelector",
      ),
    )

    val yaml = logs.generateRecordedYaml(trailblazeYaml)

    val decoded = trailblazeYaml.decodeTrail(yaml)
    assertThat(decoded.size).isEqualTo(1)
    val prompts = decoded[0] as TrailYamlItem.PromptsTrailItem
    assertThat(prompts.promptSteps[0].prompt).isEqualTo("Tap the button")
    assertThat(prompts.promptSteps[0].recording!!.tools.size).isEqualTo(1)
    assertThat(prompts.promptSteps[0].recording!!.tools[0].name).isEqualTo("tapOnElementBySelector")
  }

  @Test
  fun multipleOrphanedToolsAttachToCorrectPrecedingSteps() {
    // Each orphaned tool attaches to the most recently emitted prompt step
    val step1 = DirectionStep(step = "Enter text")
    val step2 = DirectionStep(step = "Press back")
    val logs = listOf(
      objectiveStart(step1),
      objectiveComplete(step1),
      toolLog(InputTextTrailblazeTool(text = "hello"), "inputText"),
      objectiveStart(step2),
      objectiveComplete(step2),
      toolLog(PasteClipboardTrailblazeTool, "pasteClipboard"),
    )

    val yaml = logs.generateRecordedYaml(trailblazeYaml)

    val decoded = trailblazeYaml.decodeTrail(yaml)
    assertThat(decoded.size).isEqualTo(1)
    val prompts = decoded[0] as TrailYamlItem.PromptsTrailItem
    assertThat(prompts.promptSteps.size).isEqualTo(2)
    assertThat(prompts.promptSteps[0].recording!!.tools[0].name).isEqualTo("inputText")
    assertThat(prompts.promptSteps[1].recording!!.tools[0].name).isEqualTo("pasteClipboard")
  }

  @Test
  fun configIsIncludedAtTop() {
    val config = TrailConfig(
      id = "test/case_123",
      title = "Login test",
      context = "Test account: user@test.com",
    )
    val step = DirectionStep(step = "Enter text")
    val logs = listOf(
      objectiveStart(step),
      toolLog(InputTextTrailblazeTool(text = "hello"), "inputText"),
      objectiveComplete(step),
    )

    val yaml = logs.generateRecordedYaml(trailblazeYaml, sessionTrailConfig = config)

    assertThat(yaml).contains("config:")
    assertThat(yaml).contains("id: test/case_123")
    assertThat(yaml).contains("title: Login test")
    assertThat(yaml).contains("Test account: user@test.com")
  }

  @Test
  fun emptyObjectiveWindowProducesPromptWithoutRecording() {
    val step = DirectionStep(step = "Wait for screen")
    val logs = listOf(
      objectiveStart(step),
      // No tool logs within the window
      objectiveComplete(step),
    )

    val yaml = logs.generateRecordedYaml(trailblazeYaml)

    val decoded = trailblazeYaml.decodeTrail(yaml)
    assertThat(decoded.size).isEqualTo(1)
    val prompts = decoded[0] as TrailYamlItem.PromptsTrailItem
    assertThat(prompts.promptSteps[0].prompt).isEqualTo("Wait for screen")
    assertThat(prompts.promptSteps[0].recording).isEqualTo(null)
  }

  /**
   * Round-trip test: Start with a trail YAML, simulate the logs that would be
   * created when running it with recordings, then generate a new recording and
   * verify the output matches the original.
   */
  @Test
  fun roundTripFromYamlToLogsToYaml() {
    val originalYaml = """
      |- prompts:
      |  - step: Enter username
      |    recording:
      |      tools:
      |      - inputText:
      |          text: testuser
      |  - step: Press back
      |    recording:
      |      tools:
      |      - pasteClipboard: {}
      |  - verify: Login button visible
      |    recording:
      |      tools:
      |      - assertVisibleWithText:
      |          text: Login
    """.trimMargin()

    // 1. Parse the original YAML to get trail items
    val trailItems = trailblazeYaml.decodeTrail(originalYaml)
    val promptsItem = trailItems[0] as TrailYamlItem.PromptsTrailItem

    // 2. Simulate the logs that would be created when running these recorded steps
    //    (ObjectiveStartLog, TrailblazeToolLog for each tool, ObjectiveCompleteLog)
    val simulatedLogs = mutableListOf<TrailblazeLog>()
    for (promptStep in promptsItem.promptSteps) {
      simulatedLogs.add(objectiveStart(promptStep))
      if (promptStep.recording != null) {
        for (toolWrapper in promptStep.recording!!.tools) {
          simulatedLogs.add(
            toolLog(
              tool = toolWrapper.trailblazeTool,
              toolName = toolWrapper.name,
            ),
          )
        }
      }
      simulatedLogs.add(objectiveComplete(promptStep))
    }

    // 3. Generate recording from simulated logs
    val generatedYaml = simulatedLogs.generateRecordedYaml(trailblazeYaml)

    // 4. Verify the generated YAML matches the original
    assertThat(generatedYaml).isEqualTo(originalYaml + "\n")
  }

  /**
   * Round-trip with selector-based tools — the tools that appear in real recordings
   * when the LLM calls tapOnElementByNodeId which delegates to tapOnElementBySelector.
   */
  @Test
  fun roundTripWithSelectorBasedTools() {
    val originalYaml = """
      |- prompts:
      |  - step: Tap the login button
      |    recording:
      |      tools:
      |      - tapOnElementBySelector:
      |          reason: Tap the login button
      |          selector:
      |            textRegex: Login
    """.trimMargin()

    val trailItems = trailblazeYaml.decodeTrail(originalYaml)
    val promptsItem = trailItems[0] as TrailYamlItem.PromptsTrailItem

    val simulatedLogs = mutableListOf<TrailblazeLog>()
    for (promptStep in promptsItem.promptSteps) {
      simulatedLogs.add(objectiveStart(promptStep))
      if (promptStep.recording != null) {
        for (toolWrapper in promptStep.recording!!.tools) {
          simulatedLogs.add(toolLog(toolWrapper.trailblazeTool, toolWrapper.name))
        }
      }
      simulatedLogs.add(objectiveComplete(promptStep))
    }

    val generatedYaml = simulatedLogs.generateRecordedYaml(trailblazeYaml)
    assertThat(generatedYaml).isEqualTo(originalYaml + "\n")
  }

  /**
   * Round-trip with config — verifies config is preserved through the round-trip.
   */
  @Test
  fun roundTripWithConfig() {
    val originalYaml = """
      |- config:
      |    id: test/case_123
      |    title: Login test
      |- prompts:
      |  - step: Enter text
      |    recording:
      |      tools:
      |      - inputText:
      |          text: hello
    """.trimMargin()

    val trailItems = trailblazeYaml.decodeTrail(originalYaml)
    val configItem = trailItems[0] as TrailYamlItem.ConfigTrailItem
    val promptsItem = trailItems[1] as TrailYamlItem.PromptsTrailItem

    val simulatedLogs = mutableListOf<TrailblazeLog>()
    for (promptStep in promptsItem.promptSteps) {
      simulatedLogs.add(objectiveStart(promptStep))
      if (promptStep.recording != null) {
        for (toolWrapper in promptStep.recording!!.tools) {
          simulatedLogs.add(toolLog(toolWrapper.trailblazeTool, toolWrapper.name))
        }
      }
      simulatedLogs.add(objectiveComplete(promptStep))
    }

    val generatedYaml = simulatedLogs.generateRecordedYaml(
      trailblazeYaml,
      sessionTrailConfig = configItem.config,
    )
    assertThat(generatedYaml).isEqualTo(originalYaml + "\n")
  }

  /**
   * Round-trip with mixed direction and verification steps.
   */
  @Test
  fun roundTripWithMixedStepTypes() {
    val originalYaml = """
      |- prompts:
      |  - step: Launch the app
      |    recording:
      |      tools:
      |      - launchApp:
      |          appId: com.example.app
      |  - step: Enter username
      |    recording:
      |      tools:
      |      - inputText:
      |          text: testuser
      |  - verify: Welcome message shown
      |    recording:
      |      tools:
      |      - assertVisibleWithText:
      |          text: Welcome
    """.trimMargin()

    val trailItems = trailblazeYaml.decodeTrail(originalYaml)
    val promptsItem = trailItems[0] as TrailYamlItem.PromptsTrailItem

    val simulatedLogs = mutableListOf<TrailblazeLog>()
    for (promptStep in promptsItem.promptSteps) {
      simulatedLogs.add(objectiveStart(promptStep))
      if (promptStep.recording != null) {
        for (toolWrapper in promptStep.recording!!.tools) {
          simulatedLogs.add(toolLog(toolWrapper.trailblazeTool, toolWrapper.name))
        }
      }
      simulatedLogs.add(objectiveComplete(promptStep))
    }

    val generatedYaml = simulatedLogs.generateRecordedYaml(trailblazeYaml)
    assertThat(generatedYaml).isEqualTo(originalYaml + "\n")
  }

  /**
   * When a prompt has multiple tools in its recording, all tools should appear
   * in the generated recording within the same prompt step.
   */
  @Test
  fun roundTripWithMultipleToolsPerStep() {
    val originalYaml = """
      |- prompts:
      |  - step: Fill in the form
      |    recording:
      |      tools:
      |      - inputText:
      |          text: testuser
      |      - pasteClipboard: {}
      |      - inputText:
      |          text: password123
    """.trimMargin()

    val trailItems = trailblazeYaml.decodeTrail(originalYaml)
    val promptsItem = trailItems[0] as TrailYamlItem.PromptsTrailItem

    val simulatedLogs = mutableListOf<TrailblazeLog>()
    for (promptStep in promptsItem.promptSteps) {
      simulatedLogs.add(objectiveStart(promptStep))
      if (promptStep.recording != null) {
        for (toolWrapper in promptStep.recording!!.tools) {
          simulatedLogs.add(toolLog(toolWrapper.trailblazeTool, toolWrapper.name))
        }
      }
      simulatedLogs.add(objectiveComplete(promptStep))
    }

    val generatedYaml = simulatedLogs.generateRecordedYaml(trailblazeYaml)
    assertThat(generatedYaml).isEqualTo(originalYaml + "\n")
  }

  @Test
  fun nonRecordableStepPreservesRecordableFalse() {
    val step = DirectionStep(step = "Do something", recordable = false)
    val logs = listOf(
      objectiveStart(step),
      toolLog(InputTextTrailblazeTool(text = "hello"), "inputText"),
      objectiveComplete(step),
    )

    val yaml = logs.generateRecordedYaml(trailblazeYaml)

    assertThat(yaml).contains("recordable: false")
  }

  @Test
  fun unclosedObjectiveWindowStopsProcessing() {
    val step = DirectionStep(step = "Enter text")
    val logs = listOf(
      objectiveStart(step),
      toolLog(InputTextTrailblazeTool(text = "hello"), "inputText"),
      // No ObjectiveCompleteLog — simulates a crash or timeout
    )

    val yaml = logs.generateRecordedYaml(trailblazeYaml)

    // Should produce empty list (the break on line 73-74 of the generator stops processing)
    assertThat(yaml).isEqualTo("[]\n")
  }

  /**
   * MaestroCommandLog entries are raw Maestro commands logged by OrchestraRunner
   * during tool execution. They should NOT appear in recordings — only the
   * proper MaestroTrailblazeTool (logged as TrailblazeToolLog) should.
   */
  @Test
  fun maestroCommandLogIsExcludedFromRecording() {
    val maestroCommandLog = TrailblazeLog.MaestroCommandLog(
      maestroCommandJsonObj = JsonObject(
        mapOf("tapOn" to JsonObject(mapOf("text" to JsonPrimitive("Login"))))
      ),
      traceId = null,
      successful = true,
      trailblazeToolResult = TrailblazeToolResult.Success(),
      session = testSession,
      timestamp = now,
      durationMs = 50,
    )
    val logs = listOf(maestroCommandLog)

    val yaml = logs.generateRecordedYaml(trailblazeYaml)

    // MaestroCommandLog should be skipped entirely
    assertThat(yaml).isEqualTo("[]\n")
  }

  @Test
  fun maestroCommandLogInsideObjectiveWindowIsExcluded() {
    val step = DirectionStep(step = "Tap login")
    val logs = listOf(
      objectiveStart(step),
      toolLog(
        TapOnByElementSelector(
          reason = "Tap login",
          selector = TrailblazeElementSelector(textRegex = "Login"),
        ),
        "tapOnElementBySelector",
      ),
      // This MaestroCommandLog is the raw command from OrchestraRunner
      TrailblazeLog.MaestroCommandLog(
        maestroCommandJsonObj = JsonObject(
          mapOf("tapOn" to JsonObject(mapOf("text" to JsonPrimitive("Login"))))
        ),
        traceId = null,
        successful = true,
        trailblazeToolResult = TrailblazeToolResult.Success(),
        session = testSession,
        timestamp = now,
        durationMs = 50,
      ),
      objectiveComplete(step),
    )

    val yaml = logs.generateRecordedYaml(trailblazeYaml)

    // Only tapOnElementBySelector should appear, not the raw maestro command
    assertThat(yaml).contains("tapOnElementBySelector")
    assertThat(yaml).doesNotContain("tapOn:")
    val decoded = trailblazeYaml.decodeTrail(yaml)
    val prompts = decoded[0] as TrailYamlItem.PromptsTrailItem
    assertThat(prompts.promptSteps[0].recording!!.tools.size).isEqualTo(1)
  }

  @Test
  fun generatedRecordingCanBeDeserializedAndReencoded() {
    val step1 = DirectionStep(step = "Tap login")
    val step2 = VerificationStep(verify = "Dashboard visible")
    val logs = listOf(
      objectiveStart(step1),
      toolLog(
        TapOnByElementSelector(
          reason = "Tap login button",
          selector = TrailblazeElementSelector(textRegex = "Login"),
        ),
        "tapOnElementBySelector",
      ),
      objectiveComplete(step1),
      objectiveStart(step2),
      toolLog(AssertVisibleWithTextTrailblazeTool(text = "Dashboard"), "assertVisibleWithText"),
      objectiveComplete(step2),
    )

    val yaml = logs.generateRecordedYaml(trailblazeYaml)

    // Verify the YAML can be deserialized back into trail items
    val decoded = trailblazeYaml.decodeTrail(yaml)
    assertThat(decoded.size).isEqualTo(1)
    assertThat(decoded[0]).isInstanceOf(TrailYamlItem.PromptsTrailItem::class)

    val prompts = decoded[0] as TrailYamlItem.PromptsTrailItem
    assertThat(prompts.promptSteps.size).isEqualTo(2)
    assertThat(prompts.promptSteps[0]).isInstanceOf(DirectionStep::class)
    assertThat(prompts.promptSteps[0].prompt).isEqualTo("Tap login")
    assertThat(prompts.promptSteps[1]).isInstanceOf(VerificationStep::class)
    assertThat(prompts.promptSteps[1].prompt).isEqualTo("Dashboard visible")

    // Re-encode and verify stability
    val reencoded = trailblazeYaml.encodeToString(decoded)
    assertThat(reencoded).isEqualTo(yaml)
  }

  // -- Edge cases --

  @Test
  fun emptyLogListProducesEmptyOutput() {
    val yaml = emptyList<TrailblazeLog>().generateRecordedYaml(trailblazeYaml)
    assertThat(yaml).isEqualTo("[]\n")
  }

  @Test
  fun onlyNonRecordableLogsProducesEmptyOutput() {
    val logs = listOf(
      toolLog(InputTextTrailblazeTool(text = "hello"), "takeSnapshot", isRecordable = false),
      toolLog(InputTextTrailblazeTool(text = "world"), "tapOnElementByNodeId", isRecordable = false),
    )

    val yaml = logs.generateRecordedYaml(trailblazeYaml)
    assertThat(yaml).isEqualTo("[]\n")
  }

  @Test
  fun nonRecordableToolsOutsideWindowAreExcluded() {
    val logs = listOf(
      toolLog(InputTextTrailblazeTool(text = "hello"), "inputText"),
      toolLog(InputTextTrailblazeTool(text = "ignored"), "takeSnapshot", isRecordable = false),
      toolLog(PasteClipboardTrailblazeTool, "pasteClipboard"),
    )

    val yaml = logs.generateRecordedYaml(trailblazeYaml)

    val decoded = trailblazeYaml.decodeTrail(yaml)
    val tools = decoded[0] as TrailYamlItem.ToolTrailItem
    assertThat(tools.tools.size).isEqualTo(2)
    assertThat(tools.tools[0].name).isEqualTo("inputText")
    assertThat(tools.tools[1].name).isEqualTo("pasteClipboard")
  }

  @Test
  fun failedToolsAreStillIncludedInRecording() {
    val step = DirectionStep(step = "Tap button")
    val logs = listOf(
      objectiveStart(step),
      TrailblazeLog.TrailblazeToolLog(
        trailblazeTool = TapOnByElementSelector(
          reason = "Tap button",
          selector = TrailblazeElementSelector(textRegex = "Submit"),
        ),
        toolName = "tapOnElementBySelector",
        successful = false,
        exceptionMessage = "Element not found",
        traceId = null,
        durationMs = 100,
        session = testSession,
        timestamp = now,
        isRecordable = true,
      ),
      objectiveComplete(step),
    )

    val yaml = logs.generateRecordedYaml(trailblazeYaml)

    assertThat(yaml).contains("tapOnElementBySelector")
    val decoded = trailblazeYaml.decodeTrail(yaml)
    val prompts = decoded[0] as TrailYamlItem.PromptsTrailItem
    assertThat(prompts.promptSteps[0].recording!!.tools.size).isEqualTo(1)
  }

  /**
   * Simulates a realistic AI-driven session where the LLM calls multiple tools
   * within an objective window, including non-recordable infrastructure tools,
   * delegating tools, and MaestroCommandLogs from execution.
   */
  @Test
  fun realisticAiDrivenSessionFiltersCorrectly() {
    val step = DirectionStep(step = "Tap on the login button")
    val selectorTool = TapOnByElementSelector(
      reason = "Tap login",
      selector = TrailblazeElementSelector(textRegex = "Login"),
    )
    val logs = listOf(
      objectiveStart(step),
      // Non-recordable tool (should be excluded from recording)
      toolLog(InputTextTrailblazeTool(text = ""), "takeSnapshot", isRecordable = false),
      // LLM calls tapOnElementByNodeId (delegating, non-recordable)
      delegatingToolLog(
        tool = selectorTool,
        toolName = "tapOnElementByNodeId",
        executableTools = listOf(selectorTool),
      ),
      // The selector-based tool is logged (recordable)
      toolLog(selectorTool, "tapOnElementBySelector"),
      // OrchestraRunner logs the raw Maestro command
      maestroCommandLog(),
      objectiveComplete(step),
    )

    val yaml = logs.generateRecordedYaml(trailblazeYaml)

    // Only tapOnElementBySelector should appear
    val decoded = trailblazeYaml.decodeTrail(yaml)
    assertThat(decoded.size).isEqualTo(1)
    val prompts = decoded[0] as TrailYamlItem.PromptsTrailItem
    assertThat(prompts.promptSteps.size).isEqualTo(1)
    assertThat(prompts.promptSteps[0].prompt).isEqualTo("Tap on the login button")
    assertThat(prompts.promptSteps[0].recording!!.tools.size).isEqualTo(1)
    assertThat(prompts.promptSteps[0].recording!!.tools[0].name).isEqualTo("tapOnElementBySelector")
  }

  /**
   * Tests a full multi-step trail with tools block followed by prompts block,
   * interleaved with noise logs. This simulates a real trail file structure.
   */
  @Test
  fun toolsBlockFollowedByPromptsBlock() {
    val step = DirectionStep(step = "Enter username")
    val logs = listOf(
      // Tools block (outside objective window)
      toolLog(
        LaunchAppTrailblazeTool(appId = "com.example.app"),
        "launchApp",
      ),
      // Maestro commands from launchApp execution (should be excluded)
      maestroCommandLog(),
      // Prompts block (inside objective window)
      objectiveStart(step),
      toolLog(InputTextTrailblazeTool(text = "testuser"), "inputText"),
      objectiveComplete(step),
    )

    val yaml = logs.generateRecordedYaml(trailblazeYaml)

    val decoded = trailblazeYaml.decodeTrail(yaml)
    assertThat(decoded.size).isEqualTo(2)
    // First item should be the tools block
    assertThat(decoded[0]).isInstanceOf(TrailYamlItem.ToolTrailItem::class)
    val tools = decoded[0] as TrailYamlItem.ToolTrailItem
    assertThat(tools.tools.size).isEqualTo(1)
    assertThat(tools.tools[0].name).isEqualTo("launchApp")
    // Second item should be the prompts block
    assertThat(decoded[1]).isInstanceOf(TrailYamlItem.PromptsTrailItem::class)
    val prompts = decoded[1] as TrailYamlItem.PromptsTrailItem
    assertThat(prompts.promptSteps[0].prompt).isEqualTo("Enter username")
  }

  /**
   * Tests that multiple objective windows separated by noise produce
   * a single merged PromptsTrailItem.
   */
  @Test
  fun multipleObjectiveWindowsWithNoiseBetween() {
    val step1 = DirectionStep(step = "Step one")
    val step2 = DirectionStep(step = "Step two")
    val logs = listOf(
      objectiveStart(step1),
      toolLog(InputTextTrailblazeTool(text = "one"), "inputText"),
      objectiveComplete(step1),
      // Noise between objective windows
      maestroCommandLog(),
      maestroCommandLog(),
      objectiveStart(step2),
      toolLog(PasteClipboardTrailblazeTool, "pasteClipboard"),
      objectiveComplete(step2),
    )

    val yaml = logs.generateRecordedYaml(trailblazeYaml)

    val decoded = trailblazeYaml.decodeTrail(yaml)
    // The maestro commands between windows should be skipped, so both
    // prompts merge into one PromptsTrailItem
    assertThat(decoded.size).isEqualTo(1)
    val prompts = decoded[0] as TrailYamlItem.PromptsTrailItem
    assertThat(prompts.promptSteps.size).isEqualTo(2)
    assertThat(prompts.promptSteps[0].prompt).isEqualTo("Step one")
    assertThat(prompts.promptSteps[1].prompt).isEqualTo("Step two")
  }

  /**
   * Round-trip for a realistic trail file with config, tool block, and prompts.
   * Config field order may differ between input and output (serialized in
   * TrailConfig declaration order), so we verify structure rather than exact string.
   */
  @Test
  fun roundTripFullTrailFile() {
    val config = TrailConfig(
      id = "testrail/suite_123/case_456",
      title = "User can log in",
      priority = "P0",
      context = "Account email: test@example.com",
      metadata = mapOf("testRailCaseId" to "456"),
    )
    val promptsYaml = """
      |- prompts:
      |  - step: Enter username
      |    recording:
      |      tools:
      |      - inputText:
      |          text: test@example.com
      |  - step: Tap login button
      |    recording:
      |      tools:
      |      - tapOnElementBySelector:
      |          reason: Tap login
      |          selector:
      |            textRegex: Login
      |  - verify: Dashboard is visible
      |    recording:
      |      tools:
      |      - assertVisibleBySelector:
      |          reason: Verify dashboard
      |          selector:
      |            textRegex: Dashboard
    """.trimMargin()

    val promptsItems = trailblazeYaml.decodeTrail(promptsYaml)
    val promptsItem = promptsItems[0] as TrailYamlItem.PromptsTrailItem

    // Simulate logs: launchApp outside window, then prompts with objectives
    val simulatedLogs = mutableListOf<TrailblazeLog>()
    simulatedLogs.add(toolLog(LaunchAppTrailblazeTool(appId = "com.example.app"), "launchApp"))

    for (promptStep in promptsItem.promptSteps) {
      simulatedLogs.add(objectiveStart(promptStep))
      if (promptStep.recording != null) {
        for (toolWrapper in promptStep.recording!!.tools) {
          simulatedLogs.add(toolLog(toolWrapper.trailblazeTool, toolWrapper.name))
        }
      }
      simulatedLogs.add(objectiveComplete(promptStep))
    }

    val generatedYaml = simulatedLogs.generateRecordedYaml(
      trailblazeYaml,
      sessionTrailConfig = config,
    )

    // Verify the generated YAML is structurally correct
    val decoded = trailblazeYaml.decodeTrail(generatedYaml)
    assertThat(decoded.size).isEqualTo(3) // config + tools + prompts

    val decodedConfig = (decoded[0] as TrailYamlItem.ConfigTrailItem).config
    assertThat(decodedConfig.id).isEqualTo("testrail/suite_123/case_456")
    assertThat(decodedConfig.title).isEqualTo("User can log in")
    assertThat(decodedConfig.priority).isEqualTo("P0")
    assertThat(decodedConfig.context).isEqualTo("Account email: test@example.com")
    assertThat(decodedConfig.metadata).isEqualTo(mapOf("testRailCaseId" to "456"))

    val decodedTools = (decoded[1] as TrailYamlItem.ToolTrailItem).tools
    assertThat(decodedTools.size).isEqualTo(1)
    assertThat(decodedTools[0].name).isEqualTo("launchApp")

    val decodedPrompts = (decoded[2] as TrailYamlItem.PromptsTrailItem).promptSteps
    assertThat(decodedPrompts.size).isEqualTo(3)
    assertThat(decodedPrompts[0].prompt).isEqualTo("Enter username")
    assertThat(decodedPrompts[1].prompt).isEqualTo("Tap login button")
    assertThat(decodedPrompts[2].prompt).isEqualTo("Dashboard is visible")
    assertThat(decodedPrompts[0].recording!!.tools[0].name).isEqualTo("inputText")
    assertThat(decodedPrompts[1].recording!!.tools[0].name).isEqualTo("tapOnElementBySelector")
    assertThat(decodedPrompts[2].recording!!.tools[0].name).isEqualTo("assertVisibleBySelector")

    // Verify re-encoding stability
    val reencoded = trailblazeYaml.encodeToString(decoded)
    assertThat(reencoded).isEqualTo(generatedYaml)
  }

  @Test
  fun allRecordableToolTypesSerializeCorrectly() {
    val step = DirectionStep(step = "Do many things")
    val logs = listOf(
      objectiveStart(step),
      toolLog(InputTextTrailblazeTool(text = "hello"), "inputText"),
      toolLog(PasteClipboardTrailblazeTool, "pasteClipboard"),
      toolLog(WaitForIdleSyncTrailblazeTool(timeToWaitInSeconds = 3), "wait"),
      toolLog(
        SwipeTrailblazeTool(direction = maestro.SwipeDirection.UP),
        "swipe",
      ),
      toolLog(
        TapOnByElementSelector(
          reason = "Tap element",
          selector = TrailblazeElementSelector(textRegex = "OK"),
        ),
        "tapOnElementBySelector",
      ),
      toolLog(
        AssertVisibleBySelectorTrailblazeTool(
          reason = "Check visible",
          selector = TrailblazeElementSelector(textRegex = "Success"),
        ),
        "assertVisibleBySelector",
      ),
      objectiveComplete(step),
    )

    val yaml = logs.generateRecordedYaml(trailblazeYaml)

    // Verify all tools appear and the YAML is valid
    val decoded = trailblazeYaml.decodeTrail(yaml)
    val prompts = decoded[0] as TrailYamlItem.PromptsTrailItem
    val tools = prompts.promptSteps[0].recording!!.tools
    assertThat(tools.size).isEqualTo(6)
    assertThat(tools.map { it.name }).isEqualTo(
      listOf("inputText", "pasteClipboard", "wait", "swipe", "tapOnElementBySelector", "assertVisibleBySelector"),
    )

    // Verify round-trip stability
    val reencoded = trailblazeYaml.encodeToString(decoded)
    assertThat(reencoded).isEqualTo(yaml)
  }
}
