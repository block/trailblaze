import java.io.File
import javax.inject.Inject
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.file.FileCopyDetails
import org.gradle.api.file.RelativePath
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.TaskProvider

/**
 * Pre-compiles a trailmap's in-process scripted tools (the `*.ts` under a trailmap's `tools/`
 * directory) into QuickJS `.bundle.js` files and stages them into a single asset tree, so an
 * on-device Android test APK can ship them and the on-device launcher can read each via
 * `AndroidAssetBundleSource`.
 *
 * ### Why this exists
 *
 * A target's `target.tools:` scripted tools run in-process on the device through QuickJS, and a
 * Kotlin launch orchestrator composes them by name (the same registry + nested-executor path a
 * TypeScript `ctx.tools.<name>(args)` call uses). The device APK has no `bun`/esbuild, so — unlike
 * the host/daemon, which bundles them live at session start — the bundle MUST be produced at build
 * time and packaged as an asset, or the by-name dispatch fails with `Unknown framework tool` on the
 * device farm.
 *
 * This is the trailmap-scoped analog of `:trailblaze-common`'s `bundleFrameworkScriptedTools` (which
 * bundles the framework's own scripted tools) and reuses the same [BundleAuthorToolsTask] bundler —
 * identical esbuild flags + wrapper template — so a bundle produced here behaves identically on
 * device to a framework one.
 *
 * ### Usage
 *
 * The consumer points the plugin at each trailmap's tool-source directory (it lives wherever that
 * consuming module's trailmaps are authored — the plugin stays agnostic of the layout). The asset
 * path is keyed off the trailmap [id] so it matches what the on-device launcher resolves.
 *
 * ```kotlin
 * plugins {
 *   alias(libs.plugins.android.library)
 *   alias(libs.plugins.kotlin.android)
 *   id("trailblaze.trailmap-tool-bundles")
 * }
 *
 * trailblazeTrailmapToolBundles.trailmap(
 *   id = "myapp",
 *   toolsDir = rootProject.file("path/to/trailmaps/myapp/tools"),
 * )
 *
 * // Ship the staged bundles in the test APK + close AGP's implicit-dependency gap.
 * android {
 *   sourceSets.getByName("androidTest").assets.srcDir(trailblazeTrailmapToolBundles.stagingRoot)
 * }
 * tasks.matching { it.name.endsWith("AndroidTestAssets") }
 *   .configureEach { dependsOn(trailblazeTrailmapToolBundles.allStagingTasks) }
 * ```
 *
 * Mirroring [TrailblazeQuickjsBundleAssetsPlugin], the plugin owns bundling + staging but stays
 * AGP-unaware: the consumer wires `assets.srcDirs(...)` and the asset-task `dependsOn(...)` because
 * those couple to AGP versions the build-logic classpath deliberately doesn't carry.
 */
class TrailblazeTrailmapToolBundlesPlugin : Plugin<Project> {
  override fun apply(project: Project) {
    // Single staging root every bundle copies into a sub-path of, laid out as
    // `trails/config/trailmaps/<id>/tools/<name>.bundle.js` — the exact asset path the on-device
    // launcher (`ScriptedToolNameDiscoverer.bundleResourcePathForScript`) resolves.
    val stagingRoot: Provider<Directory> =
      project.layout.buildDirectory.dir("intermediates/trailblaze/trailmap-tool-bundle-assets")
    val stagingTasks: MutableList<TaskProvider<Copy>> = mutableListOf()

    project.extensions.create(
      "trailblazeTrailmapToolBundles",
      TrailblazeTrailmapToolBundlesExtension::class.java,
      project,
      stagingRoot,
      stagingTasks,
    )
  }

  companion object {
    /**
     * The SDK-install task that populates `sdks/typescript/node_modules/.bin/esbuild`. Shared with
     * `:trailblaze-quickjs-tools` so we don't fork a second install task at the same `node_modules/`.
     */
    const val SDK_INSTALL_TASK_PATH = ":trailblaze-scripting-subprocess:installTrailblazeScriptingSdk"

    /**
     * Asset-tree-relative path for a tool's bundle — kept in lockstep with the runtime resolver
     * (`ScriptedToolNameDiscoverer.bundleResourcePathForScript`). `trailmaps` literal rather than the
     * `TrailblazeConfigPaths.TRAILMAPS_DIR` constant because build-logic doesn't carry
     * `:trailblaze-models` on its classpath.
     */
    fun assetPathFor(trailmapId: String, toolName: String): String =
      "trails/config/trailmaps/$trailmapId/tools/$toolName.bundle.js"

    /**
     * Returns the in-process scripted-tool entry `.ts` files in [toolsDir]: a `<name>.ts` that has a
     * sibling `<name>.yaml` descriptor whose runtime isn't `subprocess`. This excludes `.test.ts`
     * fixtures and shared helper modules (which have no descriptor), and subprocess tools (which
     * can't run on-device — those need the full Node API surface). Sorted for deterministic task
     * registration order.
     */
    fun inProcessToolSources(toolsDir: File): List<File> {
      val subprocessRuntime = Regex("(?m)^\\s*runtime:\\s*subprocess\\s*$")
      return (toolsDir.listFiles() ?: emptyArray())
        .filter { f ->
          f.isFile &&
            f.name.endsWith(".ts") &&
            !f.name.endsWith(".test.ts") &&
            !f.name.endsWith(".d.ts") &&
            File(toolsDir, f.name.removeSuffix(".ts") + ".yaml").let { yaml ->
              yaml.isFile && !subprocessRuntime.containsMatchIn(yaml.readText())
            }
        }
        .sortedBy { it.name }
    }
  }
}

