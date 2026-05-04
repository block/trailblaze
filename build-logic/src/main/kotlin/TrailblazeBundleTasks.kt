import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import xyz.block.trailblaze.bundle.TrailblazePackBundleException
import xyz.block.trailblaze.bundle.TrailblazePackBundler

/**
 * Gradle-side configuration shape for [BundleTrailblazePackTask]. The bundler library
 * itself (`xyz.block.trailblaze.bundle.TrailblazePackBundler`) lives in the
 * `:trailblaze-pack-bundler` main-build module and is composed into build-logic via
 * shared `srcDir` (see `build-logic/build.gradle.kts`). Same library, two consumers
 * (this Gradle plugin + the `trailblaze bundle` CLI), no drift.
 */
abstract class TrailblazeBundleExtension @Inject constructor(objects: ObjectFactory) {
  /**
   * Pack root — the directory containing one subdirectory per pack, each with a `pack.yaml`.
   * Mirrors the `bundledTrailblazeConfig.packsDir` convention.
   */
  val packsDir: DirectoryProperty = objects.directoryProperty()

  /**
   * Per-pack tools directory — where flat `.ts` / `.js` tool sources live, alongside
   * `package.json` + `tsconfig.json`. The bundler writes its output into a `.trailblaze/`
   * subdirectory under this path so the per-pack tsconfig's `include` glob (matching
   * `.ts` files recursively) picks it up without further configuration.
   *
   * In the example layout this is `[example]/trailblaze-config/tools/`, parallel to
   * `[example]/trailblaze-config/packs/`.
   */
  val toolsDir: DirectoryProperty = objects.directoryProperty()
}

/**
 * Thin Gradle-task wrapper around [TrailblazePackBundler] from `:trailblaze-pack-bundler`.
 * Validates extension wiring at task-action time, invokes the bundler, and translates
 * the bundler's domain exception into `GradleException` so build failures render
 * naturally in Gradle output.
 *
 * **`@Optional` convention.** Both [packsDir] and [outputDir] are logically required —
 * the task can't do anything useful without them — but they're marked `@get:Optional` at
 * the Gradle annotation level. The reason: without `@Optional`, Gradle's input/output
 * snapshot phase short-circuits with a generic "An input file was expected but it doesn't
 * exist" error before the [@TaskAction] runs, and the author has no way to tell *which*
 * property is missing or *how* to wire it. Marking them optional lets the task action
 * itself throw a directed `GradleException` with the exact configuration snippet the
 * consumer needs to add. Maintainers reading the property declarations should treat them
 * as effectively required despite the annotation.
 */
abstract class BundleTrailblazePackTask : DefaultTask() {
  /** See class kdoc on the `@Optional`-as-DX-trick convention. */
  @get:InputDirectory
  @get:Optional
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val packsDir: DirectoryProperty

  /**
   * Output is declared as a directory (`<toolsDir>/.trailblaze/`) rather than a single
   * `tools.d.ts` file so Gradle's stale-output cleanup removes the whole subdir on changes
   * that drop the last scripted tool, without touching anything else under the per-pack
   * tools directory. Wired by the plugin to `<toolsDir>/.trailblaze/`.
   *
   * Same `@Optional` rationale as [packsDir].
   */
  @get:OutputDirectory
  @get:Optional
  abstract val outputDir: DirectoryProperty

  @TaskAction
  fun generate() {
    if (!packsDir.isPresent) {
      throw GradleException(
        "trailblaze.bundle: 'packsDir' is not configured. Add to your build.gradle.kts:\n" +
          "  trailblazeBundle {\n" +
          "    packsDir.set(layout.projectDirectory.dir(\"...\"))\n" +
          "    toolsDir.set(layout.projectDirectory.dir(\"...\"))\n" +
          "  }",
      )
    }
    if (!outputDir.isPresent) {
      throw GradleException(
        "trailblaze.bundle: 'toolsDir' is not configured (the plugin derives " +
          "outputDir from it). Add `toolsDir.set(layout.projectDirectory.dir(\"...\"))` to " +
          "your `trailblazeBundle { ... }` block.",
      )
    }
    val bundler = TrailblazePackBundler(
      packsDir = packsDir.asFile.get(),
      outputDir = outputDir.asFile.get(),
    )
    try {
      bundler.generate()
    } catch (e: TrailblazePackBundleException) {
      // Domain exception — message is already author-actionable. Translate to Gradle's
      // idiom so failures render with the standard "Build failed" framing. The bundler
      // itself stays Gradle-agnostic so the same code path serves the CLI / daemon
      // callers without dragging Gradle types in.
      throw GradleException(e.message ?: "TrailblazePackBundler failed without a message", e)
    } catch (e: RuntimeException) {
      // Unexpected runtime exception (NullPointerException, IllegalStateException, an
      // IOException's UncheckedIOException wrapper from Files.walk on a permission-denied
      // path, etc.) — bypass would surface a raw stack trace to the build. Wrap with an
      // explicit "unexpected" prefix so an author can tell the difference between "your
      // pack manifest is malformed" (a TrailblazePackBundleException above) and "the
      // bundler hit a real bug" (this branch). Cause is preserved so `--stacktrace` still
      // shows the original failure point.
      throw GradleException(
        "TrailblazePackBundler failed unexpectedly (this is likely a bundler bug, not a " +
          "pack-manifest authoring error): ${e.message ?: e::class.simpleName}",
        e,
      )
    } catch (e: java.io.IOException) {
      // Files.walk and File.canonicalFile both throw checked IOException; Kotlin treats
      // it as unchecked but it doesn't extend RuntimeException, so the previous branch
      // misses it. Same translation idiom — disk error rather than author error.
      throw GradleException(
        "TrailblazePackBundler hit an I/O failure walking ${packsDir.asFile.get().absolutePath}: " +
          "${e.message ?: e::class.simpleName}",
        e,
      )
    }
  }

  internal companion object {
    const val GENERATED_DIR_NAME = TrailblazePackBundler.GENERATED_DIR_NAME
    const val GENERATED_FILE_NAME = TrailblazePackBundler.GENERATED_FILE_NAME
  }
}
