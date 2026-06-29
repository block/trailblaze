import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.IgnoreEmptyDirectories
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.TaskProvider

/**
 * Generates Android JUnit shell classes from `.trail.yaml` files staged under the consumer's
 * `androidTest` assets tree. The shell is the pure-boilerplate Kotlin class whose only job is to
 * make a trail YAML discoverable by `AndroidJUnitRunner` — for every `<methodName>.trail.yaml` under
 * `src/androidTest/assets/trails/<ClassName>/`, the generator emits a `class <ClassName> :
 * <BaseClass>() { @Test fun <methodName>() = runFromAsset() }` Kotlin source file. The runtime
 * resolver (`TrailblazeYamlUtil.calculateTrailblazeYamlAssetPathFromStackTrace`) finds the YAML by
 * stack-trace-derived simple-class + method name, so the directory name must equal the desired
 * generated class's simple name — same convention humans already follow when authoring shells by
 * hand.
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
 * The plugin stays AGP-unaware (this module deliberately does not carry AGP on its classpath);
 * the consumer wires the generated source dir into AGP's `androidTest` source set and the compile
 * task `dependsOn(...)` from its own build script:
 *
 * ```kotlin
 * plugins {
 *   alias(libs.plugins.android.library)
 *   alias(libs.plugins.kotlin.android)
 *   id("xyz.block.trailblaze.android-gradle")
 * }
 *
 * trailblazeAndroidGradle {
 *   packageName.set("xyz.block.trailblaze.evaluation")
 *   // baseClassFqn defaults to xyz.block.trailblaze.rules.SquareTrailblazeTest; override for
 *   // any other test base that exposes a `runFromAsset()` helper.
 *   // onlyClassNames is empty by default (= generate for every <ClassName>/ subdir);
 *   // restrict to specific shells during incremental rollout.
 * }
 *
 * android {
 *   sourceSets.getByName("androidTest").java.srcDir(
 *     trailblazeAndroidGradle.generatedSourceDir,
 *   )
 * }
 * tasks
 *   .matching { it.name.startsWith("compile") && it.name.contains("AndroidTest") }
 *   .configureEach { dependsOn(trailblazeAndroidGradle.generateTask) }
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
          "Generates Android JUnit shells (one @Test method per *.trail.yaml) from " +
            "src/androidTest/assets/trails/<ClassName>/ directories. Wired into the androidTest " +
            "source set by the consumer; emits to build/generated/source/trailblazeTrails/androidTest."
      }

    val extension =
      project.extensions.create(
        "trailblazeAndroidGradle",
        TrailblazeAndroidGradleExtension::class.java,
        project,
        generate,
      )

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
    // SetProperty.get() throws when never assigned, so the documented "leave it empty = generate
    // for every <ClassName>/ subdir" path was broken — pin an empty-set convention so a consumer
    // that omits onlyClassNames hits the all-subdirs branch instead of a NoSuchElementException at
    // generate time.
    extension.onlyClassNames.convention(emptySet())

    // Wire task inputs from the extension after defaults are in place. (Conventions on the
    // extension flow to the task only if we plumb them — the task is registered before the
    // extension so both can coexist on the extension's public surface.)
    generate.configure { task ->
      // Gate trailsAssetsDir through `filter { exists }` so the task's @Optional input is genuinely
      // absent when the conventional `src/androidTest/assets/trails` directory doesn't exist on
      // disk. Without this gate, Gradle's @InputDirectory validation fails BEFORE the TaskAction
      // can run its no-op log path — defeating the @Optional annotation. The conventional value
      // is still ergonomic (most consumers don't have to set trailsAssetsDir at all); a consumer
      // that DOES set trailsAssetsDir explicitly to a missing path still gets a hard error, since
      // their override flows through this same filter and Gradle catches misconfigured overrides
      // via the directed log message rather than an opaque input-validation failure.
      task.trailsAssetsDir.set(extension.trailsAssetsDir.filter { it.asFile.isDirectory })
      task.generatedSourceDir.set(extension.generatedSourceDir)
      task.packageName.set(extension.packageName)
      task.baseClassFqn.set(extension.baseClassFqn)
      task.ruleClassFqn.set(extension.ruleClassFqn)
      task.onlyClassNames.set(extension.onlyClassNames)
    }
  }
}

/**
 * DSL container for [TrailblazeAndroidGradlePlugin]. Configures the generator's inputs;
 * exposes [generatedSourceDir] and [generateTask] for the consumer's AGP wiring.
 */
