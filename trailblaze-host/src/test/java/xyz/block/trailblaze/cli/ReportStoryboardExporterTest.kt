package xyz.block.trailblaze.cli

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonObject
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import xyz.block.trailblaze.agent.model.AgentTaskStatus
import xyz.block.trailblaze.agent.model.AgentTaskStatusData
import xyz.block.trailblaze.api.AgentDriverAction
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.llm.TrailblazeLlmModels
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.client.temp.OtherTrailblazeTool
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.TaskId
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.report.StoryboardHtmlBuilder
import xyz.block.trailblaze.yaml.DirectionStep

/**
 * Unit-level coverage for [StoryboardHtmlBuilder] — the pure functions that produce
 * the storyboard HTML/sections without going through Playwright + ffmpeg.
 *
 * The integration shape (Playwright fullPage capture → libwebp encode → on-disk
 * artifact) is exercised by manual runs on real sessions; pinning every layout pixel
 * in a unit test would be expensive and brittle. What we DO pin here:
 *
 * - [StoryboardHtmlBuilder.buildSections] — the chronological-grouping +
 *   AI/REC + YAML-join logic.
 * - [StoryboardHtmlBuilder.buildHtml] — section/cell markup invariants (chips
 *   present, YAML strip replaces label, labels escape unsafe content).
 * - [StoryboardHtmlBuilder.autoPickColumns] — the aspect-ratio-driven column default.
 * - [StoryboardHtmlBuilder.autoFitCellWidth] — the shrink-to-fit ladder.
 *
 * Each test constructs minimal `TrailblazeLog` fixtures via [makeAgentDriverLog] /
 * [makeObjectiveStart] / [makeToolLog]. The screenshot resolver is a lambda so we
 * don't need to write actual image bytes for cell-shape assertions; the file *does*
 * need to exist on disk because the HTML builder base64-inlines its bytes — tests
 * that exercise `buildHtml` use a tiny 1x1 PNG fixture wired through
 * [TemporaryFolder].
 */
class ReportStoryboardExporterTest {

  @get:Rule val tmp = TemporaryFolder()

  // ---------------------------------------------------------------------------
  // buildSections — chronological grouping, AI/REC detection, YAML join
  // ---------------------------------------------------------------------------

  @Test
  fun `buildSections groups screenshots by surrounding ObjectiveStartLog`() {
    val png = writeTinyPng("frame.png")
    val logs = listOf(
      makeObjectiveStart("Open the home tab"),
      makeAgentDriverLog(screenshot = "a.png", traceId = "t1"),
      makeAgentDriverLog(screenshot = "b.png", traceId = "t2"),
      makeObjectiveStart("Tap into settings"),
      makeAgentDriverLog(screenshot = "c.png", traceId = "t3"),
    )

    val sections = StoryboardHtmlBuilder.buildSections(
      logs = logs,
      resolveScreenshotFile = { png },
    )

    assertEquals(2, sections.size, "Two ObjectiveStartLogs → two sections")
    assertEquals("Open the home tab", sections[0].title)
    assertEquals(2, sections[0].cells.size)
    assertEquals("Tap into settings", sections[1].title)
    assertEquals(1, sections[1].cells.size)
    // Indices are global + 1-based — should continue across sections.
    assertEquals(listOf(1, 2), sections[0].cells.map { it.index })
    assertEquals(listOf(3), sections[1].cells.map { it.index })
  }

  @Test
  fun `buildSections uses DEFAULT_SECTION_TITLE when no ObjectiveStartLog is present`() {
    val png = writeTinyPng("frame.png")
    val logs = listOf(
      makeAgentDriverLog(screenshot = "a.png", traceId = "t1"),
      makeAgentDriverLog(screenshot = "b.png", traceId = "t2"),
    )

    val sections = StoryboardHtmlBuilder.buildSections(
      logs = logs,
      resolveScreenshotFile = { png },
    )

    assertEquals(1, sections.size)
    assertEquals(StoryboardHtmlBuilder.DEFAULT_SECTION_TITLE, sections[0].title)
    assertEquals(2, sections[0].cells.size)
  }

  @Test
  fun `buildSections drops cells whose screenshot file can't be resolved`() {
    val logs = listOf(
      makeAgentDriverLog(screenshot = "missing.png", traceId = "t1"),
    )

    val sections = StoryboardHtmlBuilder.buildSections(
      logs = logs,
      resolveScreenshotFile = { null },
    )

    // Resolver returning null → cell omitted. With no cells, no section emitted either.
    assertTrue(sections.isEmpty(), "Cells with unresolved screenshots produce no section")
  }

