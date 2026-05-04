package xyz.block.trailblaze.segment

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import xyz.block.trailblaze.api.waypoint.WaypointDefinition
import xyz.block.trailblaze.logs.client.TrailblazeJson
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.client.temp.OtherTrailblazeTool
import xyz.block.trailblaze.toolcalls.getToolNameFromAnnotation
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.waypoint.WaypointMatcher
import java.io.File
import java.io.IOException

/**
 * Distills a session log directory into a list of [TrailSegment]s — one per observed
 * transition between two waypoints.
 *
 * Algorithm:
 *  1. Read every `*.json` log file in the session directory and decode as [TrailblazeLog].
 *     Skip files that don't parse (matching [xyz.block.trailblaze.report.utils.LogsRepo]'s
 *     resilient behavior — a single malformed log shouldn't fail the whole walk). The
 *     skip count is surfaced via [SessionLogReadResult.parseFailures] / [Analysis.parseFailures]
 *     so callers can tell "session was empty" apart from "session had files we dropped."
 *  2. Sort all logs by their [TrailblazeLog.timestamp] so we can reason about time-ordering
 *     uniformly. The on-disk filename prefix is monotonic but the timestamp field is
 *     authoritative; using it keeps the algorithm correct even if some emitter writes
 *     out-of-order names.
 *  3. Walk the sorted list in order, picking out [TrailblazeLog.TrailblazeLlmRequestLog]
 *     entries — each is a screen capture. For each one, run the waypoint matcher to
 *     identify which waypoint (if any) it sits at.
 *  4. For each pair of consecutive matched-waypoint screen captures `(a, b)` where
 *     `a.matched != null && b.matched != null && a.matched != b.matched`, collect every
 *     top-level [TrailblazeLog.TrailblazeToolLog] whose timestamp falls strictly between
 *     `a.timestamp` and `b.timestamp`. Those tool calls are the segment's triggers.
 *
 * Why tool calls (`TrailblazeTool`) and not driver actions (`AgentDriverAction`):
 *   the tool call retains the [xyz.block.trailblaze.api.TrailblazeNodeSelector] the LLM
 *   issued, which is the device-portable identity of the action. The driver-side
 *   `TapPoint(x, y)` records the resolved coordinates, which differ device-by-device for
 *   the same intent.
 *
 * Self-loops (`a.matched == b.matched`) are deliberately skipped — those are retries
 * within the same screen, not transitions. Null↔matched transitions (entering or leaving
 * the known waypoint set) are also skipped in v1; they're useful signals but the cleanest
 * core extractor is "boundary between two named waypoints."
 *
 * ## API surface
 *
 * Two entry points by intent:
 *  - [extract]/[analyze] taking a [File] — the production CLI path, which reads from disk.
 *  - [analyze] taking a pre-loaded `List<TrailblazeLog>` — the algorithm-only path, used by
 *    tests and by future in-memory callers (e.g. a visualizer streaming logs from a
 *    server). The disk-based overloads delegate to this one after reading.
 */
object SessionSegmentExtractor {

  /**
   * Sentinel emitted by [formatSamplingTrigger] when an [TrailblazeLog.McpSamplingLog]
   * carries a non-empty completion that we can't decode (not valid JSON, or valid JSON
   * that isn't a `JsonObject`). Surfaced in trigger lists rather than dropped silently
   * so empty triggers consistently mean "no actions" rather than "actions occurred but
   * the shape was unexpected."
   */
  const val UNPARSEABLE_COMPLETION_SENTINEL: String = "decision[unparseable]"

  /**
   * Extracts segments from the session at [sessionDir], matching each step against
   * [waypoints]. Returns segments in the order they were observed; aggregation across
   * sessions is the caller's job. Use [analyze] when you also need step-level stats
   * (how many request logs, how many had a node tree, how many matched a waypoint) to
   * surface in CLI output or diagnose "no segments" outcomes.
   */
  fun extract(sessionDir: File, waypoints: List<WaypointDefinition>): List<TrailSegment> =
    analyze(sessionDir, waypoints).segments

