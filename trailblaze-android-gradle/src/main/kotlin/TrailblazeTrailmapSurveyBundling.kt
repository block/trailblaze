import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/**
 * Packages a trailmap's surveys (`*.survey.ts` under its `surveys/`) into the test APK, so a tool
 * calling `ctx.surveys.<name>()` on a device finds them. The device has no esbuild, so each file is
 * bundled here, the way the daemon bundles it on first use.
 *
 * Asset layout, under `trailblaze/surveys/`: the survey file at `trailmaps/<id>/surveys/<file>`, and
 * beside it `<file>.bundle.js`, or `<file>.bundle-error.txt` when it could not be bundled. A file that
 * cannot be bundled does not fail the build — a survey that runs only after the session may import
 * what the sandbox lacks — and the device reports the error only to a trail that asks for one of its
 * surveys. SISTER-IMPL-TAG: packaged-survey-layout — `SurveyBundles.PACKAGED_DIR` reads it.
 *
 * Skipped, staging nothing new, when the SDK predates mid-trail surveys (no `src/surveys/in-process.ts`).
 */
internal fun registerTrailmapSurveyBundles(
  project: Project,
  extension: TrailblazeAndroidGradleExtension,
  id: String,
  surveysDir: File,
  sdkRoot: File,
  esbuild: File,
) {
  val capId = id.replaceFirstChar { it.uppercase() }
  val sdkEntry = File(sdkRoot, "src/in-process.ts")
  val bundleTask =
    if (surveyFiles(surveysDir).isEmpty() || !File(sdkRoot, "src/surveys/in-process.ts").isFile) {
      null
    } else {
      project.tasks.register("bundleTrailmap${capId}Surveys", BundleTrailmapSurveysTask::class.java) { task ->
        task.group = "trailblaze"
        task.description = "Bundles the `$id` trailmap's surveys so ctx.surveys finds them on a device."
        task.trailmapId.set(id)
        task.surveysDir.set(surveysDir)
        // The whole directory: a survey file imports its siblings.
        task.inputSources.from(
          project.fileTree(surveysDir) {
            it.include("**/*.ts", "**/*.mts", "**/*.js", "**/*.mjs", "**/*.json")
            it.exclude("**/node_modules/**", "**/.*/**")
          },
        )
        task.esbuildBinary.set(esbuild)
        task.scriptingSdkSrc.set(sdkEntry)
        task.outputDir.set(project.layout.buildDirectory.dir("intermediates/trailblaze/trailmap-survey-bundles/$id"))
        extension.sdkInstallTaskPath.orNull?.let { task.dependsOn(it) }
      }
    }
  val stagedDir = extension.stagingRoot.map { it.dir("$PACKAGED_SURVEYS_DIR/trailmaps/$id") }
  // One per module: drops the surveys of a trailmap whose block was removed or renamed since the last
  // build, which the device would otherwise still search (and find a duplicate export in).
  val pruneTask =
    if (PRUNE_SURVEYS_TASK in project.tasks.names) {
      project.tasks.named(PRUNE_SURVEYS_TASK, PruneUnconfiguredSurveysTask::class.java)
    } else {
      project.tasks.register(PRUNE_SURVEYS_TASK, PruneUnconfiguredSurveysTask::class.java) { task ->
        task.group = "trailblaze"
        task.description = "Removes staged surveys of trailmaps no longer configured."
        task.trailmapsDir.set(extension.stagingRoot.map { it.dir("$PACKAGED_SURVEYS_DIR/trailmaps") })
      }
    }
  pruneTask.configure { it.configuredIds.add(id) }
  val sweepTask =
    project.tasks.register("sweepTrailmap${capId}Surveys", SweepStagedSurveysTask::class.java) { task ->
      task.group = "trailblaze"
      task.description = "Removes the `$id` trailmap's staged surveys before restaging."
      task.stagedDir.set(stagedDir)
      task.dependsOn(pruneTask)
    }
  val stageTask =
    project.tasks.register("stageTrailmap${capId}Surveys", Copy::class.java) { task ->
      task.group = "trailblaze"
      task.description = "Stages the `$id` trailmap's surveys into the test APK asset tree."
      // A dependency, not a `doFirst`: with nothing to copy this task is NO-SOURCE, and the sweep
      // must still run then.
      task.dependsOn(sweepTask)
      bundleTask?.let { bundle -> task.from(bundle.flatMap { it.outputDir }) { it.into(PACKAGED_SURVEYS_DIR) } }
      task.into(extension.stagingRoot)
    }
  extension.allStagingTasks.add(stageTask)
}

private const val PRUNE_SURVEYS_TASK = "pruneUnconfiguredTrailmapSurveys"

/** Mirrors `SurveyBundles.PACKAGED_DIR` and its two suffixes. */
internal const val PACKAGED_SURVEYS_DIR = "trailblaze/surveys"
internal const val PACKAGED_SURVEY_BUNDLE_SUFFIX = ".bundle.js"
internal const val PACKAGED_SURVEY_ERROR_SUFFIX = ".bundle-error.txt"

