package xyz.block.trailblaze.android.accessibility

/**
 * Decides when a poll loop may conclude that an element is NOT on screen.
 *
 * Presence is cheap to prove: one capture that contains the element settles it. Absence is not.
 * A capture can lack the element because it is gone, or because the capture is missing the part
 * of the screen that holds it — every node in a capture is a live fetch from the app, and an app
 * whose main thread is blocked answers those fetches with null, so its tree comes back PARTIAL
 * (see [TreeCaptureTally]). A "wait until the loading screen is gone" that accepted such a tree
 * released while the loading screen was still up, and the step after it ran against the wrong
 * screen.
 *
 * So an absence verdict needs [confirmationsRequired] CONSECUTIVE captures that are each
 * complete and each lack the element. A partial capture proves nothing and a capture that
 * contains the element disproves the streak; both reset it. Pure state, no timing, so the policy
 * is unit-testable without a device.
 */
internal class AbsenceConfirmation(
  private val confirmationsRequired: Int = DEFAULT_CONFIRMATIONS_REQUIRED,
) {
  /** What one poll observed. */
  enum class Observation {
    /** The element resolved in a capture (complete or not: presence in a partial tree is real). */
    PRESENT,

    /** No match, in a capture with every advertised node present. Counts toward confirmation. */
    ABSENT_IN_COMPLETE_CAPTURE,

    /** No match, but the capture dropped node fetches. Proves nothing. */
    ABSENT_IN_PARTIAL_CAPTURE,

    /** No tree at all (no window root resolved). Proves nothing. */
    NO_TREE,
  }

  init {
    require(confirmationsRequired >= 1) { "confirmationsRequired must be >= 1, was $confirmationsRequired" }
  }

  private var streak = 0

  /** Number of polls that saw the element. */
  var presentCount: Int = 0
    private set

  /** Number of polls whose capture was partial or missing, so could not speak to absence. */
  var inconclusiveCount: Int = 0
    private set

  /** Number of polls that saw a complete capture without the element. */
  var completeAbsentCount: Int = 0
    private set

  /**
   * Records [observation] and returns true once absence stands confirmed — i.e. this was the
   * [confirmationsRequired]-th consecutive [Observation.ABSENT_IN_COMPLETE_CAPTURE].
   */
  fun observe(observation: Observation): Boolean {
    when (observation) {
      Observation.PRESENT -> {
        presentCount++
        streak = 0
      }
      Observation.ABSENT_IN_PARTIAL_CAPTURE, Observation.NO_TREE -> {
        inconclusiveCount++
        streak = 0
      }
      Observation.ABSENT_IN_COMPLETE_CAPTURE -> {
        completeAbsentCount++
        streak++
      }
    }
    return streak >= confirmationsRequired
  }

  /**
   * True while a streak is in progress: a complete capture lacked the element but the verdict
   * needs another one. A loop at its deadline should take that one more poll rather than fail a
   * check whose evidence so far all says "gone".
   */
  val awaitingConfirmation: Boolean get() = streak in 1 until confirmationsRequired

  /**
   * Why the check could not conclude absence, for the failure message. Distinguishes "the
   * element was there" from "we never got a tree we could trust", because the second one is a
   * capture problem, not a UI one, and a reader chasing it should not go looking at the screen.
   */
  fun describeFailure(): String = when {
    presentCount == 0 && completeAbsentCount == 0 && inconclusiveCount > 0 ->
      "no complete accessibility tree was captured in $inconclusiveCount poll(s) — the app was " +
        "not answering node fetches, so absence could not be confirmed"
    presentCount == 0 && completeAbsentCount > 0 ->
      "absent in $completeAbsentCount complete capture(s) but never in $confirmationsRequired " +
        "consecutive ones ($inconclusiveCount inconclusive poll(s) in between)"
    else ->
      "still present (seen in $presentCount poll(s); $completeAbsentCount complete capture(s) " +
        "without it, $inconclusiveCount inconclusive)"
  }

  companion object {
    /**
     * Two consecutive complete captures. One guards against a partial tree; the second guards
     * against the window a capture can land in between two screens, where a complete tree is
     * legitimately near-empty for a frame. Each capture already waits for the tree to stop
     * changing, so the second one costs one more capture, not a fixed sleep.
     */
    const val DEFAULT_CONFIRMATIONS_REQUIRED = 2
  }
}
