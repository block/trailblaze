package xyz.block.trailblaze.waypoint

import kotlinx.coroutines.delay
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.TargetTemplateContext
import xyz.block.trailblaze.api.waypoint.WaypointDefinition
import xyz.block.trailblaze.api.waypoint.WaypointMatchResult
import xyz.block.trailblaze.yaml.StepPostcondition

/**
 * Polls a [StepPostcondition]'s expected waypoint against the live screen state.
 *
 * Holds no state — callers wire it in from the trail runner with a [screenStateProvider]
 * for the device and a [waypointResolver] for the loaded waypoint registry. The asserter
 * loops every [StepPostcondition.pollIntervalMs] until either the waypoint matches or the
 * configured timeout expires, then returns a structured [Result] for the runner to act on
 * (hard-fail in deterministic mode, soft-log in LLM-self-heal modes).
 *
 * Re-evaluating in a loop is deliberate: a step's recorded tools usually finish before
 * the UI has settled (animations, async server commits). A single post-tools snapshot
 * would race with that settling — the poll lets the screen catch up to its intended state
 * without forcing every step to bake in a hardcoded sleep.
 */
object StepPostconditionAsserter {

  /**
   * Runs the postcondition poll. Always exits within `postcondition.timeoutMs + one final
   * evaluation`. The returned result type discriminates the four observable outcomes so the
   * trail runner can render distinct failure messages instead of folding everything into a
   * generic "step failed" string.
   *
   * @param postcondition the assertion declared on the trail step's YAML
   * @param screenStateProvider returns the current device screen state; may return `null`
   *   when the driver hasn't produced one yet (e.g. mid-launch). The asserter tolerates
   *   transient `null` returns within the wait window — only a persistent `null` at the
   *   timeout boundary surfaces as [Result.NoScreenState].
   * @param waypointResolver looks up a [WaypointDefinition] by id from the loaded registry.
   *   Resolution failures surface as [Result.WaypointNotFound] without polling, since they
   *   indicate a misconfigured trail rather than a runtime mismatch.
   * @param now timestamp source in epoch-ms. Defaults to [System.currentTimeMillis]; tests
   *   inject a deterministic clock to verify timeout boundaries without sleeping.
   * @param target optional [TargetTemplateContext] forwarded to the matcher so waypoint
   *   selectors carrying `{{target.appId}}` placeholders expand against the current
   *   session's resolved app id (or declared candidates). Null when the runner has no
   *   target context — the matcher then short-circuits any templated waypoint to a
   *   `UNRESOLVED_TARGET_TEMPLATE` skip (fail-closed; a `forbidden`-only placeholder
   *   would otherwise silently let the waypoint pass).
   */
  // [@JvmOverloads] generates the pre-`target` bytecode signatures so binary-linked
  // consumers (and Java callers, who don't see Kotlin default-arg sugar) keep working
  // unchanged. trailblaze-common is a published artifact (vanniktech.maven.publish), and
  // adding the `target` param without overloads would NoSuchMethodError pre-compiled
  // JARs that call the old 3- or 4-arg `assert`. Same trap PR #3036 hit at the resolver.
  @JvmOverloads
  suspend fun assert(
    postcondition: StepPostcondition,
    screenStateProvider: suspend () -> ScreenState?,
    waypointResolver: (String) -> WaypointDefinition?,
    now: () -> Long = { System.currentTimeMillis() },
    target: TargetTemplateContext? = null,
  ): Result {
    val definition = waypointResolver(postcondition.waypoint)
      ?: return Result.WaypointNotFound(postcondition.waypoint)

    val deadline = now() + postcondition.timeoutMs
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
      delay(minOf(postcondition.pollIntervalMs, remaining))
    }

    if (lastScreenWasNull) {
      return Result.NoScreenState(definition.id, postcondition.timeoutMs)
    }
    return Result.NotMatched(
      definitionId = definition.id,
      lastResult = lastResult!!,
      timeoutMs = postcondition.timeoutMs,
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
   * Human-readable summary of a [Result.NotMatched], formatted for runtime error messages
   * and report templates. Lists every missing-required and every present-forbidden selector
   * so a reader can tell at a glance which side of the waypoint contract drifted.
   */
  fun describeMismatch(result: Result.NotMatched): String = buildString {
    append("Postcondition '").append(result.definitionId).append("' not matched after ")
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
            "Wire the session's resolved target (`appId` + declared `appIds`) into the " +
            "TrailblazeRunnerUtil / matcher caller chain.",
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
