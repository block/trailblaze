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
 * Functional tests for the `trailblaze.sdk-dts-bundle` plugin's
 * `verifyTrailblazeSdkDtsBundle` task. Mirrors the shape of
 * [TrailblazeSdkBundlePluginFunctionalTest] — the two plugins live in lockstep, and the
 * verify-gate surfaces (directed error messages, byte-diff failure mode, UP-TO-DATE
 * wiring) are the surface the per-PR CI gate depends on.
 *
 * Coverage:
 *
 * 1. **Pass path** — verify reports SUCCESS and `"is fresh"` when the committed bundle
 *    matches a fresh dts-bundle-generator output.
 * 2. **Stale failure** — verify fails with the exact directed-error message (regenerate
 *    command, likely-cause list, byte-size delta) when the committed bundle diverges.
 *    Drift in the message text breaks discoverability of the fix.
 * 3. **Missing committed bundle** — verify fails with the friendly "Committed .d.ts
 *    bundle missing at" message (and includes the regenerate command), not a generic
 *    Gradle "input file does not exist" snapshot error.
 * 4. **Unconfigured extension** — a consumer who applies the plugin without setting up
 *    the `trailblazeSdkDtsBundle { }` block gets a directed error naming the required
 *    properties.
 *
 * **dts-bundle-generator prerequisite.** The TS SDK's
 * `node_modules/.bin/dts-bundle-generator` must exist in the checkout. CI's
 * `pr_static_checks.sh` runs `bun install` (with npm fallback) before invoking the
 * verify task; local developers running `./gradlew :build-logic:test` need to have run
 * the same install. Tests skip via [assumeTrue] when the binary is absent — same pattern
 * [TrailblazeSdkBundlePluginFunctionalTest] uses for esbuild.
 */
class TrailblazeSdkDtsBundlePluginFunctionalTest {

  private val tempDirs = mutableListOf<File>()

  @AfterTest
  fun cleanupTempDirs() {
    tempDirs.forEach { it.deleteRecursively() }
    tempDirs.clear()
  }

  @Test
  fun `verify task succeeds and logs is fresh when committed bundle matches`() {
    assumeTrue(
      "dts-bundle-generator not installed in sdks/typescript/node_modules — skipping",
      dtsBundleGeneratorAvailable(),
    )
    val projectDir = newFixtureProject(committedBundleRelPath = "committed-bundle.d.ts")
    val committedBundle = File(projectDir, "committed-bundle.d.ts")
    primeCommittedBundle(projectDir, committedBundle)

    val result = runner(projectDir, "verifyTrailblazeSdkDtsBundle").build()

    assertEquals(TaskOutcome.SUCCESS, result.task(":verifyTrailblazeSdkDtsBundle")?.outcome)
    assertTrue("expected 'is fresh' marker in output: ${result.output}") {
      result.output.contains("is fresh")
    }
  }

  @Test
  fun `verify task fails with the directed error message when committed bundle is stale`() {
    assumeTrue(
      "dts-bundle-generator not installed in sdks/typescript/node_modules — skipping",
      dtsBundleGeneratorAvailable(),
    )
    val projectDir = newFixtureProject(committedBundleRelPath = "committed-bundle.d.ts")
    // Deliberately-wrong bytes. The real bundle is ~270KB; this short file forces both
    // a content mismatch AND a size delta in the error message.
    File(projectDir, "committed-bundle.d.ts").writeText("// stale bytes\n")

    val result = runner(projectDir, "verifyTrailblazeSdkDtsBundle").buildAndFail()

    val output = result.output
    assertTrue("missing primary error phrase in: $output") {
      output.contains("does not match a fresh dts-bundle-generator output")
    }
    assertTrue("missing likely-cause 1 in: $output") {
      output.contains("1. You edited sdks/typescript/src/")
    }
    assertTrue("missing regenerate command in: $output") {
      output.contains(":trailblaze-models:bundleTrailblazeSdkDts")
    }
    assertTrue("missing npm ci lockfile-recovery suggestion in: $output") {
      output.contains("npm ci")
    }
    assertTrue("missing byte-size delta line in: $output") {
      output.contains("Committed bundle size: 15 bytes") &&
        Regex("Regenerated bundle size: (\\d+) bytes").find(output)
          ?.groupValues?.get(1)?.toLong()
          ?.let { it != 15L && it > 1_000L } == true
    }
  }

  @Test
  fun `verify task fails with friendly missing-bundle error when no committed file exists`() {
    val projectDir = newFixtureProject(committedBundleRelPath = "committed-bundle.d.ts")
    // No `primeCommittedBundle` — the committed file deliberately doesn't exist.

    val result = runner(projectDir, "verifyTrailblazeSdkDtsBundle").buildAndFail()

    assertTrue("expected friendly 'missing' error message in: ${result.output}") {
      result.output.contains("Committed .d.ts bundle missing at")
    }
    assertTrue("expected the regenerate command in: ${result.output}") {
      result.output.contains(":trailblaze-models:bundleTrailblazeSdkDts")
    }
  }

