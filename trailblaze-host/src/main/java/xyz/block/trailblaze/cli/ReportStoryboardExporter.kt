package xyz.block.trailblaze.cli

import com.google.gson.JsonObject
import com.microsoft.playwright.Page
import com.microsoft.playwright.options.LoadState
import java.io.File
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.runBlocking
import xyz.block.trailblaze.api.AgentDriverAction
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.HasScreenshot
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.playwright.PlaywrightBrowserManager
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.yaml.TrailblazeYaml

/**
 * Renders a session as a single "storyboard" image — a CSS grid of every step's
 * screenshot, captioned, encoded as a single-frame WebP for embedding in GitHub PR
 * comments alongside the animated `--gif` / `--webp` artifacts. Sibling to the
 * timeline-autoplay path (`ReportGifExporter` / `ReportWebpExporter` / `ReportVideoExporter`)
 * but doesn't share its frame-capture pipeline — the storyboard renders a purpose-built
 * HTML page and captures it as a single WebP via Chromium's CDP `Page.captureScreenshot`,
 * not the timeline-scrubber playback.
 *
 * **Two artifacts.** The HTML page is written alongside the WebP and is itself a
 * usable deliverable — a human can open `storyboard.html` directly and scroll. The WebP
 * is the embeddable form (GitHub renders WebP inline in comments at the same `<img>`
 * path as GIF). Both are derived from the same cell list so they show identical content.
 *
 * **Why CSS grid + headless Chromium over a manual canvas composite.** Letting Chromium
 * lay out the grid handles cell layout, label rendering, anti-aliased text, and
 * arbitrary aspect ratios via plain CSS — building the same in Java2D or BufferedImage
 * would mean re-implementing the browser's layout engine for no benefit. The only cost
 * is the same Chromium dependency the timeline-autoplay exporters already pay.
 *
 * **Why CDP over `page.screenshot({ type: "webp" })`.** Playwright Java 1.59's
 * `ScreenshotType` enum only has `PNG` and `JPEG`, so the high-level API can't emit
 * WebP directly. Instead we open a CDP session and call `Page.captureScreenshot` with
 * `format: "webp"` — the same libwebp encoder that ships inside Chromium, returning
 * base64 image bytes we decode and write straight to disk. This eliminates the
 * external ffmpeg-with-`libwebp` (or standalone `cwebp`) requirement that an earlier
 * PNG→WebP intermediate encode imposed — Linuxbrew's ffmpeg bottle ships without
 * libwebp, so the encoder-shopping fallback was a recurring source of "trailblaze
 * works on macOS but not on my Linux workstation" papercuts. The capture-and-encode
 * step is now zero-extra-deps as long as Playwright's Chromium is installed (which
 * the timeline exporters already need).
 *
 * **Layout invariants.** Default 6 columns × 300px cells → 1800px source width. WebP's
 * max dimension is 16383px, so the grid height enforces a hard cap on cell count
 * (~225 cells of mobile aspect at 6 across before we'd bust the limit). A guard
 * downgrades to fewer columns or splits into multiple files when a future trail breaks
 * that ceiling.
 */
object ReportStoryboardExporter {

  /** Fallback grid columns used by the portrait branch of [autoPickColumns] and by any
   *  caller that wants a fixed default without the aspect-ratio probe. Chosen empirically
   *  by inspecting rendered output: a 4-column layout of portrait mobile thumbs
   *  downscales to GitHub's ~760px content column at roughly 3.3× shrink (vs 5× at 6
   *  columns), so each cell stays meaningfully larger in the inline view while the
   *  click-through full-res remains identical. Bumping to 6/8/10 still makes sense for
   *  very long sessions — the per-cell auto-fit shrink will preserve the grouping with
   *  smaller cells if needed, but spreading wider on the page is the cleaner lever. */
  const val DEFAULT_COLUMNS: Int = 4

  /** Cells with `deviceWidth / deviceHeight ≥` this threshold are counted as landscape
   *  by [autoPickColumns]. 1.2 lets near-square layouts (deskop browser windows resized
   *  to ~1.0–1.1 aspect, square tablet sketches) fall into the portrait branch — those
   *  benefit more from the denser 4-column grid than from the wide 2-column layout. */
  private const val LANDSCAPE_ASPECT_THRESHOLD: Double = 1.2

  /** Fraction of cells that must be landscape (by [LANDSCAPE_ASPECT_THRESHOLD]) before
   *  [autoPickColumns] flips to the wide 2-column layout. 0.5 = simple majority; raise
   *  for stricter behavior (e.g. 0.8 = "only flip when nearly all cells are landscape"). */
  private const val LANDSCAPE_MAJORITY_THRESHOLD: Double = 0.5

  /** Column count [autoPickColumns] returns when the cell mix is landscape-majority. */
  private const val AUTO_COLUMNS_LANDSCAPE: Int = 3

  /** Column count [autoPickColumns] returns when the cell mix is portrait-majority. */
  private const val AUTO_COLUMNS_PORTRAIT: Int = DEFAULT_COLUMNS

  /** Default per-cell width at source resolution. Mobile portrait (9:20) → ~666px tall
   *  cell, desktop 16:9 → ~169px tall cell. */
  const val DEFAULT_CELL_WIDTH_PX: Int = 300

  /** Outer padding around the grid in the rendered HTML. */
  private const val PAGE_PADDING_PX: Int = 24

  /** Logical-CSS-px ceiling used by the pre-render guard and the auto-fit search.
   *
   *  This is a *logical* (CSS) dimension, but libwebp's 16383 limit applies to the
   *  *device* dimension that ends up in the encoded PNG — and `PlaywrightBrowserManager`
   *  defaults to `deviceScaleFactor = 2.0` outside CI, so device px ≈ 2× CSS px. We cap
   *  the logical estimate at 7800 to leave headroom under the 16383 device-px ceiling
   *  even after DPR scaling, plus small slack for the per-row estimate inaccuracy
   *  (label-wrap, YAML-line-wrap). The post-render dimension check catches any case
   *  where this estimate is still off.
   *
   *  If you're tempted to raise this: remember it'd need to drop again the moment we
   *  default to DPR=3 (Apple's retina-3x configuration) or someone passes a custom
   *  Playwright preset that bumps DPR. The safer move is to query the actual DPR from
   *  `PlaywrightBrowserManager` and divide; future cleanup. */
  internal const val MAX_WEBP_DIMENSION_PX: Int = 7800

  /** Preflight cap on the in-memory HTML string the builder produces. Crosses-checked
   *  against [estimateHtmlCapacity] before assembly so a pathologically large session
   *  fails with an actionable message instead of an `OutOfMemoryError`. 256MB leaves
   *  comfortable room under typical JVM heap defaults while accepting any session up to
   *  ~600 cells at typical screenshot sizes. */
  internal const val MAX_INLINED_HTML_BYTES: Long = 256L * 1024L * 1024L

  /** Absolute libwebp encoder limit — checked against the projected device-pixel
   *  dimensions of the CDP screenshot (CSS px × scale × DPR) before we call CDP.
   *  Tripping this is a "the Kotlin estimate was wrong" signal; we error before
   *  invoking the encoder so the user sees an actionable "split this session"
   *  message instead of an opaque CDP error from libwebp inside Chromium. */
  private const val LIBWEBP_HARD_DIMENSION_MAX: Int = 16383

  /** Approximate pixel cost of the per-cell label strip (action verb + sublabel). Used
   *  by the page-height guard; the actual rendered height varies a few pixels with text
   *  wrap but this is the planning bound. Errs intentionally high — better to refuse a
   *  borderline session at the Kotlin guard than to discover the overrun at ffmpeg time. */
  private const val LABEL_STRIP_HEIGHT_PX: Int = 48

