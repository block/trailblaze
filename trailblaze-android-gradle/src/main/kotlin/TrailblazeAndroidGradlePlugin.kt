import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.IgnoreEmptyDirectories
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.TaskProvider

/**
 * Trailblaze's Android Gradle plugin. Two things, both driven by AGP integration:
 *
 * 1. **JUnit shell codegen** — generates Android JUnit shell classes from `.trail.yaml` files
 *    staged under the consumer's `androidTest` assets tree. The shell is the pure-boilerplate
 *    Kotlin class whose only job is to make a trail YAML discoverable by `AndroidJUnitRunner`.
 *    Under `src/androidTest/assets/trails/<ClassName>/`, two trail layouts each produce one
 *    `@Test` method:
 *    - `<methodName>.trail.yaml` — a named trail file; the filename is the method name.
 *    - `<methodName>/trail.yaml` — a directory-per-test unified recording (the default
 *      new-recording output, and what automated recording pipelines produce); the directory name is
 *      the method name. The emitted shell passes the DIRECTORY path, which the runtime resolves to
 *      the best file inside (device-classifier-specific recording → `trail.yaml` → `blaze.yaml`)
 *      via `TrailRecordings.findBestTrailResourcePath` — the same contract hand-written shells
 *      that pass a recording-directory path already rely on.
 *    The generator emits `class <ClassName> : <BaseClass>() { @Test fun <methodName>() =
 *    runFromAsset() }`. The runtime resolver
 *    (`TrailblazeYamlUtil.calculateTrailblazeYamlAssetPathFromStackTrace`) finds the YAML by
 *    stack-trace-derived simple-class + method name, so the directory name must equal the desired
 *    generated class's simple name — same convention humans already follow when authoring shells
 *    by hand. A bare unified `trail.yaml` anywhere the generated shells can't reach it (assets
 *    root, directly in a class dir, or nested deeper than one directory) fails the build — see
 *    [GenerateAndroidTrailJUnitShellsTask.failOnUnreachableBareUnifiedTrailFiles].
 * 2. **Scripted-tool bundling** (opt-in, via the nested [TrailblazeAndroidGradleExtension.trailmap]
 *    block) — pre-compiles a trailmap's TypeScript scripted tools into QuickJS bundles staged as
 *    `androidTest` assets, so an on-device test APK can dispatch them by name.
 *
 * ### Why this exists
 *
 * Pure-boilerplate `@Test fun foo() = runFromAsset()` shells are common across the Android
 * test modules: ~75 such files just to make YAMLs discoverable. Authors must duplicate
 * "class name in the assets directory" and "class name in the Kotlin shell" by hand. This plugin
 * removes the duplication: drop a `<ClassName>/<methodName>.trail.yaml` under the assets tree and
 * the matching shell is generated as part of the `androidTest` source set.
 *
 * Shells that need real Kotlin logic (TestWatcher cleanup, `@Before` setup, custom helper methods,
 * inline `runTools(...)` against Kotlin tool objects) are unaffected — they're still hand-written
 * Kotlin and live next to the generated shells.
 *
 * ### Usage
 *
 * This plugin requires `com.android.library` or `com.android.application` to be applied — enforced
 * by an `afterEvaluate` fail-fast that checks for the `android` extension those plugins register.
 * It reaches AGP's `sourceSets` by reflection (see `wireAgpSourceSets`), not a hard dependency, so
 * it stays version-agnostic. In return, the consumer writes no manual `android { sourceSets... }`
 * wiring: the generated shells and (when [TrailblazeAndroidGradleExtension.trailmap] is configured)
 * the staged bundle assets are auto-wired into AGP's `androidTest` source set, along with the
 * matching `dependsOn(...)` for the compile/lint/asset tasks.
 *
 * ```kotlin
 * plugins {
 *   alias(libs.plugins.android.library)
 *   alias(libs.plugins.kotlin.android)
 *   id("xyz.block.trailblaze.android-gradle")
 * }
 *
 * trailblazeAndroid {
 *   packageName = "xyz.block.trailblaze.evaluation"
 *   // baseClassFqn defaults to xyz.block.trailblaze.rules.SquareTrailblazeTest; override for
 *   // any other test base that exposes a `runFromAsset()` helper.
 *   // onlyClassNames is empty by default (= generate for every <ClassName>/ subdir);
 *   // restrict to specific shells during incremental rollout.
 *
 *   // Optional — absent by default (no bun/esbuild task registration at all).
 *   trailmap {
 *     id = "square"
 *     toolsDir = rootProject.file("path/to/trailmaps/square/tools")
 *   }
 * }
 * ```
 *
 * The generated output lands under
 * `build/generated/source/trailblazeTrails/androidTest/<package-path>/<ClassName>.kt` and is
 * gitignored by virtue of being under `build/`. Spotless ignores it (the spotless plugin's
 * `targetExclude` already drops everything under `build/`).
 */
