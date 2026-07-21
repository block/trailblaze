package xyz.block.trailblaze.trailrunner

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Companion sessions: an external agent CLI attaches to a run the daemon never spawned. These pin
 * the observable contract - born RUNNING with the companion state on the DTO, narration events
 * landing through the normal emit path, disconnect finishing the run, the folder view refusing to
 * escape the trails root, and the reply surface staying closed - without a live Ktor server.
 */
class CompanionSessionTest {
  private val startedIds = mutableListOf<String>()
  private val tempDirs = mutableListOf<File>()

  @AfterTest
  fun cleanup() {
    startedIds.forEach {
      // Disconnect first: removing a still-RUNNING companion run would leave its idle-watchdog
      // coroutine looping for the rest of the test JVM. Non-companion seeds fail this harmlessly.
      ExternalAgentSupervisor.disconnectCompanion(it, note = null)
      ExternalAgentSupervisor.runs.remove(it)
    }
    startedIds.clear()
    tempDirs.forEach { runCatching { it.deleteRecursively() } }
    tempDirs.clear()
  }

  private fun tempRoot(): File = Files.createTempDirectory("companion-session").toFile().also { tempDirs += it }

  private fun connect(root: File, request: CompanionConnectRequest = CompanionConnectRequest()): ExternalAgentRunDto {
    val run = ExternalAgentSupervisor.startCompanion(request, root).getOrThrow()
    startedIds += run.id
    return run
  }

  /** Seeds a run directly (no validation), optionally with companion state - the supervisor-test pattern. */
  private fun seedRun(id: String, companion: CompanionRunState? = null): MutableExternalAgentRun {
    val run = MutableExternalAgentRun(
      id = id,
      request = ExternalAgentRunRequest(agentType = ExternalAgentType.CLAUDE, prompt = "test run"),
      title = "Test run",
      prompt = "test run",
      cwd = File("."),
    )
    run.companion = companion
    ExternalAgentSupervisor.runs[id] = run
    startedIds += id
    return run
  }

  @Test
  fun connectCreatesARunningRunCarryingTheCompanionState() {
    val root = tempRoot()
    File(root, "myapp/tos").mkdirs()
    val run = connect(
      root,
      CompanionConnectRequest(agentLabel = "Claude Code · myapp", title = "ToS trail", folder = "myapp/tos/"),
    )
    assertEquals(ExternalAgentSessionStatus.RUNNING, run.status)
    assertEquals("ToS trail", run.title)
    assertEquals("Claude Code · myapp", run.companion?.agentLabel)
    // The folder is normalized to a clean relative path (trailing slash stripped).
    assertEquals("myapp/tos", run.companion?.folder)
    assertEquals(ExternalAgentType.CLAUDE, run.agentType)
  }

