package xyz.block.trailblaze.trailrunner

import kotlinx.coroutines.runBlocking
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The generation slice of demonstrate-first Create: turning a finished demonstration into an
 * external-agent run that authors and self-verifies a trail. These tests assert the observable
 * contract - which generate requests are rejected, that the launching preamble carries the facts
 * the skill needs, when the daemon auto-continues a stalled generation run, and that an
 * unverified `ready` is downgraded so an overclaiming agent cannot mark work ready. They exercise
 * the pure decision functions directly and the emit-time policy through a seeded run - never
 * internal call counts or exact human-readable wording.
 */
class DemoGenerateTest {
  private val seededIds = mutableListOf<String>()
  private val tempDirs = mutableListOf<File>()

  private val device = TrailblazeDeviceId("emulator-5554", TrailblazeDevicePlatform.ANDROID)

  @AfterTest
  fun cleanup() {
    seededIds.forEach { ExternalAgentSupervisor.runs.remove(it) }
    seededIds.clear()
    tempDirs.forEach { runCatching { it.deleteRecursively() } }
    tempDirs.clear()
  }

  private fun tempRoot(): File = Files.createTempDirectory("demo-generate").toFile().also { tempDirs += it }

  private fun startDemo(): ExternalAgentRunDto {
    val root = tempRoot()
    return ExternalAgentSupervisor.startDemo(
      target = "myapp",
      platform = "android",
      deviceId = device,
      title = null,
      fallbackCwd = root,
      artifactsRoot = root,
    ).getOrThrow().also { seededIds += it.id }
  }

  private fun seedRun(status: ExternalAgentSessionStatus): MutableExternalAgentRun {
    val id = "seed-" + System.nanoTime()
    val run = MutableExternalAgentRun(
      id = id,
      request = ExternalAgentRunRequest(agentType = ExternalAgentType.CLAUDE, prompt = "x"),
      title = "seed",
      prompt = "x",
      cwd = tempRoot(),
    ).also { it.status = status }
    ExternalAgentSupervisor.runs[id] = run
    seededIds += id
    return run
  }

  // ─── Generate endpoint validation ──────────────────────────────────────────

  @Test
  fun generateIsRejectedBeforeTheDemonstrationIsFinished() = runBlocking {
    val demo = startDemo()
    // Still positioning: nothing has been demonstrated to generate from.
    val positioning = ExternalAgentSupervisor.generateFromDemo(
      demoRunId = demo.id,
      agentType = ExternalAgentType.CLAUDE,
      model = null,
      sandbox = null,
      fallbackCwd = tempRoot(),
      artifactsRoot = tempRoot(),
    )
    assertTrue(positioning.isFailure)

    ExternalAgentSupervisor.markDemoStart(demo.id, trailhead = null).getOrThrow()
    // Recording but not finished: still rejected.
    val recording = ExternalAgentSupervisor.generateFromDemo(
      demoRunId = demo.id,
      agentType = ExternalAgentType.CLAUDE,
      model = null,
      sandbox = null,
      fallbackCwd = tempRoot(),
      artifactsRoot = tempRoot(),
    )
    assertTrue(recording.isFailure)
  }

  @Test
  fun generateIsRejectedWhileAGenerationRunIsAlreadyRunning() = runBlocking {
    val demo = startDemo()
    ExternalAgentSupervisor.markDemoStart(demo.id, trailhead = null).getOrThrow()
    ExternalAgentSupervisor.finishDemo(demo.id, objective = "Widget shows in cart", notes = null).getOrThrow()

    // A prior generation run for this demonstration is still running.
    val running = seedRun(ExternalAgentSessionStatus.RUNNING)
    ExternalAgentSupervisor.runs[demo.id]?.demo?.generationRunId = running.id

    val second = ExternalAgentSupervisor.generateFromDemo(
      demoRunId = demo.id,
      agentType = ExternalAgentType.CLAUDE,
      model = null,
      sandbox = null,
      fallbackCwd = tempRoot(),
      artifactsRoot = tempRoot(),
    )
    assertTrue(second.isFailure)
  }

  @Test
  fun generateIsRejectedForARunThatIsNotADemonstration() = runBlocking {
    val plain = seedRun(ExternalAgentSessionStatus.COMPLETED)
    val result = ExternalAgentSupervisor.generateFromDemo(
      demoRunId = plain.id,
      agentType = ExternalAgentType.CLAUDE,
      model = null,
      sandbox = null,
      fallbackCwd = tempRoot(),
      artifactsRoot = tempRoot(),
    )
    assertTrue(result.isFailure)
  }

