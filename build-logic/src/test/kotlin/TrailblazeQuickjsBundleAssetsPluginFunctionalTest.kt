import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.gradle.testkit.runner.GradleRunner

/**
 * Plugin-level functional tests for `trailblaze.quickjs-bundle-assets`. The plugin only owns
 * staging already-produced bundles into a stable asset tree; real esbuild coverage lives with the
 * author-tool bundler and the sample-app task that produces the bundle.
 */
class TrailblazeQuickjsBundleAssetsPluginFunctionalTest {

  private val tempDirs = mutableListOf<File>()

  @AfterTest
  fun cleanupTempDirs() {
    tempDirs.forEach { it.deleteRecursively() }
    tempDirs.clear()
  }

  @Test
  fun `staging task runs producer and copies its output to configured asset path`() {
    val projectDir = newFixtureProject(
      buildScript = """
        plugins {
          base
          id("trailblaze.quickjs-bundle-assets")
        }

        tasks.register<Copy>("produceFooBundle") {
          from(layout.projectDirectory.file("producer-fixture/generated.bundle.js"))
          into(layout.buildDirectory.dir("producer-output"))
        }

        trailblazeQuickjsBundleAssets {
          register("foo") {
            bundleTask.set(tasks.named("produceFooBundle"))
            assetPath.set("fixtures/quickjs/foo.bundle.js")
          }
        }
      """.trimIndent(),
    )
    File(projectDir, "producer-fixture").mkdirs()
    File(projectDir, "producer-fixture/generated.bundle.js").writeText("globalThis.foo = 1;")

    val result = runner(
      projectDir,
      "stageFooQuickjsBundleAsset",
      "--configuration-cache",
      "--configuration-cache-problems=fail",
    ).build()

    assertTrue("expected producer task to run before staging:\n${result.output}") {
      result.output.contains(":produceFooBundle")
    }
    assertEquals(
      "globalThis.foo = 1;",
      File(
        projectDir,
        "build/intermediates/staged-quickjs-bundle-assets/fixtures/quickjs/foo.bundle.js",
      ).readText(),
    )
  }

  private fun newFixtureProject(buildScript: String): File {
    val dir = createTempDirectory("trailblaze-quickjs-bundle-assets-functional").toFile().also(tempDirs::add)
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
