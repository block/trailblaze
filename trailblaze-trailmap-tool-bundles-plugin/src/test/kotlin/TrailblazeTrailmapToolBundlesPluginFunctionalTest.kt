import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Assume.assumeTrue

/**
 * Plugin-level functional tests for `xyz.block.trailblaze.trailmap-tool-bundles` — runs the
 * plugin against isolated fixture projects via Gradle TestKit. Complements
 * [InProcessToolSourcesTest] and [SynthesizeInProcessScriptedToolWrapperTest] (which cover the
 * configuration-time discovery and wrapper-template synthesis as pure logic) by exercising the
 * surface only the plugin owns: per-tool task registration, the per-trailmap staging Copy
 * wiring, and the `sdkDir` extension property's resolver behavior.
 *
 * The end-to-end "bundle actually produces a runnable QuickJS file" path is covered separately
 * by `:trailblaze-quickjs-tools:jvmTest`'s `SampleAppToolsDemoTest` and the on-device
 * `QuickJsToolBundleOnDeviceTest`. These tests stay below that bar — they assume `assumeTrue`-
 * skip when the framework SDK install isn't present (esbuild missing), so they stay green even
 * in environments that don't carry the bun/esbuild toolchain.
 */
class TrailblazeTrailmapToolBundlesPluginFunctionalTest {

  private val tempDirs = mutableListOf<File>()

  @AfterTest
  fun cleanupTempDirs() {
    tempDirs.forEach { it.deleteRecursively() }
    tempDirs.clear()
  }

  // The SDK directory exposed via the `trailblaze.sdkDir` system property by the `test`
  // task wiring in `build.gradle.kts`. Tests that need esbuild on disk (the staging assertion
  // below) `assumeTrue` against this file's existence; tests that only care about task
  // registration / property propagation don't need esbuild and run unconditionally.
  private val frameworkSdkDir: File =
    File(System.getProperty("trailblaze.sdkDir") ?: ".")

  @Test
  fun `registers a bundle task per discovered tool and a sibling staging task`() {
    val projectDir = newFixtureProject(
      buildScript = """
        plugins {
          base
          id("xyz.block.trailblaze.trailmap-tool-bundles")
        }
        trailblazeTrailmapToolBundles {
          // Explicit sdkDir so the fixture's task registration doesn't depend on a
          // walk-up finding a real framework SDK above the TestKit temp dir.
          sdkDir.set(file("${frameworkSdkDir.absolutePath}"))
          trailmap(id = "p", toolsDir = file("tools"))
        }

        tasks.register("dumpRegistered") {
          doLast {
            val taskNames = tasks.matching {
              it.name.startsWith("bundleTrailmap") || it.name.startsWith("stageTrailmap")
            }.map { it.name }.sorted()
            println("REGISTERED=" + taskNames.joinToString(","))
          }
        }
      """.trimIndent(),
    )
    writeTool(projectDir, "fizz.ts", TYPED_TOOL_SOURCE)
    writeTool(projectDir, "buzz.ts", TYPED_TOOL_SOURCE)

    val result = runner(projectDir, "dumpRegistered").build()
    assertEquals(TaskOutcome.SUCCESS, result.task(":dumpRegistered")?.outcome)
    // Discovery is alphabetical; bundle + stage tasks are registered per tool.
    assertTrue("expected the per-tool bundle + stage tasks to be registered: ${result.output}") {
      result.output.contains(
        "REGISTERED=bundleTrailmapPBuzzToolBundle,bundleTrailmapPFizzToolBundle," +
          "stageTrailmapPBuzzToolBundleAsset,stageTrailmapPFizzToolBundleAsset",
      )
    }
  }

  @Test
  fun `sdkInstallTaskPath wires a dependsOn when set explicitly`() {
    val projectDir = newFixtureProject(
      buildScript = """
        plugins {
          base
          id("xyz.block.trailblaze.trailmap-tool-bundles")
        }

        tasks.register("stubSdkInstall") { doLast { println("STUB_INSTALL_RAN") } }

        trailblazeTrailmapToolBundles {
          sdkDir.set(file("${frameworkSdkDir.absolutePath}"))
          sdkInstallTaskPath.set(":stubSdkInstall")
          trailmap(id = "p", toolsDir = file("tools"))
        }

        tasks.register("dumpDeps") {
          doLast {
            val t = tasks.named("bundleTrailmapPOnlyToolBundle").get()
            println("DEPS=" + t.taskDependencies.getDependencies(t).map { it.path }.sorted())
          }
        }
      """.trimIndent(),
    )
    writeTool(projectDir, "only.ts", TYPED_TOOL_SOURCE)

    val result = runner(projectDir, "dumpDeps").build()
    assertEquals(TaskOutcome.SUCCESS, result.task(":dumpDeps")?.outcome)
    assertTrue("expected per-tool bundle task to dependsOn :stubSdkInstall: ${result.output}") {
      result.output.contains(":stubSdkInstall")
    }
  }

