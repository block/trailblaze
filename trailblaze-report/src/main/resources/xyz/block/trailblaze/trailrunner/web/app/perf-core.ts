// Environment-agnostic core for the performance-analysis report — the sibling of
// run-report-core.ts (see its header for the full pattern rationale).
//
// Entry module for a BUNDLED plain-JS artifact: the Gradle task `bundlePerfReportCore`
// (:trailblaze-report) emits `perf-core.js` from this module graph via `bun build --format=iife`
// into the module's generated JAR resources — the .ts sources are NOT packaged. The bun driver
// (perf-report-cli.ts) loads the emitted .js via `require()`: the Gradle task appends a one-line
// CommonJS footer that republishes the exports from the `__TRAILBLAZE_PERF_REPORT_CORE__` global
// set below. `require()` of this TS source (bun test) gets the ordinary ESM re-exports.
import { bottomUpAggregate, extractPerfSession } from './perf-extract';
import { buildPerfReportHtml } from './perf-html';
import { PERF_REPORT_CSS } from './perf-css';
import { PERF_VIEWER } from './perf-viewer';

const PERF_REPORT_EXPORTS = {
  extractPerfSession, bottomUpAggregate, buildPerfReportHtml, PERF_REPORT_CSS, PERF_VIEWER,
};
(globalThis as Record<string, unknown>).__TRAILBLAZE_PERF_REPORT_CORE__ = PERF_REPORT_EXPORTS;

export { extractPerfSession, bottomUpAggregate, buildPerfReportHtml, PERF_REPORT_CSS, PERF_VIEWER };