  @Test
  fun `buildSections keeps cells whose screenshotFile is a remote URL without invoking the local-file resolver`() {
    // Regression: some remote-device pipelines upload screenshots to S3/CDN and write
    // the signed URL into AgentDriverLog.screenshotFile. Routing those through the
    // local-file resolver dropped every cell ("file doesn't exist on disk") and the
    // storyboard never made it onto the PR comment.
    val url = "https://example.lambda-url.us-east-2.on.aws/?bucket=b&key=k.webp"
    val logs = listOf(
      makeAgentDriverLog(screenshot = url, traceId = "t1"),
    )

    val sections = StoryboardHtmlBuilder.buildSections(
      logs = logs,
      // Resolver returns null for everything — would normally drop the cell. The URL
      // short-circuit means buildSections shouldn't even call this for a URL-form path.
      resolveScreenshotFile = { error("resolveScreenshotFile must not be invoked for URL cells") },
    )

    val cell = sections.single().cells.single()
    assertEquals(url, cell.screenshotUrl, "URL-form screenshotFile lands on screenshotUrl")
    assertEquals(null, cell.screenshot, "URL-form cells have no local File reference")
  }

  @Test
  fun `buildSections attaches yamlSnippet when includeYaml=true and traceId matches a TrailblazeToolLog`() {
    val png = writeTinyPng("frame.png")
    val logs = listOf(
      makeAgentDriverLog(screenshot = "a.png", traceId = "shared-trace"),
      makeToolLog(toolName = "tapOn", traceId = "shared-trace"),
    )

    val sections = StoryboardHtmlBuilder.buildSections(
      logs = logs,
      resolveScreenshotFile = { png },
      includeYaml = true,
    )

    val cell = sections.single().cells.single()
    val yaml = cell.yamlSnippet
    assertTrue(yaml != null, "YAML should be populated for matching traceId")
    assertTrue(yaml.contains("tapOn"), "Tool name should appear in serialized YAML")
  }

  @Test
  fun `buildSections leaves yamlSnippet null when includeYaml=false`() {
    val png = writeTinyPng("frame.png")
    val logs = listOf(
      makeAgentDriverLog(screenshot = "a.png", traceId = "t1"),
      makeToolLog(toolName = "tapOn", traceId = "t1"),
    )

    val sections = StoryboardHtmlBuilder.buildSections(
      logs = logs,
      resolveScreenshotFile = { png },
      includeYaml = false,
    )

    assertEquals(null, sections.single().cells.single().yamlSnippet)
  }

  @Test
  fun `buildSections leaves yamlSnippet null when no TrailblazeToolLog shares the traceId`() {
    val png = writeTinyPng("frame.png")
    val logs = listOf(
      makeAgentDriverLog(screenshot = "a.png", traceId = "t1"),
      makeToolLog(toolName = "tapOn", traceId = "t-different"),
    )

    val sections = StoryboardHtmlBuilder.buildSections(
      logs = logs,
      resolveScreenshotFile = { png },
      includeYaml = true,
    )

    // No matching tool log → fall back to label rendering at HTML time. The cell still
    // exists; only its yamlSnippet field is null.
    assertEquals(null, sections.single().cells.single().yamlSnippet)
  }

  // ---------------------------------------------------------------------------
  // buildHtml — markup invariants
  // ---------------------------------------------------------------------------

  @Test
  fun `buildHtml emits one section per StoryboardSection with title in header`() {
    val cell = makeCell(label = "Tap", sublabel = "(100, 200)")
    val sections = listOf(
      StoryboardHtmlBuilder.StoryboardSection("Open home", listOf(cell.copy(index = 1))),
      StoryboardHtmlBuilder.StoryboardSection("Open settings", listOf(cell.copy(index = 2))),
    )

    val html = StoryboardHtmlBuilder.buildHtml(
      sections = sections,
      columns = 4,
      cellWidthPx = 300,
      pageWidthPx = 1248,
      includeYaml = false,
    )

    assertEquals(2, "<section class=\"section\">".toRegex().findAll(html).count())
    assertContains(html, "Open home")
    assertContains(html, "Open settings")
    // Step-count chip in header — 1 cell each → "1 step".
    assertEquals(2, "1 step</span>".toRegex().findAll(html).count())
  }

  @Test
  fun `buildHtml emits remote URL as img src directly without base64 inlining`() {
    val url = "https://example.lambda-url.us-east-2.on.aws/?bucket=b&key=k.webp"
    val sections = listOf(
      StoryboardHtmlBuilder.StoryboardSection(
        title = "URL cell",
        cells = listOf(
          StoryboardHtmlBuilder.StoryboardCell(
            index = 1,
            label = "Tap",
            sublabel = "",
            screenshot = null,
            screenshotUrl = url,
            deviceWidth = 1080,
            deviceHeight = 2340,
          ),
        ),
      ),
    )

    val html = StoryboardHtmlBuilder.buildHtml(
      sections = sections,
      columns = 4,
      cellWidthPx = 300,
      pageWidthPx = 1248,
      includeYaml = false,
    )

    // URL goes through htmlEscape (& → &amp;) so we look for the escaped form.
    val escapedUrl = url.replace("&", "&amp;")
    assertContains(html, "src=\"$escapedUrl\"")
    // No base64 inlining for URL cells — Chromium fetches at headless capture time.
    assertFalse(html.contains("data:image/"), "URL-form cells must not be base64-inlined")
  }