  // ─── Launching preamble ──────────────────────────────────────────────────────

  @Test
  fun generationPreambleCarriesTheBundleDirObjectiveAndFacts() {
    val demoState = DemoRunState(deviceId = device, target = "myapp", platform = "android").apply {
      objective = "Widget appears in cart after tapping Add"
      notes = "flaky on slow net"
    }
    val bundleDir = File("/tmp/tape/agent-runs/demo-1/tape")
    val trailsRoot = File("/work/trails")

    val preamble = generationPreamble(demoState, bundleDir, trailsRoot)

    // Points at the skill and hands it the bundle dir + the human's facts.
    assertTrue(preamble.contains("trailblaze-author"))
    assertTrue(preamble.contains(bundleDir.absolutePath))
    assertTrue(preamble.contains("Widget appears in cart after tapping Add"))
    assertTrue(preamble.contains("flaky on slow net"))
    assertTrue(preamble.contains("myapp"))
    assertTrue(preamble.contains("android"))
    assertTrue(preamble.contains("emulator-5554"))
    // Destination guidance under the trails root, and the autonomy contract.
    assertTrue(preamble.contains(trailsRoot.absolutePath))
    assertTrue(preamble.contains("autonomous"))
    assertTrue(preamble.contains("action=RUN"))
  }

  @Test
  fun firstPlatformPreambleNamesThePlatformKey() {
    val demoState = DemoRunState(
      deviceId = TrailblazeDeviceId("iPhone-15", TrailblazeDevicePlatform.IOS),
      target = "myapp",
      platform = "ios",
    ).apply { objective = "Buy a widget" }

    val preamble = generationPreamble(demoState, File("/tmp/tape"), File("/work/trails"))

    // First-platform mode: names the platform key and authors from scratch (no merge wording).
    assertTrue(preamble.contains("iphone"))
    assertFalse(preamble.contains("Adding a platform to an existing trail"))
  }

  @Test
  fun mergePreambleReferencesTheExistingTrailAndForbidsRestructuring() {
    val demoState = DemoRunState(
      deviceId = TrailblazeDeviceId("iPad-Pro-sim", TrailblazeDevicePlatform.IOS),
      target = "myapp",
      platform = "ios",
    ).apply {
      objective = "Widget appears in cart"
      // A prior platform already delivered the trail: this generation runs in merge mode.
      trailId = "0/myapp/widget"
      trailFiles = "trail.yaml,android.trail.yaml"
    }

    val preamble = generationPreamble(demoState, File("/tmp/tape"), File("/work/trails"))

    assertTrue(preamble.contains("trail.yaml,android.trail.yaml"))
    assertTrue(preamble.contains("0/myapp/widget"))
    // The platform key this demonstration covers.
    assertTrue(preamble.contains("ipad"))
    // The no-restructure instruction and the verify-on-this-device requirement.
    assertTrue(preamble.contains("restructure"))
    assertTrue(preamble.contains("action=RUN"))
  }

  @Test
  fun trailOutputObservationRecordsTheDeliveredTrailOnTheDemoState() = runBlocking {
    val demo = startDemo()
    ExternalAgentSupervisor.markDemoStart(demo.id, trailhead = null).getOrThrow()
    ExternalAgentSupervisor.finishDemo(demo.id, objective = "Widget shows in cart", notes = null).getOrThrow()

    val gen = seedRun(ExternalAgentSessionStatus.RUNNING).also { it.generation = GenerationRunState(demo.id) }
    // A passing trail-run result is observed first, then the delivered trail_output.
    ExternalAgentSupervisor.applyGenerationPolicy(
      gen,
      ExternalAgentEventDraft(kind = ExternalAgentEventKind.TOOL_RESULT, text = passingRunJson),
    )
    ExternalAgentSupervisor.applyGenerationPolicy(
      gen,
      ExternalAgentEventDraft(
        kind = ExternalAgentEventKind.UI_COMMAND,
        uiCommand = TrailRunnerUiCommandDto(
          action = "trail_output",
          trailId = "0/myapp/widget",
          params = mapOf("status" to "ready", "files" to "trail.yaml"),
        ),
      ),
    )

    val demoState = assertNotNull(ExternalAgentSupervisor.run(demo.id)?.demo)
    assertEquals("0/myapp/widget", demoState.trailId)
    assertEquals("trail.yaml", demoState.trailFiles)
    assertEquals(true, demoState.trailVerified)
  }

