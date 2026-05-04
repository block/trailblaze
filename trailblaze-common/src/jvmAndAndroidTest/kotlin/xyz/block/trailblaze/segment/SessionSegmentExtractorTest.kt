package xyz.block.trailblaze.segment

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import xyz.block.trailblaze.agent.model.AgentTaskStatus
import xyz.block.trailblaze.agent.model.AgentTaskStatusData
import xyz.block.trailblaze.api.DriverNodeDetail
import xyz.block.trailblaze.api.DriverNodeMatch
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.api.waypoint.WaypointDefinition
import xyz.block.trailblaze.api.waypoint.WaypointSelectorEntry
import xyz.block.trailblaze.llm.TrailblazeLlmModels
import xyz.block.trailblaze.logs.client.TrailblazeJson
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.client.temp.OtherTrailblazeTool
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.TaskId
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.mcp.LlmCallStrategy

/**
 * Tests for [SessionSegmentExtractor].
 *
 * Covers the algorithm-only [SessionSegmentExtractor.analyze] overload (in-memory logs)
 * for fast, focused tests of the segment-extraction logic, plus a small set of disk-backed
 * tests for [SessionSegmentExtractor.readSessionLogs] to pin the file-filter behavior.
 *
 * Why hand-build logs instead of fixture files: the algorithm is pure and the regression
 * cases (ambiguous-match skipping, index-based windowing, sentinel emission) are easier to
 * reason about with explicit timestamps and tree shapes than with serialized fixtures.
 */
class SessionSegmentExtractorTest {

  private val testSession = SessionId("test-session")
  private val baseTime = Instant.fromEpochMilliseconds(1_000_000)

  private val tempDirs = mutableListOf<File>()

  @AfterTest
  fun cleanupTempDirs() {
    tempDirs.forEach { it.deleteRecursively() }
    tempDirs.clear()
  }

  // ---- Algorithm tests via in-memory analyze(logs, sessionPath, waypoints) ----

  @Test
  fun `empty log list returns zero counts and no segments`() {
    val analysis = SessionSegmentExtractor.analyze(
      logs = emptyList(),
      sessionPath = "/tmp/empty",
      waypoints = listOf(waypoint("a", text = "A")),
    )
    assertEquals(0, analysis.totalRequestLogs)
    assertEquals(0, analysis.stepsWithNodeTree)
    assertEquals(0, analysis.stepsWithMatchedWaypoint)
    assertEquals(0, analysis.stepsWithAmbiguousMatch)
    assertEquals(0, analysis.parseFailures)
    assertTrue(analysis.segments.isEmpty())
  }

  @Test
  fun `single matched step yields no segments because there is no transition`() {
    val logs = listOf(requestLog(text = "A", offsetMs = 0))
    val analysis = SessionSegmentExtractor.analyze(
      logs = logs,
      sessionPath = "/tmp/x",
      waypoints = listOf(waypoint("a", text = "A")),
    )
    assertEquals(1, analysis.stepsWithMatchedWaypoint)
    assertTrue(analysis.segments.isEmpty())
  }

  @Test
  fun `self-loop on the same waypoint produces no segment`() {
    val logs = listOf(
      requestLog(text = "A", offsetMs = 0),
      requestLog(text = "A", offsetMs = 100),
    )
    val analysis = SessionSegmentExtractor.analyze(
      logs = logs,
      sessionPath = "/tmp/x",
      waypoints = listOf(waypoint("a", text = "A")),
    )
    assertEquals(2, analysis.stepsWithMatchedWaypoint)
    assertTrue(
      analysis.segments.isEmpty(),
      "self-loop A→A should be filtered out (retry, not a transition)",
    )
  }

