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
import xyz.block.trailblaze.ui.JvmLiveSessionDataProvider.Companion.MANUAL_CANCELLATION_MESSAGE
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.util.TrailYamlTemplateResolver
import xyz.block.trailblaze.yaml.DirectionStep
import xyz.block.trailblaze.yaml.PromptStep
import xyz.block.trailblaze.yaml.createTrailblazeYaml
import java.io.File
import kotlin.concurrent.thread
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Sessions in a row that complete no pass before the loop gives up. */
internal const val MAX_SESSIONS_WITHOUT_A_PASS = 3

/** Passes one session repeats when only --time-limit bounds the loop; it then starts another. */
internal const val PASSES_PER_SESSION_WITHOUT_A_COUNT = 50

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
    "Give --iterations, --time-limit, or both: the loop ends at whichever comes first. At the " +
      "time limit, a pass still running is cancelled and is not counted as a failure.",
    "",
    "With --reuse-trailhead, each pass's trailhead may reuse the setup the previous pass left " +
      "instead of setting up again, when that trailhead supports it. Each pass then runs the " +
      "whole trail in a session of its own.",
    "",
    "Takes the `trailblaze run` options, for one trail file. Several devices each loop in turn, " +
      "for --iterations and --time-limit each. Never saves a recording: it would record every " +
      "pass into the trail.",
  ],
)
class LoopCommand : TrailCommand() {

  @Option(
    names = ["-n", "--iterations"],
    paramLabel = "<count>",
    description = ["How many passes to run, failed ones included. Optional with --time-limit."],
  )
  var iterations: Int? = null

  @Option(
    names = ["--time-limit"],
    paramLabel = "<duration>",
    description = [
      "How long to loop, as hours, minutes and seconds: 8h, 90m, 1h30m, 45s. At the limit, a pass " +
        "still running is cancelled and not counted as a failure. A run that is not delegated to " +
        "a daemon (--no-daemon) can't be cancelled, so its pass finishes first. Optional with " +
        "--iterations.",
    ],
  )
  var timeLimit: String? = null

  /** [iterations], or no cap when only [timeLimit] bounds the loop. */
  private val passCap: Int get() = iterations ?: Int.MAX_VALUE

  /** "3/10" with a pass count, "3" without one. */
  private fun passLabel(pass: Int) = iterations?.let { "$pass/$it" } ?: "$pass"

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

  /** Passes the current session was given: the rest of the count, at most [PASSES_PER_SESSION_WITHOUT_A_COUNT] without one. */
  private var sessionPasses = 0

  /** When this device's loop must end, from its first session; null without --time-limit. */
  @Volatile private var deadlineMs: Long? = null
  /** Cancels the pass still running at [deadlineMs]; stopped by clearing it. */
  @Volatile private var deadlineTimer: Thread? = null
  /** Runs the time limit cancelled that came back as cut, not failed, on any device. */
  private var limitCuts = 0
  /** Runs that neither passed nor failed a pass but still didn't end well: no session, someone else's cancel, a held device. */
  private var otherFailures = 0
  /** Whether the session the time limit cancelled had already failed: it was cut in that failure's cleanup. */
  @Volatile private var cutSessionHadFailed = false
  /** Whether that session was already cancelled, say by a person, before the time limit's cancel. */
  @Volatile private var cutSessionWasCancelled = false
  /** Failed passes on every device, which [finish] does not reset: they decide the exit code. */
  private var failedPasses = 0

  internal var nowMs: () -> Long = System::currentTimeMillis
  internal var cancelPassInFlight: () -> Boolean = { cancelRunInFlight() }
  internal var deadlinePollMillis = 1_000L
  internal var runsCancellable: () -> Boolean = { canCancelRuns }

  private fun timeUp(): Boolean = deadlineMs?.let { nowMs() >= it } ?: false

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
  @Volatile private var liveSession: File? = null
  /** Passes the loop used before the current session, and how many of the session's are reported. */
  private var sessionFirstPass = 0
  private var sessionPassesReported = 0

