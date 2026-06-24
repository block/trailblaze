import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

/**
 * Configuration for the `trailblaze.sdk-bundle` plugin. `:trailblaze-scripting-bundle` sets
 * these properties to point at the TypeScript SDK source dir and the bundle output path; the
 * plugin then registers `bundleTrailblazeSdk`, which regenerates the slim on-device SDK
 * bundle from source via esbuild.
 */
interface TrailblazeSdkBundleExtension {
  /** Root of the TS SDK package — contains `package.json`, `src/`, `node_modules/.bin/esbuild`. */
  val trailblazeSdkDir: DirectoryProperty

  /**
   * Output path for the generated `trailblaze-sdk-bundle.js` that consumers ship as a resource.
   * A build artifact (gitignored), regenerated each build — not committed source.
   */
  val sdkBundleOutputFile: RegularFileProperty
}

/**
 * Registers `bundleTrailblazeSdk` against the [TrailblazeSdkBundleExtension] paths. The task
 * installs the SDK devDependencies from the committed `bun.lock` ([ensureSdkNodeModules], shared
 * with [TrailblazeSdkDtsBundlePlugin]) and runs esbuild to produce the bundle. Lives in a plugin
 * (rather than inline in `trailblaze-scripting-bundle/build.gradle.kts`) so the bundler argv is a
 * single source of truth shared across the build.
 */
class TrailblazeSdkBundlePlugin : Plugin<Project> {
  override fun apply(project: Project) {
    val ext = project.extensions.create(
      "trailblazeSdkBundle",
      TrailblazeSdkBundleExtension::class.java,
    )

    project.tasks.register("bundleTrailblazeSdk", BundleTrailblazeSdkTask::class.java) { task ->
      task.group = "build"
      task.description =
        "Regenerates the @trailblaze/scripting slim SDK bundle (a gitignored build artifact) " +
          "from sdks/typescript/src/ via esbuild. Wired ahead of resource packaging, so it " +
          "runs as part of any build that ships the bundle; can also be invoked directly."

      task.trailblazeSdkDir.set(ext.trailblazeSdkDir)
      task.sdkBundleOutputFile.set(ext.sdkBundleOutputFile)
      task.projectDir.set(project.layout.projectDirectory)
      task.installLogFile.set(project.layout.buildDirectory.file("tmp/bundle-trailblaze-sdk-install.log"))
      task.bundleLogFile.set(project.layout.buildDirectory.file("tmp/bundle-trailblaze-sdk.log"))
      declareSdkBundleInputs(task, ext, project)
    }

  }
}

abstract class BundleTrailblazeSdkTask : DefaultTask() {
  @get:Internal
  abstract val trailblazeSdkDir: DirectoryProperty

  @get:OutputFile
  abstract val sdkBundleOutputFile: RegularFileProperty

  @get:Internal
  abstract val projectDir: DirectoryProperty

  @get:Internal
  abstract val installLogFile: RegularFileProperty

  @get:Internal
  abstract val bundleLogFile: RegularFileProperty

  @TaskAction
  fun bundle() {
    requireExtensionConfigured(trailblazeSdkDir.isPresent, sdkBundleOutputFile.isPresent)
    val sdkDir = trailblazeSdkDir.get().asFile
    ensureSdkNodeModules(
      sdkDir = sdkDir,
      logFile = installLogFile.get().asFile,
    )
    val outputFile = sdkBundleOutputFile.get().asFile
    logger.lifecycle(
      "Regenerating SDK bundle → ${outputFile.relativeToOrSelf(projectDir.get().asFile)}",
    )
    runEsbuildBundle(
      sdkDir = sdkDir,
      outputFile = outputFile,
      logFile = bundleLogFile.get().asFile,
    )
    logger.lifecycle(
      "Regenerated ${outputFile.relativeToOrSelf(projectDir.get().asFile)} " +
        "(${outputFile.length() / 1024} KiB) [build artifact — not committed].",
    )
  }
}

// Directed-error check for the consumer-facing failure mode where the plugin is applied but
// the extension is never configured. Without this, an unconfigured consumer hits Gradle's
// generic "no value has been specified" snapshot error which doesn't name the extension
// block. Mirrors the pattern `BundleTrailblazeTrailmapTask.generate()` uses for `trailmapsDir`.
private fun requireExtensionConfigured(trailblazeSdkDirPresent: Boolean, sdkBundleOutputFilePresent: Boolean) {
  if (!trailblazeSdkDirPresent || !sdkBundleOutputFilePresent) {
    throw GradleException(
      "trailblaze.sdk-bundle: extension is not configured. Add to your build.gradle.kts:\n" +
        "  trailblazeSdkBundle {\n" +
        "    trailblazeSdkDir.set(layout.projectDirectory.dir(\"...\"))\n" +
        "    sdkBundleOutputFile.set(layout.projectDirectory.file(\"...\"))\n" +
        "  }",
    )
  }
}

// Bundle-source input declarations for `bundleTrailblazeSdk`. These drive Gradle's
// UP-TO-DATE check: when the SDK sources, `package.json`, or lockfile are unchanged the
// generator is skipped and the previously-generated bundle is reused. The canonical (and
// only) lockfile is `bun.lock` — Bun's text-based JSONC format, default since Bun 1.2 (Jan
// 2025) and now floor-pinned via Hermit. Every input is wrapped in
// `project.provider { if isPresent ... }` so the snapshotter tolerates an unconfigured
// extension — the directed-error message from `requireExtensionConfigured` then fires
// from doFirst instead of Gradle's generic "no value has been specified" snapshot
// error.
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
      listOf(dir.file("bun.lock").asFile).filter { it.exists() }
    },
  ).withPropertyName("sdkLockFiles")
}

/**
 * esbuild invocation for `bundleTrailblazeSdk`. The argv (banner text, format/target flags)
 * is kept stable so the generated bundle is byte-reproducible across machines — the property
 * that lets it be a gitignored build artifact rather than committed source.
 */
private fun runEsbuildBundle(sdkDir: File, outputFile: File, logFile: File) {
  val esbuildBin = File(sdkDir, "node_modules/.bin/esbuild")
  if (!esbuildBin.exists()) {
    throw GradleException(
      "esbuild not found at ${esbuildBin.absolutePath}. Run `(cd ${sdkDir.absolutePath} && " +
        "bun install --frozen-lockfile)` to pull the TS SDK's devDependencies before " +
        "regenerating the bundle. Trailblaze is bun-only — activate the repo's Hermit env " +
        "(`source bin/activate-hermit`) or install bun from https://bun.sh/.",
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
