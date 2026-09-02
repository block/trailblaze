import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Named
import org.gradle.api.NamedDomainObjectContainer
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
 * One generator → one committed `.ts` file. A module declares as many of these as it exports
 * bindings for; each gets its own generate/verify task pair.
 */
interface TrailblazeDtoTsBinding : Named {
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
   * The generate task writes here; the verify task byte-diffs a fresh codegen against it.
   */
  val generatedTsFile: RegularFileProperty
}

/**
 * Extension for the `trailblaze.dto-ts-codegen` plugin. A consuming module registers one
 * [TrailblazeDtoTsBinding] per committed `.ts` it exports.
 *
 * A container rather than a single generator, because one module can legitimately own more than one
 * TypeScript surface — `:trailblaze-models` exports both the host RPC DTOs and the `usages` report
 * contract, and they are read by different consumers with different lifetimes. Sharing one generator
 * would either fuse two unrelated surfaces into one file or push a model's bindings into whichever
 * other module still had its single slot free.
 */
interface TrailblazeDtoTsCodegenExtension {
  /**
   * One entry per committed `.ts`. The entry name suffixes its task names. At least one is
   * required — an empty container fails configuration, because it would leave `check` green with
   * nothing verified.
   */
  val bindings: NamedDomainObjectContainer<TrailblazeDtoTsBinding>
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

    // `generateDtoTs` / `verifyDtoTs` stay the names a developer types and every doc and generated
    // header names — they are now aggregates over the module's bindings, so regenerating a module
    // still means regenerating everything it exports.
    val generateAll = project.tasks.register("generateDtoTs") { task ->
      task.group = "build"
      task.description =
        "Regenerates every committed TypeScript DTO binding this module exports from the Kotlin " +
          "@Serializable models. Run after changing an exported model or a root list, and commit."
    }
    val verifyAll = project.tasks.register("verifyDtoTs") { task ->
      task.group = "verification"
      task.description =
        "Verifies every committed TypeScript DTO binding this module exports matches a fresh " +
          "codegen. Fails with the regenerate command on drift. Wired into check."
    }

    ext.bindings.all { binding ->
      val suffix = binding.name.replaceFirstChar { it.uppercaseChar() }
      val generate = project.tasks.register("generateDtoTs$suffix", GenerateDtoTsTask::class.java) { task ->
        task.group = "build"
        task.description = "Regenerates the committed TypeScript bindings for `${binding.name}`."
        task.codegenClasspath.from(binding.codegenClasspath)
        task.generatorMainClass.set(binding.mainClass)
        task.outputFile.set(binding.generatedTsFile)
      }
      val verify = project.tasks.register("verifyDtoTs$suffix", VerifyDtoTsTask::class.java) { task ->
        task.group = "verification"
        task.description = "Byte-diffs the committed TypeScript bindings for `${binding.name}`."
        task.codegenClasspath.from(binding.codegenClasspath)
        task.generatorMainClass.set(binding.mainClass)
        task.committedFile.set(binding.generatedTsFile)
        // Per-binding, so two bindings in one module cannot overwrite each other's fresh output —
        // which would make one of them verify against the other's codegen.
        task.freshFile.set(
          project.layout.buildDirectory.file("dto-ts-codegen/fresh-${binding.name}-bindings.ts"),
        )
        task.regenerateTaskPath.set("${project.path}:generateDtoTs$suffix")
        // `check` runs verify but never generate, so this is ordering-only (NOT a dependency): if
        // both are scheduled in one build, verify runs after generate's write to the committed file.
        task.mustRunAfter(generate)
      }
      generateAll.configure { it.dependsOn(generate) }
      verifyAll.configure { it.dependsOn(verify) }
    }

    // A module that applies this plugin and registers nothing would get an aggregate `verifyDtoTs`
    // with no dependencies, so `check` would pass having verified no TypeScript at all — silently,
    // and exactly in the case worth catching (a `bindings.register` block dropped or mis-nested
    // during an edit). The single-generator shape this replaced failed loudly on an unset
    // `mainClass`; keep that property. Configuration time, not `check` time, so the mistake surfaces
    // on the next build of any kind rather than only under a verification task.
    project.afterEvaluate {
      if (ext.bindings.isEmpty()) {
        throw GradleException(
          "${project.path} applies the trailblaze.dto-ts-codegen plugin but registers no bindings, " +
            "so verifyDtoTs would verify nothing and check would pass with the TypeScript " +
            "freshness gate gone. Register at least one: trailblazeDtoTsCodegen { " +
            "bindings.register(\"<name>\") { mainClass.set(...); generatedTsFile.set(...) } }, or " +
            "remove the plugin.",
        )
      }
    }

    project.tasks.named("check") { it.dependsOn(verifyAll) }
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
