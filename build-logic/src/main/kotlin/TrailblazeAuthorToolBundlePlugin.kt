import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Bundles author `tools.ts` files via esbuild for the `:trailblaze-quickjs-tools` runtime.
 * See devlog `2026-04-30-scripted-tools-not-mcp` for architectural rationale.
 *
 * ### Usage
 *
 * ```kotlin
 * plugins { id("trailblaze.author-tool-bundle") }
 *
 * trailblazeAuthorToolBundles {
 *   register("squareCardReader") {
 *     sourceDir.set(layout.projectDirectory.dir("trailblaze-config/quickjs-tools"))
 *     autoInstall.set(false) // when another task already manages this node_modules/
 *   }
 * }
 * ```
 */
class TrailblazeAuthorToolBundlePlugin : Plugin<Project> {
  override fun apply(project: Project) {
    val container = project.objects.domainObjectContainer(AuthorToolBundleSpec::class.java) { name ->
      project.objects.newInstance(AuthorToolBundleSpec::class.java, name)
    }
    project.extensions.create(
      "trailblazeAuthorToolBundles",
      TrailblazeAuthorToolBundlesExtension::class.java,
      container,
    )

    // Apply conventions and register the bundle task eagerly via `all`. The install task
    // can't be conditionally registered in `all` — Gradle invokes a container's `all`
    // callback BEFORE the per-registration configuration action runs, so reading
    // `spec.autoInstall.get()` here would always see the convention default (`true`) and
    // ignore a `register("foo") { autoInstall.set(false) }` override. The install task
    // registration is therefore deferred to `afterEvaluate`, by which point every
    // registration's configuration block has run and the final `autoInstall` value is
    // settled.
    container.all { spec ->
      spec.entryPoint.convention("tools.ts")
      spec.autoInstall.convention(true)
      project.defaultEsbuildBinary()?.let { defaultEsbuild ->
        spec.esbuildBinary.convention(
          project.layout.projectDirectory.file(defaultEsbuild.absolutePath),
        )
      }
      project.defaultToolsSdkSrc()?.let { defaultSdk ->
        spec.toolsSdkSrc.convention(
          project.layout.projectDirectory.file(defaultSdk.absolutePath),
        )
      }
      spec.outputFile.convention(
        project.layout.buildDirectory.file(
          "intermediates/trailblaze/author-tool-bundles/${spec.name}.bundle.js",
        ),
      )

      val capName = spec.name.replaceFirstChar { it.uppercase() }
      val bundleTaskName = "bundle${capName}AuthorTool"

      val bundleTask = project.tasks.register(bundleTaskName, BundleAuthorToolsTask::class.java) { task ->
        task.group = "trailblaze"
        task.description = "Bundles author tool source (`${spec.name}`) — TypeScript → single-file JavaScript."
        task.bundleName.set(spec.name)
        task.sourceDir.set(spec.sourceDir)
        task.entryPoint.set(spec.entryPoint)
        task.outputFile.set(spec.outputFile)
        task.esbuildBinary.set(spec.esbuildBinary)
        task.toolsSdkSrc.set(spec.toolsSdkSrc)
        // Snapshot only the author-managed files for up-to-date checks. Excludes the volatile
        // `node_modules/` populated by the install task (huge, would slow snapshotting and
        // produce spurious cache misses on every install) AND the install sentinel (an
        // implementation detail of `InstallAuthorToolDepsTask`). Includes lockfiles so that a
        // commit which updates `bun.lockb` / `package-lock.json` but leaves `package.json`
        // alone still triggers a rebuild — the install task would otherwise skip (its only
        // input is `package.json`) and bundling would run against a `node_modules/` that no
        // longer matches the checked-out lock state.
        task.inputSources.from(
          spec.sourceDir.map { srcDir ->
            srcDir.asFileTree.matching {
              it.include("**/*.ts", "**/*.js", "**/*.mjs", "**/*.cjs", "**/*.json")
              it.include("bun.lockb", "bun.lock", "package-lock.json", "yarn.lock", "pnpm-lock.yaml")
              it.exclude("node_modules/**", "**/.install-ok")
            }
          },
        )
      }

      // Same lazy `matching` configure that `TrailblazeBundlePlugin` uses — consumers
      // don't pay for it on builds that don't include `:build`.
      project.tasks.matching { it.name == "build" }.configureEach { it.dependsOn(bundleTask) }
    }

    // Register install tasks now that user configurators have run.
    project.afterEvaluate {
      container.forEach { spec ->
        if (!spec.autoInstall.get()) return@forEach
        val capName = spec.name.replaceFirstChar { it.uppercase() }
        val installTaskName = "install${capName}AuthorToolDeps"
        val installTask = project.tasks.register(installTaskName, InstallAuthorToolDepsTask::class.java) { task ->
          task.group = "trailblaze"
          task.description =
            "Installs npm deps (`bun install` with `npm install` fallback) for author tool bundle `${spec.name}`."
          task.bundleName.set(spec.name)
          task.packageJson.set(spec.sourceDir.file("package.json"))
          task.installSentinel.set(spec.sourceDir.file("node_modules/.install-ok"))
        }
        project.tasks.named("bundle${capName}AuthorTool").configure { it.dependsOn(installTask) }
      }
    }
  }
}
