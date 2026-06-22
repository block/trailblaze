plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.kotlin.android)
  id("trailblaze.spotless")
  // Reusable scaffolding for staging QuickJS author-tool bundles into the test APK
  // assets. Replaces what used to be a hand-rolled Copy + dependsOn block here so future
  // downstream test modules only register the bundle and asset path.
  id("trailblaze.quickjs-bundle-assets")
}

val installSampleAppMcpTools =
  project(":trailblaze-scripting-subprocess").tasks.named("installSampleAppMcpTools")

// Stage the sample-app trails into a build-output directory, filtering out `node_modules/`
// (and any sibling `install/` artifacts written by bun/npm). Two reasons:
//   1) AGP walks the asset src tree from many tasks (mergeAssets, lintAnalyze, lintReport,
//      generateLintModel, …); under Gradle 8.14 each one trips an implicit-dependency
//      validation error on the install tasks that *write into* `node_modules/` beneath
//      `trails/config/`. Excluding those subtrees means no AGP task ever reads from a
//      directory the install tasks write to, so the validator has nothing to flag —
//      regardless of which AGP task family is added next.
//   2) `node_modules/` would bloat the test APK with megabytes of npm packages that
//      runFromAsset() never opens.
//
// The copy depends on the install tasks so any consumer of the staged dir transitively
// gets the dependency, replacing the per-task `dependsOn` whack-a-mole that the matcher
// approach required.
val stagedTrailAssets = layout.buildDirectory.dir("intermediates/staged-trail-assets")

val stageTrailAssets =
  tasks.register<Copy>("stageTrailAssetsForAndroidTest") {
    from("../android-sample-app/trails") { exclude("**/node_modules/**", "**/install/**") }
    into(stagedTrailAssets)
    dependsOn(installSampleAppMcpTools)
  }

// Stage the repo-root `trails/eval/android/sample-app/` directory under the same
// asset root used by [stageTrailAssets]. Mirrors the [trails/eval/README.md]
// platform-only layout for agent evals, but slotted under a `sample-app/` subdir
// so the sample-app-targeted clipboard-round-trip eval doesn't get scooped up by
// the Square-POS-targeted `pr_eval_android.sh` runner (its `find -maxdepth 1` in
// `trails/eval/android/` deliberately ignores subdirectories). The auto-generated
// `GeneratedSampleAppTests` below scans this directory too so each `.trail.yaml`
// here becomes a JUnit test on the existing sample-app device-farm step.
val sampleAppEvalTrailsDir = file("../../../trails/eval/android/sample-app")
val stageSampleAppEvalTrails =
  tasks.register<Copy>("stageSampleAppEvalTrailAssetsForAndroidTest") {
    from(sampleAppEvalTrailsDir)
    into("${stagedTrailAssets.get().asFile}/eval/android/sample-app")
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
      // Bundle sample-app trails as assets in the test APK so runFromAsset() can read them.
      // The trails dir is sourced from the staged copy (see [stageTrailAssets]) rather than
      // the live tree so AGP asset consumers don't walk into the install tasks' npm output.
      assets.srcDirs(
        stagedTrailAssets,
        trailblazeQuickjsBundleAssets.stagingRoot,
        "src/androidTest/assets",
      )
      java.srcDirs("src/androidTest/java", "src/androidTest/generated")
    }
  }

  packaging {
    exclude("META-INF/INDEX.LIST")
    exclude("META-INF/AL2.0")
    exclude("META-INF/LICENSE.md")
    exclude("META-INF/LICENSE-notice.md")
    exclude("META-INF/LGPL2.1")
    exclude("META-INF/io.netty.versions.properties")
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  kotlin { compilerOptions { jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17 } }
  lint { abortOnError = false }
  testOptions { animationsDisabled = true }
}

// AGP wires the staged-asset directory into `assets.srcDirs` lazily — its asset-merge,
// lint-model, and lint-analysis tasks all consume `srcDirs` but don't auto-import a
// `Provider`'s task dependency. Wiring [stageTrailAssets] explicitly into the asset and
// lint task families closes that gap so they each run after the staging copy completes.
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
    dependsOn(stageTrailAssets)
    dependsOn(stageSampleAppEvalTrails)
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

