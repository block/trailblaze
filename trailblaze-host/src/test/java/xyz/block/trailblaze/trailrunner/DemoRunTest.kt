package xyz.block.trailblaze.trailrunner

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
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
import xyz.block.trailblaze.host.networkcapture.AndroidNetworkCaptureActivator
import xyz.block.trailblaze.host.networkcapture.AndroidNetworkCaptureRegistry

/**
 * The demonstrate-first Create server slice: a demo run is an agent-less run in demo mode that
 * carries a phase (positioning -> recording -> done) and a durable on-disk bundle in its tape dir.
 * These tests assert the observable contract - the phase a caller sees, what the bundle files
 * contain, which actions get an actions.ndjson line and with what phase, and which runs the
 * eviction pass protects - never internal call counts or exact human-readable strings.
 */
class DemoRunTest {
  private val seededIds = mutableListOf<String>()
  private val tempDirs = mutableListOf<File>()

  private val device = TrailblazeDeviceId("emulator-5554", TrailblazeDevicePlatform.ANDROID)

  @AfterTest
  fun cleanup() {
    AndroidNetworkCaptureRegistry.activator = null
    seededIds.forEach { ExternalAgentSupervisor.runs.remove(it) }
    seededIds.clear()
    tempDirs.forEach { runCatching { it.deleteRecursively() } }
    tempDirs.clear()
  }

  @Test
  fun markStartThreadsTheResolvedAppIdentityIntoDemoCapture() = runBlocking {
    val run = startDemo()
    var capturedAppId: String? = null
    AndroidNetworkCaptureRegistry.activator =
      object : AndroidNetworkCaptureActivator {
        override fun start(
          sessionId: String,
          sessionDir: File,
          deviceId: TrailblazeDeviceId,
          targetAppId: String?,
        ) {
          capturedAppId = targetAppId
        }

        override fun stop(sessionId: String) = Unit
      }

    ExternalAgentSupervisor.markDemoStart(
        runId = run.id,
        trailhead = null,
        targetAppId = "com.example.myapp",
      )
      .getOrThrow()

    assertEquals("com.example.myapp", capturedAppId)
    val demo = assertNotNull(assertNotNull(ExternalAgentSupervisor.runs[run.id]).demo)
    assertTrue(demo.captureStarted)
  }

  private fun tempRoot(): File = Files.createTempDirectory("demo-run").toFile().also { tempDirs += it }

  private fun startDemo(
    target: String? = "myapp",
    platform: String? = "android",
    title: String? = null,
  ): ExternalAgentRunDto {
    val root = tempRoot()
    return ExternalAgentSupervisor.startDemo(
      target = target,
      platform = platform,
      deviceId = device,
      title = title,
      fallbackCwd = root,
      artifactsRoot = root,
    ).getOrThrow().also { seededIds += it.id }
  }

  @Test
  fun startDemoBirthsAPositioningRunWithADemoStateAndBundleDir() {
    val run = startDemo(title = "Buy a widget")

    assertEquals(ExternalAgentType.SOLO, run.agentType)
    assertEquals(ExternalAgentSessionStatus.COMPLETED, run.status)
    val demo = assertNotNull(run.demo)
    assertEquals("positioning", demo.phase)
    assertNotNull(demo.bundleDir)
    assertNull(demo.objective)
    assertNull(demo.generationRunId)
  }

  @Test
  fun markStartMovesToRecordingAndWritesTheManifest() = runBlocking {
    val run = startDemo()
    val trailhead = DemoTrailheadDto(
      name = "myapp_android_signedInFresh",
      args = mapOf("account" to "primary"),
      yaml = "- trailhead: myapp_android_signedInFresh",
    )

    val phase = ExternalAgentSupervisor.markDemoStart(run.id, trailhead).getOrThrow()
    assertEquals("recording", phase)
    assertEquals("recording", ExternalAgentSupervisor.run(run.id)?.demo?.phase)

    val manifest = assertNotNull(ExternalAgentSupervisor.readDemoManifest(run.id))
    assertEquals(1, manifest.version)
    assertEquals("myapp", manifest.target)
    assertEquals("android", manifest.platform)
    assertEquals(device, manifest.deviceId)
    assertTrue(manifest.classifiers.contains("android"))
    val th = assertNotNull(manifest.trailhead)
    assertEquals("myapp_android_signedInFresh", th.name)
    assertEquals(mapOf("account" to "primary"), th.args)
    assertFalse(th.manual)
    assertNotNull(manifest.startedAtMs)
    // Objective is only known at finish.
    assertNull(manifest.objective)
    assertNull(manifest.finishedAtMs)
  }

