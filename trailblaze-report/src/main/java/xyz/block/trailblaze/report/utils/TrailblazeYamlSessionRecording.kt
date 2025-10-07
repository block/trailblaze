package xyz.block.trailblaze.report.utils

import xyz.block.trailblaze.exception.TrailblazeException
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.client.TrailblazeLog.ObjectiveCompleteLog
import xyz.block.trailblaze.logs.client.temp.OtherTrailblazeTool
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolSet
import xyz.block.trailblaze.utils.Ext.asMaestroCommand
import xyz.block.trailblaze.yaml.DirectionStep
import xyz.block.trailblaze.yaml.TrailblazeYaml
import xyz.block.trailblaze.yaml.VerificationStep
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
    return try {
      val logs = this
      val trailblazeYaml = TrailblazeYaml(
        TrailblazeToolSet.AllBuiltInTrailblazeTools,
      )
      val trailblazeYamlBuilder = TrailblazeYamlBuilder()
      var currentLogIndex = 0

      // If the session is not done then do not try to parse a recording
      if (!logs.isSessionEnded()) {
        return "Session still in progress, cannot generate a recording until the test is complete"
      }

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
                    if (nextLog.promptStep.prompt != promptStep.prompt) {
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

              when (promptStep) {
                is DirectionStep -> {
                  prompt(
                    text = promptStep.prompt,
                    recordable = promptStep.recordable,
                    recording = tools.ifEmpty { null },
                  )
                }

                is VerificationStep -> {
                  verify(
                    text = promptStep.prompt,
                    recordable = promptStep.recordable,
                    recording = tools.ifEmpty { null },
                  )
                }
              }

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

      trailblazeYaml.encodeToString(trailblazeYamlBuilder.build())
    } catch (e: Exception) {
      "Failed to generate recording: ${e.stackTraceToString()}"
    }
  }

  // Function that looks for the final status change log that has an Ended status
  // This indicates that we should be able to generate the recording
  private fun List<TrailblazeLog>.isSessionEnded(): Boolean {
    val endedLog = lastOrNull { log ->
      log is TrailblazeLog.TrailblazeSessionStatusChangeLog &&
        log.sessionStatus is SessionStatus.Ended
    }
    return endedLog != null
  }
}
