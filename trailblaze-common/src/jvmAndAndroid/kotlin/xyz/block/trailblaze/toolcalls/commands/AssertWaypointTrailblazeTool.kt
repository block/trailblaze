package xyz.block.trailblaze.toolcalls.commands

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.api.TargetTemplateContext
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.waypoint.WaypointAssertion
import xyz.block.trailblaze.waypoint.WaypointRegistryResolver

/**
 * Assert that the screen has reached a named **waypoint** — a known, named place in the app
 * (e.g. `square/ios/more-tab-no-sheet`). Polls the current screen until the waypoint's `required`
 * selectors are all present and no `forbidden` selector is present, or until the timeout elapses.
 *
 * ## Why this is a tool
 *
 * A driver reports a tap/swipe as `successful` when the gesture was dispatched — not when it
 * achieved its intent. `assertWaypoint` pins a step's *intended end-state* to a named place, so a
 * fault (a swipe that didn't dismiss a sheet, a tab that didn't switch) surfaces at the assertion
 * rather than cascading into a confusing failure several steps later. Being a tool — not a bespoke
 * step field — means the agent can *select* it while authoring, a branchy tool can call it to
 * guarantee its own "ends in a known state" contract, and it's authorable by hand in YAML/scripted
 * trails. It is read-only: a successful run is the assertion verdict, never a device mutation.
 *
 * ## Host-backed
 *
 * The waypoint registry and matcher live host-side, so this tool is `requiresHost = true`: on
 * host-orchestrated runs of any driver it executes on the host JVM (where the registry resolves);
 * pure on-device agents, which can't reach the registry, don't advertise it. Screen state and the
 * session's resolved target are read from the [TrailblazeToolExecutionContext].
 */
@Serializable
@TrailblazeToolClass(
  name = "assertWaypoint",
  requiresHost = true,
  isVerification = true,
)
@LLMDescription(
  """
Assert that the current screen has reached a named waypoint (a known place in the app, e.g.
`square/ios/more-tab-no-sheet`). Waits up to `timeoutMs` for the screen to settle into that
waypoint — all of its `required` selectors present and none of its `forbidden` selectors present.
Succeeds when the waypoint matches; fails with the missing-required / present-forbidden diff if it
does not match within the timeout. Reach for this to lock in that a step (or a branchy navigation
tool) landed where it intended, instead of trusting that a tap/swipe "succeeded".
""",
)
data class AssertWaypointTrailblazeTool(
  @LLMDescription("Id of the waypoint to assert, e.g. `square/ios/more-tab-no-sheet`.")
  val waypoint: String,
  @LLMDescription(
    "Total milliseconds to wait for the screen to settle into the waypoint before failing. " +
      "Default 5000.",
  )
  val timeoutMs: Long = WaypointAssertion.DEFAULT_TIMEOUT_MS,
  @LLMDescription(
    "Milliseconds between waypoint re-evaluations while waiting. Default 250.",
  )
  val pollIntervalMs: Long = WaypointAssertion.DEFAULT_POLL_INTERVAL_MS,
) : ExecutableTrailblazeTool {

  init {
    require(waypoint.isNotBlank()) { "assertWaypoint.waypoint must be a non-blank waypoint id" }
    require(timeoutMs > 0) { "assertWaypoint.timeoutMs must be > 0 (got $timeoutMs)" }
    require(pollIntervalMs > 0) { "assertWaypoint.pollIntervalMs must be > 0 (got $pollIntervalMs)" }
  }

  override suspend fun execute(
    toolExecutionContext: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    // A live screen source is required to poll the waypoint. Every host-orchestrated driver path
    // wires one; fail loud rather than silently passing if a run path didn't supply it.
    val screenStateProvider = toolExecutionContext.screenStateProvider
      ?: return TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "assertWaypoint requires a live screen-state provider, which this run " +
          "path did not supply. assertWaypoint runs host-side on host-orchestrated driver runs.",
        command = this,
      )

    // Forward the session's resolved target so waypoint selectors carrying `{{target.appId}}`
    // expand against the device-resolved app id (and declared candidates). Null target context
    // is fine for literal-selector waypoints; the matcher fail-closes templated ones.
    val target: TargetTemplateContext? = toolExecutionContext.resolvedTarget?.let {
      TargetTemplateContext(appId = toolExecutionContext.appId, appIds = it.appIds)
    }

    // Bound the whole poll in a try/catch: a throwing screen capture (e.g. an RPC failure mid-poll)
    // must surface as a tool Error, not escape and crash host-local tool dispatch (which executes
    // `execute()` without its own guard). Cancellation is rethrown so trail timeout / abort still
    // propagates.
    val result = try {
      WaypointAssertion.poll(
        waypointId = waypoint,
        timeoutMs = timeoutMs,
        pollIntervalMs = pollIntervalMs,
        screenStateProvider = { screenStateProvider() },
        waypointResolver = WaypointRegistryResolver.resolver(),
        target = target,
      )
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      return TrailblazeToolResult.Error.ExceptionThrown.fromThrowable(e, this)
    }

    return toToolResult(result)
  }

  /**
   * Maps a [WaypointAssertion.Result] to the tool's observable verdict: a match is the only
   * [TrailblazeToolResult.Success]; every other outcome is an [TrailblazeToolResult.Error] so a
   * failed waypoint assertion fails the step like any other verification tool. Extracted so the
   * verdict polarity is unit-testable without a live device or registry.
   */
  internal fun toToolResult(result: WaypointAssertion.Result): TrailblazeToolResult = when (result) {
    is WaypointAssertion.Result.Matched ->
      TrailblazeToolResult.Success(message = "Waypoint '${result.definitionId}' matched.")

    is WaypointAssertion.Result.NotMatched ->
      TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = WaypointAssertion.describeMismatch(result),
        command = this,
      )

    is WaypointAssertion.Result.WaypointNotFound ->
      TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "assertWaypoint references unknown waypoint '${result.requestedId}'. " +
          "Check the waypoint id against the loaded trailmaps. If every waypoint reports unknown, " +
          "the registry failed to load — see the '[WaypointRegistryResolver]' log line.",
        command = this,
      )

    is WaypointAssertion.Result.NoScreenState ->
      TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "assertWaypoint '${result.definitionId}' could not be evaluated — the " +
          "screen-state provider returned no state within ${result.timeoutMs}ms.",
        command = this,
      )
  }
}
