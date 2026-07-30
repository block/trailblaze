// Shared sprite-metadata contract for the interactive report driver (run-report-cli.ts): parses
// the `video_sprites.txt` file VideoSpriteExtractor writes next to `video_sprites.webp`, and
// applies the acceptance rules that decide whether the sprite may play as a video timeline at all.
//
// This is the TypeScript twin of the Kotlin `SpriteSheetMetadata` (in :trailblaze-capture, used by
// the legacy WasmReport). The parse + acceptance semantics are locked cross-language by
// `sprite-metadata-parity-fixtures.json`, consumed by both `SpriteMetadataParityFixturesTest`
// (Kotlin) and `run-report-sprites.test.ts` (bun). To change a rule: update both implementations
// AND the fixture in the same change — never encode new semantics in only one language's tests.
// (Same fixture-parity pattern as run-report-events.ts / session-events-parity-fixtures.json.)
//
// RunReportGenerator stages this file beside the driver in the bun temp dir.

/** Parsed `video_sprites.txt` (see VideoSpriteExtractor's kdoc for the writer-side key list). */
export interface SpriteSheetMeta {
  fps: number;
  frames: number;
  height: number;
  columns: number;
  rows: number;
  /** Deduplicated physical frame count; null on legacy sheets with no dedup data. */
  uniqueFrames: number | null;
  sheets: number;
  /**
   * Logical→physical dedup indirection; null (→ identity) when absent, empty, malformed, or its
   * length doesn't match `frames` (a partial map has no defined meaning — rejected wholesale).
   */
  frameMap: number[] | null;
  /** True when the frame↔timestamp mapping is a uniform GUESS (broken input mp4 timing). */
  restamped: boolean;
  /** Per-frame pixel width; null on sprite files written before the key existed. */
  frameWidth: number | null;
}

/**
 * Floor on a sprite's unique-frame count below which it can be degenerate — a handful of distinct
 * frames can't represent a real test run, even a short one. At or above the floor always renders.
 */
export const MIN_USEFUL_UNIQUE_FRAMES = 8;

/**
 * Total-logical-frame count above which a sub-floor unique count is treated as a near-static
 * recording stretched across the timeline (massive aliasing) rather than a genuinely short clip.
 */
export const MIN_ALIASING_TOTAL_FRAMES = 60;

/**
 * Fraction of a re-stamped sprite's logical timeline one physical frame may occupy before the
 * sprite is treated as degenerate — a guessed timeline frozen on one frame for ≥40% of the run
 * is strictly worse than the per-step screenshot slideshow.
 */
export const RESTAMPED_SINGLE_FRAME_DOMINANCE_FRAC = 0.4;

const DEFAULT_FPS = 2;

/** Strict 32-bit integer parse (mirrors Kotlin `toIntOrNull` — no parseInt("12abc") laxness). */
function strictInt(raw: string | undefined): number | null {
  if (raw === undefined || !/^[+-]?\d+$/.test(raw)) return null;
  const n = Number(raw);
  return n >= -2147483648 && n <= 2147483647 ? n : null;
}

function positiveInt(raw: string | undefined): number | null {
  const n = strictInt(raw);
  return n !== null && n > 0 ? n : null;
}

/**
 * Comma-separated ints, one per logical frame, or null (→ identity) when absent, empty, any entry
 * is malformed, or the entry count doesn't match `frames`.
 */
function parseFrameMap(raw: string | undefined, frames: number): number[] | null {
  if (raw === undefined || !raw.trim()) return null;
  const out: number[] = [];
  for (const part of raw.split(",")) {
    const n = strictInt(part.trim());
    if (n === null) return null;
    out.push(n);
  }
  return out.length === frames ? out : null;
}

/**
 * Parses the raw text of a `video_sprites.txt`. Returns null when the required geometry
 * (`frames`, `height`) is missing or non-positive — there's nothing to index into. Every other
 * field degrades to its documented default rather than failing the file: `fps` → 2, `columns` → 1,
 * `rows` → `uniqueFrames ?? frames`, `sheets` → 1, `restamped` → false (strict lowercase `true`
 * only), `uniqueFrames`/`frameWidth` → null. Lines without `=` are skipped; keys/values trimmed.
 */
export function parseSpriteMetadata(text: string): SpriteSheetMeta | null {
  const props: Record<string, string> = {};
  for (const line of text.split("\n")) {
    const eq = line.indexOf("=");
    if (eq > 0) props[line.slice(0, eq).trim()] = line.slice(eq + 1).trim();
  }
  const frames = positiveInt(props.frames);
  const height = positiveInt(props.height);
  if (frames === null || height === null) return null;
  const uniqueFrames = positiveInt(props.uniqueFrames);
  return {
    fps: positiveInt(props.fps) ?? DEFAULT_FPS,
    frames,
    height,
    columns: positiveInt(props.columns) ?? 1,
    rows: positiveInt(props.rows) ?? uniqueFrames ?? frames,
    uniqueFrames,
    sheets: positiveInt(props.sheets) ?? 1,
    frameMap: parseFrameMap(props.frameMap, frames),
    restamped: props.restamped === "true",
    frameWidth: positiveInt(props.frameWidth),
  };
}

