// Entry point for the embedded report viewer bundle: the bun bundler builds this file (plus
// everything it transitively imports) into the self-executing IIFE that buildMultiReportHtml
// embeds as the exported report's <script> (see run-report-viewer-bundle.macro.ts).
import { RUN_REPORT_VIEWER } from './run-report-viewer';

RUN_REPORT_VIEWER();
