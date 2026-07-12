package xyz.block.trailblaze.yaml

import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.client.TrailblazeLog.ObjectiveCompleteLog
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.logs.model.getSessionStartedInfo
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.yaml.unified.TrailDocument
import xyz.block.trailblaze.yaml.unified.UnifiedTrail
import xyz.block.trailblaze.yaml.unified.UnifiedTrailAdapter

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
/**
 * The session's config as a recording save path should persist it: the original config carried
 * wholesale (lossless — a hand-picked field list here silently dropped electron/tags/skip/memory)
 * with only the session's runtime driver/platform overriding, plus an optional [titleOverride]
 * (e.g. an MCP save tool's caller-supplied trail name). Started sites that don't apply
 * `config.memory:` strip it before logging, so trusting the logged config is safe.
 */
fun SessionStatus.Started.toRecordingTrailConfig(titleOverride: String? = null): TrailConfig =
  (trailConfig ?: TrailConfig()).copy(
    title = titleOverride ?: trailConfig?.title,
    driver = trailblazeDeviceInfo.trailblazeDriverType.name,
    platform = trailblazeDeviceInfo.platform.name.lowercase(),
  )

fun List<TrailblazeLog>.generateRecordedYaml(
  trailblazeYaml: TrailblazeYaml,
  sessionTrailConfig: TrailConfig? = null,
): String = try {
  trailblazeYaml.encodeToString(buildRecordedTrailItems(trailblazeYaml, sessionTrailConfig))
} catch (e: Exception) {
  Console.error("Failed to generate recording: ${e.stackTraceToString()}")
  ""
}

/**
 * The classifier slot key a unified recording preview/save uses for this session — the device's
 * classifier segments joined with `-` (e.g. `ios-iphone-sim`), read from the session's
 * [SessionStatus.Started] log, matching how the save path keys the on-disk `recordings:` slot.
 * Blank when the session logged no device classifiers, in which case [generateUnifiedRecordedYaml]
 * falls back to the v1 shape.
 *
 * Blank segments are dropped before joining, mirroring
 * [xyz.block.trailblaze.devices.TrailblazeClassifierLineage.resolutionChain] — otherwise a stray
 * empty segment (`["ios", ""]`) would produce a compound key (`"ios-"`) that resolution never
 * reconstructs, stranding a `recordings:` slot that can't be matched at replay time.
 */
private fun List<TrailblazeLog>.recordingClassifier(): String =
  getSessionStartedInfo()?.trailblazeDeviceInfo?.classifiers.orEmpty()
    .filter { it.classifier.isNotBlank() }
    .joinToString("-") { it.classifier }

/**
 * Unified-format sibling of [generateRecordedYaml]: renders the same recording as the unified
 * `trail.yaml` document (`config:` / `trailhead:` / `trail:`, each step carrying per-classifier
 * `recordings:`) that the save path writes to disk — so the recording PREVIEW shown in reports and
 * the desktop Recording tab matches the unified artifact the save path produces, instead of the
 * legacy v1 list shape. The classifier slot is derived from the session's device classifiers (the
 * same key the save path uses via [UnifiedTrailAdapter.mergeRecordedClassifier]); pass
 * [classifierOverride] only to force a specific slot (tests).
 *
 * **Preserves the other platforms.** A run only re-records the one device it ran on, but the
 * unified `trail.yaml` it ran against can already hold recordings for other classifiers. The merge
 * is seeded with the original run's full document — recovered from the session's
 * [SessionStatus.Started.rawYaml] (the exact YAML submitted to the run) — so those other slots
 * survive into the preview and only this classifier's slot is replaced. When the run started from
 * scratch (no source YAML) or from a legacy v1 trail, there is no existing unified document to seed
 * from and the preview shows this classifier alone — the same as the first write of a brand-new
 * unified trail.
 *
 * **This is a best-effort preview, not a byte-guarantee of the saved file.** Two windows where the
 * rendered doc can differ from what the save path ultimately writes: (1) it seeds from `rawYaml`
 * (the file as of run launch) whereas the save path seeds from the file on disk *at save time*, so
 * a concurrent re-record of another device between launch and save won't show here — commonMain has
 * no filesystem to read the live file; (2) it always renders the unified shape regardless of the
 * transitional save-gate (`TRAILBLAZE_UNIFIED_RECORDINGS` / `unifiedRecordingsEnabled`), so with the
 * gate reverted to legacy the preview still shows the go-forward unified form. Both are intentional.
 *
 * Falls back to the v1 encoding whenever the unified shape can't be produced — the classifier is
 * blank (no slot to key on), the recording lowers to no steps (an empty `trail:` is unparseable), or
 * the unified render itself throws. The last case is the one the save path also handles: a
 * self-healed/retried trailhead that recorded more than one tool is rejected by the unified emitter
 * (`MultiToolTrailheadUnsupported`, since a trailhead allows at most one tool per classifier), so the
 * preview must degrade to the v1 recording rather than showing an empty box and copying nothing.
 */
