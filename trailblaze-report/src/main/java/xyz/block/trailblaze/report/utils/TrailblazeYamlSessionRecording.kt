package xyz.block.trailblaze.report.utils

import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.yaml.TrailYamlItem
import xyz.block.trailblaze.yaml.createTrailblazeYaml
import xyz.block.trailblaze.yaml.generateRecordedTrailItems as generateRecordedTrailItemsCommon
import xyz.block.trailblaze.yaml.generateUnifiedRecordedYaml as generateUnifiedRecordedYamlCommon

/**
 * JVM entry point for generating the YAML representation of a Trailblaze session recording.
 *
 * Delegates to the KMP-compatible [generateUnifiedRecordedYamlCommon] implementation in commonMain,
 * adding JVM-specific support for custom tool classes via reflection.
 */
object TrailblazeYamlSessionRecording {

  /**
   * Renders the recording as a unified `trail.yaml` document (`config:`/`trailhead:`/`trail:` with
   * per-classifier `recordings:`) — the same format the save path writes to disk. Blank when the
   * session has no resolvable device classifier to key the recording slot on.
   */
  fun List<TrailblazeLog>.generateUnifiedRecordedYaml(
    sessionTrailConfig: xyz.block.trailblaze.yaml.TrailConfig? = null,
    customToolClasses: Set<kotlin.reflect.KClass<out TrailblazeTool>> = emptySet(),
    classifierOverride: String? = null,
  ): String {
    val trailblazeYaml = createTrailblazeYaml(
      customTrailblazeToolClasses = customToolClasses,
    )
    return generateUnifiedRecordedYamlCommon(
      trailblazeYaml = trailblazeYaml,
      sessionTrailConfig = sessionTrailConfig,
      classifierOverride = classifierOverride,
    )
  }

  /**
   * The lowered [TrailYamlItem] runtime spine for this session's logs, with JVM custom-tool-class
   * support. Save-back callers holding the logs in-process feed these straight into the unified
   * merge, skipping the YAML encode/decode round-trip that couples save-back to the v1 parser.
   */
  fun List<TrailblazeLog>.generateRecordedTrailItems(
    sessionTrailConfig: xyz.block.trailblaze.yaml.TrailConfig? = null,
    customToolClasses: Set<kotlin.reflect.KClass<out TrailblazeTool>> = emptySet(),
  ): List<TrailYamlItem> {
    val trailblazeYaml = createTrailblazeYaml(
      customTrailblazeToolClasses = customToolClasses,
    )
    return generateRecordedTrailItemsCommon(
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
