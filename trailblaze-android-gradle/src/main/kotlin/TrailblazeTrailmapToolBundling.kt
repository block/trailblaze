import java.io.File
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileCopyDetails
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.file.RelativePath
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Nested-block spec for `trailblazeAndroid { trailmap { ... } }` — one instance per `trailmap { }`
 * call, consumed immediately by [registerTrailmapToolBundle]. Call it more than once to bundle
 * more than one trailmap; the staging output all lands in the shared
 * [TrailblazeAndroidGradleExtension.stagingRoot].
 */
abstract class TrailmapToolBundleSpec {
  /** The trailmap id, e.g. `"square"`. Keys the on-device asset path (see [assetPathFor]). */
  abstract val id: Property<String>

  /** The trailmap's scripted-tool source directory — the `tools/` dir containing `*.ts` files. */
  abstract val toolsDir: Property<File>
}

/**
 * Pre-compiles a trailmap's in-process scripted tools (`*.ts` under its `tools/` directory) into
 * QuickJS `.bundle.js` files staged into [TrailblazeAndroidGradleExtension.stagingRoot], so an
 * on-device test APK can ship and dispatch them via `AndroidAssetBundleSource`. Moved here from the
 * retired standalone `xyz.block.trailblaze.trailmap-tool-bundles` plugin.
 *
 * The device has no `bun`/esbuild, so — unlike the host/daemon, which bundles live at session
 * start — this MUST happen at build time, or by-name dispatch fails with `Unknown framework tool`.
 */