  /** Detailed extraction result for CLI diagnostics — see [extract] for the trimmed form. */
  data class Analysis(
    val totalRequestLogs: Int,
    val stepsWithNodeTree: Int,
    val stepsWithMatchedWaypoint: Int,
    /**
     * Steps where more than one waypoint definition matched the same screen. These are
     * deliberately excluded from [segments] so the extracted series doesn't depend on
     * waypoint list ordering — surface this count in CLI output to flag a need for
     * tighter selectors.
     */
    val stepsWithAmbiguousMatch: Int,
    /**
     * Number of `*.json` files in the session directory that failed to decode as
     * [TrailblazeLog] and were skipped. Always `0` for the in-memory [analyze] overload
     * (no disk read happened); non-zero values come from the [File]-based overload via
     * [readSessionLogs]. Surface this in CLI output so users can distinguish "session is
     * empty" from "session had files we dropped because they didn't parse."
     */
    val parseFailures: Int,
    val segments: List<TrailSegment>,
    /**
     * Per-waypoint list of 1-based request-log step indices where that waypoint matched
     * uniquely (ambiguous-match steps are excluded, mirroring [segments]).
     *
     * Populated as a complete record of waypoint hits — not just transition boundaries —
     * so consumers can answer "which steps in this session matched waypoint X?" without
     * trying to reconstruct it from segments. Three observed-but-stationary hits on
     * waypoint A, for example, produce no segments yet still appear here as `A → [1, 2, 3]`.
     *
     * Step indices within each list are in chronological order. Waypoints that never
     * matched in this session are omitted from the map (not present rather than mapped
     * to an empty list) so callers can use `containsKey`/`get` symmetrically.
     *
     * Default value is empty for source-compat with existing test fixtures that build
     * `Analysis` by hand.
     */
    val matchedStepsByWaypoint: Map<String, List<Int>> = emptyMap(),
  )

  /** Disk-backed convenience: read [sessionDir], then run the in-memory [analyze]. */
  fun analyze(sessionDir: File, waypoints: List<WaypointDefinition>): Analysis {
    require(sessionDir.isDirectory) { "Not a session directory: $sessionDir" }
    val read = readSessionLogs(sessionDir)
    return analyze(
      logs = read.logs,
      sessionPath = sessionDir.absolutePath,
      waypoints = waypoints,
      parseFailures = read.parseFailures,
    )
  }

  /**
   * Algorithm-only entry point: takes a list of [TrailblazeLog] entries and the session's
   * display path, and returns segment + diagnostic counts without any disk I/O. Tests
   * drive this overload directly with hand-built logs; in-memory callers (e.g. a
   * server-side aggregator that already has logs decoded) reuse it for the same reason.
   *
   * [logs] are sorted by [TrailblazeLog.timestamp] on entry — callers may pass any order.
   * Sorting defensively here eliminates a footgun: timestamp-based duration calculation
   * silently produced negative values when a caller forgot to pre-sort, and the disk path
   * sorted but the in-memory path didn't, which made the contract sourceset-dependent.
   * Sorting cost is negligible relative to per-step waypoint matching.
   *
   * [parseFailures] is forwarded into the resulting [Analysis] unchanged. Pass `0` if you
   * don't have a separate file-read step that produced skipped files.
   */
  fun analyze(
    logs: List<TrailblazeLog>,
    sessionPath: String,
    waypoints: List<WaypointDefinition>,
    parseFailures: Int = 0,
  ): Analysis {
    if (logs.isEmpty()) {
      return Analysis(
        totalRequestLogs = 0,
        stepsWithNodeTree = 0,
        stepsWithMatchedWaypoint = 0,
        stepsWithAmbiguousMatch = 0,
        parseFailures = parseFailures,
        segments = emptyList(),
      )
    }
    val sorted = logs.sortedBy { it.timestamp }
    val collection = collectMatchedSteps(sorted, waypoints)
    val segments = buildSegments(collection.matched, sorted, sessionPath)
    val matchedStepsByWaypoint = groupMatchedSteps(collection.matched)
    // Single observation line per analyze() — turns "no badges in the UI despite a known
    // matching session" into a one-grep diagnosis. Cheap (one log per extract, not per
    // recomposition) and only fires when a session is genuinely analyzed.
    Console.log(
      "[SessionSegmentExtractor] matchedStepsByWaypoint: " +
        "${matchedStepsByWaypoint.size} waypoint(s), " +
        "${matchedStepsByWaypoint.values.sumOf { it.size }} total match(es)",
    )
    return Analysis(
      totalRequestLogs = collection.totalRequestLogs,
      stepsWithNodeTree = collection.stepsWithNodeTree,
      stepsWithMatchedWaypoint = collection.matched.size,
      stepsWithAmbiguousMatch = collection.stepsWithAmbiguousMatch,
      parseFailures = parseFailures,
      segments = segments,
      matchedStepsByWaypoint = matchedStepsByWaypoint,
    )
  }