  internal var passPollMillis = 5_000L

  /** How long to wait for a session this client abandoned to end before recovering anyway. */
  internal var abandonedSessionWaitMillis = 120_000L

  internal var envReader: (String) -> String? = System::getenv

  internal var runTrails: () -> Int = { super.call() }

  override fun yamlToRun(yamlContent: String): String {
    stepsPerPass = runCatching { createTrailblazeYaml().decodeUnifiedTrail(yamlContent).trail.size }.getOrNull()
    val remaining = passCap - passesUsed
    sessionPasses = when {
      // In process nothing can cut a session, so one pass per session keeps the overrun to a pass.
      reuseTrailhead || (timeLimit != null && !runsCancellable()) -> 1
      iterations == null -> minOf(remaining, PASSES_PER_SESSION_WITHOUT_A_COUNT)
      else -> remaining
    }
    if (reuseTrailhead) return yamlContent
    return repeatSteps(yamlContent, sessionPasses)
  }

  override fun sessionStarting(sessionDir: File) {
    stopWatcher()
    liveSession = sessionDir
    sessionFirstPass = passesUsed
    sessionPassesReported = 0
    if (deadlineMs == null) parseTimeLimit(timeLimit)?.let { startDeadline(nowMs() + it.inWholeMilliseconds) }
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
   * Reports the passes [sessionDir] has finished but the session's last [heldBack], which waits for
   * the session's end to tell whether the session failed after its last step.
   */
  private fun reportLivePasses(sessionDir: File, heldBack: Int = 1) {
    val steps = stepsPerPass?.takeIf { !listeners.isNullOrEmpty() } ?: return
    reportPasses(sessionDir, minOf(passReader.read(sessionDir, steps).completed.size, sessionPasses - heldBack), null)
  }

  /**
   * Cancels the pass still running at [deadline], retrying until one is cancelled or the device's
   * loop ends: a pass can start just as the deadline passes. [runAgain] stops the loop between passes.
   */
  private fun startDeadline(deadline: Long) {
    deadlineMs = deadline
    thread(start = false, isDaemon = true, name = "loop-time-limit") {
      try {
        while (deadlineTimer === Thread.currentThread()) {
          if (nowMs() >= deadline) {
            // Before the cancel, which can end the run before it returns.
            cutSessionHadFailed = liveSession?.let { failedSoFar(it) } ?: false
            cutSessionWasCancelled = liveSession?.let { readProgress(it).stopped } ?: false
            if (cancelPassInFlight()) {
              Console.info("Time limit reached: cancelling the pass still running.")
              return@thread
            }
          }
          Thread.sleep(deadlinePollMillis)
        }
      } catch (_: InterruptedException) {
      }
    }.also { deadlineTimer = it }.start()
  }

  private fun stopDeadline() {
    deadlineMs = null
    val thread = deadlineTimer ?: return
    deadlineTimer = null
    thread.interrupt()
    thread.join()
  }

  /** Ends the device's loop at the time limit, when it is up. */
  private fun stopIfTimeUp(): Boolean? {
    if (!timeUp()) return null
    Console.info("Stopping: the time limit is up.")
    return finish()
  }

  /** Why [run]'s session ended cancelled: the time limit's cancel, or someone else's, which the exit keeps as a failure. */
  private fun stopForCancel(run: FinishedRun, progress: SessionProgress): Boolean {
    when {
      // No logs: the run failed before its session started, say on a device that went away.
      !run.cancelled || cutSessionWasCancelled || run.sessionDir.listFiles().isNullOrEmpty() -> {
        otherFailures++
        Console.info("Stopping: the session was cancelled.")
      }
      progress.holdsDevice -> {
        otherFailures++
        Console.error("Stopping: the time limit is up, but the cancelled run still holds the device.")
      }
      else -> {
        limitCuts++
        Console.info("Stopping: the time limit is up.")
      }
    }
    return finish()
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
      // So did one whose failed pass still finished: a run abandoned while that pass stalled.
      val window = windows.completed.getOrNull(passed)?.extendedTo(windows.endedMs) ?: windows.unfinished
        ?: windows.completed.lastOrNull()?.let { PassWindow(it.endMs, it.endMs) }?.extendedTo(windows.endedMs)
      if (window != null) reported += pass(sessionFirstPass + passed, window, sessionDir, failure)
    }
    for (pass in reported) {
      active.forEach { listener ->
        runCatching { listener.passFinished(pass) }.onFailure { if (it is InterruptedException) Thread.currentThread().interrupt() else Console.error("A loop listener failed: ${it.message}") }
      }
    }
  }

