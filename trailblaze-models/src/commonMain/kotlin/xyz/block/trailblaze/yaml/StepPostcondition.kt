package xyz.block.trailblaze.yaml

import kotlinx.serialization.Serializable

/**
 * Optional structural assertion that runs after a [PromptStep]'s recorded tools complete.
 *
 * The trail runner polls the current screen state every [pollIntervalMs] until either the
 * waypoint identified by [waypoint] matches or [timeoutMs] elapses. Matching uses
 * `WaypointMatcher` over the loaded waypoint registry — all `required` selectors must be
 * present and no `forbidden` selector may be present.
 *
 * ## Why this exists
 *
 * Maestro and the AX driver layer report a tool as `successful` when the underlying gesture
 * event was dispatched without raising — not when the gesture achieved its intent. A
 * swipe-to-dismiss on a bottom sheet returns success even if the sheet stays on screen.
 * The downstream tap then fires against the wrong layer and "fails" several steps later,
 * obscuring the real cause. Declaring a postcondition pins the step's intended end-state
 * to a named waypoint, so a mismatch surfaces the actual failing step with the
 * missing-required / present-forbidden diff attached.
 *
 * ## Behavior across execution modes
 *
 *  - DETERMINISTIC: a mismatch hard-fails the trail at the declaring step.
 *  - RECORDING_WITH_FALLBACK / HYBRID / AI_ONLY: a mismatch is logged but the trail continues —
 *    the agent can self-heal on the next step. Treat the log entry as a strong signal that
 *    something drifted, not as a stop condition.
 *
 * @property waypoint id of the expected waypoint (e.g. `square/ios/more-tab-no-sheet`). Resolved
 *   against the loaded waypoint registry at run time.
 * @property timeoutMs total wait for the screen to settle into the expected waypoint. Default
 *   matches the trail-step `assertVisible` window so animations and async state commits land
 *   before the assertion fires.
 * @property pollIntervalMs how often to re-evaluate the waypoint match during the wait window.
 */
@Serializable
data class StepPostcondition(
  val waypoint: String,
  val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
  val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
) {
  init {
    require(waypoint.isNotBlank()) { "StepPostcondition.waypoint must be a non-blank waypoint id" }
    require(timeoutMs > 0) { "StepPostcondition.timeoutMs must be > 0 (got $timeoutMs)" }
    require(pollIntervalMs > 0) {
      "StepPostcondition.pollIntervalMs must be > 0 (got $pollIntervalMs)"
    }
  }

  companion object {
    const val DEFAULT_TIMEOUT_MS: Long = 5_000L
    const val DEFAULT_POLL_INTERVAL_MS: Long = 250L
  }
}
