package xyz.block.trailblaze.trailhead

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import xyz.block.trailblaze.api.TargetTemplateContext
import xyz.block.trailblaze.api.waypoint.WaypointDefinition
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.waypoint.WaypointAssertion
import xyz.block.trailblaze.waypoint.WaypointMatcher

/**
 * Which waypoints the screen matches as a trailhead invocation returns: where its setup left the
 * app. Matching uses each waypoint's own `required` and `forbidden` selectors, so whatever else
 * differs between two screens (a cart total, a clock) does not count. Only the run target's own
 * waypoints are candidates (those of the trailmap that declares it); a run with no target (a
 * browser run) or a target with none is not checked, and nothing is captured for it.
 *
 * Host-only: the waypoint registry loads on the host, so an on-device run leaves [waypoints] unset
 * and every landing is [Landing.NotChecked].
 */
object TrailheadLanding {

  /** A target id's own waypoints. Installed by the host runner; null on device. */
  @Volatile
  var waypoints: ((targetId: String) -> Collection<WaypointDefinition>)? = null

  /** How long a setup's screen gets to settle onto a waypoint before its landing is recorded. Test seam. */
  @Volatile
  internal var settleTimeoutMs: Long = WaypointAssertion.DEFAULT_TIMEOUT_MS

  sealed interface Landing {
    /** [waypoints] the screen matched, sorted; [missingExpected] when they lack the expected one. */
    data class Located(val waypoints: List<String>, val missingExpected: Boolean) : Landing

    /**
     * [onHost] is true when the run has a waypoint registry but checking failed (it would not load,
     * or the screen capture failed), as opposed to an on-device run, where nothing can check.
     */
    data class NotChecked(val reason: String, val onHost: Boolean) : Landing
  }

  /**
   * Match the screen against the target's waypoints, capturing for up to [timeoutMs] as
   * `assertWaypoint` would: until the [expected] waypoint (a reused setup's previous landing)
   * matches, or, with none expected, until exactly one waypoint does. A screen still
   * mid-transition is then not recorded as matching nothing.
   */
  suspend fun locate(
    context: TrailblazeToolExecutionContext,
    expected: String? = null,
    timeoutMs: Long = settleTimeoutMs,
    pollIntervalMs: Long = WaypointAssertion.DEFAULT_POLL_INTERVAL_MS,
  ): Landing {
    val registry = waypoints ?: return Landing.NotChecked("no waypoint registry on this run (on-device)", onHost = false)
    val targetId = context.resolvedTarget?.id
      ?: return Landing.NotChecked("this run selects no app target, so no waypoint set applies", onHost = false)
    val candidates = try {
      registry(targetId)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      return Landing.NotChecked("the waypoint registry failed to load: ${e.message}", onHost = true)
    }
    if (candidates.isEmpty()) return Landing.NotChecked("no waypoint is defined for target $targetId", onHost = false)
    val capture = context.screenStateProvider
      ?: return Landing.NotChecked("this run path supplies no screen capture", onHost = true)
    val target = context.resolvedTarget?.let { TargetTemplateContext(appId = context.appId, appIds = it.appIds) }
    val deadline = System.currentTimeMillis() + timeoutMs
    while (true) {
      // Matching inside the guard too: a screen state can read its tree lazily, so the capture
      // itself may only fail there.
      val matched = try {
        val screen = capture()
        candidates.filter { WaypointMatcher.match(it, screen, target).matched }.map { it.id }.sorted()
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        return Landing.NotChecked("the screen capture failed: ${e.message}", onHost = true)
      }
      val missingExpected = expected != null && expected !in matched
      val settled = if (expected == null) matched.size == 1 else !missingExpected
      if (settled || System.currentTimeMillis() >= deadline) return Landing.Located(matched, missingExpected)
      delay(pollIntervalMs)
    }
  }
}