class TrailblazeAndroidGradlePlugin : Plugin<Project> {
  override fun apply(project: Project) {
    val generate =
      project.tasks.register(
        "generateAndroidTrailJUnitShells",
        GenerateAndroidTrailJUnitShellsTask::class.java,
      ) { task ->
        task.group = "trailblaze"
        task.description =
          "Generates Android JUnit shells (one @Test method per <method>.trail.yaml file or " +
            "<method>/trail.yaml recording directory) from " +
            "src/androidTest/assets/trails/<ClassName>/ directories. Wired into the androidTest " +
            "source set by the consumer; emits to build/generated/source/trailblazeTrails/androidTest."
      }

    // Staging root every trailmap bundle lands under, at the on-device launcher's asset path
    // (`trails/config/trailmaps/<id>/tools/<name>.bundle.js`). Cheap to create unconditionally.
    val trailmapStagingRoot: Provider<Directory> =
      project.layout.buildDirectory.dir("intermediates/trailblaze/trailmap-tool-bundle-assets")

    val extension =
      project.extensions.create(
        "trailblazeAndroid",
        TrailblazeAndroidGradleExtension::class.java,
        generate,
        project,
        trailmapStagingRoot,
      )

    // Framework-source-tree convenience: default `sdkInstallTaskPath` to the sibling SDK-install
    // task when present, so trailmap bundle tasks chain to it with no extra config. External
    // consumers without that sibling project manage the install themselves.
    val candidateInstallPath = ":trailblaze-scripting-subprocess:installTrailblazeScriptingSdk"
    if (project.rootProject.findProject(":trailblaze-scripting-subprocess") != null) {
      extension.sdkInstallTaskPath.convention(candidateInstallPath)
    }

    // Conventions chosen to match the repo's existing on-disk convention
    // (assets/trails/<ClassName>/<methodName>.trail.yaml). Consumers override on the extension.
    extension.trailsAssetsDir.convention(
      project.layout.projectDirectory.dir("src/androidTest/assets/trails")
    )
    extension.generatedSourceDir.convention(
      project.layout.buildDirectory.dir("generated/source/trailblazeTrails/androidTest")
    )
    // Two patterns are supported (mutually exclusive — exactly one must resolve to a value):
    //   1. INLINE-RULE (OSS default): `@get:Rule val rule = <ruleClassFqn>()` + `rule.runFromAsset(path)`.
    //      Mirrors the OSS sample-app generator's emitted shape, so external consumers who follow
    //      the OSS norm get a working default without any extension config.
    //   2. EXTENDING-BASE: `class X : <baseClassFqn>()` + inherited `runFromAsset()`. Used by
    //      downstream modules that have a wrapping base test class (e.g. `SquareTrailblazeTest`).
    //
    // Both properties accept a Gradle-property override (`trailblaze.shellGenerator.<name>`), so
    // CI shards or one-off local runs can flip the active mode without editing the build file.
    // Only `ruleClassFqn` has a baseline default; if a consumer sets `baseClassFqn` explicitly
    // we treat the inline-rule default as overridden (resolution happens in the TaskAction, where
    // we can distinguish "convention default" from "explicit set" by looking at the gradle-property
    // override first).
    extension.baseClassFqn.convention(
      project.providers.gradleProperty("trailblaze.shellGenerator.baseClassFqn")
    )
    extension.ruleClassFqn.convention(
      project.providers
        .gradleProperty("trailblaze.shellGenerator.ruleClassFqn")
        .orElse("xyz.block.trailblaze.android.AndroidTrailblazeRule")
    )
    // SetProperty.get() / MapProperty.get() throw when never assigned, so the documented "leave it
    // empty = generate for every <ClassName>/ subdir" path was broken — pin empty conventions so a
    // consumer that omits the filter hits the no-op branch instead of a NoSuchElementException at
    // generate time.
    extension.onlyClassNames.convention(emptySet())
    extension.onlyMethodNames.convention(emptyMap())

    // Wire task inputs from the extension after defaults are in place. (Conventions on the
    // extension flow to the task only if we plumb them — the task is registered before the
    // extension so both can coexist on the extension's public surface.)
    generate.configure { task ->
      // `trailsAssetsDir` is `@Internal` on the task (see [GenerateAndroidTrailJUnitShellsTask]
      // for why); pass the extension's value straight through and let the task action handle the
      // missing-dir case at execution time. The matching `@InputFiles` `trailsAssetFiles` property
      // — derived from the same dir — is what Gradle tracks for change detection / cache key.
      task.trailsAssetsDir.set(extension.trailsAssetsDir)
      task.generatedSourceDir.set(extension.generatedSourceDir)
      task.packageName.set(extension.packageName)
      task.baseClassFqn.set(extension.baseClassFqn)
      task.ruleClassFqn.set(extension.ruleClassFqn)
      task.onlyClassNames.set(extension.onlyClassNames)
      task.onlyMethodNames.set(extension.onlyMethodNames)
    }

    // Auto-wire AGP's `androidTest`-shaped Kotlin compile + lint tasks to depend on [generate], so
    // a clean build can't race them against an empty generated source dir. Doing this here (instead
    // of asking each consumer to call a helper) follows from the plugin's whole reason for
    // existing — if a consumer applied this plugin and DIDN'T want the codegen to run before their
    // androidTest compile, they'd just not apply it. So the default-on shape is the right one.
    //
    // Matches four task-name families:
    //   - compile*AndroidTest*           (Kotlin/Java sources)
    //   - generate*AndroidTestLintModel  (AGP lint model)
    //   - lintAnalyze*AndroidTest        (AGP lint analyze)
    //   - lintReport*AndroidTest         (AGP lint report)
    //
    // Matched on task NAMES, not types — works regardless of AGP's own configuration timing.
    project.tasks
      .matching { task ->
        val n = task.name
        (n.startsWith("compile") && n.contains("AndroidTest")) ||
          (n.startsWith("generate") && n.endsWith("AndroidTestLintModel")) ||
          (n.startsWith("lintAnalyze") && n.endsWith("AndroidTest")) ||
          (n.startsWith("lintReport") && n.endsWith("AndroidTest"))
      }
      .configureEach { it.dependsOn(generate) }

    // AGP-aware auto-wiring, deferred to `afterEvaluate` so it runs after AGP's own `android { }`
    // configuration settles, regardless of `plugins { }` block ordering — the same timing
    // `gradle/merged-trails.gradle.kts` uses for the identical reason.
    project.afterEvaluate {
      val android = project.extensions.findByName("android")
      if (android == null) {
        throw GradleException(
          "xyz.block.trailblaze.android-gradle requires `com.android.library` or " +
            "`com.android.application` to be applied to this project — this plugin exists to wire " +
            "JUnit-shell codegen (and, when configured, scripted-tool bundling) into AGP's " +
            "`androidTest` source set, and has nothing to wire without one of those. Apply " +
            "`alias(libs.plugins.android.library)` (or `.application`), or remove this plugin if " +
            "this project doesn't build an Android test APK."
        )
      }
      wireAgpSourceSets(project, android, extension)
    }
  }
}

/**
 * The AGP-specific half of `apply()`'s wiring, split out for readability. Auto-wires:
 *  - [TrailblazeAndroidGradleExtension.generatedSourceDir] into the `androidTest` Java source set.
 *  - The trailmap staging root into the `androidTest` assets source set, but ONLY when
 *    [TrailblazeAndroidGradleExtension.trailmap] was actually called. Gated (rather than always
 *    wiring an empty dir) so a module that later removes its last `trailmap { }` block doesn't
 *    keep shipping whatever stale `.bundle.js` files happen to still be sitting in that build
 *    directory from a prior build — an empty `dependsOn` wouldn't stop AGP from picking up
 *    leftover files already on disk.
 *  - The AGP asset-merge / lint task families to `dependsOn` the trailmap staging tasks.
 *
 * [android] is accessed entirely by REFLECTION, not a typed `com.android.build.gradle.
 * BaseExtension` — matches `gradle/merged-trails.gradle.kts`'s pattern for the same problem: many
 * AGP versions across many consumers, no reason to pin one just for four stable methods.
 */
private fun wireAgpSourceSets(
  project: Project,
  android: Any,
  extension: TrailblazeAndroidGradleExtension,
) {
  @Suppress("UNCHECKED_CAST")
  val sourceSets =
    try {
      reflectAgpCall(android, "getSourceSets") as NamedDomainObjectContainer<Any>
    } catch (e: ClassCastException) {
      throw agpReflectionError(android, "getSourceSets", e)
    }
  sourceSets.named("androidTest").configure { androidTest ->
    reflectSrcDir(androidTest, "getJava", extension.generatedSourceDir)
    if (extension.allStagingTasks.isNotEmpty()) {
      reflectSrcDir(androidTest, "getAssets", extension.stagingRoot)
    }
  }

  if (extension.allStagingTasks.isNotEmpty()) {
    project.tasks
      .matching { task ->
        val n = task.name
        (n.startsWith("merge") && n.endsWith("AndroidTestAssets")) ||
          (n.startsWith("package") && n.endsWith("AndroidTestAssets")) ||
          (n.startsWith("generate") && n.endsWith("AndroidTestLintModel")) ||
          (n.startsWith("lintAnalyze") && n.endsWith("AndroidTest")) ||
          (n.startsWith("lintReport") && n.endsWith("AndroidTest"))
      }
      .configureEach { it.dependsOn(extension.allStagingTasks) }
  }
}

