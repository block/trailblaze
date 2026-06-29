import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome

/**
 * Functional tests for `xyz.block.trailblaze.android-gradle` — exercises the TaskAction
 * end-to-end via Gradle TestKit. Complements [TrailblazeAndroidGradlePluginTest] (which covers
 * the pure renderer + the identifier validators).
 *
 * **Why TestKit and not unit tests?** The TaskAction has branches the pure-function tests can't
 * reach without a real Gradle project: the missing-`trailsAssetsDir` no-op (it depends on Gradle's
 * `@Optional` machinery + the `.orNull` read), the `onlyClassNames` allow-list rejection, the
 * end-to-end "emit a .kt file with N @Test methods", and the up-to-date / cacheable contract. A
 * regression in any of those would silently bypass the documented behaviour with no signal from
 * the unit tests alone.
 */
class TrailblazeAndroidGradlePluginFunctionalTest {

  private val tempDirs = mutableListOf<File>()

  @AfterTest
  fun cleanupTempDirs() {
    tempDirs.forEach { it.deleteRecursively() }
    tempDirs.clear()
  }

  @Test
  fun `default (OSS) emit mode is inline-rule with AndroidTrailblazeRule`() {
    // When the consumer applies the plugin and configures only the minimum (packageName +
    // trailsAssetsDir), the plugin should emit the OSS-standard inline-rule shape — `class X {
    // @get:Rule val rule = AndroidTrailblazeRule(); @Test fun y() = rule.runFromAsset("trails/X/y.trail.yaml") }`.
    // This is the path an external Android team picks up off Maven Central, and it has to work
    // out of the box without any extra config.
    val projectDir =
      newFixtureProject(
        buildScript =
          """
        plugins {
          base
          id("xyz.block.trailblaze.android-gradle")
        }
        trailblazeAndroidGradle {
          packageName.set("xyz.fixture.app")
          trailsAssetsDir.set(layout.projectDirectory.dir("trails"))
        }
        """.trimIndent(),
      )
    writeTrail(projectDir, className = "FooLongTest", method = "alpha")
    writeTrail(projectDir, className = "FooLongTest", method = "beta")

    val result = runner(projectDir, "generateAndroidTrailJUnitShells").build()
    assertEquals(
      TaskOutcome.SUCCESS,
      result.task(":generateAndroidTrailJUnitShells")?.outcome,
    )
    val emitted =
      File(
        projectDir,
        "build/generated/source/trailblazeTrails/androidTest/xyz/fixture/app/FooLongTest.kt",
      )
    assertTrue("expected generated file at $emitted; build dir contents: ${tree(projectDir)}") {
      emitted.isFile
    }
    val source = emitted.readText()
    assertTrue("expected `class FooLongTest {` (no `:` extending-base form): $source") {
      source.contains("class FooLongTest {")
    }
    assertTrue("expected `@get:Rule val rule = AndroidTrailblazeRule()`: $source") {
      source.contains("@get:Rule val rule = AndroidTrailblazeRule()")
    }
    assertTrue("expected inline-rule asset path call for alpha: $source") {
      source.contains(
        "@Test fun alpha() = rule.runFromAsset(\"trails/FooLongTest/alpha.trail.yaml\")"
      )
    }
    assertTrue("expected inline-rule asset path call for beta: $source") {
      source.contains(
        "@Test fun beta() = rule.runFromAsset(\"trails/FooLongTest/beta.trail.yaml\")"
      )
    }
  }

