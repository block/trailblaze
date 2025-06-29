package xyz.block.trailblaze.logs.server.endpoints

import io.ktor.server.freemarker.FreeMarkerContent
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import xyz.block.trailblaze.report.utils.LogsRepo
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Endpoint to serve the home page of the Trailblaze logs server.
 * This endpoint displays a list of session IDs and a sample Goose recipe.
 */
object HomeEndpoint {

  fun register(
    routing: Routing,
    logsRepo: LogsRepo,
  ) = with(routing) {
    get("/") {
      val sessionIds = logsRepo.getSessionIds()

      val gooseRecipeJson = this::class.java.classLoader.getResource("trailblaze_goose_recipe.json").readText()
      call.respond(
        FreeMarkerContent(
          template = "home.ftl",
          model = mutableMapOf<String, Any?>().apply {
            put("sessions", sessionIds)
            @OptIn(ExperimentalEncodingApi::class)
            put("gooseRecipe", Base64.encode(gooseRecipeJson.toByteArray()))
          },
        ),
        null,
      )
    }
  }
}
