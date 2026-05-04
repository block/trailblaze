import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue
import org.gradle.testkit.runner.GradleRunner

/**
 * Plugin-level functional tests for `trailblaze.author-tool-bundle`. Runs the plugin against
 * isolated TestKit fixture projects and asserts on the surface only the plugin owns:
 *  - per-registration task naming + count,
 *  - `:build` dependency wiring,
 *  - `autoInstall = false` actually suppresses the auto-install task.
 *
 * Tests that actually invoke esbuild (i.e., produce a real `.bundle.js`) are not here —
 * esbuild + bun aren't reliably present in every dev/CI environment. The end-to-end
 * pipeline is exercised in `:trailblaze-quickjs-tools:jvmTest`'s `SampleAppToolsDemoTest`,
 * which depends on the plugin task and reads the produced bundle via system property.
 */
class TrailblazeAuthorToolBundlePluginFunctionalTest {

  private val tempDirs = mutableListOf<File>()

  @AfterTest
  fun cleanupTempDirs() {
    tempDirs.forEach { it.deleteRecursively() }
    tempDirs.clear()
  }

  @Test
  fun `register creates one bundle task per registration`() {
    // Note: this test only verifies that bundle tasks are registered with the expected names.
    // The actual `:build` → bundle dependency is asserted separately in the
    // `build task triggers bundle task as a transitive dependency` test below using
    // `--dry-run`.
    val projectDir = newFixtureProject(
      buildScript = """
        plugins {
          base
          id("trailblaze.author-tool-bundle")
        }
        trailblazeAuthorToolBundles {
          register("foo") {
            sourceDir.set(layout.projectDirectory.dir("foo-src"))
            autoInstall.set(false)
          }
          register("bar") {
            sourceDir.set(layout.projectDirectory.dir("bar-src"))
            autoInstall.set(false)
          }
        }
      """.trimIndent(),
    )
    val result = runner(projectDir, "tasks", "--all").build()
    assertTrue("expected bundleFooAuthorTool in task list:\n${result.output}") {
      result.output.contains("bundleFooAuthorTool")
    }
    assertTrue("expected bundleBarAuthorTool in task list:\n${result.output}") {
      result.output.contains("bundleBarAuthorTool")
    }
  }

  @Test
  fun `autoInstall false suppresses the install task`() {
    val projectDir = newFixtureProject(
      buildScript = """
        plugins {
          base
          id("trailblaze.author-tool-bundle")
        }
        trailblazeAuthorToolBundles {
          register("foo") {
            sourceDir.set(layout.projectDirectory.dir("foo-src"))
            autoInstall.set(false)
          }
        }
      """.trimIndent(),
    )
    val result = runner(projectDir, "tasks", "--all").build()
    assertTrue("expected bundleFooAuthorTool:\n${result.output}") {
      result.output.contains("bundleFooAuthorTool")
    }
    // The install task name is `install<Name>AuthorToolDeps`. With autoInstall=false the
    // plugin's afterEvaluate block must skip its registration entirely.
    assertTrue("install task should NOT be registered when autoInstall=false:\n${result.output}") {
      !result.output.contains("installFooAuthorToolDeps")
    }
  }

  @Test
  fun `bundle task fails with directed error when entry file is missing`() {
    // Pins the kdoc claim that the bundle task surfaces a "entry not found" error pointing
    // at the offending path. Doesn't need a real esbuild — the entry-file check fires before
    // ProcessBuilder runs, so we can omit `esbuildBinary` / `toolsSdkSrc` (the @InputFile
    // validation will catch *those* gaps if we tried to run; we point them at the source dir
    // so Gradle's input-snapshot pass succeeds and the task action runs).
    val projectDir = newFixtureProject(
      buildScript = """
        plugins {
          base
          id("trailblaze.author-tool-bundle")
        }
        // Stand-ins so the @InputFile validation passes — the real check we're exercising
        // happens inside the task action against `sourceDir/entryPoint`.
        val stubInput = layout.projectDirectory.file("foo-src/stub")
        trailblazeAuthorToolBundles {
          register("foo") {
            sourceDir.set(layout.projectDirectory.dir("foo-src"))
            entryPoint.set("does-not-exist.ts")
            esbuildBinary.set(stubInput)
            toolsSdkSrc.set(stubInput)
            autoInstall.set(false)
          }
        }
      """.trimIndent(),
    )
    File(projectDir, "foo-src").mkdirs()
    File(projectDir, "foo-src/stub").writeText("# placeholder so @InputFile validation passes")

    val result = runner(projectDir, "bundleFooAuthorTool").buildAndFail()
    assertTrue("expected entry-not-found error, got:\n${result.output}") {
      result.output.contains("entry not found at") &&
        result.output.contains("does-not-exist.ts")
    }
  }

  @Test
  fun `build task triggers bundle task as a transitive dependency`() {
    // Pins the `tasks.matching { it.name == "build" }.configureEach { dependsOn(bundleTask) }`
    // wiring in the plugin's apply block. Mirrors TrailblazeBundlePluginFunctionalTest's
    // equivalent assertion. Uses --dry-run so we don't actually invoke esbuild.
    val projectDir = newFixtureProject(
      buildScript = """
        plugins {
          base
          id("trailblaze.author-tool-bundle")
        }
        trailblazeAuthorToolBundles {
          register("foo") {
            sourceDir.set(layout.projectDirectory.dir("foo-src"))
            autoInstall.set(false)
          }
        }
      """.trimIndent(),
    )
    File(projectDir, "foo-src").mkdirs()

    val result = runner(projectDir, "build", "--dry-run").build()
    assertTrue("expected bundleFooAuthorTool to be in the :build task graph:\n${result.output}") {
      result.output.contains(":bundleFooAuthorTool")
    }
  }

  @Test
  fun `autoInstall true registers the install task`() {
    val projectDir = newFixtureProject(
      buildScript = """
        plugins {
          base
          id("trailblaze.author-tool-bundle")
        }
        trailblazeAuthorToolBundles {
          register("foo") {
            sourceDir.set(layout.projectDirectory.dir("foo-src"))
            // autoInstall defaults to true — leave unset to exercise the convention.
          }
        }
      """.trimIndent(),
    )
    val result = runner(projectDir, "tasks", "--all").build()
    assertTrue("expected installFooAuthorToolDeps in task list:\n${result.output}") {
      result.output.contains("installFooAuthorToolDeps")
    }
  }

  // ---- Fixtures ----

  private fun newFixtureProject(buildScript: String): File {
    val dir = createTempDirectory("trailblaze-author-tool-bundle-functional").toFile().also(tempDirs::add)
    File(dir, "settings.gradle.kts").writeText("""rootProject.name = "fixture"""")
    File(dir, "build.gradle.kts").writeText(buildScript)
    return dir
  }

  private fun runner(projectDir: File, vararg args: String): GradleRunner =
    GradleRunner.create()
      .withProjectDir(projectDir)
      .withArguments(*args)
      .withPluginClasspath()
      .forwardOutput()
}
