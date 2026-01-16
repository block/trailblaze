package xyz.block.trailblaze.mcp.handlers

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import xyz.block.trailblaze.llm.RunYamlRequest
import xyz.block.trailblaze.llm.RunYamlResponse
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.client.TrailblazeSessionManager
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.mcp.RpcHandler
import xyz.block.trailblaze.mcp.android.ondevice.rpc.RpcResult
import xyz.block.trailblaze.util.toSnakeCaseIdentifier
import xyz.block.trailblaze.yaml.TrailblazeYaml

/**
 * Handler for YAML test execution requests.
 * Manages session lifecycle and executes tests in the background.
 *
 * Uses explicit session management with TrailblazeSessionManager.
 */
class RunYamlRequestHandler(
  private val backgroundScope: CoroutineScope,
  private val getCurrentJob: () -> Job?,
  private val setCurrentJob: (Job?) -> Unit,
  private val sessionManager: TrailblazeSessionManager,
  private val runTrailblazeYaml: suspend (RunYamlRequest, TrailblazeSession) -> TrailblazeSession
) : RpcHandler<RunYamlRequest, RunYamlResponse> {

  override suspend fun handle(request: RunYamlRequest): RpcResult<RunYamlResponse> {
    // Extract config values for session naming
    val trailConfig = try {
      TrailblazeYaml.Default.extractTrailConfig(request.yaml)
    } catch (e: Exception) {
      null
    }

    // Start session for this test execution
    val overrideId = request.config.overrideSessionId
    val session: TrailblazeSession = if (overrideId != null) {
      sessionManager.createSessionWithId(overrideId)
    } else {
      val configTitle = trailConfig?.title
      val configId = trailConfig?.id
      val newSessionId = run {
        configTitle ?: configId ?: request.testName
      }.let {
        // Ensure sessionId is a valid snake case identifier without special characters for a filename
        toSnakeCaseIdentifier(it)
      }
      sessionManager.startSession(newSessionId)
    }

    return try {
      // Cancel any currently running job before starting a new session
      getCurrentJob()?.let { job ->
        if (job.isActive) {
          // Launch cancellation in background to avoid blocking the response
          backgroundScope.launch {
            job.cancelAndJoin()
          }
          // Send end log for the interrupted session with cancellation status
          sessionManager.endSession(
            session = session,
            endedStatus = SessionStatus.Ended.Cancelled(
              durationMs = 0L,
              cancellationMessage = "Session cancelled after the user started a new session.",
            ),
          )
        }
      }
      // Launch the job in the background scope so it doesn't block the response
      val job = backgroundScope.launch {
        try {
          // Pass session to runner and get updated session back
          val finalSession = runTrailblazeYaml(request, session)
          if (request.config.sendSessionEndLog) {
            sessionManager.endSession(
              session = finalSession,
              isSuccess = true,
            )
          } else {
            // Keep the session open
          }
        } catch (e: Exception) {
          e.printStackTrace()
          sessionManager.endSession(
            session = session,
            isSuccess = false,
            exception = e,
          )
        }
      }

      setCurrentJob(job)

      RpcResult.Success(
        RunYamlResponse(
          sessionId = session.sessionId,
        )
      )
    } catch (e: Exception) {
      sessionManager.endSession(
        session = session,
        isSuccess = false,
        exception = e,
      )
      RpcResult.Failure(
        errorType = RpcResult.ErrorType.UNKNOWN_ERROR,
        message = "Failed to start YAML execution",
        details = e.stackTraceToString()
      )
    }
  }
}
