package xyz.block.trailblaze.host.axe

import java.util.concurrent.ConcurrentHashMap

/**
 * The notifications-alert answer ("allow"/"deny") each app on one simulator was given by
 * `ios_setPermissions`' `notifications`. One per simulator ([forSimulator]), shared by every
 * [AxeDeviceManager] on it: a one-shot MCP or CLI call builds a new manager per tool, and the
 * answer one call arms must still hold for the next call's captures. A simulator moving onto another
 * session forgets them ([enterSession]), so one session's answer never taps a later session's alert.
 *
 * Kept per app, because the alert belongs to one: an answer is used only for an alert that quotes
 * that app's display name. Maestro's runner keeps one device-wide answer, so after "deny A, allow
 * B" it would tap Allow on A's alert too.
 */
class NotificationAlertAnswers {

  companion object {
    private val bySimulator = ConcurrentHashMap<String, NotificationAlertAnswers>()

    /**
     * The one set of answers for simulator [udid]. A YAML run and an MCP inspection can each hold
     * their own connection to the same simulator, and both read the one screen: separate answers
     * would let one tap with a stale answer, or tap an alert the other already dismissed.
     */
    fun forSimulator(udid: String): NotificationAlertAnswers = bySimulator.getOrPut(udid) { NotificationAlertAnswers() }

    /** Simulator [udid] moved onto session [sessionId]; see the instance [enterSession]. */
    fun enterSession(udid: String, sessionId: String) = forSimulator(udid).enterSession(sessionId)

    /** How many sessions [announceSession] remembers, so announcing a recent one again is a no-op. */
    private const val REMEMBERED_SESSIONS = 64
  }

  /** [value] null: this app's alerts were explicitly left to the user. Counts only in [generation]. */
  private data class Armed(val displayName: String?, val value: String?, val generation: Long)

  private val byBundleId = ConcurrentHashMap<String, Armed>()

  private val dismissLock = Any()

  /**
   * Guards [currentSession], [generation] and [enteredSessions]. Held only for those fields, never
   * across a tap: a session change must not wait on the simulator.
   */
  private val sessionLock = Any()

  private var currentSession: String? = null

  /** The sessions this simulator has been on, oldest first. */
  private val enteredSessions = LinkedHashSet<String>()

  /** Bumped by every session change. An answer armed in an earlier generation is not an answer. */
  @Volatile private var generation = 0L

  /** The current generation, read before a request starts its slow lookups (see [arm]). */
  fun generation(): Long = generation

  private fun current(bundleId: String): Armed? = byBundleId[bundleId]?.takeIf { it.generation == generation }

  /**
   * Arms [value] for [bundleId], whose alerts quote [displayName] (null if it couldn't be read).
   * Waits out an answer being tapped, so once this returns no tap can still use the old answer.
   *
   * False, arming nothing, when the simulator changed session after [since] was read: the request
   * belongs to a session that is gone, and its answer must not reach the next one's alert.
   */
  fun arm(bundleId: String, displayName: String?, value: String, since: Long = generation): Boolean =
    synchronized(dismissLock) {
      if (since != generation) return false
      byBundleId[bundleId] = Armed(displayName, value, since)
      true
    }

  /**
   * Leaves [bundleId]'s alerts unanswered again, once any answer being tapped has landed. It stays
   * on record as left alone, so another app's answer can't reach its alert through the one-armed-app
   * fallback in [answerFor]. False, like [arm], when the session changed after [since].
   *
   * It is recorded under the name it was armed under, else [displayName] (read only then, outside
   * the lock): another app sharing that name then matches an alert that names both, and the
   * conflicting answers leave that alert alone.
   */
  fun disarm(bundleId: String, since: Long = generation, displayName: () -> String? = { null }): Boolean {
    val name = current(bundleId)?.displayName ?: displayName()
    return synchronized(dismissLock) {
      if (since != generation) return false
      byBundleId[bundleId] = Armed(displayName = current(bundleId)?.displayName ?: name, value = null, generation = since)
      true
    }
  }

  /**
   * This simulator has moved onto session [sessionId]: unless it was already on it, every answer
   * armed so far stops counting. Doesn't wait for a tap in flight, which started in the earlier session.
   */
  fun enterSession(sessionId: String) {
    synchronized(sessionLock) { enterSessionLocked(sessionId) }
  }

  /**
   * A trail run is starting session [sessionId] here. A session the device manager already moved
   * this simulator onto, or any recent one, is no change: a run announces its session after the
   * move, and a late announcement of an earlier session must not wipe the current one's answers.
   * Only a session never seen here (a run that bypasses the device manager) starts afresh.
   */
  fun announceSession(sessionId: String) {
    synchronized(sessionLock) { if (sessionId !in enteredSessions) enterSessionLocked(sessionId) }
  }

  private fun enterSessionLocked(sessionId: String) {
    enteredSessions.remove(sessionId)
    enteredSessions.add(sessionId)
    if (enteredSessions.size > REMEMBERED_SESSIONS) enteredSessions.remove(enteredSessions.first())
    if (sessionId == currentSession) return
    currentSession = sessionId
    generation++
    byBundleId.values.removeIf { it.generation != generation }
  }

  /** True when no app's alert has an answer. */
  fun isEmpty(): Boolean = byBundleId.values.none { it.value != null && it.generation == generation }

  /**
   * Runs [block] while no other manager on this simulator is answering an alert. Two overlapping
   * reads can both see the alert; whichever answers second must re-read inside [block] and find it
   * gone, or its tap lands on the app behind it.
   */
  fun <T> dismissing(block: () -> T): T = synchronized(dismissLock) { block() }

  /**
   * The answer for an alert quoting [alertAppName]: that app's answer when an app with that name is
   * armed. When only one app is on record, its answer is used for any alert, as Maestro's single
   * answer is: the name `simctl appinfo` reports can differ from the one the alert quotes (a display
   * name set only in a localized InfoPlist.strings). With several on record (a disarmed app counts)
   * and no name match, null: leave it alone rather than guess which app asked.
   */
  fun answerFor(alertAppName: String?): String? {
    val armed = byBundleId.values.filter { it.generation == generation }
    val named = armed.filter { it.displayName != null && it.displayName.equals(alertAppName, ignoreCase = true) }
    if (named.isNotEmpty()) return named.map { it.value }.distinct().singleOrNull()
    return armed.singleOrNull()?.value
  }
}