  @Test
  fun `buildHtml renders 'AI' source-chip for aiGenerated cells and 'REC' for others`() {
    val sections = listOf(
      StoryboardHtmlBuilder.StoryboardSection(
        title = "Mixed sources",
        cells = listOf(
          makeCell(index = 1, aiGenerated = true),
          makeCell(index = 2, aiGenerated = false),
        ),
      ),
    )

    val html = StoryboardHtmlBuilder.buildHtml(
      sections = sections,
      columns = 4,
      cellWidthPx = 300,
      pageWidthPx = 1248,
      includeYaml = false,
    )

    assertContains(html, "source-chip ai\"")
    assertContains(html, ">AI<")
    assertContains(html, "source-chip rec\"")
    assertContains(html, ">REC<")
  }

  @Test
  fun `buildHtml replaces the label strip with YAML pre when cell has a yamlSnippet`() {
    val sections = listOf(
      StoryboardHtmlBuilder.StoryboardSection(
        title = "S",
        cells = listOf(
          makeCell(
            index = 1,
            label = "Tap",
            sublabel = "(1, 2)",
            yamlSnippet = "tapOn:\n  id: foo",
          ),
        ),
      ),
    )

    val html = StoryboardHtmlBuilder.buildHtml(
      sections = sections,
      columns = 4,
      cellWidthPx = 300,
      pageWidthPx = 1248,
      includeYaml = true,
    )

    assertContains(html, "<pre class=\"yaml\">")
    assertContains(html, "tapOn:")
    // The YAML strip replaces the synthesized verb/sublabel line entirely; the label
    // div MUST NOT be rendered for this cell. (Other cells without YAML can still
    // render it — see the next test.)
    assertFalse(
      html.contains("<div class=\"label\">"),
      "Cells with a YAML snippet should not also render the verb/sublabel label strip",
    )
  }

  @Test
  fun `buildHtml falls back to verb+sublabel label when yamlSnippet is null even with includeYaml=true`() {
    val sections = listOf(
      StoryboardHtmlBuilder.StoryboardSection(
        title = "S",
        cells = listOf(
          makeCell(index = 1, label = "Tap", sublabel = "(1, 2)", yamlSnippet = null),
        ),
      ),
    )

    val html = StoryboardHtmlBuilder.buildHtml(
      sections = sections,
      columns = 4,
      cellWidthPx = 300,
      pageWidthPx = 1248,
      includeYaml = true,
    )

    assertContains(html, "<div class=\"label\">")
    assertContains(html, ">Tap<")
    assertContains(html, "(1, 2)")
  }

  @Test
  fun `buildHtml HTML-escapes labels, sublabels, YAML, and section titles`() {
    val sections = listOf(
      StoryboardHtmlBuilder.StoryboardSection(
        title = "<title> & \"quoted\"",
        cells = listOf(
          makeCell(
            index = 1,
            label = "<bad>",
            sublabel = "a & b",
            yamlSnippet = "key: \"<script>alert('x')</script>\"",
          ),
        ),
      ),
    )

    val html = StoryboardHtmlBuilder.buildHtml(
      sections = sections,
      columns = 4,
      cellWidthPx = 300,
      pageWidthPx = 1248,
      includeYaml = true,
    )

    // The dangerous payloads should appear ONLY in escaped form. If any raw `<script>`
    // or unescaped tag leaks through, the test fails.
    assertFalse(html.contains("<script>"), "<script> tag must be escaped")
    assertContains(html, "&lt;script&gt;")
    assertContains(html, "&lt;title&gt;")
    assertContains(html, "&amp;")
    assertContains(html, "&lt;bad&gt;")
  }

  // ---------------------------------------------------------------------------
  // autoPickColumns — aspect-ratio-driven default column count
  // ---------------------------------------------------------------------------

  @Test
  fun `autoPickColumns returns 4 for an all-portrait phone session`() {
    // Pixel 7 / iPhone 14 portrait — clearly below the 1.2 landscape threshold.
    val cells = List(10) { i ->
      makeCell(index = i + 1, deviceWidth = 393, deviceHeight = 852)
    }
    assertEquals(4, StoryboardHtmlBuilder.autoPickColumns(cells))
  }

  @Test
  fun `autoPickColumns returns 3 for an all-landscape desktop session`() {
    // 1280x800 desktop window — aspect 1.6, well above the 1.2 threshold.
    val cells = List(10) { i ->
      makeCell(index = i + 1, deviceWidth = 1280, deviceHeight = 800)
    }
    assertEquals(3, StoryboardHtmlBuilder.autoPickColumns(cells))
  }

