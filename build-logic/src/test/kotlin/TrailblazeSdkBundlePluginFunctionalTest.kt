import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Assume.assumeTrue

/**
 * Functional tests for the `trailblaze.sdk-bundle` plugin's `verifyTrailblazeSdkBundle` task.
 * Pins the behaviors the production CI gate depends on:
 *
 * 1. **Pass path** — verify reports SUCCESS and `"is fresh"` when the committed bundle
 *    matches a freshly-regenerated esbuild output.
 * 2. **Stale failure** — verify fails with the exact error message (regenerate command,
 *    likely-cause list, byte-size delta) when the committed bundle diverges. This is the
 *    surface every developer hits when they edit `sdks/typescript/src/` without running
 *    `bundleTrailblazeSdk` — drift in the message text breaks discoverability of the fix.
 * 3. **UP-TO-DATE on re-run** — the `outputs.file(tempBundle)` declaration in the plugin
 *    is what makes Gradle's input snapshotting actually engage. Without an output, a task
 *    with only inputs runs every invocation; this test pins that wiring.
 * 4. **Missing committed bundle** — verify fails with the friendly "Committed bundle
 *    missing at" message (and includes the `bundleTrailblazeSdk` regenerate command), not
 *    a generic Gradle "input file does not exist" snapshot error. Depends on the committed
 *    bundle being declared as a conditional file collection so the doLast guard is the
 *    actual gate.
 * 5. **Committed bundle mutation invalidates UP-TO-DATE** — the conditional file collection
 *    declaration must propagate edits to the committed file as input-snapshot changes.
 * 6. **Source-input change invalidates UP-TO-DATE** — edits to `package.json` (a declared
 *    input) re-run the verify task. Proves the standard input wiring works.
 * 7. **Unconfigured extension** — a consumer who applies the plugin without setting up the
 *    `trailblazeSdkBundle { }` block gets a directed error naming the required properties.
 *
 * **Why TestKit and not a unit test on a helper.** The verify task's behavior is the
 * composition of: an esbuild subprocess invocation, Gradle's input/output snapshotter, and a
 * byte-for-byte compare. A pure-logic unit test would mock the bundle bytes and miss the
 * snapshotter wiring (which is what makes UP-TO-DATE work) and the actual esbuild
 * determinism (which is what makes byte equality meaningful).
 *
 * **esbuild prerequisite.** The TS SDK's `node_modules/.bin/esbuild` must exist in the
 * checkout. CI's `pr_static_checks.sh` runs `bun install` (with npm fallback) before
 * invoking the verify task; local developers running `./gradlew :build-logic:test` need to
 * have run the same install. Tests skip via [assumeTrue] when esbuild is absent — same
 * "skip rather than falsely pass" pattern `TrailblazeBundlePluginFunctionalTest` uses for
 * symlink support.
 */
class TrailblazeSdkBundlePluginFunctionalTest {

  private val tempDirs = mutableListOf<File>()

  @AfterTest
  fun cleanupTempDirs() {
    // Don't follow symlinks during cleanup — the source-input test deliberately symlinks
    // `node_modules` and `src` from the real SDK into the isolated fixture dir, and a
    // naive recursive delete would walk through those links and wipe the real on-disk SDK.
    // (Kotlin's `File.deleteRecursively()` follows symbolic directory links by default;
    // `Files.walkFileTree` without `FOLLOW_LINKS` visits links as files instead.)
    tempDirs.forEach(::deleteRecursivelyNoFollow)
    tempDirs.clear()
  }

  @Test
  fun `verify task succeeds and logs is fresh when committed bundle matches fresh esbuild output`() {
    assumeTrue("esbuild not installed in sdks/typescript/node_modules — skipping", esbuildAvailable())
    val projectDir = newFixtureProject(committedBundleRelPath = "committed-bundle.js")
    val committedBundle = File(projectDir, "committed-bundle.js")
    primeCommittedBundle(projectDir, committedBundle)

    val result = runner(projectDir, "verifyTrailblazeSdkBundle").build()

    assertEquals(TaskOutcome.SUCCESS, result.task(":verifyTrailblazeSdkBundle")?.outcome)
    assertTrue("expected 'is fresh' marker in output: ${result.output}") {
      result.output.contains("is fresh")
    }
  }

