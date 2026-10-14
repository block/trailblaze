package xyz.block.trailblaze.cli

import picocli.CommandLine.Command
import picocli.CommandLine.Option
import xyz.block.trailblaze.agent.model.AgentTaskStatus
import xyz.block.trailblaze.host.TrailblazeHostYamlRunner
import xyz.block.trailblaze.llm.OnDeviceRpcTimeouts
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.util.TrailYamlTemplateResolver
import xyz.block.trailblaze.yaml.DirectionStep
import xyz.block.trailblaze.yaml.PromptStep
import xyz.block.trailblaze.yaml.createTrailblazeYaml
import java.io.File

/** Sessions in a row that complete no pass before the loop gives up. */
internal const val MAX_SESSIONS_WITHOUT_A_PASS = 3

/**
 * `trailblaze loop`: [TrailCommand] with the trail's steps repeated inside one session. One
 * session rather than one per pass because only the session keeps the app's state between passes
 * on every driver; a fresh session can start a fresh browser. A failure ends that session, so the
 * loop recovers by starting another one, whose trailhead resets the app, for the passes left.
 */
@Command(
  name = "loop",
  mixinStandardHelpOptions = true,
  description = [
    "Run a trail's trailhead once, then repeat its steps in a loop.",
    "",
    "One session runs the `trailhead:`, then the `trail:` steps --iterations times in a row, each " +
      "pass starting where the previous one left the app. The steps should end where they " +
      "began.",
    "",
    "A failed pass is reported, handed to --on-failure, and counted toward --iterations. The " +
      "loop then recovers in a new session that runs the trailhead again, and continues with the " +
      "passes left. It stops early after $MAX_SESSIONS_WITHOUT_A_PASS sessions in a row that " +
      "complete no pass. Exits non-zero if any pass failed.",
    "",
    "Takes the `trailblaze run` options, for one trail file. Several devices each loop in turn, " +
      "for --iterations each. Never saves a recording: it would record every pass into the trail.",
  ],
)
class LoopCommand : TrailCommand() {

  @Option(
    names = ["-n", "--iterations"],
    paramLabel = "<count>",
    required = true,
    description = ["How many passes to run, failed ones included."],
  )
  var iterations: Int = 0

  @Option(
    names = ["--on-failure"],
    paramLabel = "<command>",
    description = [
      "Run this shell command for each failed pass, with the failed session's log directory appended as " +
        "its last argument. It runs alongside the loop, one at a time: the loop waits for it before " +
        "starting the next one, and before exiting.",
    ],
  )
  var onFailure: String? = null

  private var passesUsed = 0
  private var passesPassed = 0
  private var stepsPerPass: Int? = null
  private var sessionsWithoutAPass = 0
  private val failures = mutableListOf<String>()
  private var failureHook: Process? = null

  internal var envReader: (String) -> String? = System::getenv

  internal var runTrails: () -> Int = { super.call() }

  override fun yamlToRun(yamlContent: String): String {
    stepsPerPass = runCatching { createTrailblazeYaml().decodeUnifiedTrail(yamlContent).trail.size }.getOrNull()
    return repeatSteps(yamlContent, iterations - passesUsed)
  }

  override fun runAgain(run: FinishedRun?): Boolean {
    if (run == null) return finish()
    val remaining = iterations - passesUsed
    val steps = stepsPerPass
    if (run.passed || steps == null || steps == 0) {
      if (run.passed) passesPassed += remaining
      passesUsed = iterations
      return finish()
    }

    val progress = readProgress(run.sessionDir)
    if (progress.stopped) {
      val finished = minOf(progress.completedSteps / steps, remaining)
      passesPassed += finished
      passesUsed += finished
      Console.info("Stopping: the session was cancelled.")
      return finish()
    }
    val completedPasses = minOf(progress.completedSteps / steps, remaining - 1)
    passesPassed += completedPasses
    passesUsed += completedPasses + 1
    val failure = "Pass $passesUsed/$iterations failed " +
      (progress.failure ?: "without a step failure; see the session logs") +
      " (session: ${run.sessionDir.path})"
    failures += failure
    Console.error("✗ $failure")
    startFailureHook(run.sessionDir)

    sessionsWithoutAPass = if (completedPasses == 0) sessionsWithoutAPass + 1 else 0
    if (passesUsed >= iterations) return finish()
    if (sessionsWithoutAPass >= MAX_SESSIONS_WITHOUT_A_PASS) {
      Console.error("Stopping: $sessionsWithoutAPass sessions in a row failed before completing a pass.")
      return finish()
    }
    Console.info("Recovering: a new session runs the trailhead, then the ${iterations - passesUsed} pass(es) left.")
    return true
  }

  /** One hook at a time, so a hook slower than the failures it reports can't pile up processes. */
  private fun startFailureHook(sessionDir: File) {
    val command = onFailure ?: return
    awaitFailureHook()
    runCatching {
      failureHook = ProcessBuilder("sh", "-c", "$command \"\$1\"", "sh", sessionDir.absolutePath)
        .directory(CliCallerContext.callerCwd().toAbsolutePath().toFile())
        .inheritIO()
        .start()
    }.onFailure { Console.error("--on-failure could not start `$command`: ${it.message}") }
  }

