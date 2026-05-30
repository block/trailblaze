import java.io.File
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Assume.assumeTrue

/**
 * Plugin-level functional tests for `trailblaze.bundle` — runs the plugin against isolated
 * fixture projects via Gradle TestKit. Complements [TrailblazeTrailmapBundlerTest] (which covers
 * the bundler's pure logic) by exercising the surface only the plugin owns: task
 * registration, the `build` task dependency wiring, extension-property validation paths,
 * and the symlink-skipping behavior of trailmap discovery.
 *
 * **Why TestKit and not unit tests for these?**
 * - The `tasks.matching { it.name == "build" }.configureEach { dependsOn(generate) }`
 *   wiring lives in the plugin's `apply` block. Unit tests can't observe it without
 *   instantiating a real Gradle project, and a regression there (e.g. someone changes the
 *   wired task name) would silently bypass binding generation.
 * - The `BundleTrailblazeTrailmapTask.generate()` extension-validation throws (`trailmapsDir not
 *   configured` etc.) need a Gradle context to be triggered — they're meant to fire when a
 *   consumer applies the plugin without configuring the extension.
 * - Symlink-loop resilience in `discoverTrailmapFiles` is a behavior, not a return value —
 *   a unit test would have to construct symlinks anyway, and TestKit gives us a real
 *   filesystem fixture for free.
 */
class TrailblazeBundlePluginFunctionalTest {

  private val tempDirs = mutableListOf<File>()

  @AfterTest
  fun cleanupTempDirs() {
    tempDirs.forEach { it.deleteRecursively() }
    tempDirs.clear()
  }

  @Test
  fun `build task depends on bundleTrailblazeTrailmap so bindings are emitted on build`() {
    val projectDir = newFixtureProject(
      buildScript = """
        plugins {
          base
          id("trailblaze.bundle")
        }
        trailblazeBundle {
          trailmapsDir.set(layout.projectDirectory.dir("trailmaps"))
        }
      """.trimIndent(),
    )
    writeTrailmap(
      projectDir, trailmapId = "p",
      trailmapYaml = """
        id: p
        target:
          display_name: P
          tools:
            - sayHi
      """.trimIndent(),
    )
    writeTool(
      projectDir, trailmapId = "p", toolFile = "tools/sayHi.yaml",
      toolYaml = """
        script: ./tools/sayHi.ts
        name: sayHi
        inputSchema:
          message: { type: string }
      """.trimIndent(),
    )

    val result = runner(projectDir, "build").build()

    // `:build` ran, and `:bundleTrailblazeTrailmap` ran as part of it — confirms the
    // configureEach { dependsOn(generate) } wiring in TrailblazeBundlePlugin.
    assertEquals(TaskOutcome.SUCCESS, result.task(":bundleTrailblazeTrailmap")?.outcome)
    assertTrue(":build was not executed in: ${result.tasks.map { it.path }}") {
      result.task(":build") != null
    }
    val bindings = File(projectDir, "trailmaps/p/tools/.trailblaze/tools.d.ts")
    assertTrue("expected bindings file at $bindings") { bindings.isFile }
    assertTrue("bindings should declare sayHi: ${bindings.readText()}") {
      bindings.readText().contains("sayHi: {")
    }
  }

  @Test
  fun `task fails with directed error when trailmapsDir is not configured`() {
    val projectDir = newFixtureProject(
      buildScript = """
        plugins {
          base
          id("trailblaze.bundle")
        }
        // trailmapsDir omitted — should fail at task time, not at config time.
        trailblazeBundle {
        }
      """.trimIndent(),
    )

    val result = runner(projectDir, "bundleTrailblazeTrailmap").buildAndFail()
    assertTrue("expected directed error in: ${result.output}") {
      result.output.contains("'trailmapsDir' is not configured")
    }
  }

