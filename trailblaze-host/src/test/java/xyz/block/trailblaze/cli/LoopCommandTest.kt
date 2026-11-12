package xyz.block.trailblaze.cli

import xyz.block.trailblaze.agent.model.PromptRecordingResult
import xyz.block.trailblaze.logs.client.temp.OtherTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.yaml.TrailblazeToolYamlWrapper
import kotlinx.datetime.Instant
import picocli.CommandLine
import xyz.block.trailblaze.agent.model.AgentTaskStatus
import xyz.block.trailblaze.agent.model.AgentTaskStatusData
import xyz.block.trailblaze.llm.OnDeviceRpcTimeouts
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.logs.model.TaskId
import xyz.block.trailblaze.ui.JvmLiveSessionDataProvider.Companion.MANUAL_CANCELLATION_MESSAGE
import xyz.block.trailblaze.yaml.DirectionStep
import xyz.block.trailblaze.yaml.PromptStep
import xyz.block.trailblaze.yaml.VerificationStep
import xyz.block.trailblaze.yaml.createTrailblazeYaml
import xyz.block.trailblaze.util.Console
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.PrintStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class LoopCommandTest {

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
        recording:
          android-phone:
            - tapOnElementBySelector:
                nodeSelector:
                  androidAccessibility:
                    textRegex: '(?is).*\QMenu\E.*'
      - verify: The menu is open
  """.trimIndent()

  @Test
  fun `the looped trail runs the trailhead once, then every step once per iteration`() {
    val yaml = createTrailblazeYaml()
    val original = yaml.decodeUnifiedTrail(trail)
    val looped = yaml.decodeUnifiedTrail(LoopCommand.repeatSteps(trail, iterations = 3))

    assertNotNull(looped.trailhead)
    assertEquals(original.trailhead, looped.trailhead)
    assertEquals(original.config, looped.config)
    assertEquals(original.trail + original.trail + original.trail, looped.trail)
  }

  @Test
  fun `a trail that does not decode is sent unchanged so the runner reports it`() {
    val notATrail = "- prompts:\n    - step: legacy list shape\n"

    assertEquals(notATrail, LoopCommand.repeatSteps(notATrail, iterations = 3))
  }

  @Test
  fun `loop rejects what it cannot loop before touching a device or daemon`() {
    val dir = Files.createTempDirectory("loop-test").toFile()
    val first = File(dir, "a.trail.yaml").apply { writeText(trail) }
    val second = File(dir, "b.trail.yaml").apply { writeText(trail) }
    val stepless = File(dir, "c.trail.yaml").apply { writeText("config:\n  id: sample/stepless\n") }

    val cases = mapOf(
      listOf(first.path, second.path, "-n", "2") to "loop takes exactly one trail file",
      listOf(dir.path, "-n", "2") to "loop takes exactly one trail file",
      listOf(first.path, "-n", "2", "--save-recording") to "loop never saves a recording",
      listOf(first.path, "-n", "2", "--no-logging") to "loop counts passes from session logs, so it needs logging",
      listOf(first.path, "-n", "2", "--snapshot-baseline", "base.zip") to
        "loop can't check a snapshot baseline: it would compare only the first pass's snapshots",
      listOf(stepless.path, "-n", "2") to "loop repeats a trail's `trail:` steps, and c.trail.yaml has none",
      listOf(first.path, "-n", "0") to "--iterations must be a positive integer (got 0)",
      listOf(first.path, "-n", "2", "--stop-after-failures", "-1") to
        "--stop-after-failures must be 0 or a positive integer (got -1)",
      listOf(first.path, "-n", "2", "--reuse-trailhead", "--same-session") to
        "--reuse-trailhead hands a setup from one session to the next, so it can't run with --same-session",
    )
    for ((args, reason) in cases) {
      val (exit, stderr) = captureStderr { root().execute("loop", "--verbose", *args.toTypedArray()) }
      assertEquals(TrailblazeExitCode.MISUSE.code, exit, "trailblaze loop ${args.joinToString(" ")}")
      assertTrue(stderr.lines().any { it == "  reason: $reason" }, "expected `$reason`, got: <<$stderr>>")
    }
  }

  @Test
  fun `loop rejects a trail with no trail steps it can read`() {
    val legacy = Files.createTempFile("legacy", ".trail.yaml").toFile().apply {
      writeText("- prompts:\n    - step: legacy list shape\n")
    }

    val (exit, stderr) = captureStderr { root().execute("loop", "--verbose", legacy.path, "-n", "2") }

    assertEquals(TrailblazeExitCode.MISUSE.code, exit)
    assertTrue(
      stderr.lines().any { it.startsWith("  reason: loop repeats a trail's `trail:` steps, and can't read them: ") },
      "expected an unreadable-steps rejection, got: <<$stderr>>",
    )
  }

  @Test
  fun `loop rejects a snapshot baseline set in its environment`() {
    val file = Files.createTempFile("loop", ".trail.yaml").toFile().apply { writeText(trail) }
    val loop = LoopCommand().apply {
      sameSession = true
      iterations = 2
      trailFiles = listOf(file)
      envReader = { name -> "base.zip".takeIf { name == "TRAILBLAZE_SNAPSHOT_BASELINE" } }
    }

    val (exit, stderr) = captureStderr { loop.call() }

    assertEquals(TrailblazeExitCode.MISUSE.code, exit)
    assertTrue(
      stderr.lines().any { it == "  reason: loop can't check a snapshot baseline: it would compare only the first pass's snapshots" },
      "expected a snapshot-baseline rejection, got: <<$stderr>>",
    )
  }

  @Test
  fun `loop requires an iteration count, a time limit, or both`() {
    val file = Files.createTempFile("loop", ".trail.yaml").toFile().apply { writeText(trail) }

    for ((args, reason) in mapOf(
      listOf(file.path) to "loop needs --iterations, --time-limit, or both",
      listOf(file.path, "--time-limit", "soon") to
        "--time-limit must be a positive duration in hours, minutes and seconds, such as 8h, 90m or 1h30m (got soon)",
    )) {
      val (exit, stderr) = captureStderr { root().execute("loop", "--verbose", *args.toTypedArray()) }
      assertEquals(TrailblazeExitCode.MISUSE.code, exit, "trailblaze loop ${args.joinToString(" ")}")
      assertTrue(stderr.lines().any { it == "  reason: $reason" }, "expected `$reason`, got: <<$stderr>>")
    }
  }

  @Test
  fun `a time limit is hours, minutes and seconds`() {
    assertEquals(8.hours, LoopCommand.parseTimeLimit("8h"))
    assertEquals(90.minutes, LoopCommand.parseTimeLimit("90m"))
    assertEquals(90.minutes, LoopCommand.parseTimeLimit("1h30m"))
    assertEquals(45.seconds, LoopCommand.parseTimeLimit("45s"))
    for (bad in listOf("", "0m", "8", "1.5h", "30m1h", "ten minutes")) {
      assertEquals(null, LoopCommand.parseTimeLimit(bad), "'$bad'")
    }
  }

  @Test
  fun `with --reuse-trailhead the loop starts no pass once the time limit is up, though passes are left`() {
    var now = 0L
    val loop = LoopCommand().apply {
      iterations = 100
      reuseTrailhead = true
      timeLimit = "10m"
      nowMs = { now }
      cancelPassInFlight = { false }
    }
    loop.yamlToRun(trail)
    loop.sessionStarting(sessionDir())
    assertTrue(loop.runAgain(TrailCommand.FinishedRun(passed = true, sessionDir = sessionDir())))

    now = 10.minutes.inWholeMilliseconds
    val output = captureConsoleInfo {
      assertFalse(loop.runAgain(TrailCommand.FinishedRun(passed = true, sessionDir = sessionDir())))
    }

    assertTrue("Stopping: the time limit is up." in output, output)
    assertTrue("Loop: 2 of 2 pass(es) passed, 0 failed." in output, output)
  }

  @Test
  fun `with only a time limit each session runs a batch of passes, and the loop keeps starting sessions until it is up`() {
    var now = 0L
    val loop = LoopCommand().apply {
      sameSession = true
      timeLimit = "1h"
      nowMs = { now }
      cancelPassInFlight = { false }
      runsCancellable = { true }
    }
    val yaml = createTrailblazeYaml()
    val steps = yaml.decodeUnifiedTrail(trail).trail.size

    assertEquals(PASSES_PER_SESSION_WITHOUT_A_COUNT * steps, yaml.decodeUnifiedTrail(loop.yamlToRun(trail)).trail.size)
    loop.sessionStarting(sessionDir())
    assertTrue(loop.runAgain(TrailCommand.FinishedRun(passed = true, sessionDir = sessionDir())))

    loop.yamlToRun(trail)
    loop.sessionStarting(sessionDir())
    now = 1.hours.inWholeMilliseconds
    assertFalse(loop.runAgain(TrailCommand.FinishedRun(passed = true, sessionDir = sessionDir())))
  }

  @Test
  fun `at the time limit the pass still running is cancelled, is not a failure, and the loop exits clean`() {
    val file = Files.createTempFile("loop", ".trail.yaml").toFile().apply { writeText(trail) }
    val out = Files.createTempFile("on-failure", ".txt").toFile()
    var now = 0L
    val cancelled = java.util.concurrent.CountDownLatch(1)
    val loop = LoopCommand().apply {
      trailFiles = listOf(file)
      reuseTrailhead = true
      timeLimit = "5m"
      onFailure = "printf '%s' >> '${out.path}'"
      nowMs = { now }
      deadlinePollMillis = 10
      cancelPassInFlight = { cancelled.countDown(); true }
      envReader = { null }
      abandonedSessionWaitMillis = 50
      passPollMillis = 10
    }
    loop.runTrails = {
      loop.yamlToRun(trail)
      loop.sessionStarting(sessionDir())
      now = 5.minutes.inWholeMilliseconds
      assertTrue(cancelled.await(5, java.util.concurrent.TimeUnit.SECONDS), "the running pass was never cancelled")
      val stopped = sessionDir(DirectionStep("Launch signed in", isTrailhead = true) to null).also { cancel(it) }
      assertFalse(loop.runAgain(cutRun(stopped)))
      // What a run that ended cancelled exits with.
      TrailblazeExitCode.ASSERTION_FAILED.code
    }

    assertEquals(TrailblazeExitCode.SUCCESS.code, loop.call())
    assertEquals("", out.readText())
  }

  @Test
  fun `a pass that failed before the time limit cancelled its cleanup still counts as failed`() {
    val file = Files.createTempFile("loop", ".trail.yaml").toFile().apply { writeText(trail) }
    val out = Files.createTempFile("on-failure", ".txt").toFile()
    var now = 0L
    val cancelled = java.util.concurrent.CountDownLatch(1)
    val loop = LoopCommand().apply {
      trailFiles = listOf(file)
      reuseTrailhead = true
      timeLimit = "5m"
      onFailure = "printf 'hook' >> '${out.path}'"
      nowMs = { now }
      deadlinePollMillis = 10
      cancelPassInFlight = { cancelled.countDown(); true }
      envReader = { null }
    }
    loop.runTrails = {
      loop.yamlToRun(trail)
      // The step failed first; the cancel then lands during the failure's teardown.
      val failed = sessionDir(
        DirectionStep("Launch signed in", isTrailhead = true) to null,
        DirectionStep("Open the menu") to "no Menu",
      )
      loop.sessionStarting(failed)
      now = 5.minutes.inWholeMilliseconds
      assertTrue(cancelled.await(5, java.util.concurrent.TimeUnit.SECONDS), "the running pass was never cancelled")
      cancel(failed)
      val run = TrailCommand.FinishedRun(
        passed = false,
        sessionDir = failed,
        letGo = { DaemonClient.RunRelease.LET_GO },
        cancelled = true,
      )
      assertFalse(loop.runAgain(run))
      TrailblazeExitCode.ASSERTION_FAILED.code
    }

    assertEquals(TrailblazeExitCode.ASSERTION_FAILED.code, loop.call())
    assertEquals("hook", out.readText())
  }

  @Test
  fun `a failed end the session records while the cut run cleans up still counts as failed`() {
    assertEquals(TrailblazeExitCode.ASSERTION_FAILED.code, cutLoopExit { session ->
      val ended = TrailblazeLog.TrailblazeSessionStatusChangeLog(
        sessionStatus = SessionStatus.Ended.Failed(durationMs = 0, exceptionMessage = "app crashed"),
        session = SessionId("loop-session"),
        timestamp = Instant.fromEpochMilliseconds(9),
      )
      File(session, "999_TrailblazeSessionStatusChangeLog.json").writeText(TrailblazeJsonInstance.encodeToString(TrailblazeLog.serializer(), ended))
    })
  }

  @Test
  fun `a session someone stopped just before the time limit's cancel keeps the exit failed`() {
    assertEquals(TrailblazeExitCode.ASSERTION_FAILED.code, cutLoopExit(stoppedFirst = true) { cancel(it) })
  }

  @Test
  fun `a cut run whose session never started keeps the exit failed`() {
    assertEquals(TrailblazeExitCode.ASSERTION_FAILED.code, cutLoopExit(emptySession = true) {})
  }

  @Test
  fun `a session that recorded its success before the cut lands is a passed pass`() {
    val output = captureConsoleInfo {
      assertEquals(TrailblazeExitCode.SUCCESS.code, cutLoopExit { endWith(it, SessionStatus.Ended.Succeeded(durationMs = 0)) })
    }
    assertTrue("Loop: 1 of 1 pass(es) passed, 0 failed." in output, output)
  }

  @Test
  fun `a failure cleanup records after the session's success keeps the exit failed`() {
    assertEquals(TrailblazeExitCode.ASSERTION_FAILED.code, cutLoopExit { session ->
      endWith(session, SessionStatus.Ended.Succeeded(durationMs = 0))
      endWith(session, SessionStatus.Ended.Failed(durationMs = 0, exceptionMessage = "capture failed"), name = "9999")
    })
  }

  @Test
  fun `a failure cleanup records while the session still holds the device keeps the exit failed`() {
    assertEquals(
      TrailblazeExitCode.ASSERTION_FAILED.code,
      cutLoopExit(onRelease = { endWith(it, SessionStatus.Ended.Failed(durationMs = 0, exceptionMessage = "app crashed"), name = "9999") }) {
        endWith(it, SessionStatus.Ended.Succeeded(durationMs = 0))
      },
    )
  }

  @Test
  fun `an on-device handler timeout before the cut keeps the exit failed`() {
    assertEquals(
      TrailblazeExitCode.ASSERTION_FAILED.code,
      cutLoopExit { cancel(it, message = OnDeviceRpcTimeouts.HANDLER_AWAIT_CAP_MESSAGE) },
    )
  }

  @Test
  fun `a successful session that never lets go of the device keeps the exit failed`() {
    assertEquals(
      TrailblazeExitCode.ASSERTION_FAILED.code,
      cutLoopExit(release = DaemonClient.RunRelease.HOLDING) { endWith(it, SessionStatus.Ended.Succeeded(durationMs = 0)) },
    )
  }

  private fun endWith(session: File, status: SessionStatus, name: String = "999") {
    val ended = TrailblazeLog.TrailblazeSessionStatusChangeLog(
      sessionStatus = status,
      session = SessionId("loop-session"),
      timestamp = Instant.fromEpochMilliseconds(9),
    )
    File(session, "${name}_TrailblazeSessionStatusChangeLog.json").writeText(TrailblazeJsonInstance.encodeToString(TrailblazeLog.serializer(), ended))
  }

  /** Runs a timed loop whose one session the time limit cuts, then [afterCut] writes what it recorded after; returns the exit. */
  private fun cutLoopExit(
    stoppedFirst: Boolean = false,
    emptySession: Boolean = false,
    release: DaemonClient.RunRelease = DaemonClient.RunRelease.LET_GO,
    onRelease: (File) -> Unit = {},
    afterCut: (File) -> Unit,
  ): Int {
    val file = Files.createTempFile("loop", ".trail.yaml").toFile().apply { writeText(trail) }
    var now = 0L
    val cancelled = java.util.concurrent.CountDownLatch(1)
    val loop = LoopCommand().apply {
      trailFiles = listOf(file)
      reuseTrailhead = true
      timeLimit = "5m"
      nowMs = { now }
      deadlinePollMillis = 10
      cancelPassInFlight = { cancelled.countDown(); true }
      envReader = { null }
      abandonedSessionWaitMillis = 50
      passPollMillis = 10
    }
    loop.runTrails = {
      loop.yamlToRun(trail)
      val session = if (emptySession) sessionDir() else sessionDir(DirectionStep("Launch signed in", isTrailhead = true) to null)
      if (stoppedFirst) cancel(session)
      loop.sessionStarting(session)
      now = 5.minutes.inWholeMilliseconds
      assertTrue(cancelled.await(5, java.util.concurrent.TimeUnit.SECONDS), "the running pass was never cancelled")
      afterCut(session)
      loop.runAgain(cutRun(session).copy(letGo = { onRelease(session); release }))
      TrailblazeExitCode.ASSERTION_FAILED.code
    }
    return loop.call()
  }

  @Test
  fun `the exit code a time-limit cut ends the process with is success when no pass failed`() {
    var now = 0L
    val loop = LoopCommand().apply {
      reuseTrailhead = true
      timeLimit = "10m"
      nowMs = { now }
      cancelPassInFlight = { false }
    }
    loop.yamlToRun(trail)
    loop.sessionStarting(sessionDir())
    now = 10.minutes.inWholeMilliseconds
    assertFalse(loop.runAgain(cutRun(sessionDir().also { cancel(it) })))

    assertEquals(TrailblazeExitCode.SUCCESS.code, loop.finalExitCode(TrailblazeExitCode.ASSERTION_FAILED.code))
    assertEquals(TrailblazeExitCode.INFRA_FAILED.code, loop.finalExitCode(TrailblazeExitCode.INFRA_FAILED.code))
  }

  @Test
  fun `someone else's cancel on one device keeps the exit failed when another device is cut at the time limit`() {
    var now = 0L
    val loop = LoopCommand().apply {
      reuseTrailhead = true
      timeLimit = "10m"
      nowMs = { now }
      cancelPassInFlight = { false }
    }
    loop.yamlToRun(trail)
    loop.sessionStarting(sessionDir())
    assertFalse(loop.runAgain(TrailCommand.FinishedRun(passed = false, sessionDir = sessionDir().also { cancel(it) })))
    loop.yamlToRun(trail)
    loop.sessionStarting(sessionDir())
    now = 10.minutes.inWholeMilliseconds
    assertFalse(loop.runAgain(cutRun(sessionDir().also { cancel(it) })))

    assertEquals(TrailblazeExitCode.ASSERTION_FAILED.code, loop.finalExitCode(TrailblazeExitCode.ASSERTION_FAILED.code))
  }

  @Test
  fun `a session is failing once a step failed or it ended failed, but not while self-heal takes the step over`() {
    val failedStep = sessionDir(DirectionStep("Launch signed in", isTrailhead = true) to null, DirectionStep("Open the menu") to "no Menu")
    assertTrue(LoopCommand.failedSoFar(failedStep))

    val healing = sessionDir(DirectionStep("Open the menu") to "no Menu")
    val heal = TrailblazeLog.SelfHealInvokedLog(
      promptStep = DirectionStep("Open the menu"),
      session = SessionId("loop-session"),
      timestamp = Instant.fromEpochMilliseconds(5),
      recordingResult = PromptRecordingResult.Failure(
        successfulTools = emptyList(),
        failedTool = TrailblazeToolYamlWrapper(name = "tapOn", trailblazeTool = OtherTrailblazeTool(toolName = "tapOn")),
        failureResult = TrailblazeToolResult.Error.ExceptionThrown("no Menu"),
      ),
    )
    File(healing, "900_SelfHealInvokedLog.json").writeText(TrailblazeJsonInstance.encodeToString(TrailblazeLog.serializer(), heal))
    assertFalse(LoopCommand.failedSoFar(healing))

    val endedFailed = sessionDir(DirectionStep("Open the menu") to null)
    val ended = TrailblazeLog.TrailblazeSessionStatusChangeLog(
      sessionStatus = SessionStatus.Ended.Failed(durationMs = 0, exceptionMessage = "app crashed"),
      session = SessionId("loop-session"),
      timestamp = Instant.fromEpochMilliseconds(9),
    )
    File(endedFailed, "999_TrailblazeSessionStatusChangeLog.json").writeText(TrailblazeJsonInstance.encodeToString(TrailblazeLog.serializer(), ended))
    assertTrue(LoopCommand.failedSoFar(endedFailed))
  }

  @Test
  fun `in process, where nothing can cut a session, a timed loop runs one pass per session`() {
    val loop = LoopCommand().apply {
      sameSession = true
      timeLimit = "1h"
      runsCancellable = { false }
    }
    val yaml = createTrailblazeYaml()

    assertEquals(yaml.decodeUnifiedTrail(trail).trail.size, yaml.decodeUnifiedTrail(loop.yamlToRun(trail)).trail.size)
  }

  @Test
  fun `a device that never ran keeps the exit failed when another device stops at the time limit`() {
    var now = 0L
    val loop = LoopCommand().apply {
      reuseTrailhead = true
      timeLimit = "10m"
      nowMs = { now }
      cancelPassInFlight = { false }
    }
    // The first device was unavailable: no session started.
    loop.yamlToRun(trail)
    assertFalse(loop.runAgain(null))
    // The second ran clean until the time limit.
    loop.yamlToRun(trail)
    loop.sessionStarting(sessionDir())
    now = 10.minutes.inWholeMilliseconds
    assertFalse(loop.runAgain(TrailCommand.FinishedRun(passed = true, sessionDir = sessionDir())))

    assertEquals(TrailblazeExitCode.ASSERTION_FAILED.code, loop.finalExitCode(TrailblazeExitCode.ASSERTION_FAILED.code))
  }

  @Test
  fun `a full batch of passes between first-pass failures resets the failure streak`() {
    val loop = LoopCommand().apply {
      sameSession = true
      timeLimit = "8h"
      nowMs = { 0L }
      cancelPassInFlight = { false }
    }
    val firstPassFailed = { sessionDir(DirectionStep("Launch signed in", isTrailhead = true) to "App did not launch") }
    repeat(2) {
      loop.yamlToRun(trail)
      loop.sessionStarting(sessionDir())
      assertTrue(loop.runAgain(TrailCommand.FinishedRun(passed = false, sessionDir = firstPassFailed())))
      loop.yamlToRun(trail)
      loop.sessionStarting(sessionDir())
      assertTrue(loop.runAgain(TrailCommand.FinishedRun(passed = true, sessionDir = sessionDir())))
    }
    loop.yamlToRun(trail)
    loop.sessionStarting(sessionDir())

    assertTrue(loop.runAgain(TrailCommand.FinishedRun(passed = false, sessionDir = firstPassFailed())))
  }

  @Test
  fun `a session's progress counts finished steps after the trailhead and names the failed one`() {
    val session = sessionDir(
      DirectionStep("Launch signed in", isTrailhead = true) to null,
      DirectionStep("Open the menu") to null,
      VerificationStep("The menu is open") to null,
      DirectionStep("Open the menu") to "Recording failed at tapOnElementBySelector: no Menu",
    )

    assertEquals(
      LoopCommand.SessionProgress(
        completedSteps = 2,
        failure = "at \"Open the menu\": Recording failed at tapOnElementBySelector: no Menu",
      ),
      LoopCommand.readProgress(session),
    )
  }

  @Test
  fun `a teardown that ran after a failed step is neither a finished step nor the failure`() {
    val session = sessionDir(
      DirectionStep("Launch signed in", isTrailhead = true) to null,
      DirectionStep("Open the menu") to null,
      VerificationStep("The menu is open") to "The menu is closed",
      DirectionStep("Sign out", recordable = false, isTeardown = true) to null,
    )

    assertEquals(
      LoopCommand.SessionProgress(completedSteps = 1, failure = "at \"The menu is open\": The menu is closed"),
      LoopCommand.readProgress(session),
    )
  }

  @Test
  fun `a failed trailhead is reported as the trailhead`() {
    val session = sessionDir(DirectionStep("Launch signed in", isTrailhead = true) to "App did not launch")

    assertEquals(
      LoopCommand.SessionProgress(completedSteps = 0, failure = "at the trailhead: App did not launch"),
      LoopCommand.readProgress(session),
    )
  }

  @Test
  fun `after a failure in pass 2 of 5 the recovery session runs the 3 passes left`() {
    val loop = LoopCommand().apply { iterations = 5; sameSession = true }
    val yaml = createTrailblazeYaml()
    val steps = yaml.decodeUnifiedTrail(trail).trail
    assertEquals(5 * steps.size, yaml.decodeUnifiedTrail(loop.yamlToRun(trail)).trail.size)

    // Pass 1 finished both steps; pass 2 failed on its second step.
    val failedSession = sessionDir(
      DirectionStep("Launch signed in", isTrailhead = true) to null,
      DirectionStep("Open the menu") to null,
      VerificationStep("The menu is open") to null,
      DirectionStep("Open the menu") to null,
      VerificationStep("The menu is open") to "The menu is closed",
    )
    assertTrue(loop.runAgain(TrailCommand.FinishedRun(passed = false, sessionDir = failedSession)))

    val recovery = yaml.decodeUnifiedTrail(loop.yamlToRun(trail))
    assertNotNull(recovery.trailhead)
    assertEquals(3 * steps.size, recovery.trail.size)
    assertFalse(loop.runAgain(TrailCommand.FinishedRun(passed = true, sessionDir = sessionDir())))
  }

  @Test
  fun `with several devices each device loops for the full count`() {
    val loop = LoopCommand().apply { iterations = 3; sameSession = true }
    val yaml = createTrailblazeYaml()
    val steps = yaml.decodeUnifiedTrail(trail).trail.size

    loop.yamlToRun(trail)
    assertFalse(loop.runAgain(TrailCommand.FinishedRun(passed = true, sessionDir = sessionDir())))

    assertEquals(3 * steps, yaml.decodeUnifiedTrail(loop.yamlToRun(trail)).trail.size)
  }

  @Test
  fun `a step that self-healed is not reported as the failure`() {
    val session = sessionDir(
      DirectionStep("Launch signed in", isTrailhead = true) to null,
      DirectionStep("Open the menu") to "Recording failed at tapOnElementBySelector: no Menu",
      DirectionStep("Open the menu") to null,
      VerificationStep("The menu is open") to "The menu is closed",
    )

    assertEquals(
      LoopCommand.SessionProgress(completedSteps = 1, failure = "at \"The menu is open\": The menu is closed"),
      LoopCommand.readProgress(session),
    )
  }

  @Test
  fun `a session that failed after its healed steps passed names no step`() {
    val session = sessionDir(
      DirectionStep("Launch signed in", isTrailhead = true) to null,
      DirectionStep("Open the menu") to "Recording failed at tapOnElementBySelector: no Menu",
      DirectionStep("Open the menu") to null,
      VerificationStep("The menu is open") to null,
    )

    assertEquals(LoopCommand.SessionProgress(completedSteps = 2, failure = null), LoopCommand.readProgress(session))
  }

  @Test
  fun `a cancelled session stops the loop without reporting a failure`() {
    val out = Files.createTempFile("on-failure", ".txt").toFile()
    val loop = LoopCommand().apply {
      sameSession = true
      iterations = 5
      onFailure = "printf '%s' >> '${out.path}'"
    }
    loop.yamlToRun(trail)
    val stopped = sessionDir(
      DirectionStep("Launch signed in", isTrailhead = true) to null,
      DirectionStep("Open the menu") to null,
      VerificationStep("The menu is open") to null,
      DirectionStep("Open the menu") to null,
    ).also { cancel(it) }

    assertFalse(loop.runAgain(TrailCommand.FinishedRun(passed = false, sessionDir = stopped)))

    assertEquals("", out.readText())
  }

  @Test
  fun `a session the on-device timeout cancelled recovers like a failure`() {
    val out = Files.createTempFile("on-failure", ".txt").toFile()
    val loop = LoopCommand().apply {
      sameSession = true
      iterations = 3
      onFailure = "printf '%s' >> '${out.path}'"
    }
    val yaml = createTrailblazeYaml()
    loop.yamlToRun(trail)
    val timedOut = sessionDir(
      DirectionStep("Launch signed in", isTrailhead = true) to null,
      DirectionStep("Open the menu") to null,
      VerificationStep("The menu is open") to null,
      DirectionStep("Open the menu") to null,
    ).also { cancel(it, OnDeviceRpcTimeouts.HANDLER_AWAIT_CAP_MESSAGE) }

    assertTrue(loop.runAgain(TrailCommand.FinishedRun(passed = false, sessionDir = timedOut)))

    assertEquals(yaml.decodeUnifiedTrail(trail).trail, yaml.decodeUnifiedTrail(loop.yamlToRun(trail)).trail)
    loop.runAgain(TrailCommand.FinishedRun(passed = true, sessionDir = sessionDir()))
    assertEquals(timedOut.absolutePath, out.readText())
  }

  /** What the CLI reports for a run it gave up on after its no-progress window. */
  private val noProgress = "Timed out waiting for run to complete: no progress for 600s (run cancelled)"

  @Test
  fun `a pass the CLI abandoned for making no progress recovers like a failure, though its session ended cancelled`() {
    val out = Files.createTempFile("on-failure", ".txt").toFile()
    val loop = LoopCommand().apply {
      sameSession = true
      iterations = 3
      onFailure = "printf '%s' >> '${out.path}'"
    }
    val yaml = createTrailblazeYaml()
    loop.yamlToRun(trail)
    // Pass 1 finished; pass 2 hung on its first step, and the CLI's cancel ended the session.
    val abandoned = sessionDir(
      DirectionStep("Launch signed in", isTrailhead = true) to null,
      DirectionStep("Open the menu") to null,
      VerificationStep("The menu is open") to null,
    ).also { cancel(it, "Session cancelled by user - driver forcefully closed") }

    val output = captureConsoleInfo {
      assertTrue(loop.runAgain(TrailCommand.FinishedRun(passed = false, sessionDir = abandoned, abandoned = noProgress, abandonedAtMs = 500)))
    }

    assertEquals(yaml.decodeUnifiedTrail(trail).trail, yaml.decodeUnifiedTrail(loop.yamlToRun(trail)).trail)
    assertTrue("Pass 2/3 failed with no progress: $noProgress" in output, output)
    loop.runAgain(TrailCommand.FinishedRun(passed = true, sessionDir = sessionDir()))
    assertEquals(abandoned.absolutePath, out.readText())
  }

  @Test
  fun `with --reuse-trailhead an abandoned pass recovers from scratch, and abandoned passes in a row stop the loop`() {
    val out = Files.createTempFile("on-failure", ".txt").toFile()
    val loop = LoopCommand().apply {
      iterations = 100
      reuseTrailhead = true
      onFailure = "printf '%s ' >> '${out.path}'"
    }
    val abandoned = sessionDir(DirectionStep("Launch signed in", isTrailhead = true) to null).also { cancel(it) }

    val reruns = generateSequence {
      loop.runAgain(TrailCommand.FinishedRun(passed = false, sessionDir = abandoned, abandoned = noProgress))
    }.takeWhile { it }.onEach { assertEquals(false, loop.trailheadReuse) }.count()

    assertEquals(DEFAULT_STOP_AFTER_FAILURES - 1, reruns)
    assertEquals(List(DEFAULT_STOP_AFTER_FAILURES) { abandoned.absolutePath }.joinToString(" ", postfix = " "), out.readText())
  }

  @Test
  fun `an abandoned pass waits for its session to end before the hook and the recovery`() {
    val out = Files.createTempFile("on-failure", ".txt").toFile()
    val loop = LoopCommand().apply {
      sameSession = true
      iterations = 3
      onFailure = "printf '%s' >> '${out.path}'"
      passPollMillis = 10
    }
    loop.yamlToRun(trail)
    val unwinding = sessionDir(DirectionStep("Launch signed in", isTrailhead = true) to null)
    val ending = thread {
      Thread.sleep(300)
      cancel(unwinding)
    }

    assertTrue(loop.runAgain(TrailCommand.FinishedRun(passed = false, sessionDir = unwinding, abandoned = noProgress)))

    assertTrue(LoopCommand.sessionEnded(unwinding), "the recovery must not start before the abandoned session ended")
    ending.join()
    loop.runAgain(TrailCommand.FinishedRun(passed = true, sessionDir = sessionDir()))
    assertEquals(unwinding.absolutePath, out.readText())
  }

  @Test
  fun `an abandoned run that never lets go of the device stops the loop without running --on-failure`() {
    val out = Files.createTempFile("on-failure", ".txt").toFile()
    val loop = LoopCommand().apply {
      sameSession = true
      iterations = 3
      onFailure = "printf '%s' >> '${out.path}'"
      passPollMillis = 10
      abandonedSessionWaitMillis = 200
    }
    loop.yamlToRun(trail)
    val wedged = sessionDir(DirectionStep("Launch signed in", isTrailhead = true) to null)

    val output = captureConsoleInfo {
      assertFalse(loop.runAgain(TrailCommand.FinishedRun(passed = false, sessionDir = wedged, abandoned = noProgress)))
    }

    assertTrue("still holds the device" in output, output)
    assertEquals("", out.readText())
  }

  @Test
  fun `an abandoned pass waits for its run to let go of the device, not only for its session to end`() {
    val loop = LoopCommand().apply {
      sameSession = true
      iterations = 3
      passPollMillis = 10
    }
    loop.yamlToRun(trail)
    val ended = sessionDir(DirectionStep("Launch signed in", isTrailhead = true) to null).also { cancel(it) }
    val letGoAt = System.currentTimeMillis() + 300

    val run = TrailCommand.FinishedRun(false, ended, abandoned = noProgress, abandonedAtMs = 500, letGo = {
      if (System.currentTimeMillis() >= letGoAt) DaemonClient.RunRelease.LET_GO else DaemonClient.RunRelease.HOLDING
    })
    assertTrue(loop.runAgain(run))

    assertTrue(System.currentTimeMillis() >= letGoAt, "the recovery must not start while the abandoned run holds the device")
  }

  @Test
  fun `a failed status request keeps waiting rather than taking the ended session as let go`() {
    val loop = LoopCommand().apply {
      sameSession = true
      iterations = 3
      passPollMillis = 10
    }
    loop.yamlToRun(trail)
    val ended = sessionDir(DirectionStep("Launch signed in", isTrailhead = true) to null).also { cancel(it) }
    val letGoAt = System.currentTimeMillis() + 300
    val answers = ArrayDeque(listOf(DaemonClient.RunRelease.HOLDING))

    val run = TrailCommand.FinishedRun(false, ended, abandoned = noProgress, abandonedAtMs = 500, letGo = {
      answers.removeFirstOrNull() ?: if (System.currentTimeMillis() >= letGoAt) {
        DaemonClient.RunRelease.LET_GO
      } else {
        DaemonClient.RunRelease.UNREACHABLE
      }
    })
    assertTrue(loop.runAgain(run))

    assertTrue(System.currentTimeMillis() >= letGoAt, "a failed status poll released the recovery before the run let go")
  }

  @Test
  fun `an abandoned run with no session and a daemon that can't say is not waited for`() {
    val loop = LoopCommand().apply { iterations = 3; sameSession = true }
    loop.yamlToRun(trail)
    val missing = File(Files.createTempDirectory("loop-logs").toFile(), "never-written")

    val started = System.currentTimeMillis()
    assertTrue(loop.runAgain(TrailCommand.FinishedRun(false, missing, abandoned = noProgress, letGo = { DaemonClient.RunRelease.UNKNOWN })))

    assertTrue(System.currentTimeMillis() - started < 10_000, "waited for a session that does not exist")
  }

  @Test
  fun `a person's stop still stops the loop when the CLI also gave up on the run`() {
    val stoppedFromTrailRunner = sessionDir(DirectionStep("Launch signed in", isTrailhead = true) to null)
      .also { cancel(it, MANUAL_CANCELLATION_MESSAGE) }
    val stoppedBeforeTheCliGaveUp = sessionDir(DirectionStep("Launch signed in", isTrailhead = true) to null)
      .also { cancel(it, "Test cancelled") }

    for ((session, abandonedAt) in listOf(stoppedFromTrailRunner to 500L, stoppedBeforeTheCliGaveUp to 5_000L)) {
      val loop = LoopCommand().apply { iterations = 3; sameSession = true }
      loop.yamlToRun(trail)
      val output = captureConsoleInfo {
        assertFalse(loop.runAgain(TrailCommand.FinishedRun(false, session, abandoned = noProgress, abandonedAtMs = abandonedAt)))
      }
      assertTrue("Stopping: the session was cancelled." in output, output)
    }
  }

  @Test
  fun `the loop stops after sessions in a row that complete no pass`() {
    val loop = LoopCommand().apply { iterations = 100; sameSession = true }
    loop.yamlToRun(trail)
    val stuck = sessionDir(DirectionStep("Launch signed in", isTrailhead = true) to "App did not launch")

    val reruns = generateSequence { loop.runAgain(TrailCommand.FinishedRun(passed = false, sessionDir = stuck)) }
      .takeWhile { it }
      .count()

    assertEquals(DEFAULT_STOP_AFTER_FAILURES - 1, reruns)
  }

  @Test
  fun `each failed pass runs --on-failure with its session log directory, and the loop waits for it`() {
    val out = Files.createTempFile("on-failure", ".txt").toFile()
    val loop = LoopCommand().apply {
      sameSession = true
      iterations = 1
      onFailure = "sleep 1; printf '%s' >> '${out.path}'"
    }
    loop.yamlToRun(trail)
    val failed = sessionDir(DirectionStep("Launch signed in", isTrailhead = true) to "App did not launch")

    assertFalse(loop.runAgain(TrailCommand.FinishedRun(passed = false, sessionDir = failed)))

    assertEquals(failed.absolutePath, out.readText())
  }

  @Test
  fun `failure hooks run one at a time, in order`() {
    val work = Files.createTempDirectory("on-failure").toFile()
    val out = File(work, "out.txt")
    val lock = File(work, "lock")
    val loop = LoopCommand().apply {
      sameSession = true
      iterations = 10
      onFailure = "mkdir '$lock' || printf 'overlap ' >> '$out'; sleep 0.3; rmdir '$lock'; printf '%s ' >> '$out'"
    }
    loop.yamlToRun(trail)
    val first = sessionDir(DirectionStep("Launch signed in", isTrailhead = true) to "App did not launch")
    val second = sessionDir(DirectionStep("Launch signed in", isTrailhead = true) to "App did not launch")

    loop.runAgain(TrailCommand.FinishedRun(passed = false, sessionDir = first))
    loop.runAgain(TrailCommand.FinishedRun(passed = false, sessionDir = second))
    loop.runAgain(TrailCommand.FinishedRun(passed = true, sessionDir = sessionDir()))

    assertEquals("${first.absolutePath} ${second.absolutePath} ", out.readText())
  }

  @Test
  fun `a recovery rejected before its session ends the loop and waits for the hook`() {
    val out = Files.createTempFile("on-failure", ".txt").toFile()
    val loop = LoopCommand().apply {
      sameSession = true
      iterations = 3
      onFailure = "sleep 1; printf '%s' >> '${out.path}'"
    }
    val yaml = createTrailblazeYaml()
    loop.yamlToRun(trail)
    val failed = sessionDir(DirectionStep("Launch signed in", isTrailhead = true) to "App did not launch")
    assertTrue(loop.runAgain(TrailCommand.FinishedRun(passed = false, sessionDir = failed)))

    assertFalse(loop.runAgain(null))

    assertEquals(failed.absolutePath, out.readText())
    assertEquals(3 * yaml.decodeUnifiedTrail(trail).trail.size, yaml.decodeUnifiedTrail(loop.yamlToRun(trail)).trail.size)
  }

  @Test
  fun `a recovery that throws still waits for the hook`() {
    val out = Files.createTempFile("on-failure", ".txt").toFile()
    val failed = sessionDir(DirectionStep("Launch signed in", isTrailhead = true) to "App did not launch")
    val loop = LoopCommand().apply {
      sameSession = true
      iterations = 3
      trailFiles = listOf(Files.createTempFile("loop", ".trail.yaml").toFile().apply { writeText(trail) })
      onFailure = "sleep 1; printf '%s' >> '${out.path}'"
    }
    loop.runTrails = {
      loop.yamlToRun(trail)
      loop.runAgain(TrailCommand.FinishedRun(passed = false, sessionDir = failed))
      throw IOException("trail file is gone")
    }

    assertFailsWith<IOException> { loop.call() }

    assertEquals(failed.absolutePath, out.readText())
  }

  @Test
  fun `with --reuse-trailhead each pass sends the trail as written and asks for trailhead reuse`() {
    val loop = LoopCommand().also { CommandLine(it).parseArgs("loop.trail.yaml", "-n", "3", "--reuse-trailhead") }

    assertEquals(trail, loop.yamlToRun(trail))
    val request = loop.delegatedRunRequest(
      file = File("loop.trail.yaml"),
      yamlContent = trail,
      testName = "loop",
      deviceSpec = null,
      useRecordedSteps = true,
      initialArgs = emptyMap(),
      envReader = { null },
    )
    assertEquals(true, request.trailheadReuse)
  }

  @Test
  fun `without --reuse-trailhead a loop refuses reuse in either mode, and a run leaves it to the daemon`() {
    val expected = mapOf<TrailCommand, Boolean?>(
      LoopCommand().apply { iterations = 3 } to false,
      LoopCommand().apply { iterations = 3; sameSession = true } to false,
      TrailCommand() to null,
    )
    for ((command, reuse) in expected) {
      val request = command.delegatedRunRequest(
        file = File("loop.trail.yaml"),
        yamlContent = trail,
        testName = "loop",
        deviceSpec = null,
        useRecordedSteps = true,
        initialArgs = emptyMap(),
        envReader = { null },
      )
      assertEquals(reuse, request.trailheadReuse, command::class.simpleName)
    }
  }

  @Test
  fun `with --reuse-trailhead the loop runs one session per pass, a failed one included, and counts reused setups`() {
    val out = Files.createTempFile("on-failure", ".txt").toFile()
    val loop = LoopCommand().apply {
      iterations = 4
      reuseTrailhead = true
      onFailure = "printf '%s' >> '${out.path}'"
    }
    val full = passedSession(setup = "fullSetup")
    val failed = sessionDir(
      DirectionStep("Launch signed in", isTrailhead = true) to null,
      DirectionStep("Open the menu") to "Recording failed at tapOnElementBySelector: no Menu",
    ).also { writeSetup(it, "reused") }

    val tally = captureConsoleInfo {
      assertTrue(loop.runAgain(TrailCommand.FinishedRun(passed = true, sessionDir = full)))
      assertTrue(loop.runAgain(TrailCommand.FinishedRun(passed = true, sessionDir = passedSession(setup = "reused"))))
      assertTrue(loop.runAgain(TrailCommand.FinishedRun(passed = false, sessionDir = failed)))
      assertFalse(loop.runAgain(TrailCommand.FinishedRun(passed = true, sessionDir = passedSession(setup = "fullSetup"))))
    }

    assertEquals(failed.absolutePath, out.readText())
    assertTrue(
      "Loop: 3 of 4 pass(es) passed, 1 failed. 2 of them reused the previous pass's setup." in tally,
      "expected the tally, got: <<$tally>>",
    )
    assertTrue(
      "Pass 3/4 failed at \"Open the menu\": Recording failed at tapOnElementBySelector: no Menu" in tally,
      "expected the failed pass, got: <<$tally>>",
    )
  }

  @Test
  fun `by default each pass sends the trail as written and refuses trailhead reuse every time`() {
    val loop = LoopCommand().also { CommandLine(it).parseArgs("loop.trail.yaml", "-n", "3") }
    fun reuseRequest() = loop.delegatedRunRequest(
      file = File("loop.trail.yaml"),
      yamlContent = trail,
      testName = "loop",
      deviceSpec = null,
      useRecordedSteps = true,
      initialArgs = emptyMap(),
      envReader = { null },
    ).trailheadReuse

    assertEquals(trail, loop.yamlToRun(trail))
    captureConsoleInfo {
      // False, not null: null would let the daemon's TRAILBLAZE_TRAILHEAD_REUSE turn it on.
      assertEquals(false, reuseRequest())
      assertTrue(loop.runAgain(TrailCommand.FinishedRun(passed = true, sessionDir = passedSession(setup = "fullSetup"))))
      assertEquals(false, reuseRequest())
    }
  }

  @Test
  fun `by default the loop runs one session per pass, a failed one included`() {
    val loop = LoopCommand().apply {
      iterations = 3
    }
    val failed = sessionDir(
      DirectionStep("Launch signed in", isTrailhead = true) to null,
      DirectionStep("Open the menu") to "Recording failed at tapOnElementBySelector: no Menu",
    ).also { writeSetup(it, "fullSetup") }

    val tally = captureConsoleInfo {
      assertTrue(loop.runAgain(TrailCommand.FinishedRun(passed = true, sessionDir = passedSession(setup = "fullSetup"))))
      assertTrue(loop.runAgain(TrailCommand.FinishedRun(passed = false, sessionDir = failed)))
      assertFalse(loop.runAgain(TrailCommand.FinishedRun(passed = true, sessionDir = passedSession(setup = "fullSetup"))))
    }

    assertTrue(
      "Loop: 2 of 3 pass(es) passed, 1 failed." in tally && "reused" !in tally,
      "expected the tally, got: <<$tally>>",
    )
    assertTrue("Pass 2/3 failed at \"Open the menu\"" in tally, "expected the failed pass, got: <<$tally>>")
    assertFalse("Recovering:" in tally, "a fresh pass has no setup to recover from, got: <<$tally>>")
  }

  @Test
  fun `with --reuse-trailhead the pass after a failed one refuses reuse, and the next asks again`() {
    val loop = LoopCommand().apply {
      iterations = 4
      reuseTrailhead = true
    }
    fun reuseRequest() = loop.delegatedRunRequest(
      file = File("loop.trail.yaml"),
      yamlContent = trail,
      testName = "loop",
      deviceSpec = null,
      useRecordedSteps = true,
      initialArgs = emptyMap(),
      envReader = { null },
    ).trailheadReuse
    val failed = sessionDir(DirectionStep("Open the menu") to "no Menu")

    captureConsoleInfo {
      assertEquals(true, reuseRequest())
      assertTrue(loop.runAgain(TrailCommand.FinishedRun(passed = false, sessionDir = failed)))
      // False, not null: null would let the daemon's TRAILBLAZE_TRAILHEAD_REUSE turn it back on.
      assertEquals(false, reuseRequest())
      assertTrue(loop.runAgain(TrailCommand.FinishedRun(passed = true, sessionDir = passedSession(setup = "fullSetup"))))
      assertEquals(true, reuseRequest())
    }
  }

  @Test
  fun `with --reuse-trailhead a device that ended on a failed pass does not refuse reuse on the next device`() {
    val loop = LoopCommand().apply {
      iterations = 1
      reuseTrailhead = true
    }
    fun reuseRequest() = loop.delegatedRunRequest(
      file = File("loop.trail.yaml"),
      yamlContent = trail,
      testName = "loop",
      deviceSpec = null,
      useRecordedSteps = true,
      initialArgs = emptyMap(),
      envReader = { null },
    ).trailheadReuse

    captureConsoleInfo {
      assertEquals(true, reuseRequest())
      // The first device's only pass fails, which ends its loop.
      assertFalse(
        loop.runAgain(TrailCommand.FinishedRun(passed = false, sessionDir = sessionDir(DirectionStep("Open the menu") to "no Menu"))),
      )
      assertEquals(true, reuseRequest())
    }
  }

  @Test
  fun `with --reuse-trailhead the loop stops after passes in a row that fail`() {
    val loop = LoopCommand().apply {
      iterations = 100
      reuseTrailhead = true
    }
    val stuck = sessionDir(DirectionStep("Launch signed in", isTrailhead = true) to "App did not launch")

    val reruns = generateSequence { loop.runAgain(TrailCommand.FinishedRun(passed = false, sessionDir = stuck)) }
      .takeWhile { it }
      .count()

    assertEquals(DEFAULT_STOP_AFTER_FAILURES - 1, reruns)
  }

  @Test
  fun `--stop-after-failures sets how many failed passes in a row stop the loop, in either mode`() {
    val stuck = sessionDir(DirectionStep("Launch signed in", isTrailhead = true) to "App did not launch")
    for (sameSession in listOf(false, true)) {
      val loop = LoopCommand().apply {
        iterations = 100
        stopAfterFailures = 5
        this.sameSession = sameSession
      }
      loop.yamlToRun(trail)

      val reruns = generateSequence { loop.runAgain(TrailCommand.FinishedRun(passed = false, sessionDir = stuck)) }
        .takeWhile { it }
        .count()

      assertEquals(4, reruns, "sameSession=$sameSession")
    }
  }

  @Test
  fun `with --same-session a pass that fails after the session passed others counts as one failure in a row`() {
    // Pass 1 finished both steps; pass 2 failed on its second step.
    val passedThenFailed = {
      sessionDir(
        DirectionStep("Launch signed in", isTrailhead = true) to null,
        DirectionStep("Open the menu") to null,
        VerificationStep("The menu is open") to null,
        DirectionStep("Open the menu") to null,
        VerificationStep("The menu is open") to "The menu is closed",
      )
    }
    val stuck = sessionDir(DirectionStep("Launch signed in", isTrailhead = true) to "App did not launch")

    val once = LoopCommand().apply { iterations = 100; sameSession = true; stopAfterFailures = 1 }
    once.yamlToRun(trail)
    val output = captureConsoleInfo {
      assertFalse(once.runAgain(TrailCommand.FinishedRun(passed = false, sessionDir = passedThenFailed())))
    }
    assertTrue("Stopping: 1 passes in a row failed." in output, output)

    val twice = LoopCommand().apply { iterations = 100; sameSession = true; stopAfterFailures = 2 }
    twice.yamlToRun(trail)
    assertTrue(twice.runAgain(TrailCommand.FinishedRun(passed = false, sessionDir = passedThenFailed())))
    twice.yamlToRun(trail)
    assertFalse(twice.runAgain(TrailCommand.FinishedRun(passed = false, sessionDir = stuck)))
  }

  @Test
  fun `with --stop-after-failures 0 failed passes in a row never stop the loop before its count`() {
    val stuck = sessionDir(DirectionStep("Launch signed in", isTrailhead = true) to "App did not launch")
    for (sameSession in listOf(false, true)) {
      val loop = LoopCommand().apply {
        iterations = 10
        stopAfterFailures = 0
        this.sameSession = sameSession
      }
      loop.yamlToRun(trail)

      val tally = captureConsoleInfo {
        val reruns = generateSequence { loop.runAgain(TrailCommand.FinishedRun(passed = false, sessionDir = stuck)) }
          .takeWhile { it }
          .count()
        assertEquals(9, reruns, "sameSession=$sameSession")
      }

      assertFalse("Stopping:" in tally, "the count, not the failures, should end the loop, got: <<$tally>>")
      assertTrue("Loop: 0 of 10 pass(es) passed, 10 failed." in tally, "expected the tally, got: <<$tally>>")
    }
  }

  @Test
  fun `with --stop-after-failures 0 the time limit still ends a loop of failing passes, in either mode`() {
    val stuck = sessionDir(DirectionStep("Launch signed in", isTrailhead = true) to "App did not launch")
    for (sameSession in listOf(false, true)) {
      var now = 0L
      val loop = LoopCommand().apply {
        timeLimit = "1h"
        stopAfterFailures = 0
        this.sameSession = sameSession
        nowMs = { now }
        cancelPassInFlight = { false }
        runsCancellable = { true }
      }

      repeat(20) {
        loop.yamlToRun(trail)
        loop.sessionStarting(sessionDir())
        now += 1.minutes.inWholeMilliseconds
        assertTrue(
          loop.runAgain(TrailCommand.FinishedRun(passed = false, sessionDir = stuck)),
          "sameSession=$sameSession, failure ${it + 1}",
        )
      }
      loop.yamlToRun(trail)
      loop.sessionStarting(sessionDir())
      now = 1.hours.inWholeMilliseconds
      val output = captureConsoleInfo {
        assertFalse(loop.runAgain(TrailCommand.FinishedRun(passed = false, sessionDir = stuck)), "sameSession=$sameSession")
      }
      assertTrue("Stopping: the time limit is up." in output, "sameSession=$sameSession: $output")
    }
  }

  @Test
  fun `the end-of-loop tally lists only the latest failed passes`() {
    val loop = LoopCommand().apply {
      iterations = FAILURES_LISTED + 5
      stopAfterFailures = 0
    }
    loop.yamlToRun(trail)
    val stuck = sessionDir(DirectionStep("Launch signed in", isTrailhead = true) to "App did not launch")

    val tally = captureConsoleInfo {
      generateSequence { loop.runAgain(TrailCommand.FinishedRun(passed = false, sessionDir = stuck)) }.takeWhile { it }.count()
    }.substringAfter("Loop: ")

    assertTrue("...and 5 earlier failed pass(es)" in tally, "expected the elided count, got: <<$tally>>")
    assertEquals(FAILURES_LISTED, tally.lines().count { it.startsWith("  ✗ ") }, tally)
    assertTrue("  ✗ Pass ${FAILURES_LISTED + 5}/${FAILURES_LISTED + 5} failed" in tally, "expected the last pass listed, got: <<$tally>>")
    assertFalse("  ✗ Pass 5/${FAILURES_LISTED + 5} failed" in tally, "expected the earliest passes elided, got: <<$tally>>")
  }

  @Test
  fun `with --reuse-trailhead a cancelled pass stops the loop without reporting a failure`() {
    val out = Files.createTempFile("on-failure", ".txt").toFile()
    val loop = LoopCommand().apply {
      iterations = 5
      reuseTrailhead = true
      onFailure = "printf '%s' >> '${out.path}'"
    }
    val stopped = sessionDir(DirectionStep("Launch signed in", isTrailhead = true) to null).also { cancel(it) }

    assertFalse(loop.runAgain(TrailCommand.FinishedRun(passed = false, sessionDir = stopped)))

    assertEquals("", out.readText())
  }

  @Test
  fun `a session's setup is its last trailhead setup record, or null without one`() {
    assertEquals(null, LoopCommand.readSetup(sessionDir()))
    val session = sessionDir().also {
      writeSetup(it, "fullSetup")
      writeSetup(it, "reused")
    }
    File(session, "events/trailhead.ndjson").appendText(
      "{\"timeMs\":9,\"data\":{\"type\":\"teardown\",\"reusable\":true}}\nnot json\n",
    )

    assertEquals("reused", LoopCommand.readSetup(session))
  }

  private fun passedSession(setup: String): File = sessionDir(
    DirectionStep("Launch signed in", isTrailhead = true) to null,
    DirectionStep("Open the menu") to null,
  ).also { writeSetup(it, setup) }

  /** Appends a trailhead `setup` record, as the framework writes it, to [sessionDir]'s event stream. */
  private fun writeSetup(sessionDir: File, setup: String) {
    File(sessionDir, "events").mkdirs()
    File(sessionDir, "events/trailhead.ndjson").appendText(
      "{\"timeMs\":1,\"data\":{\"type\":\"setup\",\"tool\":\"sample_launchSignedIn\",\"setup\":\"$setup\"}}\n",
    )
  }

  /** What [block] printed through `Console.info` and `Console.error`, whose JVM streams are cached at class load. */
  private fun captureConsoleInfo(block: () -> Unit): String {
    val buffer = ByteArrayOutputStream()
    val stream = PrintStream(buffer, true, Charsets.UTF_8)
    val fields = listOf("out", "userOut").map { Console::class.java.getDeclaredField(it).apply { isAccessible = true } }
    val originals = fields.map { it.get(Console) }
    val originalErr = System.err
    fields.forEach { it.set(Console, stream) }
    System.setErr(stream)
    try {
      block()
    } finally {
      fields.zip(originals).forEach { (field, original) -> field.set(Console, original) }
      System.setErr(originalErr)
    }
    return buffer.toString(Charsets.UTF_8)
  }

  /** A session directory holding one objective-complete log per step; a non-null reason fails it. */
  private fun sessionDir(vararg steps: Pair<PromptStep, String?>): File {
    val dir = Files.createTempDirectory("loop-session").toFile()
    steps.forEachIndexed { index, (step, failure) ->
      val now = Instant.fromEpochMilliseconds(index.toLong())
      val statusData = AgentTaskStatusData(TaskId.generate(), step.prompt, 1, now, 1)
      val log = TrailblazeLog.ObjectiveCompleteLog(
        promptStep = step,
        objectiveResult = if (failure == null) {
          AgentTaskStatus.Success.ObjectiveComplete(statusData, llmExplanation = "Done")
        } else {
          AgentTaskStatus.Failure.ObjectiveFailed(statusData, llmExplanation = failure)
        },
        session = SessionId("loop-session"),
        timestamp = now,
      )
      File(dir, "%03d_ObjectiveCompleteLog.json".format(index + 1))
        .writeText(TrailblazeJsonInstance.encodeToString(TrailblazeLog.serializer(), log))
    }
    return dir
  }

  /** A run the time limit cancelled, as the daemon path hands it back once the run let go of the device. */
  private fun cutRun(sessionDir: File) = TrailCommand.FinishedRun(
    passed = false,
    sessionDir = sessionDir,
    letGo = { DaemonClient.RunRelease.LET_GO },
    cancelled = true,
  )

  /** Marks [sessionDir] cancelled, by default as Trail Runner's Stop does. */
  private fun cancel(sessionDir: File, message: String = "Stopped") {
    val log = TrailblazeLog.TrailblazeSessionStatusChangeLog(
      sessionStatus = SessionStatus.Ended.Cancelled(durationMs = 0, cancellationMessage = message),
      session = SessionId("loop-session"),
      timestamp = Instant.fromEpochMilliseconds(1_000),
    )
    File(sessionDir, "999_TrailblazeSessionStatusChangeLog.json")
      .writeText(TrailblazeJsonInstance.encodeToString(TrailblazeLog.serializer(), log))
  }

  private fun root() = CommandLine(
    TrailblazeCliCommand(
      appProvider = { error("appProvider must not be invoked for a rejected loop") },
      configProvider = { error("configProvider must not be invoked for a rejected loop") },
    ),
  )
}