  // (YAML height is computed per-row from actual YAML line counts — see
  // YAML_LINE_HEIGHT_PX / YAML_BLOCK_PADDING_PX below. No fixed per-cell budget.)

  /** Approximate pixel cost of the per-section header (title strip + bottom margin
   *  before the grid). */
  private const val SECTION_HEADER_HEIGHT_PX: Int = 72

  /**
   * One cell in the rendered storyboard — derived from a single [HasScreenshot] log
   * entry. [index] is 1-based and reads directly from the rendered "Step N" badge.
   * [screenshot] is the on-disk PNG/JPG/WebP file resolved via [LogsRepo.getScreenshotFile].
   * [yamlSnippet] is the YAML form of the executed tool (when `includeYaml=true` and a
   * sibling [TrailblazeLog.TrailblazeToolLog] was found by traceId); null otherwise.
   *
   * **Label vs YAML rendering.** Since the default-on YAML behavior was introduced,
   * `label`/`sublabel` are *fallback-only*: rendered as the cell footer ONLY when
   * `yamlSnippet == null` (no sibling tool log to derive YAML from). When `yamlSnippet`
   * is populated, the YAML strip replaces the verb/sublabel line entirely — the YAML
   * is strictly more informative ("what tool was invoked with what args" beats "verb
   * + truncated detail"). The two fields are kept on every cell so the renderer can
   * always fall back without re-resolving the source log.
   */
  internal data class StoryboardCell(
    val index: Int,
    val label: String,
    val sublabel: String,
    /** On-disk screenshot for local-logs sessions. Null when the source log's
     *  `screenshotFile` is a remote URL (e.g. test-farm signed S3/CDN URLs), in which
     *  case [screenshotUrl] is populated instead and the renderer uses that as
     *  `<img src>` directly — same pattern WasmReport uses, lets Chromium fetch the
     *  image at headless-capture time without an extra download/base64 round-trip.
     *  Exactly one of [screenshot] / [screenshotUrl] is non-null. */
    val screenshot: File?,
    /** Remote URL form of the cell's screenshot — populated when the source log's
     *  `screenshotFile` starts with `http://` or `https://`. See [screenshot] for the
     *  local-file counterpart. */
    val screenshotUrl: String? = null,
    val deviceWidth: Int,
    val deviceHeight: Int,
    val yamlSnippet: String? = null,
    /** True when this cell's source log shares a traceId with a
     *  [TrailblazeLog.TrailblazeLlmRequestLog] — i.e. an LLM made the decision that
     *  produced this action. Rendered as a small "AI" chip in the cell's upper-right
     *  corner so reviewers can tell at a glance which steps were LLM-driven (and which
     *  were dispatched from a deterministic recorded trail). Surfaces patterns like
     *  "the LLM looped on verification 16 times" that the screenshot stream alone
     *  doesn't make visible.
     *
     *  **Caveat on older logs.** Cells whose source log has `traceId == null` (a state
     *  possible on `AgentDriverLog` for backward compatibility with logs predating the
     *  trace-id field) always fall into the `false` branch and render the red `REC`
     *  chip — even if the action was originally LLM-driven but the traceId wasn't
     *  persisted. The AI/REC dichotomy is therefore most reliable on sessions recorded
     *  after the trace-id field was added; re-record an older session if accurate
     *  source attribution matters for review. */
    val aiGenerated: Boolean = false,
  )

  /**
   * A group of cells that share a natural-language objective (e.g. the prompt text on a
   * [TrailblazeLog.ObjectiveStartLog]). Sections render with a header strip above their
   * grid so the storyboard reads as "agent did N things, here's the breakdown of each"
   * rather than as one undifferentiated stream of 30+ thumbnails.
   *
   * Screenshots emitted before any objective starts (or in sessions that never emit
   * `ObjectiveStartLog` at all — older logs, ad-hoc captures) land in a fallback section
   * titled by [DEFAULT_SECTION_TITLE] or the surrounding `McpAgentRunLog.objective`.
   */
  internal data class StoryboardSection(
    val title: String,
    val cells: List<StoryboardCell>,
  )

  /** Fallback title for cells that fall outside any `ObjectiveStartLog` window. */
  internal const val DEFAULT_SECTION_TITLE: String = "Session steps"