  @Test
  fun `second invocation with no input changes reports UP-TO-DATE`() {
    // Output tracking via @OutputDirectories gives Gradle a stable input/output snapshot
    // to compare against. Without it the task always ran. With the filtered input file
    // tree excluding `**/.trailblaze/**`, the generated `.d.ts` doesn't feed back into
    // the input snapshot on the second run, so Gradle reports UP-TO-DATE immediately.
    val projectDir = newFixtureProject(
      buildScript = """
        plugins {
          base
          id("trailblaze.bundle")
        }
        trailblazeBundle {
          trailmapsDir.set(layout.projectDirectory.dir("trailmaps"))
        }
      """.trimIndent(),
    )
    writeTrailmap(
      projectDir, trailmapId = "p",
      trailmapYaml = """
        id: p
        target:
          display_name: P
          tools:
            - sayHi
      """.trimIndent(),
    )
    writeTool(
      projectDir, trailmapId = "p", toolFile = "tools/sayHi.yaml",
      toolYaml = """
        script: ./tools/sayHi.ts
        name: sayHi
        inputSchema:
          message: { type: string }
      """.trimIndent(),
    )

    val first = runner(projectDir, "bundleTrailblazeTrailmap").build()
    assertEquals(TaskOutcome.SUCCESS, first.task(":bundleTrailblazeTrailmap")?.outcome)

    val second = runner(projectDir, "bundleTrailblazeTrailmap").build()
    assertEquals(
      TaskOutcome.UP_TO_DATE,
      second.task(":bundleTrailblazeTrailmap")?.outcome,
      "expected UP-TO-DATE on the second invocation; got ${second.output}",
    )
  }

  @Test
  fun `editing a tool descriptor regenerates that trailmap's bindings`() {
    val projectDir = newFixtureProject(
      buildScript = """
        plugins {
          base
          id("trailblaze.bundle")
        }
        trailblazeBundle {
          trailmapsDir.set(layout.projectDirectory.dir("trailmaps"))
        }
      """.trimIndent(),
    )
    writeTrailmap(
      projectDir, trailmapId = "p",
      trailmapYaml = """
        id: p
        target:
          display_name: P
          tools:
            - sayHi
      """.trimIndent(),
    )
    writeTool(
      projectDir, trailmapId = "p", toolFile = "tools/sayHi.yaml",
      toolYaml = """
        script: ./tools/sayHi.ts
        name: sayHi
        inputSchema:
          message: { type: string }
      """.trimIndent(),
    )

    val first = runner(projectDir, "bundleTrailblazeTrailmap").build()
    assertEquals(TaskOutcome.SUCCESS, first.task(":bundleTrailblazeTrailmap")?.outcome)
    val bindings = File(projectDir, "trailmaps/p/tools/.trailblaze/tools.d.ts")
    assertTrue("first run should have written bindings: $bindings") { bindings.isFile }
    val before = bindings.readText()

    // Change the tool descriptor — add a second param. The task should rerun (not UP-TO-DATE)
    // and the regenerated `.d.ts` should reflect the new shape.
    writeTool(
      projectDir, trailmapId = "p", toolFile = "tools/sayHi.yaml",
      toolYaml = """
        script: ./tools/sayHi.ts
        name: sayHi
        inputSchema:
          message: { type: string }
          times: { type: integer }
      """.trimIndent(),
    )

    val second = runner(projectDir, "bundleTrailblazeTrailmap").build()
    assertEquals(TaskOutcome.SUCCESS, second.task(":bundleTrailblazeTrailmap")?.outcome)
    val after = bindings.readText()
    assertTrue("expected regenerated bindings to declare 'times': $after") {
      after.contains("times: number;")
    }
    assertTrue("expected regenerated bindings to differ from first run") { after != before }
  }

