plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.kotlin.android)
  id("trailblaze.spotless")
  // Reusable scaffolding for staging QuickJS author-tool bundles into the test APK
  // assets. Replaces what used to be a hand-rolled Copy + dependsOn block here so future
  // downstream test modules only register the bundle and asset path.
  id("trailblaze.quickjs-bundle-assets")
  // Auto-generates a JUnit shell per `<methodName>.trail.yaml` under the configured trails
  // directory, replacing the hand-rolled `GenerateSampleAppTestsTask` this module used to carry.
  // The plugin is published to Maven Central and is the canonical way an external team adds
  // trail-derived JUnit tests to an Android library module — this example dogfoods it so the
  // OSS docs and the example stay in sync.
  id("xyz.block.trailblaze.android-gradle")
}

val installSampleAppMcpTools =
  project(":trailblaze-scripting-subprocess").tasks.named("installSampleAppMcpTools")

// Stage the sample-app trails into the layout the plugin expects:
// `<staging>/trails/GeneratedSampleAppTests/<methodName>.trail.yaml`. Two inputs are merged:
//
//  1) Instrumentation trails under
// `../android-sample-app/trails/android-ondevice-instrumentation/`,
//     where each scenario lives at `<category>/<scenario>/android-phone.trail.yaml`. The Copy's
//     `eachFile` flattens that to `<scenarioCamel>.trail.yaml` (e.g. `text-input/android-phone…`
//     → `textInput.trail.yaml`) so a single `GeneratedSampleAppTests` class collects every
//     scenario as a `@Test fun <scenarioCamel>()`.
//
//  2) Repo-root sample-app evals under `trails/eval/android/sample-app/<basename>.trail.yaml`
//     (e.g. `clipboard-round-trip.trail.yaml`). Each file's basename is camel-cased the same way
//     and joined into the same `GeneratedSampleAppTests` class.
//
// AGP's asset packager then ships everything under `<staging>` as `assets/`, so runtime
// `runFromAsset("trails/GeneratedSampleAppTests/<m>.trail.yaml")` calls — the path shape the
// plugin emits in its inline-rule mode — resolve via AssetManager.
//
// Excludes `node_modules/` and `install/` because AGP walks the asset src tree from many tasks
// (mergeAssets, lintAnalyze, lintReport, generateLintModel, …) and under Gradle 8.14 each one
// would trip an implicit-dependency validation error on the install tasks that *write into*
// those directories beneath `trails/config/`. The Copy depends on the install tasks so any
// consumer of the staged dir transitively gets the dependency, replacing per-task `dependsOn`
// whack-a-mole.
val stagedSampleAppTrailsRoot = layout.buildDirectory.dir("intermediates/staged-sample-app-trails")
val stagedSampleAppTrailsForPlugin =
  layout.buildDirectory.dir("intermediates/staged-sample-app-trails/trails")

