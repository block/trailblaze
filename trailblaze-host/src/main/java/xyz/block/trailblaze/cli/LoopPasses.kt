package xyz.block.trailblaze.cli

import kotlinx.datetime.Instant
import xyz.block.trailblaze.agent.model.AgentTaskStatus
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.client.deviceClockOffsets
import xyz.block.trailblaze.logs.client.normalizedMs
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.logs.model.TrailblazeClockDomain
import xyz.block.trailblaze.yaml.DirectionStep
import java.io.File

/**
 * A distribution's addition to `trailblaze loop`, added to the command as a picocli mixin so its
 * options parse with the loop's. Each device's loop asks it for a [LoopListener].
 */
interface LoopExtension {
  /** Why the given options can't run, checked before the loop touches a device; null when they can. */
  fun misuse(): String? = null

  /** A listener for one device's loop, or null when this extension's options weren't given. */
  fun startLoop(loop: LoopStart): LoopListener?
}

/** One device's loop, as it starts. [captures] is false when its sessions record no video, log or memory. */
data class LoopStart(val trailFile: File, val iterations: Int, val startedAt: Instant, val captures: Boolean = true)

/**
 * Hears about the passes of one device's loop. [passFinished] is called from the loop's own
 * threads, as passes complete, so it must hand slow work off rather than do it.
 */
interface LoopListener {
  fun passFinished(pass: LoopPass)

  /**
   * The device's loop is over, every pass reported; the loop exits once this returns. Ctrl+C exits
   * without calling it, so a listener with something left to send hooks the JVM's shutdown itself.
   */
  fun loopFinished()
}

/**
 * One pass of a device's loop. [index] counts from 0 over the whole loop, recovery sessions
 * included. The window is on the host clock: from its first step's start to its last step's end,
 * or to the failure for a failed pass. [sessionDir] holds the pass among the session's others.
 */
data class LoopPass(
  val index: Int,
  val startedAt: Instant,
  val endedAt: Instant,
  val ok: Boolean,
  val error: String? = null,
  val sessionDir: File,
)

/** A pass's time window on the host clock, in epoch millis. */
data class PassWindow(val startMs: Long, val endMs: Long)

/**
 * The pass windows of one loop session, read from its objective and session status logs.
 * [completed] are the passes whose every step succeeded, in order. [unfinished] runs from the start
 * of the pass after them to the failed step's end, else to the session's recorded end, and is null
 * when no step started after them. In a session that failed it is the failed pass; one that failed
 * at its trailhead has no completed pass, and its unfinished window starts at the trailhead, or at
 * the session's start when it failed before any step started. [endedMs] is when the session recorded
 * its end, null while it runs.
 */
data class SessionPassWindows(val completed: List<PassWindow>, val unfinished: PassWindow?, val endedMs: Long? = null)

/**
 * Reads [SessionPassWindows] from session directories, caching each decoded log by file name, so
 * that polling a session for hours decodes each log once. A log file is never rewritten once it
 * decodes, which is what makes the cache safe. Only the last session read is cached.
 */
class LoopPassReader {
  private val decoded = mutableMapOf<String, TrailblazeLog>()
  private var cachedSession: File? = null

  fun read(sessionDir: File, stepsPerPass: Int): SessionPassWindows {
    if (sessionDir != cachedSession) {
      decoded.clear()
      cachedSession = sessionDir
    }
    val objectiveLogs = logs(sessionDir) {
      // No underscore: logs pulled off a device are named with their type straight after a number.
      it.endsWith("ObjectiveStartLog.json") || it.endsWith("ObjectiveCompleteLog.json") ||
        it.endsWith("TrailblazeSessionStatusChangeLog.json") || it.endsWith("SelfHealInvokedLog.json")
    }.distinctBy { it.withoutReceipt() }
    // Device-stamped objective logs come from an on-device runner; its tool logs carry the anchors
    // that put them on the host clock.
    val offsets = if (objectiveLogs.any { it.clock == TrailblazeClockDomain.DEVICE }) {
      (objectiveLogs + logs(sessionDir) { it.endsWith("TrailblazeToolLog.json") }).deviceClockOffsets()
    } else {
      null
    }
    return passWindows(objectiveLogs.map { it to it.normalizedMs(offsets) }, stepsPerPass)
  }

  private fun logs(sessionDir: File, nameFilter: (String) -> Boolean): List<TrailblazeLog> =
    // By number, not name: a log's number has three digits until it needs more, so 1000 sorts before 999.
    sessionDir.listFiles { f: File -> nameFilter(f.name) }.orEmpty().sortedWith(
      compareBy({ LOG_NUMBER.find(it.name)?.value?.toBigInteger() }, { it.name }),
    ).mapNotNull { file ->
      // A log that doesn't decode may be one still being written, so it is read again next time.
      decoded[file.name] ?: runCatching {
        TrailblazeJsonInstance.decodeFromString(TrailblazeLog.serializer(), file.readText())
      }.getOrNull()?.also { decoded[file.name] = it }
    }

