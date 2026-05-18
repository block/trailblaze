import java.io.File
import java.util.concurrent.TimeUnit
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty

/**
 * Configuration for the `trailblaze.sdk-bundle` plugin. Both production
 * (`:trailblaze-scripting-bundle`) and the plugin's functional tests set these properties to
 * point at a TypeScript SDK source dir and a "committed" bundle output path; the plugin then
 * registers `bundleTrailblazeSdk` (regenerate-and-write) and `verifyTrailblazeSdkBundle`
 * (regenerate-to-temp and byte-diff) against them.
 */
interface TrailblazeSdkBundleExtension {
  /** Root of the TS SDK package — contains `package.json`, `src/`, `node_modules/.bin/esbuild`. */
  val trailblazeSdkDir: DirectoryProperty

  /** Path to the committed `trailblaze-sdk-bundle.js` that consumers ship as a resource. */
  val sdkBundleOutputFile: RegularFileProperty
}

/**
 * Registers `bundleTrailblazeSdk` and `verifyTrailblazeSdkBundle` against the
 * [TrailblazeSdkBundleExtension] paths. Production wires `:trailblaze-scripting-bundle` to the
 * real SDK dir + committed bundle path; the functional test wires a fixture project to a
 * fresh esbuild output and a tmp committed path. Extracting the task bodies into this plugin
 * is what makes the verify task testable — when it lived inline in
 * `trailblaze-scripting-bundle/build.gradle.kts` referencing `layout.projectDirectory`, no
 * GradleTestKit fixture could exercise it without copying the entire task body and risking
 * drift.
 */
class TrailblazeSdkBundlePlugin : Plugin<Project> {
  override fun apply(project: Project) {
    val ext = project.extensions.create(
      "trailblazeSdkBundle",
      TrailblazeSdkBundleExtension::class.java,
    )

    project.tasks.register("bundleTrailblazeSdk") { task ->
      task.group = "build"
      task.description =
        "Regenerates the committed @trailblaze/scripting SDK bundle. Manual-invocation: " +
          "run after editing sdks/typescript/src/*.ts and commit the regenerated bundle " +
          "alongside the source change."

      declareSdkBundleInputs(task, ext, project)
      task.outputs.file(ext.sdkBundleOutputFile).withPropertyName("sdkBundle")

      task.doFirst { requireExtensionConfigured(ext) }
      task.doLast {
        val outputFile = ext.sdkBundleOutputFile.get().asFile
        task.logger.lifecycle(
          "Regenerating SDK bundle → ${outputFile.relativeTo(project.projectDir)}",
        )
        runEsbuildBundle(
          sdkDir = ext.trailblazeSdkDir.get().asFile,
          outputFile = outputFile,
          logFile = project.layout.buildDirectory
            .file("tmp/bundle-trailblaze-sdk.log").get().asFile,
        )
        task.logger.lifecycle(
          "Regenerated ${outputFile.relativeTo(project.projectDir)} " +
            "(${outputFile.length() / 1024} KiB). Remember to `git add` and commit it alongside " +
            "the SDK source change.",
        )
      }
    }

    project.tasks.register("verifyTrailblazeSdkBundle") { task ->
      task.group = "verification"
      task.description =
        "Verifies the committed trailblaze-sdk-bundle.js matches a fresh esbuild output. " +
          "Fails with the regenerate command when they differ. Wired into the CI " +
          "static-checks step."

      val tempBundle = project.layout.buildDirectory.file("tmp/verify-trailblaze-sdk-bundle.js")

      declareSdkBundleInputs(task, ext, project)
      // Track the committed bundle as a file collection that's empty when the file is
      // absent. Two requirements collide here: editing the committed bundle by hand should
      // invalidate UP-TO-DATE (so the verify task re-runs and catches the edit), AND the
      // doLast "Committed bundle missing" check needs to be the actual gate when the bundle
      // is absent. A plain `inputs.file(...).optional()` doesn't get us the second one —
      // Gradle still throws "input file does not exist" because the property has a present
      // value, just one pointing at a missing file. The conditional collection gives both:
      // present file → snapshotted (invalidates UP-TO-DATE on edit); absent → empty snapshot
      // → task runs → friendly doLast guard fires.
      task.inputs.files(
        project.provider {
          if (!ext.sdkBundleOutputFile.isPresent) return@provider emptyList<File>()
          val committed = ext.sdkBundleOutputFile.get().asFile
          if (committed.exists()) listOf(committed) else emptyList()
        },
      ).withPropertyName("committedBundle")
      task.outputs.file(tempBundle).withPropertyName("regeneratedBundle")

      task.doFirst { requireExtensionConfigured(ext) }
      task.doLast {
        val committed = ext.sdkBundleOutputFile.get().asFile
        if (!committed.exists()) {
          throw GradleException(
            "Committed bundle missing at ${committed.absolutePath}. Run " +
              "`./gradlew :trailblaze-scripting-bundle:bundleTrailblazeSdk` and commit the result.",
          )
        }

        val tempBundleFile = tempBundle.get().asFile
        runEsbuildBundle(
          sdkDir = ext.trailblazeSdkDir.get().asFile,
          outputFile = tempBundleFile,
          logFile = project.layout.buildDirectory
            .file("tmp/verify-trailblaze-sdk-bundle.log").get().asFile,
        )

        val committedBytes = committed.readBytes()
        val regeneratedBytes = tempBundleFile.readBytes()
        if (!committedBytes.contentEquals(regeneratedBytes)) {
          throw GradleException(
            "trailblaze-sdk-bundle.js does not match a fresh esbuild output.\n" +
              "Likely causes (in order of frequency):\n" +
              "  1. You edited sdks/typescript/src/ without regenerating the bundle.\n" +
              "  2. A transitive dep (e.g. esbuild) drifted via registry resolution since the\n" +
              "     bundle was last committed. Try `(cd ${ext.trailblazeSdkDir.get().asFile.relativeTo(project.rootDir)} && bun install)`\n" +
              "     and re-run.\n" +
              "Regenerate the bundle and commit the result:\n" +
              "  ./gradlew :trailblaze-scripting-bundle:bundleTrailblazeSdk\n" +
              "  git add ${committed.relativeTo(project.rootDir)} && git commit -m \"chore(bundle): regenerate\"\n" +
              "Committed bundle size: ${committedBytes.size} bytes\n" +
              "Regenerated bundle size: ${regeneratedBytes.size} bytes",
          )
        }
        task.logger.lifecycle(
          "✓ trailblaze-sdk-bundle.js is fresh (${committedBytes.size} bytes match).",
        )
      }
    }
  }
}