  @Test
  fun `simple A to B transition produces one segment with correct boundaries`() {
    val logs = listOf(
      requestLog(text = "A", offsetMs = 0),
      requestLog(text = "B", offsetMs = 250),
    )
    val analysis = SessionSegmentExtractor.analyze(
      logs = logs,
      sessionPath = "/tmp/x",
      waypoints = listOf(waypoint("a", text = "A"), waypoint("b", text = "B")),
    )
    assertEquals(2, analysis.stepsWithMatchedWaypoint)
    assertEquals(1, analysis.segments.size)
    val segment = analysis.segments.single()
    assertEquals("a", segment.from)
    assertEquals("b", segment.to)
    assertEquals(1, segment.observation.fromStep)
    assertEquals(2, segment.observation.toStep)
    assertEquals(250L, segment.observation.durationMs)
    assertEquals("/tmp/x", segment.observation.sessionDir)
  }

  @Test
  fun `ambiguous match is skipped and counted, breaking the segment chain`() {
    // Step 2's screen contains BOTH "A" and "B" nodes — so both waypoint defs (each
    // requiring a single textRegex match) are satisfied. Under "first wins" the segment
    // series would silently depend on definition order; here we expect step 2 to be
    // skipped entirely. textRegex is full-string match (not substring), so the
    // mixed-label screen is the way to drive the ambiguity case.
    val logs = listOf(
      requestLog(text = "A", offsetMs = 0),
      requestLogWithTexts(texts = listOf("A", "B"), offsetMs = 100),
      requestLog(text = "C", offsetMs = 200),
    )
    val analysis = SessionSegmentExtractor.analyze(
      logs = logs,
      sessionPath = "/tmp/x",
      waypoints = listOf(
        waypoint("a", text = "A"),
        waypoint("b", text = "B"),
        waypoint("c", text = "C"),
      ),
    )
    assertEquals(
      1,
      analysis.stepsWithAmbiguousMatch,
      "step 2 has both A and B nodes, so both waypoint defs match",
    )
    assertEquals(2, analysis.stepsWithMatchedWaypoint, "only steps 1 and 3 match exactly one")
    // The unique-match list is [step 1: a, step 3: c], so we get one A→C segment that
    // jumps over the ambiguous step 2 — the chain isn't bridged silently through it.
    assertEquals(1, analysis.segments.size)
    val segment = analysis.segments.single()
    assertEquals("a", segment.from)
    assertEquals("c", segment.to)
    assertEquals(1, segment.observation.fromStep)
    assertEquals(3, segment.observation.toStep)
  }

  @Test
  fun `parseFailures count is forwarded into Analysis`() {
    val analysis = SessionSegmentExtractor.analyze(
      logs = listOf(requestLog(text = "A", offsetMs = 0)),
      sessionPath = "/tmp/x",
      waypoints = listOf(waypoint("a", text = "A")),
      parseFailures = 7,
    )
    assertEquals(7, analysis.parseFailures)
  }

  // ---- matchedStepsByWaypoint ----

  @Test
  fun `matchedStepsByWaypoint groups every uniquely-matched step by waypoint id`() {
    // Three matched steps on A → B → A. Even though only the A→B and B→A transitions
    // produce segments, the matched-step map records every unique hit, so A appears
    // twice (steps 1 and 3) and B once (step 2).
    val logs = listOf(
      requestLog(text = "A", offsetMs = 0),
      requestLog(text = "B", offsetMs = 100),
      requestLog(text = "A", offsetMs = 200),
    )
    val analysis = SessionSegmentExtractor.analyze(
      logs = logs,
      sessionPath = "/tmp/x",
      waypoints = listOf(waypoint("a", text = "A"), waypoint("b", text = "B")),
    )
    assertEquals(
      mapOf("a" to listOf(1, 3), "b" to listOf(2)),
      analysis.matchedStepsByWaypoint,
    )
  }

  @Test
  fun `matchedStepsByWaypoint records self-loops where segments would skip them`() {
    // Self-loop A→A produces no segment, but the matched-step map still records both
    // hits — that's the v1.5 contract: badge every step a waypoint matched, including
    // stationary repeats, so users can see "matched at steps 1 and 2" on a screen the
    // session lingered on.
    val logs = listOf(
      requestLog(text = "A", offsetMs = 0),
      requestLog(text = "A", offsetMs = 100),
    )
    val analysis = SessionSegmentExtractor.analyze(
      logs = logs,
      sessionPath = "/tmp/x",
      waypoints = listOf(waypoint("a", text = "A")),
    )
    assertTrue(analysis.segments.isEmpty(), "self-loops produce no segments")
    assertEquals(mapOf("a" to listOf(1, 2)), analysis.matchedStepsByWaypoint)
  }

