// Headless generator for the Trailblaze performance-analysis report — the bun driver
// PerformanceAnalysisGenerator.kt invokes as a subprocess, mirroring run-report-cli.ts (see its
// header for the pattern). Much simpler than the run-report driver: the perf report embeds no
// screenshots, videos, or event streams — only the extracted per-session timing profile — so the
// input's verbatim log records are all it needs.
//
// The Kotlin side copies this file and perf-core.js (the transpiled artifact of perf-core.ts)
// into a temp dir, writes an input JSON describing the sessions, then invokes:
//   bun perf-report-cli.ts <input.json> <output.html>
import { createRequire } from "module";
import { readFileSync, writeFileSync } from "fs";

/** The input JSON PerformanceAnalysisGenerator writes (one entry per session in the report). */
interface PerfDriverInput {
  generatedAt?: string;
  sessions?: Array<{
    meta?: RunMeta;
    logs?: TrailblazeLogRecord[];
  }>;
}

const require = createRequire(import.meta.url);
// The core is loaded at runtime (in main) from the sibling transpiled artifact; assert its API
// surface here so this driver typechecks against the same contract the viewer implements.
type PerfCore = {
  extractPerfSession(logs: TrailblazeLogRecord[]): PerfSessionData | null;
  buildPerfReportHtml(args: { generatedAt?: string; sessions: PerfSessionPayload[] }): string;
};

function main(): void {
  const [, , inputPath, outputPath] = process.argv;
  if (!inputPath || !outputPath) {
    console.error("usage: bun perf-report-cli.ts <input.json> <output.html>");
    process.exit(2);
  }
  const core = require("./perf-core.js") as PerfCore;
  const input: PerfDriverInput = JSON.parse(readFileSync(inputPath, "utf8"));
  const sessions: PerfSessionPayload[] = [];
  for (const s of input.sessions || []) {
    const data = core.extractPerfSession(s.logs || []);
    // A session with no host-clock timestamps has nothing to profile; skip it rather than
    // rendering an empty timeline.
    if (data) sessions.push({ meta: s.meta || {}, data });
  }
  writeFileSync(outputPath, core.buildPerfReportHtml({ generatedAt: input.generatedAt || "", sessions }));
}

// Entry-point guard so the test suite can import this module without running the CLI.
if (import.meta.main) main();