abstract class TrailblazeAndroidGradleExtension
@Inject
constructor(
  @Suppress("unused") private val project: Project,
  /** Task provider for the consumer's `compileXxxAndroidTestKotlin.dependsOn(...)` wiring. */
  val generateTask: TaskProvider<GenerateAndroidTrailJUnitShellsTask>,
) {
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
   * Package for the generated classes. Required — no default, because picking one for the consumer
   * silently could place classes outside the test APK's runner-scanned packages.
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
   * downstream modules that have their own base class set this explicitly — e.g.
   * `baseClassFqn.set("xyz.block.trailblaze.rules.SquareTrailblazeTest")`. CI/command-line override
   * is also available via the Gradle property `trailblaze.shellGenerator.baseClassFqn`.
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
   * Rules that need constructor arguments (e.g. a custom rule with `target = ...`) can't use this
   * generator — write the shells by hand for now, or open an issue to extend the DSL.
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
}

/**
 * The codegen task. Walks [trailsAssetsDir], emits one Kotlin source file per direct subdirectory
 * that contains at least one `*.trail.yaml`. Plain text output (no KotlinPoet) — the generated
 * source is a fixed, simple shape, so the cost of pulling KotlinPoet onto build-logic's classpath
 * would dwarf the value.
 *
 * Marked `@CacheableTask` because output is a pure function of `(packageName, baseClassFqn,
 * onlyClassNames, every <ClassName>/<methodName>.trail.yaml filename under trailsAssetsDir)` —
 * inputs Gradle already tracks via the annotated properties. The actual YAML *contents* don't enter
 * the rendered shell (only filenames do), so PathSensitivity.RELATIVE on the assets dir lets the
 * cache match across worktrees / CI agents.
 */
@org.gradle.api.tasks.CacheableTask
abstract class GenerateAndroidTrailJUnitShellsTask : DefaultTask() {
  // @Optional so a module can apply the plugin BEFORE it has any trail YAMLs (early rollout)
  // without Gradle's input-validation tripping on a missing `src/androidTest/assets/trails`.
  // The TaskAction below detects the absent-dir case and no-ops with a lifecycle log — without
  // @Optional, Gradle would fail the task before that check could run.
  @get:InputDirectory
  @get:Optional
  @get:PathSensitive(PathSensitivity.RELATIVE)
  @get:IgnoreEmptyDirectories
  abstract val trailsAssetsDir: DirectoryProperty

  @get:Input abstract val packageName: Property<String>

  // Both modes are @Optional individually; the TaskAction enforces "exactly one of the two" at
  // runtime with a directed error. Both annotated @Input so the cache key reflects whichever is
  // active.
  @get:Input @get:Optional abstract val baseClassFqn: Property<String>

  @get:Input @get:Optional abstract val ruleClassFqn: Property<String>

  @get:Input
  @get:Optional
  abstract val onlyClassNames: org.gradle.api.provider.SetProperty<String>

  @get:OutputDirectory abstract val generatedSourceDir: DirectoryProperty