/** The concrete logical→physical map: the explicit frameMap, or identity when there is none. */
export function resolvedFrameMap(meta: SpriteSheetMeta): number[] {
  return meta.frameMap ?? Array.from({ length: meta.frames }, (_, i) => i);
}

/**
 * Location of one physical frame: `columns * rows` frames fill each sheet in order, and within a
 * sheet the fill is ROW-major — left-to-right, then top-to-bottom, ffmpeg `tile`'s fill order —
 * so the column is `L % columns` and the row is `L / columns` for the local index `L`. This is
 * the TS mirror of `VideoSpriteExtractor.spriteGridPosition` (Kotlin), extended with the sheet
 * split — the viewer is the only consumer that renders frames past the first sheet.
 */
export function spriteCell(
  physical: number,
  columns: number,
  rows: number,
): { sheet: number; column: number; row: number } {
  const framesPerSheet = columns * rows;
  const local = physical % framesPerSheet;
  return {
    sheet: Math.floor(physical / framesPerSheet),
    column: local % columns,
    row: Math.floor(local / columns),
  };
}

/**
 * Row count of one sheet's image: every sheet before the last is a full `columns * rows` grid;
 * the last sheet only has the rows its remaining frames need. Only meaningful on an accepted
 * sprite (multi-sheet acceptance requires `uniqueFrames`). Kotlin twin:
 * `SpriteSheetMetadata.sheetRows` — locked cross-language by the parity fixtures.
 */
export function spriteSheetRows(meta: SpriteSheetMeta, sheetIndex: number): number {
  if (sheetIndex < meta.sheets - 1) return meta.rows;
  const remaining = (meta.uniqueFrames ?? meta.frames) - sheetIndex * meta.columns * meta.rows;
  return Math.min(meta.rows, Math.max(1, Math.ceil(remaining / meta.columns)));
}

/**
 * A sprite is degenerate when its unique (deduplicated) frame count is below
 * MIN_USEFUL_UNIQUE_FRAMES AND either (a) it spans many logical frames (≥
 * MIN_ALIASING_TOTAL_FRAMES — a near-static recording massively aliased across the timeline, the
 * broken-screenrecord case) or (b) it has fewer unique frames than the per-step screenshots it
 * would replace. `uniqueFrames` null means a legacy sheet with no dedup data — conservatively not
 * degenerate.
 */
export function isSpriteDegenerate(
  uniqueFrames: number | null,
  totalFrames: number,
  stepScreenshotCount: number,
): boolean {
  if (uniqueFrames == null) return false;
  if (uniqueFrames >= MIN_USEFUL_UNIQUE_FRAMES) return false;
  return totalFrames >= MIN_ALIASING_TOTAL_FRAMES || uniqueFrames < stepScreenshotCount;
}

/**
 * A re-stamped sprite is degenerate when a single physical frame dominates its logical timeline:
 * the extractor's uniform-rate GUESS parked one frame across most of the run, so playing it as
 * video would show a frozen frame at a fabricated speed. Only fires when `restamped` is true —
 * a trusted wall-clock capture with the same distribution is a genuinely static period and is
 * kept. Gated on MIN_ALIASING_TOTAL_FRAMES so a short clip (where one frame naturally owns a
 * larger share) can't trip it.
 */
export function isRestampedSpriteDominatedBySingleFrame(
  restamped: boolean,
  frameMap: number[] | null,
  totalFrames: number,
): boolean {
  if (!restamped) return false;
  if (!frameMap || frameMap.length === 0) return false;
  if (totalFrames < MIN_ALIASING_TOTAL_FRAMES) return false;
  const counts = new Map<number, number>();
  let dominant = 0;
  for (const physical of frameMap) {
    const c = (counts.get(physical) || 0) + 1;
    counts.set(physical, c);
    if (c > dominant) dominant = c;
  }
  return dominant / frameMap.length >= RESTAMPED_SINGLE_FRAME_DOMINANCE_FRAC;
}

/** Why a sprite must not play as a video timeline (consumers fall back to step screenshots). */
export type SpriteRejection = "multiSheet" | "degenerate" | "restampedDominated";

/**
 * The single acceptance verdict: first rejection that applies, or null when the sprite is safe.
 *
 * `supportsMultiSheet` declares whether the consumer can render frames across multiple sheet
 * files (the interactive report can; WasmReport and the Compose frame caches only ever load one
 * image, so they keep the default). Even a capable consumer rejects a multi-sheet sprite without
 * `uniqueFrames` — the last sheet's geometry (spriteSheetRows) is underivable without it, and the
 * extractor always writes it alongside `sheets`.
 */
export function spriteRejectionReason(
  meta: SpriteSheetMeta,
  stepScreenshotCount: number,
  supportsMultiSheet: boolean = false,
): SpriteRejection | null {
  if (meta.sheets > 1 && (!supportsMultiSheet || meta.uniqueFrames == null)) return "multiSheet";
  if (isSpriteDegenerate(meta.uniqueFrames, meta.frames, stepScreenshotCount)) return "degenerate";
  if (isRestampedSpriteDominatedBySingleFrame(meta.restamped, meta.frameMap, meta.frames)) {
    return "restampedDominated";
  }
  return null;
}