  /**
   * Generate `storyboard.html` and `storyboard.webp` from session [sessionId].
   *
   * @param outputWebp Destination for the encoded single-frame WebP.
   * @param outputHtml Optional companion HTML — when non-null, the same page rendered by
   *   Playwright is also written here for standalone viewing. When null, the HTML is
   *   built into a tmp file and discarded after the WebP is encoded.
   * @param maxBytes When non-null, iteratively re-encode at smaller widths until the
   *   WebP fits the cap. Same loop as the timeline exporters use.
   * @return [outputWebp] for convenience (mirrors the caller pattern in `ReportCommand`).
   */
  /**
   * @param includeYaml when true, each cell's YAML strip replaces the synthesized
   *   verb/sublabel line. Library default is `false` (callers must opt in explicitly);
   *   the CLI's `--storyboard-yaml` flag defaults to `true` for end-user ergonomics —
   *   the asymmetry is intentional so direct callers of this function don't pay the
   *   per-row YAML-rendering cost unless they ask for it.
   */
  fun export(
    sessionId: SessionId,
    logsRepo: LogsRepo,
    outputWebp: File,
    outputHtml: File? = null,
    columns: Int? = null,
    cellWidthPx: Int = DEFAULT_CELL_WIDTH_PX,
    includeYaml: Boolean = false,
    headless: Boolean = true,
    maxBytes: Long? = null,
  ): File {
    // 1..12: above 12 columns each thumbnail collapses below the click-through
    // readability floor at GitHub's embed width; below 1 makes no grid. (Null falls
    // through to [autoPickColumns] below, which only returns values in this range.)
    if (columns != null) {
      require(columns in 1..12) { "columns must be in 1..12, got $columns" }
    }
    // 80..1200: 80px is the click-through readability floor for a mobile screenshot
    // (any smaller and UI text becomes illegible even at full-res); 1200px caps source
    // width × max columns below libwebp's 16383px dimension limit with headroom.
    require(cellWidthPx in 80..1200) { "cellWidthPx must be in 80..1200, got $cellWidthPx" }

    val logs = logsRepo.getLogsForSession(sessionId)
    val sections = buildSections(
      logs = logs,
      resolveScreenshotFile = { logsRepo.getScreenshotFile(it) },
      includeYaml = includeYaml,
    )
    val totalCells = sections.sumOf { it.cells.size }
    check(totalCells > 0) {
      "No screenshot-bearing logs found in session ${sessionId.value} — nothing to put in a " +
        "storyboard. Sessions with only LLM-thinking events but no executed actions or " +
        "snapshots will hit this; record at least one tap/swipe/snapshot to produce a storyboard."
    }

    val allCells = sections.flatMap { it.cells }
    // Pick a column count when the caller didn't pin one. Landscape sessions (desktop /
    // tablet) get a narrower grid so each cell is meaningfully larger; portrait sessions
    // (phone) keep the default 4-column density.
    val effectiveColumns = columns ?: autoPickColumns(allCells).also { picked ->
      val landscapeCount = countLandscape(allCells)
      Console.log(
        "[ReportStoryboardExporter] auto-picked $picked columns " +
          "($landscapeCount/${allCells.size} cells landscape" +
          " @ ≥${"%.2f".format(LANDSCAPE_ASPECT_THRESHOLD)} aspect — " +
          "majority threshold ${"%.0f".format(LANDSCAPE_MAJORITY_THRESHOLD * 100)}%). " +
          "Override with --storyboard-columns.",
      )
    }

    // Auto-fit cell width to the WebP dimension cap. Section grouping forces each
    // group to start a fresh row even when the prior section's last row is partially
    // empty, so a session with many short objectives accumulates a lot of vertical
    // whitespace. Rather than punt that as a usage error, shrink the requested
    // cellWidthPx in 20px steps until the estimated page height fits — preserves the
    // grouping (which is the whole point of sections) by trading per-cell pixel density
    // instead. The shrunk width is logged so the user knows it happened.
    val effectiveCellWidthPx = autoFitCellWidth(
      requested = cellWidthPx,
      sections = sections,
      columns = effectiveColumns,
      allCells = allCells,
      includeYaml = includeYaml,
    )
    if (effectiveCellWidthPx < cellWidthPx) {
      Console.log(
        "[ReportStoryboardExporter] shrinking cell width ${cellWidthPx}px → ${effectiveCellWidthPx}px " +
          "to fit ${sections.size} section(s) × ${totalCells} cells" +
          (if (includeYaml) " (+YAML)" else "") +
          " under the ${MAX_WEBP_DIMENSION_PX}px page-height cap. Pass " +
          "--storyboard-columns higher (e.g. 8 or 10) to keep cells at the original size.",
      )
    }
    val cellHeightPx = estimateCellHeightPx(allCells, effectiveCellWidthPx)
    val approxPageHeightPx = (PAGE_PADDING_PX * 2) + estimateSectionRowsPx(
      sections = sections,
      cellHeightPx = cellHeightPx,
      columns = effectiveColumns,
      includeYaml = includeYaml,
    )
    check(approxPageHeightPx <= MAX_WEBP_DIMENSION_PX) {
      "Storyboard would still render at ~${approxPageHeightPx}px tall after auto-shrinking " +
        "cells to ${effectiveCellWidthPx}px (${totalCells} cells across ${sections.size} " +
        "section(s), $effectiveColumns cols" +
        (if (includeYaml) ", YAML on" else "") +
        ") — exceeds libwebp's 16383px dimension ceiling. Re-run with more columns " +
        "(e.g. --storyboard-columns 10), drop --storyboard-yaml, or split the session " +
        "into smaller recordings."
    }

    outputWebp.parentFile?.mkdirs()
    outputHtml?.parentFile?.mkdirs()

    val pageWidthPx = (PAGE_PADDING_PX * 2) +
      effectiveColumns * effectiveCellWidthPx +
      (effectiveColumns - 1) * 8 /* gap */
    // Memory preflight: every screenshot is base64-inlined into the single HTML string
    // before Playwright loads it. At 500KB PNGs × 200 cells that's ~135MB of base64 in
    // one StringBuilder — fine on a developer workstation, potentially OOM-able on a
    // small CI container. Extracted into [checkInlinedHtmlSize] so tests can pin the
    // cap without spinning up a real LogsRepo + Playwright stack.
    checkInlinedHtmlSize(allCells)
    val html = buildHtml(
      sections = sections,
      columns = effectiveColumns,
      cellWidthPx = effectiveCellWidthPx,
      pageWidthPx = pageWidthPx,
      includeYaml = includeYaml,
    )

    // Always materialize an HTML on disk — Playwright loads it via file://. When the
    // caller asked for a sibling HTML artifact, write it there; otherwise drop a tmp
    // copy we clean up at the end.
    val htmlFile = outputHtml ?: File(
      System.getProperty("java.io.tmpdir"),
      "trailblaze-storyboard-${UUID.randomUUID().toString().take(8)}.html",
    )
    val ownsTmpHtml = outputHtml == null
    htmlFile.writeText(html)
    if (outputHtml != null) {
      Console.info("Storyboard HTML: file://${outputHtml.absolutePath}")
    }

    try {
      withRenderedStoryboardPage(
        htmlFile = htmlFile,
        viewportWidthPx = pageWidthPx,
        headless = headless,
        includeYaml = includeYaml,
        totalCells = totalCells,
        numSections = sections.size,
        columns = effectiveColumns,
      ) { captureWebp ->
        // null target = capture at the page's native device-pixel resolution (DPR-baked).
        captureWebp(outputWebp, null)
        Console.log(
          "[ReportStoryboardExporter] wrote ${outputWebp.absolutePath} " +
            "(${outputWebp.length() / 1024}KB, $totalCells cells, ${sections.size} section(s), " +
            "$effectiveColumns cols" + (if (includeYaml) ", YAML on" else "") + ")",
        )

        if (maxBytes != null) {
          // Iterative scale-down reused from the timeline path. Each iteration is just
          // another CDP `Page.captureScreenshot` call against the already-loaded grid —
          // no Playwright re-shoot of the HTML, no PNG intermediate to re-encode.
          //
          // `MaxArtifactSize.SCALE_WIDTHS` (1280/1024/720/640/480) is a *device-pixel*
          // ladder — that's how the prior PNG→ffmpeg path interpreted it (the source PNG
          // was already device-px after Playwright applied DPR). We hand `w` to the
          // CDP capture as the target device-pixel width directly so the ladder
          // semantics carry over. (Earlier revisions of this code divided `w` by the
          // viewport's CSS width, which silently re-multiplied by DPR inside the
          // capture lambda and upscaled the first iteration past the native capture.
          // Code review caught this — see the earlier comment for the reasoning.)
          val rescaleStartMs = System.currentTimeMillis()
          val result = MaxArtifactSize.enforce(outputWebp, maxBytes) { w ->
            Console.log(
              "[ReportStoryboardExporter] over ${maxBytes}B — re-encoding at ${w}px device-px width",
            )
            captureWebp(outputWebp, w)
            Console.log(
              "[ReportStoryboardExporter] after ${w}px: ${outputWebp.length() / 1024}KB " +
                "(cap: ${maxBytes / 1024}KB)",
            )
          }
          val rescaleElapsedMs = System.currentTimeMillis() - rescaleStartMs
          if (!result.fits) {
            error(
              "Storyboard WebP still exceeds ${maxBytes}B at the " +
                "${MaxArtifactSize.READABILITY_FLOOR_PX}px readability floor " +
                "(current size: ${outputWebp.length()}B). The storyboard's size scales with " +
                "step count — try `--storyboard-columns 8` to trailmap more cells per row (smaller " +
                "individual thumbs), or split the session into multiple shorter recordings.",
            )
          }
          if (result.widthPx != null) {
            Console.log(
              "[ReportStoryboardExporter] final size ${outputWebp.length() / 1024}KB at " +
                "${result.widthPx}px (rescale took ${rescaleElapsedMs}ms)",
            )
          }
        }
      }
    } finally {
      if (ownsTmpHtml) runCatching { htmlFile.delete() }
    }
    return outputWebp
  }

