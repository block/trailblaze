import java.io.File
import java.util.concurrent.TimeUnit
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty

/**
 * Configuration for the `trailblaze.sdk-dts-bundle` plugin. Sibling of the esbuild-driven
 * [TrailblazeSdkBundlePlugin] — same regenerate-and-commit / byte-diff verify cadence, but
 * the output is a self-contained `.d.ts` declaration bundle rather than a runtime `.js`
 * bundle. Production wires `:trailblaze-models` to the real TS SDK dir + committed bundle
 * path; the functional test wires a fixture project to a fresh dts-bundle-generator output.
 */
interface TrailblazeSdkDtsBundleExtension {
  /** Root of the TS SDK package — contains `package.json`, `src/`, `node_modules/.bin/dts-bundle-generator`. */
  val trailblazeSdkDir: DirectoryProperty

  /** Path to the committed `dist/index.d.ts` that consumers ship as a JAR resource. */
  val sdkDtsBundleOutputFile: RegularFileProperty

  /**
   * Path to the committed `dist/testing.d.ts` — the secondary declaration bundle for the
   * `@trailblaze/scripting/testing` subpath module (mock client + mock context helpers
   * authors import into `*.test.ts` files). Generated from `src/testing.ts` with the
   * same external-inline flags as the primary bundle so consumers can `import { ... }
   * from "@trailblaze/scripting/testing"` without a `node_modules/zod` step.
   *
   * Optional for backward compatibility — when unset, only the primary bundle is
   * regenerated / verified. Production wiring in `:trailblaze-models` sets both.
   */
  val sdkDtsTestingBundleOutputFile: RegularFileProperty

  /**
   * Path to the committed `dist/testing.js` — the runtime ESM module that `bun test`
   * loads when an author imports from `@trailblaze/scripting/testing` inside a
   * `*.test.ts` file. Transpiled (not bundled) from `src/testing.ts` via esbuild;
   * `src/testing.ts` has only type-only imports from the rest of the SDK so the
   * transpiled output is self-contained and bun resolves it without a node_modules
   * step.
   *
   * Wired alongside [sdkDtsTestingBundleOutputFile]; both must be set together — the
   * plugin uses the absence of either to keep the older single-bundle behavior, but
   * production wiring sets both.
   */
  val sdkTestingRuntimeOutputFile: RegularFileProperty

  /**
   * Path to the committed `dist/index.js` — the runtime ESM module bun resolves when
   * a scripted tool authored against the typed surface does
   * `import { trailblaze } from "@trailblaze/scripting"`. Without this artifact the
   * `paths` mapping in the per-trailmap tsconfig resolves only to `dist/index.d.ts`
   * (types), bun loads that declaration file as a runtime module, and the value
   * import fails with `SyntaxError: Export named 'trailblaze' not found in module
   * '<...>/dist/index.d.ts'` — the failure mode PR #3338's
   * `contacts_ios_searchContacts` documented.
   *
   * Unlike [sdkTestingRuntimeOutputFile] (a plain transpile of a type-only-importing
   * source), this is a full esbuild ESM bundle: `src/index.ts` re-exports from
   * sibling modules (`./run`, `./tool`, `./client`, `./context`, `./built-in-tools`)
   * which themselves pull in `zod` and `@modelcontextprotocol/sdk` runtime, so a
   * transpile-only output would dangle on those imports at load time. The bundle
   * inlines every transitive runtime dep so a workspace receives one self-contained
   * file and the workspace SDK directory needs no `node_modules/`.
   *
   * Optional for backward compatibility — when unset, only the `.d.ts` bundles are
   * regenerated / verified. Production wiring in `:trailblaze-models` sets it
   * alongside [sdkDtsBundleOutputFile] so the type surface and runtime surface ship
   * together.
   */
  val sdkRuntimeBundleOutputFile: RegularFileProperty
}

/**
 * Registers `bundleTrailblazeSdkDts` against the [TrailblazeSdkDtsBundleExtension] paths. Same
 * shape as [TrailblazeSdkBundlePlugin] — both generate a `@trailblaze/scripting` bundle that
 * ships in JAR resources from `sdks/typescript/src/`. The bundle is a build artifact
 * (gitignored), regenerated each build; the consuming packaging task depends on this generator.
 *
 * **Why not generalize the esbuild plugin to handle both?** The two bundlers take radically
 * different argv shapes (esbuild is bundle-mode + format flags; dts-bundle-generator is
 * declaration-rollup with `--external-inlines` for type-only inlining of zod). A unified
 * task would force one of: a god-mode flag-explosion extension, or runtime-typed
 * polymorphism inside the task body. Both are worse than the duplication this file carries
 * — the bodies are short and each plugin's surface is single-purpose.
 */