internal fun registerTrailmapToolBundle(
  project: Project,
  extension: TrailblazeAndroidGradleExtension,
  spec: TrailmapToolBundleSpec,
) {
  val id =
    spec.id.orNull?.takeIf { it.isNotBlank() }
      ?: throw GradleException("trailblazeAndroid.trailmap { }: `id` must be set.")
  val toolsDir =
    spec.toolsDir.orNull
      ?: throw GradleException("trailblazeAndroid.trailmap(\"$id\"): `toolsDir` must be set.")
  if (!toolsDir.isDirectory) {
    throw GradleException(
      "trailblazeAndroid.trailmap(\"$id\"): tools directory not found at " +
        "${toolsDir.absolutePath}. Pass the trailmap's scripted-tool source directory.",
    )
  }
  val sdkRoot: File? = extension.sdkDir.orNull?.asFile ?: project.locateFrameworkSdkRoot()
  val esbuild: File? = sdkRoot?.let { File(it, "node_modules/.bin/esbuild") }
  val sdkSrc: File? = sdkRoot?.let { File(it, "src/in-process.ts") }
  val wrapperTemplate: File? = sdkRoot?.let { File(it, "tools/in-process-wrapper-template.mjs") }
  if (esbuild == null || sdkSrc == null || wrapperTemplate == null) {
    throw GradleException(
      "trailblazeAndroid.trailmap(\"$id\"): could not locate the Trailblaze TypeScript SDK. Set " +
        "`trailblazeAndroid.sdkDir` to the directory containing your @trailblaze/scripting install " +
        "(with `node_modules/.bin/esbuild`, `src/in-process.ts`, and " +
        "`tools/in-process-wrapper-template.mjs`), or place the framework's `sdks/typescript/` " +
        "tree at or above `${project.rootProject.projectDir}` so the default walk-up can find it.",
    )
  }

  inProcessToolSources(toolsDir).forEach { tsFile ->
    // Tools-relative path (e.g. `client/launchClientRoute.ts`) drives the esbuild entry point and,
    // sans `.ts`, the staged asset subpath — a flat basename would collide across subfolders.
    val relPath = tsFile.relativeTo(toolsDir).invariantSeparatorsPath
    val relStem = relPath.removeSuffix(".ts")
    val toolName = tsFile.name.removeSuffix(".ts")
    // Filesystem/task-name-safe unique key (subpath flattened) so same-basename tools don't collide.
    val key = relStem.replace(Regex("[^A-Za-z0-9]"), "_")
    val capId = id.replaceFirstChar { it.uppercase() }
    val capKey = key.replaceFirstChar { it.uppercase() }

    val bundleTask =
      project.tasks.register(
        "bundleTrailmap$capId${capKey}ToolBundle",
        BundleAuthorToolsTask::class.java,
      ) { task ->
        task.group = "trailblaze"
        task.description =
          "Bundles the `$id` trailmap scripted tool `$relStem` (TypeScript → QuickJS bundle)."
        task.bundleName.set("$id-$toolName")
        task.sourceDir.set(project.layout.projectDirectory.dir(toolsDir.absolutePath))
        task.entryPoint.set(relPath)
        task.outputFile.set(
          project.layout.buildDirectory.file(
            "intermediates/trailblaze/trailmap-tool-bundles/$id/$key.bundle.js",
          ),
        )
        task.esbuildBinary.set(project.layout.projectDirectory.file(esbuild.absolutePath))
        task.scriptingSdkSrc.set(project.layout.projectDirectory.file(sdkSrc.absolutePath))
        task.scriptingWrapperTemplate.set(
          project.layout.projectDirectory.file(wrapperTemplate.absolutePath),
        )
        task.projectDir.set(project.layout.projectDirectory)
        task.logFile.set(
          project.layout.buildDirectory.file("tmp/bundle-trailmap-tool-$id-$key.log"),
        )
        // Snapshot only the `.ts` sources for the up-to-date check (no node_modules/ walk) —
        // esbuild --bundle inlines any sibling helper modules, so the whole tools dir is the
        // change-detection surface.
        task.inputSources.from(
          project.layout.projectDirectory.dir(toolsDir.absolutePath).asFileTree.matching {
            it.include("**/*.ts")
            it.exclude("**/.trailblaze-wrapper-*")
          },
        )
        // External consumers manage their own SDK install lifecycle and leave this unset.
        extension.sdkInstallTaskPath.orNull?.let { task.dependsOn(it) }
      }

    // Pull the bundle task's outputs as a Provider so the task dependency flows through the
    // Provider chain, then rewrite each file's relative path to the on-device asset path.
    val assetPath = assetPathFor(id, relStem)
    val stageTask =
      project.tasks.register("stageTrailmap$capId${capKey}ToolBundleAsset", Copy::class.java) {
        task ->
        task.group = "trailblaze"
        task.description =
          "Stages the `$id` trailmap `$relStem` QuickJS bundle into the test APK asset tree."
        task.from(bundleTask.map { it.outputs.files }) { copySpec ->
          copySpec.eachFile { fcd: FileCopyDetails ->
            fcd.relativePath = RelativePath.parse(true, assetPath)
          }
        }
        task.into(extension.stagingRoot)
      }
    extension.allStagingTasks.add(stageTask)
  }

  registerTrailmapToolDefs(project, extension, id, toolsDir, sdkRoot)
}

/**
 * Stages a `<tool>.tooldefs.json` beside the bundle of each in-process tool with no descriptor YAML
 * (see [ScriptedToolDefsExtraction]), so the device and the host jar can discover it by name — a
 * toolset can then deliver it — with the `.ts` as its only declaration.
 *
 * Extraction is skipped when the SDK at [sdkRoot] has no analyzer shim: an external SDK install may
 * predate it, and such a consumer's descriptor-less tools stay reachable through `target.tools:` as
 * before. The stage task is registered regardless, because it also removes this trailmap's stale
 * files from the shared staging root — a deleted tool's, or one that has since gained a YAML — and
 * a trailmap whose last descriptor-less tool just went still has those to remove.
 */
