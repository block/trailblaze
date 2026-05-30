import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.IgnoreEmptyDirectories
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectories
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import xyz.block.trailblaze.bundle.TrailblazeTrailmapBundleException
import xyz.block.trailblaze.bundle.TrailblazeTrailmapBundler

/**
 * Gradle-side configuration shape for [BundleTrailblazeTrailmapTask]. The bundler library
 * itself (`xyz.block.trailblaze.bundle.TrailblazeTrailmapBundler`) lives in the
 * `:trailblaze-trailmap-bundler` main-build module and is composed into build-logic via
 * shared `srcDir` (see `build-logic/build.gradle.kts`). Same library, two consumers
 * (this Gradle plugin + the `trailblaze bundle` CLI), no drift.
 */
abstract class TrailblazeBundleExtension @Inject constructor(objects: ObjectFactory) {
  /**
   * Trailmap root — the directory containing one subdirectory per trailmap, each with a `trailmap.yaml`.
   * Mirrors the `bundledTrailblazeConfig.trailmapsDir` convention. The bundler writes one
   * `tools.d.ts` per trailmap at `<trailmapDir>/tools/.trailblaze/tools.d.ts`, so each trailmap's
   * bindings live next to its own `tsconfig.json`/`package.json` and travel with the trailmap
   * when it's zipped or published.
   */
  val trailmapsDir: DirectoryProperty = objects.directoryProperty()

  /**
   * Workspace root — the directory that holds `trails/config/trailblaze.yaml`. The
   * `compileTrailblazeWorkspace` JavaExec task uses this as its `workingDir` so the
   * embedded [xyz.block.trailblaze.host.WorkspaceCompileBootstrap] walk-up resolves the
   * workspace correctly. Defaults to three directories above [trailmapsDir] (the canonical
   * `<workspace>/trails/config/trailmaps/` layout), which is correct for every workspace that
   * follows the convention; consumers with a non-standard layout can override.
   */
  val workspaceRoot: DirectoryProperty = objects.directoryProperty()

  /**
   * Whether the per-trailmap `bundleTrailblazeTrailmap` half of the plugin runs. `true` by
   * default — every consumer gets `tools.d.ts` generation alongside the
   * `compileTrailblazeWorkspace` JavaExec. Trailmaps that still ship `.js` scripted-tool
   * descriptors (pre-`project_scripting_sdk_ts_only_authoring.md` lockdown) hit the
   * bundler's TS-only enforcement and can't pass — those modules set this to `false` so
   * only the workspace-compile half runs (which routes through `TrailblazeCompiler` and
   * still accepts `.js` for now). Declarative form replaces the older
   * `tasks.named("bundleTrailblazeTrailmap") { enabled = false }` workaround that previously
   * had to be repeated across every affected consumer.
   *
   * Usage:
   * ```kotlin
   * plugins { id("trailblaze.bundle") }
   *
   * trailblazeBundle {
   *   trailmapsDir.set(layout.projectDirectory.dir("trails/config/trailmaps"))
   *   // Legacy `.js` scripted tools in this trailmap — skip the TS-only bundler half.
   *   bundleEnabled.set(false)
   * }
   * ```
   *
   * Scope: this flag controls ONLY the `bundleTrailblazeTrailmap` task that emits per-trailmap
   * `tools.d.ts`. The `compileTrailblazeWorkspace` JavaExec (workspace SDK extraction +
   * per-trailmap `client.d.ts` + per-trailmap `tsconfig.json`) is unaffected and keeps running
   * — that's what makes setting `bundleEnabled = false` safe for `.js` trailmaps that still
   * want IDE autocomplete materialized on every `./gradlew build`.
   */
  val bundleEnabled: Property<Boolean> = objects.property(Boolean::class.java)
}

/**
 * Thin Gradle-task wrapper around [TrailblazeTrailmapBundler] from `:trailblaze-trailmap-bundler`.
 * Validates extension wiring at task-action time, invokes the bundler, and translates
 * the bundler's domain exception into `GradleException` so build failures render
 * naturally in Gradle output.
 *
 * **`@Optional` convention.** [trailmapsDir] is logically required — the task can't do
 * anything useful without it — but its input-tracking surface ([trailmapManifestFiles]) is
 * marked `@get:Optional` at the Gradle annotation level. The reason: without `@Optional`,
 * Gradle's input/output snapshot phase short-circuits with a generic "An input file was
 * expected but it doesn't exist" error before the [@TaskAction] runs, and the author has
 * no way to tell *which* property is missing or *how* to wire it. Marking it optional
 * lets the task action itself throw a directed `GradleException` with the exact
 * configuration snippet the consumer needs to add. Maintainers reading the property
 * declaration should treat it as effectively required despite the annotation.
 *
 * **Input split.** [trailmapsDir] is `@Internal` (configuration handle only) and the
 * snapshotted inputs are exposed via [trailmapManifestFiles], a filtered file tree limited
 * to `*.yaml` / `*.yml` files — the only files the bundler actually reads (trailmap.yaml plus
 * per-tool descriptor YAMLs). Narrowing the input set this way keeps the generated
 * `tools.d.ts` from feeding back into the input snapshot (which would otherwise prevent
 * `UP-TO-DATE` on the second run) AND keeps author-side `.ts` / `.js` tool implementations
 * out of the snapshot — editing a tool's TypeScript source shouldn't invalidate the
 * bindings derived from its YAML descriptor.
 *
 * **Output tracking.** [outputDirs] is a lazy `Provider<List<Directory>>` populated by
 * [TrailblazeBundlePlugin] from
 * [TrailblazeTrailmapBundler.discoverExpectedOutputDirs] — a walk that runs at task-
 * up-to-date check time, not at configuration time, so Gradle's configuration cache stays
 * green. Output tracking gives us correct UP-TO-DATE detection (input + output snapshots
 * match a previous run) and per-trailmap output isolation (each trailmap's `<trailmapDir>/tools/
 * .trailblaze/` is its own declared output). Stale outputs from a renamed-or-deleted trailmap
 * are handled by the bundler itself (see [TrailblazeTrailmapBundler.generate]'s orphan
 * cleanup pass) rather than by Gradle's stale-output sweep, which only cleans files
 * inside *currently declared* output dirs.
 */