  private fun PassWindow.extendedTo(endedMs: Long?) = copy(endMs = maxOf(endMs, endedMs ?: endMs))

  private fun pass(index: Int, window: PassWindow, sessionDir: File, error: String?) =
    LoopPass(index, Instant.fromEpochMilliseconds(window.startMs), Instant.fromEpochMilliseconds(window.endMs), error == null, error, sessionDir)

  override fun runAgain(finished: FinishedRun?): Boolean {
    stopWatcher()
    val live = liveSession.also { liveSession = null }
    // A session that recorded its success before the cancel landed passed; the run was still the cut.
    // Not until it lets go of the device, and not if cleanup recorded a failure after that success.
    // The outcome is read after the release, as cleanup can still record a failure while it holds on.
    val run = if (finished != null && finished.cancelled && endedSucceeded(finished.sessionDir) &&
      awaitLetGo(finished) && !endedFailed(finished.sessionDir) && !cutSessionHadFailed
    ) {
      limitCuts++
      finished.copy(passed = true, cancelled = false)
    } else {
      finished
    }
    if (run == null) {
      // No session came back, say the run was cancelled, but the passes it finished still happened.
      live?.let { reportLivePasses(it, heldBack = 0) }
      // Not the time limit's cut, which comes back as a run: say a device that never ran.
      otherFailures++
      return finish()
    }
    if (reuseTrailhead) return runNextPass(run)
    val queued = sessionPasses
    val steps = stepsPerPass
    if (run.passed || steps == null || steps == 0) {
      if (!run.passed) {
        passesUsed += queued
        return finish()
      }
      passesPassed += queued
      passesUsed += queued
      sessionsWithoutAPass = 0
      reportPasses(run.sessionDir, queued, null)
      if (passesUsed >= passCap) return finish()
      stopIfTimeUp()?.let { return it }
      // Only --time-limit bounds the loop, so the session ran out of the passes it was given.
      return true
    }

    val progress = progressOf(run)
    if (progress.stopped) {
      val finished = minOf(progress.completedSteps / steps, queued)
      passesPassed += finished
      passesUsed += finished
      reportPasses(run.sessionDir, finished, null)
      return stopForCancel(run, progress)
    }
    val completedPasses = minOf(progress.completedSteps / steps, queued - 1)
    passesPassed += completedPasses
    passesUsed += completedPasses + 1
    failedPasses++
    val failure = "Pass ${passLabel(passesUsed)} failed " +
      (progress.failure ?: "without a step failure; see the session logs") +
      " (session: ${run.sessionDir.path})"
    failures += failure
    Console.error("✗ $failure")
    reportPasses(run.sessionDir, completedPasses, progress.failure?.let { "Failed $it" } ?: "Failed without a step failure")
    if (progress.holdsDevice) return stopForHeldDevice()
    startFailureHook(run.sessionDir)

    sessionsWithoutAPass = if (completedPasses == 0) sessionsWithoutAPass + 1 else 0
    if (passesUsed >= passCap) return finish()
    if (sessionsWithoutAPass >= MAX_SESSIONS_WITHOUT_A_PASS) {
      Console.error("Stopping: $sessionsWithoutAPass sessions in a row failed before completing a pass.")
      return finish()
    }
    stopIfTimeUp()?.let { return it }
    val left = iterations?.let { "the ${it - passesUsed} pass(es) left" } ?: "more passes until the time limit"
    Console.info("Recovering: a new session runs the trailhead, then $left.")
    return true
  }