private fun registerTrailmapToolDefs(
  project: Project,
  extension: TrailblazeAndroidGradleExtension,
  id: String,
  toolsDir: File,
  sdkRoot: File,
) {
  val descriptorless = inProcessToolSources(toolsDir).filter { ScriptedToolDefsExtraction.isDescriptorless(it) }
  val extractor = File(sdkRoot, "tools/extract-tool-defs.mjs")
  val capId = id.replaceFirstChar { it.uppercase() }
  val extractTask =
    if (descriptorless.isEmpty() || !extractor.isFile) {
      null
    } else {
      project.tasks.register("extractTrailmap${capId}ToolDefs", ExtractTrailmapToolDefsTask::class.java) { task ->
        task.group = "trailblaze"
        task.description = "Reads the `$id` trailmap's descriptor-less scripted tools into `.tooldefs.json` files."
        task.toolsDir.set(toolsDir)
        task.toolSources.set(descriptorless.map { it.relativeTo(toolsDir).invariantSeparatorsPath })
        // Every `.ts` and descriptor in the dir: a tool's schema can come from a sibling type module,
        // and a descriptor added later takes a tool off this list.
        task.inputSources.from(
          project.fileTree(toolsDir) {
            it.include("**/*.ts", "**/*.yaml")
            it.exclude("**/.trailblaze-wrapper-*")
          },
        )
        task.extractor.set(extractor)
        task.sdkDir.set(sdkRoot)
        task.analyzerDependencies.from(analyzerDependencyFiles(sdkRoot))
        task.outputDir.set(project.layout.buildDirectory.dir("intermediates/trailblaze/trailmap-tool-defs/$id"))
        task.logFile.set(project.layout.buildDirectory.file("tmp/extract-trailmap-tool-defs-$id.log"))
        extension.sdkInstallTaskPath.orNull?.let { task.dependsOn(it) }
      }
    }
  val stagedToolsDir = extension.stagingRoot.map { it.dir("trails/config/trailmaps/$id/tools") }
  val sweepTask =
    project.tasks.register("sweepTrailmap${capId}ToolDefs", SweepStagedToolDefsTask::class.java) { task ->
      task.group = "trailblaze"
      task.description = "Removes the `$id` trailmap's staged `.tooldefs.json` files before restaging."
      task.stagedToolsDir.set(stagedToolsDir)
    }
  val stageTask =
    project.tasks.register("stageTrailmap${capId}ToolDefs", Copy::class.java) { task ->
      task.group = "trailblaze"
      task.description = "Stages the `$id` trailmap's `.tooldefs.json` files beside its bundles."
      // A dependency, not a `doFirst`: with nothing to copy this task is skipped as NO-SOURCE, and
      // the sweep must still run then.
      task.dependsOn(sweepTask)
      extractTask?.let { extract ->
        task.from(extract.flatMap { it.outputDir }) { it.into("trails/config/trailmaps/$id/tools") }
      }
      task.into(extension.stagingRoot)
    }
  extension.allStagingTasks.add(stageTask)
}

/**
 * Deletes one trailmap's `.tooldefs.json` files from the shared staging root. Not a `Sync` on the
 * stage task: the root also holds every other task's bundles, so only these files may go.
 */
abstract class SweepStagedToolDefsTask : DefaultTask() {
  /**
   * `<stagingRoot>/trails/config/trailmaps/<id>/tools`. Declared as an output although the stage
   * tasks share it: Gradle never runs tasks with overlapping outputs at the same time, and without
   * that this task deletes files while a stage task is fingerprinting the root.
   */
  @get:OutputDirectory abstract val stagedToolsDir: DirectoryProperty

  init {
    // With overlapping outputs Gradle tracks only the files a task wrote, and this one writes none,
    // so it would otherwise be up-to-date forever after its first run. Deleting a few files is cheap.
    outputs.upToDateWhen { false }
  }

  @TaskAction
  fun sweep() {
    stagedToolsDir.get().asFile.walkTopDown()
      .filter { it.isFile && it.name.endsWith(ScriptedToolDefsExtraction.SUFFIX) }
      .forEach { it.delete() }
  }
}

/** Runs [ScriptedToolDefsExtraction] over one trailmap's descriptor-less tools. */
abstract class ExtractTrailmapToolDefsTask : DefaultTask() {
  @get:Internal abstract val toolsDir: DirectoryProperty

  /** Tools-relative paths of the `.ts` files to read. */
  @get:Input abstract val toolSources: ListProperty<String>

  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val inputSources: ConfigurableFileCollection

  @get:InputFile
  @get:PathSensitive(PathSensitivity.NONE)
  abstract val extractor: RegularFileProperty

  @get:Internal abstract val sdkDir: DirectoryProperty