class TrailblazeSdkDtsBundlePlugin : Plugin<Project> {
  override fun apply(project: Project) {
    val ext = project.extensions.create(
      "trailblazeSdkDtsBundle",
      TrailblazeSdkDtsBundleExtension::class.java,
    )

    project.tasks.register("bundleTrailblazeSdkDts") { task ->
      task.group = "build"
      task.description =
        "Regenerates the @trailblaze/scripting declaration + runtime bundle under dist/ (a " +
          "gitignored build artifact) from sdks/typescript/src/. Wired ahead of resource " +
          "packaging, so it runs as part of any build that ships dist/; can also be invoked directly."

      declareSdkDtsBundleInputs(task, ext, project)
      task.outputs.file(ext.sdkDtsBundleOutputFile).withPropertyName("sdkDtsBundle")
      task.outputs.files(
        project.provider {
          if (ext.sdkDtsTestingBundleOutputFile.isPresent) {
            project.files(ext.sdkDtsTestingBundleOutputFile)
          } else {
            project.files()
          }
        },
      ).withPropertyName("sdkDtsTestingBundle")
      task.outputs.files(
        project.provider {
          if (ext.sdkTestingRuntimeOutputFile.isPresent) {
            project.files(ext.sdkTestingRuntimeOutputFile)
          } else {
            project.files()
          }
        },
      ).withPropertyName("sdkTestingRuntime")
      task.outputs.files(
        project.provider {
          if (ext.sdkRuntimeBundleOutputFile.isPresent) {
            project.files(ext.sdkRuntimeBundleOutputFile)
          } else {
            project.files()
          }
        },
      ).withPropertyName("sdkRuntimeBundle")

      task.doFirst { requireExtensionConfigured(ext) }
      task.doLast {
        // The bundles are build artifacts, not committed source, so every build that needs
        // them installs the SDK's devDependencies (dts-bundle-generator, esbuild) from the
        // committed bun.lock first. Idempotent — skips when node_modules is already present.
        ensureSdkNodeModules(
          sdkDir = ext.trailblazeSdkDir.get().asFile,
          logFile = project.layout.buildDirectory
            .file("tmp/bundle-trailblaze-sdk-install.log").get().asFile,
        )
        val outputFile = ext.sdkDtsBundleOutputFile.get().asFile
        task.logger.lifecycle(
          "Regenerating SDK .d.ts bundle → ${outputFile.relativeTo(project.projectDir)}",
        )
        runDtsBundleGenerator(
          sdkDir = ext.trailblazeSdkDir.get().asFile,
          entryFile = File(ext.trailblazeSdkDir.get().asFile, "src/index.ts"),
          outputFile = outputFile,
          logFile = project.layout.buildDirectory
            .file("tmp/bundle-trailblaze-sdk-dts.log").get().asFile,
          appendRuntimeGlobals = true,
        )
        task.logger.lifecycle(
          "Regenerated ${outputFile.relativeTo(project.projectDir)} " +
            "(${outputFile.length() / 1024} KiB) [build artifact — not committed].",
        )

        if (ext.sdkDtsTestingBundleOutputFile.isPresent) {
          val testingOutputFile = ext.sdkDtsTestingBundleOutputFile.get().asFile
          task.logger.lifecycle(
            "Regenerating SDK testing .d.ts bundle → ${testingOutputFile.relativeTo(project.projectDir)}",
          )
          runDtsBundleGenerator(
            sdkDir = ext.trailblazeSdkDir.get().asFile,
            entryFile = File(ext.trailblazeSdkDir.get().asFile, "src/testing.ts"),
            outputFile = testingOutputFile,
            logFile = project.layout.buildDirectory
              .file("tmp/bundle-trailblaze-sdk-dts-testing.log").get().asFile,
            // The testing bundle is pure type helpers; no `URL` / `fetch` / `setTimeout`
            // references — it would never USE the runtime-globals declarations, and
            // appending them would needlessly re-declare ambient symbols that are
            // already in the primary bundle (a consumer who pulls in both would see
            // duplicate-identifier errors).
            appendRuntimeGlobals = false,
          )
          task.logger.lifecycle(
            "Regenerated ${testingOutputFile.relativeTo(project.projectDir)} " +
              "(${testingOutputFile.length() / 1024} KiB).",
          )
        }

        if (ext.sdkTestingRuntimeOutputFile.isPresent) {
          val testingRuntimeFile = ext.sdkTestingRuntimeOutputFile.get().asFile
          task.logger.lifecycle(
            "Regenerating SDK testing runtime → ${testingRuntimeFile.relativeTo(project.projectDir)}",
          )
          runEsbuildTranspile(
            sdkDir = ext.trailblazeSdkDir.get().asFile,
            entryFile = File(ext.trailblazeSdkDir.get().asFile, "src/testing.ts"),
            outputFile = testingRuntimeFile,
            logFile = project.layout.buildDirectory
              .file("tmp/bundle-trailblaze-sdk-testing-runtime.log").get().asFile,
          )
          task.logger.lifecycle(
            "Regenerated ${testingRuntimeFile.relativeTo(project.projectDir)} " +
              "(${testingRuntimeFile.length() / 1024} KiB).",
          )
        }

        if (ext.sdkRuntimeBundleOutputFile.isPresent) {
          val runtimeBundleFile = ext.sdkRuntimeBundleOutputFile.get().asFile
          task.logger.lifecycle(
            "Regenerating SDK runtime ESM bundle → ${runtimeBundleFile.relativeTo(project.projectDir)}",
          )
          runEsbuildEsmBundle(
            sdkDir = ext.trailblazeSdkDir.get().asFile,
            entryFile = File(ext.trailblazeSdkDir.get().asFile, "src/index.ts"),
            outputFile = runtimeBundleFile,
            logFile = project.layout.buildDirectory
              .file("tmp/bundle-trailblaze-sdk-runtime.log").get().asFile,
          )
          task.logger.lifecycle(
            "Regenerated ${runtimeBundleFile.relativeTo(project.projectDir)} " +
              "(${runtimeBundleFile.length() / 1024} KiB).",
          )
        }
      }
    }

  }
}