  @Test
  fun markStartWithoutATrailheadRecordsManualPositioning() = runBlocking {
    val run = startDemo()

    ExternalAgentSupervisor.markDemoStart(run.id, trailhead = null).getOrThrow()

    val manifest = assertNotNull(ExternalAgentSupervisor.readDemoManifest(run.id))
    val th = assertNotNull(manifest.trailhead)
    assertTrue(th.manual)
    assertNull(th.name)
  }

  @Test
  fun markStartIsRejectedOutsideThePositioningPhase() = runBlocking {
    val run = startDemo()
    ExternalAgentSupervisor.markDemoStart(run.id, trailhead = null).getOrThrow()

    // Already recording: a second mark-start is an invalid transition.
    assertTrue(ExternalAgentSupervisor.markDemoStart(run.id, trailhead = null).isFailure)
  }

  @Test
  fun finishMovesToDoneAndRecordsTheObjective() = runBlocking {
    val run = startDemo()
    ExternalAgentSupervisor.markDemoStart(run.id, trailhead = null).getOrThrow()

    val bundleDir = ExternalAgentSupervisor.finishDemo(run.id, objective = "Widget appears in cart", notes = "flaky on slow net").getOrThrow()
    assertTrue(File(bundleDir).isDirectory)

    val dto = assertNotNull(ExternalAgentSupervisor.run(run.id)?.demo)
    assertEquals("done", dto.phase)
    assertEquals("Widget appears in cart", dto.objective)

    val manifest = assertNotNull(ExternalAgentSupervisor.readDemoManifest(run.id))
    assertNotNull(manifest.startedAtMs)
    assertNotNull(manifest.finishedAtMs)
    assertEquals("Widget appears in cart", manifest.objective)
    assertEquals("flaky on slow net", manifest.notes)
  }

  @Test
  fun finishIsRejectedBeforeRecordingAndAfterDone() = runBlocking {
    val run = startDemo()
    // Still positioning: nothing to finish.
    assertTrue(ExternalAgentSupervisor.finishDemo(run.id, "x", null).isFailure)

    ExternalAgentSupervisor.markDemoStart(run.id, trailhead = null).getOrThrow()
    ExternalAgentSupervisor.finishDemo(run.id, "done once", null).getOrThrow()
    // Already done: a second finish is an invalid transition.
    assertTrue(ExternalAgentSupervisor.finishDemo(run.id, "done twice", null).isFailure)
  }

  @Test
  fun finishRejectsABlankObjectiveAndLeavesThePhaseUnchanged() = runBlocking {
    val run = startDemo()
    ExternalAgentSupervisor.markDemoStart(run.id, trailhead = null).getOrThrow()

    assertTrue(ExternalAgentSupervisor.finishDemo(run.id, objective = "   ", notes = null).isFailure)
    assertEquals("recording", ExternalAgentSupervisor.run(run.id)?.demo?.phase)
  }

  @Test
  fun demoActionPhaseTracksTheCurrentPhaseAndIsNullForNonDemoRuns() = runBlocking {
    val run = startDemo()
    assertEquals("setup", ExternalAgentSupervisor.demoActionPhase(run.id))

    ExternalAgentSupervisor.markDemoStart(run.id, trailhead = null).getOrThrow()
    assertEquals("step", ExternalAgentSupervisor.demoActionPhase(run.id))

    assertNull(ExternalAgentSupervisor.demoActionPhase("not-a-demo-run-" + System.nanoTime()))
  }

