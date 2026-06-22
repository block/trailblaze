# trailblaze-scripting-bundle

Ships the **bundled `@trailblaze/scripting` SDK JavaScript** that scripted tools are
evaluated against, and provides a small helper to hand that JS to a bun subprocess.

This module is **not** a tool runtime — it holds no sessions, transport, or launcher. It is
the home of one generated build artifact plus its accessor.

## What's in here

- **`trailblaze-sdk-bundle.js`** — the `@trailblaze/scripting` SDK, esbuild-bundled into a
  single IIFE at
  `src/jvmAndAndroid/resources/trailblaze/scripting/bundle/trailblaze-sdk-bundle.js`. A
  **build artifact, not committed source** (`.gitignore`d): the
  `:trailblaze-scripting-bundle:bundleTrailblazeSdk` Gradle task regenerates it from
  `sdks/typescript/src/`, and the module's resource-packaging tasks depend on it, so it's
  refreshed on every build. See `build.gradle.kts` and `TrailblazeSdkBundlePlugin`.
- **`SdkBundleResource`** — materializes that generated JS onto disk (a user-scoped temp dir,
  atomic write) so a **bun subprocess** that needs a real `File` path can `import(...)` it. The
  caller is the inline-script-tool synthesizer's subprocess path (`pathToFileURL`), which can't
  read a classpath resource directly.

## How it's consumed

- **Subprocess (`runtime: subprocess`)** — `SdkBundleResource.extractToFile()` gives the spawned
  **bun** process a file URL to import the SDK from. This is the only consumer of the generated
  bundle today.
- **In-process QuickJS (`runtime: inProcess`)** — does **not** consume this resource today. The
  on-device runtime evaluates each tool's own `.bundle.js` (which inlines whatever SDK it
  imports), so there is no separate shared-SDK load. (`SdkBundleResource`'s kdoc points at a
  `BundleRuntimePrelude` in-process loader, but no such type exists yet — it's planned, not
  built.)

## Not in this module

The on-device QuickJS *runtime* (engine, tool registration, dispatch) lives in
`:trailblaze-quickjs-tools` (`QuickJsToolHost` / `QuickJsToolBundleLauncher`), which is what
`AndroidTrailblazeRule` launches. Cross-tool composition from inside a bundle (the in-process
`__trailblazeCallback` binding) is **not built yet** — see the 2026-06-17
"Consolidate scripted-tool surfaces" decision (`docs/devlog/`) for the plan to add it and to
collapse onto a single `@trailblaze/scripting` authoring surface.