private fun requireExtensionConfigured(ext: TrailblazeSdkDtsBundleExtension) {
  if (!ext.trailblazeSdkDir.isPresent || !ext.sdkDtsBundleOutputFile.isPresent) {
    throw GradleException(
      "trailblaze.sdk-dts-bundle: extension is not configured. Add to your build.gradle.kts:\n" +
        "  trailblazeSdkDtsBundle {\n" +
        "    trailblazeSdkDir.set(layout.projectDirectory.dir(\"...\"))\n" +
        "    sdkDtsBundleOutputFile.set(layout.projectDirectory.file(\"...\"))\n" +
        "  }",
    )
  }
  // Enforce the "both testing outputs or neither" invariant the kdoc claims. Without
  // this check, a consumer that wires only `sdkDtsTestingBundleOutputFile` (or only the
  // runtime) silently ships an incomplete pair — the missing artifact only surfaces at
  // trailmap-author time as a `bun test` module-resolution failure or a tsc unresolved-import
  // error, which is failure-far-from-cause. Better to fail loud at Gradle configure time.
  val testingDtsSet = ext.sdkDtsTestingBundleOutputFile.isPresent
  val testingRuntimeSet = ext.sdkTestingRuntimeOutputFile.isPresent
  if (testingDtsSet != testingRuntimeSet) {
    val missing = if (testingDtsSet) "sdkTestingRuntimeOutputFile" else "sdkDtsTestingBundleOutputFile"
    val present = if (testingDtsSet) "sdkDtsTestingBundleOutputFile" else "sdkTestingRuntimeOutputFile"
    throw GradleException(
      "trailblaze.sdk-dts-bundle: $present is set but $missing is not — the testing " +
        "declaration bundle (`testing.d.ts`) and runtime module (`testing.js`) are a " +
        "paired unit (one is the type surface, the other is what `bun test` executes). " +
        "Set both, or neither. To wire both:\n" +
        "  trailblazeSdkDtsBundle {\n" +
        "    sdkDtsTestingBundleOutputFile.set(layout.projectDirectory.file(\"...\"))\n" +
        "    sdkTestingRuntimeOutputFile.set(layout.projectDirectory.file(\"...\"))\n" +
        "  }",
    )
  }
}

// Bundle-source input declarations for `bundleTrailblazeSdkDts` — they drive Gradle's
// UP-TO-DATE check. Inputs include the SDK sources, the package.json
// (controls the inlined-externals via the deps list), the local lockfile so a
// `bun install` drift in zod or typescript invalidates UP-TO-DATE, and the
// hand-authored [RUNTIME_GLOBALS_FILENAME] file whose contents are appended after
// `dts-bundle-generator` runs (see [runDtsBundleGenerator]). Matches the shape used
// in [TrailblazeSdkBundlePlugin] for the same reasons documented there.
private fun declareSdkDtsBundleInputs(
  task: Task,
  ext: TrailblazeSdkDtsBundleExtension,
  project: Project,
) {
  task.inputs.files(
    project.provider {
      if (ext.trailblazeSdkDir.isPresent) project.fileTree(ext.trailblazeSdkDir.get().dir("src"))
      else project.files()
    },
  ).withPropertyName("sdkSources")
  task.inputs.files(
    project.provider {
      if (ext.trailblazeSdkDir.isPresent) {
        project.files(ext.trailblazeSdkDir.get().file("package.json"))
      } else {
        project.files()
      }
    },
  ).withPropertyName("sdkPackageJson")
  task.inputs.files(
    project.provider {
      if (!ext.trailblazeSdkDir.isPresent) return@provider emptyList<File>()
      val dir = ext.trailblazeSdkDir.get()
      listOf(dir.file("bun.lock").asFile).filter { it.exists() }
    },
  ).withPropertyName("sdkLockFiles")
  // Treat `runtime-globals.d.ts` as a MANDATORY input rather than a conditional one —
  // [appendRuntimeGlobals] hard-fails if the file is missing, so leaving the input
  // optional would let Gradle skip the bundle task as UP-TO-DATE and then crash inside
  // the task body. Surfacing the missing-file failure at input-declaration time (via
  // [requireRuntimeGlobalsFile]) means a rename/typo is caught BEFORE Gradle commits to
  // an execution decision — the directed error fires immediately instead of after a
  // confusing "UP-TO-DATE then exception" sequence.
  task.inputs.files(
    project.provider {
      if (!ext.trailblazeSdkDir.isPresent) return@provider emptyList<File>()
      listOf(requireRuntimeGlobalsFile(ext.trailblazeSdkDir.get().asFile))
    },
  ).withPropertyName("sdkRuntimeGlobals")
}

