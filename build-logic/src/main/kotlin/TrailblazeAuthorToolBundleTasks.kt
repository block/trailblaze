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
 *  - [scriptingSdkSrc] — file the plugin aliases `@trailblaze/scripting` to. Defaults to the
 *    SLIM in-process profile entry `sdks/typescript/src/in-process.ts`. Author bundles import
 *    `@trailblaze/scripting`; aliasing it to the slim entry lets esbuild inline only the
 *    typed-authoring core (no `@modelcontextprotocol/sdk`, no ajv, no zod), so each produced
 *    bundle stays KB-scale — the right profile for tools that run in-process / on-device in
 *    QuickJS. Tools that genuinely need the full MCP runtime (`runtime: subprocess`) are bundled
 *    elsewhere against the full SDK and never reach this task.
 *  - [autoInstall] — when `true` (default), the plugin registers a sibling install task
 *    that runs `bun install` in [sourceDir] before bundling. bun is the sole supported JS
 *    runtime (see root CLAUDE.md / PR #3503); there is no npm fallback. Set to `false`
 *    when another module already manages the same `node_modules/` (e.g.
 *    `:trailblaze-scripting-subprocess`'s install tasks); the consumer wires `dependsOn(...)`
 *    explicitly to avoid two install tasks colliding on the same on-disk sentinel.
 */
abstract class AuthorToolBundleSpec @Inject constructor(val name: String, objects: ObjectFactory) {
  abstract val sourceDir: DirectoryProperty
  abstract val entryPoint: Property<String>
  abstract val outputFile: RegularFileProperty
  abstract val esbuildBinary: RegularFileProperty
  abstract val scriptingSdkSrc: RegularFileProperty

  /**
   * The committed registration-wrapper template (`sdks/typescript/tools/in-process-wrapper-template.mjs`)
   * — the single source of truth for the synthesized wrapper JS. Defaults to that path relative to the
   * located framework root; override only if the template moves.
   */
  abstract val scriptingWrapperTemplate: RegularFileProperty
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
 * Runs `bun install` inside [AuthorToolBundleSpec.sourceDir] so esbuild and any author-side
 * transitive deps resolve at bundle time. bun is the sole supported JS runtime (see root
 * CLAUDE.md / PR #3503) — no npm fallback; missing-bun or install failure is fatal so we
 * never paper over a contract violation with a parallel toolchain. Output is the install
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

  @get:Input
  abstract val installTimeoutMinutes: Property<Long>

  @get:Internal
  abstract val rootDir: DirectoryProperty

  @get:Internal
  abstract val logFile: RegularFileProperty

  @TaskAction
  fun install() {
    val workingDir = packageJson.get().asFile.parentFile
    val sentinel = installSentinel.get().asFile
    val name = bundleName.get()
    // Clamp to a 1-minute floor so a stale CI config or fat-fingered `-P0` doesn't make
    // `proc.waitFor(0, MINUTES)` return `false` instantly and report a successful install
    // as a timeout. 15 minutes is the documented default; smaller-than-1 is never sensible.
    // Shared with `:trailblaze-scripting-subprocess`'s install tasks (same property name).
    val timeoutMinutes = maxOf(1L, installTimeoutMinutes.get())
    val logFile = logFile.get().asFile
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
      if (proc.waitFor(timeoutMinutes, TimeUnit.MINUTES)) {
        proc.exitValue()
      } else {
        logger.warn("${command.joinToString(" ")} did not finish within ${timeoutMinutes}m — killing.")
        proc.destroyForcibly()
        proc.waitFor(10, TimeUnit.SECONDS)
        -1
      }
    } catch (e: java.io.IOException) {
      // Most common cause: bun executable isn't on PATH. Surface at WARN so the developer
      // sees it without hunting the log file — the path-vs-install distinction matters for
      // triage now that there's no npm fallback to paper over a missing bun.
      logFile.appendText("[launch failed (likely bun not on PATH): ${e.message}]\n")
      logger.warn("${command.joinToString(" ")} failed to launch (likely bun not on PATH): ${e.message}")
      -1
    } catch (e: Exception) {
      logFile.appendText("[launch failed: ${e.message}]\n")
      logger.info("${command.joinToString(" ")} failed to launch: ${e.message}")
      -1
    }

    // bun is the sole supported JS runtime (see root CLAUDE.md / PR #3503). No npm fallback —
    // if `bun install` fails or bun isn't on PATH, fail loudly rather than papering over the
    // contract violation with a parallel toolchain.
    val ok = tryInstall(listOf("bun", "install")) == 0

    if (!ok) {
      val rootDir = rootDir.get().asFile.absolutePath
      throw GradleException(
        "Failed to install author tool bundle deps for `$name` via `bun install`.\n" +
          "  Install output:  ${logFile.absolutePath}\n" +
          "  Manual install:  (cd $workingDir && bun install)\n" +
          "  Trailblaze requires bun; activate the repo's Hermit env " +
          "(from repo root: $rootDir, run `source bin/activate-hermit`) " +
          "or install bun from https://bun.sh/.",
      )
    }
    sentinel.parentFile.mkdirs()
    sentinel.writeText("ok\n")
  }
}

/**
 * Runs esbuild over a synthesized wrapper that imports [entryPoint] (resolved against
 * [sourceDir]) and writes a single bundled JavaScript file to [outputFile].
 *
 * ### Why a synthesized wrapper
 *
 * Author tools are typed declarations — `export const <toolName> = trailblaze.tool<I>(handler)`
 * — that do NOT self-register on `globalThis.__trailblazeTools`. esbuild of the raw entry would
 * produce a self-contained IIFE that runs module-init but registers nothing, so the host would
 * fail dispatch with `Tool not registered`. This task therefore bundles a
 * [synthesizeInProcessScriptedToolWrapper] entry instead: it imports every typed-tool export
 * from the user file as a namespace, builds the `client` composition shim, and registers each
 * export on `globalThis.__trailblazeTools[<exportName>]`. This is the build-time, multi-export
 * analog of `DaemonScriptedToolBundler.synthesizeWrapper` (daemon-time, single-export).
 *
 * ### Flag set
 *
 * Fixed by what the on-device QuickJS runtime can evaluate (and kept in lockstep with
 * `DaemonScriptedToolBundler.runEsbuild`):
 *  - `--bundle` — produce a single self-contained file.
 *  - `--platform=neutral` + `--main-fields=module,main` — match the resolution mode used by
 *    `bundleTrailblazeSdk` and avoid Node-only resolution quirks.
 *  - `--format=iife` — author bundles register handlers via `globalThis.__trailblazeTools` and
 *    have no top-level `await`. iife avoids the `import`/`export` keywords the runtime's
 *    script-mode evaluation rejects.
 *  - `--target=es2020` — covers the ES features the SDK needs without esnext-only syntax.
 *  - `--alias:@trailblaze/scripting=<scriptingSdkSrc>` — author code does
 *    `import { trailblaze } from "@trailblaze/scripting"`. Aliasing to the SLIM in-process entry
 *    keeps the bundle KB-scale (no MCP, no ajv, no zod).
 *  - `--external:node:process` — defensive carry-over so any author who reaches for
 *    `node:process` doesn't break the on-device build at bundle time (the runtime fails such a
 *    tool at evaluation, which is the correct outcome for an in-process tool).
 *  - `--alias:@modelcontextprotocol/sdk/server/stdio.js=<throw-only stub>` — belt-and-suspenders
 *    parity with `DaemonScriptedToolBundler`: the slim SDK never imports the MCP stdio transport,
 *    but if a transitive import ever reached for it the stub strips the unresolvable `node:process`
 *    dependency rather than failing the bundle.
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
  abstract val scriptingSdkSrc: RegularFileProperty

  /**
   * The shared registration-wrapper template, read at bundle time. Declared as an `@InputFile`
   * so editing the template (the single source of truth for the wrapper JS) re-bundles every
   * author tool.
   */
  @get:InputFile
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val scriptingWrapperTemplate: RegularFileProperty

  @get:Input
  abstract val bundleName: Property<String>

  @get:Internal
  abstract val projectDir: DirectoryProperty

  @get:Internal
  abstract val logFile: RegularFileProperty

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
    val sdk = scriptingSdkSrc.get().asFile
    if (!sdk.isFile) {
      throw GradleException(
        "@trailblaze/scripting slim SDK entry not found at $sdk for author tool bundle " +
          "`${bundleName.get()}`. Set `scriptingSdkSrc` to override.",
      )
    }
    val wrapperTemplate = scriptingWrapperTemplate.get().asFile
    if (!wrapperTemplate.isFile) {
      throw GradleException(
        "Scripted-tool wrapper template not found at $wrapperTemplate for author tool bundle " +
          "`${bundleName.get()}`. Expected sdks/typescript/tools/in-process-wrapper-template.mjs; " +
          "set `scriptingWrapperTemplate` to override.",
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

    val logFile = logFile.get().asFile
    logFile.parentFile.mkdirs()
    logFile.writeText("")

    // Synthesize the registration wrapper co-located with the user's entry so esbuild's
    // relative-import resolution finds the named exports. The wrapper imports the entry as a
    // namespace, enumerates its typed-tool exports, and registers each on
    // `globalThis.__trailblazeTools[<exportName>]`. See [synthesizeInProcessScriptedToolWrapper].
    val wrapperFile = File.createTempFile(".trailblaze-wrapper-", ".ts", entry.parentFile)
    wrapperFile.writeText(synthesizeInProcessScriptedToolWrapper(entry.name, wrapperTemplate))
    // Throw-only stub aliased in for the MCP stdio transport — parity with
    // `DaemonScriptedToolBundler`. Written into the (gitignored) build output dir, not the
    // author's source tree.
    val stdioStubFile = File(output.parentFile, "_ondevice-stdio-stub.ts").apply {
      writeText(
        "/* GENERATED by BundleAuthorToolsTask — esbuild --alias target for the on-device " +
          "QuickJS path. */\n" +
          "export class StdioServerTransport { " +
          "constructor() { throw new Error(\"StdioServerTransport unavailable on-device\"); } }\n",
      )
    }

    val argv = listOf(
      esbuild.absolutePath,
      wrapperFile.absolutePath,
      "--bundle",
      "--platform=neutral",
      "--format=iife",
      "--target=es2020",
      "--main-fields=module,main",
      "--external:node:process",
      "--alias:@trailblaze/scripting=${sdk.absolutePath}",
      "--alias:@modelcontextprotocol/sdk/server/stdio.js=${stdioStubFile.absolutePath}",
      "--outfile=${output.absolutePath}",
    )

    // `relativeToOrSelf` instead of `relativeTo` — the latter throws when [output] is
    // outside [project.projectDir], which is a legitimate `outputFile` configuration (a
    // build-cache dir under the user's home, an ad-hoc test fixture path, etc.). Falling
    // back to the absolute path keeps the log readable in those cases.
    logger.lifecycle("Bundling author tool `${bundleName.get()}` → ${output.relativeToOrSelf(projectDir.get().asFile)}")

    try {
      val proc = ProcessBuilder(argv)
        .directory(entry.parentFile)
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
    } finally {
      // Always clean up the synthesized wrapper + stub — the wrapper is co-located with the
      // user's source so a leak would clutter the author's tree (and risk an accidental commit).
      wrapperFile.delete()
      stdioStubFile.delete()
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
 * Synthesizes the in-process registration wrapper for a scripted-tool source file that exports
 * one or more typed tools (`export const <toolName> = trailblaze.tool<I>(...)`). The returned TS
 * source is written next to [userScriptFileName] and handed to esbuild as the bundle entry.
 *
 * The wrapper body is rendered from the ONE committed template,
 * `sdks/typescript/tools/in-process-wrapper-template.mjs`, read from [templateFile]. This is the
 * MULTI-export build-time form: it imports the user file as a namespace, enumerates every
 * function-valued export, and registers each on `globalThis.__trailblazeTools[<exportName>]` (the
 * export name IS the tool name). The daemon-time `DaemonScriptedToolBundler.synthesizeWrapper`
 * (`:trailblaze-host`) and `:trailblaze-common`'s framework bundler render the SAME template — the
 * daemon with a single-export prelude + footer, the others with this same multi-export footer — so
 * on-device dispatch + composition behave identically regardless of which bundler produced the
 * `.bundle.js`. There is no longer a hand-maintained copy of the JS to drift; the template file is
 * the single source of truth.
 *
 * The three bundlers reach the one template by three routes (none can import the others — a Gradle
 * plugin here, a `build.gradle.kts` build script in trailblaze-common, and a runtime JVM module in
 * :trailblaze-host): the two Gradle bundlers read the committed file off disk; :trailblaze-host
 * reads it from the classpath (staged into its JAR). [renderInProcessScriptedToolWrapper] does the
 * token substitution.
 */
fun synthesizeInProcessScriptedToolWrapper(userScriptFileName: String, templateFile: File): String {
  val header = buildString {
    appendLine("// Synthetic entry generated by BundleAuthorToolsTask.")
    appendLine("// Imports every typed-tool export from `$userScriptFileName`, builds a `client`")
    appendLine("// shim over the host's `__trailblazeCall` binding, and registers each export on")
    appendLine("// `globalThis.__trailblazeTools[<exportName>]` so QuickJsToolHost.callTool can")
    appendLine("// dispatch it.")
  }
  return renderInProcessScriptedToolWrapper(
    templateText = templateFile.readText(),
    header = header,
    importSource = "./$userScriptFileName",
    // Multi-export: no single-export prelude — the empty replacement removes the token line.
    prelude = "",
    registration = MULTI_EXPORT_REGISTRATION,
  )
}

/**
 * The multi-export registration footer shared by the two build-time bundlers (this plugin and
 * `:trailblaze-common`'s framework bundler). Registers every function-valued export under its own
 * export name. Type-only exports erase at bundle time; the `typeof === 'function'` filter skips any
 * non-tool export (e.g. esbuild's namespace markers). `const` in the for-of loop gives each
 * iteration its own binding so the handler closure captures the right tool definition.
 */
val MULTI_EXPORT_REGISTRATION: String = buildString {
  appendLine("for (const __exportName of Object.keys(__userModule)) {")
  appendLine("  const __def = __userModule[__exportName];")
  appendLine("  if (typeof __def !== 'function') continue;")
  appendLine("  globalThis.__trailblazeTools[__exportName] = {")
  appendLine("    handler: async (args, ctx) => {")
  appendLine("      const result = await __def(args, ctx, __client);")
  appendLine("      return __normalizeResult(result);")
  appendLine("    },")
  appendLine("  };")
  appendLine("}")
}

/**
 * Token substitution over the shared wrapper template. Each `// __TOKEN__\n` line is replaced
 * whole so an empty replacement (e.g. an empty [prelude] for the multi-export form) removes the
 * line cleanly. Kept trivially small and duplicated across the three bundlers' classpaths — the
 * load-bearing JS lives in the template file, not here.
 */
fun renderInProcessScriptedToolWrapper(
  templateText: String,
  header: String,
  importSource: String,
  prelude: String,
  registration: String,
): String =
  templateText
    .replace("// __TRAILBLAZE_HEADER__\n", header)
    .replace("__TRAILBLAZE_IMPORT_SOURCE__", importSource)
    .replace("// __TRAILBLAZE_PRELUDE__\n", prelude)
    .replace("// __TRAILBLAZE_REGISTRATION__\n", registration)

/**
 * Walk-up + immediate-children scan for the framework root marker
 * (`sdks/typescript/package.json`). Avoids any layout-specific path literal in the
 * source code — works for both a nested layout (where the framework root sits
 * one directory below `rootProject.projectDir`) and a flat release layout (where
 * it IS `rootProject.projectDir`).
 *
 * Used to construct the `defaultEsbuildBinary` and `defaultScriptingSdkSrc` paths without
 * embedding any layout-specific prefix. The plugin's [BundleAuthorToolsTask] declares
 * those paths as `@InputFile` properties, so a missing-file misresolution surfaces as a
 * directed Gradle validation error.
 */
internal fun locateFrameworkRoot(start: File): File? {
  val marker = "sdks/typescript/package.json"
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
internal fun Project.defaultScriptingSdkSrc(): java.io.File? =
  locateFrameworkRoot(rootProject.projectDir)?.let {
    File(it, "sdks/typescript/src/in-process.ts")
  }

/** Helper used by the plugin to wire the default wrapper template (single source of truth). */
internal fun Project.defaultScriptingWrapperTemplate(): java.io.File? =
  locateFrameworkRoot(rootProject.projectDir)?.let {
    File(it, "sdks/typescript/tools/in-process-wrapper-template.mjs")
  }

/** Helper used by the plugin to wire the default esbuild binary. */
internal fun Project.defaultEsbuildBinary(): java.io.File? =
  locateFrameworkRoot(rootProject.projectDir)?.let {
    File(it, "sdks/typescript/node_modules/.bin/esbuild")
  }