  @Test
  fun connectRejectsAFolderThatEscapesTheTrailsRoot() {
    val root = tempRoot()
    val result = ExternalAgentSupervisor.startCompanion(CompanionConnectRequest(folder = "../outside"), root)
    assertTrue(result.isFailure)
    // Pin that it failed for the folder, not some unrelated validation.
    assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("folder"))
  }

  @Test
  fun connectRefusesPastTheActiveSessionCap() {
    val root = tempRoot()
    repeat(8) { i -> seedRun("companion-cap-$i", companion = CompanionRunState(agentLabel = null, folder = null)) }
    val result = ExternalAgentSupervisor.startCompanion(CompanionConnectRequest(), root)
    assertTrue(result.isFailure)
    assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("companion sessions"))
    // The cap counts LIVE sessions: ending one frees a slot (a count of ever-finished companions
    // would eventually lock companion mode out of a long-lived daemon for good).
    ExternalAgentSupervisor.disconnectCompanion("companion-cap-0", note = null).getOrThrow()
    connect(root)
  }

  @Test
  fun eventsLandInTheTranscriptAndStopAfterDisconnect() {
    val root = tempRoot()
    val run = connect(root)
    ExternalAgentSupervisor.companionEvent(run.id, kind = null, title = null, text = "Scaffolding the trail now.").getOrThrow()
    val events = ExternalAgentSupervisor.events(run.id).orEmpty()
    assertEquals(ExternalAgentEventKind.ASSISTANT_MESSAGE, events.last().kind)
    assertEquals("Scaffolding the trail now.", events.last().text)

    ExternalAgentSupervisor.disconnectCompanion(run.id, note = "done").getOrThrow()
    assertEquals(ExternalAgentSessionStatus.COMPLETED, ExternalAgentSupervisor.run(run.id)?.status)
    // The note rides the "session ended" lifecycle event, not the finish event.
    val ended = ExternalAgentSupervisor.events(run.id).orEmpty().first { it.title == "Companion session ended" }
    assertEquals("done", ended.text)
    // Idempotent: a second disconnect is a no-op success (no duplicate terminal events), and
    // further events are refused.
    ExternalAgentSupervisor.disconnectCompanion(run.id, note = null).getOrThrow()
    assertEquals(1, ExternalAgentSupervisor.events(run.id).orEmpty().count { it.title == "Companion session ended" })
    assertTrue(ExternalAgentSupervisor.companionEvent(run.id, null, null, "late").isFailure)
  }

  @Test
  fun eventMapsTheNarrationKindVocabulary() {
    val run = connect(tempRoot())
    ExternalAgentSupervisor.companionEvent(run.id, kind = "lifecycle", title = "Step", text = null).getOrThrow()
    ExternalAgentSupervisor.companionEvent(run.id, kind = "error", title = null, text = "boom").getOrThrow()
    val events = ExternalAgentSupervisor.events(run.id).orEmpty()
    assertEquals(ExternalAgentEventKind.LIFECYCLE, events.first { it.title == "Step" }.kind)
    assertEquals(ExternalAgentEventKind.ERROR, events.first { it.text == "boom" }.kind)
  }

  @Test
  fun eventRejectsAnUnsupportedKind() {
    val run = connect(tempRoot())
    assertTrue(ExternalAgentSupervisor.companionEvent(run.id, kind = "tool_call", title = null, text = "x").isFailure)
  }

  @Test
  fun theIdleWatchdogReapsAStaleSessionAndSparesAFreshOne() {
    val run = connect(tempRoot())
    val live = ExternalAgentSupervisor.runs[run.id]!!
    val now = System.currentTimeMillis()
    // Fresh (just connected, lastActivity = the connect event): spared.
    assertFalse(ExternalAgentSupervisor.reapCompanionIfIdle(live, now))
    assertEquals(ExternalAgentSessionStatus.RUNNING, ExternalAgentSupervisor.run(run.id)?.status)
    // Three hours of silence: reaped through the normal disconnect path, with the reason on record.
    assertTrue(ExternalAgentSupervisor.reapCompanionIfIdle(live, now + 3 * 60 * 60 * 1000L))
    assertEquals(ExternalAgentSessionStatus.COMPLETED, ExternalAgentSupervisor.run(run.id)?.status)
    val ended = ExternalAgentSupervisor.events(run.id).orEmpty().first { it.title == "Companion session ended" }
    assertTrue(ended.text.orEmpty().contains("auto-disconnected"))
    // Already ended: a late watchdog tick is a no-op.
    assertFalse(ExternalAgentSupervisor.reapCompanionIfIdle(live, now + 6 * 60 * 60 * 1000L))
  }

  @Test
  fun theWatchdogSparesASessionThatSpokeWhileItWaitedForTheRunLock() {
    val run = connect(tempRoot())
    val live = ExternalAgentSupervisor.runs[run.id]!!
    // Stale by the watchdog's arithmetic at decision time.
    val decidedAt = System.currentTimeMillis()
    live.lastActivityAtMs = decidedAt - 3 * 60 * 60 * 1000L
    var reaped = true
    val watchdog = Thread { reaped = ExternalAgentSupervisor.reapCompanionIfIdle(live, decidedAt) }
    synchronized(live) {
      watchdog.start()
      // The watchdog parks on the run lock (as it would behind a mid-emit event)...
      while (watchdog.state != Thread.State.BLOCKED) Thread.sleep(1)
      // ...while the event that beat it refreshes the activity clock under that same lock.
      ExternalAgentSupervisor.companionEvent(run.id, kind = null, title = null, text = "still here").getOrThrow()
    }
    watchdog.join(5_000)
    assertFalse(reaped)
    assertEquals(ExternalAgentSessionStatus.RUNNING, ExternalAgentSupervisor.run(run.id)?.status)
  }

  @Test
  fun uiStopEndsACompanionRunLikeADisconnect() {
    val run = connect(tempRoot())
    assertTrue(ExternalAgentSupervisor.cancel(run.id))
    // Ended, not CANCELLED-with-extra-terminal-events: Stop routes through the disconnect path.
    assertEquals(ExternalAgentSessionStatus.COMPLETED, ExternalAgentSupervisor.run(run.id)?.status)
    val ended = ExternalAgentSupervisor.events(run.id).orEmpty().first { it.title == "Companion session ended" }
    assertTrue(ended.text.orEmpty().contains("stopped from Trail Runner"))
    // A CLI disconnect racing in afterwards stays a no-op.
    ExternalAgentSupervisor.disconnectCompanion(run.id, note = null).getOrThrow()
    assertEquals(1, ExternalAgentSupervisor.events(run.id).orEmpty().count { it.title == "Companion session ended" })
  }

  @Test
  fun companionCallsRefuseARunTheDaemonSpawnedItself() {
    val id = "companion-guard-" + System.nanoTime()
    seedRun(id, companion = null)
    assertTrue(ExternalAgentSupervisor.companionEvent(id, null, null, "x").exceptionOrNull()?.message.orEmpty().contains("not a companion session"))
    assertTrue(ExternalAgentSupervisor.disconnectCompanion(id, null).exceptionOrNull()?.message.orEmpty().contains("not a companion session"))
    assertNull(ExternalAgentSupervisor.companionFolderContent(id, tempRoot()))
  }

  @Test
  fun folderContentListsTrailFilesAndIgnoresTheRest() {
    val root = tempRoot()
    File(root, "myapp/tos").mkdirs()
    File(root, "myapp/tos/blaze.yaml").writeText("- config:\n")
    File(root, "myapp/tos/intent.md").writeText("# intent\n")
    File(root, "myapp/tos/scratch.txt").writeText("ignore me\n")
    val run = connect(root, CompanionConnectRequest(folder = "myapp/tos"))

    val result = ExternalAgentSupervisor.companionFolderContent(run.id, root)!!
    assertEquals("myapp/tos", result.trailId)
    assertEquals(listOf("blaze.yaml", "intent.md"), result.files.map { it.name })
    assertEquals("- config:\n", result.files.first { it.name == "blaze.yaml" }.content)
  }

  @Test
  fun folderContentIsEmptyBeforeTheFolderExistsAndNullForNonCompanionRuns() {
    val root = tempRoot()
    val run = connect(root, CompanionConnectRequest(folder = "myapp/not-written-yet"))
    val result = ExternalAgentSupervisor.companionFolderContent(run.id, root)!!
    assertEquals("myapp/not-written-yet", result.trailId)
    assertTrue(result.files.isEmpty())
    assertNull(ExternalAgentSupervisor.companionFolderContent("no-such-run", root))
  }

  @Test
  fun folderContentRefusesASymlinkThatEscapesTheRoot() {
    val root = tempRoot()
    val outside = tempRoot()
    File(outside, "evil.yaml").writeText("- config:\n")
    Files.createSymbolicLink(File(root, "link").toPath(), outside.toPath())
    // Seeded directly: connect-time validation would reject this folder, but the read path must
    // re-check containment on its own (the docstring's headline claim).
    val id = "companion-symlink-" + System.nanoTime()
    seedRun(id, companion = CompanionRunState(agentLabel = null, folder = "link"))
    val result = ExternalAgentSupervisor.companionFolderContent(id, root)!!
    assertTrue(result.files.isEmpty())
  }

  @Test
  fun folderTreeListsEveryFileAndDirectoryRecursively() {
    val root = tempRoot()
    File(root, "myapp/tos/shots").mkdirs()
    File(root, "myapp/tos/empty").mkdirs()
    File(root, "myapp/tos/blaze.yaml").writeText("- config:\n")
    File(root, "myapp/tos/shots/home.png").writeText("png")
    File(root, "myapp/tos/shots/.DS_Store").writeText("finder litter")
    val run = connect(root, CompanionConnectRequest(folder = "myapp/tos"))

    val entries = ExternalAgentSupervisor.companionFolderTree(run.id, root)!!
    // Unlike folder-content (top-level yaml/md only), the tree carries EVERYTHING - subdirs,
    // empty dirs, non-trail artifacts - sorted by path. Except .DS_Store: Finder drops one the
    // moment the tab's own reveal button opens the folder.
    assertEquals(listOf("blaze.yaml", "empty", "shots", "shots/home.png"), entries.map { it.path })
    assertTrue(entries.first { it.path == "shots" }.dir)
    assertFalse(entries.first { it.path == "blaze.yaml" }.dir)
    assertEquals(3L, entries.first { it.path == "shots/home.png" }.size)
    assertNull(ExternalAgentSupervisor.companionFolderTree("no-such-run", root))
  }

  @Test
  fun folderTreeHidesTheDaemonJournalAndDropsSymlinkEscapes() {
    val root = tempRoot()
    // Connect materializes only the daemon's own .companion journal inside the folder - state the
    // tree must hide, so a fresh session's tree reads empty, not "the daemon wrote demo files".
    val fresh = connect(root, CompanionConnectRequest(folder = "myapp/fresh"))
    assertTrue(ExternalAgentSupervisor.companionFolderTree(fresh.id, root)!!.isEmpty())

    val outside = tempRoot()
    File(outside, "evil.yaml").writeText("- config:\n")
    File(root, "myapp/tos").mkdirs()
    Files.createSymbolicLink(File(root, "myapp/tos/link").toPath(), outside.toPath())
    val run = connect(root, CompanionConnectRequest(folder = "myapp/tos"))
    // The symlinked dir and everything reached through it canonicalize outside the folder.
    assertTrue(ExternalAgentSupervisor.companionFolderTree(run.id, root)!!.isEmpty())
  }

  @Test
  fun folderTreeDoesNotDescendASymlinkedDirectory() {
    val root = tempRoot()
    val outside = tempRoot()
    File(root, "myapp/tos").mkdirs()
    File(root, "myapp/tos/blaze.yaml").writeText("- config:\n")
    // An escaping dir symlink whose target hops straight back into the folder: if the walker
    // descends the escape, files reached through it canonicalize INSIDE the folder and leak
    // into the tree as phantom "link/back/..." aliases (looping until the entry cap). The
    // walker must prune at the symlink, not just drop escaping entries after the fact.
    Files.createSymbolicLink(File(outside, "back").toPath(), File(root, "myapp/tos").toPath())
    Files.createSymbolicLink(File(root, "myapp/tos/link").toPath(), outside.toPath())
    val run = connect(root, CompanionConnectRequest(folder = "myapp/tos"))

    val entries = ExternalAgentSupervisor.companionFolderTree(run.id, root)!!
    assertEquals(listOf("blaze.yaml"), entries.map { it.path })
  }

  @Test
  fun folderDirResolvesTheDeclaredFolderWithContainment() {
    val root = tempRoot()
    File(root, "myapp/tos").mkdirs()
    val run = connect(root, CompanionConnectRequest(folder = "myapp/tos"))
    assertEquals(File(root, "myapp/tos").canonicalFile, ExternalAgentSupervisor.companionFolderDir(run.id, root))
    // Unknown run, non-companion run, and a missing folder all resolve to null. Seeded directly:
    // a real connect materializes the folder via its .companion journal.
    assertNull(ExternalAgentSupervisor.companionFolderDir("no-such-run", root))
    val spawned = seedRun("companion-dir-guard-" + System.nanoTime(), companion = null)
    assertNull(ExternalAgentSupervisor.companionFolderDir(spawned.id, root))
    val missing = seedRun("companion-dir-missing-" + System.nanoTime(), companion = CompanionRunState(agentLabel = null, folder = "myapp/never-written"))
    assertNull(ExternalAgentSupervisor.companionFolderDir(missing.id, root))
  }

  @Test
  fun directiveLandsAsAUiCommandEventCarryingItsPayload() {
    val run = connect(tempRoot())
    val payload = buildJsonObject {
      put("title", "Plan")
      put("items", buildJsonArray { add("Scaffold"); add("Record the flow") })
    }
    ExternalAgentSupervisor.companionDirective(run.id, "checklist", payload).getOrThrow()
    val event = ExternalAgentSupervisor.events(run.id).orEmpty().last()
    assertEquals(ExternalAgentEventKind.UI_COMMAND, event.kind)
    // Title = the directive, input = the payload: the reduce contract the companion screen and
    // `companion listen` both script against.
    assertEquals("checklist", event.title)
    assertTrue(event.input.orEmpty().contains("Scaffold"))
  }

  @Test
  fun directiveRejectsUnknownNamesARoutelessNavigateAndAnEndedSession() {
    val run = connect(tempRoot())
    assertTrue(ExternalAgentSupervisor.companionDirective(run.id, "explode", null).isFailure)
    // navigate is the one directive that is meaningless without its field; an empty banner CLEARS.
    assertTrue(ExternalAgentSupervisor.companionDirective(run.id, "navigate", null).isFailure)
    ExternalAgentSupervisor.companionDirective(run.id, "navigate", buildJsonObject { put("route", "trails") }).getOrThrow()
    ExternalAgentSupervisor.companionDirective(run.id, "banner", null).getOrThrow()
    ExternalAgentSupervisor.disconnectCompanion(run.id, note = null).getOrThrow()
    assertTrue(ExternalAgentSupervisor.companionDirective(run.id, "banner", null).isFailure)
  }

  @Test
  fun userActionLandsAsAHumanActionEventAndRejectsUnknownTypes() {
    val run = connect(tempRoot())
    ExternalAgentSupervisor.companionUserAction(run.id, "handback", buildJsonObject { put("note", "over to you") }).getOrThrow()
    val event = ExternalAgentSupervisor.events(run.id).orEmpty().last()
    assertEquals(ExternalAgentEventKind.HUMAN_ACTION, event.kind)
    assertEquals("handback", event.title)
    assertTrue(event.input.orEmpty().contains("over to you"))
    assertTrue(ExternalAgentSupervisor.companionUserAction(run.id, "self-destruct", null).isFailure)
    // recording-saved is the daemon's own save receipt - a postable version would forge it.
    assertTrue(ExternalAgentSupervisor.companionUserAction(run.id, "recording-saved", null).isFailure)
    // Nor may the record-gesture mirror mint it: companion runs are off-limits to that path.
    assertFalse(ExternalAgentSupervisor.emitHumanAction(run.id, "recording-saved", null, null))
    assertTrue(ExternalAgentSupervisor.events(run.id).orEmpty().none { it.title == "recording-saved" })
  }

  @Test
  fun directivesBecomeStandingStateAndAnEmptyPayloadRetracts() {
    val root = tempRoot()
    File(root, "myapp/tos").mkdirs()
    val run = connect(root, CompanionConnectRequest(folder = "myapp/tos"))
    ExternalAgentSupervisor.companionDirective(run.id, "banner", buildJsonObject { put("text", "hi") }).getOrThrow()
    ExternalAgentSupervisor.companionDirective(run.id, "arm-recording", buildJsonObject { put("variant", "ios") }).getOrThrow()
    // navigate moves the window live but must NOT become standing state a reload would re-apply.
    ExternalAgentSupervisor.companionDirective(run.id, "navigate", buildJsonObject { put("route", "trails") }).getOrThrow()
    val directives = ExternalAgentSupervisor.run(run.id)?.companion?.directives.orEmpty()
    assertEquals(setOf("banner", "arm-recording"), directives.keys)
    assertTrue(directives.getValue("banner").payload.orEmpty().contains("hi"))
    // The seq is the correlation id quick replies carry back; it must point at the directive's event.
    val bannerEvent = ExternalAgentSupervisor.events(run.id).orEmpty().first { it.title == "banner" }
    assertEquals(bannerEvent.seq, directives.getValue("banner").seq)
    // An empty (or absent) payload retracts.
    ExternalAgentSupervisor.companionDirective(run.id, "banner", null).getOrThrow()
    assertEquals(setOf("arm-recording"), ExternalAgentSupervisor.run(run.id)?.companion?.directives.orEmpty().keys)
  }

  @Test
  fun fulfilledAsksRetractThemselvesServerSide() {
    val root = tempRoot()
    File(root, "myapp/tos").mkdirs()
    val run = connect(root, CompanionConnectRequest(folder = "myapp/tos"))
    ExternalAgentSupervisor.companionDirective(run.id, "select-device", buildJsonObject { put("platform", "ios") }).getOrThrow()
    ExternalAgentSupervisor.companionDirective(run.id, "arm-recording", buildJsonObject { put("variant", "ios") }).getOrThrow()
    // The ask names ios, so an Android connect doesn't satisfy it - the card must stay up.
    ExternalAgentSupervisor.companionUserAction(
      run.id,
      "device-connected",
      buildJsonObject {
        put("device", "emu-1")
        put("platform", "android")
      },
    ).getOrThrow()
    assertEquals(setOf("select-device", "arm-recording"), ExternalAgentSupervisor.run(run.id)?.companion?.directives.orEmpty().keys)
    ExternalAgentSupervisor.companionUserAction(
      run.id,
      "device-connected",
      buildJsonObject {
        put("device", "sim-1")
        put("platform", "ios")
      },
    ).getOrThrow()
    assertEquals(setOf("arm-recording"), ExternalAgentSupervisor.run(run.id)?.companion?.directives.orEmpty().keys)
    // The armed ask names the ios variant; saving a different variant doesn't answer it.
    ExternalAgentSupervisor.companionSaveRecording(run.id, "android", "- config:\n", root).getOrThrow()
    assertEquals(setOf("arm-recording"), ExternalAgentSupervisor.run(run.id)?.companion?.directives.orEmpty().keys)
    ExternalAgentSupervisor.companionSaveRecording(run.id, "ios", "- config:\n", root, platform = "iOS").getOrThrow()
    assertTrue(ExternalAgentSupervisor.run(run.id)?.companion?.directives.orEmpty().isEmpty())
    // The save receipt names the platform explicitly (normalized), not just the variant file.
    val saved = ExternalAgentSupervisor.events(run.id).orEmpty().last { it.title == "recording-saved" }
    assertTrue(saved.input.orEmpty().contains("\"platform\":\"ios\""))
  }

  @Test
  fun directiveRejectsOversizeAndMistypedPayloads() {
    val root = tempRoot()
    val run = connect(root, CompanionConnectRequest(folder = "big"))
    // Past the retention bound the emitted input would be truncated to invalid JSON - a 200
    // that renders as nothing. Reject at the poster instead.
    val huge = buildJsonObject { put("text", "x".repeat(40_000)) }
    assertTrue(ExternalAgentSupervisor.companionDirective(run.id, "banner", huge).isFailure)
    assertTrue(ExternalAgentSupervisor.companionUserAction(run.id, "handback", huge).isFailure)
    // The save yaml legitimately exceeds the payload bound but has its own generous cap.
    assertTrue(ExternalAgentSupervisor.companionSaveRecording(run.id, "ios", "y".repeat(4_000_001), root).isFailure)
    // Fields the window renders must be the right shape, or the card would come up blank/broken.
    assertTrue(ExternalAgentSupervisor.companionDirective(run.id, "banner", buildJsonObject { put("text", 7) }).isFailure)
    assertTrue(ExternalAgentSupervisor.companionDirective(run.id, "navigate", buildJsonObject { put("route", 123) }).isFailure)
    // Render-critical fields must be PRESENT too: a 200 for a banner or checklist that renders
    // as nothing would leave the agent believing guidance is up.
    assertTrue(ExternalAgentSupervisor.companionDirective(run.id, "banner", buildJsonObject { put("title", "Heads up") }).isFailure)
    // Whitespace-only text renders as nothing too - the window trims before showing the card.
    assertTrue(ExternalAgentSupervisor.companionDirective(run.id, "banner", buildJsonObject { put("text", "   ") }).isFailure)
    assertTrue(ExternalAgentSupervisor.companionDirective(run.id, "checklist", buildJsonObject { put("title", "Plan") }).isFailure)
    // An armed recording on a folderless session is un-fulfillable; the agent hears it now.
    val bare = connect(tempRoot())
    assertTrue(ExternalAgentSupervisor.companionDirective(bare.id, "arm-recording", buildJsonObject { put("variant", "ios") }).isFailure)
    assertTrue(
      ExternalAgentSupervisor.companionDirective(
        run.id,
        "checklist",
        buildJsonObject { put("items", buildJsonArray { add(1); add(2) }) },
      ).isFailure,
    )
    // Blank items are dropped by the window, so an all-blank list is an invisible directive.
    assertTrue(
      ExternalAgentSupervisor.companionDirective(
        run.id,
        "actions",
        buildJsonObject { put("items", buildJsonArray { add("  ") }) },
      ).isFailure,
    )
    // Every rendered field is shape-checked, not just the headline one - a numeric note would
    // silently vanish from the armed card while the agent believes it's showing.
    assertTrue(
      ExternalAgentSupervisor.companionDirective(
        run.id,
        "arm-recording",
        buildJsonObject { put("variant", "ios"); put("text", 5) },
      ).isFailure,
    )
    // Nothing above may have leaked into the standing state or the transcript.
    assertTrue(ExternalAgentSupervisor.run(run.id)?.companion?.directives.orEmpty().isEmpty())
    assertTrue(ExternalAgentSupervisor.events(run.id).orEmpty().none { it.kind == ExternalAgentEventKind.UI_COMMAND })
  }

  @Test
  fun saveRecordingWritesTheVariantAndEmitsRecordingSaved() {
    val root = tempRoot()
    File(root, "myapp/tos").mkdirs()
    val run = connect(root, CompanionConnectRequest(folder = "myapp/tos"))
    val path = ExternalAgentSupervisor.companionSaveRecording(run.id, "iOS", "- config:\n", root).getOrThrow()
    // The variant name normalizes exactly like every other recorded variant (writeVariant's slug).
    assertEquals("- config:\n", File(root, "myapp/tos/ios.trail.yaml").readText())
    assertTrue(path.endsWith("ios.trail.yaml"))
    val saved = ExternalAgentSupervisor.events(run.id).orEmpty().first { it.title == "recording-saved" }
    assertEquals(ExternalAgentEventKind.HUMAN_ACTION, saved.kind)
    assertTrue(saved.input.orEmpty().contains("ios.trail.yaml"))
  }

  @Test
  fun saveRecordingRefusesWithoutADeclaredFolderOrAfterTheSessionEnds() {
    val root = tempRoot()
    val bare = connect(root)
    assertTrue(ExternalAgentSupervisor.companionSaveRecording(bare.id, "ios", "x", root).isFailure)
    File(root, "myapp/tos").mkdirs()
    val run = connect(root, CompanionConnectRequest(folder = "myapp/tos"))
    ExternalAgentSupervisor.disconnectCompanion(run.id, note = null).getOrThrow()
    assertTrue(ExternalAgentSupervisor.companionSaveRecording(run.id, "ios", "x", root).isFailure)
    assertFalse(File(root, "myapp/tos/ios.trail.yaml").exists())
  }

  @Test
  fun saveRecordingFailsCleanlyWhenTheFolderPathIsOccupiedByAFile() {
    val root = tempRoot()
    // Occupied BEFORE connect: the journal mirror would otherwise have created the directory.
    File(root, "myapp").mkdirs()
    File(root, "myapp/tos").writeText("not a directory")
    val run = connect(root, CompanionConnectRequest(folder = "myapp/tos"))
    val result = ExternalAgentSupervisor.companionSaveRecording(run.id, "ios", "- config:\n", root)
    assertTrue(result.isFailure)
    // The failure must not emit the save receipt: recording-saved means the write really landed.
    assertTrue(ExternalAgentSupervisor.events(run.id).orEmpty().none { it.title == "recording-saved" })
  }

  @Test
  fun saveRecordingRefusesAFolderSwappedForAnEscapingSymlink() {
    val root = tempRoot()
    val outside = tempRoot()
    File(root, "myapp").mkdirs()
    File(root, "myapp/tos").mkdirs()
    val run = connect(root, CompanionConnectRequest(folder = "myapp/tos"))
    // The write-path containment recheck: connect validated the folder, but a symlink swapped in
    // afterwards must not carry the sanctioned UI write outside the root.
    File(root, "myapp/tos").deleteRecursively()
    Files.createSymbolicLink(File(root, "myapp/tos").toPath(), outside.toPath())
    assertTrue(ExternalAgentSupervisor.companionSaveRecording(run.id, "ios", "x", root).isFailure)
    assertFalse(File(outside, "ios.trail.yaml").exists())
  }

  @Test
  fun everyEventOfAFolderedSessionMirrorsIntoTheJournal() {
    val root = tempRoot()
    File(root, "myapp/tos").mkdirs()
    val run = connect(root, CompanionConnectRequest(folder = "myapp/tos"))
    ExternalAgentSupervisor.companionEvent(run.id, kind = null, title = null, text = "hello").getOrThrow()
    ExternalAgentSupervisor.companionDirective(run.id, "banner", buildJsonObject { put("text", "hi") }).getOrThrow()
    val dir = File(root, "myapp/tos/.companion")
    // Self-ignoring: the journal is daemon state inside the user's repo.
    assertEquals("*\n", File(dir, ".gitignore").readText())
    // Connect lifecycle + narration + directive, one full event object per line, in seq order -
    // the same stream `companion listen` prints, so a crashed agent replays from disk.
    val events = File(dir, "journal-${run.id}.jsonl").readLines()
      .map { JSON.decodeFromString(ExternalAgentEventDto.serializer(), it) }
    assertEquals(
      listOf(ExternalAgentEventKind.LIFECYCLE, ExternalAgentEventKind.ASSISTANT_MESSAGE, ExternalAgentEventKind.UI_COMMAND),
      events.map { it.kind },
    )
    assertEquals(listOf(0, 1, 2), events.map { it.seq })
  }

  @Test
  fun journalIsBestEffortAndRefusesToFollowAnEscapingSymlink() {
    val root = tempRoot()
    val outside = tempRoot()
    File(root, "myapp/tos").mkdirs()
    // An unwritable journal dir (path occupied by a regular file) must not break narration.
    File(root, "myapp/tos/.companion").writeText("in the way")
    val blocked = connect(root, CompanionConnectRequest(folder = "myapp/tos"))
    ExternalAgentSupervisor.companionEvent(blocked.id, kind = null, title = null, text = "still lands").getOrThrow()
    assertTrue(ExternalAgentSupervisor.events(blocked.id).orEmpty().any { it.text == "still lands" })
    // A symlink swapped in after connect must not route journal writes outside the root - the
    // same containment discipline as the save path, re-checked per append.
    File(root, "myapp/tos").deleteRecursively()
    File(root, "myapp/tos").mkdirs()
    val run = connect(root, CompanionConnectRequest(folder = "myapp/tos"))
    File(root, "myapp/tos").deleteRecursively()
    Files.createSymbolicLink(File(root, "myapp/tos").toPath(), outside.toPath())
    ExternalAgentSupervisor.companionEvent(run.id, kind = null, title = null, text = "after swap").getOrThrow()
    assertFalse(File(outside, ".companion").exists())
  }

  @Test
  fun replyIsRefusedOnACompanionRun() = runBlocking {
    val run = connect(tempRoot())
    val result = ExternalAgentSupervisor.reply(run.id, "hello?")
    assertTrue(result.isFailure)
    assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("external agent"))
  }

  @Test
  fun saveRecordingFansOutToRunningSiblingsOnTheSameFolder() {
    val root = tempRoot()
    File(root, "myapp/tos").mkdirs()
    File(root, "other").mkdirs()
    val saver = connect(root, CompanionConnectRequest(folder = "myapp/tos"))
    val sibling = connect(root, CompanionConnectRequest(folder = "myapp/tos"))
    val elsewhere = connect(root, CompanionConnectRequest(folder = "other"))
    val ended = connect(root, CompanionConnectRequest(folder = "myapp/tos"))
    ExternalAgentSupervisor.disconnectCompanion(ended.id, note = null).getOrThrow()
    val endedCount = ExternalAgentSupervisor.events(ended.id).orEmpty().size

    ExternalAgentSupervisor.companionSaveRecording(saver.id, "iOS", "- config:\n", root, platform = "iOS").getOrThrow()
    // The sibling hears the same receipt the saver got, carrying the same input fields.
    val heard = ExternalAgentSupervisor.events(sibling.id).orEmpty().first { it.title == "recording-saved" }
    assertEquals(ExternalAgentEventKind.HUMAN_ACTION, heard.kind)
    assertTrue(heard.input.orEmpty().contains("ios.trail.yaml"))
    assertTrue(heard.input.orEmpty().contains("\"platform\":\"ios\""))
    // The saver is excluded from the fan-out - exactly one receipt, not a duplicate.
    assertEquals(1, ExternalAgentSupervisor.events(saver.id).orEmpty().count { it.title == "recording-saved" })
    // A different folder and an ended session hear nothing.
    assertTrue(ExternalAgentSupervisor.events(elsewhere.id).orEmpty().none { it.title == "recording-saved" })
    assertEquals(endedCount, ExternalAgentSupervisor.events(ended.id).orEmpty().size)
  }

  @Test
  fun aSiblingSaveRetractsAMatchingArmedRecordingOnly() {
    val root = tempRoot()
    File(root, "myapp/tos").mkdirs()
    val saver = connect(root, CompanionConnectRequest(folder = "myapp/tos"))
    val sibling = connect(root, CompanionConnectRequest(folder = "myapp/tos"))
    // Slugs must match across raw spellings: "iOS Phone" armed, "ios-phone" saved.
    ExternalAgentSupervisor.companionDirective(sibling.id, "arm-recording", buildJsonObject { put("variant", "iOS Phone") }).getOrThrow()
    ExternalAgentSupervisor.companionSaveRecording(saver.id, "ios-phone", "- config:\n", root).getOrThrow()
    assertTrue(ExternalAgentSupervisor.run(sibling.id)?.companion?.directives.orEmpty().isEmpty())
    // A save of some OTHER variant doesn't answer the sibling's ask - the card must stay up.
    ExternalAgentSupervisor.companionDirective(sibling.id, "arm-recording", buildJsonObject { put("variant", "android") }).getOrThrow()
    ExternalAgentSupervisor.companionSaveRecording(saver.id, "ios", "- config:\n", root).getOrThrow()
    assertEquals(setOf("arm-recording"), ExternalAgentSupervisor.run(sibling.id)?.companion?.directives.orEmpty().keys)
  }

  @Test
  fun boardRecordingsAnnounceToEveryRunningSessionOnTheFolder() {
    val root = tempRoot()
    File(root, "myapp/tos").mkdirs()
    val a = connect(root, CompanionConnectRequest(folder = "myapp/tos"))
    val b = connect(root, CompanionConnectRequest(folder = "myapp/tos"))
    // The board record flow has no companion run to exclude - both sessions hear the write.
    ExternalAgentSupervisor.announceRecordingSavedForFolder("myapp/tos", "android.trail.yaml", platform = "android")
    for (run in listOf(a, b)) {
      val heard = ExternalAgentSupervisor.events(run.id).orEmpty().first { it.title == "recording-saved" }
      assertTrue(heard.input.orEmpty().contains("android.trail.yaml"))
    }
  }

  @Test
  fun runLifecycleAnnouncesToSessionsWhoseFolderContainsTheTrail() {
    val root = tempRoot()
    File(root, "myapp/tos").mkdirs()
    File(root, "my").mkdirs()
    File(root, "other").mkdirs()
    val exact = connect(root, CompanionConnectRequest(folder = "myapp/tos"))
    val parent = connect(root, CompanionConnectRequest(folder = "myapp"))
    // "my" is a prefix of "myapp" but not a containing folder - segment boundaries matter.
    val prefixTrap = connect(root, CompanionConnectRequest(folder = "my"))
    val elsewhere = connect(root, CompanionConnectRequest(folder = "other"))
    val ended = connect(root, CompanionConnectRequest(folder = "myapp/tos"))
    ExternalAgentSupervisor.disconnectCompanion(ended.id, note = null).getOrThrow()
    val endedCount = ExternalAgentSupervisor.events(ended.id).orEmpty().size

    ExternalAgentSupervisor.announceRunStatusForFolder("myapp/tos/ios.trail.yaml", started = true, sessionId = "recording_1")
    ExternalAgentSupervisor.announceRunStatusForFolder("myapp/tos", started = false, sessionId = "recording_1", status = "succeeded")
    for (run in listOf(exact, parent)) {
      val started = ExternalAgentSupervisor.events(run.id).orEmpty().first { it.title == "run-started" }
      assertEquals(ExternalAgentEventKind.LIFECYCLE, started.kind)
      assertTrue(started.input.orEmpty().contains("\"sessionId\":\"recording_1\""))
      // status is a run-finished field only; a started event carrying one would let agents
      // misread a start as an outcome.
      assertFalse(started.input.orEmpty().contains("\"status\""))
      val finished = ExternalAgentSupervisor.events(run.id).orEmpty().first { it.title == "run-finished" }
      assertTrue(finished.input.orEmpty().contains("\"status\":\"succeeded\""))
    }
    // The event names the listener's own folder, not the run's full path.
    val parentStarted = ExternalAgentSupervisor.events(parent.id).orEmpty().first { it.title == "run-started" }
    assertTrue(parentStarted.input.orEmpty().contains("\"folder\":\"myapp\""))
    for (run in listOf(prefixTrap, elsewhere)) {
      assertTrue(ExternalAgentSupervisor.events(run.id).orEmpty().none { it.title == "run-started" || it.title == "run-finished" })
    }
    // An ended session on the matching folder hears nothing either.
    assertEquals(endedCount, ExternalAgentSupervisor.events(ended.id).orEmpty().size)
  }

  @Test
  fun staleJournalsAreSweptAtConnectAndFreshOnesSurvive() {
    val root = tempRoot()
    val dir = File(root, "myapp/tos/.companion")
    dir.mkdirs()
    val stale = File(dir, "journal-dead-run.jsonl").apply { writeText("{}\n") }
    stale.setLastModified(System.currentTimeMillis() - 8L * 24 * 60 * 60 * 1000)
    val fresh = File(dir, "journal-live-run.jsonl").apply { writeText("{}\n") }
    // Old but not a journal: the sweep must only ever touch journal-*.jsonl.
    val bystander = File(dir, "notes.txt").apply { writeText("keep me") }
    bystander.setLastModified(System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000)

    connect(root, CompanionConnectRequest(folder = "myapp/tos"))
    assertFalse(stale.exists())
    assertTrue(fresh.exists())
    assertTrue(bystander.exists())
  }

  // ─── M4: the shared-brain request queue ───

  /** Connects a companion on [folder] and tags it as having a listening agent stream. */
  private fun connectListening(root: File, folder: String?): ExternalAgentRunDto {
    val run = connect(root, CompanionConnectRequest(folder = folder))
    ExternalAgentSupervisor.addAgentConsumer(run.id)
    listeningIds += run.id
    return run
  }

  private val listeningIds = mutableListOf<String>()

  @AfterTest
  fun dropConsumers() {
    listeningIds.forEach { ExternalAgentSupervisor.removeAgentConsumer(it) }
    listeningIds.clear()
  }

  @Test
  fun deferIsNoneWithoutAMatchingCompanionAndBystandersHearNothing() {
    val root = tempRoot()
    val elsewhere = connectListening(root, "other")
    // A companion whose folder merely PREFIXES the ask's path must not match either.
    val prefixTrap = connectListening(root, "my")
    val outcome = ExternalAgentSupervisor.deferToCompanion("review-trail", "myapp/tos", buildJsonObject { put("sessionId", "s1") })
    assertEquals(DeferOutcome.None, outcome)
    for (run in listOf(elsewhere, prefixTrap)) {
      assertTrue(ExternalAgentSupervisor.events(run.id).orEmpty().none { it.title == "agent-request" })
      assertTrue(ExternalAgentSupervisor.run(run.id)?.companion?.requests.orEmpty().isEmpty())
    }
  }

  @Test
  fun deferIsDegradedWhenTheMatchedSessionHasNoListeningAgent() {
    val root = tempRoot()
    val run = connect(root, CompanionConnectRequest(folder = "myapp/tos"))
    val outcome = ExternalAgentSupervisor.deferToCompanion("review-trail", "myapp/tos", buildJsonObject { put("sessionId", "s1") })
    assertEquals(DeferOutcome.Degraded(run.id), outcome)
    // Degraded queues nothing: the human was told to ask in the CLI; a silent pending entry would
    // spin the UI forever.
    assertTrue(ExternalAgentSupervisor.events(run.id).orEmpty().none { it.title == "agent-request" })
    assertTrue(ExternalAgentSupervisor.run(run.id)?.companion?.requests.orEmpty().isEmpty())
  }

  @Test
  fun deferEnqueuesOneAgentRequestCarryingItsRequestIdAndThePendingEntry() {
    val root = tempRoot()
    val run = connectListening(root, "myapp/tos")
    val outcome = ExternalAgentSupervisor.deferToCompanion(
      "review-trail",
      "myapp/tos",
      buildJsonObject {
        put("folder", "myapp/tos")
        put("sessionId", "s1")
      },
    )
    val deferred = outcome as DeferOutcome.Deferred
    assertEquals(run.id, deferred.runId)
    assertEquals("r_1", deferred.requestId)

    val asks = ExternalAgentSupervisor.events(run.id).orEmpty().filter { it.title == "agent-request" }
    assertEquals(1, asks.size)
    assertEquals(ExternalAgentEventKind.HUMAN_ACTION, asks.single().kind)
    // The requestId rides INSIDE the event input - the agent answers off this one frame.
    assertTrue(asks.single().input.orEmpty().contains("\"requestId\":\"r_1\""))
    assertTrue(asks.single().input.orEmpty().contains("\"kind\":\"review-trail\""))
    assertTrue(asks.single().input.orEmpty().contains("\"sessionId\":\"s1\""))

    // The queue entry the UI polls.
    val entry = ExternalAgentSupervisor.run(run.id)?.companion?.requests?.get("r_1")!!
    assertEquals("pending", entry.status)
    assertEquals("review-trail", entry.kind)
  }

  @Test
  fun anIdenticalPendingAskDedupsToTheSameRequestId() {
    val root = tempRoot()
    val run = connectListening(root, "myapp/tos")
    val payload = buildJsonObject { put("sessionId", "s1") }
    val first = ExternalAgentSupervisor.deferToCompanion("review-trail", "myapp/tos", payload) as DeferOutcome.Deferred
    val again = ExternalAgentSupervisor.deferToCompanion("review-trail", "myapp/tos", payload) as DeferOutcome.Deferred
    assertEquals(first.requestId, again.requestId)
    assertEquals(1, ExternalAgentSupervisor.events(run.id).orEmpty().count { it.title == "agent-request" })
    // A different payload is a different ask; so is the same payload once the first is settled.
    val other = ExternalAgentSupervisor.deferToCompanion("review-trail", "myapp/tos", buildJsonObject { put("sessionId", "s2") }) as DeferOutcome.Deferred
    assertEquals("r_2", other.requestId)
    ExternalAgentSupervisor.companionRespond(run.id, first.requestId, "done", note = null).getOrThrow()
    val fresh = ExternalAgentSupervisor.deferToCompanion("review-trail", "myapp/tos", payload) as DeferOutcome.Deferred
    assertEquals("r_3", fresh.requestId)
  }

  @Test
  fun deferTargetsTheNewestSessionWhoseFolderContainsThePath() {
    val root = tempRoot()
    val older = connectListening(root, "myapp")
    Thread.sleep(5) // startedAtMs is the tiebreak; make the two distinct.
    val newer = connectListening(root, "myapp")
    val outcome = ExternalAgentSupervisor.deferToCompanion("review-trail", "myapp/tos", buildJsonObject { put("sessionId", "s1") }) as DeferOutcome.Deferred
    assertEquals(newer.id, outcome.runId)
    assertTrue(ExternalAgentSupervisor.events(older.id).orEmpty().none { it.title == "agent-request" })
  }

  @Test
  fun aFolderlessAskFallsBackToTheSoleCompanionOnly() {
    val root = tempRoot()
    val sole = connectListening(root, "myapp/tos")
    val outcome = ExternalAgentSupervisor.deferToCompanion("propose-steps", folderRel = null, payload = buildJsonObject { put("objective", "log in") })
    assertEquals(sole.id, (outcome as DeferOutcome.Deferred).runId)
    // Two live companions make the folderless ask ambiguous - don't guess.
    connectListening(root, "other")
    val ambiguous = ExternalAgentSupervisor.deferToCompanion("propose-steps", folderRel = null, payload = buildJsonObject { put("objective", "log out") })
    assertEquals(DeferOutcome.None, ambiguous)
  }

  @Test
  fun respondSettlesThePendingEntryAndEmitsTheReceiptExactlyOnce() {
    val root = tempRoot()
    val run = connectListening(root, "myapp/tos")
    val deferred = ExternalAgentSupervisor.deferToCompanion("review-trail", "myapp/tos", buildJsonObject { put("sessionId", "s1") }) as DeferOutcome.Deferred

    ExternalAgentSupervisor.companionRespond(run.id, deferred.requestId, "done", note = "added two assertions").getOrThrow()
    val entry = ExternalAgentSupervisor.run(run.id)?.companion?.requests?.get(deferred.requestId)!!
    assertEquals("done", entry.status)
    assertEquals("added two assertions", entry.note)
    val receipts = ExternalAgentSupervisor.events(run.id).orEmpty().filter { it.title == "request-responded" }
    assertEquals(1, receipts.size)
    assertEquals(ExternalAgentEventKind.LIFECYCLE, receipts.single().kind)
    assertTrue(receipts.single().input.orEmpty().contains("\"requestId\":\"${deferred.requestId}\""))
    assertTrue(receipts.single().input.orEmpty().contains("\"status\":\"done\""))
    assertTrue(receipts.single().input.orEmpty().contains("added two assertions"))

    // Settled means settled: a second respond, an unknown id, and a made-up status all refuse.
    assertTrue(ExternalAgentSupervisor.companionRespond(run.id, deferred.requestId, "error", note = null).isFailure)
    assertTrue(ExternalAgentSupervisor.companionRespond(run.id, "r_99", "done", note = null).isFailure)
    val second = ExternalAgentSupervisor.deferToCompanion("review-trail", "myapp/tos", buildJsonObject { put("sessionId", "s2") }) as DeferOutcome.Deferred
    assertTrue(ExternalAgentSupervisor.companionRespond(run.id, second.requestId, "maybe", note = null).isFailure)
    assertEquals("pending", ExternalAgentSupervisor.run(run.id)?.companion?.requests?.get(second.requestId)?.status)
  }

  @Test
  fun endingTheSessionCancelsPendingRequestsAndRefusesLateResponds() {
    val root = tempRoot()
    val run = connectListening(root, "myapp/tos")
    val deferred = ExternalAgentSupervisor.deferToCompanion("review-trail", "myapp/tos", buildJsonObject { put("sessionId", "s1") }) as DeferOutcome.Deferred
    val settled = ExternalAgentSupervisor.deferToCompanion("review-trail", "myapp/tos", buildJsonObject { put("sessionId", "s0") }) as DeferOutcome.Deferred
    ExternalAgentSupervisor.companionRespond(run.id, settled.requestId, "done", note = null).getOrThrow()

    ExternalAgentSupervisor.disconnectCompanion(run.id, note = null).getOrThrow()
    val requests = ExternalAgentSupervisor.run(run.id)?.companion?.requests.orEmpty()
    // Only the pending ask flips; an already-settled one keeps its real outcome.
    assertEquals("cancelled", requests[deferred.requestId]?.status)
    assertEquals("done", requests[settled.requestId]?.status)
    assertTrue(ExternalAgentSupervisor.companionRespond(run.id, deferred.requestId, "done", note = null).isFailure)
    // And an ended session never receives a new ask.
    assertEquals(
      DeferOutcome.None,
      ExternalAgentSupervisor.deferToCompanion("review-trail", "myapp/tos", buildJsonObject { put("sessionId", "s3") }),
    )
  }

  @Test
  fun theRequestTitlesStayUnforgeableThroughTheUserActionSurface() {
    val root = tempRoot()
    val run = connect(root, CompanionConnectRequest(folder = "myapp/tos"))
    // agent-request and request-responded are daemon receipts; the UI's reply surface must not
    // be able to mint either (same stance as recording-saved).
    assertTrue(ExternalAgentSupervisor.companionUserAction(run.id, "agent-request", null).isFailure)
    assertTrue(ExternalAgentSupervisor.companionUserAction(run.id, "request-responded", null).isFailure)
    // And the narration surface shares the LIFECYCLE kind with the daemon's receipts, so their
    // titles are refused there too - a posted event must not cosplay as a receipt.
    for (title in listOf("request-responded", "run-finished", "recording-saved")) {
      assertTrue(ExternalAgentSupervisor.companionEvent(run.id, kind = "lifecycle", title = title, text = null).isFailure, title)
    }
    // Ordinary narration titles stay open.
    ExternalAgentSupervisor.companionEvent(run.id, kind = "lifecycle", title = "Step recorded", text = null).getOrThrow()
  }
}
