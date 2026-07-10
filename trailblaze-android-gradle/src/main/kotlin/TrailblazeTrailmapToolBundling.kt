import java.io.File
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.file.FileCopyDetails
import org.gradle.api.file.RelativePath
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Copy

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
}

/** Asset-tree-relative path for a tool's bundle — kept in lockstep with the runtime resolver. */
internal fun assetPathFor(trailmapId: String, toolsRelativeStem: String): String =
  "trails/config/trailmaps/$trailmapId/tools/$toolsRelativeStem.bundle.js"

/**
 * Returns the in-process scripted-tool `.ts` files in [toolsDir] — a `<name>.ts` qualifies when
 * either a sibling `<name>.yaml`'s runtime isn't `subprocess`, or (descriptor-less) the `.ts`
 * declares the tool inline via `trailblaze.tool<…>(…)`. Excludes `.test.ts`, `.d.ts`, and helper
 * modules that never call `trailblaze.tool`. Sorted for deterministic task registration order.
 *
 * A descriptor-less `.ts` is ALWAYS in-process: the inline `trailblaze.tool(...)` spec
 * (`TrailblazeTypedToolSpec`) has no `runtime` field, so subprocess tools are declared solely via a
 * YAML sidecar's `runtime: subprocess` — caught by the sibling-yaml branch. Discovery must not try
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
