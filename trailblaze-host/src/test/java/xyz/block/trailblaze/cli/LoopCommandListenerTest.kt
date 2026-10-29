package xyz.block.trailblaze.cli

import kotlinx.datetime.Instant
import picocli.CommandLine
import xyz.block.trailblaze.agent.model.AgentTaskStatus
import xyz.block.trailblaze.agent.model.AgentTaskStatusData
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.logs.model.TaskId
import xyz.block.trailblaze.yaml.DirectionStep
import xyz.block.trailblaze.yaml.PromptStep
import xyz.block.trailblaze.yaml.VerificationStep
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What a distribution's [LoopExtension] hears of a loop, across a failed session and its recovery. */
class LoopCommandListenerTest {

  private val trail = """
    config:
      id: sample/loop
      title: Loop sample

    trailhead:
      step: Launch signed in
      recording:
        android-phone:
          sample_launchSignedIn: {}

    trail:
      - step: Open the menu
      - verify: The menu is open
  """.trimIndent()

  private val trailhead = DirectionStep("Launch signed in", isTrailhead = true)
  private val open = DirectionStep("Open the menu")
  private val check = VerificationStep("The menu is open")
  private val trailFile = Files.createTempFile("loop", ".trail.yaml").toFile().apply { writeText(trail) }

  private val events = CopyOnWriteArrayList<String>()
  private val passes = CopyOnWriteArrayList<LoopPass>()
  private val starts = CopyOnWriteArrayList<LoopStart>()

  private inner class Recorder(
    private val misuse: String? = null,
    private val passFails: Boolean = false,
    private val listens: Boolean = true,
  ) : LoopExtension {
    /** A mixin is a picocli command spec, which needs at least one annotated member. */
    @CommandLine.Option(names = ["--recorder"], hidden = true)
    var recorder: Boolean = false

    override fun misuse() = misuse

    override fun startLoop(loop: LoopStart): LoopListener? {
      starts += loop
      if (!listens) return null
      return object : LoopListener {
        override fun passFinished(pass: LoopPass) {
          passes += pass
          events += "pass ${pass.index}"
          if (passFails) error("listener broke")
        }

        override fun loopFinished() {
          events += "finished"
        }
      }
    }
  }

  private fun loop(extension: LoopExtension?, iterations: Int = 5, pollMillis: Long = 3_600_000L): LoopCommand =
    LoopCommand().also { loop ->
      loop.iterations = iterations
      loop.trailFiles = listOf(trailFile)
      loop.passPollMillis = pollMillis
      // A real run registers the extension as a mixin, which is where the loop finds it.
      CommandLine(loop).apply { if (extension != null) addMixin("extension", extension) }
    }

  @Test
  fun `a listener hears each pass once with its index across a failed session and its recovery`() {
    val loop = loop(Recorder())
    loop.yamlToRun(trail)
    val failed = session(
      trailhead to Ok(0, 100),
      open to Ok(200, 300), check to Ok(310, 400),
      open to Ok(500, 600), check to Fail(610, 750, "The menu is closed"),
    )

    loop.sessionStarting(failed)
    assertTrue(loop.runAgain(TrailCommand.FinishedRun(passed = false, sessionDir = failed)))

    assertEquals(
      listOf(
        Triple(0, true, null),
        Triple(1, false, "Failed at \"The menu is open\": The menu is closed"),
      ),
      passes.map { Triple(it.index, it.ok, it.error) },
    )
    assertEquals(Instant.fromEpochMilliseconds(200) to Instant.fromEpochMilliseconds(400), passes[0].startedAt to passes[0].endedAt)
    assertEquals(Instant.fromEpochMilliseconds(500) to Instant.fromEpochMilliseconds(750), passes[1].startedAt to passes[1].endedAt)
    assertEquals(listOf(failed, failed), passes.map { it.sessionDir })
    assertFalse("finished" in events, "the loop is not over: a recovery session follows")

    // The recovery runs the 3 passes left, which the loop numbers on from the 2 it has used.
    val recovery = session(
      trailhead to Ok(1_000, 1_100),
      open to Ok(1_200, 1_300), check to Ok(1_310, 1_400),
      open to Ok(1_500, 1_600), check to Ok(1_610, 1_700),
      open to Ok(1_800, 1_900), check to Ok(1_910, 2_000),
    )
    loop.yamlToRun(trail)
    loop.sessionStarting(recovery)
    assertFalse(loop.runAgain(TrailCommand.FinishedRun(passed = true, sessionDir = recovery)))

    assertEquals(
      listOf(
        Triple(0, true, null), Triple(1, false, "Failed at \"The menu is open\": The menu is closed"),
        Triple(2, true, null), Triple(3, true, null), Triple(4, true, null),
      ),
      passes.map { Triple(it.index, it.ok, it.error) },
    )
    assertEquals(
      listOf(Instant.fromEpochMilliseconds(1_200), Instant.fromEpochMilliseconds(1_500), Instant.fromEpochMilliseconds(1_800)),
      passes.drop(2).map { it.startedAt },
    )
    assertEquals(listOf(recovery, recovery, recovery), passes.drop(2).map { it.sessionDir })
    assertEquals(
      listOf("pass 0", "pass 1", "pass 2", "pass 3", "pass 4", "finished"),
      events,
      "every pass is heard before the loop finishes, and it finishes once",
    )
  }

