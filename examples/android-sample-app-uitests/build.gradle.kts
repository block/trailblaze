// The worked example for the published `xyz.block.trailblaze.android-gradle` plugin: a staging
// `Sync` that normalizes a source-of-truth trail layout into the plugin's contract, plus the
// `trailblazeAndroid { }` block that points the codegen at it.
// `trailblaze-android-gradle/README.md` sends readers here, and this is the only consumer of that
// plugin in this repo.
//
// WHAT RUNS THIS: CI's mandatory check does a repo-wide `assembleDebugAndroidTest`, so the staging
// Sync, the plugin's codegen, and the compile of every generated `GeneratedSampleAppTests` shell
// are exercised on every PR. A broken example is a red required check.
//
// WHAT DOESN'T: no CI step installs this APK and executes those generated tests. That is deliberate
// rather than a gap — the trails it stages are the sample app's own
// `android-ondevice-instrumentation` tree, which a device-farm step already replays on every PR.
// Running them here too would pay a second emulator per trail for identical coverage. The module's
// job is to prove the PLUGIN works end to end, which compiling the generated source does.
//
// It carries no hand-written tests, deliberately. It used to carry three; they ran on no CI step,
// and now live in `:trailblaze-android`'s androidTest, which is executed on a device. Keep it that
// way: a framework assertion belongs with the framework, not in an example.
plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.kotlin.android)
  id("trailblaze.spotless")
  // Auto-generates a JUnit shell per `<methodName>.trail.yaml` file or `<methodName>/trail.yaml`
  // recording directory under the configured trails directory, replacing the hand-rolled
  // `GenerateSampleAppTestsTask` this module used to carry.
  // The plugin is published to Maven Central and is the canonical way an external team adds
  // trail-derived JUnit tests to an Android library module — this example dogfoods it so the
  // OSS docs and the example stay in sync.
  id("xyz.block.trailblaze.android-gradle")
}

val installSampleAppMcpTools =
  project(":trailblaze-scripting-subprocess").tasks.named("installSampleAppMcpTools")

// Stage the sample-app trails into the layout the plugin expects:
// `<staging>/trails/GeneratedSampleAppTests/<methodName>.trail.yaml` for named trail files, or
// `<staging>/trails/GeneratedSampleAppTests/<methodName>/` for directory-per-test unified
// recordings. Two inputs are merged:
//
//  1) Instrumentation trails under
// `../android-sample-app/trails/android-ondevice-instrumentation/`,
//     where each scenario is either a named unified file `<category>/<scenario>.trail.yaml` or a
//     directory-per-test unified recording `<category>/<scenario>/trail.yaml` (the default
//     new-recording output). The Copy's `eachFile` flattens the named form to
//     `<scenarioCamel>.trail.yaml` (e.g. `forms/text-input.trail.yaml` → `textInput.trail.yaml`)
//     and relocates a recording directory wholesale to `<scenarioCamel>/<basename>` (e.g.
//     `taps/tap-interactions/trail.yaml` → `tapInteractions/trail.yaml`, keeping basenames so the
//     runtime can still pick the best file inside: classifier-specific recording → `trail.yaml` →
//     `blaze.yaml`). Either way a single `GeneratedSampleAppTests` class collects every scenario
//     as a `@Test fun <scenarioCamel>()`. (Non-`.trail.yaml` files outside a recording directory,
//     like the NL-only `mcp-tools/…/blaze.yaml`, are copied verbatim and never become generated
//     tests.)
//
//  2) Repo-root sample-app evals under `trails/eval/android/sample-app/<basename>.trail.yaml`
//     (e.g. `clipboard-round-trip.trail.yaml`). Each file's basename is camel-cased the same way
//     and joined into the same `GeneratedSampleAppTests` class. The recording-directory layout is
//     handled identically to input 1.
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

