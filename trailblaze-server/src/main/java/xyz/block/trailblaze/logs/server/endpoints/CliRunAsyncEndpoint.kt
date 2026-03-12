package xyz.block.trailblaze.logs.server.endpoints

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable

/** Response from the async run submission endpoint. */
@Serializable
data class CliRunAsyncResponse(
  val runId: String,
  val error: String? = null,
)

/** Status of an async run, returned by the polling endpoint. */
@Serializable
data class CliRunStatusResponse(
  val runId: String,
  val state: RunState,
  val sessionId: String? = null,
  val progressMessage: String? = null,
  val result: CliRunResponse? = null,
)

/** Lifecycle state of an async trail run. */
@Serializable
enum class RunState {
  PENDING,
  RUNNING,
  COMPLETED,
  FAILED,
  CANCELLED,
}

/** Response from the cancel endpoint. */
@Serializable
data class CliRunCancelResponse(
  val success: Boolean,
  val message: String? = null,
)

/**
 * Async trail run endpoints.
 *
 * - `POST /cli/run-async` — submit a run, returns immediately with a [runId].
 * - `GET /cli/run-status?runId=...` — poll for progress / completion.
 * - `POST /cli/run-cancel` — cancel an in-flight run.
 */
object CliRunAsyncEndpoint {

  fun register(
    routing: Routing,
    runManager: CliRunManager,
  ) = with(routing) {

    post(CliEndpoints.RUN_ASYNC) {
      try {
        val request = call.receive<CliRunRequest>()
        request.validate()
        val runId = runManager.submitRun(request)
        call.respond(HttpStatusCode.Accepted, CliRunAsyncResponse(runId))
      } catch (e: IllegalArgumentException) {
        call.respond(
          HttpStatusCode.BadRequest,
          CliRunAsyncResponse(runId = "", error = e.message),
        )
      } catch (e: Exception) {
        call.respond(
          HttpStatusCode.InternalServerError,
          CliRunAsyncResponse(runId = "", error = e.message ?: "Internal server error"),
        )
      }
    }

    get(CliEndpoints.RUN_STATUS) {
      val runId = call.request.queryParameters["runId"]
      if (runId.isNullOrBlank()) {
        call.respond(HttpStatusCode.BadRequest, "Missing runId parameter")
        return@get
      }
      val status = runManager.getStatus(runId)
      if (status == null) {
        call.respond(HttpStatusCode.NotFound, "Unknown runId: $runId")
      } else {
        call.respond(HttpStatusCode.OK, status)
      }
    }

    post(CliEndpoints.RUN_CANCEL) {
      @Serializable data class CancelRequest(val runId: String)
      try {
        val req = call.receive<CancelRequest>()
        val cancelled = runManager.cancelRun(req.runId)
        if (cancelled) {
          call.respond(HttpStatusCode.OK, CliRunCancelResponse(success = true))
        } else {
          call.respond(
            HttpStatusCode.NotFound,
            CliRunCancelResponse(success = false, message = "Unknown or already finished runId"),
          )
        }
      } catch (e: Exception) {
        call.respond(
          HttpStatusCode.InternalServerError,
          CliRunCancelResponse(success = false, message = e.message),
        )
      }
    }
  }
}