  @Test
  fun `with --reuse-trailhead a listener hears each pass, one session each, a failed one included`() {
    val loop = loop(Recorder(), iterations = 2).apply { reuseTrailhead = true }
    val failed = session(trailhead to Ok(0, 100), open to Ok(200, 300), check to Fail(310, 450, "The menu is closed"))
    val passed = session(trailhead to Ok(1_000, 1_100), open to Ok(1_200, 1_300), check to Ok(1_310, 1_400))

    loop.yamlToRun(trail)
    loop.sessionStarting(failed)
    assertTrue(loop.runAgain(TrailCommand.FinishedRun(passed = false, sessionDir = failed)))
    loop.yamlToRun(trail)
    loop.sessionStarting(passed)
    assertFalse(loop.runAgain(TrailCommand.FinishedRun(passed = true, sessionDir = passed)))

    assertEquals(
      listOf(Triple(0, false, "Failed at \"The menu is open\": The menu is closed"), Triple(1, true, null)),
      passes.map { Triple(it.index, it.ok, it.error) },
    )
    assertEquals(
      listOf(Instant.fromEpochMilliseconds(200) to Instant.fromEpochMilliseconds(450), Instant.fromEpochMilliseconds(1_200) to Instant.fromEpochMilliseconds(1_400)),
      passes.map { it.startedAt to it.endedAt },
    )
    assertEquals(listOf(failed, passed), passes.map { it.sessionDir })
    assertEquals(listOf("pass 0", "pass 1", "finished"), events)
  }

  @Test
  fun `the listener is started once for the loop, not for each of its sessions`() {
    val loop = loop(Recorder(), iterations = 3)
    loop.yamlToRun(trail)
    val failed = session(trailhead to Fail(0, 100, "App did not launch"))
    val recovery = session(trailhead to Ok(200, 300), open to Ok(400, 500), check to Ok(510, 600))

    loop.sessionStarting(failed)
    loop.runAgain(TrailCommand.FinishedRun(passed = false, sessionDir = failed))
    loop.yamlToRun(trail)
    loop.sessionStarting(recovery)
    loop.runAgain(TrailCommand.FinishedRun(passed = true, sessionDir = recovery))

    assertEquals(1, starts.size)
    assertEquals(trailFile, starts.single().trailFile)
    assertEquals(3, starts.single().iterations)
  }

  @Test
  fun `a listener is told whether the loop's sessions record anything`() {
    val recording = loop(Recorder())
    recording.yamlToRun(trail)
    recording.sessionStarting(session(trailhead to Ok(0, 100)))
    val bare = loop(Recorder()).apply {
      captureVideo = false
      captureLogcat = false
      captureIosLogs = false
      captureMemory = false
    }
    bare.yamlToRun(trail)
    bare.sessionStarting(session(trailhead to Ok(0, 100)))

    assertEquals(listOf(true, false), starts.map { it.captures })
  }

  @Test
  fun `a session that failed at its trailhead reports its failed pass from the trailhead`() {
    val loop = loop(Recorder(), iterations = 1)
    loop.yamlToRun(trail)
    val failed = session(trailhead to Fail(40, 100, "App did not launch"))

    loop.sessionStarting(failed)
    assertFalse(loop.runAgain(TrailCommand.FinishedRun(passed = false, sessionDir = failed)))

    val pass = passes.single()
    assertEquals(0, pass.index)
    assertFalse(pass.ok)
    assertEquals("Failed at the trailhead: App did not launch", pass.error)
    assertEquals(Instant.fromEpochMilliseconds(40) to Instant.fromEpochMilliseconds(100), pass.startedAt to pass.endedAt)
    assertEquals(listOf("pass 0", "finished"), events)
  }