/** Mirrors `SurveyBundles.FILE_SUFFIXES`. */
private val SURVEY_FILE_SUFFIXES = listOf(".survey.ts", ".survey.mts", ".survey.js", ".survey.mjs")

/** Every survey file under [surveysDir], in path order, as the daemon finds them. */
internal fun surveyFiles(surveysDir: File): List<File> {
  if (!surveysDir.isDirectory) return emptyList()
  return surveysDir.walkTopDown()
    .onEnter { it == surveysDir || !(it.name.startsWith(".") || it.name == "node_modules") }
    .filter { f -> f.isFile && SURVEY_FILE_SUFFIXES.any { f.name.endsWith(it) } }
    .sortedBy { it.relativeTo(surveysDir).invariantSeparatorsPath }
    .toList()
}

/**
 * Whether every survey [text] exports sets `requiresFinishedSession: true`. A textual approximation
 * of `SurveyBundles.exportedSurveys`, which this composite build cannot depend on; it only decides
 * whether a failed bundle is worth reporting, so a wrong answer costs a message, never a survey.
 */
internal fun onlyFinishedSessionSurveys(text: String): Boolean {
  val code = text.replace(Regex("""/\*[\s\S]*?\*/"""), "").replace(Regex("""(?m)^\s*//.*$"""), "")
  val exports = EXPORTED_SURVEY.findAll(code).count()
  return exports > 0 && FINISHED_SESSION_TRUE.findAll(code).count() >= exports
}

private val EXPORTED_SURVEY =
  Regex("""export\s+const\s+[A-Za-z_$][\w$]*\s*(?::[^=]+)?=\s*(?:trailblaze\s*\.\s*)?survey\s*\(""")
private val FINISHED_SESSION_TRUE = Regex("""(?<![\w$])(["']?)requiresFinishedSession\1\s*:\s*true\b""")

/**
 * The entry esbuild bundles for one survey file. SISTER-IMPL-TAG: survey-bundle-entry — the same
 * three lines as `SurveyBundles.entrySource`, which the daemon bundles from.
 */
internal fun surveyBundleEntrySource(importPath: String): String = buildString {
  appendLine("// Synthetic entry: one survey file, built for the scripted-tool sandbox.")
  appendLine("import * as surveys from ${jsString(importPath)};")
  appendLine("import { runNamedLiveSurvey } from \"@trailblaze/scripting/surveys\";")
  appendLine("export const runLive = (name, events, options) => runNamedLiveSurvey(surveys, name, events, options);")
}

