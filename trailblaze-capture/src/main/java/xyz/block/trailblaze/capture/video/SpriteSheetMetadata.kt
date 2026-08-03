package xyz.block.trailblaze.capture.video

/**
 * Parsed `video_sprites.txt` sprite-sheet metadata plus the acceptance rules every consumer must
 * apply before playing the sprite as a video timeline. This is the single Kotlin home for the
 * format: [VideoSpriteExtractor] writes the file (see its kdoc for the key list) and report-side
 * consumers ([xyz.block.trailblaze.report] WasmReport) parse and gate through here.
 *
 * The parse + acceptance semantics are locked cross-language against the TypeScript twin
 * (`run-report-sprites.ts` in :trailblaze-report resources) by
 * `sprite-metadata-parity-fixtures.json` — consumed by both `SpriteMetadataParityFixturesTest`
 * (Kotlin) and `run-report-sprites.test.ts` (bun). To change a rule here, update both
 * implementations AND the fixture in the same change.
 *
 * Field semantics (all key=value lines; unknown keys ignored, lines without `=` skipped):
 * - `fps`, `frames`, `height` — sprite sampling rate, logical frame count, per-frame height.
 *   `frames` and `height` are required (> 0) or the whole file is unparseable; a missing or
 *   non-positive `fps` falls back to the extractor's default of 2.
 * - `columns`, `rows` — grid geometry; row-major (physical frame N at row `N / columns`, column
 *   `N % columns` — use [VideoSpriteExtractor.spriteGridPosition], never a hand-rolled divide).
 *   `columns` defaults to 1; `rows` defaults to `uniqueFrames ?: frames` (legacy single-column
 *   sheets wrote neither).
 * - `uniqueFrames` — deduplicated physical frame count; null on legacy sheets with no dedup data
 *   (degeneracy checks are skipped for those, conservatively).
 * - `sheets` — sprite sheet file count; defaults to 1. Multi-sheet sprites are only playable by
 *   consumers that declare support (see [rejectionReason]); others fall back to screenshots.
 * - `frameMap` — logical→physical dedup indirection; null (→ identity mapping) when absent,
 *   empty, malformed, or its length doesn't match `frames` (a partial map has no defined meaning,
 *   so it's rejected wholesale rather than patched per-index).
 * - `restamped` — strict `true`/`false` (anything else reads as false, matching legacy files
 *   that predate the key). True means the frame↔timestamp mapping is a uniform GUESS synthesized
 *   by [VideoSpriteExtractor.maybeRestamp]; see [isRestampedSpriteDominatedBySingleFrame].
 * - `frameWidth` — per-frame pixel width; null on sprite files written before the key existed.
 *   Lets consumers size frames without decoding the whole sheet.
 */