  /**
   * Buckets each matched step by its waypoint id, preserving chronological order within
   * each bucket. Pulled out so it is unit-testable in isolation and so the [analyze]
   * body stays readable.
   *
   * Returns immutable per-bucket lists (built once, copied) so a caller can't downcast and
   * mutate them — the [Analysis] data class is meant to read as a value type, and a
   * mutated list under a Compose `remember` key would silently produce stale UI.
   */
  private fun groupMatchedSteps(matched: List<MatchedStep>): Map<String, List<Int>> {
    if (matched.isEmpty()) return emptyMap()
    val buckets = LinkedHashMap<String, MutableList<Int>>()
    for (step in matched) {
      buckets.getOrPut(step.waypointId) { mutableListOf() } += step.stepIndex
    }
    val out = LinkedHashMap<String, List<Int>>(buckets.size)
    for ((waypointId, stepIndexes) in buckets) {
      out[waypointId] = stepIndexes.toList()
    }
    return out
  }

  /**
   * A request-log step that successfully matched a waypoint.
   *
   * Holds [logIndex] (the 0-based position in the chronologically sorted full log list)
   * so the segment-builder can window triggers by index rather than millisecond timestamp.
   * Index-based windowing avoids two failure modes: tool calls that share a millisecond
   * with a boundary log are still picked up, and any future change to log timestamp
   * precision can't silently flip which side of the boundary a log lands on.
   */
  private data class MatchedStep(
    val stepIndex: Int, // 1-based index among request logs
    val logIndex: Int, // 0-based index in the chronologically sorted full log list
    val waypointId: String,
    val timestampEpochMs: Long,
  )

  /**
   * Result of a session-directory read: the successfully decoded logs plus a count of
   * `*.json` files we tried to parse but couldn't. Surfaced through [Analysis] so the
   * CLI can tell a confused user that we silently skipped N files.
   */
  data class SessionLogReadResult(
    val logs: List<TrailblazeLog>,
    val parseFailures: Int,
  )

  /**
   * Reads every TrailblazeLog from [sessionDir]. Files that don't decode are counted in
   * [SessionLogReadResult.parseFailures] rather than throwing — consistent with
   * [xyz.block.trailblaze.report.utils.LogsRepo]'s "one bad log doesn't fail the walk"
   * stance.
   *
   * **Returned logs are guaranteed sorted by [TrailblazeLog.timestamp]**, so a caller
   * that wants to feed the result into [analyze]`(logs, …)` can rely on chronological
   * order without re-sorting (the in-memory `analyze` overload also sorts defensively,
   * so this guarantee is for API readability rather than correctness).
   *
   * Throws [IOException] if [sessionDir] exists but [File.listFiles] returns `null` —
   * that signals an actual access problem (permission denied, removed mid-walk) which
   * would otherwise be indistinguishable from "directory was empty". Empty directories
   * still return `SessionLogReadResult(emptyList(), 0)`.
   */
  fun readSessionLogs(sessionDir: File): SessionLogReadResult {
    // Match LogsRepo's filename rule (hex-prefixed *.json) and explicitly skip the
    // session-companion file that CaptureSession writes alongside the per-step logs —
    // it isn't a TrailblazeLog and would otherwise be parsed and counted as a "parse
    // failure" on every run.
    val rawFiles = sessionDir.listFiles()
      ?: throw IOException(
        "Could not list files in session directory: ${sessionDir.absolutePath} " +
          "(permission denied or removed during walk)",
      )
    val files = rawFiles.filter {
      it.extension == "json" &&
        it.name != CAPTURE_METADATA_FILENAME &&
        it.name.firstOrNull()?.isHexDigit() == true
    }
    var failures = 0
    val decoded = files.mapNotNull { file ->
      try {
        TrailblazeJson.defaultWithoutToolsInstance
          .decodeFromString(TrailblazeLog.serializer(), file.readText())
      } catch (_: Exception) {
        failures += 1
        null
      }
    }.sortedBy { it.timestamp }
    return SessionLogReadResult(logs = decoded, parseFailures = failures)
  }

  /**
   * Local null-safe + uppercase-tolerant hex check, intentionally **not** sharing
   * [xyz.block.trailblaze.report.utils.LogsRepo]'s identical-but-stricter helper:
   *  - LogsRepo lives in `trailblaze-report` (JVM-only); this extractor lives in
   *    `trailblaze-common` (jvmAndAndroid). Pulling LogsRepo down here would invert the
   *    dependency direction.
   *  - LogsRepo's version is lowercase-only and not null-safe (`firstOrNull` would crash).
   *    The session-log files we care about always start with a lowercase hex, so for
   *    LogsRepo's path the difference is academic — but this extractor is the one that
   *    walks user-provided directories, where defensive uppercase-tolerance is cheap
   *    insurance.
   *
   * If/when a shared util module emerges that both modules depend on, hoist this there.
   */
  private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

