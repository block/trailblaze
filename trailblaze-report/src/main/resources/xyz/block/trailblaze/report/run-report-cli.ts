// Headless generator for the interactive Trailblaze run report. This is the CLI/CI counterpart to
// the in-app "Share as HTML" button: it reuses the SAME extraction + renderer (run-report-core.js)
// so the file a CI run emits is byte-for-byte the same artifact a user would Share from Trail Runner.
//
// TypeScript run directly by bun (no transpile step for this file — bun strips types in memory).
// Types come from the ambient run-report-types.d.ts, compiled together via the tsconfig.json at
// xyz/block/trailblaze/ (`tsc --noEmit` gate in pr_typescript_unit_tests.sh).
//
// The Kotlin side (RunReportGenerator) copies this file and run-report-core.js (the transpiled
// artifact of run-report-core.ts) into a temp dir, writes an input JSON describing the run(s),
// then invokes:  bun run-report-cli.ts <input.json> <output.html>
//
// `logs` is the verbatim array of a session's Trailblaze log records (the same JSON the daemon serves
// to the web app at /trailrunner/api/session/{id}/logs). `sessionDir` is where screenshots live.
import { createRequire } from "module";
import { spawnSync } from "child_process";
import { existsSync, readFileSync, readdirSync, statSync, writeFileSync } from "fs";
import { basename, join } from "path";
import { gzipSync } from "zlib";
import { buildEventStream, resolveFormatterModule } from "./run-report-events";
import { parseSpriteMetadata, resolvedFrameMap, spriteRejectionReason, spriteSheetRows } from "./run-report-sprites";

/** The input JSON RunReportGenerator writes (one entry per session in the report). */
interface DriverInput {
  generatedAt?: string;
  /** Canonical hosted URL baked into the report so its Copy link works from any serving location. */
  shareUrl?: string;
  /** File names of event-formatter modules staged beside this driver (see run-report-events.ts). */
  formatters?: string[];
  /**
   * When true (the --full-report-payloads CLI flag), formatters embed full event payloads even
   * for passed sessions — the report size budgets are bypassed entirely.
   */
  fullEventPayloads?: boolean;
  /**
   * File name (beside this driver) of the Kotlin/JS selector-engine bundle RunReportGenerator
   * staged from its JAR resources, when present. Embedded once per report — gz+base64 past the
   * shared inline threshold — so the UI Inspector can compute selector suggestions offline.
   */
  selectorEngine?: string;
  /**
   * When set, local screenshots and video sprite sheets are REFERENCED at
   * `<imageBaseUrl><sessionId>/<file>` instead of base64-embedded (see [localShotUrl]). Absent —
   * the default — embeds every image, which is what makes a report a portable single file.
   *
   * Screenshots that are ALREADY absolute URLs (a device-farm leg — see [isRemoteScreenshot]) pass
   * through unchanged either way; this switch governs only the images that live on disk.
   */
  imageBaseUrl?: string | null;
  sessions?: Array<{
    meta?: RunMeta;
    recordingYaml?: string | null;
    originalYaml?: string | null;
    sessionDir: string;
    logs?: TrailblazeLogRecord[];
  }>;
}

const require = createRequire(import.meta.url);
// The renderer is loaded at runtime (in main) from the sibling transpiled artifact; assert its API
// surface here so this driver typechecks against the same contract the viewer implements. Loaded
// lazily so the test suite can import this module's exported helpers without staging the artifact.
type ReportCore = {
  extractTrace(logs: TrailblazeLogRecord[]): RawTraceRow[];
  extractLlmLogs(logs: TrailblazeLogRecord[]): RawLlmRow[];
  extractLlmTranscripts(llmLogs: RawLlmRow[]): LlmTranscripts | null;
  traceHierarchies(trace: RawTraceRow[], sessionPassed: boolean): Record<string, unknown> | null;
  isSelectorAnalyzableTree(hierarchy: unknown): boolean;
  buildMultiReportHtml(args: { generatedAt?: string; shareUrl?: string; sessions: SessionInput[]; selectorEngine?: SelectorEnginePayload | null }): string;
};