  @Test
  fun `staging task materializes the bundle at the on-device asset path`() {
    // Only runs when the framework SDK install is present — i.e. when this test executes from
    // the framework source tree. External-environment CI without esbuild on disk skips this
    // path; the task-registration tests above keep the wiring contract green either way.
    val esbuild = File(frameworkSdkDir, "node_modules/.bin/esbuild")
    assumeTrue(
      "esbuild not present at ${esbuild.absolutePath}; skipping end-to-end stage test.",
      esbuild.isFile,
    )

    val projectDir = newFixtureProject(
      buildScript = """
        plugins {
          base
          id("xyz.block.trailblaze.trailmap-tool-bundles")
        }
        trailblazeTrailmapToolBundles {
          sdkDir.set(file("${frameworkSdkDir.absolutePath}"))
          trailmap(id = "p", toolsDir = file("tools"))
        }
      """.trimIndent(),
    )
    writeTool(projectDir, "onlyTool.ts", TYPED_TOOL_SOURCE)

    val result = runner(projectDir, "stageTrailmapPOnlyToolToolBundleAsset").build()
    assertEquals(TaskOutcome.SUCCESS, result.task(":stageTrailmapPOnlyToolToolBundleAsset")?.outcome)

    val stagedBundle = File(
      projectDir,
      "build/intermediates/trailblaze/trailmap-tool-bundle-assets/" +
        "trails/config/trailmaps/p/tools/onlyTool.bundle.js",
    )
    assertTrue("expected staged bundle at $stagedBundle") { stagedBundle.isFile }
    assertTrue("staged bundle is empty: $stagedBundle") { stagedBundle.length() > 0 }
  }

  @Test
  fun `trailmap throws a directed error when toolsDir does not exist`() {
    val projectDir = newFixtureProject(
      buildScript = """
        plugins {
          base
          id("xyz.block.trailblaze.trailmap-tool-bundles")
        }
        trailblazeTrailmapToolBundles {
          sdkDir.set(file("${frameworkSdkDir.absolutePath}"))
          trailmap(id = "p", toolsDir = file("definitely-not-here"))
        }
      """.trimIndent(),
    )

    val result = runner(projectDir, "help").buildAndFail()
    assertTrue("expected directed error about missing tools dir: ${result.output}") {
      result.output.contains("trailblazeTrailmapToolBundles.trailmap(\"p\")") &&
        result.output.contains("tools directory not found")
    }
  }

  // ---- Fixtures ----

  private fun newFixtureProject(buildScript: String): File {
    val dir = createTempDirectory("trailblaze-trailmap-tool-bundles-functional").toFile()
      .also(tempDirs::add)
    File(dir, "settings.gradle.kts").writeText("""rootProject.name = "fixture"""")
    File(dir, "build.gradle.kts").writeText(buildScript)
    // TestKit fixture's per-trailmap tools dir.
    File(dir, "tools").mkdirs()
    return dir
  }

  private fun writeTool(projectDir: File, fileName: String, contents: String) {
    File(File(projectDir, "tools"), fileName).writeText(contents)
  }

  private fun runner(projectDir: File, vararg args: String): GradleRunner =
    GradleRunner.create()
      .withProjectDir(projectDir)
      .withArguments(*args)
      .withPluginClasspath()
      .forwardOutput()

  companion object {
    /**
     * A minimal in-process scripted-tool source. Has the inline `export const … = trailblaze.tool<…>(…)`
     * marker `inProcessToolSources` requires for descriptor-less discovery, with no sibling YAML.
     */
    private val TYPED_TOOL_SOURCE: String = """
      export const onlyTool = trailblaze.tool<{ message: string }>(
        { supportedPlatforms: ["android"] },
        async (input) => "ok",
      )
    """.trimIndent()
  }
}