/** `sourceSet.<getterName>().srcDir(value)`, reflected — see [wireAgpSourceSets]'s kdoc for why. */
private fun reflectSrcDir(sourceSet: Any, getterName: String, value: Any) {
  val dirSet = reflectAgpCall(sourceSet, getterName)
  try {
    dirSet.javaClass.getMethod("srcDir", Any::class.java).invoke(dirSet, value)
  } catch (e: ReflectiveOperationException) {
    throw agpReflectionError(dirSet, "srcDir", e)
  }
}

/**
 * `target.<methodName>()`, reflected. A raw [NoSuchMethodException] / [ClassCastException] from
 * deep inside reflection code isn't actionable, so every reflective AGP call is funneled through
 * here (or the `srcDir` try/catch above) to surface [agpReflectionError] instead.
 */
private fun reflectAgpCall(target: Any, methodName: String): Any =
  try {
    target.javaClass.getMethod(methodName).invoke(target)
  } catch (e: ReflectiveOperationException) {
    throw agpReflectionError(target, methodName, e)
  }

private fun agpReflectionError(target: Any, methodName: String, cause: Throwable): GradleException =
  GradleException(
    "xyz.block.trailblaze.android-gradle: AGP's `${target.javaClass.name}` doesn't expose " +
      "`$methodName(...)` the way this plugin expects it to (via reflection — see " +
      "`wireAgpSourceSets`'s kdoc) — this plugin may be incompatible with your AGP version.",
    cause,
  )

/**
 * DSL container for [TrailblazeAndroidGradlePlugin] — configures codegen inputs and the optional
 * [trailmap] scripted-tool bundling. `apply()` auto-wires both into AGP's `androidTest` source set
 * (see `wireAgpSourceSets`); consumers don't write source-set or `dependsOn` wiring by hand.
 */
abstract class TrailblazeAndroidGradleExtension
@Inject
constructor(
  /**
   * Task provider for the codegen — `apply()` already auto-wires AGP's androidTest compile/lint
   * tasks and source set against it, so consumers rarely need this directly.
   */
  val generateTask: TaskProvider<GenerateAndroidTrailJUnitShellsTask>,
  private val project: Project,
  /**
   * Staging root every [trailmap] bundle lands under — auto-wired into AGP's `androidTest` assets
   * source set. Empty when [trailmap] is never called.
   */
  val stagingRoot: Provider<Directory>,
) {
  /**
   * Staging `Copy` tasks registered by [trailmap], one per tool — auto-wired into the AGP
   * asset-task `dependsOn(...)`. Empty (a no-op dependency) when [trailmap] is never called.
   */
  val allStagingTasks: MutableList<TaskProvider<Copy>> = mutableListOf()

  /**
   * Optional TypeScript SDK directory [trailmap] bundles against (`node_modules/.bin/esbuild`,
   * `src/in-process.ts`, `tools/in-process-wrapper-template.mjs`). Unset walks up from
   * `rootProject.projectDir` for `sdks/typescript/package.json` (the in-monorepo default).
   */
  abstract val sdkDir: DirectoryProperty

  /**
   * Optional task path each [trailmap] bundle task should `dependsOn` (e.g. a `bun install`
   * step). Defaults to `:trailblaze-scripting-subprocess:installTrailblazeScriptingSdk` when that
   * project is present; external consumers leave it unset and manage the install themselves.
   */
  abstract val sdkInstallTaskPath: Property<String>

  /**
   * Bundle + stage every in-process scripted tool in a trailmap's `tools/` directory. Absent by
   * default (no bun/esbuild task registration at all). Call more than once to bundle more than one
   * trailmap — each call lands in the shared [stagingRoot].
   *
   * ```kotlin
   * trailmap {
   *   id = "square"
   *   toolsDir = rootProject.file("path/to/trailmaps/square/tools")
   * }
   * ```
   */
  fun trailmap(configure: TrailmapToolBundleSpec.() -> Unit) {
    val spec = project.objects.newInstance(TrailmapToolBundleSpec::class.java)
    spec.configure()
    registerTrailmapToolBundle(project, this, spec)
  }

  /**
   * Root assets directory the generator scans. Each direct subdirectory becomes one generated
   * Kotlin class with the subdir's name as the simple class name.
   *
   * Default: `src/androidTest/assets/trails` (matches the runtime resolver's lookup convention).
   */
  abstract val trailsAssetsDir: DirectoryProperty

  /**
   * Where the generator writes its output. Point AGP's `sourceSets.androidTest.java.srcDir(...)` at
   * this directory.
   *
   * Default: `build/generated/source/trailblazeTrails/androidTest`.
   */
  abstract val generatedSourceDir: DirectoryProperty

  /**
   * Package for the generated classes. No default, because picking one for the consumer silently
   * could place classes outside the test APK's runner-scanned packages — required only when there
   * are trails to generate shells for; a [trailmap]-only consumer can leave it unset.
   *
   * The package does not have to match the physical asset directory layout: the runtime resolver
   * (`TrailblazeYamlUtil.calculateTrailblazeYamlAssetPathFromStackTrace`) tries three candidate
   * paths in order and one of them (`trails/<simpleClassName>/<methodName>.trail.yaml`) ignores the
   * package, so any package the AGP `androidTest` runner scans is fine.
   */
  abstract val packageName: Property<String>

  /**
   * **Opt-in to the extending-base pattern.** Fully-qualified name of the JUnit base class each
   * generated shell extends. The base must expose a `runFromAsset(): ...` method visible to
   * subclasses. Mutually exclusive with [ruleClassFqn] — set exactly one.
   *
   * Emitted shape:
   * ```
   * class <Class> : <baseClassFqn>() {
   *   @Test fun <method>() = runFromAsset()
   * }
   * ```
   *
   * Defaults to UNSET, which makes [ruleClassFqn] active (the OSS-standard inline-rule pattern).
   * Downstream modules that have their own base class set this explicitly — e.g.
   * `baseClassFqn = "com.example.app.uitests.MyAuthedTrailblazeTest"`. CI/command-line override
   * is also available via the Gradle property `trailblaze.shellGenerator.baseClassFqn`.
   *
   * **This is the right knob for rules that require constructor arguments** (an `appId`, a target,
   * an account, a custom tool surface, …). [ruleClassFqn] emits `<RuleClass>()`, which only fits
   * no-arg rules; an arg-bearing rule goes inside a JUnit base class that owns the `@Rule` field
   * with the args set, and `baseClassFqn` points at that base. See the README's
   * "Using a rule that needs constructor args" section for the worked example.
   */
  abstract val baseClassFqn: Property<String>

  /**
   * **Inline-rule pattern (OSS default).** Fully-qualified name of the JUnit `@Rule` class each
   * generated shell instantiates. The rule must have a no-arg constructor and expose a
   * `runFromAsset(path: String): ...` method (the open-source
   * `xyz.block.trailblaze.android.AndroidTrailblazeRule` does — that's why it's the default).
   * Mutually exclusive with [baseClassFqn] — set exactly one.
   *
   * Emitted shape:
   * ```
   * class <Class> {
   *   @get:Rule val rule = <ruleClassFqn>()
   *   @Test fun <method>() =
   *     rule.runFromAsset("trails/<Class>/<method>.trail.yaml")
   * }
   * ```
   *
   * Default: `xyz.block.trailblaze.android.AndroidTrailblazeRule` (the OSS standard — see
   * `examples/android-sample-app-uitests/.../GeneratedSampleAppTests.kt`). CI/command-line override
   * is available via the Gradle property `trailblaze.shellGenerator.ruleClassFqn`.
   *
   * **Rules that need constructor arguments don't fit this mode** — the emit shape is `<RuleClass>()`,
   * which has no syntax for passing them. Use [baseClassFqn] instead: write a thin JUnit base class
   * that owns the `@Rule` field with the arguments set, and point `baseClassFqn` at the base. The
   * README's "Using a rule that needs constructor args" section walks through the worked example.
   */
  abstract val ruleClassFqn: Property<String>

  /**
   * Optional allow-list of class-directory names to generate shells for. When empty (the default),
   * the generator emits a shell for every direct subdirectory of [trailsAssetsDir]. When non-empty,
   * only subdirectories whose name appears in the set are converted — the rest are left untouched
   * so an incremental rollout can convert one shell at a time without flipping a whole module.
   *
   * Useful in the PoC phase where a module has both auto-generatable (a)-bucket shells and
   * hand-written (b)/(c)-bucket shells co-located under the same assets tree.
   */
  abstract val onlyClassNames: org.gradle.api.provider.SetProperty<String>

  /**
   * Optional **per-class** allow-list of trail-method names to generate. When a class name appears
   * here with a non-empty value, the generator emits a `@Test` for only those methods in that
   * class — every other `<methodName>.trail.yaml` in the same class directory is silently skipped.
   * Classes NOT in the map keep their default behavior (every trail becomes a `@Test`).
   *
   * Composes with [onlyClassNames]: a class still has to clear that filter (when set) before its
   * per-method allow-list is consulted.
   *
   * Example — drop a CI shard to a fast-PR-check subset without removing trails from disk:
   * ```
   * trailblazeAndroid {
   *   onlyClassNames = setOf("LoginFlowTest")
   *   onlyMethodNames.put("LoginFlowTest", setOf("happyPath", "invalidCredentials"))
   * }
   * ```
   *
   * A method name in this map that does NOT match an existing `<methodName>.trail.yaml` is a hard
   * error at generate time (typo guard — same shape as the existing `onlyClassNames` typo guard;
   * silently emitting zero methods would mean "method not found" at test runtime, not build time).
   */
  abstract val onlyMethodNames: MapProperty<String, Set<String>>
}

