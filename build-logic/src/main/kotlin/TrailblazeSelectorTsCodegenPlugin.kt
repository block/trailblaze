import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty

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

    val generate = project.tasks.register("generateSelectorsTs") { task ->
      task.group = "build"
      task.description =
        "Regenerates opensource/sdks/typescript/src/generated/selectors.ts from the " +
          "Kotlin source-of-truth files. Run after editing TrailblazeNodeSelector.kt, " +
          "MatchDescriptor.kt, or TrailblazeNode.Bounds, and commit the regenerated " +
          "file alongside the source change."

      declareInputs(task, ext, project)
      task.outputs.file(ext.generatedTsFile).withPropertyName("generatedSelectorsTs")

      task.doFirst { requireExtensionConfigured(ext) }
      task.doLast {
        val outputFile = ext.generatedTsFile.get().asFile
        val (a, b, c) = resolveInputs(ext)
        val rendered = runSelectorTsCodegen(a, b, c)
        outputFile.parentFile.mkdirs()
        outputFile.writeText(rendered, Charsets.UTF_8)
        // Path is relative to the repo root, not the consuming module's project dir,
        // because the committed `selectors.ts` lives in a sibling module
        // (`:opensource/sdks/typescript`). `relativeTo(project.projectDir)` would throw
        // for any output that escapes the module via `..` segments.
        task.logger.lifecycle(
          "Regenerated ${outputFile.relativeTo(project.rootDir)} " +
            "(${outputFile.length()} bytes). Commit it alongside the Kotlin source " +
            "change so CI's verifySelectorsTs gate stays green.",
        )
      }
    }

    val verify = project.tasks.register("verifySelectorsTs") { task ->
      task.group = "verification"
      task.description =
        "Verifies the committed opensource/sdks/typescript/src/generated/selectors.ts " +
          "matches a fresh codegen against the Kotlin source-of-truth files. Fails with " +
          "the regenerate command when they differ. Wired into :trailblaze-models:check."

      declareInputs(task, ext, project)
      // Treat the committed file as an INPUT (not an output) — verify reads it to
      // byte-diff against a fresh codegen; it never writes there. The conditional
      // collection pattern keeps the input declaration valid across "file exists" vs
      // "file missing" so Gradle doesn't surface a generic "input file does not exist"
      // error before the friendly doLast guard can fire with the regenerate command.
      //
      // No declared outputs by design: this task is a pure assertion (no artifacts to
      // produce), so it intentionally runs every invocation rather than relying on
      // UP-TO-DATE skipping. Cheap enough — pure JVM, three small source files; the cost of
      // always re-running is negligible compared to the simplicity gain.
      task.inputs.files(
        project.provider {
          if (!ext.generatedTsFile.isPresent) return@provider emptyList<java.io.File>()
          val committed = ext.generatedTsFile.get().asFile
          if (committed.exists()) listOf(committed) else emptyList()
        },
      ).withPropertyName("committedSelectorsTs")

      task.doFirst { requireExtensionConfigured(ext) }
      task.doLast {
        val committed = ext.generatedTsFile.get().asFile
        if (!committed.exists()) {
          throw GradleException(
            "Committed selectors.ts missing at ${committed.absolutePath}. Run " +
              "`./gradlew :trailblaze-models:generateSelectorsTs` and commit the result.",
          )
        }
        val (a, b, c) = resolveInputs(ext)
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
              "  git add ${committed.relativeTo(project.rootDir)} && " +
              "git commit -m \"chore(sdk): regenerate selectors.ts\"\n" +
              "After regenerating, also rerun " +
              "`./gradlew :trailblaze-models:bundleTrailblazeSdkDts` so the bundled " +
              "dist/index.d.ts picks up the change.\n" +
              "Committed size: ${committedText.length} chars\n" +
              "Regenerated size: ${regenerated.length} chars",
          )
        }
        task.logger.lifecycle(
          "✓ selectors.ts is fresh (${committedText.length} chars match).",
        )
      }
    }
    verify.configure { it.mustRunAfter(generate) }

    project.tasks.matching { it.name == "check" }.configureEach { task ->
      task.dependsOn(verify)
    }
  }
}

private fun requireExtensionConfigured(ext: TrailblazeSelectorTsCodegenExtension) {
  if (!ext.kotlinSourceDir.isPresent || !ext.generatedTsFile.isPresent) {
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
  ext: TrailblazeSelectorTsCodegenExtension,
): Triple<java.io.File, java.io.File, java.io.File> {
  val dir = ext.kotlinSourceDir.get().asFile
  return Triple(
    java.io.File(dir, "TrailblazeNodeSelector.kt"),
    java.io.File(dir, "MatchDescriptor.kt"),
    java.io.File(dir, "TrailblazeNode.kt"),
  )
}

private fun declareInputs(
  task: org.gradle.api.Task,
  ext: TrailblazeSelectorTsCodegenExtension,
  project: Project,
) {
  task.inputs.files(
    project.provider {
      if (!ext.kotlinSourceDir.isPresent) return@provider emptyList<java.io.File>()
      val (a, b, c) = resolveInputs(ext)
      listOf(a, b, c).filter { it.exists() }
    },
  ).withPropertyName("kotlinSourceOfTruth")
}
