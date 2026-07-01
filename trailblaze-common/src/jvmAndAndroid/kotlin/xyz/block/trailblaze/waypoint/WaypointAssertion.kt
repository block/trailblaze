package xyz.block.trailblaze.waypoint

import kotlinx.coroutines.delay
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.TargetTemplateContext
import xyz.block.trailblaze.api.waypoint.WaypointDefinition
import xyz.block.trailblaze.api.waypoint.WaypointMatchResult

/**
 * Polls a named waypoint against the live screen state until it matches or a timeout elapses.
 *
 * This is the reusable engine behind the `assertWaypoint` framework tool. It holds no state —
 * callers wire in a [screenStateProvider] for the device and a [waypointResolver] for the loaded
 * waypoint registry. The poll loops every [pollIntervalMs] until either the waypoint matches or
 * [timeoutMs] expires, then returns a structured [Result] the caller renders into a tool result.
 *
 * Re-evaluating in a loop is deliberate: a step's tools usually finish before the UI has settled
 * (animations, async server commits). A single post-tools snapshot would race with that settling —
 * the poll lets the screen catch up to its intended state without baking a hardcoded sleep into
 * every assertion.
 *
 * Matching uses [WaypointMatcher] against the loaded waypoint registry — all `required` selectors
 * must be present and no `forbidden` selector may be present, for the device's classifier block.
 */
object WaypointAssertion {

  /** Default total wait for the screen to settle into the expected waypoint. */
  const val DEFAULT_TIMEOUT_MS: Long = 5_000L

  /** Default interval between waypoint re-evaluations during the wait window. */
  const val DEFAULT_POLL_INTERVAL_MS: Long = 250L

  /**
   * Runs the waypoint poll. Always exits within `timeoutMs + one final evaluation`. The returned
   * result type discriminates the observable outcomes so the caller can render distinct messages
   * instead of folding everything into a generic "failed" string.
   *
   * @param waypointId id of the expected waypoint (e.g. `square/ios/more-tab-no-sheet`). Resolved
   *   against the loaded registry via [waypointResolver].
   * @param timeoutMs total wait for the screen to settle into the expected waypoint.
   * @param pollIntervalMs how often to re-evaluate the waypoint match during the wait window.
   * @param screenStateProvider returns the current device screen state; may return `null` when the
   *   driver hasn't produced one yet (e.g. mid-launch). Transient `null` returns are tolerated —
   *   only a persistent `null` at the timeout boundary surfaces as [Result.NoScreenState].
   * @param waypointResolver looks up a [WaypointDefinition] by id from the loaded registry.
   *   Resolution failures surface as [Result.WaypointNotFound] without polling, since they indicate
   *   a misconfigured trail rather than a runtime mismatch.
   * @param now timestamp source in epoch-ms. Defaults to [System.currentTimeMillis]; tests inject a
   *   deterministic clock to verify timeout boundaries without sleeping.
   * @param target optional [TargetTemplateContext] forwarded to the matcher so waypoint selectors
   *   carrying `{{target.appId}}` placeholders expand against the session's resolved app id. Null
   *   when there is no target context — the matcher then short-circuits any templated waypoint to a
   *   `UNRESOLVED_TARGET_TEMPLATE` skip (fail-closed; a `forbidden`-only placeholder would otherwise
   *   silently let the waypoint pass).
   */
  suspend fun poll(
    waypointId: String,
    timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
    screenStateProvider: suspend () -> ScreenState?,
    waypointResolver: (String) -> WaypointDefinition?,
    now: () -> Long = { System.currentTimeMillis() },
    target: TargetTemplateContext? = null,
  ): Result {
    val definition = waypointResolver(waypointId)
      ?: return Result.WaypointNotFound(waypointId)

    val deadline = now() + timeoutMs
    var lastResult: WaypointMatchResult? = null
    var lastScreenWasNull = true

    while (true) {
      val screen = screenStateProvider()
      if (screen != null) {
        lastScreenWasNull = false
        val result = WaypointMatcher.match(definition, screen, target)
        lastResult = result
        if (result.matched) {
          return Result.Matched(definition.id, result)
        }
      }
      val remaining = deadline - now()
      if (remaining <= 0) break
      delay(minOf(pollIntervalMs, remaining))
    }

    if (lastScreenWasNull) {
      return Result.NoScreenState(definition.id, timeoutMs)
    }
    // Invariant: lastScreenWasNull is false here, and the only place that clears it (the
    // `screen != null` branch above) also always assigns lastResult — so lastResult is non-null.
    // Guarded (rather than `!!`) so a future change to the loop fails with a clear message.
    return Result.NotMatched(
      definitionId = definition.id,
      lastResult = lastResult ?: error("lastResult must be set once a non-null screen was evaluated"),
      timeoutMs = timeoutMs,
    )
  }