  @Test
  fun `verify task fails with the directed error message when committed bundle is stale`() {
    assumeTrue("esbuild not installed in sdks/typescript/node_modules — skipping", esbuildAvailable())
    val projectDir = newFixtureProject(committedBundleRelPath = "committed-bundle.js")
    // Deliberately-wrong bytes. The real bundle is ~1.2MB; this 11-byte file forces both
    // a content mismatch (different bytes) AND a size delta in the error message.
    File(projectDir, "committed-bundle.js").writeText("stale bytes")

    val result = runner(projectDir, "verifyTrailblazeSdkBundle").buildAndFail()

    val output = result.output
    // The four anchors the spec calls out, each one a separately-load-bearing piece of the
    // developer-facing message. A regression that strips any of them would land green
    // without these assertions.
    assertTrue("missing primary error phrase in: $output") {
      output.contains("does not match a fresh esbuild output")
    }
    assertTrue("missing likely-cause 1 in: $output") {
      output.contains("1. You edited sdks/typescript/src/")
    }
    assertTrue("missing likely-cause 2 in: $output") {
      output.contains("2. A transitive dep")
    }
    assertTrue("missing byte-size delta line in: $output") {
      // The "stale bytes" file is exactly 11 bytes long. Pin both halves of the size delta
      // line, then assert the regenerated size is meaningfully larger than the stale file
      // (matching `\d+ bytes` and not equal to 11) — robust to future esbuild output size
      // changes (tree-shaking, minification, dep churn) that would silently invalidate a
      // tighter digit-count assertion.
      output.contains("Committed bundle size: 11 bytes") &&
        Regex("Regenerated bundle size: (\\d+) bytes").find(output)
          ?.groupValues?.get(1)?.toLong()
          ?.let { it != 11L && it > 1_000L } == true
    }
  }

  @Test
  fun `second invocation with no input changes reports UP-TO-DATE`() {
    assumeTrue("esbuild not installed in sdks/typescript/node_modules — skipping", esbuildAvailable())
    val projectDir = newFixtureProject(committedBundleRelPath = "committed-bundle.js")
    val committedBundle = File(projectDir, "committed-bundle.js")
    primeCommittedBundle(projectDir, committedBundle)

    val first = runner(projectDir, "verifyTrailblazeSdkBundle").build()
    assertEquals(TaskOutcome.SUCCESS, first.task(":verifyTrailblazeSdkBundle")?.outcome)

    val second = runner(projectDir, "verifyTrailblazeSdkBundle").build()
    assertEquals(
      TaskOutcome.UP_TO_DATE,
      second.task(":verifyTrailblazeSdkBundle")?.outcome,
      "expected UP-TO-DATE on second invocation; got ${second.output}",
    )
  }

  @Test
  fun `verify fails with friendly missing-bundle message when committed bundle does not exist`() {
    // No esbuild needed — the "missing committed bundle" guard fires before any esbuild
    // invocation, so this test can run even on a checkout that hasn't run `bun install`.
    val projectDir = newFixtureProject(committedBundleRelPath = "committed-bundle.js")
    // Intentionally do not create committed-bundle.js. The conditional file collection
    // declared by the plugin returns an empty list when the committed file is absent, so
    // the task graph proceeds and the doLast guard fires.

    val result = runner(projectDir, "verifyTrailblazeSdkBundle").buildAndFail()

    assertTrue("expected friendly missing-bundle message in: ${result.output}") {
      result.output.contains("Committed bundle missing at")
    }
    assertTrue("expected the regenerate command hint in: ${result.output}") {
      result.output.contains("./gradlew :trailblaze-scripting-bundle:bundleTrailblazeSdk")
    }
  }

  @Test
  fun `editing the committed bundle invalidates UP-TO-DATE and surfaces a stale failure`() {
    assumeTrue("esbuild not installed in sdks/typescript/node_modules — skipping", esbuildAvailable())
    val projectDir = newFixtureProject(committedBundleRelPath = "committed-bundle.js")
    val committedBundle = File(projectDir, "committed-bundle.js")
    primeCommittedBundle(projectDir, committedBundle)

    val first = runner(projectDir, "verifyTrailblazeSdkBundle").build()
    assertEquals(TaskOutcome.SUCCESS, first.task(":verifyTrailblazeSdkBundle")?.outcome)

    // Hand-edit the committed bundle. The conditional `inputs.files(provider { ... })`
    // declaration must propagate this content change into the input snapshot — without it,
    // Gradle would report UP-TO-DATE and the verify gate would silently miss a manually
    // corrupted on-disk bundle.
    committedBundle.appendBytes(byteArrayOf(0))

    val second = runner(projectDir, "verifyTrailblazeSdkBundle").buildAndFail()
    assertNotEquals(
      TaskOutcome.UP_TO_DATE,
      second.task(":verifyTrailblazeSdkBundle")?.outcome,
      "expected verify to re-run after committed-bundle edit; got ${second.output}",
    )
    assertTrue("expected stale-bundle error after edit: ${second.output}") {
      second.output.contains("does not match a fresh esbuild output")
    }
  }

