package xyz.block.trailblaze.cli

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
import xyz.block.trailblaze.yaml.DirectionStep
import xyz.block.trailblaze.yaml.PromptStep
import xyz.block.trailblaze.yaml.VerificationStep
import xyz.block.trailblaze.yaml.createTrailblazeYaml
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

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
  fun `loop requires an iteration count`() {
    val file = Files.createTempFile("loop", ".trail.yaml").toFile().apply { writeText(trail) }

    val (exit, stderr) = captureStderr { root().execute("loop", file.path) }

    assertEquals(CommandLine.ExitCode.USAGE, exit)
    assertTrue("--iterations" in stderr, "expected a missing --iterations error, got: <<$stderr>>")
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
  fun `a failed trailhead is reported as the trailhead`() {
    val session = sessionDir(DirectionStep("Launch signed in", isTrailhead = true) to "App did not launch")

    assertEquals(
      LoopCommand.SessionProgress(completedSteps = 0, failure = "at the trailhead: App did not launch"),
      LoopCommand.readProgress(session),
    )
  }

  @Test
  fun `after a failure in pass 2 of 5 the recovery session runs the 3 passes left`() {
    val loop = LoopCommand().apply { iterations = 5 }
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
    val loop = LoopCommand().apply { iterations = 3 }
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

  @Test
  fun `the loop stops after sessions in a row that complete no pass`() {
    val loop = LoopCommand().apply { iterations = 100 }
    loop.yamlToRun(trail)
    val stuck = sessionDir(DirectionStep("Launch signed in", isTrailhead = true) to "App did not launch")

    val reruns = generateSequence { loop.runAgain(TrailCommand.FinishedRun(passed = false, sessionDir = stuck)) }
      .takeWhile { it }
      .count()

    assertEquals(MAX_SESSIONS_WITHOUT_A_PASS - 1, reruns)
  }

  @Test
  fun `each failed pass runs --on-failure with its session log directory, and the loop waits for it`() {
    val out = Files.createTempFile("on-failure", ".txt").toFile()
    val loop = LoopCommand().apply {
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