  @Test
  fun `matchedStepsByWaypoint excludes ambiguous-match steps`() {
    // Step 2 is ambiguous (matches both A and B); it is excluded from the map exactly
    // like it is from segments. The single uniquely-matched step on A still appears.
    val logs = listOf(
      requestLog(text = "A", offsetMs = 0),
      requestLogWithTexts(texts = listOf("A", "B"), offsetMs = 100),
    )
    val analysis = SessionSegmentExtractor.analyze(
      logs = logs,
      sessionPath = "/tmp/x",
      waypoints = listOf(waypoint("a", text = "A"), waypoint("b", text = "B")),
    )
    assertEquals(1, analysis.stepsWithAmbiguousMatch)
    assertEquals(mapOf("a" to listOf(1)), analysis.matchedStepsByWaypoint)
  }

  @Test
  fun `matchedStepsByWaypoint omits never-matched waypoints`() {
    // Waypoint "b" is in the definition list but never matched in this session — it
    // must be absent from the map (not present-with-empty-list). Callers can use
    // `containsKey`/`get` symmetrically without an extra emptiness check.
    val logs = listOf(requestLog(text = "A", offsetMs = 0))
    val analysis = SessionSegmentExtractor.analyze(
      logs = logs,
      sessionPath = "/tmp/x",
      waypoints = listOf(waypoint("a", text = "A"), waypoint("b", text = "B")),
    )
    assertEquals(mapOf("a" to listOf(1)), analysis.matchedStepsByWaypoint)
    assertTrue("b" !in analysis.matchedStepsByWaypoint)
  }

  @Test
  fun `matchedStepsByWaypoint is empty for empty input log list`() {
    // Empty input → empty map (not a map of empty lists), and no NPE on iteration.
    // This exercises the early-return path in analyze(); the no-matching-logs case is
    // covered separately below.
    val analysis = SessionSegmentExtractor.analyze(
      logs = emptyList(),
      sessionPath = "/tmp/x",
      waypoints = listOf(waypoint("a", text = "A")),
    )
    assertTrue(analysis.matchedStepsByWaypoint.isEmpty())
  }

  @Test
  fun `matchedStepsByWaypoint is empty when logs exist but none match any waypoint`() {
    // Distinct from the empty-input case above: the full collectMatchedSteps + groupMatchedSteps
    // pipeline runs, but no log's nodeTree matches the configured waypoint. The result must
    // still be an empty map (not a map of empty lists, not null) so the visualizer renders no
    // badges. Locks down that the non-early-return path produces the same empty shape.
    val analysis = SessionSegmentExtractor.analyze(
      logs = listOf(
        requestLog(text = "Z", offsetMs = 0),
        requestLog(text = "Z", offsetMs = 100),
      ),
      sessionPath = "/tmp/x",
      waypoints = listOf(waypoint("a", text = "A")), // matcher hunts for "A", logs only emit "Z"
    )
    assertTrue(analysis.matchedStepsByWaypoint.isEmpty())
    assertEquals(0, analysis.stepsWithMatchedWaypoint, "no logs should have matched")
  }

  @Test
  fun `analyze sorts unsorted input defensively`() {
    // Regression guard: the in-memory analyze() overload used to require pre-sorted
    // logs and silently produced negative durations for unsorted callers. After the
    // fix it sorts on entry, so the same logs in any order yield identical output.
    val sorted = listOf(
      requestLog(text = "A", offsetMs = 0),
      requestLog(text = "B", offsetMs = 250),
    )
    val unsorted = listOf(sorted[1], sorted[0])
    val sortedAnalysis = SessionSegmentExtractor.analyze(
      logs = sorted,
      sessionPath = "/tmp/x",
      waypoints = listOf(waypoint("a", text = "A"), waypoint("b", text = "B")),
    )
    val unsortedAnalysis = SessionSegmentExtractor.analyze(
      logs = unsorted,
      sessionPath = "/tmp/x",
      waypoints = listOf(waypoint("a", text = "A"), waypoint("b", text = "B")),
    )
    assertEquals(sortedAnalysis.segments, unsortedAnalysis.segments)
    assertEquals(250L, unsortedAnalysis.segments.single().observation.durationMs)
    assertTrue(
      unsortedAnalysis.segments.single().observation.durationMs >= 0,
      "duration must be non-negative even when input was reverse-sorted",
    )
  }

