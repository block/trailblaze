// Bun MACRO (imported `with { type: 'macro' }` from run-report-html.ts): the viewer shell's loader
// script, built at TRANSPILE time and inlined as a string constant — same pattern, same determinism
// argument as run-report-viewer-bundle.macro.ts (cwd pinned to this directory, one fixed entrypoint,
// no minification, no sourcemaps, the Hermit-pinned bun already running the transpile).
//
// Separate from the viewer bundle on purpose: only the shell document embeds this, so an ordinary
// exported report pays nothing for the loader it will never use.
import { spawnSync } from 'child_process';
import { fileURLToPath } from 'url';

export function embeddedShellScript(): string {
  const dir = fileURLToPath(new URL('.', import.meta.url));
  const res = spawnSync(
    process.execPath,
    ['build', 'run-report-shell-boot.ts', '--format=iife', '--target=browser'],
    { encoding: 'utf8', cwd: dir, maxBuffer: 64 * 1024 * 1024 },
  );
  if (res.status !== 0) {
    throw new Error(`shell bundle build failed (exit ${res.status}): ${res.stderr}${res.error ? ` (${res.error.message})` : ''}`);
  }
  return res.stdout;
}
