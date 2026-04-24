package xyz.block.trailblaze.host.axe

import kotlinx.datetime.Clock
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.util.Console

/**
 * Executes [AxeAction]s sequentially through [AxeDeviceManager], parallel to
 * [xyz.block.trailblaze.android.accessibility.AccessibilityTrailRunner].
 *
 * For now we skip the async-logging pipeline the Android runner uses and just run actions
 * inline — can add screenshot/log-on-settle later. Errors short-circuit the run.
 */
object AxeTrailRunner {

  fun runActions(
    actions: List<AxeAction>,
    traceId: TraceId?,
    deviceManager: AxeDeviceManager,
    trailblazeLogger: TrailblazeLogger? = null,
    sessionProvider: TrailblazeSessionProvider? = null,
  ): TrailblazeToolResult {
    for (action in actions) {
      val startedAt = Clock.System.now().toEpochMilliseconds()
      try {
        deviceManager.execute(action)
        val elapsed = Clock.System.now().toEpochMilliseconds() - startedAt
        Console.log("[AxeTrailRunner] ${action.description} — ${elapsed}ms (trace=$traceId)")
      } catch (e: Exception) {
        Console.log("[AxeTrailRunner] ${action.description} FAILED: ${e.message}")
        return TrailblazeToolResult.Error.ExceptionThrown(
          errorMessage = "Failed action: ${action.description}. Error: ${e.message}",
          stackTrace = e.stackTraceToString(),
        )
      }
    }
    return TrailblazeToolResult.Success()
  }
}