  @Test
  fun `prefers tool triggers when both tool and sampling logs are present in the window`() {
    // Source preference is documented as `triggers = toolTriggers.ifEmpty { samplingTriggers }`:
    // when both shapes show up in the same window (rare in practice today, possible in
    // a hybrid agent) the typed tool log wins. Pin it.
    val toolLog = TrailblazeLog.TrailblazeToolLog(
      trailblazeTool = OtherTrailblazeTool(
        toolName = "tap",
        raw = JsonObject(mapOf("ref" to JsonPrimitive("toolRef"))),
      ),
      toolName = "tap",
      successful = true,
      traceId = null,
      durationMs = 10,
      session = testSession,
      timestamp = baseTime.plusMs(50),
      isTopLevelToolCall = true,
    )
    val logs = listOf(
      requestLog(text = "A", offsetMs = 0),
      toolLog,
      samplingLog(completionJson = "{\"ref\":\"samplingRef\"}", offsetMs = 60),
      requestLog(text = "B", offsetMs = 100),
    )
    val analysis = SessionSegmentExtractor.analyze(
      logs = logs,
      sessionPath = "/tmp/x",
      waypoints = listOf(waypoint("a", text = "A"), waypoint("b", text = "B")),
    )
    val triggers = analysis.segments.single().triggers
    assertTrue(triggers.any { it.contains("toolRef") }, "tool trigger missing: $triggers")
    assertTrue(
      triggers.none { it.contains("samplingRef") },
      "sampling trigger should be suppressed when tool trigger present, got: $triggers",
    )
  }

  @Test
  fun `non-top-level tool logs are excluded from triggers`() {
    // Tool logs that are part of a higher-level tool's expansion (isTopLevelToolCall=false)
    // shouldn't surface as their own trigger — the user-visible action is the parent.
    val nestedToolLog = TrailblazeLog.TrailblazeToolLog(
      trailblazeTool = OtherTrailblazeTool(
        toolName = "tap",
        raw = JsonObject(mapOf("ref" to JsonPrimitive("nestedRef"))),
      ),
      toolName = "tap",
      successful = true,
      traceId = null,
      durationMs = 10,
      session = testSession,
      timestamp = baseTime.plusMs(50),
      isTopLevelToolCall = false,
    )
    val logs = listOf(
      requestLog(text = "A", offsetMs = 0),
      nestedToolLog,
      requestLog(text = "B", offsetMs = 100),
    )
    val analysis = SessionSegmentExtractor.analyze(
      logs = logs,
      sessionPath = "/tmp/x",
      waypoints = listOf(waypoint("a", text = "A"), waypoint("b", text = "B")),
    )
    // No tool triggers (filtered) and no sampling triggers (none in window) → empty list.
    assertTrue(
      analysis.segments.single().triggers.isEmpty(),
      "non-top-level tool should not contribute a trigger, got: ${analysis.segments.single().triggers}",
    )
  }

  @Test
  fun `unsuccessful sampling logs are excluded from triggers`() {
    // Failed sampling calls don't represent a real action — they shouldn't be summarized
    // as triggers. Only `successful=true` sampling logs feed `formatSamplingTrigger`.
    val failedSampling = samplingLog(
      completionJson = "{\"ref\":\"shouldNotAppear\"}",
      offsetMs = 50,
    ).copy(successful = false)
    val logs = listOf(
      requestLog(text = "A", offsetMs = 0),
      failedSampling,
      requestLog(text = "B", offsetMs = 100),
    )
    val analysis = SessionSegmentExtractor.analyze(
      logs = logs,
      sessionPath = "/tmp/x",
      waypoints = listOf(waypoint("a", text = "A"), waypoint("b", text = "B")),
    )
    assertTrue(
      analysis.segments.single().triggers.isEmpty(),
      "unsuccessful sampling log should not contribute a trigger, " +
        "got: ${analysis.segments.single().triggers}",
    )
  }

