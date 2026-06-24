import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.IgnoreEmptyDirectories
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Extension wiring for the `trailblaze.selector-ts-codegen` plugin. Consumers point at
 * the Kotlin source root that contains the selector-grammar source-of-truth files and
 * the TypeScript SDK source directory where the generated `selectors.ts` lands. The
 * production wire-up in `:trailblaze-models` populates both; the unit test harness
 * exercises the codegen function directly without needing this extension.
 */
interface TrailblazeSelectorTsCodegenExtension {
  /**
   * Root directory containing the Kotlin source-of-truth files —
   * `src/commonMain/kotlin/xyz/block/trailblaze/api/` under the consumer module.
   * The plugin resolves `TrailblazeNodeSelector.kt`, `MatchDescriptor.kt`, and
   * `TrailblazeNode.kt` underneath it.
   */
  val kotlinSourceDir: DirectoryProperty

  /**
   * Path to the committed `opensource/sdks/typescript/src/generated/selectors.ts`
   * file the SDK's `src/index.ts` re-exports. The `generateSelectorsTs` task writes
   * here; the `verifySelectorsTs` task byte-diffs against this committed copy.
   */
  val generatedTsFile: RegularFileProperty
}

/**
 * Registers `generateSelectorsTs` (manual regenerate) and `verifySelectorsTs` (CI
 * freshness gate) for the selector-grammar TypeScript codegen described in the
 * 2026-05-22 "Kotlin canonical, TypeScript derived" devlog. Unlike the sibling SDK bundle
 * plugins ([TrailblazeSdkDtsBundlePlugin], [TrailblazeSdkBundlePlugin]) — whose outputs are
 * gitignored build artifacts regenerated each build — the generated `selectors.ts` is committed
 * source text consumed by the SDK bundle. Because the input is pure Kotlin source and the output
 * is hand-rolled TS (no Node tool subprocess), `verifySelectorsTs` is cheap enough to wire into
 * `:check` directly and keep the committed file honest.
 */
class TrailblazeSelectorTsCodegenPlugin : Plugin<Project> {
  override fun apply(project: Project) {
    val ext = project.extensions.create(
      "trailblazeSelectorTsCodegen",
      TrailblazeSelectorTsCodegenExtension::class.java,
    )

    val generate = project.tasks.register("generateSelectorsTs", GenerateSelectorsTsTask::class.java) { task ->
      task.group = "build"
      task.description =
        "Regenerates opensource/sdks/typescript/src/generated/selectors.ts from the " +
          "Kotlin source-of-truth files. Run after editing TrailblazeNodeSelector.kt, " +
          "MatchDescriptor.kt, or TrailblazeNode.Bounds, and commit the regenerated " +
          "file alongside the source change."

      task.kotlinSourceDir.set(ext.kotlinSourceDir)
      task.generatedTsFile.set(ext.generatedTsFile)
      task.rootDir.set(project.layout.projectDirectory)
    }

    val verify = project.tasks.register("verifySelectorsTs", VerifySelectorsTsTask::class.java) { task ->
      task.group = "verification"
      task.description =
        "Verifies the committed opensource/sdks/typescript/src/generated/selectors.ts " +
          "matches a fresh codegen against the Kotlin source-of-truth files. Fails with " +
          "the regenerate command when they differ. Wired into :trailblaze-models:check."

      task.kotlinSourceDir.set(ext.kotlinSourceDir)
      task.committedTsFile.set(ext.generatedTsFile)
      task.rootDir.set(project.layout.projectDirectory)
    }
    verify.configure { it.mustRunAfter(generate) }

    project.tasks.matching { it.name == "check" }.configureEach { task ->
      task.dependsOn(verify)
    }
  }
}

abstract class GenerateSelectorsTsTask : DefaultTask() {
  @get:InputDirectory
  @get:PathSensitive(PathSensitivity.RELATIVE)
  @get:IgnoreEmptyDirectories
  abstract val kotlinSourceDir: DirectoryProperty

  @get:OutputFile
  abstract val generatedTsFile: RegularFileProperty

  @get:Internal
  abstract val rootDir: DirectoryProperty