/**
 * Resolve `<sdkDir>/runtime-globals.d.ts` and assert the file is present — used both
 * by [declareSdkDtsBundleInputs] (so a missing file surfaces before Gradle's
 * UP-TO-DATE decision) and by [appendRuntimeGlobals] (so the append helper still
 * fails loud if it's called against a malformed SDK layout in isolation).
 *
 * The error message includes the relative-style hint (`sdks/typescript/`)
 * so a developer reading a CI log can locate the file without having to know
 * the absolute container path the CI agent ran inside.
 */
private fun requireRuntimeGlobalsFile(sdkDir: File): File {
  val f = File(sdkDir, RUNTIME_GLOBALS_FILENAME)
  if (!f.isFile) {
    throw GradleException(
      "Runtime-globals declaration file '$RUNTIME_GLOBALS_FILENAME' not found at " +
        "${f.absolutePath}. Expected at sdks/typescript/$RUNTIME_GLOBALS_FILENAME " +
        "(hand-authored, committed). If this file was removed or renamed, restore it " +
        "(`git restore sdks/typescript/$RUNTIME_GLOBALS_FILENAME`) before regenerating " +
        "or verifying the SDK declaration bundle.",
    )
  }
  return f
}

/** Relative path (under [sdkDir]) of the install fingerprint stamp. */
private const val SDK_INSTALL_STAMP_PATH = "node_modules/.trailblaze-install-lock"

/** Current `bun.lock` contents for [sdkDir], or "" when the lockfile is absent. */
private fun sdkLockText(sdkDir: File): String =
  File(sdkDir, "bun.lock").let { if (it.exists()) it.readText() else "" }

/**
 * Pure decision: does the SDK's `node_modules` need a (re)install before a bundle task shells out
 * to dts-bundle-generator / esbuild? Extracted from [ensureSdkNodeModules] so this branchy
 * skip-vs-reinstall logic is unit-testable without launching bun or Gradle (see
 * `ShouldReinstallSdkNodeModulesTest`).
 *
 * Returns `true` (reinstall) unless BOTH tool binaries exist AND the recorded install fingerprint
 * (`node_modules/.trailblaze-install-lock`) matches the current `bun.lock`. **Keying the skip on
 * the lockfile — not just binary presence — is load-bearing:** a warm `node_modules` whose
 * `bun.lock` has since changed still has the old binaries on disk, and skipping on existence alone
 * would bundle against stale deps even though Gradle correctly re-ran the bundle task (`bun.lock`
 * is a declared input), silently breaking the determinism guarantee this PR relies on.
 */
internal fun shouldReinstallSdkNodeModules(sdkDir: File): Boolean {
  val dtsBin = File(sdkDir, "node_modules/.bin/dts-bundle-generator")
  val esbuildBin = File(sdkDir, "node_modules/.bin/esbuild")
  val installStamp = File(sdkDir, SDK_INSTALL_STAMP_PATH)
  return !(
    dtsBin.exists() && esbuildBin.exists() &&
      installStamp.exists() && installStamp.readText() == sdkLockText(sdkDir)
  )
}