  private data class MatchedStepCollection(
    val matched: List<MatchedStep>,
    val totalRequestLogs: Int,
    val stepsWithNodeTree: Int,
    val stepsWithAmbiguousMatch: Int,
  )

  private fun collectMatchedSteps(
    logs: List<TrailblazeLog>,
    waypoints: List<WaypointDefinition>,
  ): MatchedStepCollection {
    val out = mutableListOf<MatchedStep>()
    var requestStepIndex = 0
    var stepsWithNodeTree = 0
    var stepsWithAmbiguousMatch = 0
    for ((logIndex, log) in logs.withIndex()) {
      if (log !is TrailblazeLog.TrailblazeLlmRequestLog) continue
      requestStepIndex += 1
      val tree = log.trailblazeNodeTree ?: continue
      stepsWithNodeTree += 1
      // Require exactly one matching waypoint. Zero matches stay unmatched. Multiple
      // matches are ambiguous: under "first wins" the segment series silently depends on
      // waypoint list ordering, which is invisible to the author and changes when new
      // waypoints are added. Better to skip and surface the count so the author can
      // tighten selectors.
      //
      // Short-circuit on the second match: large packs (100+ waypoints) used to call
      // WaypointMatcher.match for every entry even after the second match, which is
      // wasted work — the conclusion ("ambiguous, skip") is already determined.
      var firstMatchedId: String? = null
      var ambiguous = false
      for (waypoint in waypoints) {
        val result = WaypointMatcher.match(waypoint, tree)
        if (!result.matched) continue
        if (firstMatchedId != null) {
          ambiguous = true
          break
        }
        firstMatchedId = result.definitionId
      }
      if (ambiguous) {
        stepsWithAmbiguousMatch += 1
        continue
      }
      val matchedId = firstMatchedId ?: continue
      out += MatchedStep(
        stepIndex = requestStepIndex,
        logIndex = logIndex,
        waypointId = matchedId,
        timestampEpochMs = log.timestamp.toEpochMilliseconds(),
      )
    }
    return MatchedStepCollection(
      matched = out,
      totalRequestLogs = requestStepIndex,
      stepsWithNodeTree = stepsWithNodeTree,
      stepsWithAmbiguousMatch = stepsWithAmbiguousMatch,
    )
  }

  private fun buildSegments(
    matched: List<MatchedStep>,
    allLogs: List<TrailblazeLog>,
    sessionDirAbsolutePath: String,
  ): List<TrailSegment> {
    if (matched.size < 2) return emptyList()
    // Two trigger sources:
    //  - TrailblazeToolLog: the older direct-tool path (no LLM in the loop).
    //  - McpSamplingLog: the modern blaze-via-MCP path, where the action is the LLM's
    //    structured `completion` response. Both feed the same string trigger list so a
    //    consumer (CLI today, future aggregator) doesn't need to know the log shape.
    val out = mutableListOf<TrailSegment>()
    for (i in 0 until matched.size - 1) {
      val a = matched[i]
      val b = matched[i + 1]
      if (a.waypointId == b.waypointId) continue
      // Walk the strictly-between window in the chronologically sorted log list. Using
      // indices (instead of `timestampEpochMs in (a + 1) until b`) sidesteps the
      // millisecond-truncation hazard: two logs that share an Instant millisecond with a
      // boundary would otherwise be excluded.
      val betweenLogs = allLogs.subList(a.logIndex + 1, b.logIndex)
      val toolTriggers = betweenLogs
        .filterIsInstance<TrailblazeLog.TrailblazeToolLog>()
        .filter { it.isTopLevelToolCall }
        .map { formatToolTrigger(it) }
      val samplingTriggers = betweenLogs
        .filterIsInstance<TrailblazeLog.McpSamplingLog>()
        .filter { it.successful }
        .map { formatSamplingTrigger(it) }
      // Source preference: if both shapes are present (which shouldn't happen in a single
      // session today), prefer the typed `TrailblazeToolLog` triggers and fall back to
      // sampling-derived ones. This keeps blaze-only sessions producing useful triggers
      // without losing fidelity in the rare hybrid case.
      val triggers = toolTriggers.ifEmpty { samplingTriggers }
      out += TrailSegment(
        from = a.waypointId,
        to = b.waypointId,
        triggers = triggers,
        observation = SegmentObservation(
          sessionDir = sessionDirAbsolutePath,
          fromStep = a.stepIndex,
          toStep = b.stepIndex,
          durationMs = b.timestampEpochMs - a.timestampEpochMs,
        ),
      )
    }
    return out
  }