val stageSampleAppTrails =
  tasks.register<Copy>("stageSampleAppTrailsForGenerator") {
    // Top-level `fun dashedToCamel(...)` would be a Gradle "script object reference" and the
    // configuration cache can't serialize that across builds. Defining the camel converter as a
    // local val inside the action keeps the closure self-contained and CC-clean.
    val dashedToCamel: (String) -> String = { raw ->
      raw
        .split("-")
        .mapIndexed { i, s -> if (i == 0) s else s.replaceFirstChar { c -> c.uppercase() } }
        .joinToString("")
    }
    // Instrumentation trails: `<cat>/<scenario>/android-phone.trail.yaml` →
    // `trails/GeneratedSampleAppTests/<scenarioCamel>.trail.yaml`.
    from("../android-sample-app/trails/android-ondevice-instrumentation") {
      exclude("**/node_modules/**", "**/install/**")
      eachFile {
        val segments = relativePath.segments
        if (segments.isNotEmpty() && segments.last().endsWith(".trail.yaml")) {
          require(segments.size >= 2) {
            "Unexpected sample-app trail path: ${relativePath.pathString}"
          }
          // Second-to-last segment is the scenario dir (e.g. `text-input`).
          val scenarioDir = segments[segments.size - 2]
          relativePath =
            org.gradle.api.file.RelativePath(
              true,
              "trails",
              "GeneratedSampleAppTests",
              "${dashedToCamel(scenarioDir)}.trail.yaml",
            )
        }
      }
      includeEmptyDirs = false
    }
    // Eval trails: `trails/eval/android/sample-app/<basename>.trail.yaml` →
    // `trails/GeneratedSampleAppTests/<basenameCamel>.trail.yaml`. Repo-root layout per
    // `trails/eval/README.md`; the eval set deliberately lives outside the sample-app dir so
    // the `pr_eval_android.sh` Square-POS-targeted runner's `find -maxdepth 1` doesn't scoop
    // them up.
    from("../../../trails/eval/android/sample-app") {
      eachFile {
        if (name.endsWith(".trail.yaml")) {
          val base = name.removeSuffix(".trail.yaml")
          relativePath =
            org.gradle.api.file.RelativePath(
              true,
              "trails",
              "GeneratedSampleAppTests",
              "${dashedToCamel(base)}.trail.yaml",
            )
        }
      }
      includeEmptyDirs = false
    }
    // Copy the trailmap `config/` subtree VERBATIM (no path rewriting). This ships the sample-app
    // trailmap definition and its scripted-tool sources — most importantly
    // `config/trailmaps/sampleapp/tools/quickjs-tools/pure.js`, which
    // `QuickJsToolBundleOnDeviceTest`
    // loads via `AndroidAssetBundleSource(assetPath =
    // "config/trailmaps/sampleapp/tools/quickjs-tools/pure.js")`.
    // The pre-conversion `stageTrailAssets` Copy copied all of `trails/` (including `config/`)
    // wholesale; the trail-YAML flattening in the two `from(...)` blocks above only handles the
    // executable trails, so this block preserves everything else that used to ride along.
    from("../android-sample-app/trails/config") {
      into("config")
      exclude("**/node_modules/**", "**/install/**")
    }
    into(stagedSampleAppTrailsRoot)
    dependsOn(installSampleAppMcpTools)
  }

// Wire the plugin's codegen task to depend on the staging Copy. The plugin's `trailsAssetsDir`
// is `@Internal`, so Gradle's standard "input directory carries producer info" inference doesn't
// apply — the dep has to be explicit. Going through `tasks.named(...)` rather than the
// extension's `generateTask.configure { ... }` proved more reliable on CI's fresh task graph
// (the extension-side wiring silently dropped on some CI builds).
tasks.named("generateAndroidTrailJUnitShells").configure { dependsOn(stageSampleAppTrails) }

// Point the plugin at the flattened staging dir and class-name everything under
// `GeneratedSampleAppTests`. No `baseClassFqn` → inline-rule mode with the OSS-default
// `xyz.block.trailblaze.android.AndroidTrailblazeRule` (no-arg). The generated source emits
// `@get:Rule val rule = AndroidTrailblazeRule()` + one `@Test fun <m>() =
// rule.runFromAsset("trails/GeneratedSampleAppTests/<m>.trail.yaml")` per staged file.
trailblazeAndroid {
  packageName = "xyz.block.trailblaze.examples.sampleapp.generated"
  // Static path — the producer dep is wired via the explicit `tasks.named(...).dependsOn(...)`
  // call above.
  trailsAssetsDir = stagedSampleAppTrailsForPlugin
  onlyClassNames = setOf("GeneratedSampleAppTests")
}

// Stage the typed `@trailblaze/scripting` bundle at a stable test-APK asset path so
// `QuickJsToolBundleOnDeviceTest` can load it via `AndroidAssetBundleSource`. Sourced
// from the bundling plugin's output so the build is the single source of truth — no
// checked-in pre-built bundle to drift. The `trailblaze.quickjs-bundle-assets` plugin
// (above) owns the per-bundle Copy task and the aggregating `stagingRoot` directory;
// this block just declares which bundle to stage and where it lands inside that root.
trailblazeQuickjsBundleAssets {
  register("sampleAppTyped") {
    bundleTask.set(
      project(":trailblaze-quickjs-tools").tasks.named("bundleSampleAppTypedAuthorTool")
    )
    assetPath.set("fixtures/quickjs/typed.bundle.js")
  }
}

