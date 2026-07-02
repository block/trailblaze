import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.gradle.testkit.runner.TaskOutcome

/**
 * Functional tests for `xyz.block.trailblaze.android-gradle` — exercises the TaskAction
 * end-to-end via Gradle TestKit. Complements [TrailblazeAndroidGradlePluginTest] (pure renderer +
 * identifier validators). Fixture helpers live in `AndroidGradleTestFixtures.kt`, shared with the
 * trailmap-bundling functional tests.
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
        androidFixtureBuildScript(
          """
        trailblazeAndroid {
          packageName = "xyz.fixture.app"
          trailsAssetsDir = layout.projectDirectory.dir("trails")
        }
        """
        ),
        tempDirs,
      )
    writeTrail(projectDir, className = "FooLongTest", method = "alpha")
    writeTrail(projectDir, className = "FooLongTest", method = "beta")

    val result = gradleRunner(projectDir, "generateAndroidTrailJUnitShells").build()
    assertEquals(
      TaskOutcome.SUCCESS,
      result.task(":generateAndroidTrailJUnitShells")?.outcome,
    )
    val emitted =
      File(
        projectDir,
        "build/generated/source/trailblazeTrails/androidTest/xyz/fixture/app/FooLongTest.kt",
      )
    assertTrue(
      "expected generated file at $emitted; build dir contents: ${fixtureTree(projectDir)}"
    ) {
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
        androidFixtureBuildScript(
          """
        trailblazeAndroid {
          packageName = "xyz.fixture.evaluation"
          trailsAssetsDir = layout.projectDirectory.dir("trails")
          baseClassFqn = "xyz.block.trailblaze.rules.SquareTrailblazeTest"
        }
        """
        ),
        tempDirs,
      )
    writeTrail(projectDir, className = "FooLongTest", method = "alpha")

    val result = gradleRunner(projectDir, "generateAndroidTrailJUnitShells").build()
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
    // that property win when the consumer hasn't assigned baseClassFqn explicitly.
    val projectDir =
      newFixtureProject(
        androidFixtureBuildScript(
          """
        trailblazeAndroid {
          packageName = "xyz.fixture.app"
          trailsAssetsDir = layout.projectDirectory.dir("trails")
          // baseClassFqn intentionally not set — pick it up from the Gradle property.
        }
        """
        ),
        tempDirs,
      )
    writeTrail(projectDir, className = "FooLongTest", method = "alpha")

    val result =
      gradleRunner(
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
        androidFixtureBuildScript(
          """
        trailblazeAndroid {
          packageName = "xyz.fixture.evaluation"
          // trailsAssetsDir intentionally NOT set — fixture has no trails dir.
        }
        """
        ),
        tempDirs,
      )

    val result = gradleRunner(projectDir, "generateAndroidTrailJUnitShells").build()
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
  fun `no-ops without requiring packageName when the plugin is applied purely for trailmap bundling`() {
    // A trailmap-only consumer (no `src/androidTest/assets/trails`, no `packageName`) must no-op
    // cleanly rather than throw "packageName is not set" — regression guard for the check-ordering
    // fix (missing-assets-dir check runs BEFORE the packageName requirement).
    val projectDir = newFixtureProject(androidFixtureBuildScript(""), tempDirs)

    val result = gradleRunner(projectDir, "generateAndroidTrailJUnitShells").build()
    assertEquals(
      TaskOutcome.SUCCESS,
      result.task(":generateAndroidTrailJUnitShells")?.outcome,
      "expected a clean no-op, not a packageName failure; output: ${result.output}",
    )
    assertTrue("expected the no-op log, not a packageName error: ${result.output}") {
      result.output.contains("no assets directory") &&
        !result.output.contains("packageName is not set")
    }
    // The declared @OutputDirectory must exist after a successful run (even an empty no-op run),
    // or Gradle can never mark this task UP-TO-DATE — it re-runs every build for trailmap-only
    // consumers otherwise.
    assertTrue("expected the (empty) generated source dir to exist after a no-op run") {
      File(projectDir, "build/generated/source/trailblazeTrails/androidTest").isDirectory
    }
  }

  @Test
  fun `no-ops without requiring packageName when a sibling non-class directory exists but has no trail yaml`() {
    // A trailmap-only consumer whose default trailsAssetsDir happens to contain a subdirectory
    // that ISN'T a <ClassName> shell dir (e.g. a `config/` tree used for something unrelated to
    // codegen) must still no-op — a bare subdirectory isn't proof there's a shell to generate.
    val projectDir =
      newFixtureProject(
        androidFixtureBuildScript(
          """
        trailblazeAndroid {
          trailsAssetsDir = layout.projectDirectory.dir("trails")
        }
        """
        ),
        tempDirs,
      )
    File(projectDir, "trails/config").mkdirs()
    File(projectDir, "trails/config/not-a-trail.yaml").writeText("")

    val result = gradleRunner(projectDir, "generateAndroidTrailJUnitShells").build()
    assertEquals(
      TaskOutcome.SUCCESS,
      result.task(":generateAndroidTrailJUnitShells")?.outcome,
      "expected a clean no-op, not a packageName failure; output: ${result.output}",
    )
    assertTrue("expected the no-op log, not a packageName error: ${result.output}") {
      !result.output.contains("packageName is not set")
    }
  }

  @Test
  fun `onlyMethodNames filters per-class trails and a typo fails with a directed error`() {
    // Two trails on disk (`alpha`, `beta`). The build configures onlyMethodNames["FooLongTest"]
    // = {"alpha"}, so only `alpha` should land in the generated shell.
    val projectDir =
      newFixtureProject(
        androidFixtureBuildScript(
          """
        trailblazeAndroid {
          packageName = "xyz.fixture.app"
          trailsAssetsDir = layout.projectDirectory.dir("trails")
          onlyMethodNames.put("FooLongTest", setOf("alpha"))
        }
        """
        ),
        tempDirs,
      )
    writeTrail(projectDir, className = "FooLongTest", method = "alpha")
    writeTrail(projectDir, className = "FooLongTest", method = "beta")

    val result = gradleRunner(projectDir, "generateAndroidTrailJUnitShells").build()
    assertEquals(TaskOutcome.SUCCESS, result.task(":generateAndroidTrailJUnitShells")?.outcome)
    val source =
      File(
          projectDir,
          "build/generated/source/trailblazeTrails/androidTest/xyz/fixture/app/FooLongTest.kt",
        )
        .readText()
    assertTrue("expected `alpha` to be emitted: $source") {
      source.contains("@Test fun alpha()")
    }
    assertTrue("expected `beta` NOT to be emitted: $source") {
      !source.contains("fun beta(")
    }

    // Typo guard: an unknown method name in the allow-list must fail loudly so a copy-paste
    // mistake doesn't silently emit a smaller test surface.
    val typoProject =
      newFixtureProject(
        androidFixtureBuildScript(
          """
        trailblazeAndroid {
          packageName = "xyz.fixture.app"
          trailsAssetsDir = layout.projectDirectory.dir("trails")
          onlyMethodNames.put("FooLongTest", setOf("alpha", "notARealTrail"))
        }
        """
        ),
        tempDirs,
      )
    writeTrail(typoProject, className = "FooLongTest", method = "alpha")

    val failure = gradleRunner(typoProject, "generateAndroidTrailJUnitShells").buildAndFail()
    assertTrue("expected the typo to be named in the error: ${failure.output}") {
      failure.output.contains("notARealTrail")
    }
    assertTrue("expected available trails listed: ${failure.output}") {
      failure.output.contains("Available trails")
    }
  }

  @Test
  fun `onlyClassNames typo fails with a directed error naming the typo and the available dirs`() {
    // A typo in onlyClassNames would silently produce no output — the test author would only
    // discover the gap at test runtime ("method not found"). Fail at generate time instead.
    val projectDir =
      newFixtureProject(
        androidFixtureBuildScript(
          """
        trailblazeAndroid {
          packageName = "xyz.fixture.evaluation"
          trailsAssetsDir = layout.projectDirectory.dir("trails")
          onlyClassNames = setOf("FooLongTest", "NotARealDir")
        }
        """
        ),
        tempDirs,
      )
    writeTrail(projectDir, className = "FooLongTest", method = "alpha")

    val result = gradleRunner(projectDir, "generateAndroidTrailJUnitShells").buildAndFail()
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
        androidFixtureBuildScript(
          """
        trailblazeAndroid {
          packageName = "xyz.fixture.evaluation"
          trailsAssetsDir = layout.projectDirectory.dir("trails")
        }
        """
        ),
        tempDirs,
      )
    writeTrail(projectDir, className = "when", method = "alpha")

    val result = gradleRunner(projectDir, "generateAndroidTrailJUnitShells").buildAndFail()
    assertTrue("expected hard-keyword rejection: ${result.output}") {
      result.output.contains("`when` is a Kotlin hard keyword")
    }
  }

  @Test
  fun `second invocation with no input changes reports UP-TO-DATE`() {
    val projectDir =
      newFixtureProject(
        androidFixtureBuildScript(
          """
        trailblazeAndroid {
          packageName = "xyz.fixture.evaluation"
          trailsAssetsDir = layout.projectDirectory.dir("trails")
        }
        """
        ),
        tempDirs,
      )
    writeTrail(projectDir, className = "FooLongTest", method = "alpha")

    val first = gradleRunner(projectDir, "generateAndroidTrailJUnitShells").build()
    assertEquals(
      TaskOutcome.SUCCESS,
      first.task(":generateAndroidTrailJUnitShells")?.outcome,
    )

    val second = gradleRunner(projectDir, "generateAndroidTrailJUnitShells").build()
    assertEquals(
      TaskOutcome.UP_TO_DATE,
      second.task(":generateAndroidTrailJUnitShells")?.outcome,
      "expected UP-TO-DATE on the second invocation; got ${second.output}",
    )
  }

  @Test
  fun `apply() auto-wires androidTest-shaped tasks to depend on generateTask`() {
    // The matcher inside `apply()` covers four task-name families. Without real AGP in the
    // fixture (see AndroidGradleTestFixtures.kt), we register stand-in tasks that exactly match
    // each family AFTER the plugin is applied and assert they all carry
    // `dependsOn(:generateAndroidTrailJUnitShells)`. This relies on the matcher being live
    // (`tasks.matching` returns a filtered view that updates as tasks are added, and
    // `configureEach` is the lazy variant that applies to current AND future matching tasks).
    //
    // The matcher predicate is what every consumer relies on; this test pins it against the four
    // documented patterns so a future refactor can't silently regress one of them — and includes
    // a negative case (`compileDebugKotlin`) to guard against an over-broad future matcher.
    val projectDir =
      newFixtureProject(
        androidFixtureBuildScript(
          """
        trailblazeAndroid {
          packageName = "xyz.fixture.app"
          trailsAssetsDir = layout.projectDirectory.dir("trails")
        }
        // Stand in for AGP's androidTest task families. Registered AFTER the plugin is applied,
        // so this also exercises the "matcher fires on tasks registered later" path that real
        // AGP consumers depend on.
        val compileDebugAndroidTestKotlin by tasks.registering
        val generateDebugAndroidTestLintModel by tasks.registering
        val lintAnalyzeDebugAndroidTest by tasks.registering
        val lintReportDebugAndroidTest by tasks.registering
        // Negative-case: same prefix as the compile family but not for androidTest.
        val compileDebugKotlin by tasks.registering

        // Forces the matcher's `configureEach` to fire by realizing each task, then asserts the
        // resulting dependsOn sets.
        tasks.register("assertAutoWiringApplied") {
          doLast {
            val expected = setOf(
              "compileDebugAndroidTestKotlin",
              "generateDebugAndroidTestLintModel",
              "lintAnalyzeDebugAndroidTest",
              "lintReportDebugAndroidTest",
            )
            expected.forEach { name ->
              val deps = tasks.named(name).get().taskDependencies.getDependencies(null)
                .map { it.name }
              check("generateAndroidTrailJUnitShells" in deps) {
                "expected ${'$'}name.dependsOn to include generateAndroidTrailJUnitShells; got ${'$'}deps"
              }
            }
            val unrelated = tasks.named("compileDebugKotlin").get().taskDependencies
              .getDependencies(null).map { it.name }
            check("generateAndroidTrailJUnitShells" !in unrelated) {
              "expected compileDebugKotlin NOT to depend on generateAndroidTrailJUnitShells; got ${'$'}unrelated"
            }
          }
        }
        """
        ),
        tempDirs,
      )
    writeTrail(projectDir, className = "FooLongTest", method = "alpha")

    val result = gradleRunner(projectDir, "assertAutoWiringApplied").build()
    assertEquals(
      TaskOutcome.SUCCESS,
      result.task(":assertAutoWiringApplied")?.outcome,
      "expected the auto-wiring assertion task to pass; got ${result.output}",
    )
  }

  @Test
  fun `apply() auto-wires the generated source dir into the android extension's androidTest java source set`() {
    val projectDir =
      newFixtureProject(
        androidFixtureBuildScript(
          """
        trailblazeAndroid {
          packageName = "xyz.fixture.app"
          trailsAssetsDir = layout.projectDirectory.dir("trails")
        }
        tasks.register("dumpAndroidTestJavaSrcDirs") {
          doLast {
            val android = project.extensions.getByName("android") as FakeAndroidExtension
            val resolved = android.sourceSets.getByName("androidTest").java.srcDirs.map { project.file(it) }
            println("SRC_DIRS=" + resolved)
          }
        }
        """
        ),
        tempDirs,
      )
    writeTrail(projectDir, className = "FooLongTest", method = "alpha")

    val result = gradleRunner(projectDir, "dumpAndroidTestJavaSrcDirs").build()
    assertEquals(TaskOutcome.SUCCESS, result.task(":dumpAndroidTestJavaSrcDirs")?.outcome)
    assertTrue(
      "expected the generated source dir on the androidTest java source set: ${result.output}"
    ) {
      result.output.contains("generated/source/trailblazeTrails/androidTest")
    }
  }

  @Test
  fun `fails when applied without com_android_library or com_android_application`() {
    // A plain JVM project has nothing for this plugin to wire — must fail loudly, not no-op.
    val projectDir =
      newFixtureProject(
        """
        plugins {
          base
          id("xyz.block.trailblaze.android-gradle")
        }
        trailblazeAndroid {
          packageName = "xyz.fixture.app"
        }
        """
          .trimIndent(),
        tempDirs,
      )

    val result = gradleRunner(projectDir, "help").buildAndFail()
    assertTrue("expected a directed AGP-required error: ${result.output}") {
      result.output.contains("xyz.block.trailblaze.android-gradle requires") &&
        result.output.contains("com.android.library")
    }
  }

  // ---- Fixtures ----

  private fun writeTrail(projectDir: File, className: String, method: String) {
    val dir = File(projectDir, "trails/$className").apply { mkdirs() }
    // The generator reads filenames only, not contents — an empty file is enough to exercise the
    // discovery + emission path. Real consumers ship real YAML, of course.
    File(dir, "$method.trail.yaml").writeText("")
  }
}
