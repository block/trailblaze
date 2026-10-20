package xyz.block.trailblaze.history

import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.util.Console

/** One tool call in a session, as `ctx.history.toolCalls()` returns it. Times are epoch milliseconds. */
@Serializable
data class HistoryToolCall(
  val id: String,
  val name: String,
  /**
   * The arguments the tool received: memory tokens resolved, except sensitive ones, which stay
   * `{{token}}`. Declared sensitive args are masked.
   */
  val args: JsonObject,
  /** The arguments as written, tokens unresolved, masked the same way. Equal to [args] when nothing resolved. */
  val rawArgs: JsonObject,
  /** Null while the call is still running: the reading tool itself and the calls it is nested in. */
  val outcome: HistoryToolCallOutcome?,
  val startedAt: Long,
  val endedAt: Long?,
  /** The call this one ran inside (a tool composing tools), or null for a step of the trail. */
  val parentId: String?,
  /** The device binding it ran on in a multi-device session, or null for one device. */
  val device: String?,
  /** The section open when it started, or null outside any section. */
  val sectionId: String?,
)

@Serializable
data class HistoryToolCallOutcome(
  val success: Boolean,
  /** The result's message or error, with sensitive memory values scrubbed. Capped in length. */
  val message: String?,
)

/** A stretch of the run: the trailhead, the trail, the teardown. Times are epoch milliseconds. */
@Serializable
data class HistorySection(
  val id: String,
  val name: String,
  /** One of [HistorySectionKind]. */
  val kind: String,
  /** The section this one is nested in, or null for a top-level section. */
  val parentId: String?,
  val startedAt: Long,
  /** Null while the section is open. */
  val endedAt: Long?,
)

/** The section kinds the runner opens. Later kinds (loop iterations, custom sections) join these. */
object HistorySectionKind {
  const val TRAILHEAD: String = "trailhead"
  const val TRAIL: String = "trail"
  const val TEARDOWN: String = "teardown"

  /**
   * One whole trail run, holding its trailhead, trail and teardown sections. A session can hold
   * several runs, so "the trail" means the one inside the latest run.
   */
  const val RUN: String = "run"

  /** The sections every trail run has, one of each; the names a script reads calls by. */
  val RUN_SECTIONS: Set<String> = setOf(TRAILHEAD, TRAIL, TEARDOWN)
}

/**
 * Told when a section starts and ends, at the boundary, on the runner's thread. For bookkeeping
 * keyed to sections, such as recording where each event stream stood at the boundary. Keep it
 * short and never throw: the runner waits for it, and an exception is logged and dropped.
 */
interface HistorySectionListener {
  fun onSectionStart(sessionId: SessionId, section: HistorySection) {}

  /** [section] carries its `endedAt`. */
  fun onSectionEnd(sessionId: SessionId, section: HistorySection) {}
}

/**
 * The framework's record of what ran in each session: every tool call and the sections they ran
 * in. Written by the framework as the run happens — a call is in it from the moment it starts, and
 * has its outcome the moment it returns — so a read is complete when it is made. Nothing here
 * lags or is reconstructed from logs.
 *
 * Process-wide, keyed by session, because tool dispatch and the trail runner reach it from
 * different places and both run in the process that runs the trail: the host daemon, or the
 * device for a trail running inside a test APK. Only the most recently used [MAX_SESSIONS] sessions
 * are kept, plus any still running a call or holding an open section, up to
 * [MAX_SESSIONS_WITH_ACTIVE].
 *
 * A call's parent is the innermost call still running in the session when it starts. That holds
 * for the way tools compose today, each nested call awaited before the next one starts. Two nested
 * calls a tool runs at the same time (`Promise.all`) can name each other as parent.
 */
object ToolCallHistory {

  const val MAX_SESSIONS: Int = 32

  /** Past this many sessions the oldest goes even if it is active, so runs that died mid-call can't pin memory. */
  const val MAX_SESSIONS_WITH_ACTIVE: Int = MAX_SESSIONS * 4

  /** Most calls kept per session, so a long daemon session doesn't keep every call it ever made. */
  const val MAX_CALLS_PER_SESSION: Int = 10_000

  /** Longest outcome message kept; the rest is cut. */
  const val MAX_MESSAGE_CHARS: Int = 2000

  /** Most sections kept per session; a reused daemon session opens a run's sections per replay. */
  const val MAX_SECTIONS_PER_SESSION: Int = 1_000

  /** A started call, to pass back to [end]. */
  class CallHandle internal constructor(internal val sessionId: SessionId, internal val id: String)

