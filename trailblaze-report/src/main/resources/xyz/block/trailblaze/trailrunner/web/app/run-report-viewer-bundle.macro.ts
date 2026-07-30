// Bun MACRO (imported `with { type: 'macro' }` from run-report-html.ts): produces the standalone
// viewer script that buildMultiReportHtml embeds into every exported report HTML. It runs at
// TRANSPILE time — during the Gradle `bundleRunReportCore` bun build AND under plain `bun test` /
// `bun run` of the TS sources — and its return value is inlined as a string constant, so every
// consumer (browser classic script, bun driver, tests) carries the same prebuilt bundle and no
// runtime ever pays a bundling step. This replaces the old Function.prototype.toString()
// serialization of RUN_REPORT_VIEWER + a hand-maintained VIEWER_HELPERS registry (where a helper
// missing from the registry compiled fine but ReferenceError'd at first render of the exported
// file).
//
// Determinism: the child build runs with cwd pinned to this directory (module-path comments in
// the output stay relative), a single fixed entrypoint, no minification, no sourcemaps, and the
// Hermit-pinned bun (`process.execPath` — the exact binary already running the transpile), so the
// bundle is byte-identical across machines for identical sources.
import { spawnSync } from 'child_process';
import { fileURLToPath } from 'url';

export function embeddedViewerScript(): string {
  const dir = fileURLToPath(new URL('.', import.meta.url));
  const res = spawnSync(
    process.execPath,
    ['build', 'run-report-viewer-boot.ts', '--format=iife', '--target=browser'],
    { encoding: 'utf8', cwd: dir, maxBuffer: 64 * 1024 * 1024 },
  );
  if (res.status !== 0) {
    // res.error covers the spawn-failed edge (e.g. ENOENT): status/stderr are null there, so the
    // launch error's own message is the only useful signal.
    throw new Error(`viewer bundle build failed (exit ${res.status}): ${res.stderr}${res.error ? ` (${res.error.message})` : ''}`);
  }
  return res.stdout;
}