  @Test
  fun `removing a trailmap cleans up its stale dts on the next run`() {
    // Rename / delete scenario: a trailmap's `trailmap.yaml` (and `.d.ts`) lives in source for
    // one build, then a developer removes the manifest. The bundler's orphan cleanup
    // pass (see TrailblazeTrailmapBundler.generate kdoc) deletes the stale `.d.ts` so it
    // doesn't feed into the next `tsc` pass as a phantom `TrailblazeToolMap` entry.
    val projectDir = newFixtureProject(
      buildScript = """
        plugins {
          base
          id("trailblaze.bundle")
        }
        trailblazeBundle {
          trailmapsDir.set(layout.projectDirectory.dir("trailmaps"))
        }
      """.trimIndent(),
    )
    writeTrailmap(
      projectDir, trailmapId = "alpha",
      trailmapYaml = """
        id: alpha
        target:
          display_name: Alpha
          tools:
            - alphaTool
      """.trimIndent(),
    )
    writeTool(
      projectDir, trailmapId = "alpha", toolFile = "tools/alphaTool.yaml",
      toolYaml = """
        script: ./tools/alphaTool.ts
        name: alphaTool
      """.trimIndent(),
    )
    writeTrailmap(
      projectDir, trailmapId = "beta",
      trailmapYaml = """
        id: beta
        target:
          display_name: Beta
          tools:
            - betaTool
      """.trimIndent(),
    )
    writeTool(
      projectDir, trailmapId = "beta", toolFile = "tools/betaTool.yaml",
      toolYaml = """
        script: ./tools/betaTool.ts
        name: betaTool
      """.trimIndent(),
    )

    val first = runner(projectDir, "bundleTrailblazeTrailmap").build()
    assertEquals(TaskOutcome.SUCCESS, first.task(":bundleTrailblazeTrailmap")?.outcome)
    val alphaBindings = File(projectDir, "trailmaps/alpha/tools/.trailblaze/tools.d.ts")
    val betaBindings = File(projectDir, "trailmaps/beta/tools/.trailblaze/tools.d.ts")
    assertTrue("alpha bindings should exist") { alphaBindings.isFile }
    assertTrue("beta bindings should exist") { betaBindings.isFile }

    // Remove the entire beta trailmap — simulates a developer deleting a trailmap from `trailmaps/`.
    File(projectDir, "trailmaps/beta").deleteRecursively()

    val second = runner(projectDir, "bundleTrailblazeTrailmap").build()
    assertEquals(TaskOutcome.SUCCESS, second.task(":bundleTrailblazeTrailmap")?.outcome)
    assertTrue("alpha bindings should still exist after removing beta") { alphaBindings.isFile }
    // beta dir is gone; the `.d.ts` went with it. Nothing further to verify there.

    // Now exercise the orphan-cleanup branch: bring beta back as an EMPTY trailmap (no
    // scripted tools). The previous `.d.ts` from when beta had `betaTool` would otherwise
    // remain and feed a stale `TrailblazeToolMap` augmentation into `tsc`.
    val betaDir = File(projectDir, "trailmaps/beta").apply { mkdirs() }
    File(betaDir, "trailmap.yaml").writeText(
      """
      id: beta
      target:
        display_name: Beta
        tools: []
      """.trimIndent(),
    )
    // Plant a stale `.d.ts` as if a previous run had written one when beta had tools.
    File(betaDir, "tools/.trailblaze").mkdirs()
    val staleBeta = File(betaDir, "tools/.trailblaze/tools.d.ts")
    staleBeta.writeText("// stale content from a previous configuration\nexport {};\n")

    val third = runner(projectDir, "bundleTrailblazeTrailmap").build()
    assertEquals(TaskOutcome.SUCCESS, third.task(":bundleTrailblazeTrailmap")?.outcome)
    assertTrue("stale beta bindings should have been cleaned up: ${staleBeta.exists()}") {
      !staleBeta.exists()
    }
  }

