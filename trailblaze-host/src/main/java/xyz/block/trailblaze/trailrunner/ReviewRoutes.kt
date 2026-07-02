package xyz.block.trailblaze.trailrunner

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.yaml.createTrailblazeYaml
import xyz.block.trailblaze.yaml.generateRecordedYaml

/**
 * "Review my trail" — the AI authoring-assist pass (Phase 1 of the smarter-trail work).
 *
 * `POST /api/session/{id}/review` reconstructs the recorded trail YAML from a session's logs (the
 * same logs→YAML path the export/draft-variant flows use) and asks the configured LLM to critique
 * it for two concrete problems: missing on-screen assertions and fragile selectors (coordinate /
 * index based). Returns structured [ReviewSuggestionDto]s the UI shows as accept/reject items.
 *
 * Read-only: it never mutates the trail — applying a suggestion is a separate, explicit edit. The
 * LLM call rides the same `reviewTrailProvider` plumbing the desktop app wires for step proposal.
 */
internal fun Route.reviewRoutes(deps: TrailRunnerDeps) {
  post("$PATH_BASE/api/session/{id}/review") {
    val id = call.parameters["id"]?.trim().orEmpty()
    if (resolveSafeSessionDir(deps.logsRepo.logsDir, id) == null) {
      call.respond(HttpStatusCode.NotFound)
      return@post
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
