import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.tasks.JavaExec
import xyz.block.trailblaze.bundle.TrailblazeTrailmapBundler

/**
 * Wires up the per-trailmap TypeScript bindings generator (Tier 2 of the typesafe-tools work).
 *
 * The plugin registers one task — [BundleTrailblazeTrailmapTask] — that walks a trailmap root,
 * reads each trailmap's `target.tools:` list, and emits one
 * `<trailmapDir>/tools/.trailblaze/tools.d.ts` per trailmap that augments `TrailblazeToolMap`
 * (from `@trailblaze/scripting`) with that trailmap's scripted tools. Authors get autocomplete
 * on `client.tools.<toolName>(...)` call sites and on the args object the IDE shows at
 * hover, scoped to the trailmap they're editing in.
 *
 * **Per-trailmap output.** Each trailmap gets its own bindings file inside its own
 * `tools/.trailblaze/` dir, so the file travels with the trailmap when it's zipped or
 * published. Every trailmap — target or library — that declares scripted tools gets bindings;
 * trailmaps with no scripted tools are silently skipped.
 *
 * **The per-trailmap `tsconfig.json`** needs to opt the dotfile-prefixed dir back into its
 * include glob — TypeScript's default recursive expansion treats `.trailblaze/` as hidden
 * and skips it. Adopting trailmaps add a literal `.trailblaze` recursive include alongside
 * the existing `.ts` / `.js` glob entries; with that addition, both `tsc` and the IDE
 * pick up the generated bindings automatically. Keeping the dot prefix (rather than
 * renaming to a non-hidden dir) preserves the "sorted to top, signals tooling output"
 * property.
 *
 * **Gitignored output.** The bindings file is regenerated from the trailmap manifest on every
 * build; it is not source-of-truth and shouldn't drift in source control. The plugin does
 * not verify the file (unlike [TrailblazeBundledConfigPlugin]) — there's nothing to
 * verify because nothing is checked in.
 *
 * **Lazy input/output wiring.** [BundleTrailblazeTrailmapTask] declares its filtered input
 * file tree and its per-trailmap output directories as Providers derived from `trailmapsDir`.
 * The trailmap walk that computes the per-trailmap output dirs runs at task-up-to-date check
 * time, not at configuration time — keeps the configuration cache green and avoids
 * walking the trailmap root on every Gradle invocation.
 *
 * **Build wiring.** The generator is wired as a dependency of the project's `build` task
 * so bindings stay in sync with the manifest on every build. Authors who want to iterate
 * faster can run `./gradlew :<module>:bundleTrailblazeTrailmap` directly.
 */
