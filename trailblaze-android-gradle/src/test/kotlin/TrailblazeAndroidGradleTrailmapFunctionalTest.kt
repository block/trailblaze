import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Assume.assumeTrue

/**
 * Functional tests for the `trailblazeAndroid { trailmap { ... } }` scripted-tool bundling —
 * ported from the retired standalone `xyz.block.trailblaze.trailmap-tool-bundles` plugin.
 * Complements [InProcessToolSourcesTest] and [SynthesizeInProcessScriptedToolWrapperTest] (pure
 * discovery/synthesis logic) by exercising task registration, staging Copy wiring, `sdkDir`
 * resolution, and the AGP auto-wiring.
 *
 * The end-to-end "bundle actually produces a runnable QuickJS file" path is covered separately by
 * `:trailblaze-quickjs-tools:jvmTest`'s `SampleAppToolsDemoTest` and the on-device
 * `QuickJsToolBundleOnDeviceTest`; tests here `assumeTrue`-skip when the framework SDK isn't
 * installed (esbuild missing).
 */
class TrailblazeAndroidGradleTrailmapFunctionalTest {

  private val tempDirs = mutableListOf<File>()

  @AfterTest
  fun cleanupTempDirs() {
    tempDirs.forEach { it.deleteRecursively() }
    tempDirs.clear()
  }

  // SDK dir from the `trailblaze.sdkDir` system property. Tests needing esbuild on disk
  // `assumeTrue` against it; task-registration-only tests run unconditionally.
  private val frameworkSdkDir: File = File(System.getProperty("trailblaze.sdkDir") ?: ".")

  @Test
  fun `registers a bundle task per discovered tool and a sibling staging task`() {
    val projectDir =
      newFixtureProject(
        androidFixtureBuildScript(
          """
        trailblazeAndroid {
          // Explicit sdkDir so registration doesn't depend on a walk-up finding a real SDK.
          sdkDir.set(file("${frameworkSdkDir.absolutePath}"))
          trailmap {
            id = "p"
            toolsDir = file("tools")
          }
        }

        tasks.register("dumpRegistered") {
          doLast {
            val taskNames = tasks.matching {
              it.name.startsWith("bundleTrailmap") || it.name.startsWith("stageTrailmap")
            }.map { it.name }.sorted()
            println("REGISTERED=" + taskNames.joinToString(","))
          }
        }
        """
        ),
        tempDirs,
      )
    writeTool(projectDir, "fizz.ts", TYPED_TOOL_SOURCE)
    writeTool(projectDir, "buzz.ts", TYPED_TOOL_SOURCE)

    val result = gradleRunner(projectDir, "dumpRegistered").build()
    assertEquals(TaskOutcome.SUCCESS, result.task(":dumpRegistered")?.outcome)
    // Discovery is alphabetical; bundle + stage tasks are registered per tool.
    assertTrue("expected the per-tool bundle + stage tasks to be registered: ${result.output}") {
      result.output.contains(
        "REGISTERED=bundleTrailmapPBuzzToolBundle,bundleTrailmapPFizzToolBundle," +
          "stageTrailmapPBuzzToolBundleAsset,stageTrailmapPFizzToolBundleAsset"
      )
    }
  }

  @Test
  fun `no trailmap block means zero bundle-related tasks registered`() {
    // Zero cost when unused — no `trailmap { }` call must mean no bundle/stage tasks registered.
    val projectDir =
      newFixtureProject(
        androidFixtureBuildScript(
          """
        trailblazeAndroid {
          packageName = "xyz.fixture.app"
        }
        tasks.register("dumpRegistered") {
          doLast {
            val taskNames = tasks.matching {
              it.name.startsWith("bundleTrailmap") || it.name.startsWith("stageTrailmap")
            }.map { it.name }
            println("REGISTERED=" + taskNames.joinToString(","))
          }
        }
        """
        ),
        tempDirs,
      )

    val result = gradleRunner(projectDir, "dumpRegistered").build()
    assertEquals(TaskOutcome.SUCCESS, result.task(":dumpRegistered")?.outcome)
    assertTrue("expected zero trailmap tasks registered: ${result.output}") {
      result.output.lines().any { it.trim() == "REGISTERED=" }
    }
  }

  @Test
  fun `no trailmap block means the staging root is not wired into the androidTest assets source set`() {
    // Regression guard: unconditionally adding an (empty) staging root to androidTest assets would
    // let stale `.bundle.js` files from a PRIOR build — before a module removed its last
    // `trailmap { }` block — keep sneaking into the test APK, since the source set would still
    // point at that build directory even though nothing regenerates or cleans it.
    val projectDir =
      newFixtureProject(
        androidFixtureBuildScript(
          """
        trailblazeAndroid {
          packageName = "xyz.fixture.app"
        }
        tasks.register("dumpAndroidTestAssetSrcDirs") {
          doLast {
            val android = project.extensions.getByName("android") as FakeAndroidExtension
            val resolved = android.sourceSets.getByName("androidTest").assets.srcDirs.map { project.file(it) }
            println("ASSET_SRC_DIRS=" + resolved)
          }
        }
        """
        ),
        tempDirs,
      )

    val result = gradleRunner(projectDir, "dumpAndroidTestAssetSrcDirs").build()
    assertEquals(TaskOutcome.SUCCESS, result.task(":dumpAndroidTestAssetSrcDirs")?.outcome)
    assertTrue("expected no trailmap staging root wired in: ${result.output}") {
      !result.output.contains("intermediates/trailblaze/trailmap-tool-bundle-assets")
    }
  }