/**
 * The codegen task. Walks [trailsAssetsDir], emits one Kotlin source file per direct subdirectory
 * that contains at least one trail — a named `*.trail.yaml` file or a `<method>/trail.yaml`
 * recording directory. Plain text output (no KotlinPoet) — the generated source is a fixed, simple
 * shape, so the cost of pulling KotlinPoet onto build-logic's classpath would dwarf the value.
 *
 * Marked `@CacheableTask` because output is a pure function of `(packageName, baseClassFqn,
 * onlyClassNames, every trail filename/directory-name under trailsAssetsDir)` — inputs Gradle
 * already tracks via the annotated properties. The actual YAML *contents* don't enter the rendered
 * shell (only file/directory names do), so PathSensitivity.RELATIVE on the assets dir lets the
 * cache match across worktrees / CI agents.
 */
@org.gradle.api.tasks.CacheableTask
abstract class GenerateAndroidTrailJUnitShellsTask : DefaultTask() {
  // @Internal — the path itself is NOT a tracked input. Two reasons:
  //   1. @InputDirectory + @Optional rejects a non-null property whose path doesn't exist on disk,
  //      so it can't accommodate "the upstream Copy will create the dir before this task runs"
  //      (real failure mode when a consumer points `trailsAssetsDir` at a build-output directory).
  //      The earlier `.filter { it.asFile.isDirectory }` workaround broke this — the filter
  //      evaluates the first time the property is queried (at config time, before the Copy ran),
  //      returns absent, and the result is cached on the Provider chain so subsequent queries
  //      after the Copy runs still see absent. Observed as the "no assets directory at <unset>"
  //      failure mode observed when a consumer's staging Copy hadn't produced its dir yet.
  //   2. The early-rollout case (plugin applied before any trail YAMLs exist) also needs the task
  //      to gracefully no-op, not fail validation.
  //
  // Tracking the file CONTENTS via [trailsAssetFiles] below gives Gradle proper change-detection
  // (cache key + up-to-date) while letting the path itself live in a non-validated Property the
  // task action can query at execution time.
  @get:Internal abstract val trailsAssetsDir: DirectoryProperty

  /**
   * Injected so [trailsAssetFiles] can build an empty FileTree fallback without calling
   * `Task.project` at execution time (illegal under the configuration cache).
   */
  @get:Inject
  internal abstract val objectFactoryForTrails: org.gradle.api.model.ObjectFactory

  /**
   * Gradle-tracked input: the `*.trail.yaml` files under [trailsAssetsDir]. Empty when the
   * directory doesn't exist (early rollout) or contains no trails — the task action handles that
   * case with a "no shells generated" lifecycle log instead of failing validation.
   *
   * Also tracks bare `trail.yaml` files (which `*.trail.yaml` does NOT match — no `.` before
   * `trail`): a `<ClassName>/<method>/trail.yaml` recording directory produces a `@Test` method,
   * and misplaced bare files hard-error (see [failOnUnreachableBareUnifiedTrailFiles]) — either
   * way, a bare file appearing or disappearing after a successful build must re-run the task, not
   * leave it UP-TO-DATE. Tracked at ALL depths because the misplacement gate walks owned class
   * dirs recursively. The only over-track is the exempt top-level `config/` tree — accepted: a
   * bare `trail.yaml` there is vanishingly rare (that tree holds trailmap/target YAML and tool
   * bundles), and the cost is one spurious re-run, not a wrong result.
   *
   * Path-sensitivity is `RELATIVE` because the renderer reads only file/directory names, not
   * absolute paths — same as the original `@InputDirectory` had. `@IgnoreEmptyDirectories` matches
   * the original behavior of skipping intermediate empty dirs.
   */
  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  @get:IgnoreEmptyDirectories
  val trailsAssetFiles: org.gradle.api.file.FileTree
    get() =
      trailsAssetsDir
        .map { dir ->
          dir.asFileTree.matching {
            it.include("**/*.trail.yaml")
            it.include("**/$BARE_UNIFIED_TRAIL_FILENAME")
          }
        }
        .getOrElse(objectFactoryForTrails.fileCollection().asFileTree)

  // @Optional at the TASK level too — Gradle's input-validation rejects an unset non-optional
  // @Input before the TaskAction runs at all, regardless of the action's own check order below.
  // The TaskAction still throws its own directed error when there's real codegen work to do.
  @get:Input @get:Optional abstract val packageName: Property<String>