  @Test
  fun `editing a source-input invalidates UP-TO-DATE`() {
    assumeTrue("esbuild not installed in sdks/typescript/node_modules — skipping", esbuildAvailable())
    // The fixture in this test builds an isolated SDK dir from symlinks; skip with an
    // assumeTrue (rather than letting `Files.createSymbolicLink` throw a generic
    // UnsupportedOperationException / AccessDenied) on filesystems that can't create
    // them — same convention `TrailblazeBundlePluginFunctionalTest` uses for its
    // symlink-loop test, so CI dashboards distinguish "ran and verified" from "couldn't
    // run here."
    assumeTrue(
      "filesystem does not support symlink creation (Windows without elevated perms?) — skipping",
      supportsSymlinks(),
    )
    // Build an isolated SDK directory that the test can mutate without disturbing other
    // tests: symlinks for `src/` and `node_modules/` (so esbuild runs against the real
    // SDK + dependencies) plus a writable copy of `package.json` (the input we'll mutate).
    val isolatedSdkDir = createTempDirectory("isolated-sdk").toFile().also(tempDirs::add)
    Files.createSymbolicLink(File(isolatedSdkDir, "src").toPath(), File(sdkDir, "src").toPath())
    Files.createSymbolicLink(
      File(isolatedSdkDir, "node_modules").toPath(),
      File(sdkDir, "node_modules").toPath(),
    )
    val packageJson = File(isolatedSdkDir, "package.json")
    Files.copy(File(sdkDir, "package.json").toPath(), packageJson.toPath())

    val projectDir = newFixtureProject(
      committedBundleRelPath = "committed-bundle.js",
      sdkDirOverride = isolatedSdkDir,
    )
    val committedBundle = File(projectDir, "committed-bundle.js")
    primeCommittedBundle(projectDir, committedBundle)

    val first = runner(projectDir, "verifyTrailblazeSdkBundle").build()
    assertEquals(TaskOutcome.SUCCESS, first.task(":verifyTrailblazeSdkBundle")?.outcome)

    // Mutate `package.json` content. Preserve valid JSON (adding a benign `keywords` field)
    // so esbuild doesn't reject the file before Gradle gets a chance to compare input
    // snapshots — the assertion below cares only about UP-TO-DATE invalidation, not the
    // bundle-content equality that the verify task would otherwise re-check.
    val original = packageJson.readText().trimEnd().trimEnd('}')
    packageJson.writeText("$original, \"keywords\": [\"touched-by-input-test\"]\n}\n")

    val second = runner(projectDir, "verifyTrailblazeSdkBundle").build()
    assertNotEquals(
      TaskOutcome.UP_TO_DATE,
      second.task(":verifyTrailblazeSdkBundle")?.outcome,
      "expected verify to re-run after package.json edit; got ${second.output}",
    )
  }