// ---------------------------------------------------------------------------
// generateSampleAppTests
// Scans ../android-sample-app/trails/android-ondevice-instrumentation/**/*.trail.yaml
// and writes a JUnit test class so the sample-app trails can run on a remote device farm.
// Usage: ./gradlew :examples:android-sample-app-uitests:generateSampleAppTests
// ---------------------------------------------------------------------------
tasks.register("generateSampleAppTests") {
  description = "Generate JUnit instrumentation tests from trail YAML files for remote device farm"
  group = "trailblaze"

  val trailsDir = file("../android-sample-app/trails/android-ondevice-instrumentation")
  // Also pick up sample-app-targeted eval trails that live under the repo-root
  // `trails/eval/android/sample-app/` per the [trails/eval/README.md] platform-only
  // layout. Asset-staging for this dir is wired in [stageSampleAppEvalTrails] above —
  // files land in the test APK at `eval/android/sample-app/<name>.trail.yaml`.
  val evalSampleAppTrailsDir = sampleAppEvalTrailsDir
  val outputFile =
    file(
      "src/androidTest/generated/xyz/block/trailblaze/examples/sampleapp/generated/GeneratedSampleAppTests.kt"
    )

  inputs.dir(trailsDir)
  inputs.dir(evalSampleAppTrailsDir)
  outputs.file(outputFile)

  doLast {
    outputFile.parentFile.mkdirs()

    // Convert a dash-or-trail-file-prefix-style name to a camelCase JUnit test method.
    fun toMethodName(raw: String) =
      raw
        .split("-")
        .mapIndexed { i, s -> if (i == 0) s else s.replaceFirstChar { c -> c.uppercase() } }
        .joinToString("")

    val instrumentationTests =
      fileTree(trailsDir)
        .filter { it.name.endsWith(".trail.yaml") }
        .map { trailFile ->
          val relPath = trailFile.relativeTo(trailsDir).path
          val testDirName = relPath.split("/").let { it[it.size - 2] }
          val methodName = toMethodName(testDirName)
          val assetPath = "android-ondevice-instrumentation/$relPath"
          Pair(methodName, assetPath)
        }

    // Eval-sample-app trails live one file per scenario directly under
    // `trails/eval/android/sample-app/` (e.g. `clipboard-round-trip.trail.yaml`).
    // The JUnit method name is derived from the filename (sans `.trail.yaml`).
    val evalSampleAppTests =
      fileTree(evalSampleAppTrailsDir)
        .filter { it.name.endsWith(".trail.yaml") }
        .map { trailFile ->
          val relPath = trailFile.relativeTo(evalSampleAppTrailsDir).path
          val baseName = trailFile.name.removeSuffix(".trail.yaml")
          val methodName = toMethodName(baseName)
          val assetPath = "eval/android/sample-app/$relPath"
          Pair(methodName, assetPath)
        }

    val testMethods = (instrumentationTests + evalSampleAppTests).sortedBy { it.first }

    outputFile.writeText(
      buildString {
        appendLine("// AUTO-GENERATED — do not edit manually.")
        appendLine(
          "// Re-generate: ./gradlew :examples:android-sample-app-uitests:generateSampleAppTests"
        )
        appendLine()
        appendLine("package xyz.block.trailblaze.examples.sampleapp.generated")
        appendLine()
        appendLine("import org.junit.Rule")
        appendLine("import org.junit.Test")
        appendLine("import xyz.block.trailblaze.android.AndroidTrailblazeRule")
        appendLine()
        appendLine("class GeneratedSampleAppTests {")
        appendLine()
        appendLine("  @get:Rule val rule = AndroidTrailblazeRule()")
        appendLine()
        testMethods.forEach { (method, path) ->
          appendLine("  @Test fun $method() = rule.runFromAsset(\"$path\")")
        }
        appendLine("}")
      }
    )

    println("Generated ${testMethods.size} tests → ${outputFile.relativeTo(projectDir)}")
  }
}