  @Test
  fun `bundle task appends every curated runtime-globals declaration to the regenerated bundle`() {
    assumeTrue(
      "dts-bundle-generator not installed in sdks/typescript/node_modules — skipping",
      dtsBundleGeneratorAvailable(),
    )
    val projectDir = newFixtureProject(committedBundleRelPath = "committed-bundle.d.ts")
    val committedBundle = File(projectDir, "committed-bundle.d.ts")
    primeCommittedBundle(projectDir, committedBundle)

    // Assert presence of EVERY curated runtime global, not just one representative.
    // If a refactor accidentally narrows or removes any entry from
    // runtime-globals.d.ts, the alarm fires on this list — a presence-of-`URL`-only
    // check would happily pass while `fetch` or `setTimeout` silently vanished.
    val rendered = committedBundle.readText(Charsets.UTF_8)
    val declareGlobalStart = rendered.indexOf("declare global {")
    assertTrue("bundle should contain the `declare global` block: ${rendered.takeLast(400)}") {
      declareGlobalStart >= 0
    }
    // Walk braces from `declare global {` to find its matching closing `}`. Asserting
    // every curated entry lives BETWEEN those two positions catches a refactor that
    // accidentally splits the declarations across two `declare global { … }` blocks
    // (which TypeScript accepts but is a structural regression — the curated set
    // should remain a single block per the file kdoc's "future ABI promise" framing).
    val declareGlobalEnd = findMatchingCloseBrace(rendered, declareGlobalStart + "declare global ".length)
    assertTrue(
      "could not find matching `}` for `declare global {` — bundle structure unexpected",
    ) { declareGlobalEnd > declareGlobalStart }
    val declareGlobalBody = rendered.substring(declareGlobalStart, declareGlobalEnd + 1)
    val mustContain = listOf(
      "var URL: {",
      "var URLSearchParams: {",
      "var AbortController: {",
      "var AbortSignal: {",
      "var Headers: {",
      "var Request: {",
      "var Response: {",
      "var DOMException: {",
      "var console: Console;",
      "function setTimeout(",
      "function clearTimeout(",
      "function fetch(",
      "interface TimeoutHandle {",
    )
    mustContain.forEach { entry ->
      assertTrue("`$entry` must appear inside the single `declare global { … }` block") {
        declareGlobalBody.contains(entry)
      }
    }
  }

  @Test
  fun `bundle task rejects an empty runtime-globals dot d dot ts with a directed error`() {
    val projectDir = newSyntheticSdkFixture(name = "trailblaze-sdk-dts-bundle-empty-globals") {
      // Empty (whitespace-only) runtime-globals.d.ts — exercises the
      // `check(runtimeGlobals.isNotBlank())` guard inside appendRuntimeGlobals.
      // The append helper runs AFTER dts-bundle-generator, so we have to make
      // node_modules available; symlinking from the real SDK keeps the binary
      // resolvable while letting us hand-craft the runtime-globals.d.ts contents.
      File(it, RUNTIME_GLOBALS_FILENAME).writeText("\n   \n\t\n")
      symlinkNodeModulesFromRealSdk(it)
    }
    assumeTrue(
      "dts-bundle-generator not installed in sdks/typescript/node_modules — skipping",
      File(projectDir, "synthetic-sdk/node_modules/.bin/dts-bundle-generator").exists(),
    )

    val result = runner(projectDir, "bundleTrailblazeSdkDts").buildAndFail()

    assertTrue("expected empty-file directed error: ${result.output}") {
      result.output.contains("is empty") &&
        result.output.contains(RUNTIME_GLOBALS_FILENAME)
    }
  }

  @Test
  fun `verify task fails with a directed error when runtime-globals dot d dot ts is missing`() {
    // Sibling of the bundle-task missing-file test below — covers the SECOND call
    // site of `requireRuntimeGlobalsFile`, which fires from
    // `verifyTrailblazeSdkDtsBundle`'s input declaration. A refactor that
    // accidentally drops `runtime-globals.d.ts` from the verify task's input wiring
    // would let an SDK contributor with no committed-bundle land a regression that
    // CI doesn't catch until production; this test guards that path.
    val projectDir = newSyntheticSdkFixture(name = "trailblaze-sdk-dts-bundle-no-globals-verify") {
      // Deliberately do NOT create runtime-globals.d.ts — exercises the
      // requireRuntimeGlobalsFile() guard fired from `verifyTrailblazeSdkDtsBundle`'s
      // input declaration.
    }
    // Plant a non-empty committed bundle at the projectDir root so the "missing
    // committed file" path doesn't fire first — we want the directed error to come
    // from the runtime-globals.d.ts check, not the committed-bundle existence check.
    File(projectDir, "committed-bundle.d.ts").writeText("// placeholder\n")

    val result = runner(projectDir, "verifyTrailblazeSdkDtsBundle").buildAndFail()

    assertTrue("expected directed missing-file message naming the file: ${result.output}") {
      result.output.contains("Runtime-globals declaration file") &&
        result.output.contains(RUNTIME_GLOBALS_FILENAME)
    }
  }

