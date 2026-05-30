// The iOS Contacts example is CLI-first — there's no JUnit harness here. The
// `trailblaze.bundle` plugin's `compileTrailblazeWorkspace` half still runs
// to emit per-trailmap `client.d.ts` and the workspace SDK / tsconfig artifacts
// the IDE needs for autocomplete; the `bundleTrailblazeTrailmap` half is
// disabled below — see kdoc.
//
// Trails under `trails/ios-contacts/` are exercised by `./trailblaze run …`
// directly — both for local development and CI. This module deliberately has
// no test sources, no test dependencies, and no `tasks.test` config.
plugins {
  id("trailblaze.bundle")
}

trailblazeBundle {
  trailmapsDir.set(layout.projectDirectory.dir("trails/config/trailmaps"))
  // Disable the per-trailmap `bundleTrailblazeTrailmap` half. The iOS Contacts
  // trailmap now uses the partial-descriptor authoring shape (each tool YAML
  // carries `name:` + `script:` + `supportedPlatforms:` shortcut only —
  // description / inputSchema / `_meta` gates come from the typed `.ts`'s
  // `trailblaze.tool<I>(spec, handler)` declaration via the runtime analyzer
  // enrichment in `AnalyzerScriptedToolEnrichment`). The standalone bundler
  // library in `:trailblaze-trailmap-bundler` deliberately doesn't depend on
  // trailblaze-models (would pull koog + MCP onto build-logic's classpath) and
  // therefore can't run the AST analyzer to recover those fields, so it would
  // reject the partial descriptors with "tool advertisement missing
  // description/schema" and fail `target.tools:` resolution.
  //
  // The `compileTrailblazeWorkspace` JavaExec — the other half of this plugin
  // — DOES route through `TrailblazeCompiler.compile()` with analyzer-backed
  // enrichment wired (PR #3348), so it resolves partial descriptors cleanly
  // and still emits the per-trailmap `client.d.ts` that the IDE consumes for
  // `ctx.tools.X(args)` autocomplete. That's the artifact this trailmap
  // actually depends on; the `tools.d.ts` the bundler would have emitted is
  // redundant with it.
  //
  // Lift this once the standalone bundler grows analyzer awareness (its
  // discovery walk has its own slim YAML schema today — see
  // `TrailblazeTrailmapBundler.kt`). Same opt-out shape wikipedia uses.
  bundleEnabled.set(false)
}
