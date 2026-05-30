// The Wikipedia example is CLI-first — there's no JUnit harness here. The
// `trailblaze.bundle` plugin's `compileTrailblazeWorkspace` half still runs
// to emit per-trailmap `client.d.ts` and the workspace SDK / tsconfig artifacts
// the IDE needs for autocomplete; the `bundleTrailblazeTrailmap` half is
// disabled below — see kdoc.
//
// Trails under `trails/wikipedia/` are exercised by `./trailblaze run …`
// directly — both for local development and CI. This module deliberately has
// no test sources, no test dependencies, and no `tasks.test` config.
plugins {
  id("trailblaze.bundle")
}

trailblazeBundle {
  trailmapsDir.set(layout.projectDirectory.dir("trails/config/trailmaps"))
  // Disable the per-trailmap `bundleTrailblazeTrailmap` half. The wikipedia trailmap has
  // adopted the meta-only authoring shape (PR #3338) where each tool YAML
  // carries only `script:` + `_meta:` — the sibling `.ts`'s
  // `trailblaze.tool<I, O>(handler)` declaration is the source of truth for
  // `name:` / `inputSchema:` / `description:`. The standalone bundler library
  // in `:trailblaze-trailmap-bundler` deliberately doesn't depend on
  // trailblaze-models (would pull koog + MCP onto build-logic's classpath) and
  // therefore can't run the AST analyzer to recover those fields, so it
  // skips meta-only descriptors and fails `target.tools:` resolution.
  //
  // The `compileTrailblazeWorkspace` JavaExec — the other half of this plugin
  // — DOES route through `TrailblazeCompiler.compile()` with analyzer-backed
  // enrichment wired (PR #3348), so it resolves meta-only descriptors
  // cleanly and still emits the per-trailmap `client.d.ts` that the IDE
  // consumes for `ctx.tools.X(args)` autocomplete. That's the artifact this
  // trailmap actually depends on; the `tools.d.ts` the bundler would have
  // emitted is redundant with it.
  //
  // Lift this once the standalone bundler grows analyzer awareness (its
  // discovery walk has its own slim YAML schema today — see
  // `TrailblazeTrailmapBundler.kt`). Same opt-out shape `.js`-authored trailmaps use
  // (see `TrailblazeBundleExtension.bundleEnabled` kdoc).
  bundleEnabled.set(false)
}
