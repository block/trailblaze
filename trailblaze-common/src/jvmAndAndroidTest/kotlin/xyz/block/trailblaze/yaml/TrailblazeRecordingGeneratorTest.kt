package xyz.block.trailblaze.yaml

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotEmpty
import assertk.assertions.doesNotContain
import assertk.assertions.startsWith
import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Test
import xyz.block.trailblaze.agent.model.AgentTaskStatus
import xyz.block.trailblaze.agent.model.AgentTaskStatusData
import xyz.block.trailblaze.api.DriverNodeMatch
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.logs.model.TaskId
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.client.temp.OtherTrailblazeTool
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.toLogPayload
import xyz.block.trailblaze.toolcalls.toLogPayloads
import xyz.block.trailblaze.toolcalls.commands.AssertVisibleBySelectorTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.AssertVisibleWithTextTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.InputTextTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.LaunchAppTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.PasteClipboardTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.SwipeTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.TapOnByElementSelector
import xyz.block.trailblaze.toolcalls.commands.TapTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.WaitForIdleSyncTrailblazeTool
import xyz.block.trailblaze.toolcalls.getIsRecordableFromAnnotation

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

  /**
   * A session-started log carrying [rawYaml] — the full YAML the run was launched with — and the
   * device [classifiers] the preview derives the recording slot from. The unified preview reads
   * rawYaml to seed the merge so other platforms' recordings survive a single-device re-record.
   */
  private fun startedLog(
    rawYaml: String? = null,
    classifiers: List<String> = emptyList(),
  ) = TrailblazeLog.TrailblazeSessionStatusChangeLog(
    sessionStatus = SessionStatus.Started(
      trailConfig = null,
      trailFilePath = null,
      hasRecordedSteps = false,
      testMethodName = "test",
      testClassName = "Test",
      trailblazeDeviceInfo = TrailblazeDeviceInfo(
        trailblazeDeviceId = TrailblazeDeviceId(
          instanceId = "pixel-7",
          trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
        ),
        trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
        widthPixels = 1080,
        heightPixels = 1920,
        classifiers = classifiers.map { xyz.block.trailblaze.devices.TrailblazeDeviceClassifier(it) },
      ),
      rawYaml = rawYaml,
    ),
    session = testSession,
    timestamp = now,
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

  private fun objectiveCompleteFailed(prompt: PromptStep, failureReason: String) =
    TrailblazeLog.ObjectiveCompleteLog(
      promptStep = prompt,
      objectiveResult = AgentTaskStatus.Failure.ObjectiveFailed(
        llmExplanation = failureReason,
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
    isTopLevelToolCall: Boolean = false,
    isVerification: Boolean = false,
    timestamp: kotlinx.datetime.Instant = now,
    durationMs: Long = 100,
    successful: Boolean = true,
    /** The authored token-bearing form, when the dispatch boundary rewrote [tool] before execution. */
    rawTool: xyz.block.trailblaze.toolcalls.TrailblazeTool? = null,
    traceId: xyz.block.trailblaze.logs.model.TraceId? = null,
  ) = TrailblazeLog.TrailblazeToolLog(
    trailblazeTool = tool.toLogPayload(),
    rawTrailblazeTool = rawTool?.toLogPayload(),
    toolName = toolName,
    successful = successful,
    traceId = traceId,
    durationMs = durationMs,
    session = testSession,
    timestamp = timestamp,
    isRecordable = isRecordable,
    isTopLevelToolCall = isTopLevelToolCall,
    isVerification = isVerification,
  )

  private fun delegatingToolLog(
    tool: xyz.block.trailblaze.toolcalls.TrailblazeTool,
    toolName: String,
    executableTools: List<xyz.block.trailblaze.toolcalls.TrailblazeTool> = emptyList(),
  ) = TrailblazeLog.DelegatingTrailblazeToolLog(
    trailblazeTool = tool.toLogPayload(),
    toolName = toolName,
    executableTools = executableTools.toLogPayloads(),
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
  fun emptyObjectiveWindowGeneratesStepWithoutRecording() {
    // Zero recordable tools fired during this window. The generator emits no `recording:` block
    // so replay falls through to AI rather than ghost-passing on an empty recording.
    val step = DirectionStep(step = "Confirm closing the dialog")
    val logs = listOf(
      objectiveStart(step),
      objectiveComplete(step),
    )

    val yaml = logs.generateRecordedYaml(trailblazeYaml)

    val expected = """
      |- prompts:
      |  - step: Confirm closing the dialog
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
      toolLog(PasteClipboardTrailblazeTool, "mobile_pasteClipboard"),
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
      |      - mobile_pasteClipboard: {}
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
          nodeSelector = TrailblazeNodeSelector.withMatch(DriverNodeMatch.AndroidAccessibility(textRegex = "Login")),
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

  /**
   * Regression: `tap { ref }` is a snapshot-scoped, ephemeral identifier. The `tap` tool
   * (`TapTrailblazeTool`) is a `DelegatingTrailblazeTool` whose only role is to expand the
   * ref into a durable selector at execution time. It must never persist into a recording —
   * refs are content-hashed against the live snapshot and meaningless on replay.
   *
   * **How the bug manifested.** The MCP `step` flow emits two log entries per LLM tool call:
   * (a) the dispatcher's `TapOnByElementSelector` `TrailblazeToolLog` (`isTopLevelToolCall =
   * false`), and (b) `StepToolSet.emitDirectToolLog`'s log of the *raw* tool the LLM asked
   * for (`isTopLevelToolCall = true`, `isRecordable` from the annotation). The recorder's
   * window-selection rule prefers top-level logs when present
   * (`toolLogsInWindow.filter { it.isTopLevelToolCall }.ifEmpty { toolLogsInWindow }`), so
   * with the previous default `isRecordable = true` on `TapTrailblazeTool` the ref-bearing
   * log won the filter and the selector log was dropped. Marking the tool non-recordable
   * removes (b) from the recordable set, so the `ifEmpty` branch falls back to the
   * selector log and the YAML carries the durable selector instead.
   */
  @Test
  fun tapTrailblazeToolWithRefIsNeverRecorded() {
    // (1) Annotation contract: log-construction helpers stamp `isRecordable = false` on any
    // log entry that wraps a TapTrailblazeTool — including StepToolSet.emitDirectToolLog
    // (which reads getIsRecordableFromAnnotation()).
    assertThat(TapTrailblazeTool(ref = "h801").getIsRecordableFromAnnotation()).isEqualTo(false)

    // (2) End-to-end: reproduce the exact two-log shape the MCP step path produces and
    // verify the recorder picks the durable selector, not the ref. The top-level
    // tap{ref} entry must be filtered out by the recordable bit so the selector log
    // (which is `isTopLevelToolCall = false`) wins via the `ifEmpty` fallback.
    val step = DirectionStep(step = "Choose sign in with email")
    val logs = listOf(
      objectiveStart(step),
      // Selector log emitted by the dispatcher after `executeDelegatingTool` expands the
      // tap. Not a top-level call — it's the dispatcher's translation of the LLM's call.
      toolLog(
        TapOnByElementSelector(
          reason = "Tap sign-in-with-email row",
          nodeSelector = TrailblazeNodeSelector.withMatch(DriverNodeMatch.AndroidAccessibility(textRegex = "Sign in with email")),
        ),
        "tapOnElementBySelector",
        isTopLevelToolCall = false,
      ),
      // Top-level log emitted by StepToolSet.emitDirectToolLog with the raw LLM call.
      // Pre-fix this had `isRecordable = true` (annotation default) and shadowed the
      // selector log; post-fix the annotation forces `isRecordable = false`.
      toolLog(
        TapTrailblazeTool(ref = "h801"),
        "tap",
        isRecordable = false,
        isTopLevelToolCall = true,
      ),
      objectiveComplete(step),
    )

    val yaml = logs.generateRecordedYaml(trailblazeYaml)

    assertThat(yaml).doesNotContain("ref: h801")
    val decoded = trailblazeYaml.decodeTrail(yaml)
    val prompts = decoded[0] as TrailYamlItem.PromptsTrailItem
    val recordedNames = prompts.promptSteps[0].recording!!.tools.map { it.name }
    assertThat(recordedNames).isEqualTo(listOf("tapOnElementBySelector"))
  }

  @Test
  fun topLevelDirectToolLogsArePreferredOverExecutorLogs() {
    val step = DirectionStep(step = "Create contact through scripted tool")
    val logs = listOf(
      objectiveStart(step),
      toolLog(
        tool = xyz.block.trailblaze.logs.client.temp.OtherTrailblazeTool(
          toolName = "ios_contacts_create_contact",
          raw = JsonObject(
            mapOf(
              "firstName" to JsonPrimitive("Trailblaze"),
              "lastName" to JsonPrimitive("Codex0426"),
            ),
          ),
        ),
        toolName = "ios_contacts_create_contact",
        isTopLevelToolCall = true,
      ),
      toolLog(LaunchAppTrailblazeTool(appId = "com.apple.MobileAddressBook"), "launchApp"),
      toolLog(InputTextTrailblazeTool(text = "Trailblaze"), "inputText"),
      objectiveComplete(step),
    )

    val yaml = logs.generateRecordedYaml(trailblazeYaml)

    assertThat(yaml).contains("ios_contacts_create_contact")
    assertThat(yaml).doesNotContain("launchApp")
    assertThat(yaml).doesNotContain("inputText")
  }

  @Test
  fun delegatingToolLogIsSkipped() {
    val step = DirectionStep(step = "Tap element")
    val selectorTool = TapOnByElementSelector(
      reason = "Tap login",
      nodeSelector = TrailblazeNodeSelector.withMatch(DriverNodeMatch.AndroidAccessibility(textRegex = "Login")),
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
      toolLog(PasteClipboardTrailblazeTool, "mobile_pasteClipboard"),
    )

    val yaml = logs.generateRecordedYaml(trailblazeYaml)

    val expected = """
      |- tools:
      |  - inputText:
      |      text: hello
      |  - mobile_pasteClipboard: {}
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
          nodeSelector = TrailblazeNodeSelector.withMatch(DriverNodeMatch.AndroidAccessibility(textRegex = "Button")),
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
      toolLog(PasteClipboardTrailblazeTool, "mobile_pasteClipboard"),
    )

    val yaml = logs.generateRecordedYaml(trailblazeYaml)

    val decoded = trailblazeYaml.decodeTrail(yaml)
    assertThat(decoded.size).isEqualTo(1)
    val prompts = decoded[0] as TrailYamlItem.PromptsTrailItem
    assertThat(prompts.promptSteps.size).isEqualTo(2)
    assertThat(prompts.promptSteps[0].recording!!.tools[0].name).isEqualTo("inputText")
    assertThat(prompts.promptSteps[1].recording!!.tools[0].name).isEqualTo("mobile_pasteClipboard")
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
  fun configCarriesEveryFieldIntoTheSavedRecording() {
    // The generator must carry the session config wholesale — a rebuilt field list here
    // silently dropped tags/skip/memory before any save-back merge saw them, so a
    // re-recorded trail lost its memory seed / tags on save.
    val config = TrailConfig(
      id = "test/case_123",
      title = "Login test",
      priority = "P1",
      source = TrailSource(type = TrailSourceType.HANDWRITTEN, reason = "authored by hand"),
      tags = listOf("smoke"),
      skip = "blocked on #123",
      memory = mapOf("email" to "tb+test@example.com"),
    )
    val step = DirectionStep(step = "Enter text")
    val logs = listOf(
      objectiveStart(step),
      toolLog(InputTextTrailblazeTool(text = "hello"), "inputText"),
      objectiveComplete(step),
    )

    val yaml = logs.generateRecordedYaml(trailblazeYaml, sessionTrailConfig = config)

    val savedConfig = trailblazeYaml.extractTrailConfig(yaml)
    assertThat(savedConfig).isEqualTo(config)
  }

  @Test
  fun emptyObjectiveWindowProducesStepWithoutRecording() {
    val step = DirectionStep(step = "Wait for screen")
    val logs = listOf(
      objectiveStart(step),
      // No tool logs within the window.
      objectiveComplete(step),
    )

    val yaml = logs.generateRecordedYaml(trailblazeYaml)

    val decoded = trailblazeYaml.decodeTrail(yaml)
    assertThat(decoded.size).isEqualTo(1)
    val prompts = decoded[0] as TrailYamlItem.PromptsTrailItem
    assertThat(prompts.promptSteps[0].prompt).isEqualTo("Wait for screen")
    // Empty windows now emit no `recording:` block, so replay falls through to AI rather than
    // ghost-passing on an empty recorded step.
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
      |      - mobile_pasteClipboard: {}
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
      |          nodeSelector:
      |            androidAccessibility:
      |              textRegex: Login
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
      |      - mobile_pasteClipboard: {}
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
          nodeSelector = TrailblazeNodeSelector.withMatch(DriverNodeMatch.AndroidAccessibility(textRegex = "Login")),
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
          nodeSelector = TrailblazeNodeSelector.withMatch(DriverNodeMatch.AndroidAccessibility(textRegex = "Login")),
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
      toolLog(PasteClipboardTrailblazeTool, "mobile_pasteClipboard"),
    )

    val yaml = logs.generateRecordedYaml(trailblazeYaml)

    val decoded = trailblazeYaml.decodeTrail(yaml)
    val tools = decoded[0] as TrailYamlItem.ToolTrailItem
    assertThat(tools.tools.size).isEqualTo(2)
    assertThat(tools.tools[0].name).isEqualTo("inputText")
    assertThat(tools.tools[1].name).isEqualTo("mobile_pasteClipboard")
  }

  @Test
  fun failedToolsAreStillIncludedInRecording() {
    val step = DirectionStep(step = "Tap button")
    val logs = listOf(
      objectiveStart(step),
      TrailblazeLog.TrailblazeToolLog(
        trailblazeTool = TapOnByElementSelector(
          reason = "Tap button",
          nodeSelector = TrailblazeNodeSelector.withMatch(DriverNodeMatch.AndroidAccessibility(textRegex = "Submit")),
        ).toLogPayload(),
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
      nodeSelector = TrailblazeNodeSelector.withMatch(DriverNodeMatch.AndroidAccessibility(textRegex = "Login")),
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
      toolLog(PasteClipboardTrailblazeTool, "mobile_pasteClipboard"),
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
      id = "regression/suite_123/case_456",
      title = "User can log in",
      priority = "P0",
      context = "Account email: test@example.com",
      metadata = mapOf("caseId" to "456"),
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
    assertThat(decodedConfig.id).isEqualTo("regression/suite_123/case_456")
    assertThat(decodedConfig.title).isEqualTo("User can log in")
    assertThat(decodedConfig.priority).isEqualTo("P0")
    assertThat(decodedConfig.context).isEqualTo("Account email: test@example.com")
    assertThat(decodedConfig.metadata).isEqualTo(mapOf("caseId" to "456"))

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
      toolLog(PasteClipboardTrailblazeTool, "mobile_pasteClipboard"),
      toolLog(WaitForIdleSyncTrailblazeTool(timeToWaitInSeconds = 3), "wait"),
      toolLog(
        SwipeTrailblazeTool(direction = maestro.SwipeDirection.UP),
        "swipe",
      ),
      toolLog(
        TapOnByElementSelector(
          reason = "Tap element",
          nodeSelector = TrailblazeNodeSelector.withMatch(DriverNodeMatch.AndroidAccessibility(textRegex = "OK")),
        ),
        "tapOnElementBySelector",
      ),
      toolLog(
        AssertVisibleBySelectorTrailblazeTool(
          reason = "Check visible",
          nodeSelector = TrailblazeNodeSelector.withMatch(DriverNodeMatch.AndroidAccessibility(textRegex = "Success")),
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
      listOf("inputText", "mobile_pasteClipboard", "wait", "swipe", "tapOnElementBySelector", "assertVisibleBySelector"),
    )

    // Verify round-trip stability
    val reencoded = trailblazeYaml.encodeToString(decoded)
    assertThat(reencoded).isEqualTo(yaml)
  }

  @Test
  fun verificationDuplicatesCollapseInStepActionsPreserved() {
    // Reproduces the case_5370490 step 6 pattern: 4 assertVisibleBySelector for the same key
    // with varying `reason:` strings, plus 2 identical TapOnByElementSelector calls. The
    // assertions collapse to one (verifications are idempotent); the taps preserve both
    // (entering "11" needs both presses).
    val step = VerificationStep(verify = "Confirm key '1' is visible")
    val keySelector = TrailblazeNodeSelector.withMatch(DriverNodeMatch.AndroidAccessibility(textRegex = "1"))
    val assertWithReason = { reason: String ->
      toolLog(
        AssertVisibleBySelectorTrailblazeTool(reason = reason, nodeSelector = keySelector),
        "assertVisibleBySelector",
        isVerification = true,
      )
    }
    val tapKey = toolLog(
      TapOnByElementSelector(reason = "Press 1", nodeSelector = keySelector),
      "tapOnElementBySelector",
    )
    val logs = listOf(
      objectiveStart(step),
      assertWithReason("digit 1 visible"),
      assertWithReason("re-check digit 1"),
      assertWithReason("verify digit 1 still visible"),
      assertWithReason("digit 1 confirmed"),
      tapKey,
      tapKey,
      objectiveComplete(step),
    )

    val yaml = logs.generateRecordedYaml(trailblazeYaml)
    val decoded = trailblazeYaml.decodeTrail(yaml)
    val prompts = decoded[0] as TrailYamlItem.PromptsTrailItem
    val tools = prompts.promptSteps[0].recording!!.tools
    assertThat(tools.filter { it.name == "assertVisibleBySelector" }.size).isEqualTo(1)
    assertThat(tools.filter { it.name == "tapOnElementBySelector" }.size).isEqualTo(2)
  }

  /**
   * Recording preservation for the `runIf` conditional scripted tool. When the agent invokes a
   * registered scripted tool like `runIf`, the host path emits a single recordable
   * [TrailblazeLog.TrailblazeToolLog] carrying the whole `{condition, then, else}` wrapper as its
   * raw args (modeled here as [OtherTrailblazeTool], the on-the-fly representation of a tool not on
   * the models classpath); the inner tool-calls it dispatches via `trailblaze.call` are NOT logged.
   *
   * So the recorder must persist the `runIf` wrapper VERBATIM — one top-level `runIf:` entry whose
   * `then` keeps the inner `tapOnElementBySelector` nested inside it — and must NOT flatten it to the
   * inner primitive. Flattening would discard the branch and turn the step back into the
   * unconditional tap that flips state every run (the motivating flake). The size==1 + name=="runIf"
   * assertions are the negative control: a flattened recording would surface the inner tap as the
   * recorded tool instead.
   */
  @Test
  fun runIfWrapperIsRecordedVerbatimNotFlattened() {
    val runIfRaw = buildJsonObject {
      putJsonObject("condition") {
        putJsonObject("selector") {
          putJsonObject("androidAccessibility") { put("textRegex", "Manual card entry") }
          putJsonObject("childOf") {
            putJsonObject("containsChild") {
              putJsonObject("androidAccessibility") { put("isChecked", true) }
            }
          }
        }
      }
      putJsonArray("then") {
        addJsonObject {
          putJsonObject("tapOnElementBySelector") {
            put("reason", "Manual card entry toggle is on; tap to disable it.")
            putJsonObject("nodeSelector") {
              putJsonObject("containsChild") {
                putJsonObject("androidAccessibility") { put("textRegex", "Manual card entry") }
              }
            }
          }
        }
      }
    }

    val step = DirectionStep(step = "If Manual card entry is on, tap to disable it")
    val logs = listOf(
      objectiveStart(step),
      toolLog(
        tool = xyz.block.trailblaze.logs.client.temp.OtherTrailblazeTool(
          toolName = "runIf",
          raw = runIfRaw,
        ),
        toolName = "runIf",
      ),
      objectiveComplete(step),
    )

    val yaml = logs.generateRecordedYaml(trailblazeYaml)

    // The wrapper is recorded as a SINGLE tool named `runIf` — not flattened to its inner tap.
    val decoded = trailblazeYaml.decodeTrail(yaml)
    val prompts = decoded[0] as TrailYamlItem.PromptsTrailItem
    val recordedTools = prompts.promptSteps[0].recording!!.tools
    assertThat(recordedTools.size).isEqualTo(1)
    assertThat(recordedTools[0].name).isEqualTo("runIf")

    // The condition and the nested `then` action survive verbatim in the recorded YAML.
    assertThat(yaml).contains("runIf:")
    assertThat(yaml).contains("condition:")
    assertThat(yaml).contains("isChecked: true")
    assertThat(yaml).contains("then:")
    assertThat(yaml).contains("tapOnElementBySelector:")

    // Decoded recording re-encodes identically — the verbatim wrapper round-trips.
    val reencoded = trailblazeYaml.encodeToString(decoded)
    assertThat(reencoded).isEqualTo(yaml)
  }

  @Test
  fun recordedTrailheadObjectiveEmitsTrailheadElement() {
    // A trailhead-marked objective (lowered from `trailhead:`) must come back out as a `- trailhead:`
    // root element, not a plain prompt step.
    val trailheadStep = TrailheadDefinition(
      step = "Sign in fresh",
      tools = listOf(
        TrailblazeToolYamlWrapper(
          name = "launchApp",
          trailblazeTool = LaunchAppTrailblazeTool("com.example"),
        ),
      ),
    ).toPromptStep()
    val logs = listOf(
      objectiveStart(trailheadStep),
      toolLog(LaunchAppTrailblazeTool("com.example"), "launchApp"),
      objectiveComplete(trailheadStep),
    )

    val yaml = logs.generateRecordedYaml(trailblazeYaml)
    assertThat(yaml).contains("trailhead:")
    assertThat(yaml).contains("Sign in fresh")
    assertThat(yaml).doesNotContain("- prompts:")

    val th = trailblazeYaml.decodeTrail(yaml)
      .filterIsInstance<TrailYamlItem.TrailheadTrailItem>().single().trailhead
    assertThat(th.step).isEqualTo("Sign in fresh")
    assertThat(th.tools!!.single().name).isEqualTo("launchApp")
  }

  @Test
  fun shorthandTrailheadObjectiveDropsTheDefaultStepText() {
    // A bare-string-shorthand trailhead carries no authored step (DEFAULT_STEP stands in at runtime);
    // the recorded `- trailhead:` should NOT resurrect that sentinel as an authored step.
    val trailheadStep = TrailheadDefinition(
      tools = listOf(
        TrailblazeToolYamlWrapper(
          name = "launchApp",
          trailblazeTool = LaunchAppTrailblazeTool("com.example"),
        ),
      ),
    ).toPromptStep()
    val logs = listOf(
      objectiveStart(trailheadStep),
      toolLog(LaunchAppTrailblazeTool("com.example"), "launchApp"),
      objectiveComplete(trailheadStep),
    )

    val yaml = logs.generateRecordedYaml(trailblazeYaml)
    assertThat(yaml).contains("trailhead:")
    assertThat(yaml).doesNotContain(TrailheadDefinition.DEFAULT_STEP)

    val th = trailblazeYaml.decodeTrail(yaml)
      .filterIsInstance<TrailYamlItem.TrailheadTrailItem>().single().trailhead
    assertThat(th.step).isEqualTo(null)
    assertThat(th.tools!!.single().name).isEqualTo("launchApp")
  }

  /**
   * Regression: a `--self-heal` run whose trailhead recording fails mid-flight produces TWO
   * trailhead-marked objective windows in the session logs — the recorded attempt closes its
   * window with a failed complete before AI recovery (`TestAgentRunner.recover`) opens its own
   * start/complete pair for the SAME step-0 prompt. The generator used to append one
   * `- trailhead:` item per window, emitting YAML the repo's own strict parser rejects with
   * "Only one trailhead item is allowed in a trail." The windows must fold into ONE trailhead
   * item whose tools are both windows' tools in execution order.
   */
  @Test
  fun healedTrailheadRunEmitsOneTrailheadItemThatRoundTripsStrictParse() {
    val trailheadStep = TrailheadDefinition(
      step = "Launch the app signed in",
      tools = listOf(
        TrailblazeToolYamlWrapper(
          name = "launchApp",
          trailblazeTool = LaunchAppTrailblazeTool("com.example"),
        ),
      ),
      maxRetries = 2,
    ).toPromptStep()
    val followUpStep = DirectionStep(step = "Open the settings tab")
    val logs = listOf(
      // Window 1: the recorded trailhead attempt — its tool fails mid-flight.
      objectiveStart(trailheadStep),
      toolLog(LaunchAppTrailblazeTool("com.example"), "launchApp", successful = false),
      objectiveCompleteFailed(trailheadStep, "Recording failed at launchApp: app crashed"),
      // Window 2: AI recovery re-opens the same trailhead objective and heals it.
      objectiveStart(trailheadStep),
      toolLog(InputTextTrailblazeTool(text = "user@example.com"), "inputText"),
      toolLog(
        TapOnByElementSelector(
          reason = "Tap sign in",
          nodeSelector = TrailblazeNodeSelector.withMatch(DriverNodeMatch.AndroidAccessibility(textRegex = "Sign in")),
        ),
        "tapOnElementBySelector",
      ),
      objectiveComplete(trailheadStep),
      // A normal step after the healed trailhead — the merged trailhead must stay ahead of it.
      objectiveStart(followUpStep),
      toolLog(PasteClipboardTrailblazeTool, "mobile_pasteClipboard"),
      objectiveComplete(followUpStep),
    )

    val yaml = logs.generateRecordedYaml(trailblazeYaml)

    // Round-trips through the strict parser (this decode threw before the merge fix).
    val decoded = trailblazeYaml.decodeTrail(yaml)
    val th = decoded.filterIsInstance<TrailYamlItem.TrailheadTrailItem>().single().trailhead
    assertThat(th.step).isEqualTo("Launch the app signed in")
    assertThat(th.maxRetries).isEqualTo(2)
    // Both windows' tools survive, in execution order: the failed recorded attempt, then the heal.
    assertThat(th.tools!!.map { it.name })
      .isEqualTo(listOf("launchApp", "inputText", "tapOnElementBySelector"))
    // The follow-up step is untouched and sits after the trailhead.
    val prompts = decoded.filterIsInstance<TrailYamlItem.PromptsTrailItem>().single()
    assertThat(prompts.promptSteps.single().prompt).isEqualTo("Open the settings tab")
    assertThat(decoded.indexOf(decoded.first { it is TrailYamlItem.TrailheadTrailItem }))
      .isEqualTo(decoded.indexOf(prompts) - 1)
  }

  @Test
  fun healedShorthandTrailheadKeepsNullStepAcrossTheMerge() {
    // Bare-string-shorthand trailheads carry no authored step text (DEFAULT_STEP stands in at
    // runtime) — the merged item must keep `step: null`, not resurrect the sentinel, while the
    // windows' tools still concatenate.
    val trailheadStep = TrailheadDefinition(
      tools = listOf(
        TrailblazeToolYamlWrapper(
          name = "launchApp",
          trailblazeTool = LaunchAppTrailblazeTool("com.example"),
        ),
      ),
    ).toPromptStep()
    val logs = listOf(
      objectiveStart(trailheadStep),
      toolLog(LaunchAppTrailblazeTool("com.example"), "launchApp", successful = false),
      objectiveCompleteFailed(trailheadStep, "Recording failed at launchApp: app crashed"),
      objectiveStart(trailheadStep),
      toolLog(InputTextTrailblazeTool(text = "user@example.com"), "inputText"),
      objectiveComplete(trailheadStep),
    )

    val yaml = logs.generateRecordedYaml(trailblazeYaml)

    assertThat(yaml).doesNotContain(TrailheadDefinition.DEFAULT_STEP)
    val th = trailblazeYaml.decodeTrail(yaml)
      .filterIsInstance<TrailYamlItem.TrailheadTrailItem>().single().trailhead
    assertThat(th.step).isEqualTo(null)
    assertThat(th.tools!!.map { it.name }).isEqualTo(listOf("launchApp", "inputText"))
  }

  @Test
  fun healWindowWithZeroRecordedToolsLeavesTheTrailheadItemUnchanged() {
    // A heal window can capture zero recordable tools (e.g. the AI verified the device already
    // reached the trailhead state and just declared the objective complete). The merged trailhead
    // keeps the first window's tools and must not manufacture a declared-empty list.
    val trailheadStep = TrailheadDefinition(
      step = "Launch the app signed in",
      tools = listOf(
        TrailblazeToolYamlWrapper(
          name = "launchApp",
          trailblazeTool = LaunchAppTrailblazeTool("com.example"),
        ),
      ),
    ).toPromptStep()
    val logs = listOf(
      objectiveStart(trailheadStep),
      toolLog(LaunchAppTrailblazeTool("com.example"), "launchApp", successful = false),
      objectiveCompleteFailed(trailheadStep, "Recording failed at launchApp: app crashed"),
      objectiveStart(trailheadStep),
      objectiveComplete(trailheadStep),
    )

    val yaml = logs.generateRecordedYaml(trailblazeYaml)

    val th = trailblazeYaml.decodeTrail(yaml)
      .filterIsInstance<TrailYamlItem.TrailheadTrailItem>().single().trailhead
    assertThat(th.step).isEqualTo("Launch the app signed in")
    assertThat(th.tools!!.map { it.name }).isEqualTo(listOf("launchApp"))
  }

  @Test
  fun healOfAFailedAttemptThatRecordedZeroToolsAdoptsTheRecoveryTools() {
    // The mirror of the zero-tool-heal case: the recorded attempt dies before any tool completes
    // (zero recordable logs in window 1, so the trailhead item is created step-only, tools null),
    // then the recovery window supplies the tools. The merge must adopt them — not stay null.
    val trailheadStep = TrailheadDefinition(
      step = "Launch the app signed in",
      tools = listOf(
        TrailblazeToolYamlWrapper(
          name = "launchApp",
          trailblazeTool = LaunchAppTrailblazeTool("com.example"),
        ),
      ),
    ).toPromptStep()
    val logs = listOf(
      objectiveStart(trailheadStep),
      objectiveCompleteFailed(trailheadStep, "Recording failed before the first tool completed"),
      objectiveStart(trailheadStep),
      toolLog(LaunchAppTrailblazeTool("com.example"), "launchApp"),
      objectiveComplete(trailheadStep),
    )

    val yaml = logs.generateRecordedYaml(trailblazeYaml)

    val th = trailblazeYaml.decodeTrail(yaml)
      .filterIsInstance<TrailYamlItem.TrailheadTrailItem>().single().trailhead
    assertThat(th.step).isEqualTo("Launch the app signed in")
    assertThat(th.tools!!.map { it.name }).isEqualTo(listOf("launchApp"))
  }

  @Test
  fun threeTrailheadWindowsFoldIntoOneItemInExecutionOrder() {
    // Retry-shaped streams can produce more than two windows (fail, heal fails, heal again).
    // The fold must accumulate across every window, not just the first pair.
    val trailheadStep = TrailheadDefinition(
      step = "Launch the app signed in",
      tools = listOf(
        TrailblazeToolYamlWrapper(
          name = "launchApp",
          trailblazeTool = LaunchAppTrailblazeTool("com.example"),
        ),
      ),
    ).toPromptStep()
    val logs = listOf(
      objectiveStart(trailheadStep),
      toolLog(LaunchAppTrailblazeTool("com.example"), "launchApp", successful = false),
      objectiveCompleteFailed(trailheadStep, "Recording failed at launchApp: app crashed"),
      objectiveStart(trailheadStep),
      toolLog(InputTextTrailblazeTool(text = "user@example.com"), "inputText", successful = false),
      objectiveCompleteFailed(trailheadStep, "First heal attempt gave up"),
      objectiveStart(trailheadStep),
      toolLog(
        TapOnByElementSelector(
          reason = "Tap sign in",
          nodeSelector = TrailblazeNodeSelector.withMatch(DriverNodeMatch.AndroidAccessibility(textRegex = "Sign in")),
        ),
        "tapOnElementBySelector",
      ),
      objectiveComplete(trailheadStep),
    )

    val yaml = logs.generateRecordedYaml(trailblazeYaml)

    val decoded = trailblazeYaml.decodeTrail(yaml)
    val th = decoded.filterIsInstance<TrailYamlItem.TrailheadTrailItem>().single().trailhead
    assertThat(th.tools!!.map { it.name })
      .isEqualTo(listOf("launchApp", "inputText", "tapOnElementBySelector"))
  }

  @Test
  fun nestedDispatchesInsideACompositeAreDroppedFromTheRecording() {
    // A composite (scripted) tool runs its internals via `ctx.tools.*`, and each nested dispatch
    // emits its own recordable TrailblazeToolLog whose execution span lies inside the composite's.
    // Only the composite is the replayable call — a trailhead recorded as one composite sign-in tool
    // must come back out as that ONE call, not the composite plus its flattened internals.
    val trailheadStep = TrailheadDefinition(
      step = "Sign in via the trailhead",
      tools = listOf(
        TrailblazeToolYamlWrapper(
          name = "launchApp",
          trailblazeTool = LaunchAppTrailblazeTool("com.example"),
        ),
      ),
    ).toPromptStep()
    val base = now.toEpochMilliseconds()
    fun at(offsetMs: Long) = kotlinx.datetime.Instant.fromEpochMilliseconds(base + offsetMs)
    val logs = listOf(
      objectiveStart(trailheadStep),
      // Nested dispatches log as they complete — BEFORE the composite's own log lands — and their
      // spans sit inside the composite's. Emission order mirrors the real runner.
      toolLog(InputTextTrailblazeTool(text = "user@example.com"), "inputText", timestamp = at(1_000), durationMs = 500),
      toolLog(InputTextTrailblazeTool(text = "123123"), "inputText", timestamp = at(2_000), durationMs = 500),
      // The composite: starts at the window's start, spans every nested dispatch.
      toolLog(LaunchAppTrailblazeTool("com.example"), "launchApp", timestamp = at(0), durationMs = 60_000),
      objectiveComplete(trailheadStep),
    )

    val yaml = logs.generateRecordedYaml(trailblazeYaml)

    val th = trailblazeYaml.decodeTrail(yaml)
      .filterIsInstance<TrailYamlItem.TrailheadTrailItem>().single().trailhead
    assertThat(th.tools!!.single().name).isEqualTo("launchApp")
  }

  @Test
  fun sequentialSiblingToolsAreAllKeptInTheRecording() {
    // Non-overlapping siblings (a step that legitimately recorded several tools in sequence) must
    // all survive the nested-dispatch filter.
    val step = DirectionStep(step = "Type and tap")
    val base = now.toEpochMilliseconds()
    fun at(offsetMs: Long) = kotlinx.datetime.Instant.fromEpochMilliseconds(base + offsetMs)
    val logs = listOf(
      objectiveStart(step),
      toolLog(InputTextTrailblazeTool(text = "hello"), "inputText", timestamp = at(0), durationMs = 500),
      toolLog(InputTextTrailblazeTool(text = "world"), "inputText", timestamp = at(1_000), durationMs = 500),
      objectiveComplete(step),
    )

    val yaml = logs.generateRecordedYaml(trailblazeYaml)

    val recorded = trailblazeYaml.decodeTrail(yaml)
      .filterIsInstance<TrailYamlItem.PromptsTrailItem>().single()
      .promptSteps.single().recording!!.tools.map { it.name }
    assertThat(recorded).isEqualTo(listOf("inputText", "inputText"))
  }

  @Test
  fun compositeInternalsStampedNonRecordableAreDroppedRegardlessOfSpan() {
    // Regression for the recording-generation leak: regenerating a recordable
    // launch/sign-in orchestrator emitted the parent call AND its `mobile_maestro` internals. The
    // runtime now stamps nested `ctx.tools.*` dispatches `isRecordable = false`, so the generator's
    // `isRecordable` filter drops them. This models that log shape with SPAN-DEFEATING timing: the
    // nested `mobile_maestro` spans are LARGER than and CONTAIN the parent `myapp_launchAppSignedIn`
    // span, so the old span-containment heuristic would have inverted — kept the internals, dropped
    // the parent. The `isRecordable = false` stamp makes the outcome independent of span math.
    val step = DirectionStep(step = "Launch the app signed in")
    val base = now.toEpochMilliseconds()
    fun at(offsetMs: Long) = kotlinx.datetime.Instant.fromEpochMilliseconds(base + offsetMs)
    val logs = listOf(
      objectiveStart(step),
      // Recordable top-level parents (disjoint spans → both survive the sibling filter).
      toolLog(OtherTrailblazeTool("mobile_listInstalledApps"), "mobile_listInstalledApps", timestamp = at(0), durationMs = 100),
      toolLog(OtherTrailblazeTool("myapp_launchAppSignedIn"), "myapp_launchAppSignedIn", timestamp = at(200), durationMs = 2_000),
      // Nested internals: non-recordable, and deliberately given spans that CONTAIN the parent's
      // (200..2200 ⊂ 200..5200 / 300..60300) so span-containment alone would drop the wrong rows.
      toolLog(OtherTrailblazeTool("mobile_maestro"), "mobile_maestro", isRecordable = false, timestamp = at(200), durationMs = 5_000),
      toolLog(OtherTrailblazeTool("mobile_maestro"), "mobile_maestro", isRecordable = false, timestamp = at(300), durationMs = 60_000),
      objectiveComplete(step),
    )

    val yaml = logs.generateRecordedYaml(trailblazeYaml)

    val recorded = trailblazeYaml.decodeTrail(yaml)
      .filterIsInstance<TrailYamlItem.PromptsTrailItem>().single()
      .promptSteps.single().recording!!.tools.map { it.name }
    assertThat(recorded).isEqualTo(listOf("mobile_listInstalledApps", "myapp_launchAppSignedIn"))
  }

  @Test
  fun orphanedNonRecordableInternalsDoNotLeakIntoThePrecedingStep() {
    // The orphaned-log path (a tool log landing outside any objective window in the sorted log list)
    // attaches to the last prompt step's recording. Before the fix it applied NO nested filter, so
    // a composite's `mobile_maestro` internals that sorted after the objective window leaked into the
    // step. They're now stamped `isRecordable = false`, and the orphaned path already honors that —
    // proving the deterministic stamp closes the orphaned hole, not just the in-window path.
    val step = DirectionStep(step = "Launch the app signed in")
    val base = now.toEpochMilliseconds()
    fun at(offsetMs: Long) = kotlinx.datetime.Instant.fromEpochMilliseconds(base + offsetMs)
    val logs = listOf(
      objectiveStart(step),
      toolLog(OtherTrailblazeTool("myapp_launchAppSignedIn"), "myapp_launchAppSignedIn", timestamp = at(0), durationMs = 2_000),
      objectiveComplete(step),
      // Orphaned internals, after the window: non-recordable, must not attach to the step.
      toolLog(OtherTrailblazeTool("mobile_maestro"), "mobile_maestro", isRecordable = false, timestamp = at(2_100), durationMs = 500),
      toolLog(OtherTrailblazeTool("mobile_maestro"), "mobile_maestro", isRecordable = false, timestamp = at(2_700), durationMs = 500),
    )

    val yaml = logs.generateRecordedYaml(trailblazeYaml)

    val recorded = trailblazeYaml.decodeTrail(yaml)
      .filterIsInstance<TrailYamlItem.PromptsTrailItem>().single()
      .promptSteps.single().recording!!.tools.map { it.name }
    assertThat(recorded).isEqualTo(listOf("myapp_launchAppSignedIn"))
  }

  @Test
  fun recordingEmitsTheAuthoredTokenBearingFormFromTheRawPayload() {
    // The dispatch boundary resolved `{{account_email}}` before execution, so the log's
    // `trailblazeTool` is the resolved form and `rawTrailblazeTool` the authored form. The
    // recording must serialize the AUTHORED form — emitting the resolved value would bake one
    // run's memory into the saved trail (the recording-fidelity defect the split exists to fix).
    val step = DirectionStep(step = "Enter the merchant email")
    val logs = listOf(
      objectiveStart(step),
      toolLog(
        InputTextTrailblazeTool(text = "owner@example.com"),
        "inputText",
        rawTool = InputTextTrailblazeTool(text = "{{account_email}}"),
      ),
      objectiveComplete(step),
    )

    val yaml = logs.generateRecordedYaml(trailblazeYaml)

    assertThat(yaml).contains("{{account_email}}")
    assertThat(yaml).doesNotContain("owner@example.com")
    // And the emitted YAML round-trips as a valid trail.
    val recorded = trailblazeYaml.decodeTrail(yaml)
      .filterIsInstance<TrailYamlItem.PromptsTrailItem>().single()
      .promptSteps.single().recording!!.tools.single()
    assertThat((recorded.trailblazeTool as InputTextTrailblazeTool).text)
      .isEqualTo("{{account_email}}")
  }

  @Test
  fun layerDuplicateDedupGroupsOnTheAuthoredForm() {
    // One physical execution, logged by two pipeline layers: the LLM-dispatch layer logs the
    // token-bearing form (no raw split — it never interpolated), the executor layer logs
    // resolved + raw. Their args fingerprints differ on the DISPATCHED payload but match on the
    // AUTHORED payload — dedup must group on the latter, drop the `llm-…` entry, and emit the
    // executor entry's authored (token-bearing) form exactly once.
    val step = DirectionStep(step = "Enter the merchant email")
    val logs = listOf(
      objectiveStart(step),
      toolLog(
        InputTextTrailblazeTool(text = "{{account_email}}"),
        "inputText",
        isTopLevelToolCall = true,
        traceId = TraceId.generate(TraceId.Companion.TraceOrigin.LLM),
      ),
      toolLog(
        InputTextTrailblazeTool(text = "owner@example.com"),
        "inputText",
        isTopLevelToolCall = true,
        traceId = TraceId.generate(TraceId.Companion.TraceOrigin.TOOL),
        rawTool = InputTextTrailblazeTool(text = "{{account_email}}"),
      ),
      objectiveComplete(step),
    )

    val yaml = logs.generateRecordedYaml(trailblazeYaml)

    val recordedTools = trailblazeYaml.decodeTrail(yaml)
      .filterIsInstance<TrailYamlItem.PromptsTrailItem>().single()
      .promptSteps.single().recording!!.tools
    assertThat(recordedTools.map { it.name }).isEqualTo(listOf("inputText"))
    assertThat((recordedTools.single().trailblazeTool as InputTextTrailblazeTool).text)
      .isEqualTo("{{account_email}}")
  }

  // -- Unified-format preview (generateUnifiedRecordedYaml) --

  @Test
  fun unifiedPreviewRendersMapShapeWithClassifierKeyedRecording() {
    // The preview shown in reports / the desktop Recording tab must match the unified document the
    // save path writes to disk: top-level `trail:` (a map key), each step's tools under
    // `recordings.<classifier>` — NOT the legacy v1 `- prompts:` list. Asserting it decodes as a
    // unified document with the tool in this device's slot is the real contract.
    val step = DirectionStep(step = "Enter search text")
    val logs = listOf(
      objectiveStart(step),
      toolLog(InputTextTrailblazeTool(text = "hello"), "inputText"),
      objectiveComplete(step),
    )

    val yaml = logs.generateUnifiedRecordedYaml(trailblazeYaml, classifierOverride = "android-phone")

    // Structural: map-shaped top level (`trail:`) with a classifier-keyed slot, not a v1
    // `- prompts:` list. (`trailhead:` never contains the substring `trail:`.)
    assertThat(yaml).contains("trail:")
    assertThat(yaml).contains("android-phone:")
    assertThat(yaml).doesNotContain("- prompts:")
    // Behavioral: it's a valid unified doc, and this device's slot carries the recorded tool.
    val unified = trailblazeYaml.decodeUnifiedTrail(yaml)
    val recorded = unified.trail.single().recordings.getValue("android-phone").map { it.name }
    assertThat(recorded).isEqualTo(listOf("inputText"))
  }

  @Test
  fun unifiedPreviewWithBlankClassifierFallsBackToV1() {
    // No classifier means no slot to key a `recordings:` map on — a classifier-less session still
    // renders something rather than an empty/invalid unified doc. Falls back to the v1 list shape.
    val step = DirectionStep(step = "Enter search text")
    val logs = listOf(
      objectiveStart(step),
      toolLog(InputTextTrailblazeTool(text = "hello"), "inputText"),
      objectiveComplete(step),
    )

    val yaml = logs.generateUnifiedRecordedYaml(trailblazeYaml, classifierOverride = "")

    assertThat(yaml).startsWith("- prompts:")
  }

  @Test
  fun unifiedPreviewContentMatchesV1Recording() {
    // Both renderers derive from the same built items, so the unified preview must carry exactly the
    // tools the v1 recording does for this device — the preview can never drift from what's saved.
    val step = DirectionStep(step = "Tap the button")
    val logs = listOf(
      objectiveStart(step),
      toolLog(InputTextTrailblazeTool(text = "hello"), "inputText"),
      objectiveComplete(step),
    )

    val v1Tools = trailblazeYaml.decodeTrail(logs.generateRecordedYaml(trailblazeYaml))
      .filterIsInstance<TrailYamlItem.PromptsTrailItem>().single()
      .promptSteps.single().recording!!.tools.map { it.name }
    val unifiedTools = trailblazeYaml
      .decodeUnifiedTrail(logs.generateUnifiedRecordedYaml(trailblazeYaml, classifierOverride = "android-phone"))
      .trail.single().recordings.getValue("android-phone").map { it.name }

    assertThat(unifiedTools).isEqualTo(v1Tools)
  }

  @Test
  fun unifiedPreviewPreservesOtherPlatformsFromTheOriginalRunYaml() {
    // A run only re-records the device it ran on, but the unified trail it ran against can already
    // hold other platforms' recordings. The preview must keep them — seeded from the original run's
    // rawYaml — not collapse to just the classifier that ran.
    val step = DirectionStep(step = "Enter search text")

    // An existing unified trail with only the iOS slot recorded (stands in for the on-disk file the
    // run was launched against). Built through the same renderer so the fixture stays self-consistent.
    val iosUnifiedYaml = listOf(
      objectiveStart(step),
      toolLog(WaitForIdleSyncTrailblazeTool(), "waitForIdleSync"),
      objectiveComplete(step),
    ).generateUnifiedRecordedYaml(trailblazeYaml, classifierOverride = "ios-iphone-sim")

    // Now record the SAME step on Android, launched against that iOS trail (rawYaml).
    val androidLogs = listOf(
      startedLog(rawYaml = iosUnifiedYaml),
      objectiveStart(step),
      toolLog(InputTextTrailblazeTool(text = "hello"), "inputText"),
      objectiveComplete(step),
    )

    val merged = trailblazeYaml.decodeUnifiedTrail(
      androidLogs.generateUnifiedRecordedYaml(trailblazeYaml, classifierOverride = "android-phone"),
    )

    // Both slots survive on the single shared step: iOS preserved, Android freshly recorded.
    val recordings = merged.trail.single().recordings
    assertThat(recordings.getValue("ios-iphone-sim").map { it.name }).isEqualTo(listOf("waitForIdleSync"))
    assertThat(recordings.getValue("android-phone").map { it.name }).isEqualTo(listOf("inputText"))
  }

  @Test
  fun rerecordingOnePlatformOfAMultiPlatformTrailUpdatesOnlyThatSlot() {
    // The self-heal / re-record case: the trail ALREADY covers multiple platforms, and a run
    // re-records exactly one of them. The other platforms must survive unchanged, and the
    // re-recorded platform must be REPLACED (not duplicated/appended) in place. The multi-platform
    // starting trail is assembled purely through the function under test — each stage merges its
    // slot in by seeding from the prior stage's rawYaml.
    val step = DirectionStep(step = "Enter search text")
    fun slot(classifier: String, priorRawYaml: String?, tool: TrailblazeTool, name: String): String =
      buildList {
        priorRawYaml?.let { add(startedLog(rawYaml = it)) }
        add(objectiveStart(step))
        add(toolLog(tool, name))
        add(objectiveComplete(step))
      }.generateUnifiedRecordedYaml(trailblazeYaml, classifierOverride = classifier)

    val ios = slot("ios-iphone-sim", null, WaitForIdleSyncTrailblazeTool(), "waitForIdleSync")
    val iosPlusWeb = slot("web", ios, SwipeTrailblazeTool(), "swipe")
    // A STALE android recording that the re-run will refresh.
    val startingTrail = slot("android-phone", iosPlusWeb, WaitForIdleSyncTrailblazeTool(), "waitForIdleSync")

    // Re-record ONLY Android with a different tool, launched against that three-platform trail.
    val androidRerun = listOf(
      startedLog(rawYaml = startingTrail),
      objectiveStart(step),
      toolLog(InputTextTrailblazeTool(text = "hello"), "inputText"),
      objectiveComplete(step),
    )
    val merged = trailblazeYaml.decodeUnifiedTrail(
      androidRerun.generateUnifiedRecordedYaml(trailblazeYaml, classifierOverride = "android-phone"),
    )

    val recordings = merged.trail.single().recordings
    // Untouched platforms survive unchanged.
    assertThat(recordings.getValue("ios-iphone-sim").map { it.name }).isEqualTo(listOf("waitForIdleSync"))
    assertThat(recordings.getValue("web").map { it.name }).isEqualTo(listOf("swipe"))
    // The re-recorded platform is REPLACED in place — the stale recording is gone, not appended to.
    assertThat(recordings.getValue("android-phone").map { it.name }).isEqualTo(listOf("inputText"))
  }

  @Test
  fun unifiedPreviewDerivesClassifierFromTheStartedLogWhenNoOverride() {
    // Production callers pass no override — the slot key comes from the session's device
    // classifiers. Prove that derivation path renders the tools under the joined-classifier slot.
    val step = DirectionStep(step = "Enter search text")
    val logs = listOf(
      startedLog(classifiers = listOf("android", "phone")),
      objectiveStart(step),
      toolLog(InputTextTrailblazeTool(text = "hello"), "inputText"),
      objectiveComplete(step),
    )

    val unified = trailblazeYaml.decodeUnifiedTrail(logs.generateUnifiedRecordedYaml(trailblazeYaml))

    assertThat(unified.trail.single().recordings.getValue("android-phone").map { it.name })
      .isEqualTo(listOf("inputText"))
  }

  @Test
  fun unifiedPreviewWithLegacyV1RawYamlRendersThisClassifierAlone() {
    // A run launched from a legacy v1 trail has nothing unified to preserve — the preview shows just
    // this device's slot (same as a first unified write), not an error.
    val step = DirectionStep(step = "Enter search text")
    val v1RawYaml = listOf(
      objectiveStart(step),
      toolLog(WaitForIdleSyncTrailblazeTool(), "waitForIdleSync"),
      objectiveComplete(step),
    ).generateRecordedYaml(trailblazeYaml) // v1 list shape
    val logs = listOf(
      startedLog(rawYaml = v1RawYaml),
      objectiveStart(step),
      toolLog(InputTextTrailblazeTool(text = "hello"), "inputText"),
      objectiveComplete(step),
    )

    val unified = trailblazeYaml.decodeUnifiedTrail(
      logs.generateUnifiedRecordedYaml(trailblazeYaml, classifierOverride = "android-phone"),
    )

    val recordings = unified.trail.single().recordings
    assertThat(recordings.keys).isEqualTo(setOf("android-phone"))
    assertThat(recordings.getValue("android-phone").map { it.name }).isEqualTo(listOf("inputText"))
  }

  @Test
  fun unifiedPreviewWithUndecodableRawYamlDegradesToThisClassifierWithoutThrowing() {
    // A garbage rawYaml must not crash the preview — it degrades to this classifier's slot alone.
    val step = DirectionStep(step = "Enter search text")
    val logs = listOf(
      startedLog(rawYaml = "this: is: not: valid: trail: yaml: [[["),
      objectiveStart(step),
      toolLog(InputTextTrailblazeTool(text = "hello"), "inputText"),
      objectiveComplete(step),
    )

    val yaml = logs.generateUnifiedRecordedYaml(trailblazeYaml, classifierOverride = "android-phone")

    // Non-empty and decodes to just this classifier (the catch degraded the seed to empty).
    val unified = trailblazeYaml.decodeUnifiedTrail(yaml)
    assertThat(unified.trail.single().recordings.keys).isEqualTo(setOf("android-phone"))
  }

  @Test
  fun unifiedPreviewWithNonBlankClassifierButNoRecordableStepsFallsBackToV1() {
    // A non-blank classifier whose recording lowers to zero steps can't produce a parseable unified
    // `trail:` — it falls back to the v1 encoding rather than emitting an empty unified doc.
    val logs = listOf(
      toolLog(WaitForIdleSyncTrailblazeTool(), "waitForIdleSync", isRecordable = false),
    )

    val yaml = logs.generateUnifiedRecordedYaml(trailblazeYaml, classifierOverride = "android-phone")

    // No steps → not the unified map shape. (A bare non-recordable tool yields no prompts/tools.)
    assertThat(yaml).doesNotContain("android-phone:")
  }

  @Test
  fun unifiedPreviewWithMultiToolTrailheadFallsBackToV1WithoutReturningBlank() {
    // A self-healed/retried trailhead can record more than one tool for a platform. The unified
    // emitter rejects that (a trailhead is at most one tool per classifier), so the preview must
    // degrade to the v1 recording rather than returning "" (empty Recording tab, empty Copy).
    val trailhead = DirectionStep(step = "Sign in", isTrailhead = true)
    val logs = listOf(
      objectiveStart(trailhead),
      toolLog(InputTextTrailblazeTool(text = "user"), "inputText"),
      toolLog(WaitForIdleSyncTrailblazeTool(), "waitForIdleSync"),
      objectiveComplete(trailhead),
    )

    val yaml = logs.generateUnifiedRecordedYaml(trailblazeYaml, classifierOverride = "android-phone")

    // Non-blank v1 fallback — the two trailhead tools survive in the legacy list shape.
    assertThat(yaml).isNotEmpty()
    assertThat(yaml).contains("- trailhead:")
    assertThat(yaml).contains("inputText")
    assertThat(yaml).contains("waitForIdleSync")
    // Not the unified map shape (no per-classifier slot key).
    assertThat(yaml).doesNotContain("android-phone:")
  }
}