  companion object {
    private val LOG_NUMBER = Regex("\\d+")

    /**
     * [logs] paired with their host-clock times. Step j of the trail is the j-th successful step
     * that is neither trailhead nor teardown; it starts at the first step start after step j-1
     * succeeded, so a self-healed step's window covers its failed attempt.
     */
    internal fun passWindows(logs: List<Pair<TrailblazeLog, Long>>, stepsPerPass: Int): SessionPassWindows {
      if (stepsPerPass <= 0) return SessionPassWindows(emptyList(), null)
      // Stable, so logs stamped in the same millisecond keep the order they were written in, and
      // steps are matched by that order rather than by time.
      val sorted = logs.sortedBy { it.second }
      fun ms(i: Int) = sorted[i].second
      fun step(i: Int) = when (val log = sorted[i].first) {
        is TrailblazeLog.ObjectiveStartLog -> log.promptStep
        is TrailblazeLog.ObjectiveCompleteLog -> log.promptStep
        else -> null
      }
      fun result(i: Int) = (sorted[i].first as? TrailblazeLog.ObjectiveCompleteLog)?.objectiveResult
      // The trailhead's setup and its teardown bookend the trail; neither is a step of a pass.
      val lifecycle = sorted.indices.filter { step(it)?.isTeardown() == false }
      val trailheadFirst = lifecycle.firstOrNull { step(it)!!.isTrailhead() }
      val trailheadEnd = lifecycle.lastOrNull { result(it) != null && step(it)!!.isTrailhead() }
      // A setup trail's steps run before the trailhead, so they are no step of a pass either.
      val passSteps = lifecycle.filter { !step(it)!!.isTrailhead() && it > (trailheadEnd ?: trailheadFirst ?: -1) }
      val starts = passSteps.filter { result(it) == null }
      val successes = passSteps.filter { result(it) is AgentTaskStatus.Success }

      // A step that succeeded but whose start log never reached the host starts where the step before
      // it ended, rather than taking the next step's start.
      fun stepStart(step: Int): Int? {
        val after = if (step == 0) trailheadEnd else successes[step - 1]
        val end = successes.getOrNull(step)
        // A search rather than a scan, so a session of thousands of passes isn't read in quadratic time.
        val first = after?.let { -starts.binarySearch(it) - 1 } ?: 0
        return starts.getOrNull(first)?.takeIf { end == null || it < end } ?: end?.let { after ?: 0 }
      }

      val completed = (0 until successes.size / stepsPerPass).map { pass ->
        PassWindow(ms(stepStart(pass * stepsPerPass)!!), ms(successes[(pass + 1) * stepsPerPass - 1]))
      }
      // A failed step ends the pass, unless a self-heal was invoked or started after it. A session that
      // failed some other way, say its driver died mid-step, ends it when the session recorded its end.
      val failed = lifecycle.lastOrNull()?.takeIf { result(it) is AgentTaskStatus.Failure }
      val failedStepEnd = failed?.takeIf { (it + 1 until sorted.size).none { i -> sorted[i].first is TrailblazeLog.SelfHealInvokedLog } }?.let(::ms)
      val unfinishedStart = stepStart(completed.size * stepsPerPass)?.let(::ms) ?: when {
        // A failed step whose start log never reached the host starts where the step before it ended.
        failed != null && failed in passSteps && (successes.isNotEmpty() || trailheadEnd != null) -> ms(successes.lastOrNull() ?: trailheadEnd!!)
        // A session that failed while it set up failed from its trailhead's start, else over its whole span.
        successes.isEmpty() -> lifecycle.firstOrNull { result(it) == null && it >= (trailheadFirst ?: 0) }?.let(::ms) ?: sorted.firstOrNull()?.second
        else -> null
      }
      val sessionEnd = sorted.lastOrNull { (log, _) ->
        (log as? TrailblazeLog.TrailblazeSessionStatusChangeLog)?.sessionStatus is SessionStatus.Ended
      }?.second
      val unfinished = unfinishedStart?.let { start ->
        PassWindow(start, maxOf(start, failedStepEnd?.takeIf { it >= start } ?: sessionEnd ?: start))
      }
      return SessionPassWindows(completed, unfinished, sessionEnd)
    }

    /** A log the runner wrote again, after its upload failed once the host had it, differs only in its receipt. */
    private fun TrailblazeLog.withoutReceipt(): TrailblazeLog = when (this) {
      is TrailblazeLog.ObjectiveStartLog -> copy(hostReceivedAt = null)
      is TrailblazeLog.ObjectiveCompleteLog -> copy(hostReceivedAt = null)
      else -> this
    }

    private fun xyz.block.trailblaze.yaml.PromptStep.isTrailhead() = this is DirectionStep && isTrailhead

    private fun xyz.block.trailblaze.yaml.PromptStep.isTeardown() = this is DirectionStep && isTeardown
  }
}
