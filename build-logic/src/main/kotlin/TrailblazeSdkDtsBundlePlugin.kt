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
}

/**
 * Registers `bundleTrailblazeSdkDts` and `verifyTrailblazeSdkDtsBundle` against the
 * [TrailblazeSdkDtsBundleExtension] paths. Same shape as [TrailblazeSdkBundlePlugin] — the
 * pairing exists for the same reason: the `.d.ts` bundle, like the `.js` bundle, is a
 * committed artifact that ships in JAR resources, and CI needs a byte-diff gate so SDK
 * source edits without a regen don't slip through.
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
        "Regenerates the committed @trailblaze/scripting declaration bundle " +
          "(dist/index.d.ts). Manual-invocation: run after editing sdks/typescript/src/*.ts " +
          "and commit the regenerated bundle alongside the source change."

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

      task.doFirst { requireExtensionConfigured(ext) }
      task.doLast {
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
            "(${outputFile.length() / 1024} KiB). Remember to `git add` and commit it " +
            "alongside the SDK source change.",
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
      }
    }

    project.tasks.register("verifyTrailblazeSdkDtsBundle") { task ->
      task.group = "verification"
      task.description =
        "Verifies the committed dist/index.d.ts matches a fresh dts-bundle-generator output. " +
          "Fails with the regenerate command when they differ. Wired into CI static checks."

      val tempBundle = project.layout.buildDirectory.file("tmp/verify-trailblaze-sdk-dts-bundle.d.ts")
      val tempTestingBundle = project.layout.buildDirectory.file("tmp/verify-trailblaze-sdk-dts-testing-bundle.d.ts")
      val tempTestingRuntime = project.layout.buildDirectory.file("tmp/verify-trailblaze-sdk-testing-runtime.js")

      declareSdkDtsBundleInputs(task, ext, project)
      // Same conditional-collection pattern as `verifyTrailblazeSdkBundle` — keeps
      // UP-TO-DATE correctness across the "bundle exists" vs "bundle missing" transition
      // without surfacing Gradle's generic "input file does not exist" error before the
      // friendly doLast guard can fire.
      task.inputs.files(
        project.provider {
          if (!ext.sdkDtsBundleOutputFile.isPresent) return@provider emptyList<File>()
          val committed = ext.sdkDtsBundleOutputFile.get().asFile
          if (committed.exists()) listOf(committed) else emptyList()
        },
      ).withPropertyName("committedDtsBundle")
      task.inputs.files(
        project.provider {
          if (!ext.sdkDtsTestingBundleOutputFile.isPresent) return@provider emptyList<File>()
          val committed = ext.sdkDtsTestingBundleOutputFile.get().asFile
          if (committed.exists()) listOf(committed) else emptyList()
        },
      ).withPropertyName("committedDtsTestingBundle")
      task.inputs.files(
        project.provider {
          if (!ext.sdkTestingRuntimeOutputFile.isPresent) return@provider emptyList<File>()
          val committed = ext.sdkTestingRuntimeOutputFile.get().asFile
          if (committed.exists()) listOf(committed) else emptyList()
        },
      ).withPropertyName("committedSdkTestingRuntime")
      task.outputs.file(tempBundle).withPropertyName("regeneratedDtsBundle")
      task.outputs.files(
        project.provider {
          if (ext.sdkDtsTestingBundleOutputFile.isPresent) {
            project.files(tempTestingBundle)
          } else {
            project.files()
          }
        },
      ).withPropertyName("regeneratedDtsTestingBundle")
      task.outputs.files(
        project.provider {
          if (ext.sdkTestingRuntimeOutputFile.isPresent) {
            project.files(tempTestingRuntime)
          } else {
            project.files()
          }
        },
      ).withPropertyName("regeneratedSdkTestingRuntime")

      task.doFirst { requireExtensionConfigured(ext) }
      task.doLast {
        val committed = ext.sdkDtsBundleOutputFile.get().asFile
        if (!committed.exists()) {
          throw GradleException(
            "Committed .d.ts bundle missing at ${committed.absolutePath}. Run " +
              "`./gradlew :trailblaze-models:bundleTrailblazeSdkDts` and commit the result.",
          )
        }

        val tempBundleFile = tempBundle.get().asFile
        runDtsBundleGenerator(
          sdkDir = ext.trailblazeSdkDir.get().asFile,
          entryFile = File(ext.trailblazeSdkDir.get().asFile, "src/index.ts"),
          outputFile = tempBundleFile,
          logFile = project.layout.buildDirectory
            .file("tmp/verify-trailblaze-sdk-dts-bundle.log").get().asFile,
          appendRuntimeGlobals = true,
        )

        val committedBytes = committed.readBytes()
        val regeneratedBytes = tempBundleFile.readBytes()
        if (!committedBytes.contentEquals(regeneratedBytes)) {
          throw GradleException(
            "dist/index.d.ts does not match a fresh dts-bundle-generator output.\n" +
              "Likely causes (in order of frequency):\n" +
              "  1. You edited sdks/typescript/src/ (or sdks/typescript/runtime-globals.d.ts)\n" +
              "     without regenerating the bundle.\n" +
              "  2. A transitive dep (e.g. typescript, zod) drifted via registry resolution since\n" +
              "     the bundle was last committed. Try `(cd ${ext.trailblazeSdkDir.get().asFile.relativeTo(project.rootDir)} && bun install)`\n" +
              "     and re-run.\n" +
              "  3. Your local node_modules drifted from package-lock.json. Try\n" +
              "     `(cd ${ext.trailblazeSdkDir.get().asFile.relativeTo(project.rootDir)} && npm ci)` to restore exact lockfile\n" +
              "     versions before regenerating.\n" +
              "Regenerate the bundle and commit the result:\n" +
              "  ./gradlew :trailblaze-models:bundleTrailblazeSdkDts\n" +
              "  git add ${committed.relativeTo(project.rootDir)} && git commit -m \"chore(sdk): regenerate dts bundle\"\n" +
              "After regenerating, sanity-check the bundle end-to-end with:\n" +
              "  ./trailblaze check PACK_ID    # e.g. playwrightsample\n" +
              "(runs the bundled tsc against the pack's generated tsconfig — using an\n" +
              "ALL_CAPS placeholder rather than the angle-bracket form so a developer\n" +
              "who copies the line verbatim doesn't trip bash's stdin-redirection on the\n" +
              "`<...>` characters).\n" +
              "Committed bundle size: ${committedBytes.size} bytes\n" +
              "Regenerated bundle size: ${regeneratedBytes.size} bytes",
          )
        }
        task.logger.lifecycle(
          "✓ dist/index.d.ts is fresh (${committedBytes.size} bytes match).",
        )

        // Optional sibling testing bundle. Same byte-diff gate; same regenerate
        // hint. Skipped silently if the extension didn't configure a second output
        // path so the plugin stays usable for the older single-bundle wiring.
        if (ext.sdkDtsTestingBundleOutputFile.isPresent) {
          val committedTesting = ext.sdkDtsTestingBundleOutputFile.get().asFile
          if (!committedTesting.exists()) {
            throw GradleException(
              "Committed testing .d.ts bundle missing at ${committedTesting.absolutePath}. " +
                "Run `./gradlew :trailblaze-models:bundleTrailblazeSdkDts` and commit the result.",
            )
          }
          val tempTestingBundleFile = tempTestingBundle.get().asFile
          runDtsBundleGenerator(
            sdkDir = ext.trailblazeSdkDir.get().asFile,
            entryFile = File(ext.trailblazeSdkDir.get().asFile, "src/testing.ts"),
            outputFile = tempTestingBundleFile,
            logFile = project.layout.buildDirectory
              .file("tmp/verify-trailblaze-sdk-dts-testing-bundle.log").get().asFile,
            appendRuntimeGlobals = false,
          )
          val committedTestingBytes = committedTesting.readBytes()
          val regeneratedTestingBytes = tempTestingBundleFile.readBytes()
          if (!committedTestingBytes.contentEquals(regeneratedTestingBytes)) {
            throw GradleException(
              "dist/testing.d.ts does not match a fresh dts-bundle-generator output.\n" +
                "Regenerate the testing bundle and commit the result:\n" +
                "  ./gradlew :trailblaze-models:bundleTrailblazeSdkDts\n" +
                "  git add ${committedTesting.relativeTo(project.rootDir)} && " +
                "git commit -m \"chore(sdk): regenerate dts testing bundle\"\n" +
                "Committed bundle size: ${committedTestingBytes.size} bytes\n" +
                "Regenerated bundle size: ${regeneratedTestingBytes.size} bytes",
            )
          }
          task.logger.lifecycle(
            "✓ dist/testing.d.ts is fresh (${committedTestingBytes.size} bytes match).",
          )
        }

        // Optional sibling testing runtime (`dist/testing.js`). Verified the same
        // byte-diff way as the `.d.ts` bundles. `bun test` consumes this file via
        // the per-pack tsconfig's `paths` mapping at runtime; the `.d.ts` covers
        // tsc's typing path.
        if (ext.sdkTestingRuntimeOutputFile.isPresent) {
          val committedRuntime = ext.sdkTestingRuntimeOutputFile.get().asFile
          if (!committedRuntime.exists()) {
            throw GradleException(
              "Committed testing runtime missing at ${committedRuntime.absolutePath}. " +
                "Run `./gradlew :trailblaze-models:bundleTrailblazeSdkDts` and commit the result.",
            )
          }
          val tempRuntimeFile = tempTestingRuntime.get().asFile
          runEsbuildTranspile(
            sdkDir = ext.trailblazeSdkDir.get().asFile,
            entryFile = File(ext.trailblazeSdkDir.get().asFile, "src/testing.ts"),
            outputFile = tempRuntimeFile,
            logFile = project.layout.buildDirectory
              .file("tmp/verify-trailblaze-sdk-testing-runtime.log").get().asFile,
          )
          val committedRuntimeBytes = committedRuntime.readBytes()
          val regeneratedRuntimeBytes = tempRuntimeFile.readBytes()
          if (!committedRuntimeBytes.contentEquals(regeneratedRuntimeBytes)) {
            throw GradleException(
              "dist/testing.js does not match a fresh esbuild transpile output.\n" +
                "Regenerate and commit:\n" +
                "  ./gradlew :trailblaze-models:bundleTrailblazeSdkDts\n" +
                "  git add ${committedRuntime.relativeTo(project.rootDir)} && " +
                "git commit -m \"chore(sdk): regenerate testing runtime\"\n" +
                "Committed size: ${committedRuntimeBytes.size} bytes\n" +
                "Regenerated size: ${regeneratedRuntimeBytes.size} bytes",
            )
          }
          task.logger.lifecycle(
            "✓ dist/testing.js is fresh (${committedRuntimeBytes.size} bytes match).",
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
  // pack-author time as a `bun test` module-resolution failure or a tsc unresolved-import
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

// Shared bundle-source input declarations used by both `bundleTrailblazeSdkDts` and
// `verifyTrailblazeSdkDtsBundle`. Inputs include the SDK sources, the package.json
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
      listOf("bun.lock", "bun.lockb", "package-lock.json")
        .map { dir.file(it).asFile }
        .filter { it.exists() }
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

/**
 * Shared dts-bundle-generator invocation used by both `bundleTrailblazeSdkDts` (writes to
 * the committed path) and `verifyTrailblazeSdkDtsBundle` (writes to a temp path and
 * byte-diffs). Single source of truth for the argv — drift would make verify report false
 * positives on a clean bundle.
 *
 * **What inlines.** `--external-inlines zod @modelcontextprotocol/sdk` pulls every `zod`
 * type the SDK transitively re-exports AND the small set of MCP-SDK types that surface
 * in the SDK's public type positions (e.g. `TrailblazeToolSpec.inputSchema:
 * ZodRawShapeCompat | AnySchema`) into the bundle. Consumer packs then have a fully
 * self-contained `.d.ts` — `lib: ["ES2022"]` is enough, no `node_modules/zod` or
 * `node_modules/@modelcontextprotocol/sdk` required on disk. Dropping either inline
 * makes the bundle reference symbols whose definitions live in unresolvable external
 * `.d.ts` files at the consumer's perspective and per-pack `tsc` fails with
 * `Cannot find module`.
 *
 * **`--no-check`** disables dts-bundle-generator's tsc validation of the generated bundle.
 * The validation is duplicative — `verifyTrailblazeSdkDtsBundle` already byte-compares
 * against a committed file, and per-pack `tsc --noEmit` in CI is the real consumer-side
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
        "bun install)` (or `npm install`) to pull the TS SDK's devDependencies before " +
        "regenerating the bundle.",
    )
  }
  outputFile.parentFile.mkdirs()

  val argv = listOf(
    dtsBin.absolutePath,
    "--external-inlines", "zod", "@modelcontextprotocol/sdk",
    "--no-check",
    "--out-file", outputFile.absolutePath,
    entryFile.absolutePath,
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
 * The verify task byte-diffs the regenerated output against the committed file, so the
 * argv must be stable across regenerations. Don't add timestamped banners or hash-based
 * suffixes here — they would invalidate the byte-diff gate.
 */