abstract class BundleTrailblazeTrailmapTask : DefaultTask() {
  /**
   * Configuration handle for the trailmap root. Marked `@Internal` so Gradle doesn't
   * snapshot it as an input — the actual input set is exposed via [trailmapManifestFiles],
   * which filters out the bundler's own generated `.trailblaze/` dirs.
   */
  @get:Internal
  abstract val trailmapsDir: DirectoryProperty

  /**
   * Input file tree wired from [trailmapsDir] by [TrailblazeBundlePlugin]. Snapshots the
   * `*.yaml` / `*.yml` files the bundler actually reads — `trailmap.yaml` plus the per-tool
   * descriptor YAMLs referenced from `target.tools:`. Skips the bundler's own generated
   * `tools.d.ts` (different extension) so it can't feed back into the next run's input
   * snapshot, and skips author-side `.ts` / `.js` tool implementations so editing a
   * tool's TypeScript source doesn't invalidate bindings derived from its YAML.
   *
   * `@IgnoreEmptyDirectories` keeps an empty `trailmaps/` (no trailmap.yaml files yet) from
   * being treated as a meaningful input change when an author drops their first trailmap
   * into the dir — only the file content matters for change detection.
   */
  @get:InputFiles
  @get:Optional
  @get:PathSensitive(PathSensitivity.RELATIVE)
  @get:IgnoreEmptyDirectories
  abstract val trailmapManifestFiles: ConfigurableFileCollection

  /**
   * Lazily-computed output directories, one `<trailmapDir>/tools/.trailblaze/` per trailmap that
   * has scripted tools. Populated by [TrailblazeBundlePlugin] from
   * [TrailblazeTrailmapBundler.discoverExpectedOutputDirs]. Gradle resolves the list at task-
   * up-to-date check time (not at configuration time), so the underlying trailmap walk
   * doesn't run on every configuration phase and the configuration cache stays green.
   */
  @get:OutputDirectories
  abstract val outputDirs: ListProperty<Directory>

  @TaskAction
  fun generate() {
    if (!trailmapsDir.isPresent) {
      throw GradleException(
        "trailblaze.bundle: 'trailmapsDir' is not configured. Add to your build.gradle.kts:\n" +
          "  trailblazeBundle {\n" +
          "    trailmapsDir.set(layout.projectDirectory.dir(\"...\"))\n" +
          "  }",
      )
    }
    val bundler = TrailblazeTrailmapBundler(
      trailmapsDir = trailmapsDir.asFile.get(),
    )
    try {
      bundler.generate()
    } catch (e: TrailblazeTrailmapBundleException) {
      // Domain exception — message is already author-actionable. Translate to Gradle's
      // idiom so failures render with the standard "Build failed" framing. The bundler
      // itself stays Gradle-agnostic so the same code path serves the CLI / daemon
      // callers without dragging Gradle types in.
      throw GradleException(e.message ?: "TrailblazeTrailmapBundler failed without a message", e)
    } catch (e: RuntimeException) {
      // Unexpected runtime exception (NullPointerException, IllegalStateException, an
      // IOException's UncheckedIOException wrapper from Files.walk on a permission-denied
      // path, etc.) — bypass would surface a raw stack trace to the build. Wrap with an
      // explicit "unexpected" prefix so an author can tell the difference between "your
      // trailmap manifest is malformed" (a TrailblazeTrailmapBundleException above) and "the
      // bundler hit a real bug" (this branch). Cause is preserved so `--stacktrace` still
      // shows the original failure point.
      throw GradleException(
        "TrailblazeTrailmapBundler failed unexpectedly (this is likely a bundler bug, not a " +
          "trailmap-manifest authoring error): ${e.message ?: e::class.simpleName}",
        e,
      )
    } catch (e: java.io.IOException) {
      // Files.walk and File.canonicalFile both throw checked IOException; Kotlin treats
      // it as unchecked but it doesn't extend RuntimeException, so the previous branch
      // misses it. Same translation idiom — disk error rather than author error.
      throw GradleException(
        "TrailblazeTrailmapBundler hit an I/O failure walking ${trailmapsDir.asFile.get().absolutePath}: " +
          "${e.message ?: e::class.simpleName}",
        e,
      )
    }
  }

  internal companion object {
    const val GENERATED_DIR_NAME = TrailblazeTrailmapBundler.GENERATED_DIR_NAME
    const val GENERATED_FILE_NAME = TrailblazeTrailmapBundler.GENERATED_FILE_NAME
  }
}
