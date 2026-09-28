package xyz.block.trailblaze.cli

import com.microsoft.playwright.Page
import com.microsoft.playwright.TimeoutError
import com.microsoft.playwright.options.LoadState
import com.microsoft.playwright.options.ScreenshotAnimations
import java.io.File
import java.util.UUID
import kotlin.math.ceil
import kotlinx.coroutines.runBlocking
import xyz.block.trailblaze.playwright.PlaywrightBrowserManager
import xyz.block.trailblaze.util.Console

/**
 * Shared scaffolding for the headless-Playwright report-export pipeline. Both
 * [ReportGifExporter] and [ReportWebpExporter] capture PNG frames in exactly the same way
 * — only the final encoder step differs. [ReportVideoExporter] takes a different path
 * (Playwright's `setRecordVideoDir` for a WebM→MP4 transcode) but still benefits from the
 * URL / install-progress helpers here.
 *
 * Keeping the capture loop in one place is the practical mitigation for the lead-dev-review
 * comment on PR #3083: with three exporters in the tree, the frame stepping and playback end
 * detection only need to be right once.
 */
internal object PlaywrightReportCapture {

  /** Default upper bound on how long we'll wait for the timeline's playback-ended signal. */
  internal const val DEFAULT_MAX_PLAYBACK_WAIT_MS: Long = 10 * 60 * 1000L

  /** Environment variable that overrides the playback-wait ceiling (milliseconds). */
  internal const val MAX_PLAYBACK_WAIT_ENV: String = "MAX_PLAYBACK_WAIT_MS"

  /**
   * Resolved playback-wait ceiling in ms. Overridable via the [MAX_PLAYBACK_WAIT_ENV]
   * environment variable so all three exporters (`--gif`, `--webp`, `--video`) honor the
   * same escape hatch the timeout message advertises — previously `--video` ignored it and
   * hit a hardcoded Playwright timeout (https://github.com/block/trailblaze/issues/173). Non-numeric or non-positive values
   * fall back to [DEFAULT_MAX_PLAYBACK_WAIT_MS]. With idle-gap compression in the autoplay
   * timeline this ceiling is rarely reached, but it remains the documented manual override.
   */
  internal val maxPlaybackWaitMs: Long
    get() = resolveMaxPlaybackWaitMs(System.getenv(MAX_PLAYBACK_WAIT_ENV))

  /**
   * Pure resolver for [maxPlaybackWaitMs] — split out from the `System.getenv` read so the
   * parsing/fallback rules are unit-testable without mutating process environment. A
   * non-numeric or non-positive [raw] override falls back to [DEFAULT_MAX_PLAYBACK_WAIT_MS].
   */
  internal fun resolveMaxPlaybackWaitMs(raw: String?): Long =
    raw?.trim()?.toLongOrNull()?.takeIf { it > 0 } ?: DEFAULT_MAX_PLAYBACK_WAIT_MS

  /**
   * The longest playback time one captured frame covers (so at least 5fps). Frames are stepped,
   * not sampled: each shows the timeline at an exact instant however long the page took to draw
   * it, so the artifact's timing never depends on how loaded the machine was. See
   * [frameTimesFor] for where the instants fall. 5fps is a sweet spot for output size: at 30fps
   * a 60s autoplay would easily blow past 50MB as a GIF.
   */
  internal const val FRAME_INTERVAL_MS: Long = 200L

  /**
   * [frameDurationsMs] is how long each captured `frame_NNNNN.png` is held, in order. Frames
   * are not evenly spaced — see [frameTimesFor] — so the encoders take a duration per frame.
   */
  data class CaptureResult(val frameDurationsMs: List<Int>) {
    val frameCount: Int get() = frameDurationsMs.size
  }

  /**
   * Per-export filesystem workspace for the GIF / WebP path: a unique `deviceId` (avoids
   * collisions when multiple exports run concurrently in the same JVM — CI matrix, etc.),
   * a temp `tempDir` rooted under `java.io.tmpdir`, and a `framesDir` inside it where
   * Playwright drops `frame_NNNNN.png` files. Caller owns the cleanup
   * (`tempDir.deleteRecursively()` in a `finally` block).
   */
  data class FrameWorkspace(val deviceId: String, val tempDir: File, val framesDir: File)