const MIME = { png: "image/png", jpg: "image/jpeg", jpeg: "image/jpeg", webp: "image/webp", gif: "image/gif" };
const MAX_LOG_BYTES = 5 * 1024 * 1024; // mirror WasmReport: skip device/network logs over 5MB
const MAX_LOG_LINES = 5000; // mirror WasmReport: keep only the tail

function dataUri(path: string): string | null {
  try {
    const bytes = readFileSync(path);
    const ext = (path.split(".").pop() || "").toLowerCase();
    const mime = MIME[ext] || "image/png";
    return `data:${mime};base64,${bytes.toString("base64")}`;
  } catch {
    return null;
  }
}

// Step screenshots dominate report size: drivers write them at full device resolution and iOS
// emits raw PNGs (200-500KB each), so a long session inlines tens of MB of base64. The report
// renders them in a ~360px-wide timeline column (zoomable), so re-encoding to JPEG capped at
// SCREENSHOT_MAX_HEIGHT keeps them visually identical in context at ~10-20x fewer bytes.
// ffmpeg is Trailblaze's documented media dependency (Hermit-pinned; the capture pipeline already
// assumes it on PATH) — but external report generation must not require it, so every failure
// (no ffmpeg, decode error, result not actually smaller) falls back to inlining the original
// bytes. Screenshots at or under SCREENSHOT_RECOMPRESS_MIN_BYTES (already-compressed WEBPs from
// the Android path are ~30-50KB) skip the subprocess entirely.
const SCREENSHOT_MAX_HEIGHT = 900;
const SCREENSHOT_JPEG_QSCALE = 5; // mjpeg qscale 2(best)..31; 5 ≈ libjpeg quality ~80
const SCREENSHOT_RECOMPRESS_MIN_BYTES = 100 * 1024;

const ffmpegAvailability = new Map<string, boolean>();
function hasFfmpeg(ffmpeg: string): boolean {
  let available = ffmpegAvailability.get(ffmpeg);
  if (available == null) {
    try {
      available = spawnSync(ffmpeg, ["-version"], { stdio: "ignore" }).status === 0;
    } catch {
      available = false;
    }
    ffmpegAvailability.set(ffmpeg, available);
  }
  return available;
}

/** Re-encode one screenshot to a bounded JPEG; null on any failure (caller falls back). */
function recompressScreenshot(path: string, originalBytes: number, ffmpeg: string): Buffer | null {
  if (!hasFfmpeg(ffmpeg)) return null;
  // scale=-2:min(ih,MAX): downscale to MAX px tall (keeping aspect, even width for the encoder);
  // an already-short screenshot passes through at its own height. The JPEG is written to stdout
  // (-f mjpeg pipe:1), which spawnSync captures - no temp files.
  const res = spawnSync(ffmpeg, [
    "-i", path,
    "-vf", `scale=-2:'min(ih,${SCREENSHOT_MAX_HEIGHT})'`,
    "-frames:v", "1", "-qscale:v", String(SCREENSHOT_JPEG_QSCALE),
    "-f", "mjpeg", "pipe:1",
  ], { stdio: ["ignore", "pipe", "ignore"], maxBuffer: 64 * 1024 * 1024 });
  if (res.status !== 0 || !res.stdout) return null;
  // A tiny/flat original (or one already smaller than its JPEG) keeps its original bytes.
  return res.stdout.length && res.stdout.length < originalBytes ? res.stdout : null;
}

/**
 * Device-farm legs don't ship step screenshots inside the session dir: the farm ingest rewrites
 * `screenshotFile` on driver/LLM logs to an absolute artifact URL and downloads only the
 * `final_screenshot` locally. Such a value is a URL, not a path under sessionDir, so it can't be
 * read off disk — it's handed to the viewer for the browser to load.
 */
export function isRemoteScreenshot(file: string): boolean {
  return /^https?:\/\//i.test(file);
}