  @Test
  fun `index-based windowing includes a sampling log sharing a millisecond with a boundary`() {
    // Regression guard for the millisecond-truncation hazard: a sampling log whose
    // timestamp shares an Instant millisecond with the start boundary used to be
    // excluded by the timestampEpochMs-based filter. Index-based filtering must still
    // include it. Here both the request log at step 1 and the sampling log share
    // offsetMs=0 (same Instant millisecond); the sampling log lives in the chronological
    // list at logIndex=1 (after the request log we wrote first), and the boundary uses
    // logIndex+1, so it's included in the window between step 1 and step 2.
    val logs = listOf(
      requestLog(text = "A", offsetMs = 0),
      samplingLog(completionJson = "{\"ref\":\"z639\"}", offsetMs = 0),
      requestLog(text = "B", offsetMs = 100),
    )
    val analysis = SessionSegmentExtractor.analyze(
      logs = logs,
      sessionPath = "/tmp/x",
      waypoints = listOf(waypoint("a", text = "A"), waypoint("b", text = "B")),
    )
    assertEquals(1, analysis.segments.size)
    assertEquals(listOf("tap ref=z639"), analysis.segments.single().triggers)
  }

  // ---- formatToolTrigger ----

  @Test
  fun `formatToolTrigger renders OtherTrailblazeTool as name plus raw json args`() {
    val log = TrailblazeLog.TrailblazeToolLog(
      trailblazeTool = OtherTrailblazeTool(
        toolName = "tap",
        raw = JsonObject(mapOf("ref" to JsonPrimitive("z639"))),
      ),
      toolName = "tap",
      successful = true,
      traceId = null,
      durationMs = 10,
      session = testSession,
      timestamp = baseTime,
      isTopLevelToolCall = true,
    )
    val rendered = SessionSegmentExtractor.formatToolTrigger(log)
    // Wrapper class name must NOT appear; raw args must.
    assertTrue(
      !rendered.contains("OtherTrailblazeTool"),
      "tool wrapper name leaked into rendered trigger: $rendered",
    )
    assertTrue(rendered.startsWith("tap "), "expected name prefix, got: $rendered")
    assertTrue(rendered.contains("\"ref\""), "expected raw json args, got: $rendered")
    assertTrue(rendered.contains("\"z639\""), "expected raw arg value, got: $rendered")
  }

  // ---- formatSamplingTrigger ----

  @Test
  fun `formatSamplingTrigger ref-only renders as tap ref`() {
    assertEquals("tap ref=z639", samplingTrigger("{\"ref\":\"z639\"}"))
  }

  @Test
  fun `formatSamplingTrigger text-only renders as inputText text`() {
    assertEquals("inputText text=\"hello\"", samplingTrigger("{\"text\":\"hello\"}"))
  }

  @Test
  fun `formatSamplingTrigger ref plus text renders as inputText with both`() {
    assertEquals(
      "inputText ref=z639 text=\"hello\"",
      samplingTrigger("{\"ref\":\"z639\",\"text\":\"hello\"}"),
    )
  }

  @Test
  fun `formatSamplingTrigger answer renders as answer field`() {
    assertEquals("answer=\"yes\"", samplingTrigger("{\"answer\":\"yes\"}"))
  }

  @Test
  fun `formatSamplingTrigger unknown shape produces decision fingerprint, ignoring commentary fields`() {
    val out = samplingTrigger(
      "{\"reasoning\":\"…\",\"screenSummary\":\"…\",\"confidence\":\"high\"," +
        "\"direction\":\"down\",\"distance\":50}",
    )
    // Order in JsonObject preserves insertion order; the fingerprint should drop the
    // ignored commentary fields and keep the structural ones.
    assertEquals("decision[direction,distance]", out)
  }

