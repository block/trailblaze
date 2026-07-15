package xyz.block.trailblaze.trailrunner

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * [ExternalAgentSupervisor.emitHumanAction] is how a human-driven action (a recorded gesture from
 * the interactive Record feature) gets mirrored into an external-agent conversation. It must go
 * through the same event path as every other emit (seq assignment, retention), work regardless of
 * run status, and no-op safely for an unknown run id.
 */
class ExternalAgentSupervisorTest {
  // The supervisor is a singleton: seeded runs must not leak into other tests' view of
  // `runs()` / prune behavior in the same JVM.
  private val seededIds = mutableListOf<String>()

  @AfterTest
  fun removeSeededRuns() {
    seededIds.forEach { ExternalAgentSupervisor.runs.remove(it) }
    seededIds.clear()
  }

  private fun seedRun(id: String, agentType: ExternalAgentType = ExternalAgentType.CLAUDE): MutableExternalAgentRun {
    val run = MutableExternalAgentRun(
      id = id,
      request = ExternalAgentRunRequest(agentType = agentType, prompt = "test run"),
      title = "Test run",
      prompt = "test run",
      cwd = File("."),
    )
    ExternalAgentSupervisor.runs[id] = run
    seededIds += id
    return run
  }

  @Test
  fun emitHumanActionAppendsAnEventAndReturnsTrue() {
    val id = "test-run-" + System.nanoTime()
    seedRun(id)
    val input = buildJsonObject { put("type", "tap") }
    val output = buildJsonObject { put("yaml", "- tools:\n    - tapOnPoint") }

    val ok = ExternalAgentSupervisor.emitHumanAction(runId = id, title = "Tap (10, 20)", input = input, output = output)

    assertTrue(ok)
    val events = assertNotNull(ExternalAgentSupervisor.events(id))
    val event = events.single()
    assertEquals(ExternalAgentEventKind.HUMAN_ACTION, event.kind)
    assertEquals("Tap (10, 20)", event.title)
    assertNotNull(event.input)
    assertNotNull(event.output)
  }

  @Test
  fun emitHumanActionSeqIsMonotonic() {
    val id = "test-run-" + System.nanoTime()
    seedRun(id)

    ExternalAgentSupervisor.emitHumanAction(runId = id, title = "Tap (1, 1)", input = null, output = null)
    ExternalAgentSupervisor.emitHumanAction(runId = id, title = "Tap (2, 2)", input = null, output = null)

    val events = assertNotNull(ExternalAgentSupervisor.events(id))
    assertEquals(2, events.size)
    assertEquals(events[0].seq + 1, events[1].seq)
  }

  @Test
  fun emitHumanActionOnUnknownRunReturnsFalse() {
    val ok = ExternalAgentSupervisor.emitHumanAction(
      runId = "does-not-exist-" + System.nanoTime(),
      title = "Tap (0, 0)",
      input = null,
      output = null,
    )

    assertFalse(ok)
  }

  // demonstrationPreamble is the bridge that lets the agent see what the human demonstrated:
  // its observable contract is that actions after the last user message ride into the next
  // reply (with their step YAML + evidence paths), and everything already seen does not.

  private fun humanActionEvent(runId: String, seq: Int, title: String, output: String?) = ExternalAgentEventDto(
    id = "$runId-$seq",
    runId = runId,
    seq = seq,
    timeMs = 0L,
    agentType = ExternalAgentType.CLAUDE,
    kind = ExternalAgentEventKind.HUMAN_ACTION,
    title = title,
    output = output,
  )

  private fun userMessageEvent(runId: String, seq: Int) = ExternalAgentEventDto(
    id = "$runId-$seq",
    runId = runId,
    seq = seq,
    timeMs = 0L,
    agentType = ExternalAgentType.CLAUDE,
    kind = ExternalAgentEventKind.USER_MESSAGE,
    title = "You",
    text = "earlier message",
  )

  @Test
  fun demonstrationPreambleIsEmptyWithoutNewHumanActions() {
    val id = "run"
    assertEquals("", demonstrationPreamble(emptyList()))
    // An action already delivered (before the last user message) must not ride again.
    val alreadySeen = listOf(
      humanActionEvent(id, 0, "Tap (1, 1)", output = null),
      userMessageEvent(id, 1),
    )
    assertEquals("", demonstrationPreamble(alreadySeen))
  }

