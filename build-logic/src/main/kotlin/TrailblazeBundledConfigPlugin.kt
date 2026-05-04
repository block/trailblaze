import org.gradle.api.Plugin
import org.gradle.api.Project

class TrailblazeBundledConfigPlugin : Plugin<Project> {
  override fun apply(project: Project) {
    val extension = project.extensions.create(
      "bundledTrailblazeConfig",
      TrailblazeBundledConfigExtension::class.java,
    )

    val generate = project.tasks.register(
      GENERATE_TASK_NAME,
      GenerateBundledTrailblazeConfigTask::class.java,
    ) { task ->
      task.group = "trailblaze"
      task.description = "Generates checked-in flat target YAMLs from authored pack manifests."
      task.packsDir.set(extension.packsDir)
      task.targetsDir.set(extension.targetsDir)
      task.regenerateCommand.set(extension.regenerateCommand)
    }

    val verify = project.tasks.register(
      VERIFY_TASK_NAME,
      VerifyBundledTrailblazeConfigTask::class.java,
    ) { task ->
      task.group = "verification"
      task.description = "Verifies checked-in generated target YAMLs are up to date with pack manifests."
      task.packsDir.set(extension.packsDir)
      task.targetsDir.set(extension.targetsDir)
      task.regenerateCommand.set(extension.regenerateCommand)
    }
    verify.configure { it.mustRunAfter(generate) }

    project.tasks.matching { it.name == "check" }.configureEach {
      it.dependsOn(verify)
    }

    // The generator writes directly into `src/commonMain/resources/trailblaze-config/`,
    // which the consuming module declares as both `resources.srcDirs` (Java resources)
    // AND `assets.srcDirs` (so on-device instrumentation tests can discover YAML via
    // AssetManager). Gradle 8.x's strict implicit-dependency validation fails any
    // task that reads that source directory without declaring a dependency on the
    // generator — and AGP creates many such per-variant tasks (`mergeDebugAssets`,
    // `processDebugJavaRes`, `generateDebugLintReportModel`, `lintAnalyzeDebug`,
    // `bundleDebugAar`, etc., one set per build variant × source set).
    //
    // Rather than enumerate every AGP task family by name (and chase new ones each
    // AGP version), wire `generate` as a dependency of every task in the project
    // except `generate` itself and the verifier (which `mustRunAfter` the generator
    // already). The generator is incremental and a no-op when up-to-date, so the
    // cost of over-wiring is negligible compared to a fragile per-name allowlist.
    project.tasks.configureEach { task ->
      if (task.name != GENERATE_TASK_NAME && task.name != VERIFY_TASK_NAME) {
        task.dependsOn(generate)
      }
    }
  }

  private companion object {
    const val GENERATE_TASK_NAME = "generateBundledTrailblazeConfig"
    const val VERIFY_TASK_NAME = "verifyBundledTrailblazeConfig"
  }
}
