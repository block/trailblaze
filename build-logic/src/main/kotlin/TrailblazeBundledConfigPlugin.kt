import java.io.File
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.PathSensitivity
import org.gradle.process.CommandLineArgumentProvider

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
      task.description = "Generates checked-in flat target YAMLs from authored trailmap manifests."
      task.trailmapsDir.set(extension.trailmapsDir)
      task.targetsDir.set(extension.targetsDir)
      task.scriptRootDir.set(extension.scriptRootDir)
      task.regenerateCommand.set(extension.regenerateCommand)
      task.analyzerToolsJson.set(extension.analyzerToolsJson)
    }

    val verify = project.tasks.register(
      VERIFY_TASK_NAME,
      VerifyBundledTrailblazeConfigTask::class.java,
    ) { task ->
      task.group = "verification"
      task.description = "Verifies checked-in generated target YAMLs are up to date with trailmap manifests."
      task.trailmapsDir.set(extension.trailmapsDir)
      task.targetsDir.set(extension.targetsDir)
      task.scriptRootDir.set(extension.scriptRootDir)
      task.regenerateCommand.set(extension.regenerateCommand)
      task.analyzerToolsJson.set(extension.analyzerToolsJson)
    }
    verify.configure { it.mustRunAfter(generate) }

    project.tasks.matching { it.name == "check" }.configureEach {
      it.dependsOn(verify)
    }

    // The generator writes directly into `src/commonMain/resources/trails/config/`,
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
    //
    // The analyzer JavaExec tasks (below) are also excluded by name: `generate` depends on
    // [ANALYZE_TASK_NAME] (it consumes its JSON), so wiring `dependsOn(generate)` onto them would
    // form a cycle; the determinism task is independent of generation entirely. Excluding by name
    // covers them even though they're created later in `afterEvaluate`.
    project.tasks.configureEach { task ->
      if (
        task.name != GENERATE_TASK_NAME &&
        task.name != VERIFY_TASK_NAME &&
        task.name != ANALYZE_TASK_NAME &&
        task.name != VERIFY_DETERMINISM_TASK_NAME
      ) {
        task.dependsOn(generate)
      }
    }

    wireAnalyzerStepWhenOptedIn(project, extension)
  }

  /**
   * Adds the bun-analyzer step that lets descriptor-less `.ts` tools be described from their
   * `trailblaze.tool<I,O>({...})` declaration — the SAME production resolution path the binary/CLI
   * uses (dogfooding). Mirrors `TrailblazeBundlePlugin`'s `compileTrailblazeWorkspace`: the analyzer
   * + enrichment live in `:trailblaze-host`, which build-logic's lean plugin classpath can't link,
   * so we fork a JVM (`JavaExec`) against `:trailblaze-host`'s runtime classpath.
   *
   * **Opt-in + lazy.** Gated on [TrailblazeBundledConfigExtension.analyzeDescriptorlessTools] (read
   * in `afterEvaluate`, after the consumer's `bundledTrailblazeConfig { }` block runs) AND on
   * `:trailblaze-host` being in the build. This is what keeps it cycle-safe: `:trailblaze-host`
   * depends on `:trailblaze-models`, so `:trailblaze-models` (which also applies this plugin) must
   * NOT pull `:trailblaze-host` — it simply doesn't opt in, so the host-classpath configuration is
   * never even created there. Compatible consumer modules opt in.
   */
  private fun wireAnalyzerStepWhenOptedIn(
    project: Project,
    extension: TrailblazeBundledConfigExtension,
  ) {
    project.afterEvaluate {
      if (!extension.analyzeDescriptorlessTools.getOrElse(false)) return@afterEvaluate
      val hostProject = project.rootProject.findProject(":trailblaze-host")
        ?: throw org.gradle.api.GradleException(
          "bundledTrailblazeConfig { analyzeDescriptorlessTools = true } requires `:trailblaze-host` " +
            "in the build (it carries the bun-backed scripted-tool analyzer).",
        )

      val analyzerClasspath =
        project.configurations.create("trailblazeBundledConfigAnalyzerClasspath") { config ->
          config.isCanBeConsumed = false
          config.isCanBeResolved = true
          config.description = "Classpath for BundledScriptedToolAnalyzeMain — pulls " +
            "`:trailblaze-host` so the bun-backed analyzer + enrichment are reachable from a JavaExec."
          config.defaultDependencies { deps ->
            // `Map` overload: the Kotlin DSL `project(":path")` extension isn't on the lean
            // build-logic compile classpath — same call shape `TrailblazeBundlePlugin` uses.
            deps.add(project.dependencies.project(mapOf("path" to ":trailblaze-host")))
          }
        }

      val analyzerOutput =
        project.layout.buildDirectory.file("bundled-scripted-tools/analyzer-tool-defs.json")
      // Feed the generator/verifier: they read this file for descriptor-less `.ts` tools.
      extension.analyzerToolsJson.set(analyzerOutput)

      val sdkDir = extension.sdkDir.orNull?.asFile
        ?: throw org.gradle.api.GradleException(
          "bundledTrailblazeConfig { analyzeDescriptorlessTools = true } requires `sdkDir` to point " +
            "at the @trailblaze/scripting SDK directory (carrying tools/extract-tool-defs.mjs + " +
            "node_modules/ts-json-schema-generator). Set it in the module's bundledTrailblazeConfig " +
            "block.",
        )
      val hasSdkInstall =
        project.rootProject.findProject(":trailblaze-scripting-subprocess") != null
      val repoRoot = project.rootProject.projectDir

      // The analyzer subprocess's output is a pure function of (the trailmap `.ts` sources, the
      // analyzer shim, and the resolved ts-json-schema-generator / TypeScript versions it runs
      // against). The `.ts` sources are tracked via `inputs.dir(trailmapsDir)` on each task below;
      // declare the analyzer TOOLCHAIN as inputs too so a shim edit or a deliberate SDK dep bump
      // invalidates the cached analyzer output. Without these, a `tools/extract-tool-defs.mjs`
      // change (or a generator bump) is served stale from a prior run's up-to-date output — caught
      // today only by the `verifyBundledScriptedToolDeterminism` re-run and by clean builds.
      //
      //   - `tools/`      — the analyzer shim (`extract-tool-defs.mjs` + its sibling helpers).
      //   - `bun.lock`    — the resolved generator / TypeScript versions. The installed
      //                     `node_modules/ts-json-schema-generator` tree is derived deterministically
      //                     from this lockfile (see CLAUDE.md, "Determinism comes from the committed
      //                     bun.lock"), so the small lockfile is the cheap, exact proxy for the
      //                     thousands-of-files install — tracking node_modules directly would hash
      //                     the whole dependency tree on every up-to-date check.
      //   - `package.json`— the declared direct deps; a version bump here is the human-visible half
      //                     of a lockfile change.
      //
      // The bun RUNTIME itself is intentionally NOT declared: it's resolved at execution time via
      // PATH-then-repo-`bin/`-walk-up (so its path isn't known at configuration time), it's
      // Hermit-pinned (a bump is a deliberate, reviewed `bin/` symlink change), and wiring a
      // repo-specific `bin/bun` path here would reintroduce exactly the layout coupling the
      // consumer-supplied `sdkDir` was designed to keep out of build-logic. A non-deterministic
      // runtime bump is backstopped by the determinism guard below (two cache-disabled passes).
      // All three are declared `.optional(true)` and `RELATIVE` for the SAME reasons, uniformly:
      //   - `.optional(true)` — a partially-populated SDK dir degrades to an empty fingerprint
      //     rather than a hard input-validation failure. A genuinely missing shim isn't masked: the
      //     bun subprocess fails loudly with a "can't find extract-tool-defs.mjs" diagnostic, which
      //     is the actionable error. (These three are committed SDK files, so absence means a broken
      //     checkout, not a normal state — but we'd rather fail at the analyzer than at fingerprinting.)
      //   - `RELATIVE` path sensitivity — keeps the fingerprint stable across checkout locations,
      //     matching the trailmaps input below. (These JavaExec tasks aren't `@CacheableTask`, so
      //     this is hygiene / future-proofing rather than a live build-cache concern, but declaring
      //     it uniformly avoids a confusing mixed-sensitivity reading on one task.)
      val analyzerShimDir = File(sdkDir, "tools")
      val analyzerSdkLockfile = File(sdkDir, "bun.lock")
      val analyzerSdkPackageJson = File(sdkDir, "package.json")
      val declareAnalyzerToolchainInputs: (JavaExec) -> Unit = { task ->
        task.inputs.dir(analyzerShimDir)
          .withPropertyName("analyzerShimDir")
          .withPathSensitivity(PathSensitivity.RELATIVE)
          .optional(true)
        task.inputs.file(analyzerSdkLockfile)
          .withPropertyName("analyzerSdkLockfile")
          .withPathSensitivity(PathSensitivity.RELATIVE)
          .optional(true)
        task.inputs.file(analyzerSdkPackageJson)
          .withPropertyName("analyzerSdkPackageJson")
          .withPathSensitivity(PathSensitivity.RELATIVE)
          .optional(true)
      }

      val analyze = project.tasks.register(ANALYZE_TASK_NAME, JavaExec::class.java) { task ->
        task.group = "trailblaze"
        task.description = "Derives InlineScriptToolConfigs for descriptor-less `.ts` tools via the " +
          "bun analyzer, for the bundled-config generator to consume."
        task.mainClass.set(ANALYZE_MAIN_CLASS)
        task.classpath = analyzerClasspath
        // Working dir = repo root so the analyzer's committed `bin/bun` walk-up resolves even when
        // the shell didn't `source bin/activate-hermit`. The SDK dir (extract-tool-defs.mjs +
        // node_modules) lives BELOW the repo root where the walk-up can't reach it, so pass it
        // explicitly via TRAILBLAZE_SDK_DIR.
        task.workingDir = repoRoot
        task.environment("TRAILBLAZE_SDK_DIR", sdkDir.absolutePath)
        task.inputs.dir(extension.trailmapsDir)
          .withPropertyName("trailmapsDir")
          .withPathSensitivity(PathSensitivity.RELATIVE)
        declareAnalyzerToolchainInputs(task)
        task.outputs.file(analyzerOutput)
        task.argumentProviders.add(
          CommandLineArgumentProvider {
            listOf(
              extension.trailmapsDir.get().asFile.absolutePath,
              analyzerOutput.get().asFile.absolutePath,
            )
          },
        )
        if (hasSdkInstall) {
          task.dependsOn(":trailblaze-scripting-subprocess:installTrailblazeScriptingSdk")
        }
      }

      // generate/verify read the analyzer JSON, so they must run after it.
      project.tasks.named(GENERATE_TASK_NAME).configure { it.dependsOn(analyze) }
      project.tasks.named(VERIFY_TASK_NAME).configure { it.dependsOn(analyze) }

      // Determinism guard wired into `check`. Runs the analyzer twice with its cache disabled
      // (`TRAILBLAZE_TOOL_ANALYZER_NO_CACHE=1`, so neither pass is served from cache) and asserts
      // byte-identical output — localizing a non-reproducible analyzer regression to the analyzer
      // itself, rather than letting it surface only as an opaque committed-target-YAML diff in the
      // existing `verifyBundledTrailblazeConfig` git-diff gate.
      val determinism =
        project.tasks.register(VERIFY_DETERMINISM_TASK_NAME, JavaExec::class.java) { task ->
          task.group = "verification"
          task.description = "Asserts the bun analyzer derives byte-identical scripted-tool configs " +
            "across two cache-disabled passes."
          task.mainClass.set(ANALYZE_MAIN_CLASS)
          task.classpath = analyzerClasspath
          task.workingDir = repoRoot
          task.environment("TRAILBLAZE_SDK_DIR", sdkDir.absolutePath)
          task.environment("TRAILBLAZE_TOOL_ANALYZER_NO_CACHE", "1")
          task.inputs.dir(extension.trailmapsDir)
            .withPropertyName("trailmapsDir")
            .withPathSensitivity(PathSensitivity.RELATIVE)
          declareAnalyzerToolchainInputs(task)
          task.argumentProviders.add(
            CommandLineArgumentProvider {
              listOf("--verify-determinism", extension.trailmapsDir.get().asFile.absolutePath)
            },
          )
          if (hasSdkInstall) {
            task.dependsOn(":trailblaze-scripting-subprocess:installTrailblazeScriptingSdk")
          }
        }
      project.tasks.matching { it.name == "check" }.configureEach { it.dependsOn(determinism) }
    }
  }

  private companion object {
    const val GENERATE_TASK_NAME = "generateBundledTrailblazeConfig"
    const val VERIFY_TASK_NAME = "verifyBundledTrailblazeConfig"
    const val ANALYZE_TASK_NAME = "analyzeBundledScriptedTools"
    const val VERIFY_DETERMINISM_TASK_NAME = "verifyBundledScriptedToolDeterminism"
    const val ANALYZE_MAIN_CLASS = "xyz.block.trailblaze.host.BundledScriptedToolAnalyzeMain"
  }
}
