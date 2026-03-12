package xyz.block.trailblaze.report.utils

import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.yaml.createTrailblazeYaml
import xyz.block.trailblaze.yaml.generateRecordedYaml as generateRecordedYamlCommon

/**
 * JVM entry point for generating the YAML representation of a Trailblaze session recording.
 *
 * Delegates to the KMP-compatible [generateRecordedYamlCommon] implementation in commonMain,
 * adding JVM-specific support for custom tool classes via reflection.
 */
object TrailblazeYamlSessionRecording {

  fun List<TrailblazeLog>.generateRecordedYaml(
    sessionTrailConfig: xyz.block.trailblaze.yaml.TrailConfig? = null,
    customToolClasses: Set<kotlin.reflect.KClass<out TrailblazeTool>> = emptySet(),
  ): String {
    val trailblazeYaml = createTrailblazeYaml(
      customTrailblazeToolClasses = customToolClasses,
    )
    return generateRecordedYamlCommon(
      trailblazeYaml = trailblazeYaml,
      sessionTrailConfig = sessionTrailConfig,
    )
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