  /**
   * The SDK's lockfile and the installed analyzer packages' manifests: the extractor imports
   * `typescript` and `ts-json-schema-generator` from [sdkDir], so a version change there can change
   * the output with no tool edited. Missing files are fine; the extractor then fails on its own.
   */
  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val analyzerDependencies: ConfigurableFileCollection

  @get:OutputDirectory abstract val outputDir: DirectoryProperty

  @get:Internal abstract val logFile: RegularFileProperty

  @TaskAction
  fun extract() {
    val root = toolsDir.get().asFile
    val out = outputDir.get().asFile
    out.deleteRecursively()
    ScriptedToolDefsExtraction.extract(
      extractor = extractor.get().asFile,
      sdkDir = sdkDir.get().asFile,
      sources = toolSources.get().map { File(root, it) },
      logFile = logFile.get().asFile,
    ).forEach { (src, json) ->
      File(out, ScriptedToolDefsExtraction.fileNameFor(src.relativeTo(root).invariantSeparatorsPath))
        .apply { parentFile.mkdirs() }
        .writeText(json)
    }
  }
}

/** Files whose change can change what the analyzer extracts, besides the tools themselves. */
internal fun analyzerDependencyFiles(sdkRoot: File): List<File> =
  listOf(
    "package.json",
    "bun.lock",
    "node_modules/typescript/package.json",
    "node_modules/ts-json-schema-generator/package.json",
  ).map { File(sdkRoot, it) }

/** Asset-tree-relative path for a tool's bundle — kept in lockstep with the runtime resolver. */
internal fun assetPathFor(trailmapId: String, toolsRelativeStem: String): String =
  "trails/config/trailmaps/$trailmapId/tools/$toolsRelativeStem.bundle.js"

/**
 * Returns the in-process scripted-tool `.ts` files in [toolsDir] — a `<name>.ts` qualifies when
 * either a sibling `<name>.yaml`'s runtime isn't `subprocess`, or (descriptor-less) the `.ts`
 * declares the tool inline via `trailblaze.tool<…>(…)`. Excludes `.test.ts`, `.d.ts`, and helper
 * modules that never call `trailblaze.tool`. Sorted for deterministic task registration order.
 *
 * A descriptor-less `.ts` is ALWAYS in-process here. The inline spec can set `runtime`, but reading
 * it needs the analyzer, so a subprocess tool also declares it in a YAML sidecar's
 * `runtime: subprocess` — caught by the sibling-yaml branch. Discovery must not try
 * to infer subprocess-ness from `.ts` text: `runtime: subprocess` only ever appears there in a
 * comment or an error-message string (e.g. a tool's doc comment documents that it does NOT pin it),
 * and grepping for it dropped that tool's on-device bundle — it stayed advertised in the target
 * config but had no `.bundle.js` in the APK, so dispatch failed with "Unknown tool …
 * not registered".
 */
internal fun inProcessToolSources(toolsDir: File): List<File> {
  val subprocessRuntimeYaml = Regex("(?m)^\\s*runtime:\\s*subprocess\\s*$")
  val toolExport = Regex("""trailblaze\s*\.\s*tool\s*[<(]""")
  if (!toolsDir.isDirectory) return emptyList()
  // Recursive so `tools/<subdir>/`-organized tools are bundled too, preserving subpath to match
  // the on-device resolver (ScriptedToolNameDiscoverer.bundleResourcePathForScript).
  return toolsDir
    .walkTopDown()
    .filter { f ->
      f.isFile &&
        f.name.endsWith(".ts") &&
        !f.name.endsWith(".test.ts") &&
        !f.name.endsWith(".d.ts") &&
        // Sibling descriptor lives in the SAME directory as the `.ts` (root or organizational subdir).
        File(f.parentFile, f.name.removeSuffix(".ts") + ".yaml").let { yaml ->
          if (yaml.isFile) {
            !subprocessRuntimeYaml.containsMatchIn(yaml.readText())
          } else {
            toolExport.containsMatchIn(f.readText())
          }
        }
    }
    .sortedBy { it.relativeTo(toolsDir).invariantSeparatorsPath }
    .toList()
}