  /**
   * Allocate a fresh [FrameWorkspace] for a frame-capture export. [kind] names the
   * exporter (`gif` / `webp`) for log + path readability — keeps temp dirs from
   * colliding when both exporters run side-by-side, and makes leftover dirs
   * recognizable for cleanup.
   */
  fun newFrameWorkspace(kind: String): FrameWorkspace {
    val deviceId = "report-$kind-${UUID.randomUUID().toString().take(8)}"
    val tempDir = File(
      System.getProperty("java.io.tmpdir"),
      "trailblaze-report-$kind-$deviceId",
    ).apply { mkdirs() }
    val framesDir = File(tempDir, "frames").apply { mkdirs() }
    return FrameWorkspace(deviceId, tempDir, framesDir)
  }

  /**
   * Loads [reportHtml] with `?autoplay=step` in a headless Playwright tab and steps its timeline
   * through [frameTimesFor], screenshotting each instant once the report says it is drawn
   * (`__tbExport.renderAt`, which waits for the session recording to finish seeking). Returns
   * what the caller needs to feed the downstream encoder.
   *
   * Throws (via `error`) on capture failure — timeout or zero frames — so the caller
   * doesn't silently produce a truncated artifact and exit 0.
   *
   * @param reportHtml Single-session interactive report HTML.
   * @param framesDir Directory to write `frame_NNNNN.png` files into. Created by caller.
   * @param tag Log-line prefix, e.g. `ReportGifExporter` / `ReportWebpExporter`. Used so
   *   each exporter's logs stay grep-distinguishable.
   */
  fun captureFrames(
    reportHtml: File,
    framesDir: File,
    headless: Boolean,
    deviceId: String,
    tag: String,
  ): CaptureResult {
    val onInstallProgress = makeInstallProgressLogger(tag)
    // Resolve the wait ceiling once so the deadline and the timeout warning can't disagree.
    val waitMs = maxPlaybackWaitMs
    var manager: PlaywrightBrowserManager? = null
    var capturedFrames = 0
    var playbackEnded = false
    var frameTimes = emptyList<Long>()
    try {
      manager = PlaywrightBrowserManager(
        headless = headless,
        deviceId = deviceId,
        onBrowserInstallProgress = onInstallProgress,
        // Capture at 1x. The frames become an animated artifact people share in a PR rather than
        // something read pixel-for-pixel. A headed run would otherwise capture at 2x, which
        // quadruples the bytes to carry detail the format then throws away. Pinning it also makes
        // a laptop export match what CI produces from the same command.
        deviceScaleFactorOverride = 1.0,
      )
      val mgr = manager
      runBlocking(mgr.playwrightDispatcher) {
        val page = mgr.currentPage
        val url = buildReportUrl(reportHtml, stepped = true)
        Console.log("[$tag] navigating to $url")
        page.navigate(url)
        page.waitForLoadState(LoadState.DOMCONTENTLOADED)

        val deadline = System.currentTimeMillis() + waitMs
        // The hook appears once the report's payload has fully loaded (a chunked report streams
        // its sessions in after DOMCONTENTLOADED), so wait for it rather than for the load state.
        try {
          page.waitForFunction(
            "() => !!globalThis.__tbExport",
            null,
            Page.WaitForFunctionOptions().setTimeout(waitMs.toDouble()),
          )
        } catch (_: TimeoutError) {
          error("The report never exposed its frame-export hook (__tbExport) within ${waitMs / 1000}s.")
        }
        // Rounded up: scaled dwells put the end and events mid-millisecond, and a frame truncated
        // to before one still shows the prior step (or, at the end, never reports done).
        val totalMs = wholeMsAtOrAfter(page.evaluate("() => globalThis.__tbExport.totalMs") as Number)
        val eventMs = (page.evaluate("() => globalThis.__tbExport.eventMs") as? List<*>)
          .orEmpty()
          .mapNotNull { (it as? Number)?.let(::wholeMsAtOrAfter) }
        frameTimes = frameTimesFor(totalMs, eventMs)
        Console.log(
          "[$tag] capturing ${frameTimes.size} frames of a ${totalMs}ms timeline " +
            "(${eventMs.size} events)...",
        )
        val screenshotOptions = Page.ScreenshotOptions()
          .setFullPage(false)
          .setAnimations(ScreenshotAnimations.DISABLED)
        for (atMs in frameTimes) {
          if (System.currentTimeMillis() >= deadline) break
          // As an Int: Playwright's argument serializer rejects a Long.
          val ended = page.evaluate("(ms) => globalThis.__tbExport.renderAt(ms)", atMs.toInt()) as? Boolean ?: false
          val frame = File(framesDir, String.format("frame_%05d.png", capturedFrames))
          frame.writeBytes(page.screenshot(screenshotOptions))
          capturedFrames++
          if (ended) {
            playbackEnded = true
            break
          }
        }
        Console.log("[$tag] captured $capturedFrames frames")
      }
    } finally {
      runCatching { manager?.close() }
    }

    if (capturedFrames == 0) error("No frames were captured — Playwright produced zero screenshots.")
    if (!playbackEnded) {
      // Fail soft: emit the best-effort truncated artifact rather than aborting with no
      // output (https://github.com/block/trailblaze/issues/173). With idle-gap compression a timeout here is unusual, so call
      // it out loudly and point at the override.
      //
      // On Console.error, not Console.log: log is suppressed in CLI quiet mode, which is
      // the one thing that would make a truncated artifact genuinely silent. Exit stays 0
      // — a partial animation is still a usable artifact and the report itself succeeded.
      Console.error(
        "[$tag] WARNING: timeline playback did not signal completion within " +
          "${waitMs / 1000}s — writing a truncated $capturedFrames-frame artifact. " +
          "Set $MAX_PLAYBACK_WAIT_ENV (ms) higher to capture the full timeline.",
      )
    }

    return CaptureResult(frameDurationsMs = frameDurationsFor(frameTimes.take(capturedFrames)))
  }