private fun jsString(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

/**
 * The esbuild command line for one survey bundle: the author-tool flags plus what makes it a survey
 * bundle. Lockstep with `DaemonScriptedToolBundler.runEsbuild(survey = true)`: an IIFE assigning
 * `__trailblazeSurveyBundle`, `@trailblaze/scripting/surveys` aliased to the sandbox entry, and no
 * `node:process` external, since the sandbox has no `require`.
 */
internal fun surveyEsbuildArgv(
  esbuild: File,
  entryFile: File,
  scriptingSdkEntry: File,
  stdioStubFile: File,
  output: File,
): List<String> {
  val sdkSrc = scriptingSdkEntry.parentFile
  val matcherEntry = File(sdkSrc, "matcher/index.ts")
  return listOfNotNull(
    esbuild.absolutePath,
    entryFile.absolutePath,
    "--bundle",
    "--platform=neutral",
    "--format=iife",
    "--global-name=__trailblazeSurveyBundle",
    "--target=es2020",
    "--main-fields=module,main",
    "--alias:@trailblaze/scripting/matcher=${matcherEntry.absolutePath}".takeIf { matcherEntry.isFile },
    "--alias:@trailblaze/scripting/surveys=${File(sdkSrc, "surveys/in-process.ts").absolutePath}",
    "--alias:@trailblaze/scripting=${scriptingSdkEntry.absolutePath}",
    "--alias:@modelcontextprotocol/sdk/server/stdio.js=${stdioStubFile.absolutePath}",
    "--outfile=${output.absolutePath}",
  )
}

/** Bundles every survey file of one trailmap into [outputDir], laid out as the APK packages it. */
@DisableCachingByDefault(because = "Runs a locally installed esbuild that is not fully tracked as an input")
abstract class BundleTrailmapSurveysTask @Inject constructor(private val objects: ObjectFactory) : DefaultTask() {
  init {
    // A survey can import a helper outside its surveys/ (another trailmap's, say), which no input
    // here tracks, so it rebundles every build rather than package stale logic.
    outputs.upToDateWhen { false }
  }

  @get:Input abstract val trailmapId: Property<String>

  @get:Internal abstract val surveysDir: DirectoryProperty

  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val inputSources: ConfigurableFileCollection

  @get:InputFile
  @get:PathSensitive(PathSensitivity.NONE)
  abstract val esbuildBinary: RegularFileProperty

  @get:InputFile
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val scriptingSdkSrc: RegularFileProperty

  /** Every SDK source a bundle can inline: the directory holding [scriptingSdkSrc]. */
  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  val scriptingSdkSources: FileCollection
    get() = scriptingSdkSrc.orNull?.asFile?.parentFile?.let { objects.fileTree().from(it) } ?: objects.fileCollection()

  /** Holds `trailmaps/<id>/surveys/…`, the path under `trailblaze/surveys/` each file is packaged at. */
  @get:OutputDirectory abstract val outputDir: DirectoryProperty

  @TaskAction
  fun bundle() {
    val root = surveysDir.get().asFile
    val out = outputDir.get().asFile.apply { deleteRecursively() }
    val work = temporaryDir.apply { deleteRecursively(); mkdirs() }
    val stdioStub = File(work, "_ondevice-stdio-stub.ts").apply {
      writeText("export class StdioServerTransport { constructor() { throw new Error(\"StdioServerTransport unavailable on-device\"); } }\n")
    }
    surveyFiles(root).forEachIndexed { index, survey ->
      val packaged = File(out, "trailmaps/${trailmapId.get()}/surveys/${survey.relativeTo(root).invariantSeparatorsPath}")
      packaged.parentFile.mkdirs()
      survey.copyTo(packaged, overwrite = true)
      val entry = File(work, "entry-$index.ts").apply { writeText(surveyBundleEntrySource(survey.absolutePath)) }
      val bundle = File(packaged.path + PACKAGED_SURVEY_BUNDLE_SUFFIX)
      val error = runEsbuild(surveyEsbuildArgv(esbuildBinary.get().asFile, entry, scriptingSdkSrc.get().asFile, stdioStub, bundle), work)
      if (error != null) {
        bundle.delete()
        // Expected for a file of surveys that run only after the session, which the device answers
        // without a bundle; `make-test-apk` does not bundle such a file at all.
        if (onlyFinishedSessionSurveys(survey.readText())) return@forEachIndexed
        File(packaged.path + PACKAGED_SURVEY_ERROR_SUFFIX).writeText(error)
        logger.lifecycle(
          "Survey file ${trailmapId.get()}/surveys/${survey.relativeTo(root).invariantSeparatorsPath} cannot run mid-trail on a device " +
            "(esbuild failed); ctx.surveys reports why to a trail that asks for one of its surveys.",
        )
      }
    }
  }

  /** Null on success, else what esbuild printed. */
  private fun runEsbuild(argv: List<String>, cwd: File): String? {
    val proc = ProcessBuilder(argv).directory(cwd).redirectErrorStream(true).start()
    val output = StringBuilder()
    val reader = Thread { output.append(proc.inputStream.bufferedReader().readText()) }.apply { start() }
    if (!proc.waitFor(2, TimeUnit.MINUTES)) {
      proc.destroyForcibly()
      proc.waitFor(10, TimeUnit.SECONDS)
      reader.join(1_000)
      return "esbuild did not finish within 2 minutes.\n$output"
    }
    reader.join()
    val bundle = File(argv.last().removePrefix("--outfile="))
    return when {
      proc.exitValue() != 0 -> "esbuild failed (exit ${proc.exitValue()}):\n$output".take(MAX_ERROR_CHARS)
      !bundle.isFile || bundle.length() == 0L -> "esbuild reported success but wrote no bundle.\n$output".take(MAX_ERROR_CHARS)
      else -> null
    }
  }

  private companion object {
    const val MAX_ERROR_CHARS = 8_000
  }
}

/** Deletes one trailmap's staged surveys from the shared staging root before they are restaged. */
@DisableCachingByDefault(because = "Deletes stale files from a staging directory shared with other tasks")
abstract class SweepStagedSurveysTask : DefaultTask() {
  /** Declared as an output for the reason [SweepStagedToolDefsTask.stagedToolsDir] is. */
  @get:OutputDirectory abstract val stagedDir: DirectoryProperty

  init {
    outputs.upToDateWhen { false }
  }

  @TaskAction
  fun sweep() {
    stagedDir.get().asFile.listFiles()?.forEach { it.deleteRecursively() }
  }
}

/** Deletes the staged surveys of every trailmap id not in [configuredIds]. */
@DisableCachingByDefault(because = "Deletes stale files from a staging directory shared with other tasks")
abstract class PruneUnconfiguredSurveysTask : DefaultTask() {
  @get:Input abstract val configuredIds: SetProperty<String>

  /** Not an output: each trailmap's own staging task owns its subdirectory. */
  @get:Internal abstract val trailmapsDir: DirectoryProperty

  @TaskAction
  fun prune() {
    val keep = configuredIds.get()
    trailmapsDir.get().asFile.listFiles()?.filter { it.name !in keep }?.forEach { it.deleteRecursively() }
  }
}