  @TaskAction
  fun generate() {
    val pkg =
      packageName.orNull?.takeIf { it.isNotBlank() }
        ?: throw GradleException(
          "xyz.block.trailblaze.android-gradle: trailblazeAndroidGradle " +
            ".packageName is not set. Set it to the package the generated classes should live in, " +
            "e.g. `packageName.set(\"xyz.block.trailblaze.evaluation\")`."
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
              "trailblazeAndroidGradle: set exactly ONE of `baseClassFqn` (extending-base " +
                "pattern) or `ruleClassFqn` (inline-rule pattern), not both. Got " +
                "`baseClassFqn=$explicitBase`, `ruleClassFqn=$explicitRule`."
            )
        }
        explicitBase != null -> TestHostMode.BaseClass(explicitBase)
        explicitRule != null -> TestHostMode.InlineRule(explicitRule)
        else ->
          throw GradleException(
            "trailblazeAndroidGradle: set either `baseClassFqn` (extending-base) or " +
              "`ruleClassFqn` (inline-rule). The latter defaults to " +
              "`$DEFAULT_RULE_FQN`, so this should not be reachable unless the default was " +
              "cleared deliberately."
          )
      }
    validateTestHostFqn(mode)

    val outRoot = generatedSourceDir.get().asFile
    // Clean previous output so removed/renamed trails don't leave stale shells behind. Output is
    // entirely build-tree-owned and gitignored, so a recursive delete is safe.
    outRoot.deleteRecursively()
    val pkgDir = outRoot.resolve(pkg.replace('.', '/'))
    pkgDir.mkdirs()

    // trailsAssetsDir is @Optional — `.orNull` rather than `.get()` so an absent or non-existent
    // dir falls through to the no-op log instead of throwing. Covers the "plugin applied before
    // the consumer authored any trails" case the @Optional annotation was added for.
    val assetsDir = trailsAssetsDir.orNull?.asFile
    if (assetsDir == null || !assetsDir.isDirectory) {
      logger.lifecycle(
        "xyz.block.trailblaze.android-gradle: no assets directory at " +
          "${assetsDir ?: "<unset>"} — no shells generated."
      )
      return
    }

    val classDirs =
      (assetsDir.listFiles() ?: emptyArray())
        .asSequence()
        .filter { it.isDirectory }
        .sortedBy { it.name }
        .toList()
    if (classDirs.isEmpty()) {
      logger.lifecycle(
        "xyz.block.trailblaze.android-gradle: $assetsDir has no subdirectories — " +
          "no shells generated."
      )
      return
    }

    val allowList = onlyClassNames.get()
    val candidates = if (allowList.isEmpty()) classDirs else classDirs.filter { it.name in allowList }

    if (allowList.isNotEmpty()) {
      val unknown = allowList - candidates.map { it.name }.toSet()
      if (unknown.isNotEmpty()) {
        // Hard error rather than warning — a typo in `onlyClassNames` would silently produce no
        // output and the developer would see "method not found" at test runtime, not at build time.
        throw GradleException(
          "trailblazeAndroidGradle.onlyClassNames references directories that do not " +
            "exist under $assetsDir: $unknown. Available directories: ${classDirs.map { it.name }}."
        )
      }
    }

    var generatedCount = 0
    val generatedClassNames = mutableListOf<String>()
    candidates.forEach { classDir ->
      val className = classDir.name
      validateSimpleName(className, source = "directory name under $assetsDir")
      val methodNames =
        (classDir.listFiles() ?: emptyArray())
          .filter { it.isFile && it.name.endsWith(".trail.yaml") }
          .map { it.name.removeSuffix(".trail.yaml") }
          .sorted()
      if (methodNames.isEmpty()) {
        logger.lifecycle(
          "xyz.block.trailblaze.android-gradle: $className has no .trail.yaml files " +
            "— skipping."
        )
        return@forEach
      }
      methodNames.forEach { validateMethodName(it, className = className) }
      val duplicates = methodNames.groupingBy { it }.eachCount().filter { it.value > 1 }.keys
      require(duplicates.isEmpty()) {
        "Duplicate trail names under $classDir: $duplicates. Every <methodName>.trail.yaml must " +
          "be unique within its class directory."
      }
      // Case-insensitive collision check — APFS/HFS+ (macOS default) and NTFS surface
      // `foo.trail.yaml` and `FOO.trail.yaml` as two distinct files, but `pkgDir.resolve(name)`
      // collides on those filesystems so one method would silently disappear from the emitted
      // class. Fail loudly with both original names so the developer can pick which to keep.
      val caseInsensitiveDups =
        methodNames.groupBy { it.lowercase() }.values.filter { it.size > 1 }
      require(caseInsensitiveDups.isEmpty()) {
        "Case-insensitive trail-name collision under $classDir: $caseInsensitiveDups. " +
          "On case-insensitive filesystems (APFS / HFS+ / NTFS) these would write to the same " +
          "generated file — rename one of each colliding pair."
      }

      val source = renderShell(pkg, className, mode, methodNames)
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

    internal fun validateMethodName(name: String, className: String) {
      require(SIMPLE_NAME_REGEX.matches(name)) {
        "Invalid Kotlin method identifier `$name` (from $className/$name.trail.yaml). Each " +
          "<methodName>.trail.yaml filename must produce a valid Kotlin simple identifier."
      }
      require(name !in KOTLIN_HARD_KEYWORDS) {
        "Invalid Kotlin method identifier `$name` (from $className/$name.trail.yaml). `$name` is " +
          "a Kotlin hard keyword and cannot be used as a method name. Rename the file."
      }
      require(name != "_") {
        "Invalid Kotlin method identifier `$name` (from $className/$name.trail.yaml). `_` is " +
          "reserved in Kotlin for anonymous bindings — rename the file to a meaningful test-method name."
      }
    }

    internal fun validatePackageName(pkg: String) {
      require(PACKAGE_NAME_REGEX.matches(pkg)) {
        "trailblazeAndroidGradle.packageName `$pkg` is not a valid Kotlin package " +
          "(dot-separated identifiers, each starting with a letter or underscore). " +
          "Example: `xyz.block.trailblaze.evaluation`."
      }
    }

    internal fun validateBaseClassFqn(fqn: String) {
      require(FQN_REGEX.matches(fqn)) {
        "trailblazeAndroidGradle.baseClassFqn must be a fully qualified class name " +
          "with at least one package segment (e.g. `xyz.block.trailblaze.rules.SquareTrailblazeTest`); " +
          "got `$fqn`."
      }
    }

    internal fun validateRuleClassFqn(fqn: String) {
      require(FQN_REGEX.matches(fqn)) {
        "trailblazeAndroidGradle.ruleClassFqn must be a fully qualified class name " +
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
      methodNames: List<String>,
    ): String =
      when (mode) {
        is TestHostMode.BaseClass ->
          renderBaseClassShell(packageName, className, mode.fqn, methodNames)
        is TestHostMode.InlineRule ->
          renderInlineRuleShell(packageName, className, mode.fqn, methodNames)
      }

    /** Pattern A: `class X : <BaseClass>() { @Test fun y() = runFromAsset() }`. */
    internal fun renderBaseClassShell(
      packageName: String,
      className: String,
      baseClassFqn: String,
      methodNames: List<String>,
    ): String = buildString {
      val baseSimpleName = baseClassFqn.substringAfterLast('.')
      appendHeader(this, className)
      appendLine("package $packageName")
      appendLine()
      appendLine("import org.junit.Test")
      appendLine("import $baseClassFqn")
      appendLine()
      append("class $className : $baseSimpleName() {")
      methodNames.forEach { methodName ->
        appendLine()
        appendLine()
        append("  @Test fun $methodName() = runFromAsset()")
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
      methodNames: List<String>,
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
      methodNames.forEach { methodName ->
        appendLine()
        // Asset path matches the on-disk layout (`trails/<ClassName>/<methodName>.trail.yaml`);
        // emitted as an explicit string so the inline-rule `runFromAsset(path)` overload picks it
        // up. Splits across multiple lines if the single-line form would push past 95 chars to
        // stay inside ktfmt's preferred line width.
        val singleLine =
          "  @Test fun $methodName() = rule.runFromAsset(\"trails/$className/$methodName.trail.yaml\")"
        if (singleLine.length <= 95) {
          appendLine(singleLine)
        } else {
          appendLine("  @Test")
          appendLine("  fun $methodName() =")
          appendLine("    rule.runFromAsset(\"trails/$className/$methodName.trail.yaml\")")
        }
      }
      appendLine("}")
    }

    private fun appendHeader(sb: StringBuilder, className: String) {
      sb.appendLine(
        "// *** DO NOT EDIT — this file is generated by xyz.block.trailblaze.android-gradle. ***"
      )
      sb.appendLine(
        "// Source: src/androidTest/assets/trails/$className/*.trail.yaml — to change the test surface,"
      )
      sb.appendLine(
        "// add / rename / remove the matching <methodName>.trail.yaml files under that directory."
      )
      sb.appendLine()
    }
  }
}