  /**
   * Field names we know about in the structured LLM completion JSON. Hoisted out of the
   * decoder so they aren't magic strings buried in the function body, and so a schema
   * rename has exactly one place to update. Internal so tests can pin the contract
   * directly.
   */
  internal object SamplingCompletionFields {
    const val REF: String = "ref"
    const val TEXT: String = "text"
    const val ANSWER: String = "answer"
  }

  /**
   * Completion fields that carry LLM commentary rather than the actionable decision —
   * filtered out of the `decision[…]` field-name fingerprint so it stays focused on the
   * structural choice keys (`ref`/`text`/`answer`/etc.) rather than every reasoning blob.
   */
  private val IGNORED_COMPLETION_FIELDS: Set<String> = setOf(
    "reasoning",
    "screenSummary",
    "confidence",
  )

  /** Filename of the session-companion file that CaptureSession writes; not a TrailblazeLog. */
  private const val CAPTURE_METADATA_FILENAME: String = "capture_metadata.json"

  /**
   * Formats a [TrailblazeLog.TrailblazeToolLog] entry as a one-line trigger summary.
   *
   * The name comes from [getToolNameFromAnnotation] so [OtherTrailblazeTool] (the shape
   * everything decodes to under `defaultWithoutToolsInstance`, since no tool classes are
   * registered) and `HostLocalExecutableTrailblazeTool` both render their advertised
   * names rather than the wrapper class. For args, we strip the wrapper too: an
   * [OtherTrailblazeTool] surface as `tap {ref:"z639"}` instead of the noisier
   * `OtherTrailblazeTool(toolName=tap, raw={ref:"z639"})` that bare `toString()` produces.
   * Typed tools (rare on this code path) keep their data-class `toString()`.
   *
   * Internal so tests can drive it directly with hand-built tool logs.
   */
  internal fun formatToolTrigger(log: TrailblazeLog.TrailblazeToolLog): String {
    val tool = log.trailblazeTool
    val name = tool.getToolNameFromAnnotation()
    val args = when (tool) {
      is OtherTrailblazeTool -> tool.raw.toString()
      else -> tool.toString()
    }
    return "$name $args"
  }

  /**
   * Best-effort summary of the LLM's structured tool-call decision. The on-disk
   * [TrailblazeLog.McpSamplingLog] doesn't carry the *target* tool name (that's resolved
   * by the MCP server from the request schema), so we lean on the `completion` JSON's
   * shape: `ref` ⇒ a tap, `text` ⇒ text input, otherwise a fingerprint of the field
   * names.
   *
   * Returns [UNPARSEABLE_COMPLETION_SENTINEL] (rather than `null`) when the completion
   * is non-blank but undecodable as a `JsonObject` — that way trigger lists never silently
   * lose entries: an empty trigger list means "no actions occurred between these
   * waypoints", a list containing the sentinel means "actions occurred but we couldn't
   * decode their shape." Blank completions still return the sentinel; a totally empty
   * `completion` field on a `successful=true` sampling log indicates a logging issue
   * worth surfacing.
   *
   * Internal so tests can drive it directly with hand-built sampling logs.
   */
  internal fun formatSamplingTrigger(log: TrailblazeLog.McpSamplingLog): String {
    val completion = log.completion
    if (completion.isBlank()) return UNPARSEABLE_COMPLETION_SENTINEL
    val element = runCatching { Json.parseToJsonElement(completion) }.getOrNull()
      ?: return UNPARSEABLE_COMPLETION_SENTINEL
    if (element !is JsonObject) return UNPARSEABLE_COMPLETION_SENTINEL
    val ref = element.stringField(SamplingCompletionFields.REF)
    val text = element.stringField(SamplingCompletionFields.TEXT)
    val answer = element.stringField(SamplingCompletionFields.ANSWER)
    return when {
      ref != null && text != null -> "inputText ref=$ref text=\"$text\""
      ref != null -> "tap ref=$ref"
      text != null -> "inputText text=\"$text\""
      answer != null -> "answer=\"$answer\""
      else -> "decision[${element.fieldNames().joinToString(",")}]"
    }
  }

  private fun JsonElement.fieldNames(): List<String> = when (this) {
    is JsonObject -> jsonObject.keys.filter { it !in IGNORED_COMPLETION_FIELDS }
    else -> emptyList()
  }

  private fun JsonObject.stringField(name: String): String? =
    (get(name) as? JsonPrimitive)?.contentOrNull
}
