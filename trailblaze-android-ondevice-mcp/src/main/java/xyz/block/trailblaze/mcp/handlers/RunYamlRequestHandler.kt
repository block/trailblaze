package xyz.block.trailblaze.mcp.handlers

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import xyz.block.trailblaze.llm.RunYamlRequest
import xyz.block.trailblaze.llm.RunYamlResponse
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.mcp.RpcHandler
import xyz.block.trailblaze.mcp.android.ondevice.rpc.RpcResult
import xyz.block.trailblaze.util.toSnakeCaseIdentifier
import xyz.block.trailblaze.util.toSnakeCaseWithId
import xyz.block.trailblaze.yaml.TrailblazeYaml

/**
 * Handler for YAML test execution requests.
 * Manages session lifecycle and executes tests in the background.
 */
class RunYamlRequestHandler(
  private val trailblazeLogger: TrailblazeLogger,
  private val backgroundScope: CoroutineScope,
  private val getCurrentJob: () -> Job?,
  private val setCurrentJob: (Job?) -> Unit,
  private val runTrailblazeYaml: suspend (RunYamlRequest) -> Unit
) : RpcHandler<RunYamlRequest, RunYamlResponse> {

  override suspend fun handle(request: RunYamlRequest): RpcResult<RunYamlResponse> {
    return try {
      // Cancel any currently running job before starting a new session
      getCurrentJob()?.let { job ->
        if (job.isActive) {
          // Launch cancellation in background to avoid blocking the response
          backgroundScope.launch {
            job.cancelAndJoin()
          }
          // Send end log for the interrupted session with cancellation status
          trailblazeLogger.sendEndLog(
            SessionStatus.Ended.Cancelled(
              durationMs = 0L,
              cancellationMessage = "Session cancelled after the user started a new session.",
            ),
          )
        }
      }

      // Extract config values for session naming
      val trailConfig = try {
        TrailblazeYaml().extractTrailConfig(request.yaml)
      } catch (e: Exception) {
        null
      }

      val configTitle = trailConfig?.title
      val configId = trailConfig?.id

      val methodName = if (configTitle != null && configId != null) {
        toSnakeCaseWithId(configTitle, configId)
      } else {
        toSnakeCaseIdentifier(request.testName)
      }

      // Start session with method name for consistency, we must call this for lifecycle/resetting
      trailblazeLogger.startSession(methodName)

      // Override the session id if requested
      val overrideSessionId = request.config.overrideSessionId
      if (overrideSessionId != null) {
        trailblazeLogger.overrideSessionId(overrideSessionId)
      }

      val startedSessionId = trailblazeLogger.getCurrentSessionId()

      // Launch the job in the background scope so it doesn't block the response
      val job = backgroundScope.launch {
        try {
          runTrailblazeYaml(request)
          if (request.config.sendSessionEndLog) {
            trailblazeLogger.sendSessionEndLog(isSuccess = true)
          } else {
            // Keep the session open
          }
        } catch (e: Exception) {
          e.printStackTrace()
          trailblazeLogger.sendSessionEndLog(isSuccess = false, exception = e)
        }
      }

      setCurrentJob(job)

      RpcResult.Success(
        RunYamlResponse(
          sessionId = startedSessionId,
        )
      )
    } catch (e: Exception) {
      trailblazeLogger.sendEndLog(false, e)
      RpcResult.Failure(
        errorType = RpcResult.ErrorType.UNKNOWN_ERROR,
        message = "Failed to start YAML execution",
        details = e.stackTraceToString()
      )
    }
  }
}
