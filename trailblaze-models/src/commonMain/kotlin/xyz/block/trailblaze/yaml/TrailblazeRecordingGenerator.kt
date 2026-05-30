package xyz.block.trailblaze.yaml

import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.client.TrailblazeLog.ObjectiveCompleteLog
import xyz.block.trailblaze.logs.model.SessionStatus
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

    // Always stamp `driver:` into the saved recording's config block, even when the source
    // [sessionTrailConfig] doesn't carry one. The recording's selector shape (Maestro `selector:`
    // vs accessibility `nodeSelector:`) is determined by which driver actually ran the session,
    // so the saved YAML must declare that driver to be replayable. We fall back to the runtime
    // driver captured in the [SessionStatus.Started] log when the source config didn't have an
    // explicit marker — this closes the gap where an LLM-driven recording (no source YAML) or a
    // legacy YAML lacking `driver:` would be saved without a driver marker.
    val resolvedDriver = sessionTrailConfig?.driver
      ?: logs
        .filterIsInstance<TrailblazeLog.TrailblazeSessionStatusChangeLog>()
        .map { it.sessionStatus }
        .filterIsInstance<SessionStatus.Started>()
        .firstOrNull()
        ?.trailblazeDeviceInfo?.trailblazeDriverType?.name

    // Add config first if provided OR if we have a runtime driver to stamp (so a logs-only
    // call without source config still emits a config block carrying the driver marker).
    if (sessionTrailConfig != null || resolvedDriver != null) {
      items.add(
        TrailYamlItem.ConfigTrailItem(
          TrailConfig(
            context = sessionTrailConfig?.context,
            id = sessionTrailConfig?.id,
            title = sessionTrailConfig?.title,
            source = sessionTrailConfig?.source,
            description = sessionTrailConfig?.description,
            priority = sessionTrailConfig?.priority,
            metadata = sessionTrailConfig?.metadata,
            target = sessionTrailConfig?.target,
            driver = resolvedDriver,
            platform = sessionTrailConfig?.platform,
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
          val toolLogsInWindow = logsInWindow
            .filterIsInstance<TrailblazeLog.TrailblazeToolLog>()
            .filter { it.isRecordable }
          val selectedToolLogs = toolLogsInWindow
            .filter { it.isTopLevelToolCall }
            .ifEmpty { toolLogsInWindow }
          val rawWrappers: List<TrailblazeToolYamlWrapper> = selectedToolLogs
            .map { log -> wrapTrailblazeTool(log.trailblazeTool, log.toolName) }
          val toolWrappers = dedupeVerificationRepeats(rawWrappers, selectedToolLogs, trailblazeYaml)

          // Zero recordable tools in this objective window → emit no recording at all (null).
          // At replay time the step will fall through to AI rather than ghost-passing. Empty
          // `ToolRecording` instances are now rejected at construction (see ToolRecording).
          val recording = if (toolWrappers.isNotEmpty()) {
            ToolRecording(tools = toolWrappers)
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
          // Orphaned tool logs (outside an ObjectiveStart/Complete window) are
          // attached to the last prompt step's recording. This handles the MCP
          // path where tool logs may be emitted asynchronously and land outside
          // the objective window in the sorted log list.
          if (currentLog.isRecordable) {
            val wrapper = wrapTrailblazeTool(currentLog.trailblazeTool, currentLog.toolName)
            val candidateFingerprint = if (currentLog.isVerification) {
              fingerprintForDedup(wrapper, trailblazeYaml)
            } else {
              null
            }
            val lastItem = items.lastOrNull()
            if (lastItem is TrailYamlItem.PromptsTrailItem && lastItem.promptSteps.isNotEmpty()) {
              val lastStep = lastItem.promptSteps.last()
              val existingTools = lastStep.recording?.tools ?: emptyList()
              val isRepeat = candidateFingerprint != null && existingTools.any { existing ->
                fingerprintForDedup(existing, trailblazeYaml) == candidateFingerprint
              }
              if (!isRepeat) {
                val updatedRecording = ToolRecording(tools = existingTools + wrapper)
                val updatedStep = when (lastStep) {
                  is DirectionStep -> lastStep.copy(recording = updatedRecording)
                  is VerificationStep -> lastStep.copy(recording = updatedRecording)
                }
                val updatedSteps = lastItem.promptSteps.dropLast(1) + updatedStep
                items[items.lastIndex] = lastItem.copy(promptSteps = updatedSteps)
              }
            } else {
              // No preceding prompt step — standalone tool (rare, but preserve it)
              if (lastItem is TrailYamlItem.ToolTrailItem) {
                val isRepeat = candidateFingerprint != null && lastItem.tools.any { existing ->
                  fingerprintForDedup(existing, trailblazeYaml) == candidateFingerprint
                }
                if (!isRepeat) {
                  items[items.lastIndex] = lastItem.copy(tools = lastItem.tools + wrapper)
                }
              } else {
                items.add(TrailYamlItem.ToolTrailItem(listOf(wrapper)))
              }
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

/**
 * Collapses repeated verification calls (e.g. the LLM re-asserting visibility of the same
 * selector 4x with different `reason:` strings) within a single objective window. Only tools
 * whose log carries `isVerification = true` are considered — verifications are read-only and
 * idempotent, so re-running the same one is always redundant. Action tools (taps, swipes,
 * waits, text entry) pass through unchanged: their repeats are usually deliberate (e.g.
 * tapping the same digit key while entering "500" needs both "0" taps).
 */
private fun dedupeVerificationRepeats(
  wrappers: List<TrailblazeToolYamlWrapper>,
  logs: List<TrailblazeLog.TrailblazeToolLog>,
  trailblazeYaml: TrailblazeYaml,
): List<TrailblazeToolYamlWrapper> {
  val seen = mutableSetOf<String>()
  return wrappers.zip(logs).mapNotNull { (wrapper, log) ->
    if (!log.isVerification) {
      wrapper
    } else {
      val fingerprint = fingerprintForDedup(wrapper, trailblazeYaml)
      if (seen.add(fingerprint)) wrapper else null
    }
  }
}

/**
 * Canonical fingerprint for a wrapper's identity-minus-`reason`. The free-form `reason:` LLM
 * annotation has zero replay significance — two calls with identical selectors but different
 * reasons describe the same physical assertion and hash the same here. Falls back to the bare
 * tool name on encode failure so a serializer hiccup never causes a tool to be dropped.
 */
private fun fingerprintForDedup(
  wrapper: TrailblazeToolYamlWrapper,
  trailblazeYaml: TrailblazeYaml,
): String {
  val rawYaml = try {
    trailblazeYaml.encodeToString(listOf(TrailYamlItem.ToolTrailItem(listOf(wrapper))))
  } catch (_: Exception) {
    wrapper.name
  }
  return wrapper.name + "||" +
    rawYaml.lines().filterNot { it.trimStart().startsWith("reason:") }.joinToString("\n")
}
