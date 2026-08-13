// Bun MACRO (imported `with { type: 'macro' }` from run-report-html.ts): the exact text of
// zip-report-core.js, inlined at TRANSPILE time so buildViewerShellHtml can embed it in the viewer
// shell without the shell needing a second network request.
//
// zip-report-core.js is already a browser-ready classic-script IIFE that publishes window.TbZipReport,
// so this is a verbatim read — no bundling step (contrast run-report-viewer-bundle.macro.ts, which
// must bun-build a module graph). Reading it here rather than copying the file next to the shell is
// what keeps ONE copy of the zip pipeline: the JAR resource Trail Runner serves and the text embedded
// in the shell come from the same bytes.
import { readFileSync } from 'fs';
import { fileURLToPath } from 'url';

export function embeddedZipReportCoreScript(): string {
  return readFileSync(fileURLToPath(new URL('./zip-report-core.js', import.meta.url)), 'utf8');
}
