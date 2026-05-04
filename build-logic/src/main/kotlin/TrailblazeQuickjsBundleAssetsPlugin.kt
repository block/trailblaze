import javax.inject.Inject
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.Directory
import org.gradle.api.file.FileCopyDetails
import org.gradle.api.file.RelativePath
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.TaskProvider

/**
 * Stages QuickJS author-tool bundles into a single asset directory tree so an Android test
 * APK (or similar consumer) can pick them up via `assets.srcDirs(...)`. Reusable wrapper
 * around what each consuming `build.gradle.kts` would otherwise hand-roll: a per-bundle
 * `Copy` task, a per-bundle staging sub-directory, and a single aggregating root the
 * consumer points `assets.srcDirs(...)` at.
 *
 * Sam called this out during PR review: the original sample-app-uitests `build.gradle.kts`
 * had a 20-line bespoke `Copy { from(...).into(...) ; rename ... }` block plus an
 * AGP-task-matching `dependsOn` block. With multiple downstream consumers on the horizon
 * (additional UI test modules, future host runners) the same scaffolding would copy
 * everywhere; this plugin centralizes it so a future migration only edits one file.
 *
 * ### Usage
 *
 * ```kotlin
 * plugins { id("trailblaze.quickjs-bundle-assets") }
 *
 * trailblazeQuickjsBundleAssets {
 *   register("sampleAppTyped") {
 *     bundleTask.set(project(":trailblaze-quickjs-tools").tasks.named("bundleSampleAppTypedAuthorTool"))
 *     // Path inside the staged tree where consumers find the bundle. Test code reads
 *     // assets relative to this root, so name it stably.
 *     assetPath.set("fixtures/quickjs/typed.bundle.js")
 *   }
 * }
 *
 * android {
 *   sourceSets.getByName("androidTest") {
 *     // Single dir aggregating every registered bundle.
 *     assets.srcDirs(trailblazeQuickjsBundleAssets.stagingRoot, "src/androidTest/assets")
 *   }
 * }
 *
 * tasks.matching { /* AGP asset/lint task family */ }
 *   .configureEach { dependsOn(trailblazeQuickjsBundleAssets.allStagingTasks) }
 * ```
 *
 * ### What the plugin owns vs. what the consumer wires
 *
 * Owns: per-bundle Copy task creation, staging-dir layout, the aggregating root Provider,
 * the list of staging tasks consumers' AGP wiring needs to depend on.
 *
 * Consumer wires: `assets.srcDirs(...)` (AGP-specific) and the AGP-asset-task `dependsOn`
 * (also AGP-specific). The plugin can't touch those without becoming AGP-aware, which
 * would couple build-logic to AGP versions and complicate the composite-build setup.
 */
class TrailblazeQuickjsBundleAssetsPlugin : Plugin<Project> {
  override fun apply(project: Project) {
    val container: NamedDomainObjectContainer<QuickjsBundleAssetSpec> =
      project.objects.domainObjectContainer(QuickjsBundleAssetSpec::class.java) { name ->
        project.objects.newInstance(QuickjsBundleAssetSpec::class.java, name)
      }

    // Single staging root every registered bundle copies into a unique sub-tree of.
    // Aggregation here means the consumer points `assets.srcDirs(...)` at one Provider
    // rather than collecting per-entry directories — fewer moving parts in the build
    // script and AGP's asset merging treats it as one input source.
    val stagingRoot: Provider<Directory> =
      project.layout.buildDirectory.dir("intermediates/staged-quickjs-bundle-assets")

    val stagingTasks: MutableList<TaskProvider<Copy>> = mutableListOf()
    project.extensions.create(
      "trailblazeQuickjsBundleAssets",
      TrailblazeQuickjsBundleAssetsExtension::class.java,
      container,
      stagingRoot,
      stagingTasks,
    )

    container.all { spec ->
      val capName = spec.name.replaceFirstChar { it.uppercase() }
      val taskName = "stage${capName}QuickjsBundleAsset"
      val copyTask = project.tasks.register(taskName, Copy::class.java) { task ->
        task.group = "trailblaze"
        task.description =
          "Stages the `${spec.name}` QuickJS bundle into a test APK asset path " +
            "consumed via `assets.srcDirs(trailblazeQuickjsBundleAssets.stagingRoot)`."
        // Pull the bundle task's outputs as a Provider so Gradle wires the implicit task
        // dependency through the Provider chain — no `dependsOn(spec.bundleTask)` needed.
        task.from(spec.bundleTask.map { it.get().outputs.files }) { copySpec ->
          // Flatten regardless of where the bundle plugin writes — the consumer reads
          // from a stable asset path, not the bundle plugin's internal layout.
          copySpec.eachFile { fcd: FileCopyDetails ->
            fcd.relativePath = RelativePath.parse(true, spec.assetPath.get())
          }
        }
        task.into(stagingRoot)
      }
      stagingTasks.add(copyTask)
    }
  }
}

/**
 * DSL container exposed by the plugin. Holds the registered bundle specs plus the two
 * read-only Providers consumers wire into AGP. Constructed by Gradle via reflection.
 */
abstract class TrailblazeQuickjsBundleAssetsExtension @Inject constructor(
  /** Underlying named domain object container for `register("name") { … }` DSL. */
  private val container: NamedDomainObjectContainer<QuickjsBundleAssetSpec>,
  /**
   * Aggregating staging root. Point AGP's `assets.srcDirs(...)` at this; every registered
   * bundle copies into a sub-path under here so consumers see one logical directory.
   */
  val stagingRoot: Provider<Directory>,
  /**
   * The Copy tasks the plugin registered, exposed so consumers can wire AGP's
   * `mergeXxxAndroidTestAssets` / `lintAnalyzeXxx` tasks to depend on them via
   * `tasks.matching { … }.configureEach { dependsOn(allStagingTasks) }`.
   */
  val allStagingTasks: List<TaskProvider<Copy>>,
) {
  /** `register("name") { bundleTask = …; assetPath = "…" }` from build.gradle.kts. */
  fun register(name: String, configure: QuickjsBundleAssetSpec.() -> Unit) {
    container.register(name, configure)
  }
}

/**
 * One registered bundle: which bundle task produces the .bundle.js, and where in the
 * staged asset tree it should land.
 */
abstract class QuickjsBundleAssetSpec @Inject constructor(
  /** Spec name (used to derive the `stage<Name>QuickjsBundleAsset` task name). */
  val name: String,
  objects: ObjectFactory,
) {

  /**
   * The bundle-producing task — typically a `bundle<Name>AuthorTool` registered by the
   * `trailblaze.author-tool-bundle` plugin. The plugin reads its output via
   * `it.outputs.files`, so the implicit task dependency flows through automatically.
   */
  abstract val bundleTask: Property<TaskProvider<out Task>>

  /**
   * Path inside the aggregated staging root where the bundle file is placed. Consumers
   * read this exact path at runtime (e.g. `AndroidAssetBundleSource("fixtures/quickjs/typed.bundle.js")`),
   * so naming must be stable across builds.
   */
  abstract val assetPath: Property<String>
}