  @Test
  fun demonstrationPreambleCarriesActionsAfterTheLastUserMessage() {
    val id = "run"
    val output = buildJsonObject {
      put("yaml", "- tools:\n    - tapOnPoint:\n        x: 10\n        y: 20")
      put(
        "element",
        buildJsonObject {
          put("label", "Next")
          put("type", "Button")
        },
      )
      put(
        "evidence",
        buildJsonObject {
          put("dir", "/tmp/tape")
          put("before", buildJsonObject { put("screenshot", "1-before.png"); put("hierarchy", "1-before-hierarchy.txt") })
          put("after", buildJsonObject { put("screenshot", "1-after.png"); put("hierarchy", "1-after-hierarchy.txt") })
          put("screenChanged", true)
        },
      )
    }.toString()
    val events = listOf(
      humanActionEvent(id, 0, "Tap (0, 0)", output = null),
      userMessageEvent(id, 1),
      humanActionEvent(id, 2, "Tap (10, 20)", output = output),
    )

    val preamble = demonstrationPreamble(events)

    // Only the post-message action is carried, with its identity, step YAML, and evidence paths.
    assertFalse(preamble.contains("Tap (0, 0)"))
    assertTrue(preamble.contains("Tap (10, 20)"))
    assertTrue(preamble.contains("\"Next\""))
    assertTrue(preamble.contains("tapOnPoint"))
    assertTrue(preamble.contains("/tmp/tape/1-after.png"))
    assertTrue(preamble.contains("/tmp/tape/1-after-hierarchy.txt"))
  }

  @Test
  fun demonstrationPreambleFlagsAnActionThatDidNotChangeTheScreen() {
    val id = "run"
    val output = buildJsonObject {
      put(
        "evidence",
        buildJsonObject {
          put("dir", "/tmp/tape")
          put("screenChanged", false)
        },
      )
    }.toString()
    val events = listOf(humanActionEvent(id, 0, "Tap (5, 5)", output = output))

    val preamble = demonstrationPreamble(events)

    assertTrue(preamble.contains("did NOT change"))
  }

  @Test
  fun demonstrationPreambleCapsARunawayActionListAndSaysSo() {
    // The preamble rides the child's argv (hard OS limit): hundreds of demonstrated actions must
    // not fail the exec and lose the whole demonstration. The most recent actions win.
    val events = (1..60).map { humanActionEvent("run", it, "Tap ($it, $it)", output = null) }

    val preamble = demonstrationPreamble(events)

    assertTrue(preamble.contains("60 actions"))
    assertTrue(preamble.contains("Tap (60, 60)"))
    assertFalse(preamble.contains("Tap (1, 1)\n"))
    assertTrue(preamble.contains("were dropped"))
  }

  // Evidence jobs finish in data-dependent time (settle + capture), but their HUMAN_ACTION
  // events must land in gesture order - and a reply must be able to drain in-flight jobs so the
  // action the human just performed rides that reply's preamble.

  @Test
  fun evidenceJobsCompleteInEnqueueOrderEvenWhenTheFirstIsSlower() = runBlocking {
    val id = "evidence-order-" + System.nanoTime()
    val scope = CoroutineScope(Dispatchers.IO)
    val order = Collections.synchronizedList(mutableListOf<Int>())

    ExternalAgentSupervisor.enqueueEvidence(id, scope) { delay(150); order += 1 }
    ExternalAgentSupervisor.enqueueEvidence(id, scope) { order += 2 }
    ExternalAgentSupervisor.awaitPendingEvidence(id)

    assertEquals(listOf(1, 2), order.toList())
  }

  @Test
  fun awaitPendingEvidenceReturnsImmediatelyWhenNothingIsInFlight() = runBlocking {
    ExternalAgentSupervisor.awaitPendingEvidence("no-such-run-" + System.nanoTime())
  }

  @Test
  fun evidenceChainSurvivesAFailingJob() = runBlocking {
    val id = "evidence-fail-" + System.nanoTime()
    val scope = CoroutineScope(Dispatchers.IO)
    val order = Collections.synchronizedList(mutableListOf<Int>())

    ExternalAgentSupervisor.enqueueEvidence(id, scope) { error("capture blew up") }
    ExternalAgentSupervisor.enqueueEvidence(id, scope) { order += 2 }
    ExternalAgentSupervisor.awaitPendingEvidence(id)

    assertEquals(listOf(2), order.toList())
  }