  /**
   * Walk [logs] in chronological order and bucket each screenshot-bearing cell into a
   * [StoryboardSection] anchored by the surrounding [TrailblazeLog.ObjectiveStartLog]
   * (or by [DEFAULT_SECTION_TITLE] for cells outside any objective window — pre-first-
   * objective screenshots, or sessions that never emit `ObjectiveStartLog` at all).
   *
   * **Cell inclusion rules.** Keep:
   *  - [TrailblazeLog.AgentDriverLog] — one per executed action (tap, swipe, type, etc.).
   *    Represents "what happened to the UI".
   *  - [TrailblazeLog.TrailblazeSnapshotLog] — explicit `takeSnapshot` calls. Represents
   *    "what the user marked as worth pinning".
   *
   * Skip:
   *  - [TrailblazeLog.TrailblazeLlmRequestLog] — would produce 2–3× the cell count for
   *    the same UI changes (one LLM screenshot before, one driver-log screenshot after
   *    each action). The post-action screenshot is the more interesting one for glance.
   *  - `McpSamplingLog` — explicitly stores `screenshotFile = null` (see its kdoc) so
   *    it'd be dropped anyway by the screenshot-file-exists filter below.
   *  - Anything else not implementing [HasScreenshot] or whose screenshot file has gone
   *    missing on disk (e.g. a logs-dir cleanup mid-run).
   *
   * **Objective grouping.** `ObjectiveStartLog` and `ObjectiveCompleteLog` don't carry
   * a `traceId` (their only fields are `promptStep` + `session` + `timestamp`), so the
   * only reliable signal for "which cells belong to this objective" is timestamp order.
   * We treat the start log as a section-open marker and the next start log (or end of
   * stream) as the close — `ObjectiveCompleteLog` is informational, not load-bearing.
   *
   * **YAML attachment.** When [includeYaml] is true, each cell looks up its sibling
   * [TrailblazeLog.TrailblazeToolLog] by `traceId` and stamps the tool's YAML form onto
   * [StoryboardCell.yamlSnippet] for rendering as a clamped `<pre>` strip. Cells with
   * a null `traceId` or no matching tool log get `yamlSnippet = null` and render without
   * the YAML strip — graceful degradation rather than a hard failure.
   *
   * Internal so tests can pin the grouping + YAML logic without spinning up Playwright.
   */
  internal fun buildSections(
    logs: List<TrailblazeLog>,
    resolveScreenshotFile: (TrailblazeLog) -> File?,
    includeYaml: Boolean = false,
  ): List<StoryboardSection> {
    // Build a traceId → TrailblazeToolLog index for YAML lookup. We map to the LAST
    // matching tool log for a given traceId — when an LLM action expands into multiple
    // executor sub-tools, the recordable top-level one is typically emitted last. Cheap
    // (O(n)) and only built when YAML is requested.
    val toolByTraceId: Map<String, TrailblazeLog.TrailblazeToolLog> = if (includeYaml) {
      logs.filterIsInstance<TrailblazeLog.TrailblazeToolLog>()
        .filter { it.traceId != null }
        .associateBy { it.traceId!!.traceId }
    } else {
      emptyMap()
    }
    // Set of traceIds for which an LLM made the decision. Built unconditionally — the
    // "AI" chip is always on (it's a tiny corner overlay, no opt-out needed) and the
    // detection cost is just `O(n)` over the chronological log stream we already walk.
    // `TrailblazeLlmRequestLog.traceId` is non-nullable (unlike AgentDriverLog/ToolLog),
    // so no nullable handling here.
    val llmTraceIds: Set<String> = logs.filterIsInstance<TrailblazeLog.TrailblazeLlmRequestLog>()
      .mapTo(mutableSetOf()) { it.traceId.traceId }

    val sections = mutableListOf<StoryboardSection>()
    var currentTitle = DEFAULT_SECTION_TITLE
    var currentCells = mutableListOf<StoryboardCell>()
    var nextIndex = 1

    fun flush() {
      if (currentCells.isNotEmpty()) {
        sections += StoryboardSection(currentTitle, currentCells.toList())
        currentCells = mutableListOf()
      }
    }

    for (log in logs) {
      when {
        log is TrailblazeLog.ObjectiveStartLog -> {
          // Section boundary — emit accumulated cells under the prior title (if any),
          // then switch to the new objective. Empty prior section is dropped by `flush`.
          flush()
          currentTitle = log.promptStep.prompt.takeIf { it.isNotBlank() } ?: DEFAULT_SECTION_TITLE
        }
        else -> {
          val (label, sublabel) = labelFor(log) ?: continue
          val withScreenshot = log as? HasScreenshot ?: continue
          // When `screenshotFile` is a remote URL — the shape some on-device test
          // farms use, where device screenshots upload to S3/CDN and only the signed URL
          // lands in the log — skip the local-file resolver entirely. The renderer will
          // emit the URL as `<img src>` directly and Chromium fetches it during headless
          // capture, same path WasmReport already uses for the timeline.
          val rawScreenshot = withScreenshot.screenshotFile
          val (screenshotFile, screenshotUrl) = if (rawScreenshot != null && isHttpUrl(rawScreenshot)) {
            null to rawScreenshot
          } else {
            val resolved = resolveScreenshotFile(log) ?: continue
            resolved to null
          }
          val yaml = if (includeYaml) yamlSnippetFor(log, toolByTraceId) else null
          val cellTraceId = (log as? xyz.block.trailblaze.logs.model.HasTraceId)?.traceId?.traceId
          val aiGenerated = cellTraceId != null && cellTraceId in llmTraceIds
          currentCells += StoryboardCell(
            index = nextIndex++,
            label = label,
            sublabel = sublabel,
            screenshot = screenshotFile,
            screenshotUrl = screenshotUrl,
            deviceWidth = withScreenshot.deviceWidth,
            deviceHeight = withScreenshot.deviceHeight,
            yamlSnippet = yaml,
            aiGenerated = aiGenerated,
          )
        }
      }
    }
    flush()
    return sections
  }

  /**
   * Look up the YAML form of the tool that produced [log]. Joins on `traceId` against
   * the prebuilt [toolByTraceId] index — same correlation key the timeline report uses
   * to link an [TrailblazeLog.AgentDriverLog] back to its driving
   * [TrailblazeLog.TrailblazeToolLog]. Truncates to [YAML_SNIPPET_MAX_LINES] lines so
   * the rendered cell stays at a consistent height (CSS also clamps as a safety net).
   *
   * Returns null when there's no traceId on the source log, no tool log carries that
   * traceId, or when YAML serialization fails (encoder doesn't know the tool type, for
   * instance) — the cell renders without a YAML strip rather than failing the whole
   * export.
   */
  private fun yamlSnippetFor(
    log: TrailblazeLog,
    toolByTraceId: Map<String, TrailblazeLog.TrailblazeToolLog>,
  ): String? {
    val traceId = (log as? xyz.block.trailblaze.logs.model.HasTraceId)?.traceId?.traceId ?: return null
    val toolLog = toolByTraceId[traceId] ?: return null
    val yaml = runCatching {
      TrailblazeYaml.toolToYaml(toolLog.toolName, toolLog.trailblazeTool)
    }.getOrNull() ?: return null
    return yaml.lineSequence().take(YAML_SNIPPET_MAX_LINES).joinToString("\n")
  }

  /** Hard cap on YAML lines per cell — defends against pathological tools whose YAML
   *  serialization runs to hundreds of lines (a multi-line enterText with a 5KB string,
   *  for instance) which would make the row hosting that cell dominate the entire grid.
   *  20 lines fits every Trailblaze tool we ship today with substantial headroom for new
   *  ones; the CSS row-stretch ensures shorter YAMLs in the same row don't pay for it. */
  private const val YAML_SNIPPET_MAX_LINES: Int = 20

  /**
   * Derive `(label, sublabel)` from a log. `label` is the short action verb
   * ("Tap", "Type", "Swipe", "Snapshot"); `sublabel` is the optional detail
   * ("(540, 1200)" for a tap, `"hello"` for a type, `"login_screen"` for a snapshot).
   *
   * Returns null for log types that don't belong in a storyboard — see [buildCells]
   * for the inclusion rules.
   */
  private fun labelFor(log: TrailblazeLog): Pair<String, String>? = when (log) {
    is TrailblazeLog.AgentDriverLog -> {
      val action = log.action
      val verb = action.type.displayLabel
      val detail = when (action) {
        is AgentDriverAction.TapPoint -> "(${action.x}, ${action.y})"
        is AgentDriverAction.LongPressPoint -> "(${action.x}, ${action.y})"
        is AgentDriverAction.EnterText -> "\"${action.text.truncateMid(40)}\""
        is AgentDriverAction.AssertCondition -> action.conditionDescription.truncateMid(60)
        is AgentDriverAction.LaunchApp -> action.appId
        is AgentDriverAction.StopApp -> action.appId
        is AgentDriverAction.KillApp -> action.appId
        is AgentDriverAction.Swipe -> action.direction
        is AgentDriverAction.GrantPermissions -> action.appId
        is AgentDriverAction.ClearAppState -> action.appId
        is AgentDriverAction.AirplaneMode -> if (action.enable) "on" else "off"
        is AgentDriverAction.Scroll -> if (action.forward) "forward" else "back"
        is AgentDriverAction.AddMedia -> "${action.mediaFiles.size} file(s)"
        is AgentDriverAction.EraseText -> "${action.characters} chars"
        is AgentDriverAction.WaitForSettle -> "${action.timeoutMs}ms"
        else -> {
          // A new AgentDriverAction subtype was added without updating this when-block.
          // Silently returning "" would leave the cell labeled with just the verb,
          // making the regression invisible. Log a breadcrumb so author-side smoke
          // testing catches it; the verb itself is still rendered so the storyboard
          // isn't broken in production.
          Console.log(
            "[ReportStoryboardExporter] no sublabel handling for AgentDriverAction subtype: " +
              "${action::class.simpleName} — add a case in labelFor()",
          )
          ""
        }
      }
      verb to detail
    }
    is TrailblazeLog.TrailblazeSnapshotLog -> {
      "Snapshot" to (log.displayName ?: "")
    }
    else -> null
  }