fun List<TrailblazeLog>.generateUnifiedRecordedYaml(
  trailblazeYaml: TrailblazeYaml,
  sessionTrailConfig: TrailConfig? = null,
  classifierOverride: String? = null,
): String {
  val items = try {
    buildRecordedTrailItems(trailblazeYaml, sessionTrailConfig)
  } catch (e: Exception) {
    Console.error("Failed to build recording items: ${e.stackTraceToString()}")
    return ""
  }
  val v1 = try {
    trailblazeYaml.encodeToString(items)
  } catch (e: Exception) {
    Console.error("Failed to encode v1 recording: ${e.stackTraceToString()}")
    return ""
  }
  val classifier = classifierOverride ?: recordingClassifier()
  if (classifier.isBlank()) return v1
  return try {
    val merged = UnifiedTrailAdapter.mergeRecordedClassifier(
      existing = existingUnifiedTrailFromRawYaml(trailblazeYaml),
      recordedItems = items,
      classifier = classifier,
    )
    if (merged.trail.isEmpty()) v1 else trailblazeYaml.encodeUnifiedTrailToString(merged)
  } catch (e: Exception) {
    // The unified shape couldn't be produced (e.g. multi-tool trailhead). Fall back to the v1
    // preview instead of returning empty — the save path preserves v1 for the same case.
    Console.error(
      "Failed to render unified recording; falling back to v1 preview: ${e.stackTraceToString()}",
    )
    v1
  }
}

/**
 * The unified trail the session ran against, recovered from [SessionStatus.Started.rawYaml] so a
 * single-device re-record can merge into it without dropping the other platforms' recordings.
 * Null when the session logged no source YAML (a from-scratch run) or the source was a legacy v1
 * trail (nothing unified to preserve) — both cases correctly seed the merge empty.
 *
 * A decode failure on a *present* `rawYaml` also degrades to null rather than throwing, but is
 * logged: silently seeding empty here would drop every other platform's recording from the preview
 * with no trace, indistinguishable from a real from-scratch run.
 */
private fun List<TrailblazeLog>.existingUnifiedTrailFromRawYaml(
  trailblazeYaml: TrailblazeYaml,
): UnifiedTrail? {
  val rawYaml = getSessionStartedInfo()?.rawYaml?.takeIf { it.isNotBlank() } ?: return null
  return try {
    when (val doc = trailblazeYaml.decodeTrailDocument(rawYaml)) {
      is TrailDocument.Unified -> doc.trail
      is TrailDocument.V1 -> null
    }
  } catch (e: Exception) {
    Console.error(
      "Unified preview: source rawYaml present but failed to decode; other platforms' " +
        "recordings will be absent from this preview. ${e.message}",
    )
    null
  }
}

/**
 * Builds the v1 [TrailYamlItem] list for this session's logs (config, optional trailhead, prompts).
 * Shared source of truth for both [generateRecordedYaml] (v1 encode) and [generateUnifiedRecordedYaml]
 * (unified merge + encode), so the two rendered formats can never diverge in content.
 */