  @Test
  fun `sdkInstallTaskPath wires a dependsOn when set explicitly`() {
    val projectDir =
      newFixtureProject(
        androidFixtureBuildScript(
          """
        tasks.register("stubSdkInstall") { doLast { println("STUB_INSTALL_RAN") } }

        trailblazeAndroid {
          sdkDir.set(file("${frameworkSdkDir.absolutePath}"))
          sdkInstallTaskPath.set(":stubSdkInstall")
          trailmap {
            id = "p"
            toolsDir = file("tools")
          }
        }

        tasks.register("dumpDeps") {
          doLast {
            val t = tasks.named("bundleTrailmapPOnlyToolBundle").get()
            println("DEPS=" + t.taskDependencies.getDependencies(t).map { it.path }.sorted())
          }
        }
        """
        ),
        tempDirs,
      )
    writeTool(projectDir, "only.ts", TYPED_TOOL_SOURCE)

    val result = gradleRunner(projectDir, "dumpDeps").build()
    assertEquals(TaskOutcome.SUCCESS, result.task(":dumpDeps")?.outcome)
    assertTrue("expected per-tool bundle task to dependsOn :stubSdkInstall: ${result.output}") {
      result.output.contains(":stubSdkInstall")
    }
  }

  @Test
  fun `staging task materializes the bundle at the on-device asset path`() {
    // Only runs when the framework SDK install is present; the task-registration tests above
    // keep the wiring contract green in environments without esbuild on disk.
    val esbuild = File(frameworkSdkDir, "node_modules/.bin/esbuild")
    assumeTrue(
      "esbuild not present at ${esbuild.absolutePath}; skipping end-to-end stage test.",
      esbuild.isFile,
    )

    val projectDir =
      newFixtureProject(
        androidFixtureBuildScript(
          """
        trailblazeAndroid {
          sdkDir.set(file("${frameworkSdkDir.absolutePath}"))
          trailmap {
            id = "p"
            toolsDir = file("tools")
          }
        }
        """
        ),
        tempDirs,
      )
    writeTool(projectDir, "onlyTool.ts", TYPED_TOOL_SOURCE)

    val result = gradleRunner(projectDir, "stageTrailmapPOnlyToolToolBundleAsset").build()
    assertEquals(
      TaskOutcome.SUCCESS,
      result.task(":stageTrailmapPOnlyToolToolBundleAsset")?.outcome,
    )

    val stagedBundle =
      File(
        projectDir,
        "build/intermediates/trailblaze/trailmap-tool-bundle-assets/" +
          "trails/config/trailmaps/p/tools/onlyTool.bundle.js",
      )
    assertTrue("expected staged bundle at $stagedBundle") { stagedBundle.isFile }
    assertTrue("staged bundle is empty: $stagedBundle") { stagedBundle.length() > 0 }
  }

  @Test
  fun `AGP auto-wiring lands the staging root on the androidTest assets source set`() {
    // Task registration only needs the SDK dir to exist; esbuild itself is only touched when a
    // bundle task executes, which this test never does — no `assumeTrue` gate needed.
    val projectDir =
      newFixtureProject(
        androidFixtureBuildScript(
          """
        trailblazeAndroid {
          sdkDir.set(file("${frameworkSdkDir.absolutePath}"))
          trailmap {
            id = "p"
            toolsDir = file("tools")
          }
        }
        tasks.register("dumpAndroidTestAssetSrcDirs") {
          doLast {
            val android = project.extensions.getByName("android") as FakeAndroidExtension
            val resolved = android.sourceSets.getByName("androidTest").assets.srcDirs.map { project.file(it) }
            println("ASSET_SRC_DIRS=" + resolved)
          }
        }
        """
        ),
        tempDirs,
      )
    writeTool(projectDir, "onlyTool.ts", TYPED_TOOL_SOURCE)

    val result = gradleRunner(projectDir, "dumpAndroidTestAssetSrcDirs").build()
    assertEquals(TaskOutcome.SUCCESS, result.task(":dumpAndroidTestAssetSrcDirs")?.outcome)
    assertTrue(
      "expected the trailmap staging root on the androidTest assets source set: ${result.output}"
    ) {
      result.output.contains("intermediates/trailblaze/trailmap-tool-bundle-assets")
    }
  }