  @Test
  fun appendDemoActionAppendsOneNdjsonLinePerActionForDemoRunsOnly() {
    val run = startDemo()
    ExternalAgentSupervisor.appendDemoAction(run.id, buildJsonObject { put("seq", 0); put("phase", "setup") })
    ExternalAgentSupervisor.appendDemoAction(run.id, buildJsonObject { put("seq", 1); put("phase", "step") })

    val dir = assertNotNull(ExternalAgentSupervisor.evidenceDir(run.id))
    val lines = File(dir, "actions.ndjson").readLines().filter { it.isNotBlank() }
    assertEquals(2, lines.size)
    val first = JSON.parseToJsonElement(lines[0]).jsonObject
    assertEquals(0, first["seq"]?.jsonPrimitive?.int)
    assertEquals("setup", first["phase"]?.jsonPrimitive?.content)

    // A non-demo run is a no-op: nothing is written.
    val soloId = "solo-" + System.nanoTime()
    seededIds += soloId
    ExternalAgentSupervisor.runs[soloId] = MutableExternalAgentRun(
      id = soloId,
      request = ExternalAgentRunRequest(agentType = ExternalAgentType.SOLO, prompt = ""),
      title = "solo",
      prompt = "",
      cwd = tempRoot(),
      artifactsRoot = tempRoot(),
    )
    ExternalAgentSupervisor.appendDemoAction(soloId, buildJsonObject { put("seq", 0) })
    val soloDir = ExternalAgentSupervisor.evidenceDir(soloId)
    assertTrue(soloDir == null || !File(soloDir, "actions.ndjson").exists())
  }

  @Test
  fun deleteDemoStepRemovesTheEventItsNdjsonLineAndItsEvidence() = runBlocking {
    val run = startDemo()
    ExternalAgentSupervisor.markDemoStart(run.id, trailhead = null).getOrThrow()
    val live = assertNotNull(ExternalAgentSupervisor.runs[run.id])
    // Two demonstrated steps, shaped as the gesture route records them (phase + tape seq in output).
    val keep = live.emit(
      kind = ExternalAgentEventKind.HUMAN_ACTION,
      title = "Tap OK",
      output = buildJsonObject { put("phase", "step"); put("seq", 1) },
    )
    val mistake = live.emit(
      kind = ExternalAgentEventKind.HUMAN_ACTION,
      title = "Type \"12345\"",
      output = buildJsonObject { put("phase", "step"); put("seq", 2) },
    )
    ExternalAgentSupervisor.appendDemoAction(run.id, buildJsonObject { put("seq", 1); put("phase", "step") })
    ExternalAgentSupervisor.appendDemoAction(run.id, buildJsonObject { put("seq", 2); put("phase", "step") })
    val dir = assertNotNull(ExternalAgentSupervisor.evidenceDir(run.id))
    val evidence = File(dir, "2-before.png").apply { parentFile.mkdirs(); writeText("png") }

    ExternalAgentSupervisor.deleteDemoStep(run.id, mistake.id).getOrThrow()

    val ids = assertNotNull(ExternalAgentSupervisor.events(run.id)).map { it.id }
    assertTrue(ids.contains(keep.id))
    assertFalse(ids.contains(mistake.id))
    val lines = File(dir, "actions.ndjson").readLines().filter { it.isNotBlank() }
    assertEquals(1, lines.size)
    assertEquals(1, JSON.parseToJsonElement(lines[0]).jsonObject["seq"]?.jsonPrimitive?.int)
    assertFalse(evidence.exists())
  }

  @Test
  fun deleteDemoStepRejectsNonStepsWrongPhasesAndUnknownEvents() = runBlocking {
    val run = startDemo()
    val live = assertNotNull(ExternalAgentSupervisor.runs[run.id])
    val setup = live.emit(
      kind = ExternalAgentEventKind.HUMAN_ACTION,
      title = "Setup tap",
      output = buildJsonObject { put("phase", "setup"); put("seq", 1) },
    )
    // Still positioning: nothing is deletable yet.
    assertTrue(ExternalAgentSupervisor.deleteDemoStep(run.id, setup.id).isFailure)

    ExternalAgentSupervisor.markDemoStart(run.id, trailhead = null).getOrThrow()
    // A setup action is not a demonstrated step.
    assertTrue(ExternalAgentSupervisor.deleteDemoStep(run.id, setup.id).isFailure)
    // An id that matches no event.
    assertTrue(ExternalAgentSupervisor.deleteDemoStep(run.id, "no-such-event").isFailure)

    val step = live.emit(
      kind = ExternalAgentEventKind.HUMAN_ACTION,
      title = "Tap",
      output = buildJsonObject { put("phase", "step"); put("seq", 2) },
    )
    ExternalAgentSupervisor.finishDemo(run.id, objective = "obj", notes = null).getOrThrow()
    // Recording is over: the bundle is sealed, steps are no longer deletable.
    assertTrue(ExternalAgentSupervisor.deleteDemoStep(run.id, step.id).isFailure)
  }