private fun runEsbuildTranspile(sdkDir: File, entryFile: File, outputFile: File, logFile: File) {
  val esbuildBin = File(sdkDir, "node_modules/.bin/esbuild")
  if (!esbuildBin.exists()) {
    throw GradleException(
      "esbuild not found at ${esbuildBin.absolutePath}. Run `(cd ${sdkDir.absolutePath} && " +
        "bun install)` (or `npm install`) to pull the TS SDK's devDependencies before " +
        "regenerating the testing runtime.",
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
 * Constant filename of the hand-authored ambient-globals declaration file appended to
 * the bundled `.d.ts`. Lives at the package root (sibling of `src/` and `dist/`) so
 * the SDK's own `tsc --noEmit` (whose `include` is `src/**/*.ts`) doesn't pick it up
 * — the declarations should only land in the consumer-facing bundle, not in the SDK's
 * own build.
 */
internal const val RUNTIME_GLOBALS_FILENAME: String = "runtime-globals.d.ts"

/**
 * Append the curated runtime-globals ambient declarations to the bundled `.d.ts` so
 * pack authors can reference `URL` / `fetch` / `setTimeout` / `console` / etc. from a
 * `.ts` tool file without a per-pack `globals.d.ts` shim. See the kdoc on
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
 * **Determinism for the verify byte-diff gate.** Both `bundleTrailblazeSdkDts` (which
 * writes to the committed path) and `verifyTrailblazeSdkDtsBundle` (which writes to a
 * temp path and byte-diffs) call `runDtsBundleGenerator`, which calls this append in
 * the same place. As long as the file content is identical between runs, the
 * post-processed bundles are byte-identical too — the gate works without further
 * accommodation.
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