  private fun awaitFailureHook() {
    val hook = failureHook ?: return
    if (hook.isAlive) Console.info("Waiting for --on-failure to finish...")
    val exit = hook.waitFor()
    if (exit != 0) Console.error("--on-failure exited $exit")
    failureHook = null
  }

  /**
   * Waits for the failure hook, prints the tally, and ends this device's loop. Resets the tally
   * because `--device a,b` and `--all-devices` loop each device in turn, each for --iterations.
   */
  private fun finish(): Boolean {
    awaitFailureHook()
    if (passesUsed > 0) {
      Console.info("Loop: $passesPassed of $passesUsed pass(es) passed, ${failures.size} failed.")
      failures.forEach { Console.info("  ✗ $it") }
    }
    passesUsed = 0
    passesPassed = 0
    sessionsWithoutAPass = 0
    failures.clear()
    return false
  }

  override fun call(): Int {
    val file = trailFiles.singleOrNull()
    val misuse = when {
      iterations < 1 -> "--iterations must be a positive integer (got $iterations)"
      file == null || file.isDirectory -> "loop takes exactly one trail file"
      saveRecording == true -> "loop never saves a recording"
      noLogging -> "loop counts passes from session logs, so it needs logging"
      snapshotBaseline != null || !envReader(TrailblazeHostYamlRunner.SNAPSHOT_BASELINE_ENV_VAR).isNullOrBlank() ->
        "loop can't check a snapshot baseline: it would compare only the first pass's snapshots"
      else -> unloopable(file)
    }
    if (misuse != null) {
      reportCliError(verb = "Trail loop", reason = misuse)
      return TrailblazeExitCode.MISUSE.code
    }
    saveRecording = false
    // A rerun can throw, say if the trail file is gone or the daemon dies, and the exit would
    // then orphan the failure hook and drop the tally.
    return try {
      runTrails()
    } finally {
      finish()
    }
  }

  /** Why [file] has no `trail:` steps to repeat, or null if it has. A missing file is left for `run` to report. */
  private fun unloopable(file: File): String? {
    if (!file.isFile) return null
    val trail = runCatching {
      createTrailblazeYaml().decodeUnifiedTrail(TrailYamlTemplateResolver.resolve(file.readText(), file))
    }.getOrElse { return "loop repeats a trail's `trail:` steps, and can't read them: ${it.message ?: it::class.simpleName}" }
    return if (trail.trail.isEmpty()) "loop repeats a trail's `trail:` steps, and ${file.name} has none" else null
  }

  /**
   * How far one session got: its completed trail steps, its failed step if one failed, and whether
   * it was stopped: cancelled by a person or by another run taking the device, not by a timeout.
   */
  internal data class SessionProgress(val completedSteps: Int, val failure: String?, val stopped: Boolean = false)

  companion object {
    /**
     * [yaml] with its `trail:` steps repeated [iterations] times and its trailhead kept once. A trail
     * that doesn't decode goes out unchanged, so the runner reports the parse error as it would for
     * `run`.
     */
    internal fun repeatSteps(yaml: String, iterations: Int): String {
      val trailblazeYaml = createTrailblazeYaml()
      val trail = runCatching { trailblazeYaml.decodeUnifiedTrail(yaml) }.getOrElse { return yaml }
      return trailblazeYaml.encodeUnifiedTrailToString(
        trail.copy(trail = List(iterations) { trail.trail }.flatten()),
      )
    }

    /** Reads only the objective-complete logs, one per finished step, and the session status logs. */
    internal fun readProgress(sessionDir: File): SessionProgress {
      val logs = sessionDir.listFiles { f: File ->
        f.name.endsWith("_ObjectiveCompleteLog.json") || f.name.endsWith("_TrailblazeSessionStatusChangeLog.json")
      }.orEmpty().mapNotNull { file ->
        runCatching { TrailblazeJsonInstance.decodeFromString(TrailblazeLog.serializer(), file.readText()) }.getOrNull()
      }
      val completions = logs.filterIsInstance<TrailblazeLog.ObjectiveCompleteLog>().sortedBy { it.timestamp }
      // Only a failed last step is the terminal failure. --self-heal logs a failed recording attempt
      // before its recovery, and a session can fail after its last step passed, so an earlier
      // failure may belong to a step that recovered.
      val failed = completions.lastOrNull()?.takeIf { it.objectiveResult is AgentTaskStatus.Failure }
      return SessionProgress(
        completedSteps = completions.count {
          it.objectiveResult is AgentTaskStatus.Success && !it.promptStep.isTrailhead()
        },
        failure = failed?.let {
          val where = if (it.promptStep.isTrailhead()) "at the trailhead" else "at \"${it.promptStep.prompt}\""
          val why = (it.objectiveResult as? AgentTaskStatus.Failure.ObjectiveFailed)?.llmExplanation
            ?: "the step ran out of LLM calls"
          "$where: $why"
        },
        stopped = logs.any {
          val status = (it as? TrailblazeLog.TrailblazeSessionStatusChangeLog)?.sessionStatus
          status is SessionStatus.Ended.Cancelled &&
            status.cancellationMessage != OnDeviceRpcTimeouts.HANDLER_AWAIT_CAP_MESSAGE
        },
      )
    }

    private fun PromptStep.isTrailhead() = this is DirectionStep && isTrailhead
  }
}
