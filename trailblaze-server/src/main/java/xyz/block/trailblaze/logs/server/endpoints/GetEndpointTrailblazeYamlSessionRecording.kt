package xyz.block.trailblaze.logs.server.endpoints

import io.ktor.server.freemarker.FreeMarkerContent
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.client.temp.OtherTrailblazeTool
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.toolcalls.TrailblazeToolSet
import xyz.block.trailblaze.yaml.TrailblazeYaml
import xyz.block.trailblaze.yaml.models.TrailblazeYamlBuilder

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

      val trailblazeYaml = TrailblazeYaml(
        TrailblazeToolSet.AllBuiltInTrailblazeTools,
      )

      val trailblazeYamlBuilder = TrailblazeYamlBuilder()

      with(trailblazeYamlBuilder) {
        logs.forEach {
          when (it) {
            is TrailblazeLog.DelegatingTrailblazeToolLog -> {
              if (it.command::class != OtherTrailblazeTool::class) {
                tools(listOf(it.command))
              }
            }
            is TrailblazeLog.ObjectiveStartLog -> prompt(it.description)
            is TrailblazeLog.TrailblazeToolLog -> {
              if (it.command::class != OtherTrailblazeTool::class) {
                tools(listOf(it.command))
              }
            }
            else -> {
              // We should add support for more types.  This is a super basic, non-complete implementation.
            }
          }
        }
      }

      val yaml: String = trailblazeYaml.encodeToString(trailblazeYamlBuilder.build())

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