  /** The first whole millisecond at or after [ms], so a frame there shows that instant. */
  internal fun wholeMsAtOrAfter(ms: Number): Long = ceil(ms.toDouble()).toLong()

  /**
   * The playback instants a [totalMs] timeline is captured at. Every event in [eventMs] (the
   * instant a timeline entry starts — the rail's tick marks) gets a frame of its own, so a tap
   * appears in the artifact at exactly the moment it happened; a fixed grid lands up to a whole
   * interval late, and in a compressed idle gap that interval is seconds of recording. The span
   * between two events is split evenly into frames no more than [FRAME_INTERVAL_MS] apart, and
   * the last frame sits at [totalMs], where the report shows its landed end state.
   */
  internal fun frameTimesFor(totalMs: Long, eventMs: List<Long>): List<Long> {
    val end = maxOf(0L, totalMs)
    val bounds = (listOf(0L) + eventMs.filter { it in 0 until end }).distinct().sorted() + end
    return bounds.zipWithNext().flatMap { (from, to) ->
      val span = to - from
      if (span <= 0) return@flatMap emptyList()
      val count = ((span + FRAME_INTERVAL_MS - 1) / FRAME_INTERVAL_MS).toInt()
      (0 until count).map { from + span * it / count }
    } + end
  }

  /**
   * How long each frame at [frameTimes] is held: until the next frame's instant, and
   * [FRAME_INTERVAL_MS] for the last one. Never below 1ms, which some decoders read as "use the
   * default".
   */
  internal fun frameDurationsFor(frameTimes: List<Long>): List<Int> =
    frameTimes.mapIndexed { i, at ->
      val next = frameTimes.getOrNull(i + 1)
      (if (next != null) next - at else FRAME_INTERVAL_MS).toInt().coerceAtLeast(1)
    }

  /**
   * Throttled install-progress callback for [PlaywrightBrowserManager]. The underlying
   * `playwright install chromium` invocation emits one callback per output line — too
   * chatty for a CLI status line. Forward only when the visible percent bumps by >=10
   * (plus 100% so the user always sees completion).
   */
  fun makeInstallProgressLogger(tag: String): (Int, String) -> Unit {
    var lastEmittedPct = -1
    return { pct, message ->
      if (pct == 100 || pct >= lastEmittedPct + 10) {
        Console.log("[$tag] Chromium install: ${pct}% — $message")
        lastEmittedPct = pct
      }
    }
  }

  /**
   * Turns an absolute filesystem path into a `file://` URL with the autoplay query parameter the
   * report reads at startup: `autoplay=step` for the frame-stepped capture here, `autoplay=1`
   * for [ReportVideoExporter]'s real-time screen recording. We let `File.toURI().toASCIIString()` do
   * the percent-encoding (generated report paths in `logs/reports/` don't realistically
   * contain `?`, but the `contains("?")` guard keeps the URL well-formed for the edge
   * case where they do).
   */
  fun buildReportUrl(reportHtml: File, stepped: Boolean = false): String {
    val base = reportHtml.toURI().toASCIIString()
    val separator = if (base.contains("?")) "&" else "?"
    return "$base${separator}autoplay=${if (stepped) "step" else "1"}"
  }
}