  @Test
  fun `autoPickColumns flips to 3 once landscape cells hit the majority threshold`() {
    // 6 landscape (60%) + 4 portrait (40%) at the 0.5 majority floor → flips to 3.
    val landscape = List(6) { i ->
      makeCell(index = i + 1, deviceWidth = 1280, deviceHeight = 800)
    }
    val portrait = List(4) { i ->
      makeCell(index = i + 7, deviceWidth = 393, deviceHeight = 852)
    }
    assertEquals(3, StoryboardHtmlBuilder.autoPickColumns(landscape + portrait))
  }

  @Test
  fun `autoPickColumns keeps 4 when landscape is below the majority threshold`() {
    // 4 landscape (40%) + 6 portrait (60%) → portrait wins.
    val landscape = List(4) { i ->
      makeCell(index = i + 1, deviceWidth = 1280, deviceHeight = 800)
    }
    val portrait = List(6) { i ->
      makeCell(index = i + 5, deviceWidth = 393, deviceHeight = 852)
    }
    assertEquals(4, StoryboardHtmlBuilder.autoPickColumns(landscape + portrait))
  }

  @Test
  fun `autoPickColumns treats near-square cells as portrait`() {
    // 1.1 aspect (1024×931) is below the 1.2 landscape threshold — falls into portrait.
    // Tablets in their landscape-but-square-ish modes shouldn't trigger the wide grid.
    val cells = List(10) { i ->
      makeCell(index = i + 1, deviceWidth = 1024, deviceHeight = 931)
    }
    assertEquals(4, StoryboardHtmlBuilder.autoPickColumns(cells))
  }

  @Test
  fun `autoPickColumns returns the portrait default for an empty cell list`() {
    // Empty input shouldn't crash — pick the portrait default. This isn't a real CLI
    // path (the exporter rejects 0-cell sessions upstream), but the helper is `internal`
    // and could be called from tests / other contexts with edge inputs.
    assertEquals(4, StoryboardHtmlBuilder.autoPickColumns(emptyList()))
  }

  @Test
  fun `autoPickColumns skips cells with zero height instead of dividing by zero`() {
    // Defensive guard — a malformed cell with deviceHeight=0 shouldn't crash the
    // storyboard pipeline. We treat it as "not landscape" and let the remaining cells
    // decide the vote.
    val malformed = makeCell(index = 1, deviceWidth = 1280, deviceHeight = 0)
    val portrait = List(3) { i ->
      makeCell(index = i + 2, deviceWidth = 393, deviceHeight = 852)
    }
    assertEquals(4, StoryboardHtmlBuilder.autoPickColumns(listOf(malformed) + portrait))
  }

  // ---------------------------------------------------------------------------
  // autoFitCellWidth — ladder shrinks until fit, floors at MIN_CELL_WIDTH_PX
  // ---------------------------------------------------------------------------

  @Test
  fun `autoFitCellWidth returns requested width when the page already fits`() {
    val cell = makeCell()
    val sections = listOf(StoryboardHtmlBuilder.StoryboardSection("S", listOf(cell)))

    val result = StoryboardHtmlBuilder.autoFitCellWidth(
      requested = 300,
      sections = sections,
      columns = 4,
      allCells = listOf(cell),
      includeYaml = false,
    )

    assertEquals(300, result, "Single-cell page fits at the requested width unchanged")
  }

  @Test
  fun `autoFitCellWidth shrinks the cell width when a many-section page would overrun the cap`() {
    // 60 sections × 1 cell each at 402x874 (iPhone-portrait) is the worst-case shape:
    // each section forces a fresh row, so vertical whitespace dominates. At 300px cells
    // with these dimensions the page comes out ~21000 logical px tall (well past the
    // 7800 cap); the auto-fit walk must shrink to fit.
    val cell = makeCell(deviceWidth = 402, deviceHeight = 874)
    val sections = List(60) { i ->
      StoryboardHtmlBuilder.StoryboardSection("S$i", listOf(cell.copy(index = i + 1)))
    }

    val result = StoryboardHtmlBuilder.autoFitCellWidth(
      requested = 300,
      sections = sections,
      columns = 4,
      allCells = sections.flatMap { it.cells },
      includeYaml = false,
    )

    assertTrue(result < 300, "Tall many-section pages should trigger the shrink")
    assertTrue(
      result >= StoryboardHtmlBuilder.MIN_CELL_WIDTH_PX,
      "Shrink must not undercut the readability floor",
    )
  }