  @Test
  fun `library trailmap inheriting tools via dependencies gets bindings on build`() {
    // Plugin-level coverage of the dep-aware emission rule introduced upstream of this PR
    // (see TrailblazeTrailmapBundler kdoc: "Per-trailmap output, dep-aware aggregation"). A library
    // trailmap with empty own-tools but a `dependencies:` edge to a trailmap that DOES declare
    // tools must still get its own `tools.d.ts` written, and `discoverExpectedOutputDirs`
    // must declare that trailmap's output dir so Gradle's UP-TO-DATE check stays accurate.
    // Without this test, a regression that drops the closure walk from
    // `discoverExpectedOutputDirs` (declaring only trailmaps with own tools) would land green.
    val projectDir = newFixtureProject(
      buildScript = """
        plugins {
          base
          id("trailblaze.bundle")
        }
        trailblazeBundle {
          trailmapsDir.set(layout.projectDirectory.dir("trailmaps"))
        }
      """.trimIndent(),
    )
    // `lib` declares the actual tool.
    writeTrailmap(
      projectDir, trailmapId = "lib",
      trailmapYaml = """
        id: lib
        target:
          display_name: Lib
          tools:
            - libTool
      """.trimIndent(),
    )
    writeTool(
      projectDir, trailmapId = "lib", toolFile = "tools/libTool.yaml",
      toolYaml = """
        script: ./tools/libTool.ts
        name: libTool
        inputSchema:
          message: { type: string }
      """.trimIndent(),
    )
    // `consumer` has no own tools but depends on `lib`. Closure-aware bundler should still
    // emit bindings for it covering `libTool`.
    writeTrailmap(
      projectDir, trailmapId = "consumer",
      trailmapYaml = """
        id: consumer
        dependencies:
          - lib
      """.trimIndent(),
    )

    val first = runner(projectDir, "bundleTrailblazeTrailmap").build()
    assertEquals(TaskOutcome.SUCCESS, first.task(":bundleTrailblazeTrailmap")?.outcome)

    val consumerBindings = File(projectDir, "trailmaps/consumer/tools/.trailblaze/tools.d.ts")
    assertTrue("consumer trailmap should receive bindings via inherited tools: $consumerBindings") {
      consumerBindings.isFile
    }
    assertTrue("consumer bindings should include libTool: ${consumerBindings.readText()}") {
      consumerBindings.readText().contains("libTool: {")
    }

    // UP-TO-DATE on re-run also exercises that `discoverExpectedOutputDirs` declared the
    // consumer's output dir — if it hadn't, Gradle would see a new output file appearing
    // and run the task again.
    val second = runner(projectDir, "bundleTrailblazeTrailmap").build()
    assertEquals(
      TaskOutcome.UP_TO_DATE,
      second.task(":bundleTrailblazeTrailmap")?.outcome,
      "expected UP-TO-DATE on the second invocation; got ${second.output}",
    )
  }

  @Test
  fun `compileTrailblazeWorkspace task is registered with trailblaze group and the expected mainClass`() {
    // The new convention task wires `xyz.block.trailblaze.host.WorkspaceCompileMain` so a
    // fresh `./gradlew build` materializes per-trailmap `client.d.ts` + the workspace
    // `@trailblaze/scripting` SDK that IDE autocomplete depends on (#3210). The fixture
    // doesn't have `:trailblaze-host` available, but the task should still be REGISTERED
    // — its `:check` wiring is conditional on a non-empty compile classpath, but the task
    // itself is part of the plugin's contract.
    val projectDir = newFixtureProject(
      buildScript = """
        plugins {
          base
          id("trailblaze.bundle")
        }
        trailblazeBundle {
          trailmapsDir.set(layout.projectDirectory.dir("trailmaps"))
        }
        tasks.register("dumpCompileTask") {
          doLast {
            val t = tasks.named("compileTrailblazeWorkspace").get() as org.gradle.api.tasks.JavaExec
            println("MAIN_CLASS=" + t.mainClass.get())
            println("GROUP=" + t.group)
            println("DESCRIPTION_OK=" + (t.description?.contains("compile chain") == true))
          }
        }
      """.trimIndent(),
    )

    val result = runner(projectDir, "dumpCompileTask").build()
    assertEquals(TaskOutcome.SUCCESS, result.task(":dumpCompileTask")?.outcome)
    assertTrue("expected mainClass set: ${result.output}") {
      result.output.contains("MAIN_CLASS=xyz.block.trailblaze.host.WorkspaceCompileMain")
    }
    assertTrue("expected trailblaze group: ${result.output}") {
      result.output.contains("GROUP=trailblaze")
    }
    assertTrue("expected description set: ${result.output}") {
      result.output.contains("DESCRIPTION_OK=true")
    }
  }