  @Test
  fun `task fails with a directed error when trailblazeSdkBundle extension is not configured`() {
    // No esbuild required — the directed-error guard fires in doFirst, before any esbuild
    // invocation. Fixture applies the plugin but intentionally omits the extension block.
    val projectDir = createTempDirectory("trailblaze-sdk-bundle-functional-unconfigured")
      .toFile().also(tempDirs::add)
    File(projectDir, "settings.gradle.kts").writeText("""rootProject.name = "fixture"""")
    File(projectDir, "build.gradle.kts").writeText(
      """
      plugins {
        id("trailblaze.sdk-bundle")
      }
      """.trimIndent(),
    )

    val result = runner(projectDir, "verifyTrailblazeSdkBundle").buildAndFail()

    assertTrue("expected directed-error naming the extension in: ${result.output}") {
      result.output.contains("trailblaze.sdk-bundle: extension is not configured")
    }
    assertTrue("expected directed-error sample code in: ${result.output}") {
      result.output.contains("trailblazeSdkBundle {")
    }
  }

  // ---- Fixtures ----

  private fun newFixtureProject(
    committedBundleRelPath: String,
    sdkDirOverride: File = sdkDir,
  ): File {
    val dir = createTempDirectory("trailblaze-sdk-bundle-functional").toFile().also(tempDirs::add)
    File(dir, "settings.gradle.kts").writeText("""rootProject.name = "fixture"""")
    File(dir, "build.gradle.kts").writeText(
      """
      plugins {
        id("trailblaze.sdk-bundle")
      }
      trailblazeSdkBundle {
        trailblazeSdkDir.set(file("${sdkDirOverride.absolutePath.escapeForKotlinString()}"))
        sdkBundleOutputFile.set(file("$committedBundleRelPath"))
      }
      """.trimIndent(),
    )
    return dir
  }

  /**
   * Generates a fresh bundle via `bundleTrailblazeSdk` into the fixture's committed-bundle
   * path. Doing this once per test (rather than checking in a static fixture bundle) keeps
   * the test resilient to esbuild version drift — whatever the host's esbuild produces
   * becomes the "committed" content the verify task should match.
   */
  private fun primeCommittedBundle(projectDir: File, committedBundle: File) {
    val result = runner(projectDir, "bundleTrailblazeSdk").build()
    assertEquals(
      TaskOutcome.SUCCESS,
      result.task(":bundleTrailblazeSdk")?.outcome,
      "priming run failed: ${result.output}",
    )
    assertTrue("expected priming run to write the committed bundle") { committedBundle.isFile }
  }

  private fun runner(projectDir: File, vararg args: String): GradleRunner =
    GradleRunner.create()
      .withProjectDir(projectDir)
      .withArguments(*args)
      .withPluginClasspath()
      .forwardOutput()

  private fun esbuildAvailable(): Boolean = File(sdkDir, "node_modules/.bin/esbuild").exists()

  /**
   * `trailblaze.sdkDir` is set in `build-logic/build.gradle.kts` for the `test` task. When
   * the property is missing (typical when an IDE runs an individual test from a gutter
   * action without delegating to Gradle), fail fast with a directed message rather than
   * falling back to a `user.dir`-relative path that resolves differently depending on the
   * IDE's working-dir choice. Listing the workaround in the error keeps the surface
   * actionable from inside the IDE.
   */
  private val sdkDir: File by lazy {
    val prop = System.getProperty("trailblaze.sdkDir")
      ?: error(
        "`trailblaze.sdkDir` system property not set. Run via `./gradlew :build-logic:test` " +
          "(which wires the property automatically), or set the JVM arg " +
          "`-Dtrailblaze.sdkDir=/abs/path/to/sdks/typescript` in your IDE's run configuration.",
      )
    File(prop)
  }

  /**
   * Escapes a path for safe interpolation into a Kotlin string literal in the fixture
   * `build.gradle.kts`. Backslashes need to survive both the Kotlin and Gradle compile
   * passes; doubling here gives the script `\\` which the Kotlin compiler then reduces to
   * a single `\` in the actual `file(...)` call. (On macOS / Linux there are no backslashes
   * in paths so this is a no-op, but keeping the escape preserves Windows correctness in
   * case a future contributor runs the build-logic test suite from there.)
   */
  private fun String.escapeForKotlinString(): String =
    replace("\\", "\\\\").replace("\"", "\\\"")

  private fun deleteRecursivelyNoFollow(file: File) {
    val path = file.toPath()
    if (!Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return
    // Best-effort cleanup — catch IO errors and log to stderr rather than letting them
    // propagate out of @AfterTest. A throwing cleanup shadows the actual test failure,
    // and the developer ends up debugging the wrong thing.
    try {
      Files.walkFileTree(
        path,
        object : SimpleFileVisitor<Path>() {
          override fun visitFile(p: Path, attrs: BasicFileAttributes): FileVisitResult {
            Files.delete(p)
            return FileVisitResult.CONTINUE
          }

          override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
            Files.delete(dir)
            return FileVisitResult.CONTINUE
          }
        },
      )
    } catch (e: IOException) {
      System.err.println("cleanup of $file failed: $e")
    }
  }

  private fun supportsSymlinks(): Boolean = try {
    val probeDir = createTempDirectory("symlink-probe").toFile().also(tempDirs::add)
    val target = File(probeDir, "target").apply { writeText("x") }
    val link = File(probeDir, "link").toPath()
    Files.createSymbolicLink(link, target.toPath())
    true
  } catch (_: UnsupportedOperationException) {
    false
  } catch (_: IOException) {
    false
  }
}
