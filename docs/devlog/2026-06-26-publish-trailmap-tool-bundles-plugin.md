---
title: "Promoting trailmap-tool-bundles to a published OSS module"
type: decision
date: 2026-06-26
---

# Promoting trailmap-tool-bundles to a published OSS module

## Summary

The `trailblaze.trailmap-tool-bundles` Gradle plugin — which pre-compiles a trailmap's
TypeScript scripted tools into QuickJS bundles staged as Android test-APK assets —
has been moved out of `build-logic/` (the framework's internal Gradle composite
build) into its own publishable module at `trailblaze-trailmap-tool-bundles-plugin/`.
The plugin id is now `xyz.block.trailblaze.trailmap-tool-bundles` — FQN-style, as
befits a published artifact. The legacy `trailblaze.author-tool-bundle` sibling
plugin remains in `build-logic` and keeps compiling against the same
`BundleAuthorToolsTask` via a shared `srcDir(...)` composition — single source of
truth, two consumers.

The goal is to make the plugin a first-class part of the OSS source tree so external
Android teams can read it, source-build against it, and eventually consume snapshot
or release artifacts from Maven Central. The `vanniktech.maven.publish` wiring lands
in this module the same way it lands in every other published OSS module; cutting an
actual Maven Central release is a separate ceremony at the next release tag.

## The external-workspace decision (Option 2)

The bundle task needs three things from a Trailblaze TypeScript SDK install:

- The `esbuild` binary at `<sdkDir>/node_modules/.bin/esbuild`
- The slim in-process entry at `<sdkDir>/src/in-process.ts`
- The wrapper template at `<sdkDir>/tools/in-process-wrapper-template.mjs`

Inside the Trailblaze framework source tree these resolve via the existing walk-up
from `rootProject.projectDir` looking for `sdks/typescript/package.json`. Outside the
tree — which is the new audience — an external consumer doesn't have any of this
until they either vendor the SDK source or `bun install @trailblaze/scripting` from
npm. **The `@trailblaze/scripting` package is `"private": true`** in
`sdks/typescript/package.json`; publishing to npm is a separate follow-up.

Three approaches were considered:

1. **Bundle the SDK + esbuild into the plugin JAR.** Cleanest UX for the consumer,
   but esbuild ships platform-specific native binaries (linux-x64, darwin-arm64, …),
   and the wrapper template is co-canonical with the framework SDK source. A bundled
   copy would drift from the framework's runtime behavior the moment the template
   changes, and turn a small Kotlin plugin into a multi-OS distribution problem.
2. **Extension-property config: the consumer points the plugin at an SDK directory.**
   Low magic, easy to debug, matches how the JVM ecosystem already handles
   "tool requires a separate install."
3. **Resolve esbuild via Node/Bun on PATH and `npx esbuild`**, fetching SDK + wrapper
   template from a pinned Maven artifact at task action time. Removes the esbuild
   distribution problem but introduces another moving part (the SDK Maven artifact),
   and `npx` has resolution edge cases the bundler is sensitive to.

We picked **Option 2**. The new extension surface:

```kotlin
trailblazeTrailmapToolBundles {
  // OPTIONAL — when unset, the plugin walks up from rootProject.projectDir to find
  // sdks/typescript/package.json (the framework-source-tree convention).
  sdkDir.set(layout.projectDirectory.dir("sdk-bundle"))

  // OPTIONAL — Gradle task path each per-tool bundle task dependsOn so an SDK-install
  // step runs first. Auto-conventioned when :trailblaze-scripting-subprocess is in the
  // consumer's build (i.e. framework source tree) so no in-tree apply-site needs to
  // change. External consumers manage their own install lifecycle.
  sdkInstallTaskPath.set(":install-sdk")

  trailmap(id = "myapp", toolsDir = file("src/main/scripted-tools"))
}
```

### Why this is honest about the toolchain

External consumers already need `bun` and an SDK install for `trailblaze check`
to work — the workspace materializes the typed SDK into
`<workspace>/.trailblaze/sdk/dist/` today. Pretending we can hide `bun` behind a
plugin JAR would be papering over a dependency inherent to the toolchain. Until
the `@trailblaze/scripting` npm publish lands, the README's recommendation is to
vendor a copy of the framework's `sdks/typescript/` directory and `bun install`
against it. When the npm publish happens, the extension can grow an
`npmPackageDir` convention that defaults to
`<sdkDir>/node_modules/@trailblaze/scripting`; the plugin's resolver logic
doesn't need to change.

## Backwards compatibility

Every existing in-tree apply-site picks up the new module without modifying its
extension block. The plugin's `apply()` checks for the framework's SDK-install
sibling project and, when present, sets `sdkInstallTaskPath` as a convention
default. Apply-sites only needed to switch
`id("trailblaze.trailmap-tool-bundles")` → `id("xyz.block.trailblaze.trailmap-tool-bundles")`.

`BundleAuthorToolsTask` and its helpers (the wrapper synthesis, the
framework-root walk-up) moved with the plugin into the new module. The legacy
`TrailblazeAuthorToolBundlePlugin` (sibling plugin in `build-logic`) still
compiles against `BundleAuthorToolsTask` because `build-logic`'s build script
now composes `src/main/kotlin` from the new module via `srcDir(...)` — the same
single-source-of-truth pattern already in place for `:trailblaze-trailmap-bundler`.

## Follow-ups

- **Wire the included build's `publish` task into the release ceremony.** This
  PR sets up the module's `vanniktech.maven.publish` config so snapshot
  artifacts can be produced via the explicit included-build invocation
  (`./gradlew :trailblaze-trailmap-tool-bundles-plugin:publish` from the OSS
  root). The root build's aggregate `publish` task does NOT reach included
  builds by default, so the next release that ships this plugin to Maven
  Central needs either an explicit invocation of the included-build's publish
  task in the release script, or an aggregator that depends on
  `gradle.includedBuild("trailblaze-trailmap-tool-bundles-plugin").task(":publish")`.
  Out of scope here — the immediate goal is making the source consumable in
  the OSS tree, not cutting a release.
- **Publish `@trailblaze/scripting` to npm.** This is the meaningful
  external-consumer ergonomics unlock — once it lands, the README's "vendor a
  copy of `sdks/typescript/`" step collapses to one `bun install`.
- **Lift `trailblaze.author-tool-bundle` to its own publishable module** if its
  use case ever extends beyond the framework itself. Today it has one consumer,
  so duplicating its publish scaffolding wouldn't be a clear win.
- **Bundling esbuild** — explicitly rejected here, but if a future audience
  demands a zero-bun experience, the path would be a platform-classifier-aware
  Gradle dependency pulling esbuild as a binary artifact.
