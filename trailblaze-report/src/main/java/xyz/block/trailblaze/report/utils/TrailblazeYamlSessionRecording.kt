package xyz.block.trailblaze.report.utils

import xyz.block.trailblaze.exception.TrailblazeException
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.client.TrailblazeLog.ObjectiveCompleteLog
import xyz.block.trailblaze.logs.client.temp.OtherTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolSet
import xyz.block.trailblaze.utils.Ext.asMaestroCommand
import xyz.block.trailblaze.yaml.TrailblazeYaml
import xyz.block.trailblaze.yaml.models.TrailblazeYamlBuilder

/**
 * Endpoint to get the YAML representation of a Trailblaze session recording.
 * This endpoint is used to generate a YAML file that represents the Trailblaze session.
 */
object TrailblazeYamlSessionRecording {

  /*
* This function will take the list of trailblaze logs and generate the recorded yaml output
   */
  fun List<TrailblazeLog>.generateRecordedYaml(): String {
    val logs = this
    val trailblazeYaml = TrailblazeYaml(
      TrailblazeToolSet.AllBuiltInTrailblazeTools,
    )
    val trailblazeYamlBuilder = TrailblazeYamlBuilder()
    var currentLogIndex = 0

    with(trailblazeYamlBuilder) {
      while (currentLogIndex < size) {
        val currentLog = logs[currentLogIndex]
        println("### have current log ${currentLog::class}")
        when (currentLog) {
          is TrailblazeLog.DelegatingTrailblazeToolLog -> {
            if (currentLog.trailblazeTool::class != OtherTrailblazeTool::class) {
              tools(listOf(currentLog.trailblazeTool))
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
              when (val nextLog = logs.get(completeIndex)) {
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
              return@with
            }
            val tools: List<TrailblazeTool> = subList(currentLogIndex, completeIndex)
              .dropLast(1) // remove objective complete log
              .filterIsInstance<TrailblazeLog.TrailblazeToolLog>()
              .map { log ->
                log.trailblazeTool
              }

            prompt(
              text = promptStep.step,
              recordable = promptStep.recordable,
              recording = tools.ifEmpty { null },
            )
            // Set current index to the completed index to skip over the processed tools
            currentLogIndex = completeIndex
          }

          is TrailblazeLog.TrailblazeToolLog -> {
            if (currentLog.trailblazeTool::class != OtherTrailblazeTool::class) {
              tools(listOf(currentLog.trailblazeTool))
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
}