  @Test
  fun `compileTrailblazeWorkspace dependsOn installTrailblazeScriptingSdk when the sibling project exists`() {
    // The convention plugin wires a `dependsOn` on
    // `:trailblaze-scripting-subprocess:installTrailblazeScriptingSdk` so the JavaExec
    // sees a populated SDK `node_modules/` (the `sdks/typescript/` source tree the
    // install task targets) before `ScriptedToolDefinitionAnalyzer` spawns its Node
    // subprocess. Without the dependency, a fresh `./gradlew check` on a clean checkout hits
    // `AnalyzerScriptedToolEnrichment.resolveFromEnvironment() == null` and trailmaps
    // authored with the partial-descriptor shape (the post-PR-#3480 layout in
    // `playwrightSample` and the ios-contacts tools) fail dependency resolution with a
    // misleading "no `node` on PATH" hint. The fixture inlines a stub
    // `:trailblaze-scripting-subprocess` carrying the task name only — production wiring
    // routes through that exact task path, so a `dependsOn` against a stub project with
    // the same task name reproduces the contract without dragging the real install task
    // into the TestKit fixture's `bun install` plumbing.
    val root = createTempDirectory("trailblaze-bundle-multiproject").toFile().also(tempDirs::add)
    File(root, "settings.gradle.kts").writeText(
      """
        rootProject.name = "fixture"
        include(":trailblaze-scripting-subprocess")
        include(":consumer")
      """.trimIndent(),
    )
    // Empty root build script — keeps Gradle from complaining about missing
    // build.gradle.kts at the root while ensuring the sibling project lookup
    // works through the includes above.
    File(root, "build.gradle.kts").writeText("")
    val stubProjectDir = File(root, "trailblaze-scripting-subprocess").apply { mkdirs() }
    File(stubProjectDir, "build.gradle.kts").writeText(
      """
        tasks.register("installTrailblazeScriptingSdk") {
          doLast { println("STUB_INSTALL_RAN") }
        }
      """.trimIndent(),
    )
    val consumerDir = File(root, "consumer").apply { mkdirs() }
    File(consumerDir, "build.gradle.kts").writeText(
      """
        plugins {
          base
          id("trailblaze.bundle")
        }
        trailblazeBundle {
          trailmapsDir.set(layout.projectDirectory.dir("trailmaps"))
        }
        tasks.register("dumpCompileDeps") {
          doLast {
            val t = tasks.named("compileTrailblazeWorkspace").get()
            println("DEPS=" + t.taskDependencies.getDependencies(t).map { it.path }.sorted())
          }
        }
      """.trimIndent(),
    )

    val result = runner(root, ":consumer:dumpCompileDeps").build()
    assertEquals(TaskOutcome.SUCCESS, result.task(":consumer:dumpCompileDeps")?.outcome)
    assertTrue("expected dependsOn the install task; got ${result.output}") {
      result.output.contains(":trailblaze-scripting-subprocess:installTrailblazeScriptingSdk")
    }
  }

  @Test
  fun `compileTrailblazeWorkspace skips the install-task dependency when the sibling project is absent`() {
    // Symmetric guard to the test above: in a multi-project build without
    // `:trailblaze-scripting-subprocess` (the TestKit fixture case + a future consumer
    // who carries the plugin without the SDK install plumbing), the wiring must no-op
    // rather than fail configuration. The `findProject(...) != null` gate is the same
    // pattern the existing `:check`-wiring uses against `:trailblaze-host`.
    val projectDir = newFixtureProject(
      buildScript = """
        plugins {
          base
          id("trailblaze.bundle")
        }
        trailblazeBundle {
          trailmapsDir.set(layout.projectDirectory.dir("trailmaps"))
        }
        tasks.register("dumpCompileDeps") {
          doLast {
            val t = tasks.named("compileTrailblazeWorkspace").get()
            println("DEPS=" + t.taskDependencies.getDependencies(t).map { it.path }.sorted())
          }
        }
      """.trimIndent(),
    )

    val result = runner(projectDir, "dumpCompileDeps").build()
    assertEquals(TaskOutcome.SUCCESS, result.task(":dumpCompileDeps")?.outcome)
    assertTrue("install task path must not appear when sibling project is absent: ${result.output}") {
      !result.output.contains("installTrailblazeScriptingSdk")
    }
  }

