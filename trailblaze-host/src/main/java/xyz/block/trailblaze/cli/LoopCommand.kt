package xyz.block.trailblaze.cli

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import picocli.CommandLine.Command
import picocli.CommandLine.Model.CommandSpec
import picocli.CommandLine.Option
import picocli.CommandLine.Spec
import xyz.block.trailblaze.agent.model.AgentTaskStatus
import xyz.block.trailblaze.events.SessionEvent
import xyz.block.trailblaze.events.SessionEvents
import xyz.block.trailblaze.host.TrailblazeHostYamlRunner
import xyz.block.trailblaze.llm.OnDeviceRpcTimeouts
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.trailhead.TrailheadEventStream
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.util.TrailYamlTemplateResolver
import xyz.block.trailblaze.yaml.DirectionStep
import xyz.block.trailblaze.yaml.PromptStep
import xyz.block.trailblaze.yaml.createTrailblazeYaml
import java.io.File
import kotlin.concurrent.thread

/** Sessions in a row that complete no pass before the loop gives up. */
internal const val MAX_SESSIONS_WITHOUT_A_PASS = 3

/**
 * `trailblaze loop`: [TrailCommand] with the trail's steps repeated inside one session. One
 * session rather than one per pass because only the session keeps the app's state between passes
 * on every driver; a fresh session can start a fresh browser. A failure ends that session, so the
 * loop recovers by starting another one, whose trailhead resets the app, for the passes left.
 *
 * With `--reuse-trailhead`, a trailhead whose `teardown` handed its setup on skips the full setup on
 * the next pass, and a failed pass hands nothing on, so the pass after it sets up from scratch.
 * Each pass is then a whole run of the trail in a session of its own.
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
    "With --reuse-trailhead, each pass's trailhead may reuse the setup the previous pass left " +
      "instead of setting up again, when that trailhead supports it. Each pass then runs the " +
      "whole trail in a session of its own.",
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

  @Option(
    names = ["--reuse-trailhead"],
    description = [
      "Let each pass's trailhead reuse the setup the previous pass handed on, instead of setting " +
        "up again. Each pass then runs the whole trail, trailhead included, in a session of its " +
        "own. Only a trailhead that defines `teardown` hands its setup on, and only after a pass " +
        "that passed; otherwise the next pass sets up from scratch.",
    ],
  )
  var reuseTrailhead: Boolean = false

  /**
   * Refused for the pass after a failed one, so it sets up from scratch on every device it binds,
   * whatever setup is still waiting there from before the failure and whatever the daemon's
   * `TRAILBLAZE_TRAILHEAD_REUSE` says. That pass hands nothing on either, since a run with reuse off
   * records no landing to check a reuse against, so the pass after it sets up from scratch too.
   * Without the flag, left to the daemon.
   */
  override val trailheadReuse: Boolean? get() = if (reuseTrailhead) !lastPassFailed else null

  private var lastPassFailed = false

  private var passesUsed = 0
  private var passesReused = 0
  private var passesPassed = 0
  private var stepsPerPass: Int? = null
  private var sessionsWithoutAPass = 0
  private val failures = mutableListOf<String>()
  private var failureHook: Process? = null

  @Spec(Spec.Target.SELF)
  private lateinit var loopSpec: CommandSpec

  /** What distributions added to this command; see [LoopExtension]. */
  internal val extensions: List<LoopExtension> by lazy {
    if (!::loopSpec.isInitialized) return@lazy emptyList()
    loopSpec.mixins().values.mapNotNull { it.userObject() as? LoopExtension }
  }

  /** This device's loop's listeners, from its first session on; null between device loops. */
  private var listeners: List<LoopListener>? = null
  private val passReader = LoopPassReader()
  @Volatile private var watcher: Thread? = null
  /** The session [sessionStarting] announced, until its run comes back. */
  private var liveSession: File? = null
  /** Passes the loop used before the current session, and how many of the session's are reported. */
  private var sessionFirstPass = 0
  private var sessionPassesReported = 0

  internal var passPollMillis = 5_000L

  internal var envReader: (String) -> String? = System::getenv

  internal var runTrails: () -> Int = { super.call() }

  override fun yamlToRun(yamlContent: String): String {
    stepsPerPass = runCatching { createTrailblazeYaml().decodeUnifiedTrail(yamlContent).trail.size }.getOrNull()
    if (reuseTrailhead) return yamlContent
    return repeatSteps(yamlContent, iterations - passesUsed)
  }

  override fun sessionStarting(sessionDir: File) {
    stopWatcher()
    liveSession = sessionDir
    sessionFirstPass = passesUsed
    sessionPassesReported = 0
    val file = trailFiles.singleOrNull() ?: return
    val active = listeners ?: extensions.mapNotNull { extension ->
      runCatching { extension.startLoop(LoopStart(file, iterations, Clock.System.now(), capturesSessions())) }
        .onFailure { Console.error("A loop listener failed to start: ${it.message}") }
        .getOrNull()
    }.also { listeners = it }
    // With --reuse-trailhead a session is one pass, reported when the session ends.
    if (active.isEmpty() || (stepsPerPass ?: 0) == 0 || reuseTrailhead) return
    // Stopped by clearing [watcher], not by the interrupt alone: a listener can swallow that.
    thread(start = false, isDaemon = true, name = "loop-pass-watcher") {
      try {
        while (watcher === Thread.currentThread()) {
          Thread.sleep(passPollMillis)
          reportLivePasses(sessionDir)
        }
      } catch (_: InterruptedException) {
      }
    }.also { watcher = it }.start()
  }

  /**
   * Reports the passes [sessionDir] has finished but the loop's last [heldBack], which waits for the
   * session's end to tell whether the session failed after its last step.
   */
  private fun reportLivePasses(sessionDir: File, heldBack: Int = 1) {
    val steps = stepsPerPass?.takeIf { !listeners.isNullOrEmpty() } ?: return
    reportPasses(sessionDir, minOf(passReader.read(sessionDir, steps).completed.size, iterations - passesUsed - heldBack), null)
  }

  private fun stopWatcher() {
    val thread = watcher ?: return
    watcher = null
    thread.interrupt()
    thread.join()
  }

  /** Reports the session's passes after those reported, up to its first [passed], then its failed one when [failure] is set. */
  private fun reportPasses(sessionDir: File, passed: Int, failure: String?) {
    val active = listeners?.takeIf { it.isNotEmpty() } ?: return
    val steps = stepsPerPass ?: return
    if (sessionPassesReported >= passed && failure == null) return
    val windows = passReader.read(sessionDir, steps)
    val reported = mutableListOf<LoopPass>()
    while (sessionPassesReported < passed) {
      val window = windows.completed.getOrNull(sessionPassesReported) ?: break
      reported += pass(sessionFirstPass + sessionPassesReported, window, sessionDir, error = null)
      sessionPassesReported++
    }
    if (failure != null) {
      // A session that failed after its last step passed failed its last pass, at the session's end.
      val window = windows.unfinished ?: (windows.completed.getOrNull(passed) ?: windows.completed.lastOrNull()
        ?.let { PassWindow(it.endMs, it.endMs) })?.let { it.copy(endMs = maxOf(it.endMs, windows.endedMs ?: it.endMs)) }
      if (window != null) reported += pass(sessionFirstPass + passed, window, sessionDir, failure)
    }
    for (pass in reported) {
      active.forEach { listener ->
        runCatching { listener.passFinished(pass) }.onFailure { if (it is InterruptedException) Thread.currentThread().interrupt() else Console.error("A loop listener failed: ${it.message}") }
      }
    }
  }

  private fun pass(index: Int, window: PassWindow, sessionDir: File, error: String?) =
    LoopPass(index, Instant.fromEpochMilliseconds(window.startMs), Instant.fromEpochMilliseconds(window.endMs), error == null, error, sessionDir)

  override fun runAgain(run: FinishedRun?): Boolean {
    stopWatcher()
    val live = liveSession.also { liveSession = null }
    if (run == null) {
      // No session came back, say the run was cancelled, but the passes it finished still happened.
      live?.let { reportLivePasses(it, heldBack = 0) }
      return finish()
    }
    if (reuseTrailhead) return runNextPass(run)
    val remaining = iterations - passesUsed
    val steps = stepsPerPass
    if (run.passed || steps == null || steps == 0) {
      if (run.passed) {
        passesPassed += remaining
        reportPasses(run.sessionDir, remaining, null)
      }
      passesUsed = iterations
      return finish()
    }

    val progress = readProgress(run.sessionDir)
    if (progress.stopped) {
      val finished = minOf(progress.completedSteps / steps, remaining)
      passesPassed += finished
      passesUsed += finished
      reportPasses(run.sessionDir, finished, null)
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
    reportPasses(run.sessionDir, completedPasses, progress.failure?.let { "Failed $it" } ?: "Failed without a step failure")
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

  /** `--reuse-trailhead`: [run] was one pass, trailhead and all. */
  private fun runNextPass(run: FinishedRun): Boolean {
    val progress = readProgress(run.sessionDir)
    if (!run.passed && progress.stopped) {
      Console.info("Stopping: the session was cancelled.")
      return finish()
    }
    passesUsed++
    lastPassFailed = !run.passed
    if (readSetup(run.sessionDir) == TrailheadEventStream.Setup.REUSED) passesReused++
    val failed = if (run.passed) null else (progress.failure?.let { "Failed $it" } ?: "Failed without a step failure")
    reportPasses(run.sessionDir, if (run.passed) 1 else 0, failed)
    if (run.passed) {
      passesPassed++
      sessionsWithoutAPass = 0
    } else {
      val failure = "Pass $passesUsed/$iterations failed " +
        (progress.failure ?: "without a step failure; see the session logs") +
        " (session: ${run.sessionDir.path})"
      failures += failure
      Console.error("✗ $failure")
      startFailureHook(run.sessionDir)
      sessionsWithoutAPass++
    }
    if (passesUsed >= iterations) return finish()
    if (sessionsWithoutAPass >= MAX_SESSIONS_WITHOUT_A_PASS) {
      Console.error("Stopping: $sessionsWithoutAPass passes in a row failed.")
      return finish()
    }
    if (!run.passed) Console.info("Recovering: a failed pass hands no setup on, so the next pass sets up from scratch.")
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
   * Waits for the failure hook, prints the tally, and ends this device's loop. Resets the tally and
   * the last pass's verdict because `--device a,b` and `--all-devices` loop each device in turn,
   * each for --iterations, and one device's failed last pass must not refuse reuse on the next.
   */
  private fun finish(): Boolean {
    stopWatcher()
    awaitFailureHook()
    listeners?.forEach { listener ->
      runCatching { listener.loopFinished() }.onFailure { Console.error("A loop listener failed to finish: ${it.message}") }
    }
    listeners = null
    if (passesUsed > 0) {
      val reused = if (reuseTrailhead) " $passesReused of them reused the previous pass's setup." else ""
      Console.info("Loop: $passesPassed of $passesUsed pass(es) passed, ${failures.size} failed.$reused")
      failures.forEach { Console.info("  ✗ $it") }
    }
    passesUsed = 0
    passesPassed = 0
    passesReused = 0
    sessionsWithoutAPass = 0
    lastPassFailed = false
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
      else -> unloopable(file) ?: extensions.firstNotNullOfOrNull { it.misuse() }
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
      // A teardown runs after a failed step too, so it is neither a pass's step nor the failure.
      val completions = logs.filterIsInstance<TrailblazeLog.ObjectiveCompleteLog>().filter { (it.promptStep as? DirectionStep)?.isTeardown != true }.sortedBy { it.timestamp }
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

    /**
     * What the session's trailhead did about setup, from its `trailhead` event stream: one of
     * [TrailheadEventStream.Setup], or null when it logged none (a trailhead with no teardown, or no
     * trailhead). The last record wins, so a multi-device trail reports its last trailhead.
     */
    internal fun readSetup(sessionDir: File): String? {
      val stream = File(File(sessionDir, SessionEvents.DIR_NAME), SessionEvents.fileName(TrailheadEventStream.NAME))
      if (!stream.isFile) return null
      return stream.readLines().mapNotNull { line ->
        val data = runCatching { TrailblazeJsonInstance.decodeFromString(SessionEvent.serializer(), line).data }
          .getOrNull() as? JsonObject ?: return@mapNotNull null
        if ((data["type"] as? JsonPrimitive)?.contentOrNull != TrailheadEventStream.Type.SETUP) return@mapNotNull null
        (data["setup"] as? JsonPrimitive)?.contentOrNull
      }.lastOrNull()
    }

    private fun PromptStep.isTrailhead() = this is DirectionStep && isTrailhead
  }
}