  private class CallRecord(
    val id: String,
    val name: String,
    val args: JsonObject,
    val rawArgs: JsonObject,
    val startedAt: Long,
    val parentId: String?,
    val device: String?,
    val sectionId: String?,
  ) {
    var outcome: HistoryToolCallOutcome? = null
    var endedAt: Long? = null

    fun snapshot() = HistoryToolCall(id, name, args, rawArgs, outcome, startedAt, endedAt, parentId, device, sectionId)
  }

  private class SectionRecord(
    val id: String,
    val name: String,
    val kind: String,
    val parentId: String?,
    val startedAt: Long,
  ) {
    var endedAt: Long? = null

    fun snapshot() = HistorySection(id, name, kind, parentId, startedAt, endedAt)
  }

  /** One session's history. Every access holds its lock. */
  private class SessionHistory {
    val calls = ArrayList<CallRecord>()
    val runningCalls = ArrayList<CallRecord>()
    val sections = ArrayList<SectionRecord>()
    val openSections = ArrayList<SectionRecord>()
    var nextCallNumber = 1
    var nextSectionNumber = 1

    /** Appends [record]; past [MAX_CALLS_PER_SESSION], the oldest finished call goes. */
    fun add(record: CallRecord) {
      calls += record
      if (calls.size > MAX_CALLS_PER_SESSION) {
        val oldestFinished = calls.indexOfFirst { it.outcome != null }
        if (oldestFinished >= 0) calls.removeAt(oldestFinished)
      }
    }

    /**
     * Past [MAX_SECTIONS_PER_SESSION], drops the oldest closed top-level section and the sections
     * inside it. Its calls stay in whole-session reads.
     */
    fun pruneSections() {
      if (sections.size <= MAX_SECTIONS_PER_SESSION) return
      val oldest = sections.firstOrNull { it.parentId == null && it.endedAt != null } ?: return
      // Sections are stored in open order, so a nested section always follows its parent.
      val dropped = mutableSetOf(oldest.id)
      sections.removeAll { section ->
        (section.id in dropped || section.parentId in dropped).also { if (it) dropped += section.id }
      }
    }
  }

  /** Least recently used first: [usedHistoryOf] moves a session to the end each time it is read. */
  private val sessions = LinkedHashMap<SessionId, SessionHistory>()

  /** Replaced whole on each change, so [notify] walks a list no one changes under it. */
  private var listeners: List<HistorySectionListener> = emptyList()

  private fun now(): Long = Clock.System.now().toEpochMilliseconds()

  private fun historyOf(sessionId: SessionId): SessionHistory = withHistoryLock(sessions) {
    usedHistoryOf(sessionId) ?: SessionHistory().also { created ->
      sessions[sessionId] = created
      evictPast(created)
    }
  }

  /** [sessionId]'s history, now the most recently used. Holds the store's lock. */
  private fun usedHistoryOf(sessionId: SessionId): SessionHistory? =
    sessions.remove(sessionId)?.also { sessions[sessionId] = it }

  /**
   * Drops the least recently used sessions past [MAX_SESSIONS], skipping active ones: a long trail
   * must keep its history while short sessions start and end beside it. [created] is never dropped.
   * Holds the store's lock; takes each session's after it, the one order used anywhere.
   */
  private fun evictPast(created: SessionHistory) {
    val iterator = sessions.values.iterator()
    while (sessions.size > MAX_SESSIONS && iterator.hasNext()) {
      val history = iterator.next()
      if (history === created) continue
      val active = withHistoryLock(history) { history.runningCalls.isNotEmpty() || history.openSections.isNotEmpty() }
      if (!active || sessions.size > MAX_SESSIONS_WITH_ACTIVE) iterator.remove()
    }
  }

  private fun existingHistoryOf(sessionId: SessionId): SessionHistory? = withHistoryLock(sessions) { usedHistoryOf(sessionId) }

  /** Records a call as it starts. Its parent and section are whatever is running and open now. */
  fun begin(
    sessionId: SessionId,
    name: String,
    args: JsonObject,
    device: String?,
    startedAt: Long = now(),
    rawArgs: JsonObject = args,
  ): CallHandle {
    val history = historyOf(sessionId)
    val id = withHistoryLock(history) {
      val record = CallRecord(
        id = "c${history.nextCallNumber++}",
        name = name,
        args = args,
        rawArgs = rawArgs,
        startedAt = startedAt,
        parentId = history.runningCalls.lastOrNull()?.id,
        device = device,
        sectionId = history.openSections.lastOrNull()?.id,
      )
      history.add(record)
      history.runningCalls += record
      record.id
    }
    return CallHandle(sessionId, id)
  }

  /** Records how a call [begin] started ended. A second call for the same handle is ignored. */
  fun end(handle: CallHandle, success: Boolean, message: String?, endedAt: Long = now()) {
    val history = existingHistoryOf(handle.sessionId) ?: return
    withHistoryLock(history) {
      val index = history.runningCalls.indexOfLast { it.id == handle.id }
      if (index < 0) return
      val record = history.runningCalls.removeAt(index)
      record.outcome = HistoryToolCallOutcome(success, message?.take(MAX_MESSAGE_CHARS))
      record.endedAt = endedAt
    }
  }