class TrailblazeBundlePlugin : Plugin<Project> {
  override fun apply(project: Project) {
    val extension = project.extensions.create(
      "trailblazeBundle",
      TrailblazeBundleExtension::class.java,
    )

    // Defaults applied at extension-creation time so they're observable inside any later
    // `trailblazeBundle { ... }` block AND in the task configure closures below — no
    // `afterEvaluate` indirection needed. `workspaceRoot` derives from `trailmapsDir` via a
    // lazy `Provider` (`.map { it.dir("../../..") }`), so the path resolution happens at
    // task-execution time, not at apply time. Three parents because the canonical
    // workspace layout is `<workspace>/trails/config/trailmaps/` — `trailmaps/` → `config/` →
    // `trails/` → workspace root — and `WorkspaceCompileBootstrap.bootstrap()` discovers
    // a workspace by walking up looking for `trails/config/trailblaze.yaml`, so the
    // working directory it gets needs to start from that root.
    extension.bundleEnabled.convention(true)
    extension.workspaceRoot.convention(extension.trailmapsDir.map { it.dir("../../..") })

    val generate = project.tasks.register(
      GENERATE_TASK_NAME,
      BundleTrailblazeTrailmapTask::class.java,
    ) { task ->
      task.group = "trailblaze"
      task.description = "Generates per-trailmap TypeScript bindings (.d.ts) augmenting " +
        "TrailblazeToolMap with the scripted tools each trailmap declares."
      task.trailmapsDir.set(extension.trailmapsDir)
      // Honor the `bundleEnabled` toggle on the extension. Trailmaps that still ship pre-TS-
      // lockdown `.js` script descriptors (see `project_scripting_sdk_ts_only_authoring.md`)
      // can't run the bundler, but should still get `compileTrailblazeWorkspace` for
      // autocomplete — they set `trailblazeBundle { bundleEnabled.set(false) }` to disable
      // just this half of the plugin declaratively, rather than reaching into Gradle's task
      // graph with `tasks.named("bundleTrailblazeTrailmap") { enabled = false }`.
      task.onlyIf { extension.bundleEnabled.get() }

      // Input snapshot: only the YAML manifest files the bundler actually reads
      // (`trailmap.yaml` plus per-tool descriptor YAMLs referenced from `target.tools:`).
      // Limiting to `*.yaml`/`*.yml` skips both the bundler's own generated `.d.ts`
      // outputs (so they don't feed back into the next run's input snapshot, breaking
      // UP-TO-DATE) AND every author-side `.ts`/`.js` tool implementation, which the
      // bundler doesn't read — editing a tool's TypeScript source shouldn't invalidate
      // the bindings derived from its YAML descriptor.
      //
      // `project.provider { ... }` keeps the walk lazy AND handles the unconfigured-
      // `trailmapsDir` case (the directed-error path in the @TaskAction) by returning an
      // empty file collection instead of throwing at dependency-resolution time.
      task.trailmapManifestFiles.from(
        project.provider {
          if (extension.trailmapsDir.isPresent) {
            extension.trailmapsDir.get().asFileTree.matching { filter ->
              filter.include("**/*.yaml")
              filter.include("**/*.yml")
            }
          } else {
            project.files()
          }
        },
      )

      // Per-trailmap output dirs. The list is computed lazily — `project.provider` runs at
      // task-up-to-date check time, when Gradle resolves the @OutputDirectories provider.
      // The bundler's `discoverExpectedOutputDirs()` parses each trailmap.yaml to filter out
      // trailmaps with no scripted tools, so library trailmaps (no `target:` block) don't get a
      // declared output dir that Gradle would otherwise auto-create as empty. Falls back
      // to an empty list when `trailmapsDir` is unset so the directed-error path in the
      // @TaskAction can run.
      task.outputDirs.set(
        project.provider {
          if (extension.trailmapsDir.isPresent) {
            discoverOutputDirectories(extension.trailmapsDir.get())
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

    // ----------------------------------------------------------------------
    // Full workspace-compile task — wires `WorkspaceCompileBootstrap` into `check` so a
    // fresh `./gradlew build` materializes `<workspace>/.trailblaze/sdk/` and per-trailmap
    // `client.d.ts` / `tsconfig.json` artifacts that the IDE needs for autocomplete on
    // `import { ... } from '@trailblaze/scripting'`. Without this, a brand-new
    // contributor opens a `.ts` tool file and sees red squigglies until they remember to
    // run `./trailblaze compile` once. See #3210.
    //
    // **JavaExec vs. direct call.** `WorkspaceCompileBootstrap.bootstrap()` lives in the
    // main-build `:trailblaze-host` module, not in build-logic, so we can't call it
    // inline from this plugin's `apply`. A `JavaExec` against `:trailblaze-host`'s
    // runtime classpath gets us the same code path the daemon and the CLI use without
    // duplicating the wiring or pulling the host's transitive deps (Compose Desktop,
    // Skiko, Playwright) into the lean Gradle plugin classpath.
    //
    // **`workingDir` not argv.** `WorkspaceCompileBootstrap.bootstrap()` discovers the
    // workspace via `TrailblazeWorkspaceConfigResolver.resolve(Paths.get(""))`, which
    // walks up from the JVM's working directory. Setting `JavaExec.workingDir` to the
    // workspace root therefore reaches the same code path as a user running
    // `trailblaze compile` from the workspace; no extra `--workspace` arg plumbing.
    // ----------------------------------------------------------------------
    // `defaultDependencies { ... }` is Gradle's built-in "apply unless the consumer
    // configured one" hook for a Configuration — it runs at resolution time and only
    // contributes if no one else added dependencies first. Cleaner than an
    // `afterEvaluate` + `dependencies.isNotEmpty()` dance, and configuration-cache-safe.
    //
    // The `hostProject` lookup happens once at apply time and is reused for both the
    // default dep AND the `:check` wiring gate below — they MUST stay in sync (if the
    // wiring fires but the dep doesn't, the JavaExec runs with an empty classpath), so
    // routing through one variable makes the alignment automatic instead of relying on
    // two separate `findProject` calls returning the same answer. The guard keeps the
    // TestKit functional tests in `build-logic/` green — their isolated fixture projects
    // don't include `:trailblaze-host`, so both branches no-op.
    //
    // Follow-up tracked off #3210: `:trailblaze-host` is heavyweight (Compose Desktop,
    // Skiko, Playwright transitives). Extracting `WorkspaceCompileBootstrap` +
    // `WorkspaceCompileMain` into a leaner module would shrink the JavaExec classpath and
    // configuration phase. Out of scope for the onboarding fix; revisit when CI cycle
    // time on `./gradlew check` starts to bite.
    val hostProject = project.rootProject.findProject(":trailblaze-host")
    val compileClasspath = project.configurations.create("trailblazeWorkspaceCompileClasspath") { config ->
      config.isCanBeConsumed = false
      config.isCanBeResolved = true
      config.description = "Classpath for the WorkspaceCompileMain JavaExec task — pulls " +
        "`:trailblaze-host` so `WorkspaceCompileBootstrap.bootstrap()` is reachable."
      // `project.dependencies.project(...)` uses the `Map` overload because the Kotlin DSL
      // `project(":path")` extension isn't on the build-logic compile classpath (this
      // module doesn't `implementation(gradleKotlinDsl())`, intentionally — keeps the lean
      // plugin classpath). Same Dependency object, slightly more verbose call site.
      config.defaultDependencies { deps ->
        if (hostProject != null) {
          deps.add(project.dependencies.project(mapOf("path" to ":trailblaze-host")))
        }
      }
    }

    val compile = project.tasks.register(
      COMPILE_TASK_NAME,
      JavaExec::class.java,
    ) { task ->
      task.group = "trailblaze"
      task.description = "Runs the full trailblaze compile chain — workspace SDK " +
        "extraction, per-trailmap client.d.ts, per-trailmap tsconfig.json/.gitignore, and " +
        "TrailblazeCompiler — so IDE autocomplete on @trailblaze/scripting is alive " +
        "after a single ./gradlew build, without a manual `trailblaze compile` step."
      task.mainClass.set("xyz.block.trailblaze.host.WorkspaceCompileMain")
      task.classpath = compileClasspath
      // `ProcessForkOptions.setWorkingDir(Any)` accepts a `Provider`/`Callable` and
      // resolves it lazily at task-realization time via `project.file(...)`. The
      // directed-error branch needs to live INSIDE the provider — not in `doFirst` —
      // because `workingDir` is resolved during task graph wiring, before `doFirst` runs.
      // A `doFirst` guard against `workspaceRoot.isPresent` would be unreachable in the
      // both-unset case: Gradle's own provider resolver throws "Cannot query the value of
      // property 'workspaceRoot' because it has no value available" first, with a less
      // directed message than what we want to show the author.
      //
      // With `workspaceRoot.convention(trailmapsDir.map { ... })`, this `.orNull` returns the
      // convention value when trailmapsDir is set, the explicit value when workspaceRoot is
      // set, and null only when BOTH are unset — exactly the case we want to catch.
      task.setWorkingDir(
        project.provider {
          extension.workspaceRoot.orNull?.asFile ?: throw GradleException(
            "trailblaze.bundle: compileTrailblazeWorkspace needs `trailblazeBundle " +
              "{ workspaceRoot.set(...) }` or `trailblazeBundle { trailmapsDir.set(...) }`. " +
              "Neither is set — the JavaExec has no working directory to run against.",
          )
        },
      )

      // Classpath guard fires AFTER workingDir resolution but BEFORE the JVM spawn. The
      // empty-classpath case happens when the plugin is applied to a module outside the
      // multi-project build that has `:trailblaze-host` — `defaultDependencies` no-ops
      // and `task.classpath` resolves to an empty FileCollection. Without this guard,
      // the JVM starts and dies with `Could not find or load main class
      // xyz.block.trailblaze.host.WorkspaceCompileMain`, leaving the author with zero
      // signal about how to fix it.
      task.doFirst {
        if (task.classpath.isEmpty) {
          throw GradleException(
            "trailblaze.bundle: compileTrailblazeWorkspace ran with an empty classpath — " +
              "no `:trailblaze-host` in this build. Either include `:trailblaze-host` in " +
              "your settings.gradle.kts (the plugin auto-wires it via defaultDependencies " +
              "when present), or wire a classpath manually:\n" +
              "  dependencies { trailblazeWorkspaceCompileClasspath(project(\":your-host-module\")) }",
          )
        }
      }

      // Wire `installTrailblazeScriptingSdk` so the JavaExec sees a populated
      // SDK `node_modules/` (the `sdks/typescript/` source tree the install task
      // targets) before `ScriptedToolDefinitionAnalyzer` tries to spawn its Node
      // subprocess against `node_modules/ts-json-schema-generator`. When the install hasn't
      // run, `AnalyzerScriptedToolEnrichment.resolveFromEnvironment()` returns
      // null and meta-only / partial-descriptor trailmaps (the shape PR #3480
      // migrated `playwrightSample` and the ios-contacts tools to) fail
      // dependency resolution at bootstrap with the misleading "no `node` on
      // PATH" warning. The dependency is path-string-based (lazy) so this
      // plugin doesn't force the sibling project to configure synchronously —
      // same pattern `:trailblaze-quickjs-tools:bundleSampleAppTypedAuthorTool`
      // already uses. The `findProject` guard keeps the TestKit functional
      // tests in `build-logic/` green: their isolated fixture projects don't
      // include `:trailblaze-scripting-subprocess`, so the dependency is
      // skipped there too.
      if (project.rootProject.findProject(":trailblaze-scripting-subprocess") != null) {
        task.dependsOn(":trailblaze-scripting-subprocess:installTrailblazeScriptingSdk")
      }
    }

    // `:check` wiring gated on the same `hostProject` reference `defaultDependencies` uses
    // — see the comment up there for why these two must stay in sync.
    if (hostProject != null) {
      project.tasks.matching { it.name == "check" }.configureEach { it.dependsOn(compile) }
    }
  }

  /**
   * Resolve the lazy `Directory` instances for each trailmap that has scripted tools. The
   * walk + per-trailmap manifest parse is delegated to [TrailblazeTrailmapBundler] so the same
   * filtering logic (skip empty trailmaps, skip malformed manifests, no symlink follow) is
   * shared with `generate()`. Returns a list of `Directory` rooted at [trailmapsDirRoot] so
   * Gradle's `@OutputDirectories` can snapshot them with path-sensitive normalization.
   *
   * Workspace-level errors from [TrailblazeTrailmapBundler.discoverExpectedOutputDirs]
   * (duplicate trailmap id, malformed `trailmap.yaml`, `Files.walk` I/O failures) are caught and
   * translated to an empty list here. The `@TaskAction` will re-walk and surface the
   * underlying error through its existing translation path (which produces a directed
   * `GradleException` with the actionable message). Re-throwing here would surface as a
   * cryptic Provider failure during Gradle's task-graph wiring, not the message the
   * author needs to see.
   */
  private fun discoverOutputDirectories(trailmapsDirRoot: Directory): List<Directory> {
    val rootFile = trailmapsDirRoot.asFile
    if (!rootFile.isDirectory) return emptyList()
    val bundler = TrailblazeTrailmapBundler(trailmapsDir = rootFile)
    val rootPath = rootFile.toPath()
    val expected = try {
      bundler.discoverExpectedOutputDirs()
    } catch (_: RuntimeException) {
      // RuntimeException covers any of the bundler's `TrailblazeTrailmapBundleException`
      // subclasses plus `UncheckedIOException` from `Files.walk`. Let the @TaskAction's
      // error-translation produce the directed message.
      return emptyList()
    } catch (_: java.io.IOException) {
      return emptyList()
    }
    return expected.map { outDir ->
      val relative = rootPath.relativize(outDir.toPath()).toString()
      trailmapsDirRoot.dir(relative.replace(java.io.File.separatorChar, '/'))
    }
  }

  private companion object {
    const val GENERATE_TASK_NAME = "bundleTrailblazeTrailmap"
    const val COMPILE_TASK_NAME = "compileTrailblazeWorkspace"
  }
}
