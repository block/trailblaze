package xyz.block.trailblaze.yaml

import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.client.TrailblazeLog.ObjectiveCompleteLog
import xyz.block.trailblaze.util.Console

/**
 * Generates a recording YAML string from a list of [TrailblazeLog] entries.
 *
 * This is a KMP-compatible implementation that works on all platforms (JVM, Wasm, etc.)
 * because it relies on metadata already present on each log entry (e.g., [TrailblazeLog.TrailblazeToolLog.toolName]
 * and [TrailblazeLog.TrailblazeToolLog.isRecordable]) instead of JVM reflection.
 *
 * @param trailblazeYaml The YAML serializer instance to use for encoding.
 * @param sessionTrailConfig Optional trail config to include at the top of the recording.
 */
fun List<TrailblazeLog>.generateRecordedYaml(
  trailblazeYaml: TrailblazeYaml,
  sessionTrailConfig: TrailConfig? = null,
): String {
  return try {
    val logs = this
    val items = mutableListOf<TrailYamlItem>()

    // Add config first if provided
    if (sessionTrailConfig != null) {
      items.add(
        TrailYamlItem.ConfigTrailItem(
          TrailConfig(
            context = sessionTrailConfig.context,
            id = sessionTrailConfig.id,
            title = sessionTrailConfig.title,
            source = sessionTrailConfig.source,
            description = sessionTrailConfig.description,
            priority = sessionTrailConfig.priority,
            metadata = sessionTrailConfig.metadata,
            app = sessionTrailConfig.app,
            driver = sessionTrailConfig.driver,
            platform = sessionTrailConfig.platform,
          ),
        ),
      )
    }

    var currentLogIndex = 0
    while (currentLogIndex < size) {
      when (val currentLog = logs[currentLogIndex]) {
        is TrailblazeLog.DelegatingTrailblazeToolLog -> {
          // Skip — DelegatingTrailblazeToolLog contains nodeId-based tools that
          // are not replayable. The corresponding selector-based TrailblazeToolLog
          // entries are used instead.
        }

        is TrailblazeLog.ObjectiveStartLog -> {
          val promptStep = currentLog.promptStep
          // Find the associated objective complete log
          var completeIndex = currentLogIndex + 1
          var foundCompleteLog = false
          while (completeIndex < size && !foundCompleteLog) {
            when (val nextLog = logs[completeIndex]) {
              is ObjectiveCompleteLog -> {
                if (nextLog.promptStep.prompt == promptStep.prompt) {
                  foundCompleteLog = true
                } else {
                  completeIndex++
                }
              }

              else -> completeIndex++
            }
          }
          if (!foundCompleteLog) {
            break
          }

          // Collect recordable TrailblazeToolLog entries within the window
          val logsInWindow = subList(currentLogIndex, completeIndex)
          val toolWrappers: List<TrailblazeToolYamlWrapper> = logsInWindow
            .filterIsInstance<TrailblazeLog.TrailblazeToolLog>()
            .filter { it.isRecordable }
            .map { log -> wrapTrailblazeTool(log.trailblazeTool, log.toolName) }

          val recording = if (toolWrappers.isNotEmpty()) {
            ToolRecording(toolWrappers)
          } else {
            null
          }

          val newStep = when (promptStep) {
            is DirectionStep -> DirectionStep(
              step = promptStep.prompt,
              recordable = promptStep.recordable,
              recording = recording,
            )

            is VerificationStep -> VerificationStep(
              verify = promptStep.prompt,
              recordable = promptStep.recordable,
              recording = recording,
            )
          }

          // Merge into existing PromptsTrailItem or create new one
          val lastItem = items.lastOrNull()
          if (lastItem is TrailYamlItem.PromptsTrailItem) {
            items[items.lastIndex] = lastItem.copy(
              promptSteps = lastItem.promptSteps + newStep,
            )
          } else {
            items.add(TrailYamlItem.PromptsTrailItem(listOf(newStep)))
          }

          // Advance past the ObjectiveComplete log
          currentLogIndex = completeIndex
        }

        is TrailblazeLog.TrailblazeToolLog -> {
          if (currentLog.isRecordable) {
            val wrapper = wrapTrailblazeTool(currentLog.trailblazeTool, currentLog.toolName)
            val lastItem = items.lastOrNull()
            if (lastItem is TrailYamlItem.ToolTrailItem) {
              items[items.lastIndex] = lastItem.copy(tools = lastItem.tools + wrapper)
            } else {
              items.add(TrailYamlItem.ToolTrailItem(listOf(wrapper)))
            }
          }
        }

        is TrailblazeLog.MaestroCommandLog -> {
          // Skip — MaestroCommandLog entries are raw Maestro commands logged by
          // OrchestraRunner during tool execution. They are implementation details
          // of higher-level tools (e.g., TapOnByElementSelector) and should not
          // appear in recordings. The proper MaestroTrailblazeTool (which IS
          // recordable) is logged as a TrailblazeToolLog and handled above.
        }

        else -> {
          // Skip unsupported log types
        }
      }

      currentLogIndex++
    }

    trailblazeYaml.encodeToString(items)
  } catch (e: Exception) {
    Console.error("Failed to generate recording: ${e.stackTraceToString()}")
    ""
  }
}
