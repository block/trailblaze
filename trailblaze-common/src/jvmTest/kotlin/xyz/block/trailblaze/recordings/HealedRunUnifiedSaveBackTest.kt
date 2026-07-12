package xyz.block.trailblaze.recordings

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.block.trailblaze.agent.model.AgentTaskStatus
import xyz.block.trailblaze.agent.model.AgentTaskStatusData
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.client.temp.OtherTrailblazeTool
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.TaskId
import xyz.block.trailblaze.yaml.DirectionStep
import xyz.block.trailblaze.yaml.PromptStep
import xyz.block.trailblaze.yaml.ToolRecording
import xyz.block.trailblaze.yaml.TrailConfig
import xyz.block.trailblaze.yaml.TrailYamlItem
import xyz.block.trailblaze.yaml.TrailblazeToolYamlWrapper
import xyz.block.trailblaze.yaml.TrailheadDefinition
import xyz.block.trailblaze.yaml.createTrailblazeYaml
import xyz.block.trailblaze.yaml.generateRecordedYaml

/**
 * Simulated end-to-end save-back for a `--self-heal` run of a unified trail. The session's log
 * stream is the fixture — two trailhead-marked objective windows, exactly what a heal produces
 * (the failed recorded attempt closes its window, then AI recovery opens its own start/complete
 * pair for the same step-0 prompt) — and it drives the REAL production chain, device execution
 * being the only thing simulated:
 *
 *   logs → [generateRecordedYaml] → decodeTrail (strict) → [UnifiedRecordingWriter.mergeIntoUnified]
 *
 * That is the chain `TrailCommand.saveRecordingAsUnified` runs after a unified-trail run. Before
 * the healed-trailhead merge fix in [generateRecordedYaml], the chain died at the strict decode
 * ("Only one trailhead item is allowed in a trail."), so a healed run could never save back to a
 * unified trail and left an unparseable `recording.trail.yaml` behind.
 */
class HealedRunUnifiedSaveBackTest {

  @get:Rule val tempFolder = TemporaryFolder()

  private val yaml = createTrailblazeYaml()
  private val session = SessionId("healed-run")
  private val now = Clock.System.now()

  @Test
  fun `healed multi-tool trailhead reaches the designed unsupported outcome with the unified trail untouched`() {
    // A unified trail already on disk with this classifier's trailhead + step slots recorded.
    val dir = tempFolder.newFolder()
    val seeded = UnifiedRecordingWriter.mergeIntoUnified(dir, seedItems(), "android")
    assertTrue(seeded is UnifiedRecordingWriter.MergeOutcome.Merged, "seed merge must succeed")
    val unifiedFile = File(dir, TrailRecordings.UNIFIED_TRAIL_FILENAME)
    val before = unifiedFile.readText()

    // The heal recovered with two tool calls, so the recorded trailhead arrives as
    // failed attempt (1 tool) + recovery (2 tools) = 3 tools.
    val logs = healedRunLogs(recoveryToolNames = listOf("tapSignIn", "enterEmail"))
    val recordingYaml = logs.generateRecordedYaml(yaml)
    // The strict parse the CLI save-back performs — this line threw before the merge fix.
    val recordedItems = yaml.decodeTrail(recordingYaml)

    val outcome = UnifiedRecordingWriter.mergeIntoUnified(dir, recordedItems, "android")

    // The unified trailhead is one tool per classifier, so a healed multi-tool trailhead is
    // deliberately NOT auto-adopted — and the refusal is all-or-nothing, before any file I/O.
    assertEquals(UnifiedRecordingWriter.MergeOutcome.MultiToolTrailheadUnsupported(3), outcome)
    assertEquals(before, unifiedFile.readText(), "the on-disk unified trail must be byte-identical")
  }

