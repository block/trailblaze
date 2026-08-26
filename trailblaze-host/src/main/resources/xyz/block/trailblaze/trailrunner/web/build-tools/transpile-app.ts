// Build-time transpiler for the Trail Runner web app's .tsx screens.
//
// The screens used to ship as `<script type="text/babel">` tags that @babel/standalone compiled in
// the browser on EVERY page load — 3-6 s of blocked main thread for the 29 files, plus the 3.1 MB
// babel.min.js download. This script does the same work once at build time in ~20 ms, and
// `:trailblaze-host:transpileTrailRunnerApp` wires it into `processResources` so the classpath the
// daemon serves from (TrailRunnerEndpoint reads the app STRICTLY from resources — there is no
// serve-from-disk dev mode) carries plain JS in both dev and release builds.
//
// PER-FILE TRANSPILE, NEVER A BUNDLE. The screens are non-module classic scripts: they share one
// global lexical scope, read each other's top-level declarations by bare name, and read the
// `window.*` surface earlier tags publish (window.TbRpc, React, window.TM, window.TbZipReport, ...).
// Bundling or renaming would break all of that, so each .tsx is transpiled 1:1 to a .js loaded by
// its own <script> tag in index.html, in the order index.html already declares. `Bun.Transpiler`
// (not `bun build`) is what makes that possible: it strips types and compiles JSX without wrapping
// the file in a module scope or rewriting any identifier.
//
// JSX compiles to the CLASSIC runtime (`React.createElement`) because React arrives as a UMD global
// from the CDN, not as an import. Unlike Babel's `env` preset this does NOT downlevel syntax — the
// target is a modern desktop Chrome/WKWebView, and nothing in the app depends on downleveling.
// (One thing downleveling did mask: `env` rewrote top-level `const` to `var`, which incidentally
// published it on `window`. Every cross-file `window.X` read in the app resolves to an explicit
// `window.X = ...` / `Object.assign(window, {...})` publish or to a function declaration, so
// keeping `const` changes nothing — but a NEW cross-file global must be published explicitly.)
//
// The .tsx files stay the editable, type-checked (`checkTrailRunnerTypes`) sources; they are
// excluded from the runtime jar because nothing loads them at runtime any more. They remain in the
// sources jar — that is where the authoring format belongs.
//
// THIS file is deliberately outside `checkTrailRunnerTypes` (tsconfig.check.json includes `app/**`
// only). Type-checking it needs `@types/bun` for Bun.Transpiler/Bun.Glob/import.meta.main, and the
// internal npm mirror does not carry it (`@types/bun` and `bun-types` both 404), so adding it would
// break the gate's own `bun install --frozen-lockfile`. What covers this file instead: the pure
// helpers below have bun tests (transpile-app.test.ts, run in CI by pr_typescript_unit_tests.sh),
// and `main()` executes on every single build — a break here fails `processResources`, loudly and
// immediately, rather than shipping.

import { rm, mkdir } from "node:fs/promises";
import { dirname, join } from "node:path";

/** Local `<script src="./...">` paths declared by [html], in document order. */
export function scriptSrcs(html: string): string[] {
  return [...html.matchAll(/<script\b[^>]*\bsrc="\.\/([^"]+)"/g)].map((m) => m[1]);
}

/** The transpiled artifact path a .tsx source maps to (`app/screens/home.tsx` -> `app/screens/home.js`). */
export function transpiledPath(tsxPath: string): string {
  return tsxPath.replace(/\.tsx$/, ".js");
}

/**
 * Scripts index.html loads that this source tree does not contain, by design: `:trailblaze-report`
 * publishes them onto the SAME classpath path (`xyz/block/trailblaze/trailrunner/web/app/`), so the
 * daemon serves them even though they are absent here. Without this list the "nothing produces it"
 * check below would fail the build on two files that are perfectly wired.
 *
 * Adding a third one has to be deliberate: a new unresolvable tag is a bug until someone proves
 * another module publishes it.
 */
export const SCRIPTS_PROVIDED_BY_OTHER_MODULES = [
  "app/run-report-core.js",
  "app/zip-report-core.js",
];