  /**
   * Pick a column count by majority cell aspect ratio. Landscape sessions (desktop
   * web, tablet apps) get the narrower [AUTO_COLUMNS_LANDSCAPE] grid so each cell
   * stays meaningfully large at GitHub's ~760px embed width; portrait sessions
   * (phone) keep the denser [AUTO_COLUMNS_PORTRAIT] layout. The thresholds —
   * [LANDSCAPE_ASPECT_THRESHOLD] for what counts as landscape, and
   * [LANDSCAPE_MAJORITY_THRESHOLD] for the per-session vote — are constants near the
   * top of this file, deliberately surfaced for easy tweaking.
   *
   * **Mixed-aspect sessions** (a desktop-Compose trail that briefly opens a mobile
   * emulator preview, or vice versa) resolve by simple majority. If that proves
   * surprising for a real session, raise [LANDSCAPE_MAJORITY_THRESHOLD] toward 0.8 to
   * require near-uniform landscape before flipping. The caller logs the breakdown so
   * the choice is visible from a CLI tail.
   *
   * Internal so unit tests can pin the threshold behavior without going through the
   * full session-rendering pipeline.
   */
  internal fun autoPickColumns(cells: List<StoryboardCell>): Int {
    if (cells.isEmpty()) return AUTO_COLUMNS_PORTRAIT
    val landscapeFraction = countLandscape(cells).toDouble() / cells.size
    return if (landscapeFraction >= LANDSCAPE_MAJORITY_THRESHOLD) {
      AUTO_COLUMNS_LANDSCAPE
    } else {
      AUTO_COLUMNS_PORTRAIT
    }
  }

  /** Count cells whose device aspect ratio is at or above [LANDSCAPE_ASPECT_THRESHOLD].
   *  Cells with `deviceHeight == 0` are skipped — that shouldn't happen in practice
   *  (a 0-height screenshot would have been rejected upstream) but the divide-by-zero
   *  guard keeps malformed inputs from crashing the storyboard pipeline. */
  private fun countLandscape(cells: List<StoryboardCell>): Int =
    cells.count {
      it.deviceHeight > 0 &&
        it.deviceWidth.toDouble() / it.deviceHeight >= LANDSCAPE_ASPECT_THRESHOLD
    }

  /**
   * Find the largest cell width ≤ [requested] for which the estimated rendered page
   * height fits under [MAX_WEBP_DIMENSION_PX]. Walks down in 20px steps from the
   * requested width to [MIN_CELL_WIDTH_PX]. Returns the requested width unchanged when
   * it already fits.
   *
   * Why auto-fit rather than just error: NL objective grouping intentionally forces
   * each section to start a fresh row, so any session with many short objectives
   * (think 15+ taps each in their own one-cell section) bloats vertical whitespace
   * even before YAML strips. Shrinking cells preserves the grouping (which is the
   * whole point) at the cost of pixel density — strictly better than refusing to
   * render. The caller logs that auto-shrink ran so the user can override with more
   * columns if they want larger cells.
   *
   * If even the [MIN_CELL_WIDTH_PX] floor doesn't fit, returns that floor; the caller's
   * post-render dimension check will then surface a clean error.
   */
  internal fun autoFitCellWidth(
    requested: Int,
    sections: List<StoryboardSection>,
    columns: Int,
    allCells: List<StoryboardCell>,
    includeYaml: Boolean,
  ): Int {
    var candidate = requested
    while (candidate >= MIN_CELL_WIDTH_PX) {
      val cellHeightPx = estimateCellHeightPx(allCells, candidate)
      val pageHeightPx = (PAGE_PADDING_PX * 2) + estimateSectionRowsPx(
        sections = sections,
        cellHeightPx = cellHeightPx,
        columns = columns,
        includeYaml = includeYaml,
      )
      if (pageHeightPx <= MAX_WEBP_DIMENSION_PX) return candidate
      candidate -= CELL_WIDTH_STEP_PX
    }
    return MIN_CELL_WIDTH_PX
  }

  /**
   * Sum the pixel cost of every section's rows + headers. When [includeYaml] is true,
   * the YAML strip's contribution is computed per grid row as `max(lines in row) ×
   * line-height + padding` — matching CSS Grid's `align-items: stretch` row-alignment.
   * This lets a row with all 2-line YAMLs cost ~38px while an adjacent row with one
   * 8-line YAML costs ~124px, instead of every row paying the worst-case ${YAML_SNIPPET_MAX_LINES}-line
   * tax.
   *
   * Why per-row rather than per-cell: this is the same shape CSS produces at render
   * time, so the Kotlin estimate and the actual rendered PNG end up tracking each
   * other within ~5%. The pre-render guard would otherwise be wildly conservative for
   * sessions where most tools have tiny YAMLs but one tool has a big one.
   */
  private fun estimateSectionRowsPx(
    sections: List<StoryboardSection>,
    cellHeightPx: Int,
    columns: Int,
    includeYaml: Boolean,
  ): Int = sections.sumOf { section ->
    val cellRows = section.cells.chunked(columns)
    val rowsHeight = cellRows.sumOf { rowCells ->
      // Per-cell footer is *either* the YAML strip (if the cell has a yamlSnippet) *or*
      // the synthesized label strip (fallback for cells without a sibling tool log).
      // CSS Grid stretches every cell in the row to match the tallest, so the row's
      // footer contribution is the max across both options.
      val maxYamlLinesInRow = if (includeYaml) {
        rowCells.maxOf { it.yamlSnippet?.lines()?.size ?: 0 }
      } else 0
      val yamlPx = if (maxYamlLinesInRow == 0) 0 else maxYamlLinesInRow * YAML_LINE_HEIGHT_PX + YAML_BLOCK_PADDING_PX
      val anyCellLacksYaml = rowCells.any { it.yamlSnippet == null }
      val labelPx = if (anyCellLacksYaml) LABEL_STRIP_HEIGHT_PX else 0
      cellHeightPx + maxOf(yamlPx, labelPx)
    }
    rowsHeight + SECTION_HEADER_HEIGHT_PX
  }

  /** Pixel height of a single YAML line at the configured font-size × line-height
   *  (10px × 1.4 = 14px). Matches the `.yaml` CSS in [buildHtml]. */
  private const val YAML_LINE_HEIGHT_PX: Int = 14

  /** Pixel padding around the YAML `<pre>` block (top+bottom + border). Matches the
   *  `.yaml` CSS in [buildHtml]: 6px padding × 2 = 12, +1px border = 13, rounded up. */
  private const val YAML_BLOCK_PADDING_PX: Int = 14

  /** Floor for auto-fit cell-width shrinking. Below this, thumbnails become too small
   *  for click-through readability — the post-render dimension check fails cleanly. */
  internal const val MIN_CELL_WIDTH_PX: Int = 140

