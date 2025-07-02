package xyz.block.trailblaze.logs.server.endpoints

import io.ktor.server.freemarker.FreeMarkerContent
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import maestro.orchestra.Command
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.client.TrailblazeLog.TrailblazeLlmRequestLog
import xyz.block.trailblaze.maestro.MaestroYamlSerializer
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.utils.Ext.asMaestroCommand

/**
 * Registers an endpoint to display LLM conversation as an html chat view.
 */
object GetEndpointMaestroYamlSessionRecording {

  fun register(routing: Routing, logsDirUtil: LogsRepo) = with(routing) {
    get("/recording/maestro/{session}") {
      println("Recording YAML")
      // Only save the llm request logs for now
      val sessionId = this.call.parameters["session"]

      val logs = logsDirUtil.getLogsForSession(sessionId)
      val basicCommands: List<Command> = logs
        .filterIsInstance<TrailblazeLog.MaestroCommandLog>()
        .filter { it.successful }
        .flatMap { toolCommandLog: TrailblazeLog.MaestroCommandLog ->
          listOf(toolCommandLog.maestroCommandJsonObj.asMaestroCommand()).filterNotNull()
        }

      val yamlString = MaestroYamlSerializer.toYaml(
        commands = basicCommands.mapNotNull { it },
        prompt = logs.filterIsInstance<TrailblazeLlmRequestLog>().firstOrNull()?.instructions,
      )
      call.respond(
        FreeMarkerContent(
          "recording_yaml.ftl",
          mapOf(
            "session" to sessionId,
            "yaml" to yamlString,
            "recordingType" to "Maestro Recording",
          ),
        ),
        null,
      )
    }
  }
}
