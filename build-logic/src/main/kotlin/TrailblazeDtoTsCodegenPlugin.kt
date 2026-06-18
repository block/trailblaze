import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.JavaExec

/**
 * Extension for the `trailblaze.dto-ts-codegen` plugin. A consuming module points the plugin at the
 * generator `main` it ships, that module's runtime classpath (so the `@Serializable` classes are
 * loadable), and the committed `.ts` the bindings land in.
 */
interface TrailblazeDtoTsCodegenExtension {
  /**
   * Fully-qualified name of the generator entry point (e.g.
   * `xyz.block.trailblaze.trailrunner.codegen.TrailRunnerDtoTsBindingsKt`). It must accept the
   * output path as `args[0]` and write the rendered TypeScript there.
   */
  val mainClass: Property<String>

  /**
   * Runtime classpath of the module whose `@Serializable` models are exported — typically
   * `compilation.output.allOutputs + compilation.runtimeDependencyFiles`. The generator runs
   * against this via `JavaExec`, so it carries the compiled DTO classes plus
   * `kotlinx-serialization` and the reusable `SerialDescriptorTsCodegen` walker.
   */
  val codegenClasspath: ConfigurableFileCollection

  /**
   * The committed output `.ts` (e.g. the SDK's `sdks/typescript/src/generated/<name>.ts`).
   * `generateDtoTs` writes here; `verifyDtoTs` byte-diffs a fresh codegen against it.
   */
  val generatedTsFile: RegularFileProperty
}

/**
 * Registers `generateDtoTs` (manual regenerate) and `verifyDtoTs` (CI freshness gate, wired into
 * `check`) for descriptor-walking Kotlin → TypeScript codegen. The sibling of
 * [TrailblazeSelectorTsCodegenPlugin], with the same regenerate-and-commit / byte-diff cadence.
 *
 * **Why this runs via `JavaExec` instead of in-process like the selector plugin.** The selector
 * codegen parses Kotlin *source text*, so it can run inside `build-logic` with no runtime classes.
 * This codegen walks `kotlinx.serialization` `SerialDescriptor`s, which requires the *compiled*
 * `@Serializable` classes on a runtime classpath — so the plugin only wires the tasks, while the
 * generator `main` + its root-type list live in the consuming module and the reusable
 * `SerialDescriptorTsCodegen` walker lives in `:trailblaze-models`.
 */
class TrailblazeDtoTsCodegenPlugin : Plugin<Project> {
  override fun apply(project: Project) {
    val ext = project.extensions.create(
      "trailblazeDtoTsCodegen",
      TrailblazeDtoTsCodegenExtension::class.java,
    )

    val generate = project.tasks.register("generateDtoTs", JavaExec::class.java) { task ->
      task.group = "build"
      task.description =
        "Regenerates the committed TypeScript DTO bindings from the Kotlin @Serializable models. " +
          "Run after changing an exported model or the root list, and commit the regenerated file."
      task.classpath = ext.codegenClasspath
      task.mainClass.set(ext.mainClass)
      // Lazy: the output path is read at execution, after the consuming module configures it.
      task.argumentProviders.add { listOf(ext.generatedTsFile.get().asFile.absolutePath) }
      task.outputs.file(ext.generatedTsFile).withPropertyName("generatedDtoTs")
      task.doFirst { requireConfigured(ext) }
    }

    project.tasks.register("verifyDtoTs", JavaExec::class.java) { task ->
      task.group = "verification"
      task.description =
        "Verifies the committed TypeScript DTO bindings match a fresh codegen against the Kotlin " +
          "models. Fails with the regenerate command on drift. Wired into check."
      task.classpath = ext.codegenClasspath
      task.mainClass.set(ext.mainClass)

      val freshFile = project.layout.buildDirectory.file("dto-ts-codegen/fresh-dto-bindings.ts")
      task.argumentProviders.add { listOf(freshFile.get().asFile.absolutePath) }
      // Treat the committed file as an INPUT (verify reads it to byte-diff; it never writes there).
      // The conditional collection keeps the input declaration valid whether or not the committed
      // file exists, so Gradle doesn't surface a generic "input file does not exist" error before
      // the friendly doLast guard can fire with the regenerate command.
      task.inputs.files(
        project.provider {
          if (!ext.generatedTsFile.isPresent) return@provider emptyList<java.io.File>()
          val committed = ext.generatedTsFile.get().asFile
          if (committed.exists()) listOf(committed) else emptyList()
        },
      ).withPropertyName("committedDtoTs")
      task.outputs.file(freshFile).withPropertyName("freshDtoTs")
      // `check` runs verify but never generate, so this is ordering-only (NOT a dependency): if
      // both are scheduled in one build, verify runs after generate's write to the committed file.
      task.mustRunAfter(generate)

      task.doFirst { requireConfigured(ext) }
      task.doLast {
        val committed = ext.generatedTsFile.get().asFile
        if (!committed.exists()) {
          throw GradleException(
            "Committed DTO bindings missing at ${committed.absolutePath}. Run " +
              "`./gradlew ${project.path}:generateDtoTs` and commit the result.",
          )
        }
        val fresh = freshFile.get().asFile
        if (committed.readText(Charsets.UTF_8) != fresh.readText(Charsets.UTF_8)) {
          throw GradleException(
            "TypeScript DTO bindings are out of date. Regenerate with:\n" +
              "  ./gradlew ${project.path}:generateDtoTs\n" +
              "and commit ${committed.absolutePath}.",
          )
        }
      }
    }

    project.tasks.named("check") { it.dependsOn("verifyDtoTs") }
  }

  private fun requireConfigured(ext: TrailblazeDtoTsCodegenExtension) {
    require(ext.mainClass.isPresent) {
      "trailblazeDtoTsCodegen.mainClass must be set to the generator's main class FQN."
    }
    require(ext.generatedTsFile.isPresent) {
      "trailblazeDtoTsCodegen.generatedTsFile must be set to the committed output .ts path."
    }
  }
}