// Directed-error check for the consumer-facing failure mode where the plugin is applied but
// the extension is never configured. Without this, an unconfigured consumer hits Gradle's
// generic "no value has been specified" snapshot error which doesn't name the extension
// block. Mirrors the pattern `BundleTrailblazePackTask.generate()` uses for `packsDir`.
private fun requireExtensionConfigured(ext: TrailblazeSdkBundleExtension) {
  if (!ext.trailblazeSdkDir.isPresent || !ext.sdkBundleOutputFile.isPresent) {
    throw GradleException(
      "trailblaze.sdk-bundle: extension is not configured. Add to your build.gradle.kts:\n" +
        "  trailblazeSdkBundle {\n" +
        "    trailblazeSdkDir.set(layout.projectDirectory.dir(\"...\"))\n" +
        "    sdkBundleOutputFile.set(layout.projectDirectory.file(\"...\"))\n" +
        "  }",
    )
  }
}

// Shared bundle-source input declarations used by both `bundleTrailblazeSdk` and
// `verifyTrailblazeSdkBundle`. Keeping these in one place is load-bearing: if a future
// input (e.g. `tsconfig.json`) is added to one task and forgotten on the other, the verify
// gate silently stops protecting against that input's drift. Lock files use a filtered
// collection because either, both, or neither may exist on disk (bun vs npm depending on
// contributor setup). Every input is wrapped in `project.provider { if isPresent ... }` so
// the snapshotter tolerates an unconfigured extension — the directed-error message from
// `requireExtensionConfigured` then fires from doFirst instead of Gradle's generic
// "no value has been specified" snapshot error.
private fun declareSdkBundleInputs(
  task: Task,
  ext: TrailblazeSdkBundleExtension,
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
}

/**
 * Shared esbuild invocation used by both `bundleTrailblazeSdk` (writes to the committed
 * path) and `verifyTrailblazeSdkBundle` (writes to a temp path and byte-diffs). Keeping a
 * single source of truth for the argv is load-bearing: any drift between the two — banner
 * text, footer text, format/target flags — would make the verify check report false drift
 * on a clean bundle, or worse, pass while the regenerated artifact differs.
 */
private fun runEsbuildBundle(sdkDir: File, outputFile: File, logFile: File) {
  val esbuildBin = File(sdkDir, "node_modules/.bin/esbuild")
  if (!esbuildBin.exists()) {
    throw GradleException(
      "esbuild not found at ${esbuildBin.absolutePath}. Run `(cd ${sdkDir.absolutePath} && " +
        "bun install)` (or `npm install`) to pull the TS SDK's devDependencies before " +
        "regenerating the bundle.",
    )
  }
  val entry = File(sdkDir, "src/index.ts")
  outputFile.parentFile.mkdirs()

  // `--banner:js=` emits the string verbatim at the top of the output. Single-line
  // `/*…*/` block so it survives any future esbuild `--banner:` minify pass.
  val bannerJs =
    "/* GENERATED FILE — do not hand-edit. " +
      "Source: sdks/typescript/src/. " +
      "Regenerate with ./gradlew :trailblaze-scripting-bundle:bundleTrailblazeSdk */"
  val argv = listOf(
    esbuildBin.absolutePath,
    entry.absolutePath,
    "--bundle",
    "--platform=neutral",
    "--format=iife",
    "--global-name=trailblazeSdk",
    "--target=es2020",
    "--main-fields=module,main",
    "--external:node:process",
    "--banner:js=$bannerJs",
    "--footer:js=globalThis.trailblaze = trailblazeSdk.trailblaze; globalThis.fromMeta = trailblazeSdk.fromMeta; globalThis.z = trailblazeSdk.z; void 0;",
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
    // Catches Ctrl+C / build cancellation / thrown exceptions between start() and the
    // clean-exit return path. `destroyForcibly` is a no-op if the process already exited.
    if (proc.isAlive) proc.destroyForcibly()
  }
}