// Sync, not Copy: the destination must mirror the current source tree exactly. A plain Copy
// leaves stale files behind when a source trail is deleted or renamed — a removed scenario's
// staged file would keep generating a @Test (or keep tripping the plugin's misplacement gate)
// until a manual clean.
val stageSampleAppTrails =
  tasks.register<Sync>("stageSampleAppTrailsForGenerator") {
    // Top-level `fun dashedToCamel(...)` would be a Gradle "script object reference" and the
    // configuration cache can't serialize that across builds. Defining the camel converter (and
    // the relocation rule below) as local vals inside the action keeps the closures
    // self-contained and CC-clean.
    val dashedToCamel: (String) -> String = { raw ->
      raw
        .split("-")
        .mapIndexed { i, s -> if (i == 0) s else s.replaceFirstChar { c -> c.uppercase() } }
        .joinToString("")
    }
    // Relocation rule shared by both trail inputs — see the task-level comment for the mapping.
    // Order matters: the recording-directory check runs FIRST so a classifier-specific recording
    // (`<scenario>/android-phone.trail.yaml`) rides along with its directory instead of being
    // flattened into a named test of its own by the `.trail.yaml` branch.
    // The bare `trail.yaml` is the ONLY recording-directory sentinel, here and in the
    // android-gradle plugin's codegen: the unified recorder always emits it. A directory holding
    // only classifier-specific files (no bare trail.yaml) is out of contract — its classifier
    // file would be flattened into a bogus named test by the `.trail.yaml` branch below.
    val unifiedTrailFilename = "trail.yaml"
    val stageTrailFile: (org.gradle.api.file.FileCopyDetails) -> Unit = { details ->
      val fileName = details.relativePath.lastName
      val segments = details.relativePath.segments
      // Directory-per-test unified recording: the file's source directory holds a bare
      // `trail.yaml`, and that directory's name is the scenario. Relocate every file in the
      // directory (the bare trail.yaml AND its siblings) keeping basenames.
      val recordingDirName =
        if (
          segments.size >= 2 &&
            details.file.parentFile?.resolve(unifiedTrailFilename)?.isFile == true
        ) {
          segments[segments.size - 2]
        } else {
          null
        }
      if (recordingDirName != null) {
        // <category>/<scenario>/trail.yaml (3 segments) or <scenario>/trail.yaml (2). Anything
        // deeper would still relocate to a valid-looking staged recording dir — named after the
        // DEEPEST directory, a silent mis-name the plugin's placement gate can't see — so fail
        // here with the source path instead.
        if (segments.size > 3) {
          throw GradleException(
            "Trail recording directory is nested too deep to stage: " +
              "${details.file.parentFile}. Recordings must sit at " +
              "<category>/<scenario>/$unifiedTrailFilename (or <scenario>/$unifiedTrailFilename) " +
              "so the scenario directory names the generated test method."
          )
        }
        details.relativePath =
          org.gradle.api.file.RelativePath(
            true,
            "trails",
            "GeneratedSampleAppTests",
            dashedToCamel(recordingDirName),
            fileName,
          )
      } else if (fileName.endsWith(".trail.yaml")) {
        val scenario = fileName.removeSuffix(".trail.yaml")
        details.relativePath =
          org.gradle.api.file.RelativePath(
            true,
            "trails",
            "GeneratedSampleAppTests",
            "${dashedToCamel(scenario)}.trail.yaml",
          )
      } else if (fileName == unifiedTrailFilename) {
        // A bare trail.yaml directly at the input root has no scenario directory to name a test
        // after. Staging it verbatim would park it outside the plugin's trails/ view and the
        // recording would silently never run; failing here (rather than routing it into the
        // class dir for the plugin's misplacement gate) makes the error name the SOURCE file
        // instead of a staged build/intermediates path.
        throw GradleException(
          "Bare $unifiedTrailFilename at the trails root has no scenario directory to name a " +
            "generated test after: ${details.file}. Move it into a scenario directory " +
            "(<category>/<scenario>/$unifiedTrailFilename) or name it <scenario>.trail.yaml."
        )
      }
    }
    // Instrumentation trails: named unified `<cat>/<scenario>.trail.yaml` →
    // `trails/GeneratedSampleAppTests/<scenarioCamel>.trail.yaml`; directory-per-test recording
    // `<cat>/<scenario>/trail.yaml` → `trails/GeneratedSampleAppTests/<scenarioCamel>/…`. The
    // scenario name is the file's basename or recording-directory name (the pre-unified layout
    // put it in the parent dir; the camel-cased result is identical either way, so generated
    // test-method names are unchanged).
    from("../android-sample-app/trails/android-ondevice-instrumentation") {
      exclude("**/node_modules/**", "**/install/**")
      eachFile { stageTrailFile(this) }
      includeEmptyDirs = false
    }
    // Copy the trailmap `config/` subtree VERBATIM (no path rewriting). This ships the sample-app
    // trailmap definition and its scripted-tool sources, which the generated tests resolve their
    // `target:` against. The trail-YAML flattening in the `from(...)` block above only handles the
    // executable trails, so this block preserves everything else that has to ride along.
    from("../android-sample-app/trails/config") {
      into("config")
      exclude("**/node_modules/**", "**/install/**")
    }
    into(stagedSampleAppTrailsRoot)
    // The flattening drops the source category directory, so two same-shape scenarios with the
    // same name in different categories (`taps/foo` + `forms/foo`) land on the same staged path.
    // Gradle's default INCLUDE strategy silently keeps one and the other scenario's coverage
    // evaporates; fail instead. (A named file colliding with a recording DIRECTORY stages to
    // distinct paths — the plugin's duplicate-method gate catches that shape.)
    duplicatesStrategy = DuplicatesStrategy.FAIL
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
// rule.runFromAsset("trails/GeneratedSampleAppTests/<m>.trail.yaml")` per staged named file,
// or `rule.runFromAsset("trails/GeneratedSampleAppTests/<m>")` (the DIRECTORY path) per staged
// recording directory.
trailblazeAndroid {
  packageName = "xyz.block.trailblaze.examples.sampleapp.generated"
  // Static path — the producer dep is wired via the explicit `tasks.named(...).dependsOn(...)`
  // call above.
  trailsAssetsDir = stagedSampleAppTrailsForPlugin
  onlyClassNames = setOf("GeneratedSampleAppTests")
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
      assets.srcDirs(stagedSampleAppTrailsRoot, "src/androidTest/assets")
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
  .configureEach { dependsOn(stageSampleAppTrails) }

// Exactly what the plugin's generated `GeneratedSampleAppTests` needs to compile and, for anyone
// running it locally, to execute: the rule, its transitive runtime, and the JUnit surface. The
// module carries no hand-written tests, so nothing here exists for a test's own dependencies.
dependencies {
  androidTestImplementation(project(":trailblaze-common"))
  androidTestImplementation(project(":trailblaze-android"))
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