  @Test
  fun `a session that failed at its end after its last step passed fails its last pass through to that end`() {
    val loop = loop(Recorder(), iterations = 1)
    loop.yamlToRun(trail)
    val failed = session(trailhead to Ok(0, 100), open to Ok(200, 300), check to Ok(310, 400)).endedFailed(900)

    loop.sessionStarting(failed)
    assertFalse(loop.runAgain(TrailCommand.FinishedRun(passed = false, sessionDir = failed)))

    val pass = passes.single()
    assertFalse(pass.ok)
    assertEquals(Instant.fromEpochMilliseconds(200) to Instant.fromEpochMilliseconds(900), pass.startedAt to pass.endedAt)
  }

  @Test
  fun `the watcher reports finished passes while the session runs, holding the last for the end of the session`() {
    val loop = loop(Recorder(), iterations = 3, pollMillis = 10)
    loop.yamlToRun(trail)
    val running = session(
      trailhead to Ok(0, 100),
      open to Ok(200, 300), check to Ok(310, 400),
      open to Ok(500, 600), check to Ok(610, 700),
      open to Ok(800, 900), check to Ok(910, 1_000),
    )

    loop.sessionStarting(running)
    // A hang guard, not a time budget: the watcher polls every 10ms.
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
    while (passes.size < 2 && System.nanoTime() < deadline) Thread.sleep(5)

    assertEquals(listOf(0, 1), passes.map { it.index })
    assertFalse(passes.any { it.index == 2 }, "the last pass waits for the session to end")

    assertFalse(loop.runAgain(TrailCommand.FinishedRun(passed = true, sessionDir = running)))

    assertEquals(listOf(0, 1, 2), passes.map { it.index }, "each pass is heard once, the watcher's included")
    assertEquals("finished", events.last())
    assertEquals(1, events.count { it == "finished" })
  }

  @Test
  fun `a run that comes back with no session still reports the passes the session finished`() {
    val loop = loop(Recorder(), iterations = 3)
    loop.yamlToRun(trail)
    val cancelled = session(
      trailhead to Ok(0, 100),
      open to Ok(200, 300), check to Ok(310, 400),
      open to Ok(500, 600), check to Ok(610, 700),
    )

    loop.sessionStarting(cancelled)
    assertFalse(loop.runAgain(null))

    assertEquals(listOf("pass 0", "pass 1", "finished"), events)
  }

  @Test
  fun `a run that comes back with no session after its last pass still reports that pass`() {
    val loop = loop(Recorder(), iterations = 1)
    loop.yamlToRun(trail)
    val cancelled = session(trailhead to Ok(0, 100), open to Ok(200, 300), check to Ok(310, 400))

    loop.sessionStarting(cancelled)
    assertFalse(loop.runAgain(null))

    assertEquals(listOf("pass 0", "finished"), events)
  }

  @Test
  fun `a listener interrupted mid-handoff can't keep the watcher from stopping`() {
    val handingOff = CountDownLatch(1)
    val extension = object : LoopExtension {
      @CommandLine.Option(names = ["--blocking"], hidden = true)
      var blocking: Boolean = false

      override fun startLoop(loop: LoopStart) = object : LoopListener {
        // A handoff to a full queue, which throws on interrupt and clears the thread's flag.
        override fun passFinished(pass: LoopPass) {
          handingOff.countDown()
          LinkedBlockingQueue<LoopPass>(1).apply { put(pass) }.put(pass)
        }

        override fun loopFinished() {}
      }
    }
    val loop = loop(extension, iterations = 3, pollMillis = 10)
    loop.yamlToRun(trail)
    val running = session(trailhead to Ok(0, 100), open to Ok(200, 300), check to Ok(310, 400))
    loop.sessionStarting(running)
    assertTrue(handingOff.await(60, TimeUnit.SECONDS))

    val finishing = thread { loop.runAgain(null) }
    finishing.join(TimeUnit.SECONDS.toMillis(60))

    assertFalse(finishing.isAlive, "the loop hung waiting for its watcher")
  }

  @Test
  fun `a watcher stopped mid-handoff doesn't block again on the next pass of the same poll`() {
    val handingOff = CountDownLatch(1)
    val extension = object : LoopExtension {
      @CommandLine.Option(names = ["--blocking"], hidden = true)
      var blocking: Boolean = false

      override fun startLoop(loop: LoopStart) = object : LoopListener {
        override fun passFinished(pass: LoopPass) {
          handingOff.countDown()
          LinkedBlockingQueue<LoopPass>(1).apply { put(pass) }.put(pass)
        }

        override fun loopFinished() {}
      }
    }
    val loop = loop(extension, iterations = 4, pollMillis = 10)
    loop.yamlToRun(trail)
    val running = session(
      trailhead to Ok(0, 100),
      open to Ok(200, 300), check to Ok(310, 400),
      open to Ok(500, 600), check to Ok(610, 700),
    )
    loop.sessionStarting(running)
    assertTrue(handingOff.await(60, TimeUnit.SECONDS))

    val finishing = thread { loop.runAgain(null) }
    finishing.join(TimeUnit.SECONDS.toMillis(60))

    assertFalse(finishing.isAlive, "the loop hung on the watcher's second handoff")
  }

