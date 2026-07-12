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
  fun `a bare unified trail yaml directly in a class directory fails with a layout hint`() {
    // A bare `trail.yaml` DIRECTLY in a class dir has no method name to derive (supported
    // recordings live one level down: `<ClassName>/<methodName>/trail.yaml`) — it would silently
    // never get a @Test. Must fail loudly with the supported layouts spelled out.
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
    File(projectDir, "trails/FooLongTest/trail.yaml").writeText("")

    val result = gradleRunner(projectDir, "generateAndroidTrailJUnitShells").buildAndFail()
    assertTrue("expected the offending file to be named: ${result.output}") {
      result.output.contains("FooLongTest/trail.yaml")
    }
    assertTrue("expected both supported layouts in the error: ${result.output}") {
      result.output.contains("<methodName>.trail.yaml") &&
        result.output.contains("<methodName>/trail.yaml")
    }
  }

  @Test
  fun `a class directory containing only a bare unified trail yaml fails instead of soft-skipping`() {
    // Without the gate this case hits the "no <ClassName>/*.trail.yaml files" lifecycle log — a
    // soft no-op that reads as success while the trail never runs.
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
    File(projectDir, "trails/FooLongTest").mkdirs()
    File(projectDir, "trails/FooLongTest/trail.yaml").writeText("")

    // buildAndFail is the load-bearing assertion — the pre-gate behavior for this layout was a
    // SUCCESSFUL no-op ("has no <ClassName>/*.trail.yaml files").
    val result = gradleRunner(projectDir, "generateAndroidTrailJUnitShells").buildAndFail()
    assertTrue("expected the offending file to be named: ${result.output}") {
      result.output.contains("FooLongTest/trail.yaml")
    }
  }

  @Test
  fun `a bare unified trail yaml at the assets root fails too`() {
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
    File(projectDir, "trails").mkdirs()
    File(projectDir, "trails/trail.yaml").writeText("")

    val result = gradleRunner(projectDir, "generateAndroidTrailJUnitShells").buildAndFail()
    assertTrue("expected the root-level bare file to be named: ${result.output}") {
      result.output.contains("trails/trail.yaml")
    }
  }

  @Test
  fun `a recording directory generates a @Test method named after the directory`() {
    // The directory-per-test unified layout (`<ClassName>/<methodName>/trail.yaml`) is the
    // default new-recording output and what automated recording pipelines produce — it must
    // generate a method, mixed freely with named trails in the same class dir. The emitted
    // inline-rule call passes the DIRECTORY path so the runtime picks the best file inside
    // (classifier-specific recording → trail.yaml → blaze.yaml).
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
    writeRecordingDirTrail(projectDir, className = "FooLongTest", method = "checkout_happy_path")

    val result = gradleRunner(projectDir, "generateAndroidTrailJUnitShells").build()
    assertEquals(TaskOutcome.SUCCESS, result.task(":generateAndroidTrailJUnitShells")?.outcome)
    val source =
      File(
          projectDir,
          "build/generated/source/trailblazeTrails/androidTest/xyz/fixture/app/FooLongTest.kt",
        )
        .readText()
    assertTrue("expected the named trail's method: $source") {
      source.contains(
        "@Test fun alpha() = rule.runFromAsset(\"trails/FooLongTest/alpha.trail.yaml\")"
      )
    }
    assertTrue("expected the recording dir's method with a DIRECTORY asset path: $source") {
      source.contains(
        "@Test fun checkout_happy_path() = rule.runFromAsset(\"trails/FooLongTest/checkout_happy_path\")"
      )
    }
  }

  @Test
  fun `a class directory containing only recording directories still generates a shell`() {
    // A fully-unified class dir (no named trails at all) is the steady-state CI-pipeline shape —
    // it must not fall into the "no trails — skipping" soft path.
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
    writeRecordingDirTrail(projectDir, className = "FooLongTest", method = "checkout_happy_path")

    val result = gradleRunner(projectDir, "generateAndroidTrailJUnitShells").build()
    assertEquals(TaskOutcome.SUCCESS, result.task(":generateAndroidTrailJUnitShells")?.outcome)
    val source =
      File(
          projectDir,
          "build/generated/source/trailblazeTrails/androidTest/xyz/fixture/app/FooLongTest.kt",
        )
        .readText()
    assertTrue("expected the recording dir's method: $source") {
      source.contains(
        "@Test fun checkout_happy_path() = rule.runFromAsset(\"trails/FooLongTest/checkout_happy_path\")"
      )
    }
  }

  @Test
  fun `sibling files inside a recording directory do not add methods`() {
    // A unified recording dir routinely carries more than the bare trail.yaml — a
    // classifier-specific recording, a blaze.yaml. Exactly ONE method (named after the directory)
    // must come out; the siblings are the runtime's business, not the generator's.
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
    writeRecordingDirTrail(projectDir, className = "FooLongTest", method = "checkout_happy_path")
    File(projectDir, "trails/FooLongTest/checkout_happy_path/android-phone.trail.yaml")
      .writeText("")
    File(projectDir, "trails/FooLongTest/checkout_happy_path/blaze.yaml").writeText("")

    val result = gradleRunner(projectDir, "generateAndroidTrailJUnitShells").build()
    assertEquals(TaskOutcome.SUCCESS, result.task(":generateAndroidTrailJUnitShells")?.outcome)
    val source =
      File(
          projectDir,
          "build/generated/source/trailblazeTrails/androidTest/xyz/fixture/app/FooLongTest.kt",
        )
        .readText()
    assertTrue("expected exactly the recording dir's method: $source") {
      source.contains(
        "@Test fun checkout_happy_path() = rule.runFromAsset(\"trails/FooLongTest/checkout_happy_path\")"
      )
    }
    assertEquals(
      1,
      Regex("@Test fun ").findAll(source).count(),
      "expected exactly one @Test method (no method for the classifier sibling): $source",
    )
  }

  @Test
  fun `a recording directory and a named trail differing only in case collide loudly`() {
    // `Alpha/trail.yaml` and `alpha.trail.yaml` derive distinct method names that write to the
    // same generated file on case-insensitive filesystems (APFS / NTFS) — one method would
    // silently vanish. The case-insensitive gate must fail naming both.
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
    writeRecordingDirTrail(projectDir, className = "FooLongTest", method = "Alpha")

    val result = gradleRunner(projectDir, "generateAndroidTrailJUnitShells").buildAndFail()
    assertTrue("expected a case-insensitive collision error naming both: ${result.output}") {
      result.output.contains("Case-insensitive trail-name collision") &&
        result.output.contains("Alpha") &&
        result.output.contains("alpha")
    }
  }

  @Test
  fun `a bare unified trail yaml nested deeper than one directory fails`() {
    // Only DIRECT subdirectories of a class dir map to methods (matching the one-level probing of
    // the runtime resolvers) — a deeper bare file (e.g. a copied suite/section/case tree) would
    // silently never run, so it fails loudly instead.
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
    File(projectDir, "trails/FooLongTest/suite_123/case_456").mkdirs()
    File(projectDir, "trails/FooLongTest/suite_123/case_456/trail.yaml").writeText("")

    val result = gradleRunner(projectDir, "generateAndroidTrailJUnitShells").buildAndFail()
    assertTrue("expected the deep bare file to be named: ${result.output}") {
      result.output.contains("FooLongTest/suite_123/case_456/trail.yaml")
    }
  }

  @Test
  fun `a named trail and a recording directory with the same method name collide loudly`() {
    // `foo.trail.yaml` and `foo/trail.yaml` both derive the method name `foo` — emitting both
    // would be a Kotlin compile error in the generated file; failing at generate time keeps the
    // error next to the offending files.
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
    writeRecordingDirTrail(projectDir, className = "FooLongTest", method = "alpha")

    val result = gradleRunner(projectDir, "generateAndroidTrailJUnitShells").buildAndFail()
    assertTrue("expected a duplicate-method error naming both sources: ${result.output}") {
      result.output.contains("Duplicate trail method names") &&
        result.output.contains("FooLongTest/alpha.trail.yaml") &&
        result.output.contains("FooLongTest/alpha/trail.yaml")
    }
  }

  @Test
  fun `a recording directory whose name is not a valid Kotlin identifier fails at generate time`() {
    // Directory names become method names, so they go through the same identifier validation as
    // named trail filenames — a kebab-case recording dir fails with a directed error instead of a
    // Kotlin compile error in the generated file.
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
    writeRecordingDirTrail(projectDir, className = "FooLongTest", method = "checkout-happy-path")

    val result = gradleRunner(projectDir, "generateAndroidTrailJUnitShells").buildAndFail()
    assertTrue("expected the identifier validation to name the dir: ${result.output}") {
      result.output.contains("Invalid Kotlin method identifier `checkout-happy-path`") &&
        result.output.contains("FooLongTest/checkout-happy-path/trail.yaml")
    }
  }

  @Test
  fun `a bare unified trail yaml under the top-level config tree is exempt`() {
    // `trails/config/**` is the documented non-codegen tree (trailmap/target YAML, staged tool
    // bundles) — the gate must not reach into it under implicit ownership.
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
    File(projectDir, "trails/config/trailmaps/sample").mkdirs()
    File(projectDir, "trails/config/trailmaps/sample/trail.yaml").writeText("")

    val result = gradleRunner(projectDir, "generateAndroidTrailJUnitShells").build()
    assertEquals(TaskOutcome.SUCCESS, result.task(":generateAndroidTrailJUnitShells")?.outcome)
  }

  @Test
  fun `a bare unified trail yaml in an allow-listed directory still fails`() {
    // The positive half of the onlyClassNames boundary: the sibling exemption test alone can't
    // tell "gate scoped correctly" from "gate disabled under a non-empty allow-list".
    val projectDir =
      newFixtureProject(
        androidFixtureBuildScript(
          """
        trailblazeAndroid {
          packageName = "xyz.fixture.app"
          trailsAssetsDir = layout.projectDirectory.dir("trails")
          onlyClassNames = setOf("FooLongTest")
        }
        """
        ),
        tempDirs,
      )
    writeTrail(projectDir, className = "FooLongTest", method = "alpha")
    File(projectDir, "trails/FooLongTest/trail.yaml").writeText("")

    val result = gradleRunner(projectDir, "generateAndroidTrailJUnitShells").buildAndFail()
    assertTrue("expected the bare file in the listed dir to be named: ${result.output}") {
      result.output.contains("FooLongTest/trail.yaml")
    }
  }

  @Test
  fun `a bare unified trail yaml in a non-allow-listed directory is left alone`() {
    // With a non-empty onlyClassNames, non-listed dirs back hand-written shells and are
    // documented as "left untouched" — the bare-file gate must respect the same boundary.
    val projectDir =
      newFixtureProject(
        androidFixtureBuildScript(
          """
        trailblazeAndroid {
          packageName = "xyz.fixture.app"
          trailsAssetsDir = layout.projectDirectory.dir("trails")
          onlyClassNames = setOf("FooLongTest")
        }
        """
        ),
        tempDirs,
      )
    writeTrail(projectDir, className = "FooLongTest", method = "alpha")
    File(projectDir, "trails/HandWrittenTest").mkdirs()
    File(projectDir, "trails/HandWrittenTest/trail.yaml").writeText("")

    val result = gradleRunner(projectDir, "generateAndroidTrailJUnitShells").build()
    assertEquals(TaskOutcome.SUCCESS, result.task(":generateAndroidTrailJUnitShells")?.outcome)
  }

  @Test
  fun `adding a misplaced bare unified trail yaml after a successful build re-runs the task and fails`() {
    // Pins the input tracking: `*.trail.yaml` does not match `trail.yaml`, so without the extra
    // include the second invocation would report UP-TO-DATE and the misplacement gate would
    // never fire.
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

    val first = gradleRunner(projectDir, "generateAndroidTrailJUnitShells").build()
    assertEquals(TaskOutcome.SUCCESS, first.task(":generateAndroidTrailJUnitShells")?.outcome)

    File(projectDir, "trails/FooLongTest/trail.yaml").writeText("")
    val second = gradleRunner(projectDir, "generateAndroidTrailJUnitShells").buildAndFail()
    assertTrue("expected the new bare file to fail the re-run: ${second.output}") {
      second.output.contains("FooLongTest/trail.yaml")
    }
  }

  @Test
  fun `adding a recording directory after a successful build re-runs the task and emits its method`() {
    // The same `**/trail.yaml` input include drives the positive path: a recording dir dropped in
    // after a green build must re-run codegen and grow the shell, not report UP-TO-DATE.
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

    val first = gradleRunner(projectDir, "generateAndroidTrailJUnitShells").build()
    assertEquals(TaskOutcome.SUCCESS, first.task(":generateAndroidTrailJUnitShells")?.outcome)

    writeRecordingDirTrail(projectDir, className = "FooLongTest", method = "checkout_happy_path")
    val second = gradleRunner(projectDir, "generateAndroidTrailJUnitShells").build()
    assertEquals(TaskOutcome.SUCCESS, second.task(":generateAndroidTrailJUnitShells")?.outcome)
    val source =
      File(
          projectDir,
          "build/generated/source/trailblazeTrails/androidTest/xyz/fixture/app/FooLongTest.kt",
        )
        .readText()
    assertTrue("expected the new recording dir's method after the re-run: $source") {
      source.contains(
        "@Test fun checkout_happy_path() = rule.runFromAsset(\"trails/FooLongTest/checkout_happy_path\")"
      )
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

  /** The directory-per-test unified layout: `trails/<ClassName>/<method>/trail.yaml`. */
  private fun writeRecordingDirTrail(projectDir: File, className: String, method: String) {
    val dir = File(projectDir, "trails/$className/$method").apply { mkdirs() }
    File(dir, "trail.yaml").writeText("")
  }
}