  @Test
  fun `bundle task fails with a directed error when runtime-globals dot d dot ts is missing`() {
    val projectDir = newSyntheticSdkFixture(name = "trailblaze-sdk-dts-bundle-no-globals") {
      // Deliberately do NOT create runtime-globals.d.ts. The task fails at input
      // resolution via requireRuntimeGlobalsFile() before dts-bundle-generator is
      // ever invoked, so we don't need to symlink node_modules — the failure
      // surface under test is the input-declaration-time guard, not the append.
    }

    val result = runner(projectDir, "bundleTrailblazeSdkDts").buildAndFail()

    // Loose substring matches — the exact recovery phrasing is intentionally not
    // pinned (a future operations PR rewording the message shouldn't break this
    // test). The contract under test is "error names the file AND offers a git
    // recovery path" — anything tighter is brittle coupling.
    assertTrue("expected directed missing-file message naming the file: ${result.output}") {
      result.output.contains("Runtime-globals declaration file") &&
        result.output.contains(RUNTIME_GLOBALS_FILENAME)
    }
    assertTrue("expected a git-based recovery hint mentioning the file: ${result.output}") {
      (result.output.contains("git restore") || result.output.contains("git checkout")) &&
        result.output.contains(RUNTIME_GLOBALS_FILENAME)
    }
  }

