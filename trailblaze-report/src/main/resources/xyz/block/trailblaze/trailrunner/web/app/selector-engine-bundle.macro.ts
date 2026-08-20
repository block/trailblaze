// Bun MACRO (imported `with { type: 'macro' }` from run-report-shell-html.ts): the Kotlin/JS
// selector-engine bundle, read and packed at TRANSPILE time so the standalone viewer shell carries
// the UI Inspector's engine the same way an exported report does.
//
// Why the shell needs its own copy. An exported report gets the engine from the Kotlin side:
// RunReportGenerator stages the JAR resource and run-report-cli.ts packs it into the
// `#tb-selector-engine` chunk. The shell has no such generation step — it is built by bun alone,
// with no run baked in — so without this macro the shell ships every line of Inspector code and no
// engine to run, and `loadSelectorEngineFromChunk` degrades to null: the "Inspect UI" button opens
// a panel that can never show a suggestion.
//
// Same transport as the report (`packSelectorEngine` — imported, not re-implemented, so the two
// homes can't drift): `{ js }` inline below the threshold, `{ gz }` gzip+base64 above it. The real
// bundle is ~320 KB, so it always lands on the gz side at ~110 KB.
//
// The bundle is a BUILD ARTIFACT, never committed: `./gradlew
// :trailblaze-selector-engine-js:bundleSelectorEngine` writes it, and that task itself skips
// cleanly when bun is absent. Absent bundle → null → the shell embeds no chunk and behaves exactly
// as it did before this macro existed. build-viewer-shell.sh builds it on demand and can be asked
// to fail rather than ship a viewer without it (`--require-engine`).
import { readFileSync } from 'fs';
import { fileURLToPath } from 'url';
import { packSelectorEngine } from '../../../report/run-report-cli';

/** Default location of the Gradle-built bundle, relative to this file (repo root is ten up). */
const DEFAULT_BUNDLE = '../../../../../../../../../../trailblaze-selector-engine-js/build/dist/trailblaze-selector-engine.min.js';

/**
 * The packed engine payload, or null when the bundle hasn't been built. `TRAILBLAZE_SELECTOR_ENGINE_BUNDLE`
 * overrides the path for packagers that build the bundle somewhere else.
 */
export function embeddedSelectorEngine(): { js?: string | null; gz?: string | null } | null {
  const override = process.env.TRAILBLAZE_SELECTOR_ENGINE_BUNDLE;
  const path = override && override.trim()
    ? override.trim()
    : fileURLToPath(new URL(DEFAULT_BUNDLE, import.meta.url));
  let code: string;
  try {
    code = readFileSync(path, 'utf8');
  } catch {
    return null;
  }
  return packSelectorEngine(code.trim() ? code : null);
}