  @Test
  fun `compileTrailblazeWorkspace is NOT wired to check when no trailblaze-host project exists`() {
    // Fixture projects don't have a sibling `:trailblaze-host` in their multi-project
    // build, so the plugin's `afterEvaluate` default-deps wiring leaves the compile
    // classpath empty. With no classpath, running the JavaExec would fail with a
    // confusing "main class not found" error — so the plugin skips wiring it to `:check`
    // entirely. Verifies the guard rather than the inverse: if a future regression
    // started force-wiring `:check → compileTrailblazeWorkspace`, every TestKit fixture's
    // `:check` invocation in this file would start failing instead of staying green.
    val projectDir = newFixtureProject(
      buildScript = """
        plugins {
          base
          id("trailblaze.bundle")
        }
        trailblazeBundle {
          trailmapsDir.set(layout.projectDirectory.dir("trailmaps"))
        }
      """.trimIndent(),
    )

    val result = runner(projectDir, "check").build()
    // `:check` is a lifecycle task with no actions; its outcome can vary across Gradle
    // versions and task-graph shapes (UP_TO_DATE when no work, SUCCESS when it has any
    // dep that ran, etc.). The contract we actually care about here is "the new compile
    // task didn't sneak into the graph" — assert that directly, not the lifecycle task's
    // outcome enum.
    assertTrue("expected :check to have run: ${result.tasks.map { it.path }}") {
      result.task(":check") != null
    }
    assertTrue("compileTrailblazeWorkspace should not have run: ${result.tasks.map { it.path }}") {
      result.task(":compileTrailblazeWorkspace") == null
    }
  }

  @Test
  fun `workspaceRoot consumer override wins over the trailmapsDir-derived convention`() {
    // The plugin sets a `workspaceRoot` convention of `trailmapsDir.map { it.dir("../../..") }`
    // so the canonical `<workspace>/trails/config/trailmaps/` layout Just Works. But if a
    // consumer explicitly overrides `workspaceRoot`, the override must win — a regression
    // that drops the `.convention(...)` semantics (e.g. switching to `.set(...)` at
    // extension creation time) would silently stomp the override and the JavaExec would
    // run against the wrong working directory. Gradle's `Provider.convention` only sets
    // the default if no explicit value was set, so this test asserts that contract.
    val projectDir = newFixtureProject(
      buildScript = """
        plugins {
          base
          id("trailblaze.bundle")
        }
        trailblazeBundle {
          trailmapsDir.set(layout.projectDirectory.dir("trailmaps"))
          workspaceRoot.set(layout.projectDirectory.dir("custom-workspace"))
        }
        tasks.register("dumpWorkspaceRoot") {
          doLast {
            val t = tasks.named("compileTrailblazeWorkspace").get() as org.gradle.api.tasks.JavaExec
            println("WORKING_DIR=" + t.workingDir.canonicalPath)
          }
        }
      """.trimIndent(),
    )
    File(projectDir, "custom-workspace").mkdirs()

    val result = runner(projectDir, "dumpWorkspaceRoot").build()
    assertEquals(TaskOutcome.SUCCESS, result.task(":dumpWorkspaceRoot")?.outcome)
    val expected = File(projectDir, "custom-workspace").canonicalPath
    assertTrue("expected workingDir to honor the explicit workspaceRoot ($expected): ${result.output}") {
      result.output.contains("WORKING_DIR=$expected")
    }
  }

  @Test
  fun `bundleEnabled = false disables bundleTrailblazeTrailmap without disabling the compile half`() {
    // The declarative replacement for `tasks.named("bundleTrailblazeTrailmap") { enabled = false }`.
    // Trailmaps that still ship `.js` script descriptors (pre-TS-only-lockdown) toggle this
    // flag to keep the bundle half quiet while leaving the workspace-compile half
    // unaffected. A regression that ignores the flag or wires it to the wrong task would
    // surface as the bundle still running on a `.js`-bearing trailmap, blowing up CI.
    val projectDir = newFixtureProject(
      buildScript = """
        plugins {
          base
          id("trailblaze.bundle")
        }
        trailblazeBundle {
          trailmapsDir.set(layout.projectDirectory.dir("trailmaps"))
          bundleEnabled.set(false)
        }
      """.trimIndent(),
    )
    writeTrailmap(
      projectDir, trailmapId = "p",
      trailmapYaml = """
        id: p
        target:
          display_name: P
          tools:
            - sayHi
      """.trimIndent(),
    )
    writeTool(
      projectDir, trailmapId = "p", toolFile = "tools/sayHi.yaml",
      toolYaml = """
        script: ./tools/sayHi.ts
        name: sayHi
      """.trimIndent(),
    )

    val result = runner(projectDir, "bundleTrailblazeTrailmap").build()
    // `onlyIf` should short-circuit the task — Gradle reports SKIPPED, not SUCCESS.
    assertEquals(TaskOutcome.SKIPPED, result.task(":bundleTrailblazeTrailmap")?.outcome)
    val bindings = File(projectDir, "trailmaps/p/tools/.trailblaze/tools.d.ts")
    assertTrue("bindings should NOT have been written: $bindings") { !bindings.exists() }
  }