  /** `--reuse-trailhead`: [run] was one pass, trailhead and all. */
  private fun runNextPass(run: FinishedRun): Boolean {
    val progress = progressOf(run)
    if (!run.passed && progress.stopped) return stopForCancel(run, progress)
    passesUsed++
    lastPassFailed = !run.passed
    if (readSetup(run.sessionDir) == TrailheadEventStream.Setup.REUSED) passesReused++
    val failed = if (run.passed) null else (progress.failure?.let { "Failed $it" } ?: "Failed without a step failure")
    reportPasses(run.sessionDir, if (run.passed) 1 else 0, failed)
    if (run.passed) {
      passesPassed++
      sessionsWithoutAPass = 0
    } else {
      failedPasses++
      val failure = "Pass ${passLabel(passesUsed)} failed " +
        (progress.failure ?: "without a step failure; see the session logs") +
        " (session: ${run.sessionDir.path})"
      failures += failure
      Console.error("✗ $failure")
      if (progress.holdsDevice) return stopForHeldDevice()
      startFailureHook(run.sessionDir)
      sessionsWithoutAPass++
    }
    if (passesUsed >= passCap) return finish()
    if (sessionsWithoutAPass >= MAX_SESSIONS_WITHOUT_A_PASS) {
      Console.error("Stopping: $sessionsWithoutAPass passes in a row failed.")
      return finish()
    }
    stopIfTimeUp()?.let { return it }
    if (!run.passed) Console.info("Recovering: a failed pass hands no setup on, so the next pass sets up from scratch.")
    return true
  }

  /**
   * How far [run] got. A run this client abandoned for making no progress failed rather than
   * stopped: the cancellation its session ends with is the client's own. A person's stop still
   * stops the loop: one that ended the session before the client gave up, or Trail Runner's at any
   * time. The run is left to let go of the device first, so --on-failure gets its whole session,
   * capture included, and the recovery doesn't start while it still holds the device. Only passes
   * it finished before it was abandoned count: one that finishes while it lets go is the one that
   * stalled.
   */
  private fun progressOf(run: FinishedRun): SessionProgress {
    if (run.cancelled) return cancelledProgress(run)
    val abandoned = run.abandoned ?: return readProgress(run.sessionDir)
    val letGo = awaitLetGo(run)
    val steps = stepsPerPass?.takeIf { it > 0 } ?: 0
    val windows = passReader.read(run.sessionDir, maxOf(steps, 1))
    val abandonedAt = run.abandonedAtMs
    val passesBefore = if (steps == 0) 0 else windows.completed.count { abandonedAt == null || it.endMs <= abandonedAt }
    val endedBeforeAbandon = abandonedAt != null && (windows.endedMs ?: Long.MAX_VALUE) < abandonedAt
    val stopped = readProgress(run.sessionDir).stopped &&
      (endedBeforeAbandon || cancellationMessage(run.sessionDir) == MANUAL_CANCELLATION_MESSAGE)
    return SessionProgress(
      completedSteps = passesBefore * steps,
      failure = "with no progress: $abandoned",
      stopped = stopped,
      holdsDevice = !letGo,
    )
  }

  /**
   * How far a [run] the time limit cancelled got, once it has let go of the device, so its session
   * is complete. Stopped, not failed, unless a step failed before the cancel: the session may have
   * been in that failure's teardown or capture.
   */
  private fun cancelledProgress(run: FinishedRun): SessionProgress {
    val letGo = awaitLetGo(run)
    // A failed end recorded during cleanup failed before the cut, which ends a session cancelled.
    val failed = cutSessionHadFailed || endedFailed(run.sessionDir)
    return readProgress(run.sessionDir).copy(stopped = !failed, holdsDevice = !letGo)
  }