/**
 * DSL container exposed by the plugin. Declare each trailmap via [trailmap]; wire [stagingRoot] and
 * [allStagingTasks] into AGP from the consuming build script.
 */
abstract class TrailblazeTrailmapToolBundlesExtension @Inject constructor(
  private val project: Project,
  /** Aggregating staging root — point AGP's `assets.srcDirs(...)` at this. */
  val stagingRoot: Provider<Directory>,
  /** The staging Copy tasks, for the consumer's AGP-asset-task `dependsOn(...)` wiring. */
  val allStagingTasks: MutableList<TaskProvider<Copy>>,
) {

  /**
   * Bundle + stage every in-process scripted tool found in [toolsDir] under the trailmap [id].
   * Enumerates the tool sources at configuration time (they're committed source, always present),
   * registering one bundle task + one staging task per tool. The produced bundles land in
   * [stagingRoot] at `trails/config/trailmaps/<id>/tools/<name>.bundle.js`.
   */
  fun trailmap(id: String, toolsDir: File) {
    if (!toolsDir.isDirectory) {
      throw GradleException(
        "trailblazeTrailmapToolBundles.trailmap(\"$id\"): tools directory not found at " +
          "${toolsDir.absolutePath}. Pass the trailmap's scripted-tool source directory.",
      )
    }
    val esbuild = project.defaultEsbuildBinary()
    val sdkSrc = project.defaultScriptingSdkSrc()
    val wrapperTemplate = project.defaultScriptingWrapperTemplate()
    if (esbuild == null || sdkSrc == null || wrapperTemplate == null) {
      throw GradleException(
        "trailblazeTrailmapToolBundles.trailmap(\"$id\"): could not locate the Trailblaze " +
          "TypeScript SDK (sdks/typescript) from ${project.rootProject.projectDir}. esbuild / slim " +
          "SDK entry / wrapper template are required to bundle scripted tools.",
      )
    }

    TrailblazeTrailmapToolBundlesPlugin.inProcessToolSources(toolsDir).forEach { tsFile ->
      val toolName = tsFile.name.removeSuffix(".ts")
      val capId = id.replaceFirstChar { it.uppercase() }
      val capTool = toolName.replaceFirstChar { it.uppercase() }

      val bundleTask =
        project.tasks.register(
          "bundleTrailmap$capId${capTool}ToolBundle",
          BundleAuthorToolsTask::class.java,
        ) { task ->
          task.group = "trailblaze"
          task.description =
            "Bundles the `$id` trailmap scripted tool `$toolName` (TypeScript → QuickJS bundle)."
          task.bundleName.set("$id-$toolName")
          task.sourceDir.set(project.layout.projectDirectory.dir(toolsDir.absolutePath))
          task.entryPoint.set(tsFile.name)
          task.outputFile.set(
            project.layout.buildDirectory.file(
              "intermediates/trailblaze/trailmap-tool-bundles/$id/$toolName.bundle.js",
            ),
          )
          task.esbuildBinary.set(project.layout.projectDirectory.file(esbuild.absolutePath))
          task.scriptingSdkSrc.set(project.layout.projectDirectory.file(sdkSrc.absolutePath))
          task.scriptingWrapperTemplate.set(
            project.layout.projectDirectory.file(wrapperTemplate.absolutePath),
          )
          // Snapshot only the author-managed `.ts` sources for the up-to-date check (no
          // node_modules/ walk). esbuild --bundle inlines any sibling helper modules a tool
          // imports, so the whole tools dir's `.ts` is the change-detection surface.
          task.inputSources.from(
            project.layout.projectDirectory.dir(toolsDir.absolutePath).asFileTree.matching {
              it.include("**/*.ts")
            },
          )
          // esbuild lives in the SDK's node_modules; install it first.
          task.dependsOn(TrailblazeTrailmapToolBundlesPlugin.SDK_INSTALL_TASK_PATH)
        }

      // Stage the produced bundle into the aggregated asset root at the exact path the on-device
      // launcher reads. Pull the bundle task's outputs as a Provider so the task dependency flows
      // through the Provider chain (no explicit dependsOn needed), then rewrite each file's relative
      // path to the asset path — the same flatten-via-relativePath pattern
      // [TrailblazeQuickjsBundleAssetsPlugin] uses.
      val assetPath = TrailblazeTrailmapToolBundlesPlugin.assetPathFor(id, toolName)
      val stageTask =
        project.tasks.register(
          "stageTrailmap$capId${capTool}ToolBundleAsset",
          Copy::class.java,
        ) { task ->
          task.group = "trailblaze"
          task.description =
            "Stages the `$id` trailmap `$toolName` QuickJS bundle into the test APK asset tree."
          task.from(bundleTask.map { it.outputs.files }) { copySpec ->
            copySpec.eachFile { fcd: FileCopyDetails -> fcd.relativePath = RelativePath.parse(true, assetPath) }
          }
          task.into(stagingRoot)
        }
      allStagingTasks.add(stageTask)
    }
  }
}
