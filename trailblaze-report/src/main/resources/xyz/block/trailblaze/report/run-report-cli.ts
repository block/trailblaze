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
import { existsSync, readFileSync, readdirSync, statSync, writeFileSync } from "fs";
import { join } from "path";
import { gzipSync } from "zlib";
import { buildEventStream, resolveFormatterModule } from "./run-report-events";

/** The input JSON RunReportGenerator writes (one entry per session in the report). */
interface DriverInput {
  generatedAt?: string;
  /** File names of event-formatter modules staged beside this driver (see run-report-events.ts). */
  formatters?: string[];
  sessions?: Array<{
    meta?: RunMeta;
    recordingYaml?: string | null;
    originalYaml?: string | null;
    sessionDir: string;
    logs?: TrailblazeLogRecord[];
  }>;
}

const require = createRequire(import.meta.url);
// The renderer is loaded at runtime from the sibling transpiled artifact; assert its API surface
// here so this driver typechecks against the same contract the viewer implements.
const core = require("./run-report-core.js") as {
  extractTrace(logs: TrailblazeLogRecord[]): RawTraceRow[];
  extractLlmLogs(logs: TrailblazeLogRecord[]): RawLlmRow[];
  buildMultiReportHtml(args: { generatedAt?: string; sessions: SessionInput[] }): string;
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

function screenshotDataUri(sessionDir: string, file: string): string | null {
  return dataUri(join(sessionDir, file));
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
// Event payloads are embedded IN FULL (no last-N window, no preview truncation — see
// run-report-events.ts), so stream size is managed here instead: a per-file read cap and a loud
// per-session total budget bound the worst case, and anything past a small inline threshold is
// embedded gzipped (the viewer inflates lazily via DecompressionStream). A network stream that
// captures large response bodies is legitimately tens of MB on disk and gzips ~10-20x.
const MAX_EVENTS_FILE_BYTES = 64 * 1024 * 1024;
const MAX_EVENTS_TOTAL_CHARS = 256 * 1024 * 1024;
// Below this, embed plain JSON: a small events payload stays greppable in the HTML and skips the
// async inflate; only genuinely heavy sessions pay for compression.
const EVENTS_INLINE_MAX_CHARS = 1024 * 1024;

function readEvents(sessionDir: string, formatters: EventStreamFormatter[]): EventStream[] | null {
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
        const stream = buildEventStream(file, readFileSync(path, "utf8").split("\n"), formatters);
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

/** Splits a session's streams into inline `events` vs compressed `eventsGz` at the threshold. */
function packEvents(streams: EventStream[] | null): { events: EventStream[] | null; eventsGz: string | null } {
  if (!streams) return { events: null, eventsGz: null };
  const json = JSON.stringify(streams);
  if (json.length <= EVENTS_INLINE_MAX_CHARS) return { events: streams, eventsGz: null };
  return { events: null, eventsGz: gzipSync(json).toString("base64") };
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
// Mirror WasmReport.isSpriteDegenerate: a sprite with very few unique frames that's either stretched
// across many logical frames OR has fewer unique frames than step screenshots is a broken-screenrecord
// artifact (a near-static "video"). The legacy report strips it and falls back to per-step
// screenshots; we do the same by hiding the Video tab.
const MIN_USEFUL_UNIQUE_FRAMES = 8;
const MIN_ALIASING_TOTAL_FRAMES = 60;
function isSpriteDegenerate(uniqueFrames: number, totalFrames: number, stepScreenshotCount: number): boolean {
  if (!uniqueFrames) return false;
  if (uniqueFrames >= MIN_USEFUL_UNIQUE_FRAMES) return false;
  return totalFrames >= MIN_ALIASING_TOTAL_FRAMES || uniqueFrames < stepScreenshotCount;
}

function readVideo(sessionDir: string, logs: TrailblazeLogRecord[], stepScreenshotCount: number): VideoInfo | null {
  try {
    const metaPath = join(sessionDir, "capture_metadata.json");
    if (!existsSync(metaPath)) return null;
    const artifacts: any[] = (JSON.parse(readFileSync(metaPath, "utf8")).artifacts) || [];
    const framesArt = artifacts.find((a) => a.type === "VIDEO_FRAMES");
    if (!framesArt) return null; // WASM also prefers VIDEO_FRAMES; raw-MP4-only sessions fall back to the screenshot timeline.

    let spritePath = join(sessionDir, "video_sprites.webp");
    if (!existsSync(spritePath)) spritePath = join(sessionDir, "video_sprites.jpg");
    const txtPath = join(sessionDir, "video_sprites.txt");
    if (!existsSync(spritePath) || !existsSync(txtPath)) return null;

    const info: Record<string, string> = {};
    for (const line of readFileSync(txtPath, "utf8").split("\n")) {
      const eq = line.indexOf("=");
      if (eq > 0) info[line.slice(0, eq).trim()] = line.slice(eq + 1).trim();
    }
    const num = (k: string, d: number): number => { const n = parseInt(info[k], 10); return Number.isNaN(n) ? d : n; };
    const fps = num("fps", 2) || 2;
    const frames = num("frames", 0);
    const columns = num("columns", 1) || 1;
    const rows = num("rows", 0);
    const frameHeight = num("height", 0);
    const uniqueFrames = num("uniqueFrames", 0);
    let frameMap = (info.frameMap || "").split(",").map((s) => parseInt(s, 10)).filter((n) => !Number.isNaN(n));
    // A sprite txt without a frameMap means no alias dedup ran: logical frame N is physical frame N.
    // WasmReport tolerates this with an identity map (`frameMap ?: i`); mirror that instead of
    // dropping the whole video.
    if (!frameMap.length) frameMap = Array.from({ length: frames }, (_, i) => i);
    if (!frames || !rows || !frameHeight) return null;
    // Suppress degenerate sprites (broken screenrecord) so the timeline uses per-step screenshots,
    // matching the legacy report.
    if (isSpriteDegenerate(uniqueFrames, frames, stepScreenshotCount)) return null;

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

    const sprite = dataUri(spritePath);
    if (!sprite) return null;
    return { sprite, fps, frames, columns, rows, frameHeight, frameMap, startFrame, endFrame, startMs };
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
  const input: DriverInput = JSON.parse(readFileSync(inputPath, "utf8"));
  const formatters = loadFormatters(input.formatters || []);
  const sessions: SessionInput[] = (input.sessions || []).map((s) => {
    const logs = s.logs || [];
    const trace = core.extractTrace(logs);
    const llmLogs = core.extractLlmLogs(logs);
    // Inline only the screenshots the timeline actually references (deduped), mirroring the
    // in-app Share path (share-export.jsx#collectScreenshots).
    const files = [...new Set(trace.map((t) => t.screenshotFile).filter(Boolean))] as string[];
    const shots: Record<string, string> = {};
    for (const f of files) {
      const uri = screenshotDataUri(s.sessionDir, f);
      if (uri) shots[f] = uri;
    }
    const { events, eventsGz } = packEvents(readEvents(s.sessionDir, formatters));
    return {
      meta: s.meta || {},
      trace,
      llmLogs,
      shots,
      recordingYaml: s.recordingYaml || null,
      originalYaml: s.originalYaml || null,
      deviceLog: readDeviceLog(s.sessionDir),
      network: readNetworkLog(s.sessionDir),
      events,
      eventsGz,
      video: readVideo(s.sessionDir, logs, files.length),
    };
  });

  const html = core.buildMultiReportHtml({ generatedAt: input.generatedAt || "", sessions });
  writeFileSync(outputPath, html);
}

main();
