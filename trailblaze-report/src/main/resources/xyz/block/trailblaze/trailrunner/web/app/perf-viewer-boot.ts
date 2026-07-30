// Entry point for the embedded performance-analysis viewer bundle: the bun bundler builds this
// file (plus everything it transitively imports) into the self-executing IIFE that
// buildPerfReportHtml embeds as the exported report's <script> (see perf-viewer-bundle.macro.ts).
import { PERF_VIEWER } from './perf-viewer';

PERF_VIEWER();