  @Test
  fun `task fails with a directed error when trailblazeSdkDtsBundle extension is not configured`() {
    val projectDir = createTempDirectory("trailblaze-sdk-dts-bundle-unconfigured")
      .toFile().also(tempDirs::add)
    File(projectDir, "settings.gradle.kts").writeText("""rootProject.name = "fixture"""")
    File(projectDir, "build.gradle.kts").writeText(
      """
      plugins {
        id("trailblaze.sdk-dts-bundle")
      }
      """.trimIndent(),
    )

    val result = runner(projectDir, "verifyTrailblazeSdkDtsBundle").buildAndFail()

    assertTrue("expected directed-error naming the extension in: ${result.output}") {
      result.output.contains("trailblaze.sdk-dts-bundle: extension is not configured")
    }
    assertTrue("expected directed-error sample code in: ${result.output}") {
      result.output.contains("trailblazeSdkDtsBundle {")
    }
  }

  // ---- Fixtures ----

  private fun newFixtureProject(committedBundleRelPath: String): File {
    val dir = createTempDirectory("trailblaze-sdk-dts-bundle-functional").toFile()
      .also(tempDirs::add)
    File(dir, "settings.gradle.kts").writeText("""rootProject.name = "fixture"""")
    File(dir, "build.gradle.kts").writeText(
      """
      plugins {
        id("trailblaze.sdk-dts-bundle")
      }
      trailblazeSdkDtsBundle {
        trailblazeSdkDir.set(file("${sdkDir.absolutePath.escapeForKotlinString()}"))
        sdkDtsBundleOutputFile.set(file("$committedBundleRelPath"))
      }
      """.trimIndent(),
    )
    return dir
  }

  /**
   * Generates a fresh `.d.ts` bundle via `bundleTrailblazeSdkDts` into the fixture's
   * committed-bundle path. Doing this once per test (rather than checking in a static
   * fixture bundle) keeps the test resilient to dts-bundle-generator version drift —
   * whatever the host's binary produces becomes the "committed" content the verify task
   * should match.
   */
  private fun primeCommittedBundle(projectDir: File, committedBundle: File) {
    val result = runner(projectDir, "bundleTrailblazeSdkDts").build()
    assertEquals(
      TaskOutcome.SUCCESS,
      result.task(":bundleTrailblazeSdkDts")?.outcome,
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

  private fun dtsBundleGeneratorAvailable(): Boolean =
    File(sdkDir, "node_modules/.bin/dts-bundle-generator").exists()

  /**
   * Build a fixture project around a synthetic SDK layout. Used by the missing-file
   * and empty-file tests that need to control the SDK layout precisely without
   * touching the real `sdks/typescript/` (which other tests, and the
   * developer's IDE, depend on remaining stable).
   *
   * The fixture has:
   *  - A Gradle project with the `trailblaze.sdk-dts-bundle` plugin applied,
   *    pointing `trailblazeSdkDir` at a freshly-created `synthetic-sdk/` subdir.
   *  - A `synthetic-sdk/src/index.ts` + `synthetic-sdk/package.json` so the
   *    inputs declaration doesn't blow up on missing-required-files unrelated to
   *    the behavior under test.
   *  - Whatever else the caller's [configure] lambda creates inside the synthetic
   *    SDK dir — typically a hand-crafted `runtime-globals.d.ts` (or its absence)
   *    plus a `node_modules` symlink if the test needs to actually exec
   *    dts-bundle-generator.
   */
  private fun newSyntheticSdkFixture(name: String, configure: (syntheticSdk: File) -> Unit): File {
    val projectDir = createTempDirectory(name).toFile().also(tempDirs::add)
    val syntheticSdk = File(projectDir, "synthetic-sdk").apply { mkdirs() }
    File(syntheticSdk, "src").mkdirs()
    File(syntheticSdk, "src/index.ts").writeText("export {};\n")
    File(syntheticSdk, "package.json").writeText("{}\n")

    configure(syntheticSdk)

    File(projectDir, "settings.gradle.kts").writeText("""rootProject.name = "fixture"""")
    File(projectDir, "build.gradle.kts").writeText(
      """
      plugins {
        id("trailblaze.sdk-dts-bundle")
      }
      trailblazeSdkDtsBundle {
        trailblazeSdkDir.set(file("${syntheticSdk.absolutePath.escapeForKotlinString()}"))
        sdkDtsBundleOutputFile.set(file("committed-bundle.d.ts"))
      }
      """.trimIndent(),
    )
    return projectDir
  }

  /**
   * Symlink the real SDK's `node_modules` into the synthetic SDK dir so
   * `node_modules/.bin/dts-bundle-generator` resolves. Used by the empty-file
   * test, which exercises the append-time guard and therefore needs the bundler
   * to actually run. Reuses (rather than re-installs) the real SDK's
   * node_modules — the install is too slow + registry-gated for a unit-test loop.
   *
   * Swallows symlink-creation failures (Windows without Developer Mode throws
   * `UnsupportedOperationException` or `AccessDeniedException` from
   * `createSymbolicLink`). The caller's `assumeTrue` gate keys off the resulting
   * file's existence, so a swallowed failure naturally turns into a test SKIP
   * rather than a FAILURE — Windows CI runners that can't symlink don't break
   * the build, they just skip the gated tests like a missing `dts-bundle-generator`
   * binary would.
   */
  private fun symlinkNodeModulesFromRealSdk(syntheticSdk: File) {
    val realNodeModules = File(sdkDir, "node_modules")
    if (!realNodeModules.isDirectory) return // assumeTrue gate inside the test handles this case
    try {
      java.nio.file.Files.createSymbolicLink(
        File(syntheticSdk, "node_modules").toPath(),
        realNodeModules.toPath(),
      )
    } catch (_: UnsupportedOperationException) {
      // Filesystem doesn't support symlinks (e.g. Windows without Developer Mode).
      // The test's assumeTrue gate will skip when node_modules can't be resolved.
    } catch (_: java.nio.file.FileSystemException) {
      // AccessDeniedException, FileAlreadyExistsException, or other filesystem
      // edge cases — same outcome: the test gate skips.
    }
  }

  /**
   * Walk `body` from `openBraceIndex` (the position of an opening `{`) until the
   * matching closing `}`, respecting nested braces. Returns the index of the
   * matching `}`, or -1 if no match is found within the string.
   *
   * Used by the "appends every curated declaration" test to assert that all
   * curated entries live inside ONE `declare global { … }` block — a refactor
   * that splits them across multiple blocks would pass a naive substring check
   * but fail this single-block invariant. Quote/comment handling is intentionally
   * absent — `runtime-globals.d.ts` doesn't use string literals containing `{`
   * or `}` at the top level, and parsing TypeScript here would be massive overkill.
   */
  private fun findMatchingCloseBrace(body: String, openBraceIndex: Int): Int {
    require(body[openBraceIndex] == '{') {
      "expected '{' at index $openBraceIndex, got '${body[openBraceIndex]}'"
    }
    var depth = 1
    var i = openBraceIndex + 1
    while (i < body.length && depth > 0) {
      when (body[i]) {
        '{' -> depth++
        '}' -> depth--
      }
      if (depth == 0) return i
      i++
    }
    return -1
  }

  /**
   * `trailblaze.sdkDir` is set in `build-logic/build.gradle.kts` for the `test` task. See
   * the sibling [TrailblazeSdkBundlePluginFunctionalTest.sdkDir] for the rationale — this
   * test reuses the same wiring.
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

  private fun String.escapeForKotlinString(): String =
    replace("\\", "\\\\").replace("\"", "\\\"")
}
