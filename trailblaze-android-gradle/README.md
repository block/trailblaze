# trailblaze-android-gradle

Trailblaze's Android Gradle plugin. Today it auto-generates Android JUnit
shells from Trailblaze `.trail.yaml` files, so `AndroidJUnitRunner` discovers
each trail as its own `@Test` method without hand-written boilerplate.

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

Then apply the plugin in your module's `build.gradle.kts`:

```kotlin
plugins {
  id("com.android.library")
  id("org.jetbrains.kotlin.android")
  id("xyz.block.trailblaze.android-gradle") version "<latest>"
}

trailblazeAndroidGradle {
  packageName.set("com.example.app")
  // (optional) restrict to specific shells during incremental rollout —
  // leaving onlyClassNames unset generates a shell for every <ClassName>/ subdir.
  // onlyClassNames.set(setOf("LoginFlowTest"))
}

android {
  sourceSets
    .getByName("androidTest")
    .java
    .srcDir(trailblazeAndroidGradle.generatedSourceDir)
}

tasks
  .matching { it.name.startsWith("compile") && it.name.contains("AndroidTest") }
  .configureEach { dependsOn(trailblazeAndroidGradle.generateTask) }
```

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
trailblazeAndroidGradle {
  packageName.set("com.example.app")

  // Inline-rule mode (default) — override the rule class if needed:
  // ruleClassFqn.set("com.example.app.MyTrailblazeRule")

  // Extending-base mode — set baseClassFqn to switch:
  // baseClassFqn.set("com.example.app.MyTrailblazeBase")
}
```

You can also set either property via a Gradle command-line property
(`-Ptrailblaze.shellGenerator.baseClassFqn=...`,
`-Ptrailblaze.shellGenerator.ruleClassFqn=...`) for CI shards or one-off
local runs — no build file edit required.

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