  /**
   * Waits, up to [abandonedSessionWaitMillis], for an abandoned [run] to let go of its device, as its
   * daemon says, else for its session to end; a daemon that can't say and no session mean nothing to
   * wait for. A status request that fails says nothing either way, so it waits on. False when the
   * run still holds the device at the end.
   */
  private fun awaitLetGo(run: FinishedRun): Boolean {
    fun over() = when (run.letGo?.invoke() ?: DaemonClient.RunRelease.UNKNOWN) {
      DaemonClient.RunRelease.LET_GO -> true
      DaemonClient.RunRelease.HOLDING, DaemonClient.RunRelease.UNREACHABLE -> false
      DaemonClient.RunRelease.UNKNOWN -> !run.sessionDir.exists() || sessionEnded(run.sessionDir)
    }
    if (over()) return true
    Console.info("Waiting for the ${if (run.cancelled) "cancelled" else "abandoned"} run to let go of the device...")
    val deadline = System.currentTimeMillis() + abandonedSessionWaitMillis
    while (System.currentTimeMillis() < deadline) {
      Thread.sleep(minOf(passPollMillis, 1_000L))
      if (over()) return true
    }
    return false
  }

  /**
   * Ends the loop for an abandoned run still holding the device: a recovery would run against it,
   * and --on-failure would get its session before capture stopped.
   */
  private fun stopForHeldDevice(): Boolean {
    Console.error(
      "Stopping: the abandoned run still holds the device after ${abandonedSessionWaitMillis / 1000}s, " +
        "so the next pass would run against it. --on-failure was not run for it.",
    )
    return finish()
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
    stopDeadline()
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
      iterations == null && timeLimit == null -> "loop needs --iterations, --time-limit, or both"
      iterations.let { it != null && it < 1 } -> "--iterations must be a positive integer (got $iterations)"
      timeLimit != null && parseTimeLimit(timeLimit) == null ->
        "--time-limit must be a positive duration in hours, minutes and seconds, such as 8h, 90m or 1h30m (got $timeLimit)"
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
    val exit = try {
      runTrails()
    } finally {
      finish()
    }
    return finalExitCode(exit)
  }