  sealed interface Result {
    /** Waypoint matched within the timeout window. */
    data class Matched(val definitionId: String, val matchResult: WaypointMatchResult) : Result

    /** Polling finished without a match. [lastResult] carries the most recent diff. */
    data class NotMatched(
      val definitionId: String,
      val lastResult: WaypointMatchResult,
      val timeoutMs: Long,
    ) : Result

    /** Trail referenced a waypoint id that the loaded registry could not resolve. */
    data class WaypointNotFound(val requestedId: String) : Result

    /** Screen state provider returned `null` for the entire wait window. */
    data class NoScreenState(val definitionId: String, val timeoutMs: Long) : Result
  }

  /**
   * Human-readable summary of a [Result.NotMatched], formatted for tool error messages and report
   * templates. Lists every missing-required and every present-forbidden selector so a reader can
   * tell at a glance which side of the waypoint contract drifted.
   */
  fun describeMismatch(result: Result.NotMatched): String = buildString {
    append("Waypoint '").append(result.definitionId).append("' not matched after ")
    append(result.timeoutMs).append("ms.")
    // Skip cases carry an empty missing/forbidden diff (the matcher short-circuits before
    // evaluating entries), so the missing/forbidden joiners below would produce nothing.
    // Surface the structural reason instead — otherwise an operator hitting
    // `UNRESOLVED_TARGET_TEMPLATE` would see a bare "not matched after Yms" line with no
    // hint that it's a missing-target-context issue rather than a content miss.
    when (result.lastResult.skipped) {
      WaypointMatchResult.SkipReason.UNRESOLVED_TARGET_TEMPLATE -> {
        append(
          " Skipped (UNRESOLVED_TARGET_TEMPLATE): the waypoint's selectors contain " +
            "`{{target.appId}}` but no target context was supplied to the matcher. " +
            "Run the assertion on a path that resolves the session's target (`appId` + " +
            "declared `appIds`).",
        )
        return@buildString
      }
      WaypointMatchResult.SkipReason.NO_NODE_TREE_IN_SCREEN_STATE -> {
        append(
          " Skipped (NO_NODE_TREE_IN_SCREEN_STATE): the driver did not produce a " +
            "TrailblazeNode tree for this screen. This typically means the screen state " +
            "is empty or the driver path doesn't populate a node tree.",
        )
        return@buildString
      }
      WaypointMatchResult.SkipReason.NO_CLASSIFIER_BLOCK -> {
        append(
          " Skipped (NO_CLASSIFIER_BLOCK): the waypoint declares no block for this device's " +
            "classifier, so it doesn't describe a screen on this device. Add a block for this " +
            "platform/form-factor to the waypoint, or assert a waypoint that targets this device.",
        )
        return@buildString
      }
      null -> {} // fall through to the missing/forbidden diff below
    }
    val missing = result.lastResult.missingRequired
    if (missing.isNotEmpty()) {
      append(" Missing required: ")
      missing.joinTo(this, separator = "; ") { it.entry.description ?: it.entry.selector.toString() }
      append('.')
    }
    val forbidden = result.lastResult.presentForbidden
    if (forbidden.isNotEmpty()) {
      append(" Present forbidden: ")
      forbidden.joinTo(this, separator = "; ") { it.entry.description ?: it.entry.selector.toString() }
      append('.')
    }
  }
}