  @Test
  fun `autoFitCellWidth returns MIN_CELL_WIDTH_PX when no width fits the cap`() {
    // 300 sections × 1 mobile-portrait cell each is impossible to fit under the 7800px
    // cap at any width — each section forces a new row no matter how narrow cells get.
    // The function should return MIN_CELL_WIDTH_PX as the floor without throwing; the
    // post-render PNG dimension check is what catches "still too big" cases.
    val cell = makeCell(deviceWidth = 402, deviceHeight = 874)
    val sections = List(300) { i ->
      StoryboardHtmlBuilder.StoryboardSection("S$i", listOf(cell.copy(index = i + 1)))
    }

    val result = StoryboardHtmlBuilder.autoFitCellWidth(
      requested = 300,
      sections = sections,
      columns = 4,
      allCells = sections.flatMap { it.cells },
      includeYaml = false,
    )

    assertEquals(
      StoryboardHtmlBuilder.MIN_CELL_WIDTH_PX,
      result,
      "Floor-hit case must return MIN_CELL_WIDTH_PX, not throw or return less",
    )
  }

  // ---------------------------------------------------------------------------
  // export — MAX_INLINED_HTML_BYTES preflight cap
  // ---------------------------------------------------------------------------

  @Test
  fun `checkInlinedHtmlSize rejects when estimated HTML exceeds MAX_INLINED_HTML_BYTES`() {
    // Estimate math (from estimateHtmlCapacity): 4096 + cells.size × (250KB × 4/3 + 512)
    // ≈ cells.size × 341845. To exceed 256MB = 268435456 bytes we need > 785 cells. We
    // use 800 cells to give clear headroom above the threshold. No Playwright/ffmpeg
    // involved — the check is extracted into its own internal helper precisely so it
    // can be exercised with bare cells.
    val cells = List(800) { i -> makeCell(index = i + 1) }
    val ex = runCatching { StoryboardHtmlBuilder.checkInlinedHtmlSize(cells) }.exceptionOrNull()
    assertTrue(ex is IllegalStateException, "Expected IllegalStateException, got $ex")
    assertTrue(
      ex.message!!.contains("256MB preflight cap"),
      "Error message should reference the 256MB cap so the user knows what to do: '${ex.message}'",
    )
  }

  @Test
  fun `checkInlinedHtmlSize accepts typical session sizes without throwing`() {
    val cells = List(50) { i -> makeCell(index = i + 1) }
    // No assertion needed — the call returning normally IS the pass condition.
    StoryboardHtmlBuilder.checkInlinedHtmlSize(cells)
  }

  @Test
  fun `checkInlinedHtmlSize accepts the empty cell list (no-op)`() {
    // Empty case has no cells to inline, so the check should short-circuit cleanly
    // rather than dividing by zero in the error-message construction.
    StoryboardHtmlBuilder.checkInlinedHtmlSize(emptyList())
  }

  // ---------------------------------------------------------------------------
  // buildSections — AI/REC detection via TrailblazeLlmRequestLog correlation
  // ---------------------------------------------------------------------------

  @Test
  fun `buildSections sets aiGenerated=true when an LlmRequestLog shares the traceId`() {
    val png = writeTinyPng("frame.png")
    val sharedTrace = "shared-llm-trace"
    val logs = listOf(
      makeAgentDriverLog(screenshot = "a.png", traceId = sharedTrace),
      makeLlmRequestLog(traceId = sharedTrace),
    )

    val cell = StoryboardHtmlBuilder.buildSections(
      logs = logs,
      resolveScreenshotFile = { png },
    ).single().cells.single()

    assertTrue(cell.aiGenerated, "Matching LlmRequestLog traceId must mark the cell as AI")
  }

  @Test
  fun `buildSections sets aiGenerated=false when no LlmRequestLog shares the traceId`() {
    val png = writeTinyPng("frame.png")
    val logs = listOf(
      makeAgentDriverLog(screenshot = "a.png", traceId = "driver-trace"),
      makeLlmRequestLog(traceId = "different-llm-trace"),
    )

    val cell = StoryboardHtmlBuilder.buildSections(
      logs = logs,
      resolveScreenshotFile = { png },
    ).single().cells.single()

    assertFalse(cell.aiGenerated, "Non-matching traceId must leave the cell as REC")
  }

  @Test
  fun `buildSections groups cells under the LATEST ObjectiveStartLog seen in chronological stream order`() {
    // Stream order is the contract — clock-skewed sessions where an ObjectiveStartLog
    // arrives in the log file AFTER the AgentDriverLog it should bracket will silently
    // mis-attribute the cell. Pin the current behavior so a future "be clever about
    // timestamps" change has to update this test and re-justify the change.
    val png = writeTinyPng("frame.png")
    val logs = listOf(
      makeObjectiveStart("First objective"),
      makeAgentDriverLog(screenshot = "cell1.png", traceId = "t1"),
      // Reverse-order: the AgentDriverLog appears BEFORE the next ObjectiveStartLog,
      // so it belongs to "First objective" — the next start has not yet opened a new
      // section in the stream walk.
      makeAgentDriverLog(screenshot = "cell2.png", traceId = "t2"),
      makeObjectiveStart("Second objective (arrives late)"),
      makeAgentDriverLog(screenshot = "cell3.png", traceId = "t3"),
    )

    val sections = StoryboardHtmlBuilder.buildSections(
      logs = logs,
      resolveScreenshotFile = { png },
    )

    assertEquals(2, sections.size)
    assertEquals("First objective", sections[0].title)
    assertEquals(2, sections[0].cells.size, "cells before the second ObjectiveStartLog stay in the first section")
    assertEquals("Second objective (arrives late)", sections[1].title)
    assertEquals(1, sections[1].cells.size)
  }