/**
 * What the viewer interpolates into `src="…"` for a remote screenshot: the URL, reserialized.
 *
 * Deliberately NOT fetched and inlined here. `screenshotFile` comes from session-log JSON, so
 * fetching it would let a crafted or corrupted log point report generation at any host the
 * generating machine can reach and embed the response in the report — an exfiltration path out of
 * a CI job. Letting the browser load it keeps that surface where it already was: this mirrors
 * [StoryboardHtmlBuilder]'s handling of the same remote-URL case, which also emits the URL for
 * Chromium to fetch. The cost is that farm-leg screenshots need network at view time.
 *
 * Escaping here is load-bearing: the viewer interpolates a shot into the attribute WITHOUT escaping
 * (safe for the base64 data URIs a local screenshot produces), so a URL carrying a `"` would
 * otherwise break out of it. Reserializing through the URL parser rather than `encodeURI`, because
 * a farm URL already carries `%xx` escapes — the artifact key is a percent-encoded path — and
 * `encodeURI` re-encodes the `%` itself, turning `%2F` into `%252F` and making the host reject the
 * request. The parser leaves valid escapes alone while still encoding what would close the
 * attribute.
 */
export function remoteShotValue(url: string): string {
  try {
    return new URL(url).href;
  } catch {
    // Not parseable as a URL, so it can't be normalized — it still must not close the attribute.
    return url.replace(/"/g, "%22");
  }
}

/**
 * What the viewer renders for a LOCAL screenshot or sprite sheet when the report links images
 * instead of embedding them (`imageBaseUrl` in the driver input — see [DriverInput.imageBaseUrl]).
 *
 * The emitted key is `<sessionId>/<file>`, the same key the legacy WASM report hands its
 * `window.transformImageUrl` hook and the same one the in-app Share path already fetches
 * (`share-export.tsx` builds this exact `/static/<enc sessionId>/<enc file>` shape), so every
 * hosting environment that already serves that layout keeps working unchanged:
 *
 * - the daemon passes `/static/`, which its `staticFiles("/static", logsRepo.logsDir)` route serves;
 * - CI passes `""`, leaving a document-relative reference that the browser resolves against the
 *   report's own artifact URL — arithmetic identical to what a hosted-report `transformImageUrl`
 *   hook computes for the WASM report, against the same `<sessionId>/<file>` artifact paths the
 *   report step uploads.
 *
 * ESCAPING IS LOAD-BEARING, in two different contexts. The viewer interpolates a screenshot into
 * `src="…"` and a sprite sheet into `background-image:url('…')` — both WITHOUT escaping, because
 * both were only ever fed base64 data URIs before. So the value must be unable to close EITHER
 * delimiter: percent-encoding each path segment handles `"`, and `'` is encoded explicitly because
 * `encodeURIComponent` leaves it intact. Segments are encoded individually rather than encoding the
 * whole key, so a name that itself contains `/` still yields a real path instead of `%2F` (which no
 * static file route would resolve).
 */
export function localShotUrl(baseUrl: string, sessionId: string, file: string): string {
  // A base is a URL prefix, so it must end in `/` — tolerate a caller that omits it rather than
  // silently emitting `/staticmy_session/shot.webp`.
  const base = baseUrl === "" || baseUrl.endsWith("/") ? baseUrl : `${baseUrl}/`;
  return `${base}${encodePathSegments(sessionId)}/${encodePathSegments(file)}`;
}

/** Percent-encodes each `/`-separated segment, preserving the separators. See [localShotUrl]. */
function encodePathSegments(value: string): string {
  return value
    .split("/")
    .map((segment) => encodeURIComponent(segment).replace(/'/g, "%27"))
    .join("/");
}

export function screenshotDataUri(sessionDir: string, file: string, ffmpeg: string = "ffmpeg"): string | null {
  const path = join(sessionDir, file);
  try {
    const size = statSync(path).size;
    if (size > SCREENSHOT_RECOMPRESS_MIN_BYTES) {
      const jpeg = recompressScreenshot(path, size, ffmpeg);
      if (jpeg) return `data:image/jpeg;base64,${jpeg.toString("base64")}`;
    }
  } catch {
    return null;
  }
  return dataUri(path);
}

// Device log (logcat). Matches WasmReport/LogcatParser: device.log, or any file whose name contains
// "logcat" or "system_log". Skip empty/oversized; keep the last MAX_LOG_LINES lines.
function readDeviceLog(sessionDir: string): string | null {
  try {
    const name = readdirSync(sessionDir).find((n) => {
      const l = n.toLowerCase();
      return l === "device.log" || l.includes("logcat") || l.includes("system_log");
    });
    if (!name) return null;
    const path = join(sessionDir, name);
    const size = statSync(path).size;
    if (size === 0 || size > MAX_LOG_BYTES) return null;
    return readFileSync(path, "utf8").split("\n").slice(-MAX_LOG_LINES).join("\n");
  } catch {
    return null;
  }
}

// Network log (network.ndjson). One NetworkEvent JSON object per line; keep the fields the viewer
// renders. Skip empty/oversized; keep the last MAX_LOG_LINES events.
function readNetworkLog(sessionDir: string): NetworkEvent[] | null {
  try {
    const path = join(sessionDir, "network.ndjson");
    if (!existsSync(path) || statSync(path).size > MAX_LOG_BYTES) return null;
    const lines = readFileSync(path, "utf8").split("\n").filter((l) => l.trim()).slice(-MAX_LOG_LINES);
    const out: NetworkEvent[] = [];
    for (const line of lines) {
      try {
        const e = JSON.parse(line);
        out.push({ method: e.method || "", statusCode: e.statusCode ?? null, durationMs: e.durationMs ?? null, urlPath: e.urlPath || e.url || "", phase: e.phase || "" });
      } catch { /* skip malformed line */ }
    }
    return out.length ? out : null;
  } catch {
    return null;
  }
}

// Generic session events (`<sessionDir>/events/<name>.ndjson`) — the producer-agnostic
// artifact `xyz.block.trailblaze.events.SessionEvents` writes: any producer drops NDJSON streams
// here and they surface without report-side per-producer code. The line-level decode, the optional
// per-stream formatter pass, and every payload budget live in run-report-events.ts (shared with its
// raw-line tests); this wrapper only owns the filesystem walk.
//
// Event payloads embed in full by default (no last-N window, no preview truncation — see
// run-report-events.ts), bounded here by a per-file read cap and a loud per-session total budget;
// anything past a small inline threshold is embedded gzipped (the viewer inflates lazily via
// DecompressionStream). Formatters additionally receive the session outcome and may size-budget
// raw payloads of PASSED sessions (grep REPORT_SIZE_BUDGET); sessions that didn't pass always
// embed full payloads, and `--full-report-payloads` opts passed sessions out of the budgets too
// (see formatterContext). A network stream that captures large response bodies is legitimately
// tens of MB on disk and gzips ~10-20x.
const MAX_EVENTS_FILE_BYTES = 64 * 1024 * 1024;
const MAX_EVENTS_TOTAL_CHARS = 256 * 1024 * 1024;
// Below this, embed plain JSON: a small events payload stays greppable in the HTML and skips the
// async inflate; only genuinely heavy sessions pay for compression.
const EVENTS_INLINE_MAX_CHARS = 1024 * 1024;

/**
 * Formatter size budgets key off the session outcome: only an affirmative "passed" (see
 * RunReportGenerator.statusLabel) lets a formatter budget raw payloads — failed / cancelled /
 * unknown sessions keep full evidence. `fullEventPayloads` (the `--full-report-payloads` CLI
 * flag) turns budgeting off entirely by presenting every session as not-passed.
 */
export function formatterContext(status: string | undefined, fullEventPayloads: boolean): FormatterContext {
  return { sessionPassed: !fullEventPayloads && status === "passed" };
}

function readEvents(
  sessionDir: string,
  formatters: EventStreamFormatter[],
  ctx: FormatterContext,
): EventStream[] | null {
  try {
    const dir = join(sessionDir, "events");
    if (!existsSync(dir) || !statSync(dir).isDirectory()) return null;
    const streams: EventStream[] = [];
    let totalChars = 0;
    for (const file of readdirSync(dir).filter((n) => n.endsWith(".ndjson")).sort()) {
      try {
        const path = join(dir, file);
        if (statSync(path).size > MAX_EVENTS_FILE_BYTES) {
          console.error(`events: skipping ${file} — exceeds the ${MAX_EVENTS_FILE_BYTES / 1024 / 1024}MB per-stream cap`);
          continue;
        }
        const stream = buildEventStream(file, readFileSync(path, "utf8").split("\n"), formatters, ctx);
        if (!stream) continue;
        totalChars += JSON.stringify(stream).length;
        if (totalChars > MAX_EVENTS_TOTAL_CHARS) {
          console.error(`events: skipping ${file} and later streams — session events exceed the ${MAX_EVENTS_TOTAL_CHARS / 1024 / 1024}MB total budget`);
          break;
        }
        streams.push(stream);
      } catch { /* skip this stream */ }
    }
    return streams.length ? streams : null;
  } catch {
    return null;
  }
}

// Device/network logs ride the same gzip+base64 transport as events (the viewer inflates lazily
// via DecompressionStream when the tab first needs them). Logs are the largest field in many
// reports (a capped logcat tail is still ~800KB of text, gzipping ~10x); below the threshold they
// stay plain so small logs remain greppable in the HTML and skip the async inflate.
const LOG_INLINE_MAX_CHARS = 64 * 1024;

/** Splits a payload into an inline value vs a gzip+base64 blob at `maxInlineChars`. */
function packGz<T>(value: T | null, encode: (value: T) => string, maxInlineChars: number): { inline: T | null; gz: string | null } {
  if (value == null) return { inline: null, gz: null };
  const text = encode(value);
  if (text.length <= maxInlineChars) return { inline: value, gz: null };
  return { inline: null, gz: gzipSync(text).toString("base64") };
}

/** Splits a session's streams into inline `events` vs compressed `eventsGz` at the threshold. */
function packEvents(streams: EventStream[] | null): { events: EventStream[] | null; eventsGz: string | null } {
  const { inline, gz } = packGz(streams, (s) => JSON.stringify(s), EVENTS_INLINE_MAX_CHARS);
  return { events: inline, eventsGz: gz };
}

/** Splits a device log into inline `deviceLog` vs compressed `deviceLogGz` at the threshold. */
export function packDeviceLog(text: string | null): { deviceLog: string | null; deviceLogGz: string | null } {
  const { inline, gz } = packGz(text, (t) => t, LOG_INLINE_MAX_CHARS);
  return { deviceLog: inline, deviceLogGz: gz };
}

/** Splits network events into inline `network` vs compressed `networkGz` at the threshold. */
export function packNetwork(events: NetworkEvent[] | null): { network: NetworkEvent[] | null; networkGz: string | null } {
  const { inline, gz } = packGz(events, (e) => JSON.stringify(e), LOG_INLINE_MAX_CHARS);
  return { network: inline, networkGz: gz };
}

/** Splits a session's LLM transcripts into inline `llmMessages` vs compressed `llmMessagesGz` at
 * the threshold. The transcripts arrive already pooled + image-stripped (extractLlmTranscripts). */
export function packLlmMessages(transcripts: LlmTranscripts | null): { llmMessages: LlmTranscripts | null; llmMessagesGz: string | null } {
  const { inline, gz } = packGz(transcripts, (t) => JSON.stringify(t), LOG_INLINE_MAX_CHARS);
  return { llmMessages: inline, llmMessagesGz: gz };
}

/**
 * Whether any session carries a hierarchy the selector engine can actually analyze — the gate for
 * embedding the engine at all. Presence of *some* hierarchy isn't enough: a session that only
 * logged the legacy `ViewHierarchyTreeNode` shape (agent/MCP-sampling captures) opens an inspector
 * that can never show a suggestion, so paying ~110 KB for it is pure weight. `isAnalyzable` is the
 * viewer's own `isSelectorAnalyzableTree` (passed in — this driver reaches shared logic through the
 * bundled core, never a re-implementation), so the gate and the UI agree by construction.
 */
export function anyAnalyzableHierarchy(
  liftedPerSession: Array<Record<string, unknown> | null>,
  isAnalyzable: (hierarchy: unknown) => boolean,
): boolean {
  return liftedPerSession.some((lifted) => lifted != null && Object.values(lifted).some(isAnalyzable));
}

/**
 * Splits the Kotlin/JS selector-engine bundle into the shared inline/gz transport (see packGz) for
 * buildMultiReportHtml's `selectorEngine` argument. The real bundle (~320 KB) always lands on the
 * gz side (~110 KB as base64); the inline branch exists only for threshold symmetry with every
 * other side-channel. Null in, null out — an absent bundle embeds nothing.
 */
export function packSelectorEngine(code: string | null): SelectorEnginePayload | null {
  if (!code) return null;
  const { inline, gz } = packGz(code, (t) => t, LOG_INLINE_MAX_CHARS);
  return { js: inline, gz };
}

// The staged engine file name comes from input.json; require the same plain path-safe shape as
// event formatters so a crafted input can't point report generation at an arbitrary file and
// embed its contents (the exfiltration concern remoteShotValue documents).
const SAFE_ENGINE_FILE_NAME = /^[A-Za-z0-9][A-Za-z0-9._-]*$/;

/** Read the staged selector-engine bundle, or null when unnamed/unsafe/unreadable. */
function readSelectorEngineSource(name: string | undefined): string | null {
  if (!name || !SAFE_ENGINE_FILE_NAME.test(name)) return null;
  try {
    return readFileSync(name, "utf8");
  } catch {
    return null;
  }
}

/**
 * Splits the per-step view-hierarchies map (lifted off the extracted trace by traceHierarchies in
 * run-report-extract — shared with the browser producers, so the size budget behaves identically
 * everywhere; grep REPORT_SIZE_BUDGET) into inline `hierarchies` vs compressed `hierarchiesGz` at
 * the threshold. Measured on real captured hierarchies: gzip alone is ~9x, ~6.5x end-to-end after
 * the base64 leg re-inflates it 4/3x — and the viewer inflates it only when an inspector is
 * opened. The shared inline threshold is well under one real hierarchy (median ~75 KB on Android
 * captures), so real sessions land on the gz side by design; the inline branch exists for the
 * deferred-parse split, not greppability. `hierarchyStepCount` is how many trace rows carried a
 * hierarchy, so a budget-trimmed session logs what was dropped.
 */
export function packHierarchies(
  lifted: Record<string, unknown> | null,
  hierarchyStepCount: number,
): { hierarchies: Record<string, unknown> | null; hierarchiesGz: string | null } {
  const kept = lifted ? Object.keys(lifted).length : 0;
  if (hierarchyStepCount > kept) {
    console.error(`hierarchies: report size budget kept ${kept} of ${hierarchyStepCount} step hierarchies on this session (passed sessions trim at 8 MB, every status caps at 64 MB; --full-report-payloads lifts the passed-session trim)`);
  }
  const { inline, gz } = packGz(kept ? lifted : null, (h) => JSON.stringify(h), LOG_INLINE_MAX_CHARS);
  return { hierarchies: inline, hierarchiesGz: gz };
}

// Event-formatter modules staged beside this driver by RunReportGenerator. A module that fails to
// load or doesn't export the EventStreamFormatter shape is skipped with a note — formatting is a
// rendering upgrade, never a reason to lose the report.
function loadFormatters(names: string[]): EventStreamFormatter[] {
  const formatters: EventStreamFormatter[] = [];
  for (const name of names) {
    try {
      const formatter = resolveFormatterModule(require(`./${name}`));
      if (formatter) formatters.push(formatter);
      else console.error(`skipping event formatter ${name}: not an EventStreamFormatter module`);
    } catch (e) {
      console.error(`skipping event formatter ${name}: ${e}`);
    }
  }
  return formatters;
}

// Video frames as a CSS sprite scrubber (parity with the old report's video tab, but pure-DOM — no
// ffmpeg). Reads capture_metadata.json (prefers the VIDEO_FRAMES artifact), the sprite sheet image,
// and video_sprites.txt layout, then trims the playable logical-frame range to the test window
// [first log, last log] the same way WasmReport does. The viewer reads the sprite's natural width to
// derive per-frame width and plays frames via background-position.
//
// Metadata parsing and the acceptance rules (degenerate sprite, restamped-and-dominated sprite,
// multi-sheet) live in run-report-sprites.ts — the shared contract this driver and the legacy
// WasmReport both apply, locked cross-language by sprite-metadata-parity-fixtures.json. A rejected
// sprite hides the Video tab so the timeline falls back to per-step screenshots.
/**
 * @param spriteValue what to put in each sheet's `uri` — the base64 data URI by default, or the
 *   linked-image URL when the report references images instead of embedding them. Sheets are
 *   ordinary files in the session dir (`video_sprites*.webp`), served by the same two hosts as the
 *   step screenshots, so they follow the same switch — and they are the largest single blob a
 *   session contributes.
 */
export function readVideo(
  sessionDir: string,
  logs: TrailblazeLogRecord[],
  stepScreenshotCount: number,
  spriteValue: (path: string) => string | null = dataUri,
): VideoInfo | null {
  try {
    const metaPath = join(sessionDir, "capture_metadata.json");
    if (!existsSync(metaPath)) return null;
    const artifacts: any[] = (JSON.parse(readFileSync(metaPath, "utf8")).artifacts) || [];
    const framesArt = artifacts.find((a) => a.type === "VIDEO_FRAMES");
    if (!framesArt) return null; // WASM also prefers VIDEO_FRAMES; raw-MP4-only sessions fall back to the screenshot timeline.

    const txtPath = join(sessionDir, "video_sprites.txt");
    if (!existsSync(txtPath)) return null;

    const meta = parseSpriteMetadata(readFileSync(txtPath, "utf8"));
    if (!meta) return null;
    // This viewer plays multi-sheet sprites (it swaps background-image per sheet), so opt in.
    const rejection = spriteRejectionReason(meta, stepScreenshotCount, true);
    if (rejection) {
      console.error(
        `video: skipping sprite in ${sessionDir} (${rejection}: ${meta.uniqueFrames} unique of ` +
          `${meta.frames} total frames, restamped=${meta.restamped}); timeline will use per-step screenshots`,
      );
      return null;
    }

    // A single sheet keeps the plain filename (legacy sheets may be .jpg); multiple sheets are
    // numbered video_sprites_<k>.webp and every one must be present.
    const spritePaths: string[] = [];
    if (meta.sheets <= 1) {
      let spritePath = join(sessionDir, "video_sprites.webp");
      if (!existsSync(spritePath)) spritePath = join(sessionDir, "video_sprites.jpg");
      if (!existsSync(spritePath)) return null;
      spritePaths.push(spritePath);
    } else {
      for (let k = 0; k < meta.sheets; k++) {
        const spritePath = join(sessionDir, `video_sprites_${k}.webp`);
        if (!existsSync(spritePath)) return null;
        spritePaths.push(spritePath);
      }
    }
    const { fps, frames, columns, rows, height: frameHeight, frameWidth } = meta;
    const frameMap = resolvedFrameMap(meta);

    // Trim playable range to the test window, mirroring WasmReport.extractFromSpriteSheet.
    let startFrame = 0;
    let endFrame = frames - 1;
    const startMs = framesArt.startTimestampMs ?? null;
    const endMs = framesArt.endTimestampMs ?? null;
    const ts = logs.map((l) => (l.timestamp ? Date.parse(l.timestamp) : NaN)).filter((n) => !Number.isNaN(n)).sort((a, b) => a - b);
    if (startMs != null && ts.length) {
      const trimStart = Math.max(startMs, ts[0]);
      const trimEnd = endMs != null ? Math.min(endMs, ts[ts.length - 1]) : ts[ts.length - 1];
      const s = Math.max(0, Math.floor(((trimStart - startMs) * fps) / 1000));
      const e = Math.min(frames - 1, Math.floor(((trimEnd - startMs) * fps) / 1000));
      if (e >= s) { startFrame = s; endFrame = e; }
    }

    const sprites: Array<{ uri: string; rows: number }> = [];
    for (let k = 0; k < spritePaths.length; k++) {
      const uri = spriteValue(spritePaths[k]);
      if (!uri) return null;
      sprites.push({ uri, rows: spriteSheetRows(meta, k) });
    }
    return { sprites, fps, frames, columns, rows, frameHeight, frameWidth, frameMap, startFrame, endFrame, startMs };
  } catch {
    return null;
  }
}

function main(): void {
  const [, , inputPath, outputPath] = process.argv;
  if (!inputPath || !outputPath) {
    console.error("usage: bun run-report-cli.ts <input.json> <output.html>");
    process.exit(2);
  }
  const core = require("./run-report-core.js") as ReportCore;
  const input: DriverInput = JSON.parse(readFileSync(inputPath, "utf8"));
  const formatters = loadFormatters(input.formatters || []);
  // Each session's lifted per-step hierarchies, kept for the selector-engine embed gate below.
  const liftedHierarchies: Array<Record<string, unknown> | null> = [];
  const imageBaseUrl = input.imageBaseUrl ?? null;
  const sessions: SessionInput[] = (input.sessions || []).map((s) => {
    const logs = s.logs || [];
    const trace = core.extractTrace(logs);
    const llmLogs = core.extractLlmLogs(logs);
    // Inline only the screenshots the timeline actually references (deduped), mirroring the
    // in-app Share path (share-export.jsx#collectScreenshots).
    const files = [...new Set(trace.map((t) => t.screenshotFile).filter(Boolean))] as string[];
    // Session id == the session dir's own name, the segment both hosts address images under.
    const sessionId = basename(s.sessionDir);
    // In linked-image mode a file that isn't on disk is dropped rather than referenced, matching
    // what embedding does with the same file: a shot the report can't produce is one the viewer
    // renders without, not a broken <img>. NOTE: this is why any caller that deletes the image
    // files must do so AFTER generation — see the CI report step's ordering gate.
    const linkedShot = (file: string) =>
      imageBaseUrl != null && existsSync(join(s.sessionDir, file))
        ? localShotUrl(imageBaseUrl, sessionId, file)
        : null;
    const shots: Record<string, string> = {};
    for (const f of files) {
      if (isRemoteScreenshot(f)) {
        shots[f] = remoteShotValue(f);
        continue;
      }
      const uri = linkedShot(f) ?? screenshotDataUri(s.sessionDir, f);
      if (uri) shots[f] = uri;
    }
    const ctx = formatterContext(s.meta?.status, input.fullEventPayloads === true);
    const { events, eventsGz } = packEvents(readEvents(s.sessionDir, formatters, ctx));
    const { deviceLog, deviceLogGz } = packDeviceLog(readDeviceLog(s.sessionDir));
    const { network, networkGz } = packNetwork(readNetworkLog(s.sessionDir));
    const { llmMessages, llmMessagesGz } = packLlmMessages(core.extractLlmTranscripts(llmLogs));
    const lifted = core.traceHierarchies(trace, ctx.sessionPassed);
    liftedHierarchies.push(lifted);
    const { hierarchies, hierarchiesGz } = packHierarchies(
      lifted,
      trace.filter((t) => t.viewHierarchy != null).length,
    );
    return {
      meta: s.meta || {},
      trace,
      llmLogs,
      shots,
      recordingYaml: s.recordingYaml || null,
      originalYaml: s.originalYaml || null,
      deviceLog,
      deviceLogGz,
      network,
      networkGz,
      events,
      eventsGz,
      llmMessages,
      llmMessagesGz,
      hierarchies,
      hierarchiesGz,
      video: readVideo(s.sessionDir, logs, files.length, (path) => linkedShot(basename(path)) ?? dataUri(path)),
    };
  });

  // Embed the selector engine only when some session carries a hierarchy it can analyze — an
  // inspector-less (or legacy-tree-only) report gets no engine bytes at all.
  const selectorEngine = anyAnalyzableHierarchy(liftedHierarchies, core.isSelectorAnalyzableTree)
    ? packSelectorEngine(readSelectorEngineSource(input.selectorEngine))
    : null;
  const html = core.buildMultiReportHtml({ generatedAt: input.generatedAt || "", ...(input.shareUrl ? { shareUrl: input.shareUrl } : {}), ...(selectorEngine ? { selectorEngine } : {}), sessions });
  writeFileSync(outputPath, html);
}

// Entry-point guard so the test suite can import the exported helpers without running the CLI.
if (import.meta.main) main();