  @Test
  fun buildDemoActionLineCarriesPerKindFields() {
    val tap = buildDemoActionLine(
      seq = 3,
      phase = "step",
      body = RecordGestureRequest(trailblazeDeviceId = device, type = "tap", x = 120, y = 340),
      resp = RecordGestureResponse(ok = true, label = "Tap (120, 340)", yaml = "- tools:\n    - tapOnPoint"),
      evidence = null,
    )
    assertEquals(3, tap["seq"]?.jsonPrimitive?.int)
    assertEquals("step", tap["phase"]?.jsonPrimitive?.content)
    assertEquals("tap", tap["kind"]?.jsonPrimitive?.content)
    assertEquals(120, tap["x"]?.jsonPrimitive?.int)
    assertEquals(340, tap["y"]?.jsonPrimitive?.int)
    assertEquals("Tap (120, 340)", tap["title"]?.jsonPrimitive?.content)
    assertNull(tap["text"])
    assertNull(tap["key"])

    val typed = buildDemoActionLine(
      seq = 4,
      phase = "step",
      body = RecordGestureRequest(trailblazeDeviceId = device, type = "inputText", text = "hello"),
      resp = RecordGestureResponse(ok = true, label = "Type \"hello\""),
      evidence = null,
    )
    assertEquals("inputText", typed["kind"]?.jsonPrimitive?.content)
    assertEquals("hello", typed["text"]?.jsonPrimitive?.content)

    val key = buildDemoActionLine(
      seq = 5,
      phase = "setup",
      body = RecordGestureRequest(trailblazeDeviceId = device, type = "pressKey", key = "enter"),
      resp = RecordGestureResponse(ok = true, label = "Press enter"),
      evidence = null,
    )
    assertEquals("pressKey", key["kind"]?.jsonPrimitive?.content)
    assertEquals("enter", key["key"]?.jsonPrimitive?.content)

    val swipe = buildDemoActionLine(
      seq = 6,
      phase = "step",
      body = RecordGestureRequest(trailblazeDeviceId = device, type = "swipe", startX = 10, startY = 20, endX = 10, endY = 400),
      resp = RecordGestureResponse(ok = true, label = "Swipe"),
      evidence = RecordActionEvidence(
        dir = "/tmp/tape",
        before = RecordEvidenceSide(screenshot = "6-before.png", hierarchy = "6-before-hierarchy.txt"),
        after = RecordEvidenceSide(screenshot = "6-after.png", hierarchy = "6-after-hierarchy.txt"),
        screenChanged = true,
      ),
    )
    assertEquals("swipe", swipe["kind"]?.jsonPrimitive?.content)
    assertEquals(10, swipe["x"]?.jsonPrimitive?.int)
    assertEquals(20, swipe["y"]?.jsonPrimitive?.int)
    assertEquals(400, swipe["endY"]?.jsonPrimitive?.int)
    val evidence = assertNotNull(swipe["evidence"]).jsonObject
    assertEquals("6-before.png", evidence["before"]?.jsonPrimitive?.content)
    assertEquals("6-after-hierarchy.txt", evidence["afterHierarchy"]?.jsonPrimitive?.content)
    assertTrue(swipe["screenChanged"]?.jsonPrimitive?.content == "true")
  }

  // ─── Draft storage layout + platform keys ───────────────────────────────────

  @Test
  fun draftLandsUnderTheWorkspaceDraftsDirKeyedByPlatformWithASelfIgnoringGitignore() = runBlocking {
    val run = startDemo(platform = "android")
    val demo0 = assertNotNull(run.demo)
    assertEquals("android", demo0.platform)
    assertEquals(listOf("android"), demo0.platforms.map { it.key })
    assertNotNull(demo0.draftDir)

    ExternalAgentSupervisor.markDemoStart(run.id, trailhead = null).getOrThrow()

    val dir = assertNotNull(ExternalAgentSupervisor.evidenceDir(run.id))
    // Current platform bundle: <trailsRoot>/.trailblaze/drafts/<runId>/demos/<platform>/
    assertEquals("android", dir.name)
    assertEquals("demos", dir.parentFile.name)
    assertTrue(dir.canonicalPath.replace('\\', '/').contains("/.trailblaze/drafts/"))
    assertTrue(File(dir, "demo.yaml").isFile)

    val runDraftDir = dir.parentFile.parentFile // .../drafts/<runId>
    assertTrue(File(runDraftDir, "draft.yaml").isFile)

    // The self-ignoring `*` .gitignore sits at the drafts root, so drafts can never be committed.
    val gitignore = File(runDraftDir.parentFile, ".gitignore")
    assertTrue(gitignore.isFile)
    assertEquals("*", gitignore.readText().trim())
  }

