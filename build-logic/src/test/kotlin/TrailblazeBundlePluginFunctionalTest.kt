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
          toolsDir.set(layout.projectDirectory.dir("tools"))
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
    val bindings = File(projectDir, "tools/.trailblaze/tools.d.ts")
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
        // toolsDir set, packsDir omitted — should fail at task time, not at config time.
        trailblazeBundle {
          toolsDir.set(layout.projectDirectory.dir("tools"))
        }
      """.trimIndent(),
    )

    val result = runner(projectDir, "bundleTrailblazePack").buildAndFail()
    assertTrue("expected directed error in: ${result.output}") {
      result.output.contains("'packsDir' is not configured")
    }
  }

  @Test
  fun `task fails with directed error when toolsDir is not configured`() {
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
    // packsDir's @InputDirectory requires a real on-disk directory; create an empty one
    // so we exercise the toolsDir-missing path specifically (rather than tripping
    // Gradle's input-snapshot check first).
    File(projectDir, "packs").mkdirs()

    val result = runner(projectDir, "bundleTrailblazePack").buildAndFail()
    assertTrue("expected directed error in: ${result.output}") {
      result.output.contains("'toolsDir' is not configured")
    }
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
          toolsDir.set(layout.projectDirectory.dir("tools"))
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