  // Both modes are @Optional individually; the TaskAction enforces "exactly one of the two" at
  // runtime with a directed error. Both annotated @Input so the cache key reflects whichever is
  // active.
  @get:Input @get:Optional abstract val baseClassFqn: Property<String>

  @get:Input @get:Optional abstract val ruleClassFqn: Property<String>

  @get:Input
  @get:Optional
  abstract val onlyClassNames: org.gradle.api.provider.SetProperty<String>

  @get:Input @get:Optional abstract val onlyMethodNames: MapProperty<String, Set<String>>

  @get:OutputDirectory abstract val generatedSourceDir: DirectoryProperty

  @TaskAction
  fun generate() {
    val outRoot = generatedSourceDir.get().asFile
    // Clean previous output so removed/renamed trails don't leave stale shells behind. Output is
    // entirely build-tree-owned and gitignored, so a recursive delete is safe. Done unconditionally
    // (even in the no-op branches below) so stale output from a prior config doesn't linger.
    outRoot.deleteRecursively()

    // trailsAssetsDir is @Optional — `.orNull` so an absent/non-existent dir falls through to the
    // no-op log instead of throwing. Covers early-rollout AND trailmap-only consumers alike;
    // checked BEFORE `packageName` below so the latter isn't required when there's nothing to do.
    val assetsDir = trailsAssetsDir.orNull?.asFile
    if (assetsDir == null || !assetsDir.isDirectory) {
      logger.lifecycle(
        "xyz.block.trailblaze.android-gradle: no assets directory at " +
          "${assetsDir ?: "<unset>"} — no shells generated."
      )
      // generatedSourceDir is a declared @OutputDirectory — Gradle expects it to exist after a
      // successful execution (a missing declared output means the task can never be UP-TO-DATE).
      outRoot.mkdirs()
      return
    }

    val classDirs =
      (assetsDir.listFiles() ?: emptyArray())
        .asSequence()
        .filter { it.isDirectory }
        .sortedBy { it.name }
        .toList()

    // Ownership: with an empty allow-list the generator owns every direct subdirectory EXCEPT the
    // top-level `config/` dir — that's the documented non-codegen tree (trailmap/target YAML,
    // mirroring the on-device `trails/config/**` asset convention). With a non-empty allow-list it
    // owns exactly the listed dirs (an explicit entry named `config` is honored as a class dir
    // like any other); non-listed dirs back hand-written shells and are left untouched.
    val allowList = onlyClassNames.get()
    val ownedClassDirs =
      if (allowList.isEmpty()) classDirs.filterNot { it.name == NON_CODEGEN_CONFIG_DIR_NAME }
      else classDirs.filter { it.name in allowList }

    if (allowList.isNotEmpty()) {
      val unknown = allowList - ownedClassDirs.map { it.name }.toSet()
      if (unknown.isNotEmpty()) {
        // Hard error rather than warning — a typo in `onlyClassNames` would silently produce no
        // output and the developer would see "method not found" at test runtime, not at build
        // time. Checked before the "no trails" early return below so an allow-list made entirely
        // of typos still errors instead of no-op'ing.
        throw GradleException(
          "trailblazeAndroid.onlyClassNames references directories that do not " +
            "exist under $assetsDir: $unknown. Available directories: ${classDirs.map { it.name }}."
        )
      }
    }

    // Reject misplaced bare unified `trail.yaml` files BEFORE the no-op early returns below — a
    // class dir holding only a misplaced bare file would otherwise fall into the "no trails" soft
    // log and the recording would silently never get a @Test.
    failOnUnreachableBareUnifiedTrailFiles(assetsDir = assetsDir, ownedClassDirs = ownedClassDirs)

    // A subdirectory alone doesn't mean there's a shell to generate — e.g. a trailmap-only
    // consumer's `assets/trails/config/**` (trailmap/target YAML, unrelated to codegen) would
    // otherwise count as "work to do" and wrongly demand `packageName`. Require at least one
    // trail (named file or recording directory) in an owned class dir before treating this as
    // real codegen work.
    val methodsByClassDir = ownedClassDirs.associateWith { discoverTrailMethods(it) }
    if (methodsByClassDir.values.all { it.isEmpty() }) {
      logger.lifecycle(
        "xyz.block.trailblaze.android-gradle: $assetsDir has no <ClassName>/<method>.trail.yaml " +
          "files or <ClassName>/<method>/$BARE_UNIFIED_TRAIL_FILENAME recording directories " +
          "— no shells generated."
      )
      outRoot.mkdirs()
      return
    }

    // From here on there IS at least one shell to generate, so packageName is load-bearing.
    val pkg =
      packageName.orNull?.takeIf { it.isNotBlank() }
        ?: throw GradleException(
          "xyz.block.trailblaze.android-gradle: trailblazeAndroid " +
            ".packageName is not set. Set it to the package the generated classes should live in, " +
            "e.g. `packageName = \"xyz.block.trailblaze.evaluation\"`."
        )
    validatePackageName(pkg)

    // Mode resolution: a consumer may set EITHER baseClassFqn (extending-base, pattern A) OR
    // ruleClassFqn (inline-rule, pattern B). Setting both is an error — they'd produce conflicting
    // emit shapes. Setting neither cannot happen by default (ruleClassFqn has a convention
    // default), but we still gate on it for clarity.
    val explicitBase = baseClassFqn.orNull?.takeIf { it.isNotBlank() }
    val explicitRule = ruleClassFqn.orNull?.takeIf { it.isNotBlank() }
    val mode: TestHostMode =
      when {
        explicitBase != null && explicitRule != null -> {
          // If both are explicitly set we can't tell which one the consumer meant. Reject — but
          // ONLY if the rule isn't carrying its convention default (otherwise a consumer who
          // legitimately wants pattern A would have to also unset ruleClassFqn, which is unfriendly).
          // The way to tell: if ruleClassFqn equals the documented default AND baseClassFqn is set,
          // assume the consumer wants pattern A and the rule default was just inert.
          if (explicitRule == DEFAULT_RULE_FQN) TestHostMode.BaseClass(explicitBase)
          else
            throw GradleException(
              "trailblazeAndroid: set exactly ONE of `baseClassFqn` (extending-base " +
                "pattern) or `ruleClassFqn` (inline-rule pattern), not both. Got " +
                "`baseClassFqn=$explicitBase`, `ruleClassFqn=$explicitRule`."
            )
        }
        explicitBase != null -> TestHostMode.BaseClass(explicitBase)
        explicitRule != null -> TestHostMode.InlineRule(explicitRule)
        else ->
          throw GradleException(
            "trailblazeAndroid: set either `baseClassFqn` (extending-base) or " +
              "`ruleClassFqn` (inline-rule). The latter defaults to " +
              "`$DEFAULT_RULE_FQN`, so this should not be reachable unless the default was " +
              "cleared deliberately."
          )
      }
    validateTestHostFqn(mode)

    val pkgDir = outRoot.resolve(pkg.replace('.', '/'))
    pkgDir.mkdirs()

    val methodAllowLists = onlyMethodNames.get()
    var generatedCount = 0
    val generatedClassNames = mutableListOf<String>()
    ownedClassDirs.forEach { classDir ->
      val className = classDir.name
      validateSimpleName(className, source = "directory name under $assetsDir")
      val allMethods = methodsByClassDir.getValue(classDir)
      if (allMethods.isEmpty()) {
        logger.lifecycle(
          "xyz.block.trailblaze.android-gradle: $className has no <method>.trail.yaml files or " +
            "<method>/$BARE_UNIFIED_TRAIL_FILENAME recording directories — skipping."
        )
        return@forEach
      }
      val allMethodNames = allMethods.map { it.methodName }
      // Per-class method allow-list: class not in the map → keep all trails (default). Class with
      // a non-empty allow-list → keep only the named methods, hard-error on names that don't match
      // an actual trail (typo guard, same shape as the onlyClassNames typo guard above — silently
      // emitting zero methods would mean "method not found" at test runtime, not build time).
      val perClassAllow = methodAllowLists[className]
      val methods =
        if (perClassAllow.isNullOrEmpty()) {
          allMethods
        } else {
          val unknown = perClassAllow - allMethodNames.toSet()
          if (unknown.isNotEmpty()) {
            throw GradleException(
              "trailblazeAndroid.onlyMethodNames[\"$className\"] references trails that do not " +
                "exist under $classDir: $unknown. Available trails: $allMethodNames."
            )
          }
          allMethods.filter { it.methodName in perClassAllow }
        }
      methods.forEach { validateMethodName(it.methodName, source = it.source) }
      // Duplicate + case-insensitive collision checks run against the on-disk truth
      // (`allMethods`), not the filtered subset, so a class-dir invariant violation can't be
      // masked by an `onlyMethodNames` filter that happens to drop one side of the collision.
      // Exact-name duplicates are now genuinely reachable: `foo.trail.yaml` and `foo/trail.yaml`
      // in the same class dir both derive the method name `foo`.
      val duplicateGroups = allMethods.groupBy { it.methodName }.filterValues { it.size > 1 }
      require(duplicateGroups.isEmpty()) {
        val listing =
          duplicateGroups.entries.joinToString("\n") { (name, collided) ->
            "  - `$name` from: ${collided.map { it.source }}"
          }
        "Duplicate trail method names under $classDir:\n$listing\n" +
          "Each @Test method name must be unique within its class directory — a named " +
          "`<methodName>.trail.yaml` file and a `<methodName>/$BARE_UNIFIED_TRAIL_FILENAME` " +
          "recording directory collide when they share the same <methodName>."
      }
      // Case-insensitive collision check — APFS/HFS+ (macOS default) and NTFS surface
      // `foo.trail.yaml` and `FOO.trail.yaml` as two distinct entries, but `pkgDir.resolve(name)`
      // collides on those filesystems so one method would silently disappear from the emitted
      // class. Fail loudly with both original names so the developer can pick which to keep.
      val caseInsensitiveDups =
        allMethodNames.groupBy { it.lowercase() }.values.filter { it.size > 1 }
      require(caseInsensitiveDups.isEmpty()) {
        "Case-insensitive trail-name collision under $classDir: $caseInsensitiveDups. " +
          "On case-insensitive filesystems (APFS / HFS+ / NTFS) these would write to the same " +
          "generated file — rename one of each colliding pair."
      }

      val source = renderShell(pkg, className, mode, methods)
      pkgDir.resolve("$className.kt").writeText(source, Charsets.UTF_8)
      generatedClassNames += className
      generatedCount += 1
    }

    logger.lifecycle(
      "xyz.block.trailblaze.android-gradle: generated $generatedCount shell(s) " +
        "(${generatedClassNames.sorted()}) under ${outRoot.path}."
    )
  }

