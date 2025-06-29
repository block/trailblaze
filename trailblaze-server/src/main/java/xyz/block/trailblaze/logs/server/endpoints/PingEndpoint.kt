package xyz.block.trailblaze.logs.server.endpoints

import io.ktor.http.ContentType
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get

/**
 * Endpoint to check if the server is running.
 * This is used to make sure the server is available.
 */
object PingEndpoint {

  fun register(
    routing: Routing,
  ) = with(routing) {
    get("/ping") {
      // Used to make sure the server is available
      call.respondText("""{ "status" : "OK" }""", ContentType.Application.Json)
    }
  }
}
