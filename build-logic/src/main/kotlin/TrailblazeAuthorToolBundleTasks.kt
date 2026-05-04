import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Authoring spec for one bundled author tool script. Registered via the
 * `trailblazeAuthorToolBundles { register("name") { … } }` DSL — each registration produces
 * one [BundleAuthorToolsTask] that turns a TypeScript entry point into a single
 * QuickJS-evaluatable JavaScript file consumable by `:trailblaze-quickjs-tools`.
 *
 * Property semantics:
 *  - [sourceDir] — directory containing the entry `.ts` (and a populated `node_modules/`
 *    if [autoInstall] is `false`). Required.
 *  - [entryPoint] — file under [sourceDir] passed to esbuild as the bundle entry. Defaults
 *    to `tools.ts` to match repository convention.
 *  - [outputFile] — where the produced bundle lands. Defaults to
 *    `<projectBuildDir>/intermediates/trailblaze/author-tool-bundles/<name>.bundle.js`.
 *  - [esbuildBinary] — explicit path to an esbuild executable. Defaults to the SDK's
 *    bundled esbuild populated by `installTrailblazeScriptingSdk`. Falling back to that
 *    single shared esbuild rather than requiring every consumer's `package.json` to add
 *    esbuild matches the convention `bundleTrailblazeSdk` already uses (see
 *    `:trailblaze-scripting-bundle/build.gradle.kts`).
 *  - [toolsSdkSrc] — file the plugin aliases `@trailblaze/tools` to. Defaults to
 *    `sdks/typescript-tools/src/index.ts`. Author bundles import `@trailblaze/tools`;
 *    esbuild inlines the SDK source directly into each bundle. The SDK is small enough
 *    (~50 lines, no MCP, no zod, no `@modelcontextprotocol/sdk`) that inlining is cheap —
 *    produced bundles are ~2 KB per author. No global pre-load required at runtime; the
 *    bundle is fully self-contained.
 *  - [autoInstall] — when `true` (default), the plugin registers a sibling install task
 *    that runs `bun install` (or `npm install`) in [sourceDir] before bundling. Set to
 *    `false` when another module already manages the same `node_modules/` (e.g.
 *    `:trailblaze-scripting-subprocess`'s install tasks); the consumer wires `dependsOn(...)`
 *    explicitly to avoid two install tasks colliding on the same on-disk sentinel.
 */
abstract class AuthorToolBundleSpec @Inject constructor(val name: String, objects: ObjectFactory) {
  abstract val sourceDir: DirectoryProperty
  abstract val entryPoint: Property<String>
  abstract val outputFile: RegularFileProperty
  abstract val esbuildBinary: RegularFileProperty
  abstract val toolsSdkSrc: RegularFileProperty
  abstract val autoInstall: Property<Boolean>
}

/** DSL surface registered as the project extension. */
abstract class TrailblazeAuthorToolBundlesExtension @Inject constructor(
  /** Container Gradle gives us via `objects.domainObjectContainer`. */
  val bundles: NamedDomainObjectContainer<AuthorToolBundleSpec>,
) {
  /**
   * Register a bundle. Idiomatic Gradle DSL — `trailblazeAuthorToolBundles { register("foo") { … } }`
   * is shorthand for `trailblazeAuthorToolBundles.bundles.register("foo") { … }`.
   */
  fun register(name: String, configure: AuthorToolBundleSpec.() -> Unit) {
    bundles.register(name, configure)
  }
}

/**
 * Runs `bun install` (with `npm install` fallback) inside [AuthorToolBundleSpec.sourceDir]
 * so esbuild and any author-side transitive deps resolve at bundle time. Output is the install
 * sentinel under `node_modules/.install-ok` — keying the up-to-date check off the sentinel
 * (rather than the dir itself) means a Ctrl-C'd install leaves Gradle still seeing the task as
 * out-of-date next run, instead of silently passing on a half-populated `node_modules/`.
 */
abstract class InstallAuthorToolDepsTask : DefaultTask() {

  @get:InputFile
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val packageJson: RegularFileProperty

  @get:OutputFile
  abstract val installSentinel: RegularFileProperty

  @get:Input
  abstract val bundleName: Property<String>

  @TaskAction
  fun install() {
    val workingDir = packageJson.get().asFile.parentFile
    val sentinel = installSentinel.get().asFile
    val name = bundleName.get()
    // Clamp to a 1-minute floor so a stale CI config or fat-fingered `-P0` doesn't make
    // `proc.waitFor(0, MINUTES)` return `false` instantly and report a successful install
    // as a timeout. 15 minutes is the documented default; smaller-than-1 is never sensible.
    // Shared with `:trailblaze-scripting-subprocess`'s install tasks (same property name).
    val installTimeoutMinutes = maxOf(
      1L,
      (project.findProperty("trailblazeInstallTimeoutMinutes") as? String)?.toLongOrNull() ?: 15L,
    )
    val logFile = project.layout.buildDirectory
      .file("tmp/install-author-tool-$name.log").get().asFile
    logFile.parentFile.mkdirs()
    logFile.writeText("")
    if (sentinel.exists()) sentinel.delete()

    logger.lifecycle(
      "Installing author tool bundle deps for `$name` (one-time; Gradle's up-to-date check " +
        "caches this on subsequent runs). Log: ${logFile.absolutePath}",
    )

    fun tryInstall(command: List<String>): Int = try {
      logFile.appendText("\n\n==== ${command.joinToString(" ")} (cwd=$workingDir) ====\n")
      val proc = ProcessBuilder(command)
        .directory(workingDir)
        .redirectErrorStream(true)
        .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
        .start()
      if (proc.waitFor(installTimeoutMinutes, TimeUnit.MINUTES)) {
        proc.exitValue()
      } else {
        logger.warn("${command.joinToString(" ")} did not finish within ${installTimeoutMinutes}m — killing.")
        proc.destroyForcibly()
        proc.waitFor(10, TimeUnit.SECONDS)
        -1
      }
    } catch (e: Exception) {
      logFile.appendText("[launch failed: ${e.message}]\n")
      logger.info("${command.joinToString(" ")} failed to launch: ${e.message}")
      -1
    }

    val ok = if (tryInstall(listOf("bun", "install")) == 0) {
      true
    } else {
      logger.info("bun install failed or unavailable for `$name`; trying npm install")
      tryInstall(listOf("npm", "install", "--prefer-offline", "--no-audit", "--no-fund")) == 0
    }

    if (!ok) {
      throw GradleException(
        "Failed to install author tool bundle deps for `$name` via bun or npm.\n" +
          "  Install output:  ${logFile.absolutePath}\n" +
          "  Manual install:  cd $workingDir && bun install  (or `npm install`)",
      )
    }
    sentinel.parentFile.mkdirs()
    sentinel.writeText("ok\n")
  }
}

/**
 * Runs esbuild over [entryPoint] (resolved against [sourceDir]) and writes a single bundled
 * JavaScript file to [outputFile].
 *
 * ### Flag set
 *
 * The flag set is fixed by what the on-device QuickJS runtime can evaluate:
 *  - `--bundle` — produce a single self-contained file.
 *  - `--platform=neutral` + `--main-fields=module,main` — match the resolution mode used by
 *    `bundleTrailblazeSdk` and avoid Node-only resolution quirks.
 *  - `--format=iife` — author bundles for the `:trailblaze-quickjs-tools` runtime register
 *    handlers via `globalThis.__trailblazeTools` and have no top-level `await`. iife
 *    avoids the `import`/`export` keywords that the runtime's script-mode evaluation
 *    rejects.
 *  - `--target=es2020` — covers the ES features the new tiny SDK needs without requiring
 *    esnext-only syntax. QuickJS-NG handles the resulting output cleanly.
 *  - `--alias:@trailblaze/tools=<toolsSdkSrc>` — author code does
 *    `import { trailblaze } from "@trailblaze/tools"`. Aliasing to the SDK source lets
 *    esbuild bundle the SDK directly, producing a fully self-contained `.bundle.js` (no
 *    runtime global pre-load required, no second-stage SDK bundle on the device).
 *  - `--external:node:process` — defensive carry-over from `bundleTrailblazeSdk` so any
 *    author who reaches for `node:process` doesn't break the on-device build at bundle
 *    time. The runtime fails such a tool at evaluation, which is the correct outcome.
 *
 * Authors don't override these — the values are baked in. If a consumer needs different
 * flags, they're outside the on-device contract this plugin exists to maintain, and they
 * should drive esbuild themselves.
 */
abstract class BundleAuthorToolsTask : DefaultTask() {

  /**
   * The directory `ProcessBuilder` runs esbuild from (cwd). Marked `@Internal` because Gradle
   * snapshotting the entire dir would walk through `node_modules/` (populated by the install
   * task) on every up-to-date check — slow and produces spurious cache misses when the
   * install task touches anything inside. The actual change-detection inputs are declared
   * separately as [inputSources].
   */
  @get:Internal
  abstract val sourceDir: DirectoryProperty

  /**
   * The author sources Gradle should snapshot for the up-to-date check: `package.json`, the
   * entry `.ts`/`.js` files under [sourceDir], lockfiles. Excludes `node_modules/` (volatile
   * + huge) and the install sentinel. Populated by [TrailblazeAuthorToolBundlePlugin] from
   * [sourceDir].
   *
   * Lockfiles (`bun.lockb`, `bun.lock`, `package-lock.json`, `yarn.lock`) are included so
   * that switching commits which update the lockfile but leave `package.json` unchanged
   * still triggers a re-bundle — without this, the install task would skip (its input is
   * `package.json` only) and bundling would run against `node_modules/` that no longer
   * matches the checked-out lockfile.
   */
  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val inputSources: ConfigurableFileCollection

  @get:Input
  abstract val entryPoint: Property<String>

  @get:OutputFile
  abstract val outputFile: RegularFileProperty

  @get:InputFile
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val esbuildBinary: RegularFileProperty

  @get:InputFile
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val toolsSdkSrc: RegularFileProperty

  @get:Input
  abstract val bundleName: Property<String>

  @TaskAction
  fun bundle() {
    val source = sourceDir.get().asFile
    val entry = File(source, entryPoint.get())
    if (!entry.isFile) {
      throw GradleException(
        "Author tool bundle `${bundleName.get()}` entry not found at $entry " +
          "(sourceDir=${source.absolutePath}, entryPoint=${entryPoint.get()})",
      )
    }
    val esbuild = esbuildBinary.get().asFile
    if (!esbuild.isFile) {
      throw GradleException(
        "esbuild binary not found at $esbuild for author tool bundle `${bundleName.get()}`. " +
          "Run `bun install` in the SDK package to populate, or set `esbuildBinary` on this " +
          "registration to a different executable.",
      )
    }
    val sdk = toolsSdkSrc.get().asFile
    if (!sdk.isFile) {
      throw GradleException(
        "@trailblaze/tools SDK source not found at $sdk for author tool bundle " +
          "`${bundleName.get()}`. Set `toolsSdkSrc` to override.",
      )
    }
    val output = outputFile.get().asFile
    // Surface mkdirs failures up front rather than letting esbuild fail later with a
    // generic "missing or empty output" — read-only filesystems / a file at the parent
    // path / permission-denied errors are all more diagnosable here.
    if (!output.parentFile.mkdirs() && !output.parentFile.isDirectory) {
      throw GradleException(
        "Could not create output directory ${output.parentFile.absolutePath} for author " +
          "tool bundle `${bundleName.get()}`.",
      )
    }

    val argv = listOf(
      esbuild.absolutePath,
      entry.absolutePath,
      "--bundle",
      "--platform=neutral",
      "--format=iife",
      "--target=es2020",
      "--main-fields=module,main",
      "--alias:@trailblaze/tools=${sdk.absolutePath}",
      "--external:node:process",
      "--outfile=${output.absolutePath}",
    )

    val logFile = project.layout.buildDirectory
      .file("tmp/bundle-author-tool-${bundleName.get()}.log").get().asFile
    logFile.parentFile.mkdirs()
    logFile.writeText("")

    // `relativeToOrSelf` instead of `relativeTo` — the latter throws when [output] is
    // outside [project.projectDir], which is a legitimate `outputFile` configuration (a
    // build-cache dir under the user's home, an ad-hoc test fixture path, etc.). Falling
    // back to the absolute path keeps the log readable in those cases.
    logger.lifecycle("Bundling author tool `${bundleName.get()}` → ${output.relativeToOrSelf(project.projectDir)}")

    val proc = ProcessBuilder(argv)
      .directory(source)
      .redirectErrorStream(true)
      .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
      .start()
    // 2 minutes matches `bundleTrailblazeSdk` — esbuild itself is fast on a typical author
    // tool bundle (single-digit ms) but bun/npm cold-installs of transitive deps can stretch.
    if (!proc.waitFor(2, TimeUnit.MINUTES)) {
      proc.destroyForcibly()
      // Give the OS a beat to actually reap the process so the redirected log file's handle
      // closes before the GradleException propagates. Mirrors the install task's pattern.
      proc.waitFor(10, TimeUnit.SECONDS)
      throw GradleException(
        "esbuild did not finish within 2 minutes for `${bundleName.get()}`. See ${logFile.absolutePath}.",
      )
    }
    if (proc.exitValue() != 0) {
      throw GradleException(
        "esbuild failed (exit ${proc.exitValue()}) for `${bundleName.get()}`. See ${logFile.absolutePath}.",
      )
    }
    if (!output.isFile || output.length() == 0L) {
      throw GradleException(
        "esbuild reported success but ${output.absolutePath} is missing or empty " +
          "(bundle `${bundleName.get()}`). See ${logFile.absolutePath}.",
      )
    }
  }
}

/**
 * Walk-up + immediate-children scan for the framework root marker
 * (`sdks/typescript-tools/package.json`). Avoids any layout-specific path literal in the
 * source code — works for both the internal-monorepo layout (where the framework root sits
 * one directory below `rootProject.projectDir`) and the open-source release layout (where
 * it IS `rootProject.projectDir`).
 *
 * Used to construct the `defaultEsbuildBinary` and `defaultToolsSdkSrc` paths without
 * embedding any layout-specific prefix. The plugin's [BundleAuthorToolsTask] declares
 * those paths as `@InputFile` properties, so a missing-file misresolution surfaces as a
 * directed Gradle validation error.
 */
internal fun locateFrameworkRoot(start: File): File? {
  val marker = "sdks/typescript-tools/package.json"
  var current: File? = start
  while (current != null) {
    if (File(current, marker).isFile) return current
    // `listFiles` returns null on I/O errors and throws SecurityException when the JVM
    // lacks read permission for the directory. Restricted CI environments + networked
    // mounts both hit the latter; treating it as "no children to scan" lets the walk-up
    // continue into the parent rather than aborting plugin configuration.
    val children = try {
      current.listFiles()
    } catch (_: SecurityException) {
      null
    }
    if (children != null) {
      for (child in children) {
        if (child.isDirectory && File(child, marker).isFile) return child
      }
    }
    current = current.parentFile
  }
  return null
}

/** Helper used by the plugin to wire the container's defaults. */
internal fun Project.defaultToolsSdkSrc(): java.io.File? =
  locateFrameworkRoot(rootProject.projectDir)?.let {
    File(it, "sdks/typescript-tools/src/index.ts")
  }

/** Helper used by the plugin to wire the default esbuild binary. */
internal fun Project.defaultEsbuildBinary(): java.io.File? =
  locateFrameworkRoot(rootProject.projectDir)?.let {
    File(it, "sdks/typescript/node_modules/.bin/esbuild")
  }