  @Test
  fun `bundleEnabled = true (the default) runs bundleTrailblazeTrailmap and writes bindings`() {
    // Inverse pair of the `bundleEnabled = false` test above. Without this, a regression
    // that always skips the task — say, an `onlyIf { false }` typo or an unrelated `onlyIf`
    // condition that happens to evaluate false — would still pass the SKIPPED assertion
    // there. Asserting the SUCCESS path here pins the positive direction.
    val projectDir = newFixtureProject(
      buildScript = """
        plugins {
          base
          id("trailblaze.bundle")
        }
        trailblazeBundle {
          trailmapsDir.set(layout.projectDirectory.dir("trailmaps"))
          // bundleEnabled left at the convention default (true).
        }
      """.trimIndent(),
    )
    writeTrailmap(
      projectDir, trailmapId = "p",
      trailmapYaml = """
        id: p
        target:
          display_name: P
          tools:
            - sayHi
      """.trimIndent(),
    )
    writeTool(
      projectDir, trailmapId = "p", toolFile = "tools/sayHi.yaml",
      toolYaml = """
        script: ./tools/sayHi.ts
        name: sayHi
        inputSchema:
          message: { type: string }
      """.trimIndent(),
    )

    val result = runner(projectDir, "bundleTrailblazeTrailmap").build()
    assertEquals(TaskOutcome.SUCCESS, result.task(":bundleTrailblazeTrailmap")?.outcome)
    val bindings = File(projectDir, "trailmaps/p/tools/.trailblaze/tools.d.ts")
    assertTrue("bindings file should exist after default-enabled run: $bindings") { bindings.isFile }
    assertTrue("bindings should declare sayHi: ${bindings.readText()}") {
      bindings.readText().contains("sayHi: {")
    }
  }

  @Test
  fun `compileTrailblazeWorkspace doFirst surfaces a directed error on empty classpath`() {
    // The plugin's `doFirst` guard is the only thing between a misconfigured consumer and
    // a cryptic "Could not find or load main class" page from the JVM. TestKit fixtures
    // never include `:trailblaze-host`, so `defaultDependencies` leaves the classpath
    // empty AND the `:check` wiring is skipped — but manually invoking the task is exactly
    // the path an author hits when they apply the plugin to a module outside the wired
    // multi-project build. This test simulates that and asserts the directed message.
    val projectDir = newFixtureProject(
      buildScript = """
        plugins {
          base
          id("trailblaze.bundle")
        }
        trailblazeBundle {
          trailmapsDir.set(layout.projectDirectory.dir("trailmaps"))
        }
      """.trimIndent(),
    )

    val result = runner(projectDir, "compileTrailblazeWorkspace").buildAndFail()
    assertTrue("expected the directed classpath error message: ${result.output}") {
      result.output.contains("no `:trailblaze-host` in this build")
    }
  }

  @Test
  fun `compileTrailblazeWorkspace doFirst surfaces a directed error when both roots are unset`() {
    // The first guard — neither `trailmapsDir` nor `workspaceRoot` is set. Since the
    // convention is `workspaceRoot.convention(trailmapsDir.map { ... })`, both being unset is
    // the only state in which `workspaceRoot.isPresent` is false. Stub the classpath via
    // a dummy local file so the empty-classpath guard doesn't trigger first and mask the
    // intended assertion path.
    val projectDir = newFixtureProject(
      buildScript = """
        plugins {
          base
          id("trailblaze.bundle")
        }
        dependencies {
          trailblazeWorkspaceCompileClasspath(files("dummy.jar"))
        }
        // No `trailblazeBundle { ... }` — trailmapsDir and workspaceRoot both unset.
      """.trimIndent(),
    )
    File(projectDir, "dummy.jar").writeText("")

    val result = runner(projectDir, "compileTrailblazeWorkspace").buildAndFail()
    assertTrue("expected the directed missing-roots error message: ${result.output}") {
      result.output.contains("compileTrailblazeWorkspace needs `trailblazeBundle")
    }
  }

