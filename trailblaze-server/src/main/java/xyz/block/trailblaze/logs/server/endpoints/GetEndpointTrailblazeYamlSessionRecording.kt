package xyz.block.trailblaze.logs.server.endpoints

import io.ktor.server.freemarker.FreeMarkerContent
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.report.utils.TrailblazeYamlSessionRecording.generateRecordedYaml

/**
 * Endpoint to get the YAML representation of a Trailblaze session recording.
 * This endpoint is used to generate a YAML file that represents the Trailblaze session.
 */
object GetEndpointTrailblazeYamlSessionRecording {

  fun register(routing: Routing, logsDirUtil: LogsRepo) = with(routing) {
    get("/recording/trailblaze/{session}") {
      println("Recording YAML")
      // Only save the llm request logs for now
      val sessionId = this.call.parameters["session"]
      val logs = logsDirUtil.getLogsForSession(sessionId)
      val yaml: String = logs.generateRecordedYaml()

      println("--- YAML ---\n$yaml\n---")

      call.respond(
        FreeMarkerContent(
          "recording_yaml.ftl",
          mapOf(
            "session" to sessionId,
            "yaml" to yaml,
            "recordingType" to "Trailblaze Recording",
          ),
        ),
        null,
      )
    }
  }
}