/**
 * Everything about [html] that would make the transpiled app silently wrong, as human-readable
 * problems. Empty means the document and the source tree agree.
 *
 * These are build failures rather than warnings because each one is invisible in a browser: an
 * unwired screen is just a component that never renders, and a leftover babel tag re-downloads
 * 3.1 MB and re-transpiles nothing (the .tsx are no longer served) while looking fine.
 *
 * Both directions are checked. A .tsx no tag loads never reaches the browser; a tag whose .tsx was
 * deleted or renamed 404s, and every global that screen published goes missing with it.
 */
export function wiringProblems(
  tsxPaths: string[],
  html: string,
  committedJsPaths: string[],
): string[] {
  const problems: string[] = [];
  if (/type="text\/babel"/.test(html)) {
    problems.push(
      'index.html still declares a <script type="text/babel"> tag. The .tsx sources are not served ' +
        "any more — load the transpiled ./app/<name>.js instead.",
    );
  }
  if (/@babel\/standalone|babel\.min\.js/.test(html)) {
    problems.push(
      "index.html still loads @babel/standalone. Nothing transpiles in the browser any more; drop " +
        "the tag (it is a 3.1 MB download and ~65 ms of parse per page load).",
    );
  }
  const declared = new Set(scriptSrcs(html));
  const committed = new Set(committedJsPaths);
  for (const tsx of tsxPaths) {
    const js = transpiledPath(tsx);
    if (committed.has(js)) {
      problems.push(
        `${tsx} would be transpiled over the committed ${js}. Rename one of them — the transpiled ` +
          "artifact wins on the classpath, so the committed file would silently stop being served.",
      );
    }
    if (!declared.has(js)) {
      problems.push(
        `${tsx} is not loaded by index.html. Add <script src="./${js}"></script> in the right load ` +
          "order (these are classic scripts: a screen must come after the globals it reads).",
      );
    }
  }

  const producible = new Set([
    ...committedJsPaths,
    ...tsxPaths.map(transpiledPath),
    ...SCRIPTS_PROVIDED_BY_OTHER_MODULES,
  ]);
  for (const src of scriptSrcs(html)) {
    if (src.endsWith(".tsx")) {
      problems.push(
        `index.html loads ${src} directly. The .tsx sources are not served any more — load ` +
          `./${transpiledPath(src)} instead.`,
      );
    } else if (!producible.has(src)) {
      problems.push(
        `index.html loads ${src}, but nothing produces it: there is no committed ${src} and no ` +
          `${src.replace(/\.js$/, ".tsx")} to transpile. Drop the tag, or restore the source it lost.`,
      );
    }
  }
  return problems;
}

async function main(): Promise<void> {
  const outRoot = process.argv[2];
  if (!outRoot) throw new Error("usage: bun run build-tools/transpile-app.ts <output-dir>");

  const tsxPaths = (await Array.fromAsync(new Bun.Glob("app/**/*.tsx").scan("."))).sort();
  if (tsxPaths.length === 0) {
    throw new Error("no app/**/*.tsx sources found — is the working directory the trailrunner web dir?");
  }
  const committedJsPaths = await Array.fromAsync(new Bun.Glob("app/**/*.js").scan("."));

  const html = await Bun.file("index.html").text();
  const problems = wiringProblems(tsxPaths, html, committedJsPaths);
  if (problems.length > 0) {
    throw new Error(`Trail Runner app wiring is out of sync:\n  - ${problems.join("\n  - ")}`);
  }

  // Wipe first: this is the task's declared output dir, and a .tsx that gets deleted or renamed
  // would otherwise leave its stale .js behind for index.html to keep loading.
  await rm(outRoot, { recursive: true, force: true });

  const transpiler = new Bun.Transpiler({
    loader: "tsx",
    target: "browser",
    tsconfig: { compilerOptions: { jsx: "react" } },
  });
  let bytes = 0;
  for (const tsx of tsxPaths) {
    const js = join(outRoot, transpiledPath(tsx));
    await mkdir(dirname(js), { recursive: true });
    const out = transpiler.transformSync(await Bun.file(tsx).text());
    await Bun.write(js, out);
    bytes += out.length;
  }
  console.log(`transpiled ${tsxPaths.length} .tsx sources (${(bytes / 1024).toFixed(0)} KB) into ${outRoot}`);
}

if (import.meta.main) await main();