  @Test
  fun `buildSections sets aiGenerated=false for cells with null traceId (older logs)`() {
    val png = writeTinyPng("frame.png")
    val logs = listOf(
      makeAgentDriverLog(screenshot = "a.png", traceId = null),
      makeLlmRequestLog(traceId = "any-llm-trace"),
    )

    val cell = StoryboardHtmlBuilder.buildSections(
      logs = logs,
      resolveScreenshotFile = { png },
    ).single().cells.single()

    // Pre-traceId logs always fall into REC — see the StoryboardCell.aiGenerated kdoc
    // for the documented caveat. This test pins that behavior so the visual semantics
    // can't drift silently.
    assertFalse(cell.aiGenerated, "Null traceId must render as REC (documented behavior)")
  }

  // ---------------------------------------------------------------------------
  // buildSections — YAML truncation at YAML_SNIPPET_MAX_LINES
  // ---------------------------------------------------------------------------

  @Test
  fun `buildSections truncates yamlSnippet to YAML_SNIPPET_MAX_LINES`() {
    val png = writeTinyPng("frame.png")
    val sharedTrace = "yaml-trunc-trace"
    // Construct a tool whose YAML serializes to many lines. OtherTrailblazeTool uses
    // the toolName as the YAML root and `raw` as the body — a multi-key raw JsonObject
    // produces one YAML line per key. 30 keys → ~30 lines, well past the 20-line cap.
    val manyKeys = (1..30).associate { "key$it" to kotlinx.serialization.json.JsonPrimitive("v$it") }
    val toolLog = TrailblazeLog.TrailblazeToolLog(
      trailblazeTool = OtherTrailblazeTool(
        toolName = "bigTool",
        raw = JsonObject(manyKeys),
      ),
      toolName = "bigTool",
      successful = true,
      traceId = traceId(sharedTrace),
      durationMs = 0L,
      session = SessionId("test-session"),
      timestamp = Clock.System.now(),
    )
    val logs = listOf(
      makeAgentDriverLog(screenshot = "a.png", traceId = sharedTrace),
      toolLog,
    )

    val cell = StoryboardHtmlBuilder.buildSections(
      logs = logs,
      resolveScreenshotFile = { png },
      includeYaml = true,
    ).single().cells.single()

    val yaml = cell.yamlSnippet
    assertTrue(yaml != null, "YAML snippet should be populated")
    assertEquals(
      20,
      yaml.lines().size,
      "YAML must be clamped to exactly YAML_SNIPPET_MAX_LINES (20) lines",
    )
  }

  // ---------------------------------------------------------------------------
  // labelFor — sublabel derivation for each AgentDriverAction subtype
  // ---------------------------------------------------------------------------

  @Test
  fun `labelFor derives sublabel for EnterText including the 40-char truncation boundary`() {
    val png = writeTinyPng("frame.png")
    val shortText = "hello"
    val longText = "a".repeat(50) // > 40, will trigger truncateMid
    val logs = listOf(
      makeAgentDriverLogWithAction(
        screenshot = "short.png",
        action = AgentDriverAction.EnterText(text = shortText),
      ),
      makeAgentDriverLogWithAction(
        screenshot = "long.png",
        action = AgentDriverAction.EnterText(text = longText),
      ),
    )

    val cells = StoryboardHtmlBuilder.buildSections(
      logs = logs,
      resolveScreenshotFile = { png },
    ).single().cells

    assertEquals("Type", cells[0].label)
    assertEquals("\"hello\"", cells[0].sublabel, "Short text renders verbatim in quotes")
    assertTrue(
      cells[1].sublabel.contains("…"),
      "Long text must show the ellipsis from truncateMid (got '${cells[1].sublabel}')",
    )
  }