  @Test
  fun `extending-base mode (baseClassFqn set) emits downstream shape with inherited runFromAsset`() {
    // downstream modules (e.g. uitests-evaluation) explicitly set `baseClassFqn` to their
    // wrapping base, which switches the generator to pattern A — `class X : BaseClass() { @Test
    // fun y() = runFromAsset() }`. Lock that shape against the OSS-default-flip; without this test,
    // a future refactor could regress the internal consumer without anyone noticing until on-device.
    val projectDir =
      newFixtureProject(
        buildScript =
          """
        plugins {
          base
          id("xyz.block.trailblaze.android-gradle")
        }
        trailblazeAndroidGradle {
          packageName.set("xyz.fixture.evaluation")
          trailsAssetsDir.set(layout.projectDirectory.dir("trails"))
          baseClassFqn.set("xyz.block.trailblaze.rules.SquareTrailblazeTest")
        }
        """.trimIndent(),
      )
    writeTrail(projectDir, className = "FooLongTest", method = "alpha")

    val result = runner(projectDir, "generateAndroidTrailJUnitShells").build()
    assertEquals(
      TaskOutcome.SUCCESS,
      result.task(":generateAndroidTrailJUnitShells")?.outcome,
    )
    val source =
      File(
          projectDir,
          "build/generated/source/trailblazeTrails/androidTest/xyz/fixture/evaluation/FooLongTest.kt",
        )
        .readText()
    assertTrue("expected `class FooLongTest : SquareTrailblazeTest()`: $source") {
      source.contains("class FooLongTest : SquareTrailblazeTest()")
    }
    assertTrue("expected inherited runFromAsset (no rule prefix): $source") {
      source.contains("@Test fun alpha() = runFromAsset()")
    }
  }

  @Test
  fun `Gradle property overrides the active baseClassFqn from the command line`() {
    // CI shards / one-off local runs can flip the host class without editing the build file via
    // `-Ptrailblaze.shellGenerator.baseClassFqn=...`. The property convention plumbing must let
    // that property win when the consumer hasn't called .set() explicitly.
    val projectDir =
      newFixtureProject(
        buildScript =
          """
        plugins {
          base
          id("xyz.block.trailblaze.android-gradle")
        }
        trailblazeAndroidGradle {
          packageName.set("xyz.fixture.app")
          trailsAssetsDir.set(layout.projectDirectory.dir("trails"))
          // baseClassFqn intentionally not set — pick it up from the Gradle property.
        }
        """.trimIndent(),
      )
    writeTrail(projectDir, className = "FooLongTest", method = "alpha")

    val result =
      runner(
          projectDir,
          "generateAndroidTrailJUnitShells",
          "-Ptrailblaze.shellGenerator.baseClassFqn=com.example.MyBase",
        )
        .build()
    assertEquals(
      TaskOutcome.SUCCESS,
      result.task(":generateAndroidTrailJUnitShells")?.outcome,
    )
    val source =
      File(
          projectDir,
          "build/generated/source/trailblazeTrails/androidTest/xyz/fixture/app/FooLongTest.kt",
        )
        .readText()
    assertTrue("expected `class FooLongTest : MyBase()` from -P override: $source") {
      source.contains("class FooLongTest : MyBase()")
    }
  }

  @Test
  fun `no-ops with a friendly log when trails-assets-dir does not exist`() {
    // Sanity-check the @Optional + .orNull plumbing: a module that applies the plugin BEFORE
    // authoring any trails (early rollout) should hit the no-op lifecycle log instead of an
    // input-validation error or a stack trace.
    val projectDir =
      newFixtureProject(
        buildScript =
          """
        plugins {
          base
          id("xyz.block.trailblaze.android-gradle")
        }
        trailblazeAndroidGradle {
          packageName.set("xyz.fixture.evaluation")
          // trailsAssetsDir intentionally NOT set — fixture has no trails dir.
        }
        """.trimIndent(),
      )

    val result = runner(projectDir, "generateAndroidTrailJUnitShells").build()
    assertEquals(
      TaskOutcome.SUCCESS,
      result.task(":generateAndroidTrailJUnitShells")?.outcome,
    )
    assertTrue("expected no-op log in output: ${result.output}") {
      result.output.contains("no assets directory") &&
        result.output.contains("no shells generated")
    }
  }