  /**
   * Discovers the `@Test` methods a class directory produces. Two layouts, each one method:
   * - `<methodName>.trail.yaml` — a named trail file directly in the class dir; the filename is
   *   the method name.
   * - `<methodName>/trail.yaml` — a directory-per-test unified recording (the default
   *   new-recording output and what automated recording pipelines produce); the directory name is
   *   the method name. Detected by the bare [BARE_UNIFIED_TRAIL_FILENAME] as a direct child —
   *   sibling files inside the recording dir (classifier-specific recordings, `blaze.yaml`) don't
   *   add methods; the runtime picks the best one at execution time.
   *
   * The bare [BARE_UNIFIED_TRAIL_FILENAME] is the ONLY recording-directory sentinel, relying on
   * the invariant that the unified recorder always emits one. A directory holding only
   * classifier-specific files (`<methodName>/android-phone.trail.yaml`, no bare file) is out of
   * contract and gets no method — device-classifier names aren't knowable here, so such a dir is
   * structurally indistinguishable from a class dir of named trail files.
   *
   * Returned sorted by method name so the emitted shell is deterministic across filesystems.
   */
  private fun discoverTrailMethods(classDir: java.io.File): List<TrailMethod> {
    val children = classDir.listFiles() ?: return emptyList()
    val namedTrails =
      children
        .filter { it.isFile && it.name.endsWith(".trail.yaml") }
        .map {
          TrailMethod(
            methodName = it.name.removeSuffix(".trail.yaml"),
            isRecordingDir = false,
            source = "${classDir.name}/${it.name}",
          )
        }
    val recordingDirs =
      children
        .filter { it.isDirectory && java.io.File(it, BARE_UNIFIED_TRAIL_FILENAME).isFile }
        .map {
          TrailMethod(
            methodName = it.name,
            isRecordingDir = true,
            source = "${classDir.name}/${it.name}/$BARE_UNIFIED_TRAIL_FILENAME",
          )
        }
    return (namedTrails + recordingDirs).sortedBy { it.methodName }
  }