  @Test
  fun `trailmap throws a directed error when toolsDir does not exist`() {
    val projectDir =
      newFixtureProject(
        androidFixtureBuildScript(
          """
        trailblazeAndroid {
          sdkDir.set(file("${frameworkSdkDir.absolutePath}"))
          trailmap {
            id = "p"
            toolsDir = file("definitely-not-here")
          }
        }
        """
        ),
        tempDirs,
      )

    val result = gradleRunner(projectDir, "help").buildAndFail()
    assertTrue("expected directed error about missing tools dir: ${result.output}") {
      result.output.contains("trailblazeAndroid.trailmap(\"p\")") &&
        result.output.contains("tools directory not found")
    }
  }

  @Test
  fun `trailmap throws a directed error when id is not set`() {
    val projectDir =
      newFixtureProject(
        androidFixtureBuildScript(
          """
        trailblazeAndroid {
          trailmap {
            toolsDir = file("tools")
          }
        }
        """
        ),
        tempDirs,
      )

    val result = gradleRunner(projectDir, "help").buildAndFail()
    assertTrue("expected directed error about missing id: ${result.output}") {
      result.output.contains("trailblazeAndroid.trailmap { }: `id` must be set.")
    }
  }

  @Test
  fun `trailmap throws a directed error when toolsDir is not set`() {
    val projectDir =
      newFixtureProject(
        androidFixtureBuildScript(
          """
        trailblazeAndroid {
          trailmap {
            id = "p"
          }
        }
        """
        ),
        tempDirs,
      )

    val result = gradleRunner(projectDir, "help").buildAndFail()
    assertTrue("expected directed error about missing toolsDir: ${result.output}") {
      result.output.contains("trailblazeAndroid.trailmap(\"p\"): `toolsDir` must be set.")
    }
  }

  @Test
  fun `trailmap throws a directed error when the SDK cannot be located`() {
    // No `sdkDir` set and no real SDK anywhere above this temp fixture's directory tree, so the
    // default walk-up resolution comes back empty and the "could not locate" path fires.
    val projectDir =
      newFixtureProject(
        androidFixtureBuildScript(
          """
        trailblazeAndroid {
          trailmap {
            id = "p"
            toolsDir = file("tools")
          }
        }
        """
        ),
        tempDirs,
      )
    writeTool(projectDir, "onlyTool.ts", TYPED_TOOL_SOURCE)

    val result = gradleRunner(projectDir, "help").buildAndFail()
    assertTrue("expected directed error about the missing SDK: ${result.output}") {
      result.output.contains("could not locate the Trailblaze TypeScript SDK")
    }
  }

  @Test
  fun `two trailmap blocks in one module register non-colliding tasks for both`() {
    val projectDir =
      newFixtureProject(
        androidFixtureBuildScript(
          """
        trailblazeAndroid {
          sdkDir.set(file("${frameworkSdkDir.absolutePath}"))
          trailmap {
            id = "alpha"
            toolsDir = file("alpha-tools")
          }
          trailmap {
            id = "beta"
            toolsDir = file("beta-tools")
          }
        }

        tasks.register("dumpRegistered") {
          doLast {
            val taskNames = tasks.matching {
              it.name.startsWith("bundleTrailmap") || it.name.startsWith("stageTrailmap")
            }.map { it.name }.sorted()
            println("REGISTERED=" + taskNames.joinToString(","))
          }
        }
        """
        ),
        tempDirs,
      )
    File(File(projectDir, "alpha-tools").apply { mkdirs() }, "fizz.ts").writeText(TYPED_TOOL_SOURCE)
    File(File(projectDir, "beta-tools").apply { mkdirs() }, "buzz.ts").writeText(TYPED_TOOL_SOURCE)

    val result = gradleRunner(projectDir, "dumpRegistered").build()
    assertEquals(TaskOutcome.SUCCESS, result.task(":dumpRegistered")?.outcome)
    assertTrue("expected bundle+stage tasks for both trailmaps: ${result.output}") {
      result.output.contains(
        "REGISTERED=bundleTrailmapAlphaFizzToolBundle,bundleTrailmapBetaBuzzToolBundle," +
          "stageTrailmapAlphaFizzToolBundleAsset,stageTrailmapBetaBuzzToolBundleAsset"
      )
    }
  }

  // ---- Fixtures ----

  private fun writeTool(projectDir: File, fileName: String, contents: String) {
    File(File(projectDir, "tools").apply { mkdirs() }, fileName).writeText(contents)
  }

  companion object {
    /**
     * A minimal in-process scripted-tool source. Has the inline `export const … = trailblaze.tool<…>(…)`
     * marker `inProcessToolSources` requires for descriptor-less discovery, with no sibling YAML.
     */
    private val TYPED_TOOL_SOURCE: String =
      """
      export const onlyTool = trailblaze.tool<{ message: string }>(
        { supportedPlatforms: ["android"] },
        async (input) => "ok",
      )
    """
        .trimIndent()
  }
}
