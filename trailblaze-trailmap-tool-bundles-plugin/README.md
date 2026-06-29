# trailblaze-trailmap-tool-bundles-plugin

A Gradle plugin that pre-compiles a trailmap's TypeScript scripted tools (the `*.ts`
files under the trailmap's `tools/` directory) into QuickJS-evaluatable `.bundle.js`
files and stages them as Android test-APK assets. On-device Trailblaze tests can then
dispatch those tools by name — the device has no `bun` or `esbuild` available, so the
bundling has to happen at build time.

**Plugin id:** `xyz.block.trailblaze.trailmap-tool-bundles`
**Group / artifact:** `xyz.block.trailblaze:trailblaze-trailmap-tool-bundles-plugin`

## When you'd want this

You author a target's `target.tools:` scripted tools in TypeScript, the Kotlin launch
orchestrator or another scripted tool composes them by name (`ctx.tools.<name>(args)`),
and you want them to dispatch from an on-device Android test APK as well as from the
host/daemon path. The host/daemon path bundles them live at session start; on-device
needs a pre-compiled bundle shipped as an asset.

## Apply

```kotlin
plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.kotlin.android)
  id("xyz.block.trailblaze.trailmap-tool-bundles")
}

trailblazeTrailmapToolBundles {
  trailmap(
    id = "myapp",
    toolsDir = file("src/main/scripted-tools"),
  )
}

// Ship the staged bundles in the test APK + close AGP's implicit-dependency gap.
android {
  sourceSets.getByName("androidTest").assets.srcDir(trailblazeTrailmapToolBundles.stagingRoot)
}
tasks
  .matching { it.name.endsWith("AndroidTestAssets") }
  .configureEach { dependsOn(trailblazeTrailmapToolBundles.allStagingTasks) }
```

The plugin registers one bundle task and one staging task per discovered `*.ts` tool
file. Bundles land at the on-device asset path the launcher resolves:
`trails/config/trailmaps/<id>/tools/<toolName>.bundle.js`.

## External consumers: setting up the SDK directory

The bundle task drives `esbuild` against the Trailblaze TypeScript SDK so the produced
bundles behave identically on-device to the framework's own bundler. It needs three
things to be present on disk:

| Path under `sdkDir` | What it is |
| --- | --- |
| `node_modules/.bin/esbuild` | The esbuild binary — populated by `bun install` |
| `src/in-process.ts` | The slim in-process SDK entry esbuild aliases `@trailblaze/scripting` to |
| `tools/in-process-wrapper-template.mjs` | The wrapper template the bundler stamps to register each tool |

For consumers building **inside the Trailblaze framework source tree** (where the SDK
lives at `<repo>/sdks/typescript/`), leave `sdkDir` unset — the plugin walks up from
`rootProject.projectDir` to find `sdks/typescript/package.json` and wires everything
automatically. This is the historical behavior; no existing apply-site needs to change.

For consumers building **outside that tree** — e.g. an external Android team's repo —
set `sdkDir` explicitly. Until the `@trailblaze/scripting` package publishes to npm,
the simplest reliable setup is to vendor a copy of the framework SDK source into your
repo and `bun install` against it:

```bash
# One-time, in your repo
cp -R path/to/trailblaze/sdks/typescript ./sdk-bundle
(cd sdk-bundle && bun install)
```

Then point the extension at it:

```kotlin
trailblazeTrailmapToolBundles {
  sdkDir.set(layout.projectDirectory.dir("sdk-bundle"))
  trailmap(id = "myapp", toolsDir = file("src/main/scripted-tools"))
}
```

If you want the bundle task to depend on a Gradle task that re-runs `bun install`
whenever the lockfile changes, expose it through `sdkInstallTaskPath`:

```kotlin
trailblazeTrailmapToolBundles {
  sdkDir.set(layout.projectDirectory.dir("sdk-bundle"))
  sdkInstallTaskPath.set(":install-sdk")
  trailmap(id = "myapp", toolsDir = file("src/main/scripted-tools"))
}

tasks.register<Exec>("install-sdk") {
  workingDir = file("sdk-bundle")
  commandLine("bun", "install")
}
```

Leaving `sdkInstallTaskPath` unset is fine if you install the SDK manually or as part
of a different lifecycle.

## How tool discovery decides what to bundle

The plugin's `inProcessToolSources` discovery looks at every `*.ts` in the configured
`toolsDir` and bundles it when either:

- a sibling `<name>.yaml` exists whose `runtime:` isn't `subprocess`, **or**
- no sibling YAML exists, but the `.ts` declares the tool inline via
  `export const … = trailblaze.tool<…>(…)` and doesn't pin `runtime: "subprocess"` in
  its inline spec.

Files ending in `.test.ts`, `.d.ts`, and shared helper modules (which don't call
`trailblaze.tool`) are skipped.

## Plugin extension surface

| Property | Type | Required | What it does |
| --- | --- | --- | --- |
| `trailmap(id, toolsDir)` | DSL | yes (≥ 1×) | Register a trailmap. One call per trailmap. |
| `sdkDir` | `DirectoryProperty` | external only | Where the TS SDK lives. Walk-up fallback if unset. |
| `sdkInstallTaskPath` | `Property<String>` | optional | A Gradle task path the bundle tasks `dependsOn`. |
| `stagingRoot` | `Provider<Directory>` | output | The aggregated asset-staging directory — point AGP `assets.srcDirs(...)` at it. |
| `allStagingTasks` | `List<TaskProvider<Copy>>` | output | The per-bundle staging tasks — wire them into the AGP-asset-task `dependsOn(...)`. |

## See also

- `sdks/typescript/README.md` — the SDK whose source the bundle aliases
  `@trailblaze/scripting` to.
- `docs/scripted-tools-typed-authoring.md` — how to author the TypeScript scripted
  tools this plugin compiles.
- `docs/scripted-tools-project-layout.md` — the trailmap directory layout this plugin
  keys off of.
