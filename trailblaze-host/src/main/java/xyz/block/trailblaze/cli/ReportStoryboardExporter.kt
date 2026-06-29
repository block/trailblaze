package xyz.block.trailblaze.cli

import com.google.gson.JsonObject
import com.microsoft.playwright.Page
import com.microsoft.playwright.options.LoadState
import java.io.File
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.runBlocking
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.playwright.PlaywrightBrowserManager
import xyz.block.trailblaze.report.StoryboardHtmlBuilder
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.util.Console

/**
 * Renders a session as a single "storyboard" image — a CSS grid of every step's
 * screenshot, captioned, encoded as a single-frame WebP for embedding in GitHub PR
 * comments alongside the animated `--gif` / `--webp` artifacts. Sibling to the
 * timeline-autoplay path (`ReportGifExporter` / `ReportWebpExporter` / `ReportVideoExporter`)
 * but doesn't share its frame-capture pipeline — the storyboard renders a purpose-built
 * HTML page and captures it as a single WebP via Chromium's CDP `Page.captureScreenshot`,
 * not the timeline-scrubber playback.
 *
 * **Where the HTML comes from.** The Playwright-free half — log → sections → standalone
 * HTML page, plus the column/cell-width layout decisions — lives in
 * [StoryboardHtmlBuilder] (in `trailblaze-report`) so the report server can serve the
 * same page on demand without depending on this host-only module. This exporter owns the
 * Chromium/WebP-capture half: it asks the builder for a prepared page, loads it headless,
 * and screenshots it.
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
 */
object ReportStoryboardExporter {

  /** Absolute libwebp encoder limit — checked against the projected device-pixel
   *  dimensions of the CDP screenshot (CSS px × scale × DPR) before we call CDP.
   *  Tripping this is a "the Kotlin estimate was wrong" signal; we error before
   *  invoking the encoder so the user sees an actionable "split this session"
   *  message instead of an opaque CDP error from libwebp inside Chromium. */
  private const val LIBWEBP_HARD_DIMENSION_MAX: Int = 16383

  /**
   * Generate `storyboard.html` and `storyboard.webp` from session [sessionId].
   *
   * @param outputWebp Destination for the encoded single-frame WebP.
   * @param outputHtml Optional companion HTML — when non-null, the same page rendered by
   *   Playwright is also written here for standalone viewing. When null, the HTML is
   *   built into a tmp file and discarded after the WebP is encoded.
   * @param maxBytes When non-null, iteratively re-encode at smaller widths until the
   *   WebP fits the cap. Same loop as the timeline exporters use.
   * @param includeYaml when true, each cell's YAML strip replaces the synthesized
   *   verb/sublabel line. Library default is `false` (callers must opt in explicitly);
   *   the CLI's `--storyboard-yaml` flag defaults to `true` for end-user ergonomics —
   *   the asymmetry is intentional so direct callers of this function don't pay the
   *   per-row YAML-rendering cost unless they ask for it.
   * @return [outputWebp] for convenience (mirrors the caller pattern in `ReportCommand`).
   */
  fun export(
    sessionId: SessionId,
    logsRepo: LogsRepo,
    outputWebp: File,
    outputHtml: File? = null,
    columns: Int? = null,
    cellWidthPx: Int = StoryboardHtmlBuilder.DEFAULT_CELL_WIDTH_PX,
    includeYaml: Boolean = false,
    headless: Boolean = true,
    maxBytes: Long? = null,
  ): File {
    val logs = logsRepo.getLogsForSession(sessionId)
    // Resolve sections, pick the column count, auto-fit cell width, run the memory +
    // dimension preflights, and build the HTML — all the Playwright-free layout work,
    // shared with the report server's on-demand `/storyboard` endpoint.
    val prepared = StoryboardHtmlBuilder.prepare(
      logs = logs,
      resolveScreenshotFile = { logsRepo.getScreenshotFile(it) },
      includeYaml = includeYaml,
      columns = columns,
      cellWidthPx = cellWidthPx,
    )

    outputWebp.parentFile?.mkdirs()
    outputHtml?.parentFile?.mkdirs()

    // Always materialize an HTML on disk — Playwright loads it via file://. When the
    // caller asked for a sibling HTML artifact, write it there; otherwise drop a tmp
    // copy we clean up at the end.
    val htmlFile = outputHtml ?: File(
      System.getProperty("java.io.tmpdir"),
      "trailblaze-storyboard-${UUID.randomUUID().toString().take(8)}.html",
    )
    val ownsTmpHtml = outputHtml == null
    htmlFile.writeText(prepared.html)
    if (outputHtml != null) {
      Console.info("Storyboard HTML: file://${outputHtml.absolutePath}")
    }

    try {
      withRenderedStoryboardPage(
        htmlFile = htmlFile,
        viewportWidthPx = prepared.pageWidthPx,
        headless = headless,
        includeYaml = includeYaml,
        totalCells = prepared.totalCells,
        numSections = prepared.numSections,
        columns = prepared.columns,
      ) { captureWebp ->
        // null target = capture at the page's native device-pixel resolution (DPR-baked).
        captureWebp(outputWebp, null)
        Console.log(
          "[ReportStoryboardExporter] wrote ${outputWebp.absolutePath} " +
            "(${outputWebp.length() / 1024}KB, ${prepared.totalCells} cells, ${prepared.numSections} section(s), " +
            "${prepared.columns} cols" + (if (includeYaml) ", YAML on" else "") + ")",
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
            // [StoryboardHtmlBuilder.MAX_WEBP_DIMENSION_PX] depends on).
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
