package xyz.block.trailblaze.trailrunner

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

internal fun Route.staticRoutes() {
  get(PATH_BASE) {
    call.respondRedirect("$PATH_BASE/", permanent = true)
  }

  get("$PATH_BASE/") {
    respondIndexHtml() ?: call.respond(HttpStatusCode.NotFound)
  }

  get("$PATH_BASE/{path...}") {
    val segments = call.parameters.getAll("path").orEmpty()
    val joined = segments.joinToString("/")
    if (joined.isEmpty() || joined.contains("..")) {
      call.respond(HttpStatusCode.NotFound)
      return@get
    }
    if (respondResource(joined) == null) {
      call.respond(HttpStatusCode.NotFound)
    }
  }
}
