# trailblaze-android-gradle

Trailblaze's Android Gradle plugin, both driven by AGP integration:

1. **JUnit shell codegen** — auto-generates Android JUnit shells from
   Trailblaze `.trail.yaml` files, so `AndroidJUnitRunner` discovers each
   trail as its own `@Test` method without hand-written boilerplate.
2. **Scripted-tool bundling** (opt-in, via the nested `trailmap { }` block,
   see [below](#scripted-tool-bundling-trailmap)) — pre-compiles a trailmap's
   TypeScript scripted tools into QuickJS bundles staged as `androidTest`
   assets, so an on-device test APK can dispatch them by name.

Apply one plugin id and both are auto-wired into AGP's `androidTest` source
set — no manual `android { sourceSets... }` block required.

For every `<methodName>.trail.yaml` you drop under
`src/androidTest/assets/trails/<ClassName>/`, the plugin emits a matching
Kotlin shell:

```kotlin
class <ClassName> {
  @get:Rule val rule = AndroidTrailblazeRule()
  @Test fun <methodName>() = rule.runFromAsset("trails/<ClassName>/<methodName>.trail.yaml")
}
```

Add or rename a trail file and the matching `@Test` appears on the next
`androidTest` build — no Kotlin edit, no class to maintain. Shells that need
real Kotlin (a `TestWatcher`, `@Before`, helper functions) keep working as
before; this plugin only retires the pure-boilerplate ones.

## Apply

The plugin is published to Maven Central. The plugin marker is not on the
Gradle Plugin Portal, so add Maven Central to your `pluginManagement` block
first (skip this step if your `settings.gradle.kts` already lists it):

```kotlin
// settings.gradle.kts
pluginManagement {
  repositories {
    mavenCentral()
    gradlePluginPortal()
  }
}
```

Then apply the plugin in your module's `build.gradle.kts`. It also requires
`com.android.library` or `com.android.application` to be applied, and fails
fast with a directed error if neither is present:

```kotlin
plugins {
  id("com.android.library")
  id("org.jetbrains.kotlin.android")
  id("xyz.block.trailblaze.android-gradle") version "<latest>"
}

trailblazeAndroid {
  packageName = "com.example.app"
  // (optional) restrict to specific shells during incremental rollout —
  // leaving onlyClassNames unset generates a shell for every <ClassName>/ subdir.
  // onlyClassNames = setOf("LoginFlowTest")
  // (optional) restrict to specific trails within a class — e.g. a fast-PR-check subset.
  // Class not in the map → all its trails generate. Method name typos fail loudly at build time.
  // onlyMethodNames.put("LoginFlowTest", setOf("happyPath", "invalidCredentials"))

  // (optional) pre-compile a trailmap's scripted tools too — see "Scripted-tool bundling" below.
  // trailmap {
  //   id = "myapp"
  //   toolsDir = file("src/main/scripted-tools")
  // }
}
```

No `android { sourceSets... }` or `tasks.matching {}` block needed — the plugin auto-wires the
generated source dir (and, when `trailmap { }` is configured, the staged bundle assets) into AGP's
`androidTest` source set and its compile/lint/asset-merge tasks.

Then drop your YAMLs:

```
src/androidTest/assets/trails/
  LoginFlowTest/
    happyPath.trail.yaml
    invalidCredentials.trail.yaml
```

Build the test APK — the plugin emits
`build/generated/source/trailblazeTrails/androidTest/com/example/app/LoginFlowTest.kt`
containing one `@Test` per YAML.

## Two emit modes

| Mode | When to use | Emit shape |
| --- | --- | --- |
| **Inline-rule** (default) | The standard path for any OSS Android consumer. Uses `xyz.block.trailblaze.android.AndroidTrailblazeRule` as a JUnit `@Rule`. No additional config beyond `packageName`. | `class X { @get:Rule val rule = AndroidTrailblazeRule(); @Test fun y() = rule.runFromAsset("trails/X/y.trail.yaml") }` |
| **Extending-base** | The shell extends a downstream base class that exposes its own `runFromAsset()` helper. Useful for teams that already have a wrapping JUnit base class (cleanup hooks, custom `@Rule` chains, etc.). | `class X : <baseClassFqn>() { @Test fun y() = runFromAsset() }` |

The mode is picked by which extension property you set:

```kotlin
trailblazeAndroid {
  packageName = "com.example.app"

  // Inline-rule mode (default) — override the rule class if needed:
  // ruleClassFqn = "com.example.app.MyTrailblazeRule"

  // Extending-base mode — set baseClassFqn to switch:
  // baseClassFqn = "com.example.app.MyTrailblazeBase"
}
```

You can also set either property via a Gradle command-line property
(`-Ptrailblaze.shellGenerator.baseClassFqn=...`,
`-Ptrailblaze.shellGenerator.ruleClassFqn=...`) for CI shards or one-off
local runs — no build file edit required.

## Using a rule that needs constructor args

The inline-rule mode emits `@get:Rule val rule = <RuleClass>()` — so it only
fits rules whose constructor takes no required arguments (`AndroidTrailblazeRule`
qualifies because every parameter has a default). Rules that wrap a specific
app — anything that requires an `appId`, a target descriptor, a custom tool
surface, an account, etc. — can't be instantiated from `()` and so can't be
named via `ruleClassFqn`.

Use **extending-base** mode for those. Write a thin JUnit base class whose
sole job is to own the `@Rule` field with the required arguments set, expose
a `runFromAsset()` helper, and then point `baseClassFqn` at the base. The
generator's shells extend that base and inherit the helper. The rule's
arguments live in one place (your base) instead of being duplicated across
every generated shell.

A sketch — for a rule that needs an `appId` and an account:

```kotlin
// src/androidTest/java/com/example/app/uitests/MyAuthedTrailblazeTest.kt
abstract class MyAuthedTrailblazeTest {
  @get:Rule
  val rule = MyAuthedTrailblazeRule(
    appId = "com.example.app",
    account = MyAccount.TEST_USER,
  )

  fun runFromAsset() = rule.runFromAsset()
}
```

Put the base in `src/androidTest/` (not `src/main/`) so it stays out of the
production artifact and has access to test-only dependencies. If you want
to share a base across several modules, lift it into a dedicated
test-support library's `main` source set and consume that library via
`androidTestImplementation` — that's the multi-module variant.

```kotlin
// In the same module's build.gradle.kts
trailblazeAndroid {
  packageName = "com.example.app.uitests"
  baseClassFqn = "com.example.app.uitests.MyAuthedTrailblazeTest"
}
```

Drop the trail YAMLs and the base class alongside each other under `androidTest`:

```
src/androidTest/
  assets/trails/LoginFlowTest/
    happyPath.trail.yaml
    invalidCredentials.trail.yaml
  java/com/example/app/uitests/
    MyAuthedTrailblazeTest.kt
```

The plugin then emits:

```kotlin
class LoginFlowTest : MyAuthedTrailblazeTest() {
  @Test fun happyPath() = runFromAsset()
  @Test fun invalidCredentials() = runFromAsset()
}
```

This pattern composes with everything a JUnit base class can do — custom
`@Rule` chains, `@Before` / `@After`, `TestWatcher` cleanup, shared helper
methods — without touching the generated files. Each app family that needs
its own rule wiring (a `*TrailblazeRule` constructor specific to its app)
typically has its own `*TrailblazeTest` base class; consumers point
`baseClassFqn` at theirs.

## Scripted-tool bundling (`trailmap { }`)

Optional, independent of the codegen above. Use it if your target authors TypeScript scripted
tools (`*.ts` under a trailmap's `tools/` directory) that need to dispatch by name from an
on-device test APK. The device has no `bun`/esbuild, so the bundle must be pre-compiled at build
time and shipped as an asset.

```kotlin
trailblazeAndroid {
  // OPTIONAL — unset walks up from `rootProject.projectDir` for `sdks/typescript/package.json`.
  // Set explicitly outside the Trailblaze framework source tree (see below).
  sdkDir.set(layout.projectDirectory.dir("sdk-bundle"))

  trailmap {
    id = "myapp"
    toolsDir = file("src/main/scripted-tools")
  }
}
```

Call `trailmap { }` more than once to bundle more than one trailmap — each call registers its own
bundle + stage tasks, landing in the same staging root the plugin auto-wires into AGP's
`androidTest` assets at `trails/config/trailmaps/<id>/tools/<toolName>.bundle.js`.

### External consumers: setting up the SDK directory

The bundle task drives `esbuild` against the Trailblaze TypeScript SDK. It needs three things
under `sdkDir`:

| Path under `sdkDir` | What it is |
| --- | --- |
| `node_modules/.bin/esbuild` | The esbuild binary — populated by `bun install` |
| `src/in-process.ts` | The slim in-process SDK entry esbuild aliases `@trailblaze/scripting` to |
| `tools/in-process-wrapper-template.mjs` | The wrapper template that registers each tool |

Inside the Trailblaze framework source tree, leave `sdkDir` unset — the walk-up finds it. Outside
that tree, set `sdkDir` explicitly; until `@trailblaze/scripting` publishes to npm, vendor a copy:

```bash
cp -R path/to/trailblaze/sdks/typescript ./sdk-bundle
(cd sdk-bundle && bun install)
```

To re-run `bun install` whenever the lockfile changes, wire a task through `sdkInstallTaskPath`:

```kotlin
trailblazeAndroid {
  sdkDir.set(layout.projectDirectory.dir("sdk-bundle"))
  sdkInstallTaskPath.set(":install-sdk")
  trailmap {
    id = "myapp"
    toolsDir = file("src/main/scripted-tools")
  }
}

tasks.register<Exec>("install-sdk") {
  workingDir = file("sdk-bundle")
  commandLine("bun", "install")
}
```

Leave `sdkInstallTaskPath` unset if you manage the install some other way.

### How tool discovery decides what to bundle

Every `*.ts` in `toolsDir` is bundled when either a sibling `<name>.yaml` exists whose `runtime:`
isn't `subprocess`, or (no sidecar) the `.ts` declares the tool inline via `trailblaze.tool<…>(…)`
without pinning `runtime: "subprocess"`. `.test.ts`, `.d.ts`, and helper modules that never call
`trailblaze.tool` are skipped.

### `trailmap { }` block properties

| Property | Type | Required | What it does |
| --- | --- | --- | --- |
| `id` | `Property<String>` | yes | The trailmap id — keys the on-device asset path. |
| `toolsDir` | `Property<File>` | yes | The trailmap's scripted-tool source directory. |

### Extension-level properties consumed by `trailmap { }`

| Property | Type | Required | What it does |
| --- | --- | --- | --- |
| `sdkDir` | `DirectoryProperty` | external only | Where the TS SDK lives. Walk-up fallback if unset. |
| `sdkInstallTaskPath` | `Property<String>` | optional | A Gradle task path the bundle tasks `dependsOn`. |
| `stagingRoot` | `Provider<Directory>` | output | Auto-wired into AGP's `androidTest` assets; read directly only for something the auto-wiring doesn't cover. |
| `allStagingTasks` | `List<TaskProvider<Copy>>` | output | Auto-wired into the AGP asset-task `dependsOn(...)`; same caveat as `stagingRoot`. |

### See also

- `sdks/typescript/README.md` — the SDK the bundle aliases `@trailblaze/scripting` to.
- `docs/scripted-tools-typed-authoring.md` — authoring the TypeScript tools this plugin compiles.
- `docs/scripted-tools-project-layout.md` — the trailmap directory layout this plugin keys off of.

## What it does not do

This plugin scans **filenames only** — it never opens the `.trail.yaml`
contents. The shells it emits are the minimum AGP needs to discover each
trail as its own JUnit method; the real test logic lives in the trail YAML.

Shells that need any of the following stay hand-written:

- Custom `@Before`, `@After`, or `@ClassRule` setup.
- Cleanup that must run regardless of trail outcome (`TestWatcher`).
- Helper methods that several `@Test`s share within the same class.
- Inline `runTools(...)` against Kotlin tool objects (no YAML at all).

You can mix generated and hand-written shells in the same module — the
generated output goes to a separate `build/generated/` directory and the
plugin only emits classes for `<ClassName>/` subdirectories of
`assets/trails/`.

## Source

Plugin source and tests live at
[`trailblaze-android-gradle/`](https://github.com/block/trailblaze/tree/main/trailblaze-android-gradle)
in this repo. Issues and pull requests welcome.
