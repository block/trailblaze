package xyz.block.trailblaze.trailrunner

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import xyz.block.trailblaze.logs.model.SessionId
import java.io.File
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.yaml.createTrailblazeYaml
import xyz.block.trailblaze.yaml.generateRecordedYaml

/**
 * "Review my trail" — the AI authoring-assist pass (Phase 1 of the smarter-trail work).
 *
 * `POST /api/session/{id}/review` reconstructs the recorded trail YAML from a session's logs (the
 * same logs→YAML path the export/bundle-variant flows use) and asks the configured LLM to critique
 * it for two concrete problems: missing on-screen assertions and fragile selectors (coordinate /
 * index based). Returns structured [ReviewSuggestionDto]s the UI shows as accept/reject items.
 *
 * Read-only: it never mutates the trail — applying a suggestion is a separate, explicit edit. The
 * LLM call rides the same `reviewTrailProvider` plumbing the desktop app wires for step proposal.
 */
internal fun Route.reviewRoutes(deps: TrailRunnerDeps) {
  post("$PATH_BASE/api/session/{id}/review") {
    val id = call.parameters["id"]?.trim().orEmpty()
    val sessionDir = resolveSafeSessionDir(deps.logsRepo.logsDir, id)
    if (sessionDir == null) {
      call.respond(HttpStatusCode.NotFound)
      return@post
    }
    // Shared brain: when the human's own agent CLI is attached to this trail's folder, hand the
    // review to it instead of the wired LLM. The dispatch marker names which trail the session
    // ran; sessions without one (raw-YAML replays) have no folder to key on and never defer.
    val trailId = withContext(Dispatchers.IO) {
      runCatching { File(sessionDir, ".trailrunner-trail-id").readText().trim().takeIf { it.isNotEmpty() } }.getOrNull()
    }
    val folderRel = companionRelFor(null, trailId)
    if (folderRel != null) {
      val payload = JsonObject(
        buildMap {
          put("folder", JsonPrimitive(folderRel))
          put("sessionId", JsonPrimitive(id))
          put("trailId", JsonPrimitive(trailId!!))
        },
      )
      when (val deferred = ExternalAgentSupervisor.deferToCompanion("review-trail", folderRel, payload)) {
        is DeferOutcome.Deferred -> {
          call.respondText(
            text = JSON.encodeToString(ReviewTrailResponse.serializer(), ReviewTrailResponse(deferred = true, requestId = deferred.requestId, runId = deferred.runId)),
            contentType = ContentType.Application.Json,
          )
          return@post
        }
        is DeferOutcome.Degraded -> {
          call.respondText(
            text = JSON.encodeToString(ReviewTrailResponse.serializer(), ReviewTrailResponse(degraded = true, runId = deferred.runId, error = COMPANION_AGENT_NOT_LISTENING)),
            contentType = ContentType.Application.Json,
          )
          return@post
        }
        DeferOutcome.None -> {}
      }
    }
    val provider = deps.reviewTrailProvider
    if (provider == null) {
      call.respondText(
        text = JSON.encodeToString(ReviewTrailResponse.serializer(), ReviewTrailResponse(error = "trail review is not available (no LLM provider wired)")),
        contentType = ContentType.Application.Json,
      )
      return@post
    }
    val logs = withContext(Dispatchers.IO) { deps.logsRepo.getLogsForSession(SessionId(id)) }
    if (logs.isEmpty()) {
      call.respondText(
        text = JSON.encodeToString(ReviewTrailResponse.serializer(), ReviewTrailResponse(error = "no logs for session $id")),
        contentType = ContentType.Application.Json,
      )
      return@post
    }
    val info = runCatching { deps.logsRepo.getSessionInfoDirect(SessionId(id)) }.getOrNull()
    val target = info?.trailConfig?.target
    val platform = info?.trailblazeDeviceInfo?.platform?.name?.lowercase()
    val recordedYaml = withContext(Dispatchers.IO) {
      runCatching { logs.generateRecordedYaml(createTrailblazeYaml()) }.getOrNull()
    }
    if (recordedYaml.isNullOrBlank()) {
      call.respondText(
        text = JSON.encodeToString(ReviewTrailResponse.serializer(), ReviewTrailResponse(error = "could not reconstruct a recorded trail from this session")),
        contentType = ContentType.Application.Json,
      )
      return@post
    }
    val response = runCatching { ReviewTrailResponse(suggestions = provider(recordedYaml, target, platform)) }
      .getOrElse { e ->
        Console.log("[TrailRunnerEndpoint] trail review failed for session $id: ${e.message}")
        ReviewTrailResponse(error = e.message ?: "trail review failed")
      }
    call.respondText(
      text = JSON.encodeToString(ReviewTrailResponse.serializer(), response),
      contentType = ContentType.Application.Json,
    )
  }
}