  @Test
  fun `labelFor derives sublabel for varied AgentDriverAction subtypes`() {
    val png = writeTinyPng("frame.png")
    val logs = listOf(
      makeAgentDriverLogWithAction("a.png", AgentDriverAction.Swipe("UP", 200L)),
      makeAgentDriverLogWithAction("b.png", AgentDriverAction.LaunchApp("com.example.app")),
      makeAgentDriverLogWithAction("c.png", AgentDriverAction.Scroll(forward = true)),
      makeAgentDriverLogWithAction(
        "d.png",
        AgentDriverAction.AssertCondition(
          conditionDescription = "Verify the button is visible",
          x = 0,
          y = 0,
          isVisible = true,
        ),
      ),
      makeAgentDriverLogWithAction("e.png", AgentDriverAction.AirplaneMode(enable = true)),
      makeAgentDriverLogWithAction("f.png", AgentDriverAction.LongPressPoint(x = 100, y = 200)),
    )

    val cells = StoryboardHtmlBuilder.buildSections(
      logs = logs,
      resolveScreenshotFile = { png },
    ).single().cells

    assertEquals("Swipe" to "UP", cells[0].label to cells[0].sublabel)
    assertEquals("Launch" to "com.example.app", cells[1].label to cells[1].sublabel)
    assertEquals("Scroll" to "forward", cells[2].label to cells[2].sublabel)
    assertEquals("Assert", cells[3].label)
    assertTrue(cells[3].sublabel.contains("Verify"))
    assertEquals("Airplane" to "on", cells[4].label to cells[4].sublabel)
    assertEquals("Long press" to "(100, 200)", cells[5].label to cells[5].sublabel)
  }

  // ---------------------------------------------------------------------------
  // buildHtml — plural step count
  // ---------------------------------------------------------------------------

  @Test
  fun `buildHtml renders both AI and REC chips when a section contains mixed-source cells`() {
    val sections = listOf(
      StoryboardHtmlBuilder.StoryboardSection(
        title = "Mixed source section",
        cells = listOf(
          makeCell(index = 1, aiGenerated = true),
          makeCell(index = 2, aiGenerated = false),
        ),
      ),
    )

    val html = StoryboardHtmlBuilder.buildHtml(
      sections = sections,
      columns = 4,
      cellWidthPx = 300,
      pageWidthPx = 1248,
      includeYaml = false,
    )

    // Both chip types must coexist — a regression that dropped one branch in appendCell
    // would only surface visually otherwise. Two cells in a single section produces
    // exactly one of each chip.
    assertTrue(
      html.contains("source-chip ai\""),
      "AI chip must be rendered for the aiGenerated=true cell",
    )
    assertTrue(
      html.contains("source-chip rec\""),
      "REC chip must be rendered for the aiGenerated=false cell",
    )
  }

  @Test
  fun `buildHtml renders singular '1 step' for sections containing exactly one cell`() {
    val sections = listOf(
      StoryboardHtmlBuilder.StoryboardSection(
        title = "Single-cell section",
        cells = listOf(makeCell(index = 1)),
      ),
    )

    val html = StoryboardHtmlBuilder.buildHtml(
      sections = sections,
      columns = 4,
      cellWidthPx = 300,
      pageWidthPx = 1248,
      includeYaml = false,
    )

    assertContains(html, "1 step</span>")
    assertFalse(
      html.contains("1 steps</span>"),
      "Singular form must be 'step' for exactly 1 cell — pluralization regression would slip past CI otherwise",
    )
  }

  @Test
  fun `buildHtml pluralizes section step count for N greater than 1`() {
    val cell = makeCell()
    val sections = listOf(
      StoryboardHtmlBuilder.StoryboardSection(
        title = "Multi-step section",
        cells = listOf(cell.copy(index = 1), cell.copy(index = 2), cell.copy(index = 3)),
      ),
    )

    val html = StoryboardHtmlBuilder.buildHtml(
      sections = sections,
      columns = 4,
      cellWidthPx = 300,
      pageWidthPx = 1248,
      includeYaml = false,
    )

    assertContains(html, "3 steps</span>")
    assertFalse(
      html.contains("3 step</span>"),
      "Plural form must be 'steps' not 'step' for N>1",
    )
  }

  // ---------------------------------------------------------------------------
  // Test fixtures
  // ---------------------------------------------------------------------------

  /** Minimal 1x1 PNG bytes (transparent). Smallest possible valid PNG for fixtures. */
  private val tinyPng = byteArrayOf(
    0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
    0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
    0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, 0xC4.toByte(), 0x89.toByte(),
    0x00, 0x00, 0x00, 0x0D, 0x49, 0x44, 0x41, 0x54,
    0x08, 0x99.toByte(), 0x63, 0xF8.toByte(), 0xCF.toByte(), 0xC0.toByte(), 0x00, 0x00, 0x00, 0x03, 0x00, 0x01,
    0x5B, 0xB7.toByte(), 0xBF.toByte(), 0xF2.toByte(),
    0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44, 0xAE.toByte(), 0x42, 0x60, 0x82.toByte(),
  )

  private fun writeTinyPng(name: String): File {
    val file = File(tmp.root, name)
    file.writeBytes(tinyPng)
    return file
  }

  /**
   * Map from string label → TraceId instance, populated on first use so the same string
   * always resolves to the same TraceId across multiple fixture builders. `TraceId`'s
   * primary constructor is internal to the models module (so tests can't call it
   * directly); we use `TraceId.generate(...)` as the only externally-available factory
   * and memoize so multiple references to "shared-trace" link up correctly.
   */
  private val traceIdsByLabel = mutableMapOf<String, TraceId>()
  private fun traceId(label: String): TraceId =
    traceIdsByLabel.getOrPut(label) { TraceId.generate(TraceId.Companion.TraceOrigin.LLM) }

