package xyz.block.trailblaze.toolcalls.commands

import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * The early stop behind `findSelectorMatches(stopWhenSettled = true)`: a wait for something to
 * APPEAR ends before its timeout once the screen has settled — the captured tree unchanged for
 * [quietWindowMs] and the platform reporting the app idle ([appIdleProbe]).
 *
 * Why it exists: a setup check like "is the starting-balance prompt here?" waits for something
 * that never appears on most accounts, so it normally runs its whole timeout. Once the app is idle
 * and the screen has stopped changing, nothing is coming, and the wait can answer "no" now.
 *
 * Why it is opt-in per query, and appear-waits only:
 * - A static loading screen sends no accessibility events and looks settled. A wait for that screen
 *   to GO AWAY would stop early with a false "still there", so absence waits never use this.
 * - The same screen could, in principle, make an appear-wait answer "no" before a slow network call
 *   pops its dialog. [quietWindowMs] bounds that risk; the caller accepts it by opting in.
 */
object SettledScreen {

  /**
   * Whether the app under test is idle right now, given the quiet window the caller wants. Set by
   * the driver that can tell (the Android accessibility driver, on the device). Null means no
   * signal: an opted-in wait then runs to its timeout exactly as before.
   */
  @Volatile
  var appIdleProbe: ((quietWindowMs: Long) -> Boolean)? = null

  /** How long the screen must stay unchanged, and the app quiet, before a wait may stop. */
  const val DEFAULT_QUIET_WINDOW_MS = 1_500L

  /** A mutable seam so tests avoid real 1.5s sleeps; production uses [DEFAULT_QUIET_WINDOW_MS]. */
  @Volatile
  var quietWindowMs: Long = DEFAULT_QUIET_WINDOW_MS

  /**
   * One per wait. Feed it each complete capture's tree signature with [settled]; call [reset] on
   * any capture that cannot count toward a quiet screen (no tree, a partial capture, a match).
   */
  internal class Tracker(
    private val appIdle: (quietWindowMs: Long) -> Boolean,
    private val quietWindowMs: Long,
    private val timeSource: TimeSource = TimeSource.Monotonic,
  ) {
    private var signature: Int? = null
    private var stableSince: TimeMark? = null
    private var idleConfirmed = false

    /**
     * True when [treeSignature] has held for the quiet window, the app reported idle, and a capture
     * taken AFTER that idle check still shows the same tree. The idle check can block; something
     * that appears while it runs must be seen by a fresh capture, never answered "no" from the
     * capture before it.
     */
    fun settled(treeSignature: Int): Boolean {
      val since = stableSince
      if (since == null || treeSignature != signature) {
        signature = treeSignature
        stableSince = timeSource.markNow()
        idleConfirmed = false
        return false
      }
      if (idleConfirmed) return true
      idleConfirmed = since.elapsedNow() >= quietWindowMs.milliseconds && appIdle(quietWindowMs)
      return false
    }

    fun reset() {
      signature = null
      stableSince = null
      idleConfirmed = false
    }
  }
}