data class SpriteSheetMetadata(
  val fps: Int,
  val frames: Int,
  val height: Int,
  val columns: Int,
  val rows: Int,
  val uniqueFrames: Int?,
  val sheets: Int,
  val frameMap: List<Int>?,
  val restamped: Boolean,
  val frameWidth: Int?,
) {

  /** Physical sprite cell for logical frame [logicalIndex] (identity when no dedup map). */
  fun physicalFrame(logicalIndex: Int): Int = frameMap?.getOrNull(logicalIndex) ?: logicalIndex

  /**
   * Row count of sheet [sheetIndex]'s image: every sheet before the last is a full
   * `columns * rows` grid; the last sheet only has the rows its remaining frames need.
   * Only meaningful on an accepted sprite (multi-sheet acceptance requires [uniqueFrames]).
   * The cell within a sheet is [VideoSpriteExtractor.spriteGridPosition] of the sheet-local
   * index `physical % (columns * rows)`; sheet `physical / (columns * rows)`.
   */
  fun sheetRows(sheetIndex: Int): Int {
    if (sheetIndex < sheets - 1) return rows
    val remaining = (uniqueFrames ?: frames) - sheetIndex * columns * rows
    return ((remaining + columns - 1) / columns).coerceIn(1, rows)
  }

  /** Why this sprite must not be played as a video timeline, or null when it's trustworthy. */
  enum class SpriteRejection(val wireName: String) {
    /** More than one sprite sheet file, and this consumer can only render one (or `uniqueFrames` is missing). */
    MULTI_SHEET("multiSheet"),

    /** Too few unique frames for the timeline it spans — a near-static broken-screenrecord artifact. */
    DEGENERATE("degenerate"),

    /** Re-stamped (guessed) timing that parks one physical frame across most of the timeline. */
    RESTAMPED_DOMINATED("restampedDominated"),
  }

  companion object {

    /**
     * Floor on a sprite's unique-frame count below which it can be degenerate — a handful of
     * distinct frames can't represent a real test run, even a short one. A sprite at or above
     * this floor always renders.
     */
    const val MIN_USEFUL_UNIQUE_FRAMES = 8

    /**
     * Total-logical-frame count above which a sub-floor unique count is treated as a near-static
     * recording stretched across the timeline (massive aliasing) rather than a genuinely short
     * clip. ~30s at the 2fps sprite sampling — comfortably above any short, low-motion-but-valid
     * recording, while the canonical broken case (3 unique / 234 total) clears it with huge margin.
     */
    const val MIN_ALIASING_TOTAL_FRAMES = 60

    /**
     * Fraction of a re-stamped sprite's logical timeline that a *single* physical frame may occupy
     * before the sprite is treated as degenerate. When [VideoSpriteExtractor.maybeRestamp] had to
     * synthesize a constant frame rate (`restamped=true`), the frame↔time mapping is a uniform
     * guess; if that guess parks one physical frame across ≥40% of the timeline, the video-frame
     * timeline is showing a frozen frame through most of the run and the per-step screenshot
     * slideshow is strictly better. 40% is comfortably above the ~single-frame share a correctly
     * timed clip produces, and the gate only ever applies to already-untrustworthy (re-stamped)
     * timing — a healthy wall-clock-PTS capture reports `restamped=false` and is never touched.
     */
    const val RESTAMPED_SINGLE_FRAME_DOMINANCE_FRAC = 0.40

    private const val DEFAULT_FPS = 2

    /**
     * Parses the raw text of a `video_sprites.txt`. Returns null when the required geometry
     * (`frames`, `height`) is missing or non-positive — there's nothing to index into. Every
     * other field degrades to its documented default rather than failing the file.
     */
    fun parse(text: String): SpriteSheetMetadata? {
      val props = text.lineSequence()
        .mapNotNull { line ->
          val eq = line.indexOf('=')
          if (eq <= 0) null else line.substring(0, eq).trim() to line.substring(eq + 1).trim()
        }
        .toMap()

      fun positiveInt(key: String): Int? = props[key]?.toIntOrNull()?.takeIf { it > 0 }

      val frames = positiveInt("frames") ?: return null
      val height = positiveInt("height") ?: return null
      val uniqueFrames = positiveInt("uniqueFrames")
      return SpriteSheetMetadata(
        fps = positiveInt("fps") ?: DEFAULT_FPS,
        frames = frames,
        height = height,
        columns = positiveInt("columns") ?: 1,
        rows = positiveInt("rows") ?: uniqueFrames ?: frames,
        uniqueFrames = uniqueFrames,
        sheets = positiveInt("sheets") ?: 1,
        frameMap = parseFrameMap(props["frameMap"], frames),
        restamped = props["restamped"]?.toBooleanStrictOrNull() ?: false,
        frameWidth = positiveInt("frameWidth"),
      )
    }

    /**
     * Comma-separated ints, one per logical frame, or null (→ identity) when absent, empty, any
     * entry is malformed, or the entry count doesn't match [frames].
     */
    private fun parseFrameMap(raw: String?, frames: Int): List<Int>? {
      if (raw.isNullOrBlank()) return null
      val entries = raw.split(",").map { it.trim().toIntOrNull() ?: return null }
      return entries.takeIf { it.size == frames }
    }

    /**
     * A sprite is degenerate when its unique (deduplicated) frame count is below
     * [MIN_USEFUL_UNIQUE_FRAMES] AND either (a) it has lots of total logical frames
     * ([totalFrameCount] >= [MIN_ALIASING_TOTAL_FRAMES]) — i.e. a near-static recording massively
     * aliased across the whole timeline, the broken-screenrecord case — or (b) it has fewer unique
     * frames than the per-step screenshots it would replace. The total-frame rule is the reliable
     * signal: [stepScreenshotCount] collapses to ~2 in replay mode (recorded steps emit few
     * screenshot-bearing logs), so it can't be the only denominator. A short healthy clip has
     * unique ≈ total (little dedup) and a small total, so neither rule fires. [uniqueFrameCount]
     * null means a legacy sheet with no dedup data — conservatively not degenerate.
     */
    fun isSpriteDegenerate(
      uniqueFrameCount: Int?,
      totalFrameCount: Int,
      stepScreenshotCount: Int,
    ): Boolean {
      if (uniqueFrameCount == null) return false
      if (uniqueFrameCount >= MIN_USEFUL_UNIQUE_FRAMES) return false
      return totalFrameCount >= MIN_ALIASING_TOTAL_FRAMES || uniqueFrameCount < stepScreenshotCount
    }

    /**
     * A re-stamped sprite is degenerate when a single physical frame dominates its logical
     * timeline. Only fires when [restamped] is true (real per-frame timing was unrecoverable and
     * the extractor fell back to a uniform-rate guess) — the direct symptom of the broken
     * `screenrecord` case where the guessed distribution collapses most of the timeline onto one
     * frame (observed on a long-running CI trail: one physical frame covering ~43% of a 102-frame
     * map). Correctly timed captures report `restamped=false` and are never flagged here. Gated on
     * [MIN_ALIASING_TOTAL_FRAMES] so a short clip — where one frame naturally owns a larger
     * share — can't trip it.
     */
    fun isRestampedSpriteDominatedBySingleFrame(
      restamped: Boolean,
      frameMap: List<Int>?,
      totalFrameCount: Int,
    ): Boolean {
      if (!restamped) return false
      if (frameMap.isNullOrEmpty()) return false
      if (totalFrameCount < MIN_ALIASING_TOTAL_FRAMES) return false
      val dominantFrameCount = frameMap.groupingBy { it }.eachCount().values.maxOrNull() ?: return false
      return dominantFrameCount.toDouble() / frameMap.size >= RESTAMPED_SINGLE_FRAME_DOMINANCE_FRAC
    }

    /**
     * The single acceptance verdict for a parsed sprite: the first [SpriteRejection] that applies,
     * or null when the sprite is safe to play. Consumers that get a rejection fall back to the
     * per-step screenshot timeline.
     *
     * [supportsMultiSheet] declares whether the consumer can render frames across multiple sheet
     * files (the interactive report can; WasmReport and the Compose frame caches only ever load
     * one image, so they keep the default). Even a capable consumer rejects a multi-sheet sprite
     * without [uniqueFrames] — the last sheet's geometry ([sheetRows]) is underivable without it,
     * and the extractor always writes it alongside `sheets`.
     */
    fun rejectionReason(
      meta: SpriteSheetMetadata,
      stepScreenshotCount: Int,
      supportsMultiSheet: Boolean = false,
    ): SpriteRejection? = when {
      meta.sheets > 1 && (!supportsMultiSheet || meta.uniqueFrames == null) -> SpriteRejection.MULTI_SHEET
      isSpriteDegenerate(meta.uniqueFrames, meta.frames, stepScreenshotCount) -> SpriteRejection.DEGENERATE
      isRestampedSpriteDominatedBySingleFrame(meta.restamped, meta.frameMap, meta.frames) ->
        SpriteRejection.RESTAMPED_DOMINATED
      else -> null
    }
  }
}
