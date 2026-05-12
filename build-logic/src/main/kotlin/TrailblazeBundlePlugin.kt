import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.Directory
import xyz.block.trailblaze.bundle.TrailblazePackBundler

/**
 * Wires up the per-pack TypeScript bindings generator (Tier 2 of the typesafe-tools work).
 *
 * The plugin registers one task — [BundleTrailblazePackTask] — that walks a pack root,
 * reads each pack's `target.tools:` list, and emits one
 * `<packDir>/tools/.trailblaze/tools.d.ts` per pack that augments `TrailblazeToolMap`
 * (from `@trailblaze/scripting`) with that pack's scripted tools. Authors get autocomplete
 * on `client.tools.<toolName>(...)` call sites and on the args object the IDE shows at
 * hover, scoped to the pack they're editing in.
 *
 * **Per-pack output.** Each pack gets its own bindings file inside its own
 * `tools/.trailblaze/` dir, so the file travels with the pack when it's zipped or
 * published. Every pack — target or library — that declares scripted tools gets bindings;
 * packs with no scripted tools are silently skipped.
 *
 * **The per-pack `tsconfig.json`** needs to opt the dotfile-prefixed dir back into its
 * include glob — TypeScript's default recursive expansion treats `.trailblaze/` as hidden
 * and skips it. Adopting packs add a literal `.trailblaze` recursive include alongside
 * the existing `.ts` / `.js` glob entries; with that addition, both `tsc` and the IDE
 * pick up the generated bindings automatically. Keeping the dot prefix (rather than
 * renaming to a non-hidden dir) preserves the "sorted to top, signals tooling output"
 * property.
 *
 * **Gitignored output.** The bindings file is regenerated from the pack manifest on every
 * build; it is not source-of-truth and shouldn't drift in source control. The plugin does
 * not verify the file (unlike [TrailblazeBundledConfigPlugin]) — there's nothing to
 * verify because nothing is checked in.
 *
 * **Lazy input/output wiring.** [BundleTrailblazePackTask] declares its filtered input
 * file tree and its per-pack output directories as Providers derived from `packsDir`.
 * The pack walk that computes the per-pack output dirs runs at task-up-to-date check
 * time, not at configuration time — keeps the configuration cache green and avoids
 * walking the pack root on every Gradle invocation.
 *
 * **Build wiring.** The generator is wired as a dependency of the project's `build` task
 * so bindings stay in sync with the manifest on every build. Authors who want to iterate
 * faster can run `./gradlew :<module>:bundleTrailblazePack` directly.
 */
class TrailblazeBundlePlugin : Plugin<Project> {
  override fun apply(project: Project) {
    val extension = project.extensions.create(
      "trailblazeBundle",
      TrailblazeBundleExtension::class.java,
    )

    val generate = project.tasks.register(
      GENERATE_TASK_NAME,
      BundleTrailblazePackTask::class.java,
    ) { task ->
      task.group = "trailblaze"
      task.description = "Generates per-pack TypeScript bindings (.d.ts) augmenting " +
        "TrailblazeToolMap with the scripted tools each pack declares."
      task.packsDir.set(extension.packsDir)

      // Input snapshot: only the YAML manifest files the bundler actually reads
      // (`pack.yaml` plus per-tool descriptor YAMLs referenced from `target.tools:`).
      // Limiting to `*.yaml`/`*.yml` skips both the bundler's own generated `.d.ts`
      // outputs (so they don't feed back into the next run's input snapshot, breaking
      // UP-TO-DATE) AND every author-side `.ts`/`.js` tool implementation, which the
      // bundler doesn't read — editing a tool's TypeScript source shouldn't invalidate
      // the bindings derived from its YAML descriptor.
      //
      // `project.provider { ... }` keeps the walk lazy AND handles the unconfigured-
      // `packsDir` case (the directed-error path in the @TaskAction) by returning an
      // empty file collection instead of throwing at dependency-resolution time.
      task.packManifestFiles.from(
        project.provider {
          if (extension.packsDir.isPresent) {
            extension.packsDir.get().asFileTree.matching { filter ->
              filter.include("**/*.yaml")
              filter.include("**/*.yml")
            }
          } else {
            project.files()
          }
        },
      )

      // Per-pack output dirs. The list is computed lazily — `project.provider` runs at
      // task-up-to-date check time, when Gradle resolves the @OutputDirectories provider.
      // The bundler's `discoverExpectedOutputDirs()` parses each pack.yaml to filter out
      // packs with no scripted tools, so library packs (no `target:` block) don't get a
      // declared output dir that Gradle would otherwise auto-create as empty. Falls back
      // to an empty list when `packsDir` is unset so the directed-error path in the
      // @TaskAction can run.
      task.outputDirs.set(
        project.provider {
          if (extension.packsDir.isPresent) {
            discoverOutputDirectories(extension.packsDir.get())
          } else {
            emptyList()
          }
        },
      )
    }

    // Run before `build` so a fresh checkout's first `./gradlew build` writes the .d.ts
    // before anything that might consume it. The generator is incremental (Gradle
    // up-to-date checks via the declared input/output tracking), so wiring as a build
    // dependency is cheap on no-change builds.
    project.tasks.matching { it.name == "build" }.configureEach { it.dependsOn(generate) }
  }

  /**
   * Resolve the lazy `Directory` instances for each pack that has scripted tools. The
   * walk + per-pack manifest parse is delegated to [TrailblazePackBundler] so the same
   * filtering logic (skip empty packs, skip malformed manifests, no symlink follow) is
   * shared with `generate()`. Returns a list of `Directory` rooted at [packsDirRoot] so
   * Gradle's `@OutputDirectories` can snapshot them with path-sensitive normalization.
   *
   * Workspace-level errors from [TrailblazePackBundler.discoverExpectedOutputDirs]
   * (duplicate pack id, malformed `pack.yaml`, `Files.walk` I/O failures) are caught and
   * translated to an empty list here. The `@TaskAction` will re-walk and surface the
   * underlying error through its existing translation path (which produces a directed
   * `GradleException` with the actionable message). Re-throwing here would surface as a
   * cryptic Provider failure during Gradle's task-graph wiring, not the message the
   * author needs to see.
   */
  private fun discoverOutputDirectories(packsDirRoot: Directory): List<Directory> {
    val rootFile = packsDirRoot.asFile
    if (!rootFile.isDirectory) return emptyList()
    val bundler = TrailblazePackBundler(packsDir = rootFile)
    val rootPath = rootFile.toPath()
    val expected = try {
      bundler.discoverExpectedOutputDirs()
    } catch (_: RuntimeException) {
      // RuntimeException covers any of the bundler's `TrailblazePackBundleException`
      // subclasses plus `UncheckedIOException` from `Files.walk`. Let the @TaskAction's
      // error-translation produce the directed message.
      return emptyList()
    } catch (_: java.io.IOException) {
      return emptyList()
    }
    return expected.map { outDir ->
      val relative = rootPath.relativize(outDir.toPath()).toString()
      packsDirRoot.dir(relative.replace(java.io.File.separatorChar, '/'))
    }
  }

  private companion object {
    const val GENERATE_TASK_NAME = "bundleTrailblazePack"
  }
}