  // A solo session is the agentless Create path: start() must produce a live run without any
  // vendor CLI installed (no executable check, no process, prompt optional), born finished so
  // nothing shows a phantom in-flight turn - while human actions still record into it and a
  // reply (there is no agent to reply to) is rejected.

  @Test
  fun soloStartCreatesAFinishedRunWithoutAnExecutable() {
    val run = ExternalAgentSupervisor.start(
      request = ExternalAgentRunRequest(agentType = ExternalAgentType.SOLO, prompt = ""),
      fallbackCwd = File("."),
    ).getOrThrow()
    seededIds += run.id

    assertEquals(ExternalAgentType.SOLO, run.agentType)
    assertEquals(ExternalAgentSessionStatus.COMPLETED, run.status)
    assertNotNull(run.endedAtMs)
    assertEquals("Solo session", run.title)
    // Recording works on the solo run exactly as on an agent run.
    assertTrue(ExternalAgentSupervisor.emitHumanAction(runId = run.id, title = "Tap (3, 4)", input = null, output = null))
    val kinds = assertNotNull(ExternalAgentSupervisor.events(run.id)).map { it.kind }
    assertTrue(ExternalAgentEventKind.HUMAN_ACTION in kinds)
  }

  @Test
  fun soloRunRejectsReplies() = runBlocking {
    val run = ExternalAgentSupervisor.start(
      request = ExternalAgentRunRequest(agentType = ExternalAgentType.SOLO, prompt = ""),
      fallbackCwd = File("."),
    ).getOrThrow()
    seededIds += run.id

    val result = ExternalAgentSupervisor.reply(run.id, "hello?")

    assertTrue(result.isFailure)
  }

  // A finished CLI turn cannot resume itself, so a final message promising to wait/continue is a
  // broken promise the UI must backstop with a nudge. The detector is conservative: it needs BOTH
  // a first-person continue/wait promise AND a concrete duration.

  @Test
  fun detectWaitPromiseFiresOnPromisePlusDuration() {
    assertTrue(detectWaitPromise("The run is going. I'll check back in 30 seconds with the result."))
    assertTrue(detectWaitPromise("I’ll wait ~2 minutes for the recording to finish, then verify."))
    assertTrue(detectWaitPromise("The replay takes a while; it will continue automatically and I'll retry in 45s."))
  }

  @Test
  fun detectWaitPromiseStaysQuietWithoutBothSignals() {
    // Duration but no promise: a factual statement about elapsed time.
    assertFalse(detectWaitPromise("The trail ran green in 42 seconds."))
    // Promise phrasing but no duration: "I'll continue" with the work done in-turn.
    assertFalse(detectWaitPromise("I'll continue by saving the trail now. Done - it's at trails/example."))
    // Neither, and the null/blank edges.
    assertFalse(detectWaitPromise("Saved the trail. Reply if you want changes."))
    assertFalse(detectWaitPromise(""))
    assertFalse(detectWaitPromise(null))
  }

  @Test
  fun mayHoldDeviceWorkTracksOpenToolCalls() {
    val id = "test-run-" + System.nanoTime()
    val run = seedRun(id)
    // Idle conversation (no tool in flight): stopping it must not touch the device.
    assertFalse(ExternalAgentSupervisor.mayHoldDeviceWork(id))

    run.emit(kind = ExternalAgentEventKind.TOOL_CALL, toolName = "mcp__trailblaze__trail", toolCallId = "call-1")
    assertTrue(ExternalAgentSupervisor.mayHoldDeviceWork(id))

    run.emit(kind = ExternalAgentEventKind.TOOL_RESULT, toolCallId = "call-1")
    assertFalse(ExternalAgentSupervisor.mayHoldDeviceWork(id))
  }

  @Test
  fun mayHoldDeviceWorkIsAlwaysTrueForCodex() {
    // Codex MCP tool items don't parse into TOOL_CALL/TOOL_RESULT events, so in-flight device
    // work is invisible - the cancel must keep releasing the device unconditionally there.
    val id = "test-run-" + System.nanoTime()
    seedRun(id, agentType = ExternalAgentType.CODEX)
    assertTrue(ExternalAgentSupervisor.mayHoldDeviceWork(id))
  }

  @Test
  fun mayHoldDeviceWorkIsFalseForAnUnknownRun() {
    assertFalse(ExternalAgentSupervisor.mayHoldDeviceWork("no-such-run"))
  }
}