private fun List<TrailblazeLog>.buildRecordedTrailItems(
  trailblazeYaml: TrailblazeYaml,
  sessionTrailConfig: TrailConfig? = null,
): List<TrailYamlItem> {
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
    // Carry the source config wholesale (copy, not a field list) so the saved recording is
    // lossless — a rebuilt field list here silently dropped electron/tags/skip/memory before
    // the save-back merge ever saw them.
    if (sessionTrailConfig != null || resolvedDriver != null) {
      items.add(
        TrailYamlItem.ConfigTrailItem(
          (sessionTrailConfig ?: TrailConfig()).copy(driver = resolvedDriver),
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
            .ifEmpty { dropNestedToolCalls(toolLogsInWindow) }
            .let { dedupeLayerDuplicates(it) }
          val rawWrappers: List<TrailblazeToolYamlWrapper> = selectedToolLogs
            .map { log -> wrapTrailblazeTool(log.authoredTrailblazeTool, log.toolName) }
          val toolWrappers = dedupeVerificationRepeats(rawWrappers, selectedToolLogs, trailblazeYaml)

          // Zero recordable tools in this objective window → emit no recording at all (null).
          // At replay time the step will fall through to AI. A live session capturing zero tools
          // means "not recorded," never "author declared a no-op" — that empty-but-declared state
          // (see ToolRecording's 3-state doc) is reserved for deliberate, hand-authored
          // declarations, so this generator must never manufacture it on its own.
          val recording = if (toolWrappers.isNotEmpty()) {
            ToolRecording(tools = toolWrappers)
          } else {
            null
          }

          if (promptStep is DirectionStep && promptStep.isTrailhead) {
            // This objective is the lowered form of the trail's `trailhead:` (step 0). Re-emit it as a
            // first-class `- trailhead:` root element instead of a prompt step so the recorded trail
            // keeps its trailhead. A bare-string-shorthand trailhead carries no authored step text
            // (DEFAULT_STEP stands in), so drop it back to null to round-trip cleanly.
            val trailheadStep = promptStep.prompt.takeIf { it != TrailheadDefinition.DEFAULT_STEP }
            // Keep null (not `?: emptyList()`) — a session that captured zero trailhead tools is
            // "not recorded," not a declared no-op (same reasoning as `recording` above).
            val trailheadTools = recording?.tools
            if (trailheadStep != null || !trailheadTools.isNullOrEmpty()) {
              upsertTrailheadItem(items, trailheadStep, trailheadTools, promptStep.maxRetries)
            }
          } else {
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
            val wrapper = wrapTrailblazeTool(currentLog.authoredTrailblazeTool, currentLog.toolName)
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

  return items
}

/**
 * Adds the trailhead item for a trailhead-marked objective window, merging into the existing
 * `- trailhead:` item when one is already present. A self-healed (or retried) trailhead produces
 * multiple windows for the same step 0 — the failed recorded attempt closes its window before AI
 * recovery opens its own start/complete pair — and the strict parser allows exactly one trailhead
 * item per trail. Merge semantics: first window's step text/maxRetries (identical across windows),
 * tools concatenated in execution order (the same information-preserving choice as keeping failed
 * tools in recordings), null-preserving so "not recorded" (null) is never manufactured into a
 * declared-empty list. Assumes trailhead windows precede all prompt windows (step 0 runs — and
 * heals — before step 1), so the merged item keeps its parser-legal position before any prompts.
 */
private fun upsertTrailheadItem(
  items: MutableList<TrailYamlItem>,
  trailheadStep: String?,
  trailheadTools: List<TrailblazeToolYamlWrapper>?,
  maxRetries: Int?,
) {
  val existingIndex = items.indexOfFirst { it is TrailYamlItem.TrailheadTrailItem }
  if (existingIndex < 0) {
    items.add(
      TrailYamlItem.TrailheadTrailItem(
        TrailheadDefinition(
          step = trailheadStep,
          tools = trailheadTools,
          maxRetries = maxRetries,
        ),
      ),
    )
    return
  }
  val existing = (items[existingIndex] as TrailYamlItem.TrailheadTrailItem).trailhead
  val mergedTools = when {
    existing.tools == null -> trailheadTools
    trailheadTools == null -> existing.tools
    else -> existing.tools + trailheadTools
  }
  Console.log(
    "[recording-merge] folded a repeated trailhead objective window (self-heal/retry) into the " +
      "existing trailhead item — ${mergedTools?.size ?: 0} tools kept in execution order.",
  )
  items[existingIndex] = TrailYamlItem.TrailheadTrailItem(
    TrailheadDefinition(
      step = existing.step ?: trailheadStep,
      tools = mergedTools,
      maxRetries = existing.maxRetries ?: maxRetries,
    ),
  )
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

/**
 * Drops tool logs that are nested dispatches INSIDE another logged tool call — the `ctx.tools.*`
 * sub-calls a composite (scripted) tool makes while it runs. Only the outermost call is the
 * replayable recording: re-emitting the internals alongside it would replay every piece twice and
 * couple the recording to the composite's implementation (the point of a one-call trailhead like
 * `myapp_signedInToRoute` is that the trail sees ONE call).
 *
 * Detection is by execution-span containment ([TrailblazeLog.TrailblazeToolLog.timestamp] +
 * `durationMs`): a nested call's span lies inside its parent's strictly-longer span. Span math is
 * the signal because only the MCP step path stamps `isTopLevelToolCall` — runner/replay paths never
 * set it (which is why this runs on the `ifEmpty` fallback), and it also holds for logs recorded
 * before any emitter stamped the flag. The strictly-longer requirement means identical spans (layer
 * duplicates, collapsed separately by [dedupeLayerDuplicates]) never drop each other.
 *
 * Order-preserving: returns the input list minus the contained entries, in input order.
 */
private fun dropNestedToolCalls(
  logs: List<TrailblazeLog.TrailblazeToolLog>,
): List<TrailblazeLog.TrailblazeToolLog> {
  if (logs.size < 2) return logs
  fun startMs(log: TrailblazeLog.TrailblazeToolLog): Long = log.timestamp.toEpochMilliseconds()
  fun endMs(log: TrailblazeLog.TrailblazeToolLog): Long = startMs(log) + log.durationMs
  return logs.filter { candidate ->
    logs.none { container ->
      container !== candidate &&
        startMs(container) <= startMs(candidate) &&
        endMs(container) >= endMs(candidate) &&
        (endMs(container) - startMs(container)) > (endMs(candidate) - startMs(candidate))
    }
  }
}

/**
 * Collapses "layer-duplicate" tool logs — when one physical tool execution produces two
 * `TrailblazeToolLog` entries because two layers of the execution pipeline each emit a log
 * (the LLM-dispatch layer logs with a `llm-…` trace and the host/RPC executor logs with a
 * `tool-…` trace). Both rows have identical toolName + args + success, differ only in trace
 * ID prefix, and represent the same physical work — so the recording should serialize them
 * once.
 *
 * Algorithm: group ALL logs (not just consecutive) by `(toolName, argsFingerprint, successful)`.
 * Within a group, only the SPECIFIC `(llm, tool)` prefix pair triggers dedup — drop the
 * `llm-…` entries and keep the `tool-…` ones (executor logs are the source of truth for
 * driver activity). Other prefix combinations (`maestro-`, `mcp-`, no-prefix, etc.) do NOT
 * trigger dedup, and same-prefix repeats (e.g. two `tool-…` entries from a deliberate
 * digit-key replay) survive unchanged.
 *
 * Order-preserving: returns the input list minus the dropped entries, in input order.
 */
private fun dedupeLayerDuplicates(
  logs: List<TrailblazeLog.TrailblazeToolLog>,
): List<TrailblazeLog.TrailblazeToolLog> {
  if (logs.size < 2) return logs
  val groups = logs.groupBy {
    // Group on the AUTHORED payload: the two layers of one physical execution may interpolate
    // at different points (the LLM-dispatch log can carry the token-bearing form while the
    // executor log carries resolved + raw), but their authored form is always identical.
    Triple(it.toolName, it.authoredTrailblazeTool.argsFingerprint(), it.successful)
  }
  val dropped = mutableSetOf<TrailblazeLog.TrailblazeToolLog>()
  for ((_, group) in groups) {
    val prefixes = group.mapNotNull { tracePrefix(it.traceId?.traceId) }.toSet()
    if ("llm" in prefixes && "tool" in prefixes) {
      // Layer-duplicate signature confirmed. Drop the LLM-dispatch logs; the `tool-…`
      // entries represent the actual executor work and carry the driver-event traces.
      group.filterTo(dropped) { tracePrefix(it.traceId?.traceId) == "llm" }
    }
  }
  return if (dropped.isEmpty()) logs else logs.filterNot { it in dropped }
}

/** Returns the origin token of a [TraceId] string ("llm" / "tool" / "maestro" / "mcp"), or
 *  null when the input isn't a `prefix-uuid` shape. */
private fun tracePrefix(traceId: String?): String? {
  if (traceId.isNullOrEmpty()) return null
  val dash = traceId.indexOf('-')
  if (dash <= 0) return null
  return traceId.substring(0, dash)
}

/** Fingerprint over the canonical JSON encoding of the tool's args. kotlinx.serialization's
 *  `JsonObject.toString()` emits keys in insertion order and the same `OtherTrailblazeTool`
 *  payload is produced by `toLogPayload()` on both layers of the duplicate, so the two encode
 *  to identical strings. Used by [dedupeLayerDuplicates] to identify same-args entries. */
private fun xyz.block.trailblaze.logs.client.temp.OtherTrailblazeTool.argsFingerprint(): String =
  this.raw.toString()

/**
 * The payload a recording must serialize: the tool AS AUTHORED (memory tokens intact) when the
 * dispatch boundary resolved `{{var}}` / `${var}` tokens, else the dispatched form (which IS the
 * authored form for token-free calls and for logs written before [TrailblazeLog.TrailblazeToolLog.rawTrailblazeTool]
 * existed). Emitting the resolved form here would bake one run's memory values into the saved
 * trail — the recording-fidelity defect the raw/resolved split exists to fix.
 */
private val TrailblazeLog.TrailblazeToolLog.authoredTrailblazeTool: xyz.block.trailblaze.logs.client.temp.OtherTrailblazeTool
  get() = rawTrailblazeTool ?: trailblazeTool