  @Test
  fun platformKeyDerivationCoversTheVocabulary() {
    fun key(instanceId: String, platform: TrailblazeDevicePlatform) =
      demoPlatformKey(TrailblazeDeviceId(instanceId, platform))

    assertEquals("android", key("emulator-5554", TrailblazeDevicePlatform.ANDROID))
    assertEquals("android-tablet", key("emulator-tablet-1", TrailblazeDevicePlatform.ANDROID))
    assertEquals("iphone", key("iPhone-15-sim", TrailblazeDevicePlatform.IOS))
    assertEquals("ipad", key("iPad-Pro-sim", TrailblazeDevicePlatform.IOS))
    // An unknowable iOS form factor (a bare simulator UUID) falls back to the bare platform name.
    assertEquals("ios", key("ABCD-1234-5678-UUID", TrailblazeDevicePlatform.IOS))
    assertEquals("web", key("checkout", TrailblazeDevicePlatform.WEB))
  }

  // ─── Multi-platform loop: add-platform ───────────────────────────────────────

  @Test
  fun addPlatformResetsToPositioningAndListsTheSecondPlatform() = runBlocking {
    val run = startDemo(platform = "android")
    ExternalAgentSupervisor.markDemoStart(run.id, trailhead = null).getOrThrow()
    ExternalAgentSupervisor.finishDemo(run.id, objective = "Widget in cart", notes = null).getOrThrow()

    val iphone = TrailblazeDeviceId("iPhone-15-sim", TrailblazeDevicePlatform.IOS)
    ExternalAgentSupervisor.addDemoPlatform(run.id, iphone).getOrThrow()

    val demo = assertNotNull(ExternalAgentSupervisor.run(run.id)?.demo)
    assertEquals("positioning", demo.phase)
    assertEquals("iphone", demo.platform)
    assertEquals(listOf("android", "iphone"), demo.platforms.map { it.key })
    // The first platform's demonstration finished; the new one has not been demonstrated yet.
    assertTrue(demo.platforms.first { it.key == "android" }.done)
    assertFalse(demo.platforms.first { it.key == "iphone" }.done)
    // The current bundle now points at the new platform's dir.
    val dir = assertNotNull(ExternalAgentSupervisor.evidenceDir(run.id))
    assertEquals("iphone", dir.name)
  }

  @Test
  fun addPlatformDropsTheOldGenerationLinkAndRestampsThePlatform() = runBlocking {
    val run = startDemo(platform = "android")
    ExternalAgentSupervisor.markDemoStart(run.id, trailhead = null).getOrThrow()
    ExternalAgentSupervisor.finishDemo(run.id, objective = "Widget in cart", notes = null).getOrThrow()

    // Platform 1's generation run finished and delivered a trail.
    val genId = "gen-" + System.nanoTime()
    seededIds += genId
    ExternalAgentSupervisor.runs[genId] = MutableExternalAgentRun(
      id = genId,
      request = ExternalAgentRunRequest(agentType = ExternalAgentType.CLAUDE, prompt = "x"),
      title = "gen",
      prompt = "x",
      cwd = tempRoot(),
    ).also { it.status = ExternalAgentSessionStatus.COMPLETED }
    val state = assertNotNull(ExternalAgentSupervisor.runs[run.id]?.demo)
    state.generationRunId = genId
    state.trailId = "1234/buy-a-widget"

    ExternalAgentSupervisor.addDemoPlatform(run.id, TrailblazeDeviceId("iPhone-15-sim", TrailblazeDevicePlatform.IOS)).getOrThrow()

    // The old generation run is not this platform's: the link is dropped (so the UI doesn't adopt
    // its transcript), while the delivered trail stays for the merge-mode generation.
    val demo = assertNotNull(ExternalAgentSupervisor.run(run.id)?.demo)
    assertNull(demo.generationRunId)
    assertEquals("1234/buy-a-widget", demo.trailId)
    // The demo now describes the new device's platform - the manifest and the generation preamble
    // both read it.
    assertEquals("ios", state.platform)
  }