  @Test
  fun `an extension that listens to nothing leaves the loop as it was`() {
    val loop = loop(Recorder(listens = false), iterations = 2)
    loop.yamlToRun(trail)
    val done = session(trailhead to Ok(0, 100), open to Ok(200, 300), check to Ok(310, 400), open to Ok(500, 600), check to Ok(610, 700))

    loop.sessionStarting(done)

    assertFalse(loop.runAgain(TrailCommand.FinishedRun(passed = true, sessionDir = done)))
    assertEquals(emptyList(), events)
  }

  @Test
  fun `a listener that throws neither stops the others nor the loop`() {
    val loop = loop(Recorder(passFails = true), iterations = 2)
    loop.yamlToRun(trail)
    val done = session(trailhead to Ok(0, 100), open to Ok(200, 300), check to Ok(310, 400), open to Ok(500, 600), check to Ok(610, 700))

    loop.sessionStarting(done)
    assertFalse(loop.runAgain(TrailCommand.FinishedRun(passed = true, sessionDir = done)))

    assertEquals(listOf("pass 0", "pass 1", "finished"), events)
  }

  @Test
  fun `an extension that finds its options unusable rejects the loop before any session`() {
    val loop = loop(Recorder(misuse = "--upload needs a workspace"))
    var ran = false
    loop.runTrails = { ran = true; 0 }

    val (exit, stderr) = captureStderr { loop.call() }

    assertEquals(TrailblazeExitCode.MISUSE.code, exit)
    assertTrue(stderr.lines().any { it.contains("--upload needs a workspace") }, "got: <<$stderr>>")
    assertFalse(ran)
    assertNull(starts.firstOrNull())
  }

  private sealed interface Step
  private data class Ok(val startMs: Long, val endMs: Long) : Step
  private data class Fail(val startMs: Long, val endMs: Long, val why: String) : Step

  /** A session directory holding a start and a complete log per step, stamped at the given host times. */
  private fun session(vararg steps: Pair<PromptStep, Step>): File {
    val dir = Files.createTempDirectory("loop-session").toFile()
    var n = 0
    fun write(kind: String, log: TrailblazeLog) {
      File(dir, "%03d_%s.json".format(++n, kind)).writeText(TrailblazeJsonInstance.encodeToString(TrailblazeLog.serializer(), log))
    }
    for ((step, outcome) in steps) {
      val (startMs, endMs, why) = when (outcome) {
        is Ok -> Triple(outcome.startMs, outcome.endMs, null)
        is Fail -> Triple(outcome.startMs, outcome.endMs, outcome.why)
      }
      write("ObjectiveStartLog", TrailblazeLog.ObjectiveStartLog(step, SessionId("loop-session"), Instant.fromEpochMilliseconds(startMs)))
      val end = Instant.fromEpochMilliseconds(endMs)
      val data = AgentTaskStatusData(TaskId.generate(), step.prompt, 1, end, 1)
      write(
        "ObjectiveCompleteLog",
        TrailblazeLog.ObjectiveCompleteLog(
          promptStep = step,
          objectiveResult = if (why == null) {
            AgentTaskStatus.Success.ObjectiveComplete(data, llmExplanation = "Done")
          } else {
            AgentTaskStatus.Failure.ObjectiveFailed(data, llmExplanation = why)
          },
          session = SessionId("loop-session"),
          timestamp = end,
        ),
      )
    }
    return dir
  }

  /** Records the session's failed end at [atMs], after the logs it already has. */
  private fun File.endedFailed(atMs: Long): File = also { dir ->
    val log = TrailblazeLog.TrailblazeSessionStatusChangeLog(
      sessionStatus = SessionStatus.Ended.Failed(durationMs = atMs, exceptionMessage = "Capture failed to finalize"),
      session = SessionId("loop-session"),
      timestamp = Instant.fromEpochMilliseconds(atMs),
    )
    File(dir, "%03d_TrailblazeSessionStatusChangeLog.json".format(dir.list()!!.size + 1))
      .writeText(TrailblazeJsonInstance.encodeToString(TrailblazeLog.serializer(), log))
  }
}