  @Test
  fun `formatSamplingTrigger blank completion emits the unparseable sentinel`() {
    assertEquals(SessionSegmentExtractor.UNPARSEABLE_COMPLETION_SENTINEL, samplingTrigger(""))
  }

  @Test
  fun `formatSamplingTrigger non-json completion emits the unparseable sentinel`() {
    assertEquals(
      SessionSegmentExtractor.UNPARSEABLE_COMPLETION_SENTINEL,
      samplingTrigger("not json at all"),
    )
  }

  @Test
  fun `formatSamplingTrigger non-object json emits the unparseable sentinel`() {
    // Valid JSON but a primitive — must not silently drop, must surface the sentinel so
    // empty trigger lists consistently mean "no actions" rather than "actions occurred
    // but the shape was unexpected."
    assertEquals(SessionSegmentExtractor.UNPARSEABLE_COMPLETION_SENTINEL, samplingTrigger("\"hi\""))
    assertEquals(SessionSegmentExtractor.UNPARSEABLE_COMPLETION_SENTINEL, samplingTrigger("[1,2]"))
  }

  // ---- readSessionLogs (disk-backed file filter) ----

  @Test
  fun `readSessionLogs excludes capture_metadata json and counts unparseable files as failures`() {
    val dir = newTempDir()
    val log = requestLog(text = "A", offsetMs = 0)
    // The file's first character must be a hex digit to pass the filter — use the
    // serialized log's hash-style naming convention as a stand-in (we just need any
    // hex prefix).
    val serialized = TrailblazeJson.defaultWithoutToolsInstance
      .encodeToString(TrailblazeLog.serializer(), log)
    File(dir, "abc123_request.json").writeText(serialized)
    // Companion file must be skipped, NOT counted as a parse failure.
    File(dir, "capture_metadata.json").writeText("{\"sessionId\":\"x\"}")
    // Hex-prefixed file with garbage content — must be counted as a parse failure.
    File(dir, "deadbeef_garbage.json").writeText("not a TrailblazeLog")
    // Non-hex prefix — silently excluded by the filter (predates this test, behavior
    // matches LogsRepo).
    File(dir, "metadata.json").writeText("{}")

    val result = SessionSegmentExtractor.readSessionLogs(dir)
    assertEquals(1, result.logs.size, "only the valid hex-prefixed log should decode")
    assertEquals(1, result.parseFailures, "deadbeef garbage file should count as 1 failure")
  }

  @Test
  fun `readSessionLogs throws IOException when listFiles returns null`() {
    // A path that doesn't exist makes File.listFiles() return null. We want this signal
    // surfaced as an explicit IOException rather than silently returning an empty result —
    // a CLI user pointing at the wrong directory deserves an actionable error, not a
    // confusing "0 request logs" report.
    val nonexistent = File(newTempDir(), "does-not-exist")
    val thrown = runCatching { SessionSegmentExtractor.readSessionLogs(nonexistent) }
      .exceptionOrNull()
    assertTrue(
      thrown is java.io.IOException,
      "expected IOException, got: ${thrown?.let { it::class.simpleName }}",
    )
    assertTrue(
      thrown.message!!.contains("does-not-exist"),
      "error should mention the path, got: ${thrown.message}",
    )
  }

  // ---- File-based analyze end-to-end (delegates to in-memory + readSessionLogs) ----

  @Test
  fun `analyze File overload surfaces parseFailures from the read step`() {
    val dir = newTempDir()
    File(dir, "deadbeef_garbage.json").writeText("not a TrailblazeLog")
    val analysis = SessionSegmentExtractor.analyze(dir, waypoints = emptyList())
    assertEquals(0, analysis.totalRequestLogs)
    assertEquals(1, analysis.parseFailures)
  }

  // ---- Test fixture helpers ----

  private fun newTempDir(): File {
    val dir = createTempDirectory(prefix = "segment-extractor-test").toFile()
    tempDirs += dir
    return dir
  }