  @Test
  fun addPlatformIsRejectedBeforeDoneAndWhileGenerating() = runBlocking {
    val run = startDemo()
    // Wrong phase: still positioning.
    assertTrue(ExternalAgentSupervisor.addDemoPlatform(run.id, device).isFailure)

    ExternalAgentSupervisor.markDemoStart(run.id, trailhead = null).getOrThrow()
    ExternalAgentSupervisor.finishDemo(run.id, objective = "Widget in cart", notes = null).getOrThrow()

    // A generation run for this demonstration is still running.
    val genId = "gen-" + System.nanoTime()
    seededIds += genId
    ExternalAgentSupervisor.runs[genId] = MutableExternalAgentRun(
      id = genId,
      request = ExternalAgentRunRequest(agentType = ExternalAgentType.CLAUDE, prompt = "x"),
      title = "gen",
      prompt = "x",
      cwd = tempRoot(),
    ).also { it.status = ExternalAgentSessionStatus.RUNNING }
    ExternalAgentSupervisor.runs[run.id]?.demo?.generationRunId = genId

    assertTrue(ExternalAgentSupervisor.addDemoPlatform(run.id, device).isFailure)
  }

  @Test
  fun finishWithABlankObjectiveOnALaterPlatformKeepsTheOriginal() = runBlocking {
    val run = startDemo()
    ExternalAgentSupervisor.markDemoStart(run.id, trailhead = null).getOrThrow()
    ExternalAgentSupervisor.finishDemo(run.id, objective = "Original objective", notes = null).getOrThrow()

    ExternalAgentSupervisor.addDemoPlatform(run.id, TrailblazeDeviceId("iPad-sim", TrailblazeDevicePlatform.IOS)).getOrThrow()
    ExternalAgentSupervisor.markDemoStart(run.id, trailhead = null).getOrThrow()
    // A blank objective on platform 2 is accepted and keeps the existing one.
    ExternalAgentSupervisor.finishDemo(run.id, objective = "   ", notes = null).getOrThrow()

    assertEquals("Original objective", ExternalAgentSupervisor.run(run.id)?.demo?.objective)
  }

  @Test
  fun finishRetitlesTheRunToTheObjective() = runBlocking {
    val run = startDemo(title = "Demonstration")
    ExternalAgentSupervisor.markDemoStart(run.id, trailhead = null).getOrThrow()
    ExternalAgentSupervisor.finishDemo(run.id, objective = "Widget appears in cart", notes = null).getOrThrow()

    assertEquals("Widget appears in cart", ExternalAgentSupervisor.run(run.id)?.title)
  }

  @Test
  fun evictionProtectsActiveDemosButNotFinishedOnes() {
    fun dummy(configure: MutableExternalAgentRun.() -> Unit = {}): MutableExternalAgentRun =
      MutableExternalAgentRun(
        id = "dummy-" + System.nanoTime() + "-" + (0..Int.MAX_VALUE).random(),
        request = ExternalAgentRunRequest(agentType = ExternalAgentType.SOLO, prompt = ""),
        title = "dummy",
        prompt = "",
        cwd = File("."),
      ).apply {
        status = ExternalAgentSessionStatus.COMPLETED
        configure()
      }

    val activePositioning = dummy { demo = DemoRunState(device, "t", "android") }
    val activeRecording = dummy { demo = DemoRunState(device, "t", "android").also { it.phase = DemoPhase.RECORDING } }
    val doneDemo = dummy { demo = DemoRunState(device, "t", "android").also { it.phase = DemoPhase.DONE } }
    // Enough plain finished runs to push everything past the retention cap.
    val plain = (1..60).map { dummy() }

    val evicted = ExternalAgentSupervisor.runsToEvict(plain + doneDemo + activePositioning + activeRecording)

    // Active demonstrations are never eviction candidates, regardless of the cap.
    assertFalse(evicted.contains(activePositioning))
    assertFalse(evicted.contains(activeRecording))
    // A done demo is an ordinary finished run and can be evicted once past the cap.
    assertTrue(evicted.isNotEmpty())
    assertTrue(evicted.all { !ExternalAgentSupervisor.isActiveDemo(it) })
  }
}