  /** The pass the time limit cancelled ended its run as a failure; it was not one, if nothing else was. */
  override fun finalExitCode(code: Int): Int =
    if (code == TrailblazeExitCode.ASSERTION_FAILED.code && limitCuts > 0 && failedPasses == 0 && otherFailures == 0) {
      TrailblazeExitCode.SUCCESS.code
    } else {
      code
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
  /** [holdsDevice]: an abandoned run that never let go of the device, so nothing more can run on it. */
  internal data class SessionProgress(
    val completedSteps: Int,
    val failure: String?,
    val stopped: Boolean = false,
    val holdsDevice: Boolean = false,
  )

  companion object {
    private val TIME_LIMIT = Regex("""^(?:(\d+)h)?(?:(\d+)m)?(?:(\d+)s)?$""")

    /** `8h`, `90m`, `1h30m` or `45s` as a positive duration, else null. */
    internal fun parseTimeLimit(value: String?): Duration? {
      val match = value?.trim()?.lowercase()?.let { TIME_LIMIT.matchEntire(it) } ?: return null
      val (h, m, s) = match.destructured
      val limit = (h.toLongOrNull() ?: 0).hours + (m.toLongOrNull() ?: 0).minutes + (s.toLongOrNull() ?: 0).seconds
      return limit.takeIf { it.isPositive() }
    }

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
      // A teardown runs after a failed step too, so it is neither a pass's step nor the failure, and
      // a run's setup runs before every trail, outside it.
      val completions = logs.filterIsInstance<TrailblazeLog.ObjectiveCompleteLog>()
        .filter { (it.promptStep as? DirectionStep)?.let { step -> step.isTeardown || step.isSetup } != true }
        .sortedBy { it.timestamp }
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
     * Whether [sessionDir]'s session is failing: it recorded a failed end, or its last step so far
     * failed, setup included, teardown not, and no self-heal took that step over.
     */
    internal fun failedSoFar(sessionDir: File): Boolean {
      val logs = sessionDir.listFiles { f: File ->
        f.name.endsWith("ObjectiveCompleteLog.json") || f.name.endsWith("SelfHealInvokedLog.json") ||
          f.name.endsWith("TrailblazeSessionStatusChangeLog.json")
      }.orEmpty().mapNotNull { file ->
        runCatching { TrailblazeJsonInstance.decodeFromString(TrailblazeLog.serializer(), file.readText()) }.getOrNull()
      }
      if (logs.any { it.endedFailed() }) return true
      val last = logs.filterIsInstance<TrailblazeLog.ObjectiveCompleteLog>()
        .filter { (it.promptStep as? DirectionStep)?.isTeardown != true }
        .maxByOrNull { it.timestamp }
        ?.takeIf { it.objectiveResult is AgentTaskStatus.Failure } ?: return false
      return logs.none { it is TrailblazeLog.SelfHealInvokedLog && it.timestamp >= last.timestamp }
    }

    /** Whether [sessionDir]'s session recorded a successful end. */
    internal fun endedSucceeded(sessionDir: File): Boolean = sessionDir.listFiles { f: File -> f.name.endsWith("TrailblazeSessionStatusChangeLog.json") }
      .orEmpty().any { file ->
        val status = (runCatching { TrailblazeJsonInstance.decodeFromString(TrailblazeLog.serializer(), file.readText()) }.getOrNull()
          as? TrailblazeLog.TrailblazeSessionStatusChangeLog)?.sessionStatus
        status is SessionStatus.Ended.Succeeded || status is SessionStatus.Ended.SucceededWithSelfHeal
      }

    /** Whether [sessionDir]'s session recorded a failed end. */
    internal fun endedFailed(sessionDir: File): Boolean = sessionDir.listFiles { f: File -> f.name.endsWith("TrailblazeSessionStatusChangeLog.json") }
      .orEmpty().any { file ->
        runCatching { TrailblazeJsonInstance.decodeFromString(TrailblazeLog.serializer(), file.readText()) }.getOrNull()?.endedFailed() == true
      }

    private fun TrailblazeLog.endedFailed(): Boolean = when (val status = (this as? TrailblazeLog.TrailblazeSessionStatusChangeLog)?.sessionStatus) {
      is SessionStatus.Ended.Failed, is SessionStatus.Ended.FailedWithSelfHeal, is SessionStatus.Ended.TimeoutReached,
      is SessionStatus.Ended.MaxCallsLimitReached -> true
      // An on-device handler that timed out records a cancel, but it failed: no one stopped it.
      is SessionStatus.Ended.Cancelled -> status.cancellationMessage == OnDeviceRpcTimeouts.HANDLER_AWAIT_CAP_MESSAGE
      else -> false
    }

    /** The message [sessionDir]'s session ended cancelled with, if it did. */
    internal fun cancellationMessage(sessionDir: File): String? =
      sessionDir.listFiles { f: File -> f.name.endsWith("_TrailblazeSessionStatusChangeLog.json") }.orEmpty().sorted().firstNotNullOfOrNull { file ->
        val log = runCatching { TrailblazeJsonInstance.decodeFromString(TrailblazeLog.serializer(), file.readText()) }.getOrNull()
        ((log as? TrailblazeLog.TrailblazeSessionStatusChangeLog)?.sessionStatus as? SessionStatus.Ended.Cancelled)?.cancellationMessage
      }

    /** Whether [sessionDir] has logged its session's end. */
    internal fun sessionEnded(sessionDir: File): Boolean =
      sessionDir.listFiles { f: File -> f.name.endsWith("_TrailblazeSessionStatusChangeLog.json") }.orEmpty().any { file ->
        val log = runCatching { TrailblazeJsonInstance.decodeFromString(TrailblazeLog.serializer(), file.readText()) }.getOrNull()
        (log as? TrailblazeLog.TrailblazeSessionStatusChangeLog)?.sessionStatus is SessionStatus.Ended
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