/**
 * Ensure the SDK's devDependencies (`dts-bundle-generator`, `esbuild`) are installed before
 * a bundle task shells out to them. The bundles are build artifacts rather than committed
 * source, so every build that produces them installs `node_modules` from the committed
 * `bun.lock` — `--frozen-lockfile` keeps the resolution byte-deterministic. The skip decision
 * lives in [shouldReinstallSdkNodeModules]; on (re)install we record the `bun.lock` fingerprint
 * so the next call can skip.
 *
 * Trailblaze is bun-only and bun is pinned via Hermit, so `bun` is expected on PATH for any
 * build that compiles the framework. A missing `bun` (or a non-zero install) fails loud here
 * with a directed message rather than a raw `IOException` stack trace or a silently-empty bundle.
 *
 * **Concurrency.** Three generator tasks across three projects call this against the SAME
 * `sdks/typescript` dir. That is safe today only because `org.gradle.parallel` is OFF (commented
 * out in `gradle.properties`), so a single build runs them sequentially. If parallel execution is
 * ever enabled, these installs must be serialized (a shared install task the generators depend on,
 * or a file lock) — concurrent `bun install` into one `node_modules` can corrupt it.
 */
internal fun ensureSdkNodeModules(sdkDir: File, logFile: File) {
  if (!shouldReinstallSdkNodeModules(sdkDir)) return
  val dtsBin = File(sdkDir, "node_modules/.bin/dts-bundle-generator")
  val esbuildBin = File(sdkDir, "node_modules/.bin/esbuild")
  logFile.parentFile.mkdirs()
  logFile.writeText("")
  val proc = try {
    ProcessBuilder("bun", "install", "--frozen-lockfile")
      .directory(sdkDir)
      .redirectErrorStream(true)
      .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
      .start()
  } catch (e: java.io.IOException) {
    throw GradleException(
      "Could not launch `bun` to install the @trailblaze/scripting SDK devDependencies in " +
        "${sdkDir.absolutePath}. Trailblaze is bun-only and `bun` is a hard build prerequisite " +
        "for generating the SDK bundles — put it on PATH by activating the repo's Hermit env " +
        "(`source bin/activate-hermit`) or install it from https://bun.sh/. Cause: ${e.message}",
      e,
    )
  }
  try {
    if (!proc.waitFor(15, TimeUnit.MINUTES)) {
      throw GradleException(
        "`bun install --frozen-lockfile` in ${sdkDir.absolutePath} did not finish within " +
          "15 minutes — stuck or deadlocked. See ${logFile.absolutePath}.",
      )
    }
    if (proc.exitValue() != 0) {
      throw GradleException(
        "`bun install --frozen-lockfile` failed (exit ${proc.exitValue()}) in " +
          "${sdkDir.absolutePath}. The TS SDK's devDependencies are required to build the " +
          "@trailblaze/scripting bundles. Ensure `bun` is on PATH (activate the repo's " +
          "Hermit env: `source bin/activate-hermit`) and see ${logFile.absolutePath}.",
      )
    }
    if (!dtsBin.exists() || !esbuildBin.exists()) {
      throw GradleException(
        "`bun install --frozen-lockfile` reported success but the expected binaries are still " +
          "missing under ${sdkDir.absolutePath}/node_modules/.bin/ (dts-bundle-generator / " +
          "esbuild). See ${logFile.absolutePath}.",
      )
    }
  } finally {
    if (proc.isAlive) proc.destroyForcibly()
  }
  // Record the lockfile we installed from so the next call skips a redundant install when
  // `bun.lock` is unchanged. Best-effort — a write failure just means the next call re-installs.
  runCatching { File(sdkDir, SDK_INSTALL_STAMP_PATH).writeText(sdkLockText(sdkDir)) }
}

/**
 * Shared dts-bundle-generator invocation used by `bundleTrailblazeSdkDts`. Single source of
 * truth for the argv — keeps the generated bundle byte-deterministic across machines.
 *
 * **What inlines.** `--external-inlines zod @modelcontextprotocol/sdk` pulls every `zod`
 * type the SDK transitively re-exports AND the small set of MCP-SDK types that surface
 * in the SDK's public type positions (e.g. `TrailblazeToolSpec.inputSchema:
 * ZodRawShapeCompat | AnySchema`) into the bundle. Consumer trailmaps then have a fully
 * self-contained `.d.ts` — `lib: ["ES2022"]` is enough, no `node_modules/zod` or
 * `node_modules/@modelcontextprotocol/sdk` required on disk. Dropping either inline
 * makes the bundle reference symbols whose definitions live in unresolvable external
 * `.d.ts` files at the consumer's perspective and per-trailmap `tsc` fails with
 * `Cannot find module`.
 *
 * **`--no-check`** disables dts-bundle-generator's tsc validation of the generated bundle.
 * The validation is duplicative — per-trailmap `tsc --noEmit` in CI is the real consumer-side
 * gate. Running validation would also force this Gradle plugin onto the slower path of
 * spinning up a tsc instance per build for no incremental safety.
 */