  @Test
  fun `discoverTrailmapFiles does not follow symlinks (symlink loop does not wedge the task)`() {
    // Create a trailmap root, drop a real trailmap inside, then add a symlink BACK to the root
    // from a subdirectory. With FOLLOW_LINKS, walkTopDown would re-enter the root via the
    // symlink and either loop or duplicate the trailmap. With Files.walk default (no follow),
    // the walk skips the symlink target and the task succeeds without duplication.
    //
    // `Assume.assumeTrue` (rather than an early `return`) so the test runner reports the
    // environment as SKIPPED, not falsely PASSED — matters for CI dashboards that need to
    // distinguish "ran and verified" from "filesystem doesn't support symlinks here."
    assumeTrue(
      "filesystem does not support symlink creation (Windows without elevated perms?) — skipping",
      supportsSymlinks(),
    )
    val projectDir = newFixtureProject(
      buildScript = """
        plugins {
          base
          id("trailblaze.bundle")
        }
        trailblazeBundle {
          trailmapsDir.set(layout.projectDirectory.dir("trailmaps"))
        }
      """.trimIndent(),
    )
    writeTrailmap(
      projectDir, trailmapId = "real",
      trailmapYaml = """
        id: real
        target:
          display_name: Real
          tools: []
      """.trimIndent(),
    )
    val trailmapsRoot = File(projectDir, "trailmaps").apply { mkdirs() }
    val cyclic = File(trailmapsRoot, "cyclic-link").toPath()
    Files.createSymbolicLink(cyclic, trailmapsRoot.toPath())

    val result = runner(projectDir, "bundleTrailblazeTrailmap").build()
    assertEquals(TaskOutcome.SUCCESS, result.task(":bundleTrailblazeTrailmap")?.outcome)
  }

  // ---- Fixtures ----

  private fun newFixtureProject(buildScript: String): File {
    val dir = createTempDirectory("trailblaze-bundle-functional").toFile().also(tempDirs::add)
    File(dir, "settings.gradle.kts").writeText(
      // settings.gradle for the fixture — the `withPluginClasspath()` runner injection
      // makes the under-test classpath available at apply time, so we don't need a
      // pluginManagement block here.
      """rootProject.name = "fixture"""",
    )
    File(dir, "build.gradle.kts").writeText(buildScript)
    return dir
  }

  private fun writeTrailmap(projectDir: File, trailmapId: String, trailmapYaml: String) {
    val dir = File(projectDir, "trailmaps/$trailmapId").apply { mkdirs() }
    File(dir, "trailmap.yaml").writeText(trailmapYaml)
  }

  private fun writeTool(projectDir: File, trailmapId: String, toolFile: String, toolYaml: String) {
    val out = File(File(projectDir, "trailmaps/$trailmapId"), toolFile)
    out.parentFile.mkdirs()
    out.writeText(toolYaml)
  }

  private fun runner(projectDir: File, vararg args: String): GradleRunner =
    GradleRunner.create()
      .withProjectDir(projectDir)
      .withArguments(*args)
      .withPluginClasspath()
      .forwardOutput()

  private fun supportsSymlinks(): Boolean = try {
    val probeDir = createTempDirectory("symlink-probe").toFile().also(tempDirs::add)
    val target = File(probeDir, "target").apply { writeText("x") }
    val link = File(probeDir, "link").toPath()
    Files.createSymbolicLink(link, target.toPath())
    true
  } catch (_: UnsupportedOperationException) {
    false
  } catch (_: java.io.IOException) {
    // FilesystemException / AccessDenied on restricted environments — same outcome as
    // unsupported, treat as "skip."
    false
  }
}
