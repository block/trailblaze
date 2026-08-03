package xyz.block.trailblaze.host.ios

import kotlinx.datetime.Clock
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.util.Console

/**
 * Executes [IosDriverAction]s sequentially through an [IosDeviceManager], parallel to
 * [xyz.block.trailblaze.android.accessibility.AccessibilityTrailRunner].
 *
 * For now we skip the async-logging pipeline the Android runner uses and just run actions
 * inline — can add screenshot/log-on-settle later. Errors short-circuit the run.
 */
object IosDriverTrailRunner {

  fun runActions(
    actions: List<IosDriverAction>,
    traceId: TraceId?,
    deviceManager: IosDeviceManager,
    trailblazeLogger: TrailblazeLogger? = null,
    sessionProvider: TrailblazeSessionProvider? = null,
  ): TrailblazeToolResult {
    for (action in actions) {
      val startedAt = Clock.System.now().toEpochMilliseconds()
      try {
        deviceManager.execute(action)
        val elapsed = Clock.System.now().toEpochMilliseconds() - startedAt
        Console.log("[IosDriverTrailRunner] ${action.description} — ${elapsed}ms (trace=$traceId)")
      } catch (e: Exception) {
        Console.log("[IosDriverTrailRunner] ${action.description} FAILED: ${e.message}")
        return TrailblazeToolResult.Error.ExceptionThrown(
          errorMessage = "Failed action: ${action.description}. Error: ${e.message}",
          stackTrace = e.stackTraceToString(),
        )
      }
    }
    return TrailblazeToolResult.Success()
  }
}