private fun runDtsBundleGenerator(
  sdkDir: File,
  entryFile: File,
  outputFile: File,
  logFile: File,
  appendRuntimeGlobals: Boolean,
) {
  val dtsBin = File(sdkDir, "node_modules/.bin/dts-bundle-generator")
  if (!dtsBin.exists()) {
    throw GradleException(
      "dts-bundle-generator not found at ${dtsBin.absolutePath}. Run `(cd ${sdkDir.absolutePath} && " +
        "bun install --frozen-lockfile)` to pull the TS SDK's devDependencies before " +
        "regenerating the bundle. Trailblaze is bun-only — activate the repo's Hermit env " +
        "(`source bin/activate-hermit`) or install bun from https://bun.sh/.",
    )
  }
  outputFile.parentFile.mkdirs()

  // Run dts-bundle-generator THROUGH bun rather than executing its `.bin` shim directly. The
  // shim is `#!/usr/bin/env node`, so a direct exec needs `node` on PATH — but Trailblaze is
  // bun-only and the CI agents that now build these bundles have no node (the direct exec
  // failed with exit 127 = "node: No such file or directory" on the bun-only uber-JAR build).
  // `bun <jsfile>` runs the CLI's JavaScript under bun, ignoring the node shebang, with no node
  // required. (esbuild, by contrast, is a native binary bun symlinks into `.bin`, so it's
  // invoked directly below.)
  val argv = listOf(
    "bun",
    dtsBin.absolutePath,
    "--external-inlines", "zod", "@modelcontextprotocol/sdk",
    "--no-check",
    "--out-file", outputFile.absolutePath,
    entryFile.absolutePath,
  )

  logFile.parentFile.mkdirs()
  logFile.writeText("")

  // `argv[0]` is `bun` (we run dts-bundle-generator's JS through bun — see above). On a warm
  // node_modules `ensureSdkNodeModules` returns early without ever launching bun, so this can be
  // the first bun launch; if bun is off PATH, surface the same directed error rather than a raw
  // IOException stack trace.
  val proc = try {
    ProcessBuilder(argv)
      .directory(sdkDir)
      .redirectErrorStream(true)
      .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
      .start()
  } catch (e: java.io.IOException) {
    throw GradleException(
      "Could not launch `bun` to run dts-bundle-generator in ${sdkDir.absolutePath}. Trailblaze " +
        "is bun-only and `bun` is a hard build prerequisite for generating the SDK bundles — put " +
        "it on PATH by activating the repo's Hermit env (`source bin/activate-hermit`) or install " +
        "it from https://bun.sh/. Cause: ${e.message}",
      e,
    )
  }
  try {
    if (!proc.waitFor(2, TimeUnit.MINUTES)) {
      throw GradleException(
        "dts-bundle-generator did not finish within 2 minutes — stuck or deadlocked. See ${logFile.absolutePath}.",
      )
    }
    if (proc.exitValue() != 0) {
      throw GradleException(
        "dts-bundle-generator failed (exit ${proc.exitValue()}). See ${logFile.absolutePath}.",
      )
    }
    if (!outputFile.exists() || outputFile.length() == 0L) {
      throw GradleException(
        "dts-bundle-generator reported success but ${outputFile.absolutePath} is missing or empty. " +
          "See ${logFile.absolutePath}.",
      )
    }
  } finally {
    if (proc.isAlive) proc.destroyForcibly()
  }

  if (appendRuntimeGlobals) {
    appendRuntimeGlobals(sdkDir, outputFile)
  }
}

/**
 * Transpile a single `.ts` source file to an ESM `.js` module via esbuild — no bundling,
 * no minification, just TypeScript-to-JavaScript conversion. Used for the `testing.js`
 * runtime artifact that `bun test` loads when an author imports
 * `@trailblaze/scripting/testing`. The input source MUST be self-contained at runtime
 * (only type-only imports from sibling SDK files); a relative runtime import would dangle
 * after transpile because we deliberately don't ship sibling SDK source to the consumer.
 *
 * The argv must be stable across regenerations so the generated artifact is byte-reproducible
 * across machines (the property that lets it be a gitignored build artifact). Don't add
 * timestamped banners or hash-based suffixes here — they would break that reproducibility.
 */
