package xyz.block.trailblaze.report.utils

import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.yaml.createTrailblazeYaml
import xyz.block.trailblaze.yaml.generateRecordedYaml as generateRecordedYamlCommon
import xyz.block.trailblaze.yaml.generateUnifiedRecordedYaml as generateUnifiedRecordedYamlCommon

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

  /**
   * Like [generateRecordedYaml] but renders the recording in the unified `trail.yaml` shape
   * (`config:`/`trailhead:`/`trail:` with per-classifier `recordings:`) — the format the save path
   * writes to disk. For a session with no resolvable device classifier this falls back to the v1
   * list shape.
   */
  fun List<TrailblazeLog>.generateUnifiedRecordedYaml(
    sessionTrailConfig: xyz.block.trailblaze.yaml.TrailConfig? = null,
    customToolClasses: Set<kotlin.reflect.KClass<out TrailblazeTool>> = emptySet(),
  ): String {
    val trailblazeYaml = createTrailblazeYaml(
      customTrailblazeToolClasses = customToolClasses,
    )
    return generateUnifiedRecordedYamlCommon(
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