  @Test
  fun `healed run whose recovery captured zero tools merges back into the unified trail`() {
    // The AI can heal without any recordable tool call (it verified the trailhead state was
    // already reached and declared the objective complete). The merged trailhead then keeps only
    // the recorded attempt's single tool, which the unified trailhead slot CAN represent.
    val dir = tempFolder.newFolder()
    val logs = healedRunLogs(recoveryToolNames = emptyList())

    val recordedItems = yaml.decodeTrail(logs.generateRecordedYaml(yaml))
    val outcome = UnifiedRecordingWriter.mergeIntoUnified(dir, recordedItems, "android")

    assertTrue(outcome is UnifiedRecordingWriter.MergeOutcome.Merged)
    val unified = yaml.decodeUnifiedTrail(File(dir, TrailRecordings.UNIFIED_TRAIL_FILENAME).readText())
    assertEquals(
      listOf("myapp_launchSignedIn"),
      unified.trailhead?.recordings?.get("android")?.map { it.name },
      "the trailhead slot carries the recorded attempt's tool",
    )
    assertEquals(
      listOf("tapCart"),
      unified.trail.single().recordings["android"]?.map { it.name },
      "the ordinary step's recording merges alongside the healed trailhead",
    )
  }

  // --- fixtures ---

  /**
   * The log stream of one healed run: the trailhead's recorded attempt fails (window 1, closed
   * with a failed complete), AI recovery re-opens the same trailhead prompt step and succeeds
   * (window 2, with [recoveryToolNames] recordable calls), then the rest of the trail proceeds
   * normally (one recorded step).
   */
  private fun healedRunLogs(recoveryToolNames: List<String>): List<TrailblazeLog> {
    val trailheadStep = TrailheadDefinition(
      step = "Launch the app signed in",
      tools = listOf(wrapper("myapp_launchSignedIn")),
    ).toPromptStep()
    val cartStep = DirectionStep(step = "Open the cart")
    return buildList {
      add(start(trailheadStep))
      add(toolLog("myapp_launchSignedIn", successful = false))
      add(completeFailed(trailheadStep))
      add(start(trailheadStep))
      recoveryToolNames.forEach { add(toolLog(it)) }
      add(completeOk(trailheadStep))
      add(start(cartStep))
      add(toolLog("tapCart"))
      add(completeOk(cartStep))
    }
  }

  /** The v1 items of a healthy earlier recording, used to seed the on-disk unified trail. */
  private fun seedItems(): List<TrailYamlItem> = listOf(
    TrailYamlItem.ConfigTrailItem(TrailConfig(id = "app/checkout", target = "app", driver = "D")),
    TrailYamlItem.TrailheadTrailItem(
      TrailheadDefinition(step = "Launch the app signed in", tools = listOf(wrapper("myapp_launchSignedIn"))),
    ),
    TrailYamlItem.PromptsTrailItem(
      listOf(DirectionStep(step = "Open the cart", recording = ToolRecording(tools = listOf(wrapper("tapCart"))))),
    ),
  )

  private fun start(step: PromptStep) = TrailblazeLog.ObjectiveStartLog(
    promptStep = step,
    session = session,
    timestamp = now,
  )

  private fun completeOk(step: PromptStep) = TrailblazeLog.ObjectiveCompleteLog(
    promptStep = step,
    objectiveResult = AgentTaskStatus.Success.ObjectiveComplete(
      llmExplanation = "Done",
      statusData = statusData(step),
    ),
    session = session,
    timestamp = now,
  )

  private fun completeFailed(step: PromptStep) = TrailblazeLog.ObjectiveCompleteLog(
    promptStep = step,
    objectiveResult = AgentTaskStatus.Failure.ObjectiveFailed(
      llmExplanation = "Recording failed at myapp_launchSignedIn: app crashed",
      statusData = statusData(step),
    ),
    session = session,
    timestamp = now,
  )

  private fun statusData(step: PromptStep) = AgentTaskStatusData(
    taskId = TaskId.generate(),
    prompt = step.prompt,
    callCount = 1,
    taskStartTime = now,
    totalDurationMs = 100,
  )

  private fun toolLog(name: String, successful: Boolean = true) = TrailblazeLog.TrailblazeToolLog(
    trailblazeTool = OtherTrailblazeTool(toolName = name, raw = JsonObject(mapOf("marker" to JsonPrimitive(name)))),
    toolName = name,
    successful = successful,
    traceId = null,
    durationMs = 100,
    session = session,
    timestamp = now,
    isRecordable = true,
  )

  private fun wrapper(name: String) = TrailblazeToolYamlWrapper(
    name = name,
    trailblazeTool = OtherTrailblazeTool(toolName = name, raw = JsonObject(mapOf("marker" to JsonPrimitive(name)))),
  )
}
