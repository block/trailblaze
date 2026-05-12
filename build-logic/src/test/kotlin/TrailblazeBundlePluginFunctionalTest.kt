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
 * fixture projects via Gradle TestKit. Complements [TrailblazePackBundlerTest] (which covers
 * the bundler's pure logic) by exercising the surface only the plugin owns: task
 * registration, the `build` task dependency wiring, extension-property validation paths,
 * and the symlink-skipping behavior of pack discovery.
 *
 * **Why TestKit and not unit tests for these?**
 * - The `tasks.matching { it.name == "build" }.configureEach { dependsOn(generate) }`
 *   wiring lives in the plugin's `apply` block. Unit tests can't observe it without
 *   instantiating a real Gradle project, and a regression there (e.g. someone changes the
 *   wired task name) would silently bypass binding generation.
 * - The `BundleTrailblazePackTask.generate()` extension-validation throws (`packsDir not
 *   configured` etc.) need a Gradle context to be triggered — they're meant to fire when a
 *   consumer applies the plugin without configuring the extension.
 * - Symlink-loop resilience in `discoverPackFiles` is a behavior, not a return value —
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
  fun `build task depends on bundleTrailblazePack so bindings are emitted on build`() {
    val projectDir = newFixtureProject(
      buildScript = """
        plugins {
          base
          id("trailblaze.bundle")
        }
        trailblazeBundle {
          packsDir.set(layout.projectDirectory.dir("packs"))
        }
      """.trimIndent(),
    )
    writePack(
      projectDir, packId = "p",
      packYaml = """
        id: p
        target:
          display_name: P
          tools:
            - tools/sayHi.yaml
      """.trimIndent(),
    )
    writeTool(
      projectDir, packId = "p", toolFile = "tools/sayHi.yaml",
      toolYaml = """
        script: ./tools/sayHi.js
        name: sayHi
        inputSchema:
          message: { type: string }
      """.trimIndent(),
    )

    val result = runner(projectDir, "build").build()

    // `:build` ran, and `:bundleTrailblazePack` ran as part of it — confirms the
    // configureEach { dependsOn(generate) } wiring in TrailblazeBundlePlugin.
    assertEquals(TaskOutcome.SUCCESS, result.task(":bundleTrailblazePack")?.outcome)
    assertTrue(":build was not executed in: ${result.tasks.map { it.path }}") {
      result.task(":build") != null
    }
    val bindings = File(projectDir, "packs/p/tools/.trailblaze/tools.d.ts")
    assertTrue("expected bindings file at $bindings") { bindings.isFile }
    assertTrue("bindings should declare sayHi: ${bindings.readText()}") {
      bindings.readText().contains("sayHi: {")
    }
  }

  @Test
  fun `task fails with directed error when packsDir is not configured`() {
    val projectDir = newFixtureProject(
      buildScript = """
        plugins {
          base
          id("trailblaze.bundle")
        }
        // packsDir omitted — should fail at task time, not at config time.
        trailblazeBundle {
        }
      """.trimIndent(),
    )

    val result = runner(projectDir, "bundleTrailblazePack").buildAndFail()
    assertTrue("expected directed error in: ${result.output}") {
      result.output.contains("'packsDir' is not configured")
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
          packsDir.set(layout.projectDirectory.dir("packs"))
        }
      """.trimIndent(),
    )
    writePack(
      projectDir, packId = "p",
      packYaml = """
        id: p
        target:
          display_name: P
          tools:
            - tools/sayHi.yaml
      """.trimIndent(),
    )
    writeTool(
      projectDir, packId = "p", toolFile = "tools/sayHi.yaml",
      toolYaml = """
        script: ./tools/sayHi.js
        name: sayHi
        inputSchema:
          message: { type: string }
      """.trimIndent(),
    )

    val first = runner(projectDir, "bundleTrailblazePack").build()
    assertEquals(TaskOutcome.SUCCESS, first.task(":bundleTrailblazePack")?.outcome)

    val second = runner(projectDir, "bundleTrailblazePack").build()
    assertEquals(
      TaskOutcome.UP_TO_DATE,
      second.task(":bundleTrailblazePack")?.outcome,
      "expected UP-TO-DATE on the second invocation; got ${second.output}",
    )
  }

  @Test
  fun `editing a tool descriptor regenerates that pack's bindings`() {
    val projectDir = newFixtureProject(
      buildScript = """
        plugins {
          base
          id("trailblaze.bundle")
        }
        trailblazeBundle {
          packsDir.set(layout.projectDirectory.dir("packs"))
        }
      """.trimIndent(),
    )
    writePack(
      projectDir, packId = "p",
      packYaml = """
        id: p
        target:
          display_name: P
          tools:
            - tools/sayHi.yaml
      """.trimIndent(),
    )
    writeTool(
      projectDir, packId = "p", toolFile = "tools/sayHi.yaml",
      toolYaml = """
        script: ./tools/sayHi.js
        name: sayHi
        inputSchema:
          message: { type: string }
      """.trimIndent(),
    )

    val first = runner(projectDir, "bundleTrailblazePack").build()
    assertEquals(TaskOutcome.SUCCESS, first.task(":bundleTrailblazePack")?.outcome)
    val bindings = File(projectDir, "packs/p/tools/.trailblaze/tools.d.ts")
    assertTrue("first run should have written bindings: $bindings") { bindings.isFile }
    val before = bindings.readText()

    // Change the tool descriptor — add a second param. The task should rerun (not UP-TO-DATE)
    // and the regenerated `.d.ts` should reflect the new shape.
    writeTool(
      projectDir, packId = "p", toolFile = "tools/sayHi.yaml",
      toolYaml = """
        script: ./tools/sayHi.js
        name: sayHi
        inputSchema:
          message: { type: string }
          times: { type: integer }
      """.trimIndent(),
    )

    val second = runner(projectDir, "bundleTrailblazePack").build()
    assertEquals(TaskOutcome.SUCCESS, second.task(":bundleTrailblazePack")?.outcome)
    val after = bindings.readText()
    assertTrue("expected regenerated bindings to declare 'times': $after") {
      after.contains("times: number;")
    }
    assertTrue("expected regenerated bindings to differ from first run") { after != before }
  }

  @Test
  fun `removing a pack cleans up its stale dts on the next run`() {
    // Rename / delete scenario: a pack's `pack.yaml` (and `.d.ts`) lives in source for
    // one build, then a developer removes the manifest. The bundler's orphan cleanup
    // pass (see TrailblazePackBundler.generate kdoc) deletes the stale `.d.ts` so it
    // doesn't feed into the next `tsc` pass as a phantom `TrailblazeToolMap` entry.
    val projectDir = newFixtureProject(
      buildScript = """
        plugins {
          base
          id("trailblaze.bundle")
        }
        trailblazeBundle {
          packsDir.set(layout.projectDirectory.dir("packs"))
        }
      """.trimIndent(),
    )
    writePack(
      projectDir, packId = "alpha",
      packYaml = """
        id: alpha
        target:
          display_name: Alpha
          tools:
            - tools/alphaTool.yaml
      """.trimIndent(),
    )
    writeTool(
      projectDir, packId = "alpha", toolFile = "tools/alphaTool.yaml",
      toolYaml = """
        script: ./tools/alphaTool.js
        name: alphaTool
      """.trimIndent(),
    )
    writePack(
      projectDir, packId = "beta",
      packYaml = """
        id: beta
        target:
          display_name: Beta
          tools:
            - tools/betaTool.yaml
      """.trimIndent(),
    )
    writeTool(
      projectDir, packId = "beta", toolFile = "tools/betaTool.yaml",
      toolYaml = """
        script: ./tools/betaTool.js
        name: betaTool
      """.trimIndent(),
    )

    val first = runner(projectDir, "bundleTrailblazePack").build()
    assertEquals(TaskOutcome.SUCCESS, first.task(":bundleTrailblazePack")?.outcome)
    val alphaBindings = File(projectDir, "packs/alpha/tools/.trailblaze/tools.d.ts")
    val betaBindings = File(projectDir, "packs/beta/tools/.trailblaze/tools.d.ts")
    assertTrue("alpha bindings should exist") { alphaBindings.isFile }
    assertTrue("beta bindings should exist") { betaBindings.isFile }

    // Remove the entire beta pack — simulates a developer deleting a pack from `packs/`.
    File(projectDir, "packs/beta").deleteRecursively()

    val second = runner(projectDir, "bundleTrailblazePack").build()
    assertEquals(TaskOutcome.SUCCESS, second.task(":bundleTrailblazePack")?.outcome)
    assertTrue("alpha bindings should still exist after removing beta") { alphaBindings.isFile }
    // beta dir is gone; the `.d.ts` went with it. Nothing further to verify there.

    // Now exercise the orphan-cleanup branch: bring beta back as an EMPTY pack (no
    // scripted tools). The previous `.d.ts` from when beta had `betaTool` would otherwise
    // remain and feed a stale `TrailblazeToolMap` augmentation into `tsc`.
    val betaDir = File(projectDir, "packs/beta").apply { mkdirs() }
    File(betaDir, "pack.yaml").writeText(
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

    val third = runner(projectDir, "bundleTrailblazePack").build()
    assertEquals(TaskOutcome.SUCCESS, third.task(":bundleTrailblazePack")?.outcome)
    assertTrue("stale beta bindings should have been cleaned up: ${staleBeta.exists()}") {
      !staleBeta.exists()
    }
  }

  @Test
  fun `library pack inheriting tools via dependencies gets bindings on build`() {
    // Plugin-level coverage of the dep-aware emission rule introduced upstream of this PR
    // (see TrailblazePackBundler kdoc: "Per-pack output, dep-aware aggregation"). A library
    // pack with empty own-tools but a `dependencies:` edge to a pack that DOES declare
    // tools must still get its own `tools.d.ts` written, and `discoverExpectedOutputDirs`
    // must declare that pack's output dir so Gradle's UP-TO-DATE check stays accurate.
    // Without this test, a regression that drops the closure walk from
    // `discoverExpectedOutputDirs` (declaring only packs with own tools) would land green.
    val projectDir = newFixtureProject(
      buildScript = """
        plugins {
          base
          id("trailblaze.bundle")
        }
        trailblazeBundle {
          packsDir.set(layout.projectDirectory.dir("packs"))
        }
      """.trimIndent(),
    )
    // `lib` declares the actual tool.
    writePack(
      projectDir, packId = "lib",
      packYaml = """
        id: lib
        target:
          display_name: Lib
          tools:
            - tools/libTool.yaml
      """.trimIndent(),
    )
    writeTool(
      projectDir, packId = "lib", toolFile = "tools/libTool.yaml",
      toolYaml = """
        script: ./tools/libTool.js
        name: libTool
        inputSchema:
          message: { type: string }
      """.trimIndent(),
    )
    // `consumer` has no own tools but depends on `lib`. Closure-aware bundler should still
    // emit bindings for it covering `libTool`.
    writePack(
      projectDir, packId = "consumer",
      packYaml = """
        id: consumer
        dependencies:
          - lib
      """.trimIndent(),
    )

    val first = runner(projectDir, "bundleTrailblazePack").build()
    assertEquals(TaskOutcome.SUCCESS, first.task(":bundleTrailblazePack")?.outcome)

    val consumerBindings = File(projectDir, "packs/consumer/tools/.trailblaze/tools.d.ts")
    assertTrue("consumer pack should receive bindings via inherited tools: $consumerBindings") {
      consumerBindings.isFile
    }
    assertTrue("consumer bindings should include libTool: ${consumerBindings.readText()}") {
      consumerBindings.readText().contains("libTool: {")
    }

    // UP-TO-DATE on re-run also exercises that `discoverExpectedOutputDirs` declared the
    // consumer's output dir — if it hadn't, Gradle would see a new output file appearing
    // and run the task again.
    val second = runner(projectDir, "bundleTrailblazePack").build()
    assertEquals(
      TaskOutcome.UP_TO_DATE,
      second.task(":bundleTrailblazePack")?.outcome,
      "expected UP-TO-DATE on the second invocation; got ${second.output}",
    )
  }

  @Test
  fun `discoverPackFiles does not follow symlinks (symlink loop does not wedge the task)`() {
    // Create a pack root, drop a real pack inside, then add a symlink BACK to the root
    // from a subdirectory. With FOLLOW_LINKS, walkTopDown would re-enter the root via the
    // symlink and either loop or duplicate the pack. With Files.walk default (no follow),
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
          packsDir.set(layout.projectDirectory.dir("packs"))
        }
      """.trimIndent(),
    )
    writePack(
      projectDir, packId = "real",
      packYaml = """
        id: real
        target:
          display_name: Real
          tools: []
      """.trimIndent(),
    )
    val packsRoot = File(projectDir, "packs").apply { mkdirs() }
    val cyclic = File(packsRoot, "cyclic-link").toPath()
    Files.createSymbolicLink(cyclic, packsRoot.toPath())

    val result = runner(projectDir, "bundleTrailblazePack").build()
    assertEquals(TaskOutcome.SUCCESS, result.task(":bundleTrailblazePack")?.outcome)
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

  private fun writePack(projectDir: File, packId: String, packYaml: String) {
    val dir = File(projectDir, "packs/$packId").apply { mkdirs() }
    File(dir, "pack.yaml").writeText(packYaml)
  }

  private fun writeTool(projectDir: File, packId: String, toolFile: String, toolYaml: String) {
    val out = File(File(projectDir, "packs/$packId"), toolFile)
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