android {
  namespace = "xyz.block.trailblaze.examples.sampleapp.uitests"
  compileSdk = 36
  defaultConfig {
    minSdk = 28
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  sourceSets {
    getByName("androidTest") {
      // Bundle the staged sample-app trails (under `trails/GeneratedSampleAppTests/…`) and the
      // typed QuickJS bundle in the test APK so runFromAsset() can read them. The trails dir
      // is sourced from the staged copy rather than the live tree so AGP asset consumers don't
      // walk into the install tasks' npm output.
      assets.srcDirs(
        stagedSampleAppTrailsRoot,
        trailblazeQuickjsBundleAssets.stagingRoot,
        "src/androidTest/assets",
      )
      // The plugin's generated source dir (GeneratedSampleAppTests.kt) and its dependency on
      // `generateAndroidTrailJUnitShells` are now auto-wired by `apply()` — no srcDir needed here.
    }
  }

  packaging {
    resources.excludes.add("META-INF/INDEX.LIST")
    resources.excludes.add("META-INF/AL2.0")
    resources.excludes.add("META-INF/LICENSE.md")
    resources.excludes.add("META-INF/LICENSE-notice.md")
    resources.excludes.add("META-INF/LGPL2.1")
    resources.excludes.add("META-INF/io.netty.versions.properties")
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  kotlin { compilerOptions { jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17 } }
  lint { abortOnError = false }
  testOptions { animationsDisabled = true }
}

// AGP wires staged-asset directories into `assets.srcDirs` lazily — its asset-merge, lint-model,
// and lint-analysis tasks all consume `srcDirs` but don't auto-import a `Provider`'s task
// dependency. Wiring [stageSampleAppTrails] and the QuickJS bundle staging tasks explicitly into
// the asset and lint task families closes that gap so they each run after the staging copies
// complete.
tasks
  .matching {
    val n = it.name
    (n.startsWith("merge") && n.endsWith("AndroidTestAssets")) ||
      (n.startsWith("package") && n.endsWith("AndroidTestAssets")) ||
      (n.startsWith("generate") && n.endsWith("AndroidTestLintModel")) ||
      (n.startsWith("lintAnalyze") && n.endsWith("AndroidTest")) ||
      (n.startsWith("lintReport") && n.endsWith("AndroidTest"))
  }
  .configureEach {
    dependsOn(stageSampleAppTrails)
    // Each registered QuickJS bundle has its own `stage<Name>QuickjsBundleAsset` task;
    // the plugin exposes them as a list so this AGP-task wiring stays a single line as
    // bundles get added.
    dependsOn(trailblazeQuickjsBundleAssets.allStagingTasks)
  }

dependencies {
  androidTestImplementation(project(":trailblaze-common"))
  androidTestImplementation(project(":trailblaze-android"))
  // The MCP-free QuickJS-tool runtime. Tests here exercise `QuickJsToolHost` and the
  // launcher path on-device through the new `AndroidAssetBundleSource` resolver.
  androidTestImplementation(project(":trailblaze-quickjs-tools"))
  androidTestImplementation(libs.junit)
  androidTestImplementation(libs.koog.prompt.executor.ollama)
  androidTestImplementation(libs.koog.prompt.executor.openai)
  androidTestImplementation(libs.koog.prompt.executor.openrouter)
  androidTestImplementation(libs.koog.prompt.executor.clients)
  androidTestImplementation(libs.koog.prompt.llm)
  androidTestImplementation(libs.ktor.client.core)
  androidTestImplementation(libs.kotlinx.datetime)
  androidTestRuntimeOnly(libs.androidx.test.runner)
  androidTestRuntimeOnly(libs.coroutines.android)
  androidTestImplementation(libs.maestro.orchestra.models) { isTransitive = false }
}
