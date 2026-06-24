import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations

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

    val generate = project.tasks.register("generateDtoTs", GenerateDtoTsTask::class.java) { task ->
      task.group = "build"
      task.description =
        "Regenerates the committed TypeScript DTO bindings from the Kotlin @Serializable models. " +
          "Run after changing an exported model or the root list, and commit the regenerated file."
      task.codegenClasspath.from(ext.codegenClasspath)
      task.generatorMainClass.set(ext.mainClass)
      task.outputFile.set(ext.generatedTsFile)
    }

    project.tasks.register("verifyDtoTs", VerifyDtoTsTask::class.java) { task ->
      task.group = "verification"
      task.description =
        "Verifies the committed TypeScript DTO bindings match a fresh codegen against the Kotlin " +
          "models. Fails with the regenerate command on drift. Wired into check."
      task.codegenClasspath.from(ext.codegenClasspath)
      task.generatorMainClass.set(ext.mainClass)
      task.committedFile.set(ext.generatedTsFile)
      task.freshFile.set(project.layout.buildDirectory.file("dto-ts-codegen/fresh-dto-bindings.ts"))
      task.regenerateTaskPath.set("${project.path}:generateDtoTs")
      // `check` runs verify but never generate, so this is ordering-only (NOT a dependency): if
      // both are scheduled in one build, verify runs after generate's write to the committed file.
      task.mustRunAfter(generate)
    }

    project.tasks.named("check") { it.dependsOn("verifyDtoTs") }
  }
}

abstract class GenerateDtoTsTask @Inject constructor(objects: ObjectFactory) : DefaultTask() {
  @get:Classpath
  val codegenClasspath: ConfigurableFileCollection = objects.fileCollection()

  @get:Input
  abstract val generatorMainClass: Property<String>

  @get:OutputFile
  abstract val outputFile: RegularFileProperty

  @get:Inject
  abstract val execOperations: ExecOperations

  @TaskAction
  fun generate() {
    requireConfigured(generatorMainClass.isPresent, outputFile.isPresent)
    execOperations.javaexec { spec ->
      spec.classpath(codegenClasspath)
      spec.mainClass.set(generatorMainClass)
      spec.args(outputFile.get().asFile.absolutePath)
    }
  }
}

abstract class VerifyDtoTsTask @Inject constructor(objects: ObjectFactory) : DefaultTask() {
  @get:Classpath
  val codegenClasspath: ConfigurableFileCollection = objects.fileCollection()

  @get:Input
  abstract val generatorMainClass: Property<String>

  @get:Optional
  @get:InputFile
  abstract val committedFile: RegularFileProperty

  @get:OutputFile
  abstract val freshFile: RegularFileProperty

  @get:Input
  abstract val regenerateTaskPath: Property<String>

  @get:Inject
  abstract val execOperations: ExecOperations

  @TaskAction
  fun verify() {
    requireConfigured(generatorMainClass.isPresent, committedFile.isPresent)
    val fresh = freshFile.get().asFile
    execOperations.javaexec { spec ->
      spec.classpath(codegenClasspath)
      spec.mainClass.set(generatorMainClass)
      spec.args(fresh.absolutePath)
    }
    val committed = committedFile.get().asFile
    if (!committed.exists()) {
      throw GradleException(
        "Committed DTO bindings missing at ${committed.absolutePath}. Run " +
          "`./gradlew ${regenerateTaskPath.get()}` and commit the result.",
      )
    }
    if (committed.readText(Charsets.UTF_8) != fresh.readText(Charsets.UTF_8)) {
      throw GradleException(
        "TypeScript DTO bindings are out of date. Regenerate with:\n" +
          "  ./gradlew ${regenerateTaskPath.get()}\n" +
          "and commit ${committed.absolutePath}.",
      )
    }
  }
}

private fun requireConfigured(mainClassPresent: Boolean, generatedTsFilePresent: Boolean) {
  require(mainClassPresent) {
      "trailblazeDtoTsCodegen.mainClass must be set to the generator's main class FQN."
  }
  require(generatedTsFilePresent) {
      "trailblazeDtoTsCodegen.generatedTsFile must be set to the committed output .ts path."
  }
}