  @TaskAction
  fun generate() {
    requireExtensionConfigured(kotlinSourceDir.isPresent, generatedTsFile.isPresent)
    val outputFile = generatedTsFile.get().asFile
    val (a, b, c) = resolveInputs(kotlinSourceDir.get().asFile)
    val rendered = runSelectorTsCodegen(a, b, c)
    outputFile.parentFile.mkdirs()
    outputFile.writeText(rendered, Charsets.UTF_8)
    logger.lifecycle(
      "Regenerated ${outputFile.relativeToOrSelf(rootDir.get().asFile)} " +
        "(${outputFile.length()} bytes). Commit it alongside the Kotlin source " +
        "change so CI's verifySelectorsTs gate stays green.",
    )
  }
}

abstract class VerifySelectorsTsTask : DefaultTask() {
  @get:InputDirectory
  @get:PathSensitive(PathSensitivity.RELATIVE)
  @get:IgnoreEmptyDirectories
  abstract val kotlinSourceDir: DirectoryProperty

  @get:Optional
  @get:InputFile
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val committedTsFile: RegularFileProperty

  @get:Internal
  abstract val rootDir: DirectoryProperty

  @get:Input
  val alwaysRun: String = "verify"

  @TaskAction
  fun verify() {
    requireExtensionConfigured(kotlinSourceDir.isPresent, committedTsFile.isPresent)
    val committed = committedTsFile.get().asFile
    if (!committed.exists()) {
      throw GradleException(
        "Committed selectors.ts missing at ${committed.absolutePath}. Run " +
          "`./gradlew :trailblaze-models:generateSelectorsTs` and commit the result.",
      )
    }
    val (a, b, c) = resolveInputs(kotlinSourceDir.get().asFile)
    val regenerated = runSelectorTsCodegen(a, b, c)
    val committedText = committed.readText(Charsets.UTF_8)
    if (committedText != regenerated) {
      throw GradleException(
        "selectors.ts does not match a fresh codegen against the Kotlin source.\n" +
          "Likely causes:\n" +
          "  1. You edited TrailblazeNodeSelector.kt / MatchDescriptor.kt / " +
          "TrailblazeNode.Bounds without regenerating.\n" +
          "  2. You hand-edited the generated file — the codegen output is the " +
          "source of truth, not selectors.ts itself.\n" +
          "Regenerate and commit:\n" +
          "  ./gradlew :trailblaze-models:generateSelectorsTs\n" +
          "  git add ${committed.relativeToOrSelf(rootDir.get().asFile)} && " +
          "git commit -m \"chore(sdk): regenerate selectors.ts\"\n" +
          "After regenerating, also rerun " +
          "`./gradlew :trailblaze-models:bundleTrailblazeSdkDts` so the bundled " +
          "dist/index.d.ts picks up the change.\n" +
          "Committed size: ${committedText.length} chars\n" +
          "Regenerated size: ${regenerated.length} chars",
      )
    }
    logger.lifecycle(
      "✓ selectors.ts is fresh (${committedText.length} chars match).",
    )
  }
}

private fun requireExtensionConfigured(kotlinSourceDirPresent: Boolean, generatedTsFilePresent: Boolean) {
  if (!kotlinSourceDirPresent || !generatedTsFilePresent) {
    throw GradleException(
      "trailblaze.selector-ts-codegen: extension is not configured. Add to your build.gradle.kts:\n" +
        "  trailblazeSelectorTsCodegen {\n" +
        "    kotlinSourceDir.set(layout.projectDirectory.dir(\"src/commonMain/kotlin/xyz/block/trailblaze/api\"))\n" +
        "    generatedTsFile.set(layout.projectDirectory.file(\"../sdks/typescript/src/generated/selectors.ts\"))\n" +
        "  }",
    )
  }
}

private fun resolveInputs(
  dir: java.io.File,
): Triple<java.io.File, java.io.File, java.io.File> {
  return Triple(
    java.io.File(dir, "TrailblazeNodeSelector.kt"),
    java.io.File(dir, "MatchDescriptor.kt"),
    java.io.File(dir, "TrailblazeNode.kt"),
  )
}