  /**
   * Fails the build when a bare unified `trail.yaml` sits somewhere no generated shell can reach:
   * - directly under [assetsDir] (`trails/trail.yaml`) — no class directory, no method name;
   * - directly under an owned class dir (`trails/<ClassName>/trail.yaml`) — no method name to
   *   derive (supported recordings live one level down: `<ClassName>/<methodName>/trail.yaml`);
   * - nested deeper than one directory under an owned class dir
   *   (`trails/<ClassName>/<a>/<b>/trail.yaml`) — only direct subdirectories of a class dir map
   *   to methods, matching the one-directory-deep probing of the runtime resolvers.
   *
   * A bare file at exactly `<ClassName>/<methodName>/trail.yaml` is the supported
   * directory-per-test layout and generates a method instead — see [discoverTrailMethods]. The
   * walk matches the filename exactly (case-sensitive), keeping the gate consistent with the
   * case-sensitive input includes in [trailsAssetFiles] even on case-insensitive filesystems.
   *
   * Scoped to generator-owned dirs only: with a non-empty `onlyClassNames`, non-listed dirs back
   * hand-written shells and are documented as "left untouched" — the same boundary applies here.
   * The caller also exempts the top-level `config/` dir from implicit ownership (see the call
   * site) — that tree is documented non-codegen content.
   */
  private fun failOnUnreachableBareUnifiedTrailFiles(
    assetsDir: java.io.File,
    ownedClassDirs: List<java.io.File>,
  ) {
    val unreachableFiles =
      (assetsDir.listFiles()?.filter { it.isFile && it.name == BARE_UNIFIED_TRAIL_FILENAME }.orEmpty() +
        ownedClassDirs.flatMap { classDir ->
          classDir.walkTopDown().filter {
            // `<classDir>/<methodName>/trail.yaml` (grandparent == classDir) is the supported
            // directory-per-test layout; every other depth is unreachable.
            it.isFile && it.name == BARE_UNIFIED_TRAIL_FILENAME && it.parentFile?.parentFile != classDir
          }
        })
        .sortedBy { it.path }
    if (unreachableFiles.isEmpty()) return
    val listing =
      unreachableFiles.joinToString("\n") { "  - ${it.relativeTo(assetsDir.parentFile ?: assetsDir).path}" }
    throw GradleException(
      "xyz.block.trailblaze.android-gradle: found bare unified `$BARE_UNIFIED_TRAIL_FILENAME` " +
        "file(s) at locations no generated @Test could reach, under $assetsDir:\n" +
        listing + "\n" +
        "Supported layouts under trails/<ClassName>/ (each produces one @Test method):\n" +
        "  - <methodName>.trail.yaml (named trail file)\n" +
        "  - <methodName>/$BARE_UNIFIED_TRAIL_FILENAME (directory-per-test unified recording — " +
        "the directory name becomes the method name)\n" +
        "A bare `$BARE_UNIFIED_TRAIL_FILENAME` anywhere else has no derivable method name, so no " +
        "test would ever run it (silent coverage loss). Move each listed file into one of the " +
        "supported layouts."
    )
  }

  /**
   * One `@Test` method the generator will emit, discovered from either trail layout — see
   * [discoverTrailMethods].
   */
  internal data class TrailMethod(
    val methodName: String,
    /**
     * `true` when the trail is a `<methodName>/trail.yaml` recording directory rather than a
     * named `<methodName>.trail.yaml` file. Drives the inline-rule emit shape: recording
     * directories pass the DIRECTORY asset path to `runFromAsset`, which the runtime resolves to
     * the best file inside (device-classifier-specific recording → `trail.yaml` → `blaze.yaml`)
     * via `TrailRecordings.findBestTrailResourcePath` — the same contract hand-written shells
     * that pass a recording-directory path already rely on. Named trails pass the file path
     * directly.
     */
    val isRecordingDir: Boolean,
    /** On-disk source relative to the assets dir, for validation / collision error messages. */
    val source: String,
  )

  /**
   * Active emit mode resolved from the extension's `baseClassFqn` / `ruleClassFqn` properties.
   * Carried into [renderShell] so the renderer can branch on shape without re-reading the
   * extension. Kept as a sealed class (rather than two booleans + a string) so the type system
   * makes "ruleFqn is null when in BaseClass mode" unrepresentable.
   */
  internal sealed class TestHostMode {
    abstract val fqn: String

    /** Extending-base pattern: `class X : <fqn>() { @Test fun y() = runFromAsset() }`. */
    data class BaseClass(override val fqn: String) : TestHostMode()

    /**
     * Inline-rule pattern (OSS default): `class X { @get:Rule val rule = <fqn>(); @Test fun y() =
     * rule.runFromAsset("trails/X/y.trail.yaml") }`.
     */
    data class InlineRule(override val fqn: String) : TestHostMode()
  }