  @Test
  fun generationRunDtoCarriesTheDemoRunId() {
    val gen = seedRun(ExternalAgentSessionStatus.RUNNING).also { it.generation = GenerationRunState("demo-xyz") }
    assertEquals("demo-xyz", ExternalAgentSupervisor.run(gen.id)?.demoRunId)
  }

  @Test
  fun workspaceRootForGenerationClimbsAboveAConfiguredTrailsDir() {
    val workspace = tempRoot()
    val trails = File(workspace, "trails").apply { mkdirs() }
    File(trails, "config").mkdirs()
    File(trails, "config/trailblaze.yaml").writeText("version: 1\n")

    // A configured trails dir (carries config/trailblaze.yaml) resolves to the workspace above it,
    // where ./trailblaze and .claude/skills live.
    assertEquals(workspace.canonicalFile, workspaceRootForGeneration(trails).canonicalFile)

    // An unconfigured dir is used as-is (nothing to climb to).
    val loose = tempRoot()
    assertEquals(loose, workspaceRootForGeneration(loose))
  }

  @Test
  fun suggestedTrailDirKebabsTheObjectiveUnderTheTargetArea() {
    val demoState = DemoRunState(deviceId = device, target = "myapp", platform = "android").apply {
      objective = "Widget appears in cart!"
    }
    assertEquals("myapp/widget-appears-in-cart/", suggestedTrailDir(demoState))

    // No objective falls back to a stable slug; no target falls back to the platform area.
    val bare = DemoRunState(deviceId = device, target = null, platform = "android")
    assertEquals("android/trail/", suggestedTrailDir(bare))
  }

  // ─── Auto-continue firing rules ──────────────────────────────────────────────

  @Test
  fun shouldAutoContinueFiresWhenNoTrailWasDeliveredAndBudgetRemains() {
    assertTrue(
      shouldAutoContinue(
        GenerationContinueInputs(
          cancelled = false,
          humanReplied = false,
          terminalTrailOutputSeen = false,
          autoTurnsUsed = 0,
        ),
      ),
    )
  }

  @Test
  fun shouldAutoContinueDoesNotFireAfterATerminalTrailOutput() {
    assertFalse(
      shouldAutoContinue(
        GenerationContinueInputs(
          cancelled = false,
          humanReplied = false,
          terminalTrailOutputSeen = true,
          autoTurnsUsed = 0,
        ),
      ),
    )
  }

  @Test
  fun shouldAutoContinueDoesNotFireAfterAHumanReplyOrCancel() {
    assertFalse(
      shouldAutoContinue(
        GenerationContinueInputs(cancelled = false, humanReplied = true, terminalTrailOutputSeen = false, autoTurnsUsed = 0),
      ),
    )
    assertFalse(
      shouldAutoContinue(
        GenerationContinueInputs(cancelled = true, humanReplied = false, terminalTrailOutputSeen = false, autoTurnsUsed = 0),
      ),
    )
  }

  @Test
  fun shouldAutoContinueDoesNotFireOnceTheBudgetIsSpent() {
    assertFalse(
      shouldAutoContinue(
        GenerationContinueInputs(
          cancelled = false,
          humanReplied = false,
          terminalTrailOutputSeen = false,
          autoTurnsUsed = DEMO_GENERATE_MAX_TURNS,
        ),
      ),
    )
  }

  @Test
  fun hasTerminalTrailOutputRecognizesReadyAndDraftOnly() {
    fun event(status: String?) = ExternalAgentEventDto(
      id = "e",
      runId = "r",
      seq = 0,
      timeMs = 0L,
      agentType = ExternalAgentType.CLAUDE,
      kind = ExternalAgentEventKind.UI_COMMAND,
      uiCommand = TrailRunnerUiCommandDto(
        action = "trail_output",
        params = status?.let { mapOf("status" to it) } ?: emptyMap(),
      ),
    )
    assertTrue(hasTerminalTrailOutput(listOf(event("ready"))))
    assertTrue(hasTerminalTrailOutput(listOf(event("draft"))))
    // A trail_output with no terminal status (still working) is not terminal.
    assertFalse(hasTerminalTrailOutput(listOf(event(null))))
    assertFalse(hasTerminalTrailOutput(emptyList()))
  }

  // ─── Ready-downgrade / verified check ─────────────────────────────────────────

