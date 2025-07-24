package xyz.block.trailblaze.logs.server.endpoints

import io.ktor.server.freemarker.FreeMarkerContent
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import xyz.block.trailblaze.exception.TrailblazeException
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.client.TrailblazeLog.ObjectiveCompleteLog
import xyz.block.trailblaze.logs.client.temp.OtherTrailblazeTool
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolSet
import xyz.block.trailblaze.utils.Ext.asMaestroCommand
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

/*
* This function will take the list oof trailblaze logs and generate the recorded yaml output
 */
private fun List<TrailblazeLog>.generateRecordedYaml(): String {
  val trailblazeYaml = TrailblazeYaml(
    TrailblazeToolSet.AllBuiltInTrailblazeTools,
  )
  val trailblazeYamlBuilder = TrailblazeYamlBuilder()
  var currentLogIndex = 0

  with(trailblazeYamlBuilder) {
    while (currentLogIndex < size) {
      val currentLog = get(currentLogIndex)
      println("### have current log ${currentLog::class}")
      when (currentLog) {
        is TrailblazeLog.DelegatingTrailblazeToolLog -> {
          if (currentLog.command::class != OtherTrailblazeTool::class) {
            tools(listOf(currentLog.command))
          }
        }
        is TrailblazeLog.ObjectiveStartLog -> {
          // Grab the initial prompt step
          val promptStep = currentLog.promptStep
          println("### handle prompt step $promptStep")
          // Find the associated objective complete log for the current prompt step
          var completeIndex = currentLogIndex + 1
          var foundCompleteLog = false
          while (completeIndex < size && !foundCompleteLog) {
            when (val nextLog = get(completeIndex)) {
              is ObjectiveCompleteLog -> {
                if (nextLog.promptStep != promptStep) {
                  throw TrailblazeException("Found invalid objective complete log for prompt step $promptStep, ${nextLog.promptStep}")
                }
                foundCompleteLog = true
              }
              else -> completeIndex++
            }
          }
          if (!foundCompleteLog) {
            throw TrailblazeException("Failed to find the objective complete log for prompt step $promptStep")
          }
          val tools: List<TrailblazeTool> = subList(currentLogIndex, completeIndex)
            .dropLast(1) // remove objective complete log
            .filterIsInstance<TrailblazeLog.TrailblazeToolLog>()
            .map { log ->
              log.command
            }

          prompt(
            text = promptStep.step,
            recordable = promptStep.recordable,
            recording = tools,
          )
          // Set current index to the completed index to skip over the processed tools
          currentLogIndex = completeIndex
        }
        is TrailblazeLog.TrailblazeToolLog -> {
          if (currentLog.command::class != OtherTrailblazeTool::class) {
            tools(listOf(currentLog.command))
          }
        }
        is TrailblazeLog.MaestroCommandLog -> {
          maestro(listOf(currentLog.maestroCommandJsonObj.asMaestroCommand()!!))
        }

        else -> {
          // We should add support for more types.  This is a super basic, non-complete implementation.
        }
      }

      currentLogIndex++
    }
  }

  return trailblazeYaml.encodeToString(trailblazeYamlBuilder.build())
}