  companion object {
    /**
     * Public OSS-shipped Trailblaze rule, used as the inline-rule default. Picked because it's
     * already what `examples/android-sample-app-uitests`'s own generator emits, so an external
     * consumer who follows the OSS norm gets a working default out of the box.
     */
    internal const val DEFAULT_RULE_FQN = "xyz.block.trailblaze.android.AndroidTrailblazeRule"

    /**
     * Filename of the unified per-test trail format (the default new-recording output), whose
     * identity comes from its enclosing directory rather than its filename. Mirrors
     * `TrailRecordings.UNIFIED_TRAIL_FILENAME` in `trailblaze-models` (not a dependency of this
     * included build). At `<ClassName>/<methodName>/trail.yaml` it produces a `@Test` method (see
     * [discoverTrailMethods]); at any other depth it fails the build (see
     * [failOnUnreachableBareUnifiedTrailFiles]).
     */
    internal const val BARE_UNIFIED_TRAIL_FILENAME = "trail.yaml"

    /**
     * Name of the top-level assets subdirectory that holds non-codegen content — trailmap/target
     * YAML and staged tool bundles, mirroring the on-device `trails/config/` asset convention
     * (see [TrailblazeAndroidGradlePlugin]'s staging root). Exempt from the bare-`trail.yaml`
     * gate under implicit ownership; an explicit `onlyClassNames` entry with this name is still
     * honored as a class dir.
     */
    internal const val NON_CODEGEN_CONFIG_DIR_NAME = "config"

    private val SIMPLE_NAME_REGEX = Regex("^[A-Za-z_][A-Za-z0-9_]*$")

    // Full Kotlin/Java package syntax: dot-separated identifiers, each itself a SIMPLE_NAME.
    // Permissive on case (`Foo.Bar` is legal even if not conventional) — the goal is to reject
    // values that would generate broken Kotlin source, not to enforce style.
    private val PACKAGE_NAME_REGEX =
      Regex("^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*$")

    // Fully-qualified class name: at least one dot (i.e. two+ segments), each a SIMPLE_NAME.
    // Rejects trailing/leading dots and empty segments — without this gate `xyz.` would pass the
    // historical `contains('.')` check and `substringAfterLast('.')` would return "" and the
    // renderer would emit `class X : () {`, a Kotlin compile error pointing at the generated file
    // instead of the misconfigured property.
    private val FQN_REGEX =
      Regex("^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+$")

    // Kotlin hard keywords (the unbacktickable set). A directory or filename that matches the
    // identifier regex but lands on one of these would render as `class when : ...` or
    // `@Test fun fun()`, which fails Kotlin compile downstream with a confusing error pointing at
    // the generated file instead of the author's intent. Rejecting at generate time keeps the
    // error message close to the source (the path that named it `when`).
    //
    // Source: https://kotlinlang.org/docs/keyword-reference.html#hard-keywords. Soft keywords
    // (`by`, `catch`, `get`, `set`, etc.) are valid identifiers and intentionally NOT included.
    private val KOTLIN_HARD_KEYWORDS =
      setOf(
        "as",
        "break",
        "class",
        "continue",
        "do",
        "else",
        "false",
        "for",
        "fun",
        "if",
        "in",
        "interface",
        "is",
        "null",
        "object",
        "package",
        "return",
        "super",
        "this",
        "throw",
        "true",
        "try",
        "typealias",
        "typeof",
        "val",
        "var",
        "when",
        "while",
      )

    internal fun validateSimpleName(name: String, source: String) {
      require(SIMPLE_NAME_REGEX.matches(name)) {
        "Invalid Kotlin identifier `$name` (from $source). Class-directory names under " +
          "`src/androidTest/assets/trails/` must be valid Kotlin simple identifiers."
      }
      require(name !in KOTLIN_HARD_KEYWORDS) {
        "Invalid Kotlin identifier `$name` (from $source). `$name` is a Kotlin hard keyword and " +
          "cannot be used as a class name. Rename the directory."
      }
      // `_` is technically a legal Kotlin identifier (the regex accepts it) but Kotlin 1.5+
      // reserves it for anonymous / unused bindings in destructuring and lambda parameters.
      // Naming a generated test class `_` is almost certainly unintended and confusing.
      require(name != "_") {
        "Invalid Kotlin identifier `$name` (from $source). `_` is reserved in Kotlin for " +
          "anonymous bindings — rename the directory to a meaningful test-class name."
      }
    }

    internal fun validateMethodName(name: String, source: String) {
      require(SIMPLE_NAME_REGEX.matches(name)) {
        "Invalid Kotlin method identifier `$name` (from $source). Each <methodName>.trail.yaml " +
          "filename and <methodName>/ recording-directory name must produce a valid Kotlin " +
          "simple identifier."
      }
      require(name !in KOTLIN_HARD_KEYWORDS) {
        "Invalid Kotlin method identifier `$name` (from $source). `$name` is " +
          "a Kotlin hard keyword and cannot be used as a method name. Rename the file or directory."
      }
      require(name != "_") {
        "Invalid Kotlin method identifier `$name` (from $source). `_` is " +
          "reserved in Kotlin for anonymous bindings — rename the file or directory to a " +
          "meaningful test-method name."
      }
    }

    internal fun validatePackageName(pkg: String) {
      require(PACKAGE_NAME_REGEX.matches(pkg)) {
        "trailblazeAndroid.packageName `$pkg` is not a valid Kotlin package " +
          "(dot-separated identifiers, each starting with a letter or underscore). " +
          "Example: `xyz.block.trailblaze.evaluation`."
      }
    }

    internal fun validateBaseClassFqn(fqn: String) {
      require(FQN_REGEX.matches(fqn)) {
        "trailblazeAndroid.baseClassFqn must be a fully qualified class name " +
          "with at least one package segment (e.g. `xyz.block.trailblaze.rules.SquareTrailblazeTest`); " +
          "got `$fqn`."
      }
    }

    internal fun validateRuleClassFqn(fqn: String) {
      require(FQN_REGEX.matches(fqn)) {
        "trailblazeAndroid.ruleClassFqn must be a fully qualified class name " +
          "with at least one package segment (e.g. `xyz.block.trailblaze.android.AndroidTrailblazeRule`); " +
          "got `$fqn`."
      }
    }

    internal fun validateTestHostFqn(mode: TestHostMode) {
      when (mode) {
        is TestHostMode.BaseClass -> validateBaseClassFqn(mode.fqn)
        is TestHostMode.InlineRule -> validateRuleClassFqn(mode.fqn)
      }
    }

    /**
     * Emits the shell source text. Public-ish (internal) so a unit test can byte-diff the output
     * without needing to spin up a full Gradle TestKit fixture.
     */
    /**
     * Top-level renderer that dispatches on [mode]. Plain text (no KotlinPoet); both branches emit
     * canonical ktfmt formatting so spotless / ktfmt no-op the file.
     */
    internal fun renderShell(
      packageName: String,
      className: String,
      mode: TestHostMode,
      methods: List<TrailMethod>,
    ): String =
      when (mode) {
        is TestHostMode.BaseClass ->
          renderBaseClassShell(packageName, className, mode.fqn, methods)
        is TestHostMode.InlineRule ->
          renderInlineRuleShell(packageName, className, mode.fqn, methods)
      }

    /**
     * Pattern A: `class X : <BaseClass>() { @Test fun y() = runFromAsset() }`. Same no-arg emit
     * for both trail layouts — the runtime stack-trace resolver
     * (`TrailblazeYamlUtil.calculateTrailblazeYamlAssetPathFromStackTrace`) probes the named-file
     * paths first, then the `<ClassName>/<methodName>/trail.yaml` recording-directory shape.
     */
    internal fun renderBaseClassShell(
      packageName: String,
      className: String,
      baseClassFqn: String,
      methods: List<TrailMethod>,
    ): String = buildString {
      val baseSimpleName = baseClassFqn.substringAfterLast('.')
      appendHeader(this, className)
      appendLine("package $packageName")
      appendLine()
      appendLine("import org.junit.Test")
      appendLine("import $baseClassFqn")
      appendLine()
      append("class $className : $baseSimpleName() {")
      methods.forEach { method ->
        appendLine()
        appendLine()
        append("  @Test fun ${method.methodName}() = runFromAsset()")
      }
      appendLine()
      appendLine("}")
    }

    /**
     * Pattern B: `class X { @get:Rule val rule = <RuleClass>(); @Test fun y() =
     * rule.runFromAsset("trails/X/y.trail.yaml") }`. Mirrors the emit shape of OSS's existing
     * `examples/android-sample-app-uitests`'s `GenerateSampleAppTestsTask`, so a downstream
     * generator that publishes downstream stays aligned with the OSS norm.
     */
    internal fun renderInlineRuleShell(
      packageName: String,
      className: String,
      ruleClassFqn: String,
      methods: List<TrailMethod>,
    ): String = buildString {
      val ruleSimpleName = ruleClassFqn.substringAfterLast('.')
      appendHeader(this, className)
      appendLine("package $packageName")
      appendLine()
      appendLine("import org.junit.Rule")
      appendLine("import org.junit.Test")
      appendLine("import $ruleClassFqn")
      appendLine()
      appendLine("class $className {")
      appendLine()
      appendLine("  @get:Rule val rule = $ruleSimpleName()")
      methods.forEach { method ->
        appendLine()
        // Asset path matches the on-disk layout; emitted as an explicit string so the inline-rule
        // `runFromAsset(path)` overload picks it up. Named trails pass the file path; recording
        // directories pass the DIRECTORY path, which the runtime resolves to the best file inside
        // (classifier-specific recording → trail.yaml → blaze.yaml). Splits across multiple lines
        // if the single-line form would push past 95 chars to stay inside ktfmt's preferred width.
        val methodName = method.methodName
        val assetPath =
          if (method.isRecordingDir) "trails/$className/$methodName"
          else "trails/$className/$methodName.trail.yaml"
        val singleLine = "  @Test fun $methodName() = rule.runFromAsset(\"$assetPath\")"
        if (singleLine.length <= 95) {
          appendLine(singleLine)
        } else {
          appendLine("  @Test")
          appendLine("  fun $methodName() =")
          appendLine("    rule.runFromAsset(\"$assetPath\")")
        }
      }
      appendLine("}")
    }

    private fun appendHeader(sb: StringBuilder, className: String) {
      sb.appendLine(
        "// *** DO NOT EDIT — this file is generated by xyz.block.trailblaze.android-gradle. ***"
      )
      sb.appendLine(
        "// Source: src/androidTest/assets/trails/$className/ — to change the test surface, add /"
      )
      sb.appendLine(
        "// rename / remove the matching <methodName>.trail.yaml files or <methodName>/trail.yaml"
      )
      sb.appendLine("// recording directories under that directory.")
      sb.appendLine()
    }
  }
}