  /**
   * Builds a [TrailblazeNode] tree with one child per text. [WaypointMatcher] runs against
   * [TrailblazeNode] trees, so this is the minimum fixture shape that lets text-based
   * selectors match — and supplying multiple texts lets a single screen satisfy multiple
   * waypoint definitions, which is what the ambiguous-match test needs.
   */
  private fun nodeTreeWithTexts(texts: List<String>): TrailblazeNode = TrailblazeNode(
    nodeId = 1,
    children = texts.mapIndexed { i, text ->
      TrailblazeNode(
        nodeId = (i + 2).toLong(),
        driverDetail = DriverNodeDetail.AndroidAccessibility(text = text),
      )
    },
    driverDetail = DriverNodeDetail.AndroidAccessibility(),
  )

  private fun nodeTreeWithText(text: String): TrailblazeNode = nodeTreeWithTexts(listOf(text))

  /**
   * A minimal [TrailblazeLog.TrailblazeLlmRequestLog] driving the matcher: only
   * [TrailblazeLog.TrailblazeLlmRequestLog.trailblazeNodeTree] and [timestamp][TrailblazeLog.timestamp]
   * matter for [SessionSegmentExtractor]; the rest is filled with safe defaults.
   */
  private fun requestLog(text: String, offsetMs: Long): TrailblazeLog.TrailblazeLlmRequestLog =
    requestLogWithTexts(texts = listOf(text), offsetMs = offsetMs)

  private fun requestLogWithTexts(
    texts: List<String>,
    offsetMs: Long,
  ): TrailblazeLog.TrailblazeLlmRequestLog =
    TrailblazeLog.TrailblazeLlmRequestLog(
      agentTaskStatus = AgentTaskStatus.InProgress(
        statusData = AgentTaskStatusData(
          taskId = TaskId.generate(),
          prompt = "test",
          callCount = 0,
          taskStartTime = baseTime,
          totalDurationMs = 0,
        ),
      ),
      viewHierarchy = ViewHierarchyTreeNode(),
      trailblazeNodeTree = nodeTreeWithTexts(texts),
      instructions = "",
      trailblazeLlmModel = TrailblazeLlmModels.GPT_4O_MINI,
      llmMessages = emptyList(),
      llmResponse = emptyList(),
      actions = emptyList(),
      toolOptions = emptyList(),
      screenshotFile = null,
      durationMs = 0,
      session = testSession,
      timestamp = baseTime.plusMs(offsetMs),
      traceId = TraceId.generate(TraceId.Companion.TraceOrigin.LLM),
      deviceHeight = 0,
      deviceWidth = 0,
    )

  private fun samplingLog(completionJson: String, offsetMs: Long): TrailblazeLog.McpSamplingLog =
    TrailblazeLog.McpSamplingLog(
      llmStrategy = LlmCallStrategy.MCP_SAMPLING,
      systemPrompt = "",
      userMessage = "",
      completion = completionJson,
      includedScreenshot = false,
      usageAndCost = null,
      modelName = null,
      successful = true,
      errorMessage = null,
      durationMs = 0,
      session = testSession,
      timestamp = baseTime.plusMs(offsetMs),
      traceId = TraceId.generate(TraceId.Companion.TraceOrigin.LLM),
    )

  /** Convenience: build a single-required-textRegex waypoint definition. */
  private fun waypoint(id: String, text: String): WaypointDefinition = WaypointDefinition(
    id = id,
    required = listOf(
      WaypointSelectorEntry(
        selector = TrailblazeNodeSelector(
          androidAccessibility = DriverNodeMatch.AndroidAccessibility(textRegex = text),
        ),
      ),
    ),
  )

  /** Convenience to invoke the internal formatter with a hand-built sampling log. */
  private fun samplingTrigger(completionJson: String): String =
    SessionSegmentExtractor.formatSamplingTrigger(samplingLog(completionJson, offsetMs = 0))

  private fun Instant.plusMs(ms: Long): Instant =
    Instant.fromEpochMilliseconds(toEpochMilliseconds() + ms)
}