private fun runEsbuildTranspile(sdkDir: File, entryFile: File, outputFile: File, logFile: File) {
  val esbuildBin = File(sdkDir, "node_modules/.bin/esbuild")
  if (!esbuildBin.exists()) {
    throw GradleException(
      "esbuild not found at ${esbuildBin.absolutePath}. Run `(cd ${sdkDir.absolutePath} && " +
        "bun install --frozen-lockfile)` to pull the TS SDK's devDependencies before " +
        "regenerating the testing runtime. Trailblaze is bun-only — activate the repo's " +
        "Hermit env (`source bin/activate-hermit`) or install bun from https://bun.sh/.",
    )
  }
  outputFile.parentFile.mkdirs()
  // No `--bundle` — testing.ts has only type-only imports, so plain transpile keeps
  // the output minimal and self-contained. Bundling would attempt to resolve the
  // (erased-anyway) type imports and produce noisier output.
  val argv = listOf(
    esbuildBin.absolutePath,
    entryFile.absolutePath,
    "--format=esm",
    "--target=es2020",
    "--outfile=${outputFile.absolutePath}",
  )
  logFile.parentFile.mkdirs()
  logFile.writeText("")
  val proc = ProcessBuilder(argv)
    .directory(sdkDir)
    .redirectErrorStream(true)
    .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
    .start()
  try {
    if (!proc.waitFor(2, TimeUnit.MINUTES)) {
      throw GradleException(
        "esbuild did not finish within 2 minutes — stuck or deadlocked. See ${logFile.absolutePath}.",
      )
    }
    if (proc.exitValue() != 0) {
      throw GradleException(
        "esbuild failed (exit ${proc.exitValue()}). See ${logFile.absolutePath}.",
      )
    }
    if (!outputFile.exists() || outputFile.length() == 0L) {
      throw GradleException(
        "esbuild reported success but ${outputFile.absolutePath} is missing or empty. " +
          "See ${logFile.absolutePath}.",
      )
    }
  } finally {
    if (proc.isAlive) proc.destroyForcibly()
  }
}

/**
 * Bundle a TypeScript entry to an ESM JavaScript module via esbuild — runtime
 * companion to the `.d.ts` rollup that `dts-bundle-generator` produces. Used for the
 * `dist/index.js` artifact that bun resolves at runtime when a scripted tool does
 * `import { trailblaze } from "@trailblaze/scripting"`.
 *
 * **Why bundle (not transpile).** Unlike [runEsbuildTranspile] (which serves
 * `dist/testing.js`, a pure type-only-importer), `src/index.ts` re-exports from
 * sibling modules that pull in `zod` and `@modelcontextprotocol/sdk` runtime. A
 * transpile-only output would dangle on those imports at load time because the
 * workspace SDK directory has no `node_modules/`. Inlining every transitive runtime
 * dep into one file means a workspace receives a self-contained module that resolves
 * cleanly under bun (or any ES-compatible runtime).
 *
 * **Argv shape.** `--platform=neutral` keeps the output free of Node-only resolution
 * shortcuts so the bundle stays portable across runtimes (bun, the on-device runner,
 * a future browser-side consumer). `--external:node:process` mirrors the IIFE
 * bundle's stance: the SDK doesn't itself import `node:process`, but a transitive
 * dep that does should resolve through the host environment rather than fail at
 * bundle time. `--target=es2020` matches the per-trailmap tsconfig's `target: ES2022`
 * minus the small set of ES2022 features esbuild can't downlevel (top-level await
 * is fine in ESM at es2020 already). The `--banner:js=` is a hand-edit deterrent
 * (the file is a generated build artifact, not committed source).
 *
 * The argv must be stable across regenerations so the generated bundle is byte-reproducible
 * across machines. Don't add timestamped banners or hash-based suffixes — they would break
 * that reproducibility.
 */
private fun runEsbuildEsmBundle(sdkDir: File, entryFile: File, outputFile: File, logFile: File) {
  val esbuildBin = File(sdkDir, "node_modules/.bin/esbuild")
  if (!esbuildBin.exists()) {
    throw GradleException(
      "esbuild not found at ${esbuildBin.absolutePath}. Run `(cd ${sdkDir.absolutePath} && " +
        "bun install --frozen-lockfile)` to pull the TS SDK's devDependencies before " +
        "regenerating the runtime bundle. Trailblaze is bun-only — activate the repo's " +
        "Hermit env (`source bin/activate-hermit`) or install bun from https://bun.sh/.",
    )
  }
  outputFile.parentFile.mkdirs()
  val bannerJs =
    "/* GENERATED FILE — do not hand-edit. " +
      "Source: sdks/typescript/src/. " +
      "Regenerate with ./gradlew :trailblaze-models:bundleTrailblazeSdkDts */"
  val argv = listOf(
    esbuildBin.absolutePath,
    entryFile.absolutePath,
    "--bundle",
    "--platform=neutral",
    "--format=esm",
    "--target=es2020",
    "--main-fields=module,main",
    "--external:node:process",
    // The MCP SDK's stdio transport (`StdioServerTransport`) statically imports
    // `node:process` at module top-level — esbuild's `--external:node:process` above keeps
    // the import call literal but still hoists it to the bundle's top scope. The IIFE
    // wrapper produced by `DaemonScriptedToolBundler` then turns that into a top-level
    // `__require("node:process")` that throws on QuickJS load — breaking every Square
    // (and other scripted-tool-using) trail at session start.
    //
    // `run.ts` already uses `await import(...)` to keep the stdio path dynamic at source
    // level, but esbuild's `--bundle` walks dynamic imports it can statically resolve and
    // inlines them. Externalizing the stdio entry point here leaves the dynamic import as
    // a literal `import("@modelcontextprotocol/sdk/server/stdio.js")` in the produced
    // ESM. On bun/node the dynamic resolver picks it up at runtime; on QuickJS the early
    // return on `globalThis.__trailblazeInProcessTransport` means the import is never hit.
    // The sibling external in `DaemonScriptedToolBundler.runEsbuild` ensures the per-tool
    // IIFE bundler doesn't re-walk into the SDK and re-introduce the same eager `require`.
    "--external:@modelcontextprotocol/sdk/server/stdio.js",
    "--banner:js=$bannerJs",
    "--outfile=${outputFile.absolutePath}",
  )
  logFile.parentFile.mkdirs()
  logFile.writeText("")
  val proc = ProcessBuilder(argv)
    .directory(sdkDir)
    .redirectErrorStream(true)
    .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
    .start()
  try {
    if (!proc.waitFor(2, TimeUnit.MINUTES)) {
      throw GradleException(
        "esbuild did not finish within 2 minutes — stuck or deadlocked. See ${logFile.absolutePath}.",
      )
    }
    if (proc.exitValue() != 0) {
      throw GradleException(
        "esbuild failed (exit ${proc.exitValue()}). See ${logFile.absolutePath}.",
      )
    }
    if (!outputFile.exists() || outputFile.length() == 0L) {
      throw GradleException(
        "esbuild reported success but ${outputFile.absolutePath} is missing or empty. " +
          "See ${logFile.absolutePath}.",
      )
    }
  } finally {
    if (proc.isAlive) proc.destroyForcibly()
  }
}