  private fun makeAgentDriverLog(
    screenshot: String,
    traceId: String? = null,
    deviceWidth: Int = 402,
    deviceHeight: Int = 874,
  ): TrailblazeLog.AgentDriverLog = TrailblazeLog.AgentDriverLog(
    viewHierarchy = null,
    screenshotFile = screenshot,
    action = AgentDriverAction.TapPoint(x = 10, y = 20),
    durationMs = 0L,
    session = SessionId("test-session"),
    timestamp = Clock.System.now(),
    deviceHeight = deviceHeight,
    deviceWidth = deviceWidth,
    traceId = traceId?.let { traceId(it) },
  )

  /**
   * Variant for tests that need to vary the action (label/sublabel coverage). Same
   * device dimensions and session as [makeAgentDriverLog]; only the action differs.
   * traceId omitted because the label-derivation tests don't exercise AI/REC.
   */
  private fun makeAgentDriverLogWithAction(
    screenshot: String,
    action: AgentDriverAction,
  ): TrailblazeLog.AgentDriverLog = TrailblazeLog.AgentDriverLog(
    viewHierarchy = null,
    screenshotFile = screenshot,
    action = action,
    durationMs = 0L,
    session = SessionId("test-session"),
    timestamp = Clock.System.now(),
    deviceHeight = 874,
    deviceWidth = 402,
    traceId = null,
  )

  /**
   * Construct a minimal-but-valid [TrailblazeLog.TrailblazeLlmRequestLog] for AI/REC
   * tests. All non-defaulted fields get sensible no-op values — the only thing
   * production code cares about is the [TrailblazeLog.TrailblazeLlmRequestLog.traceId],
   * which the buildSections logic uses to compute the `llmTraceIds` set. The same
   * traceId label resolves to the same TraceId across fixtures via [traceId].
   */
  private fun makeLlmRequestLog(traceId: String): TrailblazeLog.TrailblazeLlmRequestLog =
    TrailblazeLog.TrailblazeLlmRequestLog(
      agentTaskStatus = AgentTaskStatus.InProgress(
        statusData = AgentTaskStatusData(
          taskId = TaskId.generate(),
          prompt = "test",
          callCount = 0,
          taskStartTime = Clock.System.now(),
          totalDurationMs = 0L,
        ),
      ),
      viewHierarchy = ViewHierarchyTreeNode(),
      instructions = "",
      trailblazeLlmModel = TrailblazeLlmModels.GPT_4O_MINI,
      llmMessages = emptyList(),
      llmResponse = emptyList(),
      actions = emptyList(),
      toolOptions = emptyList(),
      screenshotFile = null,
      durationMs = 0L,
      session = SessionId("test-session"),
      timestamp = Clock.System.now(),
      traceId = traceId(traceId),
      deviceHeight = 0,
      deviceWidth = 0,
    )

  private fun makeObjectiveStart(prompt: String): TrailblazeLog.ObjectiveStartLog =
    TrailblazeLog.ObjectiveStartLog(
      promptStep = DirectionStep(prompt),
      session = SessionId("test-session"),
      timestamp = Clock.System.now(),
    )

  private fun makeToolLog(toolName: String, traceId: String): TrailblazeLog.TrailblazeToolLog =
    TrailblazeLog.TrailblazeToolLog(
      trailblazeTool = OtherTrailblazeTool(
        toolName = toolName,
        raw = JsonObject(emptyMap()),
      ),
      toolName = toolName,
      successful = true,
      traceId = traceId(traceId),
      durationMs = 0L,
      session = SessionId("test-session"),
      timestamp = Clock.System.now(),
    )

  /**
   * Build a [StoryboardHtmlBuilder.StoryboardCell] backed by a 1x1 PNG fixture.
   * Used by [buildHtml] tests that just need a working data:URI source for the
   * embedded `<img>` — no need to draw anything meaningful.
   */
  private fun makeCell(
    index: Int = 1,
    label: String = "Tap",
    sublabel: String = "",
    yamlSnippet: String? = null,
    aiGenerated: Boolean = false,
    deviceWidth: Int = 402,
    deviceHeight: Int = 874,
  ): StoryboardHtmlBuilder.StoryboardCell {
    val png = if (!File(tmp.root, "fixture.png").exists()) writeTinyPng("fixture.png") else File(tmp.root, "fixture.png")
    return StoryboardHtmlBuilder.StoryboardCell(
      index = index,
      label = label,
      sublabel = sublabel,
      screenshot = png,
      deviceWidth = deviceWidth,
      deviceHeight = deviceHeight,
      yamlSnippet = yamlSnippet,
      aiGenerated = aiGenerated,
    )
  }
}
