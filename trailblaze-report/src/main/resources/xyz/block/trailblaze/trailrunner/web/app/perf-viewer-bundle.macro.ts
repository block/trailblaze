// Bun MACRO (imported `with { type: 'macro' }` from perf-html.ts): produces the standalone
// performance-analysis viewer script that buildPerfReportHtml embeds into every exported document.
// Same mechanism as run-report-viewer-bundle.macro.ts (see its header for the full rationale): it
// runs at TRANSPILE time and its return value is inlined as a string constant, so every consumer
// (bun driver, tests) carries the same prebuilt bundle and no runtime pays a bundling step.
//
// Determinism: cwd pinned to this directory, single fixed entrypoint, no minification, no
// sourcemaps, the Hermit-pinned bun (`process.execPath`).
import { spawnSync } from 'child_process';
import { fileURLToPath } from 'url';

export function embeddedPerfViewerScript(): string {
  const dir = fileURLToPath(new URL('.', import.meta.url));
  const res = spawnSync(
    process.execPath,
    ['build', 'perf-viewer-boot.ts', '--format=iife', '--target=browser'],
    { encoding: 'utf8', cwd: dir, maxBuffer: 64 * 1024 * 1024 },
  );
  if (res.status !== 0) {
    throw new Error(`perf viewer bundle build failed (exit ${res.status}): ${res.stderr}${res.error ? ` (${res.error.message})` : ''}`);
  }
  return res.stdout;
}