  /** Step size for the auto-fit binary-walk. 20px steps converge in ~8 iterations from
   *  the 300px default to the 140px floor — fast enough not to matter. */
  private const val CELL_WIDTH_STEP_PX: Int = 20

  /**
   * Pick a representative cell aspect ratio (h/w as Double) from the first cell with
   * a sane device size. Mixed orientations within a single trail are rare; outlier
   * cells letterbox via `object-fit: contain` in the rendered HTML.
   */
  private fun estimateCellHeightPx(cells: List<StoryboardCell>, cellWidthPx: Int): Int {
    val first = cells.firstOrNull { it.deviceWidth > 0 && it.deviceHeight > 0 }
      ?: return (cellWidthPx * 16.0 / 9.0).toInt() // fall back to portrait-ish
    val aspect = first.deviceHeight.toDouble() / first.deviceWidth.toDouble()
    return (cellWidthPx * aspect).toInt()
  }

  /**
   * Build a self-contained HTML page. Images are inlined as base64 `data:` URIs so the
   * resulting file is portable — a human can mail it, attach it to a Linear ticket, or
   * scp it to another machine and it'll still render.
   *
   * Internal so tests can snapshot the layout markup without going through Playwright.
   */
  internal fun buildHtml(
    sections: List<StoryboardSection>,
    columns: Int,
    cellWidthPx: Int,
    pageWidthPx: Int,
    includeYaml: Boolean,
  ): String {
    val allCells = sections.flatMap { it.cells }
    val cellHeightPx = estimateCellHeightPx(allCells, cellWidthPx)
    // StringBuilder's initial-capacity ctor takes Int. By the time we reach buildHtml,
    // `checkInlinedHtmlSize` has already rejected any session that would exceed
    // MAX_INLINED_HTML_BYTES (well below Int.MAX_VALUE), so the cast is safe and
    // capped — coerceAtMost guards against a future cap raise that crosses 2GB.
    val sb = StringBuilder(estimateHtmlCapacity(allCells).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
    sb.append("<!doctype html>\n<html><head><meta charset=\"utf-8\"><title>Storyboard</title>\n")
    sb.append("<style>\n")
    sb.append(
      """
        :root { color-scheme: light; }
        * { box-sizing: border-box; }
        body {
          margin: 0 auto;
          padding: ${PAGE_PADDING_PX}px;
          /* max-width pins the layout to $columns columns at the canonical width when
             there's room (this is what Playwright captures), but the page is otherwise
             fluid — narrower viewports and browser zoom-in (Cmd-+) reflow the grid to
             fewer columns naturally via the `auto-fill` template below. No on-page zoom
             controls because we screenshot this exact markup; native browser zoom is the
             reader-side knob. */
          max-width: ${pageWidthPx}px;
          width: 100%;
          font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
          background: #f6f8fa;
          color: #1f2328;
        }
        .grid {
          display: grid;
          /* `auto-fill` trailmaps as many ${cellWidthPx}px columns as fit, so the grid
             reflows under browser zoom and on narrower viewports without distorting cell
             dimensions. At Playwright's pinned ${pageWidthPx}px viewport this always
             resolves to exactly $columns columns — the canonical screenshot layout. */
          grid-template-columns: repeat(auto-fill, ${cellWidthPx}px);
          justify-content: start;
          gap: 8px;
        }
        .cell {
          width: ${cellWidthPx}px;
          background: white;
          border: 1px solid #d0d7de;
          border-radius: 6px;
          overflow: hidden;
          display: flex;
          flex-direction: column;
        }
        .imgwrap {
          width: 100%;
          height: ${cellHeightPx}px;
          background: #0d1117;
          display: flex;
          align-items: center;
          justify-content: center;
          position: relative;
        }
        .imgwrap img {
          max-width: 100%;
          max-height: 100%;
          object-fit: contain;
          display: block;
        }
        .badge {
          position: absolute;
          top: 4px;
          left: 4px;
          font-size: 11px;
          font-weight: 600;
          padding: 2px 6px;
          border-radius: 4px;
          background: rgba(15, 23, 42, 0.85);
          color: white;
          font-variant-numeric: tabular-nums;
        }
        /* Source chip — rendered in the opposite corner from the step-number badge so
           the two never overlap, even on tiny cells. Every cell gets exactly one chip:
           - .ai-chip (green) for cells whose source log shares a traceId with a
             TrailblazeLlmRequestLog — "the LLM decided this".
           - .rec-chip (red) for everything else — deterministic dispatch from a recorded
             trail or direct tool invocation. Red borrows the camera-record-button
             visual vocabulary, not error semantics.
           Both share size/position/typography so they're directly comparable in a
           mixed session; only the fill differs. The white outline keeps both legible
           over arbitrary screenshot content. */
        .source-chip {
          position: absolute;
          top: 4px;
          right: 4px;
          font-size: 10px;
          font-weight: 700;
          padding: 2px 6px;
          border-radius: 4px;
          color: white;
          letter-spacing: 0.5px;
          box-shadow: 0 0 0 1px rgba(255, 255, 255, 0.4);
        }
        .source-chip.ai  { background: rgba(31, 136, 61, 0.92); }
        .source-chip.rec { background: rgba(207, 34, 46, 0.92); }
        .label {
          padding: 6px 8px;
          font-size: 12px;
          line-height: 1.3;
          border-top: 1px solid #d0d7de;
        }
        .label .verb { font-weight: 600; }
        .label .detail {
          color: #57606a;
          margin-left: 4px;
          word-break: break-word;
        }
        /* YAML snippet — opt-in via --storyboard-yaml. No `max-height` clamp: CSS Grid's
           default `align-items: stretch` matches all cells in a row to the tallest cell,
           so removing the clamp lets each row size itself to whatever the tallest YAML
           in that row needs ("row 1: 2 lines, row 2: 6 lines" rather than "all rows: 4
           lines"). Kotlin still bounds at ${YAML_SNIPPET_MAX_LINES} lines for pathological
           inputs (a multi-line enterText with 200 chars of typed input shouldn't dominate
           the layout). Shorter YAMLs in tall rows show whitespace below them, which falls
           out cleanly because `.cell` is `display: flex; flex-direction: column`. */
        .yaml {
          margin: 0;
          padding: 6px 8px;
          font-family: "SF Mono", "JetBrains Mono", Menlo, Consolas, monospace;
          font-size: 10px;
          line-height: 1.4;
          background: #f6f8fa;
          color: #1f2328;
          border-top: 1px solid #d0d7de;
          white-space: pre;
          word-break: normal;
        }
        /* Section header — one per `ObjectiveStartLog` group. The natural-language step
           text reads like a chapter title above the grid of cells the agent ran through
           to accomplish it. Tall enough to dominate a cell border without overwhelming. */
        .section { margin-bottom: 20px; }
        .section:last-child { margin-bottom: 0; }
        .section-header {
          margin: 0 0 8px 0;
          padding: 8px 12px;
          font-size: 14px;
          font-weight: 600;
          color: #1f2328;
          background: #ddf4ff;
          border-left: 3px solid #0969da;
          border-radius: 3px;
        }
        .section-header .count {
          font-weight: 400;
          color: #57606a;
          margin-left: 8px;
          font-variant-numeric: tabular-nums;
        }
      """.trimIndent(),
    )
    sb.append("\n</style>\n</head><body>\n")
    for (section in sections) {
      sb.append("<section class=\"section\">\n")
      sb.append("<h2 class=\"section-header\">")
        .append(htmlEscape(section.title))
        .append("<span class=\"count\">")
        .append(section.cells.size)
        .append(if (section.cells.size == 1) " step" else " steps")
        .append("</span>")
        .append("</h2>\n")
      sb.append("<div class=\"grid\">\n")
      for (cell in section.cells) {
        appendCell(sb, cell)
      }
      sb.append("</div>\n</section>\n")
    }
    sb.append("</body></html>\n")
    return sb.toString()
  }

  /**
   * Render a single cell into [sb]. Factored out of [buildHtml] so the per-section loop
   * stays readable — same markup we used before, just minus the section-level wrapping.
   */
  private fun appendCell(sb: StringBuilder, cell: StoryboardCell) {
    // URL-form cells (test-farm signed S3/CDN URLs): emit the URL verbatim so Chromium
    // fetches the image during headless capture — same shape WasmReport uses for the
    // timeline animation. Local-file cells: read bytes and inline as a base64 data URI
    // so the companion `storyboard.html` is portable (scp/email-able, doesn't go stale
    // when a signed URL expires).
    val imgSrc = cell.screenshotUrl
      ?: cell.screenshot?.let { encodeImageAsDataUri(it) }
      ?: error("StoryboardCell #${cell.index} has neither screenshot nor screenshotUrl")
    sb.append("<div class=\"cell\">\n")
    sb.append("  <div class=\"imgwrap\">")
    sb.append("<span class=\"badge\">").append(cell.index).append("</span>")
    if (cell.aiGenerated) {
      sb.append("<span class=\"source-chip ai\" title=\"This action was decided by an LLM\">AI</span>")
    } else {
      sb.append("<span class=\"source-chip rec\" title=\"This action was dispatched from a recorded trail (no LLM in the loop)\">REC</span>")
    }
    sb.append("<img alt=\"")
      .append(htmlEscape(cell.label))
      .append("\" src=\"")
      // Only URL cells need escaping (signed-URL query strings can legitimately carry
      // `&` and the occasional `=` followed by punctuation). Base64 data URIs use the
      // fixed `A–Za–z0–9+/=` alphabet plus the literal `data:image/...;base64,` prefix —
      // none of those bytes are HTML-special, so running the five-pass `htmlEscape`
      // over a ~330KB base64 string is pure overhead that scales with cell count.
      .append(if (cell.screenshotUrl != null) htmlEscape(imgSrc) else imgSrc)
      .append("\"/>")
    sb.append("</div>\n")
    // When we have a YAML snippet, it IS the label — the recordable tool name + args
    // is strictly more informative than the synthesized "Tap (x, y)" / "Assert <desc>"
    // line that would otherwise appear above it. Skip the redundant strip. Fall back to
    // the synthesized label only for cells where the YAML lookup failed (no sibling
    // TrailblazeToolLog with a matching traceId — older logs, or actions dispatched
    // without going through the recordable-tool path) so those cells aren't unlabeled.
    val yaml = cell.yamlSnippet
    if (yaml != null) {
      sb.append("  <pre class=\"yaml\">").append(htmlEscape(yaml)).append("</pre>\n")
    } else {
      sb.append("  <div class=\"label\"><span class=\"verb\">")
        .append(htmlEscape(cell.label))
        .append("</span>")
      if (cell.sublabel.isNotEmpty()) {
        sb.append("<span class=\"detail\">").append(htmlEscape(cell.sublabel)).append("</span>")
      }
      sb.append("</div>\n")
    }
    sb.append("</div>\n")
  }

  /**
   * Throws when the projected base64-inlined HTML would exceed [MAX_INLINED_HTML_BYTES].
   * Splits out from [export]'s preflight so tests can drive the check with a bare cell
   * list, no real LogsRepo or Playwright stack required. Error message names the
   * estimated MB and the cap so users know whether to split-session, drop YAML, or
   * shrink screenshots.
   *
   * Internal so the test class in the same package can call it without going through
   * the full `export()` orchestration.
   */
  internal fun checkInlinedHtmlSize(allCells: List<StoryboardCell>) {
    val totalCells = allCells.size
    if (totalCells == 0) return
    val estimatedHtmlBytes = estimateHtmlCapacity(allCells)
    check(estimatedHtmlBytes < MAX_INLINED_HTML_BYTES) {
      "Storyboard HTML would inline ~${estimatedHtmlBytes / (1024 * 1024)}MB of base64 " +
        "screenshot data ($totalCells cells × ~${estimatedHtmlBytes / totalCells / 1024}KB " +
        "each) — exceeds the ${MAX_INLINED_HTML_BYTES / (1024 * 1024)}MB preflight cap. " +
        "Split the session into smaller recordings or reduce screenshot resolution before " +
        "running the storyboard exporter."
    }
  }

  private fun estimateHtmlCapacity(cells: List<StoryboardCell>): Long {
    // base64 ~= 4/3 of source; assume 250KB avg PNG per cell as the planning bound for
    // local-file cells. URL-form cells (test-farm signed S3/CDN URLs) contribute only
    // the URL string itself — ~600 bytes plus cell markup, an order of magnitude smaller
    // than a base64-inlined screenshot.
    // `cells.size.toLong() * perCellChars` is the critical promotion — Int multiplication
    // wraps at ~6300 cells (Int.MAX_VALUE / perCellChars), so a multi-thousand-cell
    // session would silently overflow and pass the memory preflight with a negative or
    // small-positive value. The .toLong() forces Long arithmetic before multiplication.
    val localPerCellChars = (250 * 1024 * 4 / 3) + 512
    val urlPerCellChars = 1024
    var total = 4096L
    for (cell in cells) {
      total += if (cell.screenshotUrl != null) urlPerCellChars.toLong() else localPerCellChars.toLong()
    }
    return total
  }

  /** True when [s] looks like an absolute HTTP(S) URL — used to short-circuit the
   *  local-file resolver for test-farm sessions whose `screenshotFile` is a signed
   *  S3/CDN URL. Same recognizer WasmReport uses. */
  private fun isHttpUrl(s: String): Boolean =
    s.startsWith("http://") || s.startsWith("https://")

  /**
   * Read [file] and return a `data:image/<ext>;base64,...` URI. Format is inferred from
   * the file extension (the same extension the on-disk filename was assigned at write
   * time via [TrailblazeImageFormat]).
   */
  private fun encodeImageAsDataUri(file: File): String {
    val mime = when (file.extension.lowercase()) {
      "png" -> "image/png"
      "jpg", "jpeg" -> "image/jpeg"
      "webp" -> "image/webp"
      else -> "application/octet-stream"
    }
    val b64 = Base64.getEncoder().encodeToString(file.readBytes())
    return "data:$mime;base64,$b64"
  }

  private fun htmlEscape(s: String): String =
    s.replace("&", "&amp;")
      .replace("<", "&lt;")
      .replace(">", "&gt;")
      .replace("\"", "&quot;")
      // Single-quote escape kept for defense-in-depth: current markup uses
      // double-quoted attributes throughout, but a future edit that switches to
      // single-quoted attributes would otherwise expose an XSS vector through
      // user-controlled cell labels / YAML / section titles.
      .replace("'", "&#39;")

  /**
   * Truncate a string to [maxLen] chars by replacing the middle with "…". Preserves the
   * leading/trailing context that's usually most informative for a label.
   */
  private fun String.truncateMid(maxLen: Int): String {
    if (length <= maxLen) return this
    val keep = maxLen - 1
    val head = keep / 2
    val tail = keep - head
    return substring(0, head) + "…" + substring(length - tail)
  }

  /**
   * Open a headless Playwright page on [htmlFile], measure its content dimensions
   * once, then hand the caller a `captureWebp(outputFile, scale)` lambda it can invoke
   * one or more times — for the initial full-resolution encode plus any `--max-size`
   * rescale iterations — without re-launching Chromium or re-loading the HTML.
   *
   * Each `captureWebp` call goes through CDP `Page.captureScreenshot` with
   * `format: "webp"`, `quality: 80`, `captureBeyondViewport: true`, and an explicit
   * `clip` covering the full content size with the requested CSS-px `scale`. The
   * response payload is base64-decoded straight onto disk. No PNG intermediate, no
   * ffmpeg, no host-side `cwebp` — Chromium ships its own libwebp.
   *
   * **Why CDP rather than [Page.screenshot].** Playwright Java 1.59's
   * `ScreenshotType` enum is `PNG | JPEG` only. Driving CDP directly lets us pick
   * `webp` without waiting on the Java client to expose the underlying CDP capability
   * (the JS client has had `type: "webp"` for years). The CDP session is opened on
   * the existing [com.microsoft.playwright.BrowserContext] — same pattern as the
   * `WebAuthn.enable` call in `PlaywrightBrowserManager.disableWebAuthn`.
   *
   * **Why this doesn't reuse [PlaywrightReportCapture] like `--gif` / `--webp`.** The
   * timeline exporters all share a frame-capture loop that loads the WASM timeline
   * report and screenshots every ~200ms until autoplay ends — a multi-frame sequence.
   * The storyboard pipeline loads a purpose-built grid HTML (no WASM, no autoplay) and
   * takes one or more single-frame full-page captures, so it has no use for the
   * timeline frame cadence, the `__tbPlaybackEnded` signal, or the fps measurement.
   * Inlining its own `PlaywrightBrowserManager` lifecycle keeps the storyboard path
   * independent and lets the two capture styles evolve separately.
   *
   * **Dimension guard placement.** Pre-CDP we project the encoded size as
   * `contentCss × scale × devicePixelRatio` and check it against
   * `LIBWEBP_HARD_DIMENSION_MAX`. Tripping this raises with the same actionable
   * "split / re-column / drop YAML" message we used to surface after a wasted ffmpeg
   * invocation — only now we never wasted any encoder work.
   */
  private fun withRenderedStoryboardPage(
    htmlFile: File,
    viewportWidthPx: Int,
    headless: Boolean,
    includeYaml: Boolean,
    totalCells: Int,
    numSections: Int,
    columns: Int,
    block: (captureWebp: (File, Int?) -> Unit) -> Unit,
  ) {
    val deviceId = "report-storyboard-${UUID.randomUUID().toString().take(8)}"
    val onInstallProgress = PlaywrightReportCapture.makeInstallProgressLogger("ReportStoryboardExporter")
    var manager: PlaywrightBrowserManager? = null
    try {
      manager = PlaywrightBrowserManager(
        headless = headless,
        deviceId = deviceId,
        onBrowserInstallProgress = onInstallProgress,
      )
      val mgr = manager
      runBlocking(mgr.playwrightDispatcher) {
        val page = mgr.currentPage
        // Set viewport wide enough that the body's fixed width fits without horizontal
        // scrollbar. Height seed is intentionally small (100px) — `captureBeyondViewport`
        // extends the screenshot to `max(viewportHeight, contentHeight)`, so seeding
        // tall would pad the WebP with viewport-height whitespace when the grid is
        // short (e.g. a 4-cell session at 6 cols renders ~250px of content; a 720px
        // seed gave us 470px of empty space below). The small seed lets the capture
        // extend cleanly to whatever height the grid actually needs.
        page.setViewportSize(viewportWidthPx, 100)
        val url = htmlFile.toURI().toASCIIString()
        Console.log("[ReportStoryboardExporter] navigating to $url")
        page.navigate(url)
        page.waitForLoadState(LoadState.LOAD)
        // Belt-and-braces: data-URI <img> tags decode synchronously but waiting for
        // NETWORKIDLE is the documented way to know all <img> have settled across browsers.
        page.waitForLoadState(LoadState.NETWORKIDLE)

        // Measure once — content dimensions and DPR don't change across rescale
        // iterations (`clip.scale` rescales the captured surface, not the underlying
        // layout). Reading them via a single evaluate() roundtrip keeps the per-
        // iteration cost down to one CDP call.
        @Suppress("UNCHECKED_CAST")
        val dims = page.evaluate(
          "() => ({ w: document.documentElement.scrollWidth, " +
            "h: document.documentElement.scrollHeight, " +
            "dpr: window.devicePixelRatio })",
        ) as Map<String, Any>
        val contentW = (dims["w"] as Number).toInt()
        val contentH = (dims["h"] as Number).toInt()
        val dpr = (dims["dpr"] as Number).toDouble()
        Console.log(
          "[ReportStoryboardExporter] page content ${contentW}x${contentH} CSS px " +
            "(DPR=$dpr, device ${(contentW * dpr).toInt()}x${(contentH * dpr).toInt()})",
        )

        val cdp = page.context().newCDPSession(page)
        try {
          block { outputWebp, targetDevicePxWidth ->
            // CDP's `clip.scale` produces an image sized `clip.width × clip.scale`
            // — in CSS pixels, ignoring the page's deviceScaleFactor. So to land on a
            // specific device-pixel output width we compute the CSS scale that
            // produces it directly. Null target = "native" = capture at the page's
            // own DPR, matching what the prior PNG→ffmpeg path produced (and the
            // CSS comment column shrink ratio the docstring on
            // [MAX_WEBP_DIMENSION_PX] depends on).
            //
            // Earlier code took a `requestedScale: Double` and computed
            // `effectiveScale = dpr * requestedScale`. That made the rescale ladder
            // round-trip through DPR a second time — `w=1280` from `MaxArtifactSize`
            // (which is a device-pixel width by convention from the timeline path)
            // would land at `2560` device px instead of `1280`; caught in code review.
            val effectiveScale = targetDevicePxWidth?.let { it.toDouble() / contentW } ?: dpr
            // `ceil` so the libwebp guard never under-estimates relative to
            // Chromium's internal rounding — Copilot pointed out that
            // `(contentW * effectiveScale).toInt()` could read 16383 while the
            // encoder sees 16384 and refuses.
            val outputPxW = kotlin.math.ceil(contentW * effectiveScale).toInt()
            val outputPxH = kotlin.math.ceil(contentH * effectiveScale).toInt()
            check(outputPxW <= LIBWEBP_HARD_DIMENSION_MAX && outputPxH <= LIBWEBP_HARD_DIMENSION_MAX) {
              "Storyboard would render at ${outputPxW}x${outputPxH} device px " +
                "(CSS ${contentW}x${contentH} × effectiveScale=${"%.3f".format(effectiveScale)}) — " +
                "exceeds libwebp's ${LIBWEBP_HARD_DIMENSION_MAX}px encoder cap " +
                "($totalCells cells across $numSections section(s), $columns cols" +
                (if (includeYaml) ", YAML on" else "") +
                "). Re-run with more columns (e.g. --storyboard-columns 8 — smaller " +
                "cells means fewer rows per section)" +
                (if (includeYaml) ", drop --storyboard-yaml" else "") +
                ", or split the session into smaller recordings."
            }
            val params = JsonObject().apply {
              addProperty("format", "webp")
              addProperty("quality", WEBP_QUALITY)
              addProperty("captureBeyondViewport", true)
              add(
                "clip",
                JsonObject().apply {
                  addProperty("x", 0)
                  addProperty("y", 0)
                  addProperty("width", contentW)
                  addProperty("height", contentH)
                  addProperty("scale", effectiveScale)
                },
              )
            }
            val response = cdp.send("Page.captureScreenshot", params)
            val base64 = response.get("data").asString
            outputWebp.parentFile?.mkdirs()
            outputWebp.writeBytes(Base64.getDecoder().decode(base64))
          }
        } finally {
          runCatching { cdp.detach() }
        }
      }
    } finally {
      runCatching { manager?.close() }
    }
  }

  /** WebP quality (0–100) handed to CDP. 80 matches the prior ffmpeg
   *  `-q:v 80` setting — visually indistinguishable from lossless at storyboard sizes
   *  but ~5× smaller. */
  private const val WEBP_QUALITY: Int = 80
}
