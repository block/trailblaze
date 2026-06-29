import java.io.File
import javax.inject.Inject
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
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
 *   id("xyz.block.trailblaze.trailmap-tool-bundles")
 * }
 *
 * trailblazeTrailmapToolBundles {
 *   // OPTIONAL — when unset, the plugin walks up from `rootProject.projectDir` looking for
 *   // `sdks/typescript/package.json`. Set explicitly when consuming this plugin outside the
 *   // Trailblaze framework source tree — point it at a directory whose layout matches
 *   // `sdks/typescript/` (a `node_modules/.bin/esbuild`, a `src/in-process.ts`, and the
 *   // `tools/in-process-wrapper-template.mjs` template).
 *   sdkDir.set(layout.projectDirectory.dir("sdk-bundle"))
 *
 *   trailmap(
 *     id = "myapp",
 *     toolsDir = rootProject.file("path/to/trailmaps/myapp/tools"),
 *   )
 * }
 *
 * // Ship the staged bundles in the test APK + close AGP's implicit-dependency gap.
 * android {
 *   sourceSets.getByName("androidTest").assets.srcDir(trailblazeTrailmapToolBundles.stagingRoot)
 * }
 * tasks.matching { it.name.endsWith("AndroidTestAssets") }
 *   .configureEach { dependsOn(trailblazeTrailmapToolBundles.allStagingTasks) }
 * ```
 *
 * The plugin owns bundling + staging but stays AGP-unaware: the consumer wires
 * `assets.srcDirs(...)` and the asset-task `dependsOn(...)` because those couple to AGP versions
 * the plugin's classpath deliberately doesn't carry.
 */
class TrailblazeTrailmapToolBundlesPlugin : Plugin<Project> {
  override fun apply(project: Project) {
    // Single staging root every bundle copies into a sub-path of, laid out as
    // `trails/config/trailmaps/<id>/tools/<name>.bundle.js` — the exact asset path the on-device
    // launcher resolves.
    val stagingRoot: Provider<Directory> =
      project.layout.buildDirectory.dir("intermediates/trailblaze/trailmap-tool-bundle-assets")
    val stagingTasks: MutableList<TaskProvider<Copy>> = mutableListOf()

    val extension = project.extensions.create(
      "trailblazeTrailmapToolBundles",
      TrailblazeTrailmapToolBundlesExtension::class.java,
      project,
      stagingRoot,
      stagingTasks,
    )

    // Framework-source-tree convenience: when the bundle is consumed inside the Trailblaze
    // monorepo, the SDK's `node_modules/.bin/esbuild` is populated by a sibling Gradle task
    // (`:trailblaze-scripting-subprocess:installTrailblazeScriptingSdk`). When that project
    // is present in the consumer's build, default `sdkInstallTaskPath` to it so the bundle
    // tasks transparently chain to the install — no extra config in the consumer's build
    // script. External consumers (without that sibling project) leave the property unset
    // and manage the SDK install themselves.
    val candidateInstallPath = ":trailblaze-scripting-subprocess:installTrailblazeScriptingSdk"
    if (project.rootProject.findProject(":trailblaze-scripting-subprocess") != null) {
      extension.sdkInstallTaskPath.convention(candidateInstallPath)
    }
  }

  companion object {
    /**
     * Asset-tree-relative path for a tool's bundle — kept in lockstep with the runtime
     * resolver. Literal `trailmaps` rather than a constant from `:trailblaze-models` because
     * this plugin's classpath deliberately doesn't carry that module.
     */
    fun assetPathFor(trailmapId: String, toolName: String): String =
      "trails/config/trailmaps/$trailmapId/tools/$toolName.bundle.js"

    /**
     * Returns the in-process scripted-tool entry `.ts` files in [toolsDir]. A `<name>.ts` qualifies
     * when it is an in-process scripted tool that isn't a subprocess tool — established one of two
     * ways:
     *  - **Sidecar descriptor:** a sibling `<name>.yaml` whose runtime isn't `subprocess`.
     *  - **Descriptor-less (TypeScript-only):** no `<name>.yaml`, but the `.ts` declares the tool
     *    inline via `export const … = trailblaze.tool<…>(…)` and does not pin `runtime: subprocess`
     *    in its inline spec. This mirrors the descriptor side, which derives such tools straight
     *    from their TypeScript when no sidecar is present (PR #3981) — without this branch those
     *    tools were silently dropped from the on-device bundle and failed at runtime with
     *    "Unknown framework tool".
     *
     * Excludes `.test.ts` fixtures, `.d.ts`, and shared helper modules (which export plain functions
     * and never call `trailblaze.tool`, so neither path matches them). Sorted for deterministic task
     * registration order.
     */
    fun inProcessToolSources(toolsDir: File): List<File> {
      val subprocessRuntimeYaml = Regex("(?m)^\\s*runtime:\\s*subprocess\\s*$")
      // Inline `export const … = trailblaze.tool<…>(…)` / `trailblaze.tool(…)` registration marker.
      val toolExport = Regex("""trailblaze\s*\.\s*tool\s*[<(]""")
      // Inline-spec counterpart to the `.yaml` `runtime: subprocess` gate.
      val subprocessRuntimeTs = Regex("""runtime\s*:\s*["']?subprocess""")
      return (toolsDir.listFiles() ?: emptyArray())
        .filter { f ->
          f.isFile &&
            f.name.endsWith(".ts") &&
            !f.name.endsWith(".test.ts") &&
            !f.name.endsWith(".d.ts") &&
            File(toolsDir, f.name.removeSuffix(".ts") + ".yaml").let { yaml ->
              if (yaml.isFile) {
                !subprocessRuntimeYaml.containsMatchIn(yaml.readText())
              } else {
                val text = f.readText()
                toolExport.containsMatchIn(text) && !subprocessRuntimeTs.containsMatchIn(text)
              }
            }
        }
        .sortedBy { it.name }
    }
  }
}

/**
 * DSL container exposed by the plugin. Declare each trailmap via [trailmap]; wire [stagingRoot] and
 * [allStagingTasks] into AGP from the consuming build script.
 *
 * ### External vs. framework-source-tree consumers
 *
 * The bundle task needs three things from a Trailblaze TypeScript SDK install:
 *  - The `esbuild` binary at `<sdkDir>/node_modules/.bin/esbuild`
 *  - The slim in-process entry at `<sdkDir>/src/in-process.ts`
 *  - The wrapper template at `<sdkDir>/tools/in-process-wrapper-template.mjs`
 *
 * **For consumers building inside the Trailblaze framework source tree** (where the SDK lives
 * at `<repo>/sdks/typescript/`), leave [sdkDir] unset — the plugin walks up from
 * `rootProject.projectDir` looking for the SDK's marker `sdks/typescript/package.json` and
 * wires everything automatically. This is the historical behavior; every existing
 * apply-site keeps working unchanged.
 *
 * **For consumers building outside that tree** (e.g. an Android team's own repo), set [sdkDir]
 * to the directory containing your SDK install. The directory layout must match
 * `sdks/typescript/`'s — at minimum a `node_modules/.bin/esbuild` produced by
 * `bun install`, a `src/in-process.ts`, and a `tools/in-process-wrapper-template.mjs`. Until
 * `@trailblaze/scripting` is published to npm, an external consumer typically vendors a copy
 * of the framework's `sdks/typescript/` directory and runs `bun install` in it.
 */
abstract class TrailblazeTrailmapToolBundlesExtension @Inject constructor(
  private val project: Project,
  /** Aggregating staging root — point AGP's `assets.srcDirs(...)` at this. */
  val stagingRoot: Provider<Directory>,
  /** The staging Copy tasks, for the consumer's AGP-asset-task `dependsOn(...)` wiring. */
  val allStagingTasks: MutableList<TaskProvider<Copy>>,
) {
  /**
   * Optional explicit Trailblaze TypeScript SDK install directory. When set, the plugin reads
   * `<sdkDir>/node_modules/.bin/esbuild`, `<sdkDir>/src/in-process.ts`, and
   * `<sdkDir>/tools/in-process-wrapper-template.mjs`. When unset, the plugin walks up from
   * `rootProject.projectDir` to find `sdks/typescript/package.json` (the framework-source-tree
   * convention).
   */
  abstract val sdkDir: DirectoryProperty

  /**
   * Optional Gradle task path that each per-tool bundle task should `dependsOn`, so an
   * SDK-install step (e.g. `bun install` in the SDK directory) runs first. Set this when the
   * consuming build owns a sibling task that populates the SDK's `node_modules/.bin/esbuild`;
   * leave it unset when the consumer manages the install lifecycle some other way (manual,
   * Gradle convention plugin, etc.).
   */
  abstract val sdkInstallTaskPath: org.gradle.api.provider.Property<String>

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
    val sdkRoot: File? = sdkDir.orNull?.asFile ?: project.locateFrameworkSdkRoot()
    val esbuild: File? = sdkRoot?.let { File(it, "node_modules/.bin/esbuild") }
    val sdkSrc: File? = sdkRoot?.let { File(it, "src/in-process.ts") }
    val wrapperTemplate: File? = sdkRoot?.let { File(it, "tools/in-process-wrapper-template.mjs") }
    if (esbuild == null || sdkSrc == null || wrapperTemplate == null) {
      throw GradleException(
        "trailblazeTrailmapToolBundles.trailmap(\"$id\"): could not locate the Trailblaze " +
          "TypeScript SDK. Set `trailblazeTrailmapToolBundles.sdkDir` to the directory containing " +
          "your @trailblaze/scripting install (with `node_modules/.bin/esbuild`, `src/in-process.ts`, " +
          "and `tools/in-process-wrapper-template.mjs`), or place the framework's `sdks/typescript/` " +
          "tree at or above `${project.rootProject.projectDir}` so the default walk-up can find it.",
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
          task.projectDir.set(project.layout.projectDirectory)
          task.logFile.set(
            project.layout.buildDirectory.file(
              "tmp/bundle-trailmap-tool-$id-$toolName.log",
            ),
          )
          // Snapshot only the author-managed `.ts` sources for the up-to-date check (no
          // node_modules/ walk). esbuild --bundle inlines any sibling helper modules a tool
          // imports, so the whole tools dir's `.ts` is the change-detection surface.
          task.inputSources.from(
            project.layout.projectDirectory.dir(toolsDir.absolutePath).asFileTree.matching {
              it.include("**/*.ts")
              it.exclude("**/.trailblaze-wrapper-*")
            },
          )
          // esbuild lives in the SDK's node_modules; if the consumer's build owns an
          // install task, run it first. External consumers that manage their install
          // lifecycle themselves leave `sdkInstallTaskPath` unset.
          sdkInstallTaskPath.orNull?.let { task.dependsOn(it) }
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
