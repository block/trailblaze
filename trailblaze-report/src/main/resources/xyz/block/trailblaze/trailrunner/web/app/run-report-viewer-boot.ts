// Entry point for the embedded report viewer bundle: the bun bundler builds this file (plus
// everything it transitively imports) into the self-executing IIFE that buildMultiReportHtml
// embeds as the exported report's <script> (see run-report-viewer-bundle.macro.ts).
import { RUN_REPORT_VIEWER } from './run-report-viewer';

// The one thing the viewer shell needs from this bundle: a way to start the viewer once it has put a
// payload in place. Deliberately just this handoff — the shell also needs the log-derivation and
// payload-shaping functions, but importing them HERE pulled their whole run-report-extract closure
// into the boot entry (+18.7 KB on the bundle every exported report embeds, for functions a report
// generated ahead of time never calls). The shell's own bundle imports them directly instead, so the
// cost lands only on the one document that uses them.
if (typeof window !== 'undefined') window.__TB_BOOT_REPORT__ = () => RUN_REPORT_VIEWER();

// A shell document starts with no payload: booting the viewer there would render an empty report over
// the loader chrome. It boots on the shell's terms instead, once an archive is in place (the shell
// clears this marker first). Every other document — every exported report — boots exactly as before.
// Probed defensively for the same reason the viewer tolerates a missing rAF: this bundle also runs in
// in-app reuse and in the fake-DOM harness the self-containment tests evaluate it under, neither of
// which is a full document. A real browser always answers, so a shell is never mistaken for a report.
const documentElement = typeof document !== 'undefined' ? document.documentElement : null;
if (!documentElement || !documentElement.hasAttribute('data-tb-shell')) RUN_REPORT_VIEWER();