/**
 * Constant filename of the hand-authored ambient-globals declaration file appended to
 * the bundled `.d.ts`. Lives at the package root (sibling of `src/` and `dist/`) so
 * the SDK's own `tsc --noEmit` (whose `include` is `src/**/*.ts`) doesn't pick it up
 * — the declarations should only land in the consumer-facing bundle, not in the SDK's
 * own build.
 */
internal const val RUNTIME_GLOBALS_FILENAME: String = "runtime-globals.d.ts"

/**
 * Append the curated runtime-globals ambient declarations to the bundled `.d.ts` so
 * trailmap authors can reference `URL` / `fetch` / `setTimeout` / `console` / etc. from a
 * `.ts` tool file without a per-trailmap `globals.d.ts` shim. See the kdoc on
 * [RUNTIME_GLOBALS_FILENAME] and the file's own header for the scope decisions and
 * the host-vs-on-device runtime caveats.
 *
 * **Why post-process rather than inline via a triple-slash reference in
 * `src/index.ts`.** `dts-bundle-generator` treats `///<reference>` directives as
 * external-resolution hints and drops them from the rolled-up output by default —
 * preserving them would require either a non-default flag with unpredictable
 * cross-version behavior or a custom config file that lives outside the SDK source
 * tree. A deterministic byte-level append after the bundler has finished is the
 * least-magic option, and the `runtime-globals.d.ts` file remains a hand-editable
 * artifact a human can read in isolation.
 *
 * **Determinism across machines.** `bundleTrailblazeSdkDts` calls `runDtsBundleGenerator`,
 * which calls this append in the same place every run. As long as the runtime-globals file
 * content is identical, the post-processed bundle is byte-identical across machines — the
 * property that lets the bundle be a gitignored build artifact rather than committed source.
 */
private fun appendRuntimeGlobals(sdkDir: File, outputFile: File) {
  val runtimeGlobalsFile = requireRuntimeGlobalsFile(sdkDir)
  val runtimeGlobals = runtimeGlobalsFile.readText(Charsets.UTF_8)
  check(runtimeGlobals.isNotBlank()) {
    "$RUNTIME_GLOBALS_FILENAME at ${runtimeGlobalsFile.absolutePath} is empty. The file " +
      "must declare at least one `declare global { … }` entry — an empty append would " +
      "silently produce a bundle that compiles but exposes no runtime globals to " +
      "consumers, defeating the point of this whole code path."
  }
  val existing = outputFile.readText(Charsets.UTF_8)
  // Normalize: strip trailing NEWLINES from the bundler output (deliberately not
  // `trimEnd()` — that strips spaces and tabs too, which would silently mask a
  // future dts-bundle-generator version that started emitting trailing spaces),
  // then join with a FIXED `\n\n` separator. Eliminates the heuristic that
  // previously inferred the separator from the existing file's last byte — if a
  // future dts-bundle-generator version stops emitting a trailing newline, the
  // heuristic would silently produce a different join than the committed bundle
  // and verify would fail confusingly.
  val joined = existing.trimEnd('\n', '\r') + "\n\n" + runtimeGlobals
  outputFile.writeText(joined, Charsets.UTF_8)
}