  @Test
  fun `onlyClassNames typo fails with a directed error naming the typo and the available dirs`() {
    // A typo in onlyClassNames would silently produce no output — the test author would only
    // discover the gap at test runtime ("method not found"). Fail at generate time instead.
    val projectDir =
      newFixtureProject(
        buildScript =
          """
        plugins {
          base
          id("xyz.block.trailblaze.android-gradle")
        }
        trailblazeAndroidGradle {
          packageName.set("xyz.fixture.evaluation")
          trailsAssetsDir.set(layout.projectDirectory.dir("trails"))
          onlyClassNames.set(setOf("FooLongTest", "NotARealDir"))
        }
        """.trimIndent(),
      )
    writeTrail(projectDir, className = "FooLongTest", method = "alpha")

    val result = runner(projectDir, "generateAndroidTrailJUnitShells").buildAndFail()
    assertTrue("expected typo to be named: ${result.output}") {
      result.output.contains("NotARealDir")
    }
    assertTrue("expected available dirs in error: ${result.output}") {
      result.output.contains("Available directories")
    }
  }

  @Test
  fun `a directory whose name is a Kotlin hard keyword fails at generate time`() {
    val projectDir =
      newFixtureProject(
        buildScript =
          """
        plugins {
          base
          id("xyz.block.trailblaze.android-gradle")
        }
        trailblazeAndroidGradle {
          packageName.set("xyz.fixture.evaluation")
          trailsAssetsDir.set(layout.projectDirectory.dir("trails"))
        }
        """.trimIndent(),
      )
    writeTrail(projectDir, className = "when", method = "alpha")

    val result = runner(projectDir, "generateAndroidTrailJUnitShells").buildAndFail()
    assertTrue("expected hard-keyword rejection: ${result.output}") {
      result.output.contains("`when` is a Kotlin hard keyword")
    }
  }

  @Test
  fun `second invocation with no input changes reports UP-TO-DATE`() {
    val projectDir =
      newFixtureProject(
        buildScript =
          """
        plugins {
          base
          id("xyz.block.trailblaze.android-gradle")
        }
        trailblazeAndroidGradle {
          packageName.set("xyz.fixture.evaluation")
          trailsAssetsDir.set(layout.projectDirectory.dir("trails"))
        }
        """.trimIndent(),
      )
    writeTrail(projectDir, className = "FooLongTest", method = "alpha")

    val first = runner(projectDir, "generateAndroidTrailJUnitShells").build()
    assertEquals(
      TaskOutcome.SUCCESS,
      first.task(":generateAndroidTrailJUnitShells")?.outcome,
    )

    val second = runner(projectDir, "generateAndroidTrailJUnitShells").build()
    assertEquals(
      TaskOutcome.UP_TO_DATE,
      second.task(":generateAndroidTrailJUnitShells")?.outcome,
      "expected UP-TO-DATE on the second invocation; got ${second.output}",
    )
  }

  // ---- Fixtures ----

  private fun newFixtureProject(buildScript: String): File {
    val dir =
      createTempDirectory("trailblaze-junit-shells-functional").toFile().also(tempDirs::add)
    File(dir, "settings.gradle.kts").writeText("""rootProject.name = "fixture"""")
    File(dir, "build.gradle.kts").writeText(buildScript)
    return dir
  }

  private fun writeTrail(projectDir: File, className: String, method: String) {
    val dir = File(projectDir, "trails/$className").apply { mkdirs() }
    // The generator reads filenames only, not contents — an empty file is enough to exercise the
    // discovery + emission path. Real consumers ship real YAML, of course.
    File(dir, "$method.trail.yaml").writeText("")
  }

  private fun runner(projectDir: File, vararg args: String): GradleRunner =
    GradleRunner.create()
      .withProjectDir(projectDir)
      .withArguments(*args)
      .withPluginClasspath()
      .forwardOutput()

  private fun tree(dir: File): String =
    dir.walkTopDown().joinToString("\n") { it.relativeTo(dir).path }
}
