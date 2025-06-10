package xyz.block.trailblaze.logs.server.endpoints

import io.ktor.server.freemarker.FreeMarkerContent
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import maestro.orchestra.MaestroCommand
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.client.TrailblazeLog.TrailblazeLlmRequestLog
import xyz.block.trailblaze.maestro.MaestroCommandToYamlSerializer
import xyz.block.trailblaze.report.utils.LogsRepo

/**
 * Registers an endpoint to display LLM conversation as an html chat view.
 */
object GetEndpointYamlSessionRecording {

  fun register(routing: Routing, logsDirUtil: LogsRepo) = with(routing) {
    get("/recording/yaml/{session}") {
      println("Recording YAML")
      // Only save the llm request logs for now
      val sessionId = this.call.parameters["session"]

      val logs = logsDirUtil.getLogsForSession(sessionId)
      val basicCommands: List<MaestroCommand> = logs
        .filterIsInstance<TrailblazeLog.MaestroCommandLog>()
        .filter { it.successful }
        .flatMap { toolCommandLog: TrailblazeLog.MaestroCommandLog ->
          listOf(toolCommandLog.maestroCommand)
        }

      val yamlString = MaestroCommandToYamlSerializer.toYaml(
        commands = basicCommands.mapNotNull { it.asCommand() },
        prompt = logs.filterIsInstance<TrailblazeLlmRequestLog>().firstOrNull()?.instructions,
      )
      call.respond(
        FreeMarkerContent(
          "recording_yaml.ftl",
          mapOf(
            "session" to sessionId,
            "yaml" to yamlString,
          ),
        ),
        null,
      )
    }
  }
}