  /** Whether a call is running in [sessionId] right now. */
  fun hasRunningCall(sessionId: SessionId): Boolean =
    existingHistoryOf(sessionId)?.let { withHistoryLock(it) { it.runningCalls.isNotEmpty() } } ?: false

  /**
   * Records a call that is only seen once it has finished: one logged by a path that doesn't go
   * through the agent's tool dispatch. Skipped while a call is running in the session, because
   * that call already stands for whatever it logs.
   */
  fun recordFinished(
    sessionId: SessionId,
    name: String,
    args: JsonObject,
    device: String?,
    startedAt: Long,
    success: Boolean,
    message: String?,
    endedAt: Long = now(),
    rawArgs: JsonObject = args,
  ) {
    val history = historyOf(sessionId)
    withHistoryLock(history) {
      if (history.runningCalls.isNotEmpty()) return
      history.add(
        CallRecord(
          id = "c${history.nextCallNumber++}",
        name = name,
        args = args,
        rawArgs = rawArgs,
        startedAt = startedAt,
        parentId = null,
        device = device,
        sectionId = history.openSections.lastOrNull()?.id,
        ).also {
          it.outcome = HistoryToolCallOutcome(success, message?.take(MAX_MESSAGE_CHARS))
          it.endedAt = endedAt
        },
      )
    }
  }

  /** Opens a section inside the innermost open one, and tells the listeners. */
  fun openSection(
    sessionId: SessionId,
    kind: String,
    name: String,
    startedAt: Long = now(),
  ): HistorySection {
    val history = historyOf(sessionId)
    val section = withHistoryLock(history) {
      val record = SectionRecord(
        id = "s${history.nextSectionNumber++}",
        name = name,
        kind = kind,
        parentId = history.openSections.lastOrNull()?.id,
        startedAt = startedAt,
      )
      history.sections += record
      history.openSections += record
      history.pruneSections()
      record.snapshot()
    }
    notify(sessionId, section) { listener, s -> listener.onSectionStart(sessionId, s) }
    return section
  }

  /** Closes [sectionId] and any section still open inside it, innermost first. */
  fun closeSection(sessionId: SessionId, sectionId: String, endedAt: Long = now()) {
    val history = existingHistoryOf(sessionId) ?: return
    val closed = withHistoryLock(history) {
      val index = history.openSections.indexOfFirst { it.id == sectionId }
      if (index < 0) return
      val closing = history.openSections.subList(index, history.openSections.size)
      closing.reversed().map { record ->
        record.endedAt = endedAt
        record.snapshot()
      }.also { closing.clear() }
    }
    closed.forEach { section -> notify(sessionId, section) { listener, s -> listener.onSectionEnd(sessionId, s) } }
  }

  /** Every call in the session in start order, or only those that started in [sectionId] or a section inside it. */
  fun toolCalls(sessionId: SessionId, sectionId: String? = null): List<HistoryToolCall> {
    val history = existingHistoryOf(sessionId) ?: return emptyList()
    return withHistoryLock(history) {
      if (sectionId == null) return@withHistoryLock history.calls.map { it.snapshot() }
      val scope = mutableSetOf(sectionId)
      // Sections are stored in open order, so a nested section always follows its parent.
      history.sections.forEach { if (it.parentId in scope) scope += it.id }
      history.calls.filter { it.sectionId in scope }.map { it.snapshot() }
    }
  }

  /** Every section in the session, in the order they opened. */
  fun sections(sessionId: SessionId): List<HistorySection> {
    val history = existingHistoryOf(sessionId) ?: return emptyList()
    return withHistoryLock(history) { history.sections.map { it.snapshot() } }
  }

  fun addSectionListener(listener: HistorySectionListener) {
    withHistoryLock(sessions) { if (listener !in listeners) listeners = listeners + listener }
  }

  fun removeSectionListener(listener: HistorySectionListener) {
    withHistoryLock(sessions) { listeners = listeners - listener }
  }

  private inline fun notify(
    sessionId: SessionId,
    section: HistorySection,
    call: (HistorySectionListener, HistorySection) -> Unit,
  ) {
    for (listener in withHistoryLock(sessions) { listeners }) {
      try {
        call(listener, section)
      } catch (e: Exception) {
        Console.log(
          "[ToolCallHistory] ${listener::class.simpleName} failed at section ${section.id} of ${sessionId.value}: " +
            "${e::class.simpleName}: ${e.message}",
        )
      }
    }
  }

  /** Test seam. */
  internal fun clear() {
    withHistoryLock(sessions) { sessions.clear() }
  }
}