  private val passingRunJson =
    """{"success":true,"file":"trails/myapp/widget/android.trail.yaml","steps":5,"duration":1234,"message":"Trail completed successfully: 5 steps in 1234ms"}"""
  private val failingRunJson =
    """{"success":false,"file":"trails/myapp/widget/android.trail.yaml","steps":2,"failedAt":3,"failureReason":"selector not found","message":"Trail failed at step 3: selector not found"}"""

  @Test
  fun classifyTrailRunResultOnlyTrustsAPassingRunResult() {
    assertTrue(classifyTrailRunResult(toolName = null, resultJson = passingRunJson))
    // A failing run is not a pass.
    assertFalse(classifyTrailRunResult(toolName = null, resultJson = failingRunJson))
    // A success:true that is NOT a run result (e.g. an edit result) must not read as a passing run.
    assertFalse(classifyTrailRunResult(toolName = null, resultJson = """{"success":true,"totalSteps":5,"recordedSteps":5}"""))
    // Conservative on anything absent or unparseable.
    assertFalse(classifyTrailRunResult(toolName = null, resultJson = null))
    assertFalse(classifyTrailRunResult(toolName = null, resultJson = "not json"))
    assertFalse(classifyTrailRunResult(toolName = null, resultJson = """{"saved":true}"""))
  }

  @Test
  fun rewriteDowngradesUnverifiedReadyAndAnnotatesVerified() {
    val ready = TrailRunnerUiCommandDto(action = "trail_output", trailId = "0/myapp/widget", params = mapOf("status" to "ready", "files" to "android.trail.yaml"))

    val unverified = rewriteTrailOutputStatus(ready, verified = false)
    assertEquals("draft", unverified.params["status"])
    assertEquals("false", unverified.params["verified"])
    assertEquals("android.trail.yaml", unverified.params["files"])

    val verified = rewriteTrailOutputStatus(ready, verified = true)
    assertEquals("ready", verified.params["status"])
    assertEquals("true", verified.params["verified"])

    // A non-trail_output command is untouched.
    val nav = TrailRunnerUiCommandDto(action = "navigate", route = "active")
    assertEquals(nav, rewriteTrailOutputStatus(nav, verified = true))
  }

  @Test
  fun policyDowngradesReadyWhenNoPassingRunWasObserved() {
    val run = seedRun(ExternalAgentSessionStatus.RUNNING).also { it.generation = GenerationRunState("demo") }
    val ready = ExternalAgentEventDraft(
      kind = ExternalAgentEventKind.UI_COMMAND,
      uiCommand = TrailRunnerUiCommandDto(action = "trail_output", params = mapOf("status" to "ready")),
    )

    val adjusted = ExternalAgentSupervisor.applyGenerationPolicy(run, ready)

    assertEquals("draft", adjusted.uiCommand?.params?.get("status"))
    assertEquals("false", adjusted.uiCommand?.params?.get("verified"))
  }

  @Test
  fun policyKeepsReadyAfterAPassingTrailRunResult() {
    val run = seedRun(ExternalAgentSessionStatus.RUNNING).also { it.generation = GenerationRunState("demo") }

    // A passing trail-run TOOL_RESULT is observed first: the server records the pass itself.
    val toolResult = ExternalAgentEventDraft(kind = ExternalAgentEventKind.TOOL_RESULT, text = passingRunJson)
    ExternalAgentSupervisor.applyGenerationPolicy(run, toolResult)

    val ready = ExternalAgentEventDraft(
      kind = ExternalAgentEventKind.UI_COMMAND,
      uiCommand = TrailRunnerUiCommandDto(action = "trail_output", params = mapOf("status" to "ready")),
    )
    val adjusted = ExternalAgentSupervisor.applyGenerationPolicy(run, ready)

    assertEquals("ready", adjusted.uiCommand?.params?.get("status"))
    assertEquals("true", adjusted.uiCommand?.params?.get("verified"))
  }

  @Test
  fun policyLeavesNonGenerationRunsUntouched() {
    val run = seedRun(ExternalAgentSessionStatus.RUNNING) // no generation state
    val ready = ExternalAgentEventDraft(
      kind = ExternalAgentEventKind.UI_COMMAND,
      uiCommand = TrailRunnerUiCommandDto(action = "trail_output", params = mapOf("status" to "ready")),
    )

    val adjusted = ExternalAgentSupervisor.applyGenerationPolicy(run, ready)

    // An ordinary (non-generation) run is not policed: status stays ready and no verified is added.
    assertEquals("ready", adjusted.uiCommand?.params?.get("status"))
    assertNull(adjusted.uiCommand?.params?.get("verified"))
  }
}
