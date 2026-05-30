package xyz.block.trailblaze.cli

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for [TrailmapUnitTestRunner] — the bun-test phase that runs as step 3 of
 * `trailblaze check`. Coverage focuses on the pure-logic surface that doesn't require
 * spawning `bun`: file discovery and the timeout-parse fallback. The actual subprocess
 * dispatch is covered indirectly by `pr_validate_ts_tooling.sh` running against the real
 * example trailmaps in CI (where bun is on PATH and a real workspace exists).
 *
 * Why no end-to-end "tests pass" / "tests fail" cases here: `TrailmapUnitTestRunner.run()`
 * shells out to `bun test`, which CI agents have on PATH but unit-test JVMs may not. The
 * agents that drive `:trailblaze-host:test` aren't guaranteed to ship `bun`, so a test
 * that depends on it would either need conditional skipping (which masks regressions on
 * agents that DO have bun) or a stubbed PathResolver injection (which adds production
 * complexity to support a unit-only seam). The chosen split — pure-logic unit-tested
 * here, integration covered by `pr_validate_ts_tooling.sh` against the real example
 * trailmaps — keeps both layers honest without either masking failures or adding test-only
 * production code.
 */
class TrailmapUnitTestRunnerTest {

  private val workDir: File = createTempDirectory("trailblaze-trailmap-unit-test-runner-test").toFile()

  @AfterTest fun cleanup() {
    workDir.deleteRecursively()
  }

  @Test
  fun `findTestFiles returns empty list for a tools dir with no test files`() {
    val toolsDir = File(workDir, "tools").apply { mkdirs() }
    File(toolsDir, "openContact.ts").writeText("export function openContact() {}")
    File(toolsDir, "openContact.yaml").writeText("name: openContact\n")

    val result = TrailmapUnitTestRunner.findTestFiles(toolsDir.toPath())
    assertEquals(emptyList(), result)
  }

  @Test
  fun `findTestFiles returns empty list for a non-existent tools dir`() {
    // Some trailmaps ship without a tools/ tree at all (config-only). The runner needs to
    // tolerate that without throwing — the caller iterates over trailmaps and silently
    // skips ones with no tools/ before reaching the test-file walk, but the runner
    // shouldn't assume that pre-filter and instead degrade safely on its own.
    val missing = File(workDir, "absent")
    val result = TrailmapUnitTestRunner.findTestFiles(missing.toPath())
    assertEquals(emptyList(), result)
  }

  @Test
  fun `findTestFiles discovers test ts files sorted lexicographically`() {
    val toolsDir = File(workDir, "tools").apply { mkdirs() }
    // Write in non-sorted order so the assertion proves the sort actually runs (rather
    // than the filesystem happening to enumerate in lexical order on this OS).
    File(toolsDir, "zeta.test.ts").writeText("// test")
    File(toolsDir, "alpha.test.ts").writeText("// test")
    File(toolsDir, "mid.test.ts").writeText("// test")
    File(toolsDir, "non-test.ts").writeText("// not a test")
    File(toolsDir, "README.md").writeText("not a script")

    val result = TrailmapUnitTestRunner.findTestFiles(toolsDir.toPath())
    val names = result.map { it.fileName.toString() }
    assertEquals(listOf("alpha.test.ts", "mid.test.ts", "zeta.test.ts"), names)
  }

  @Test
  fun `findTestFiles recurses into subdirectories`() {
    // Some trailmaps nest test files alongside fixtures — e.g. `tools/fixtures/foo.test.ts`.
    // Bun's own discovery would walk recursively, so the runner's pre-flight walk must
    // match to avoid pre-filtering away valid test files.
    val toolsDir = File(workDir, "tools").apply { mkdirs() }
    val nested = File(toolsDir, "nested").apply { mkdirs() }
    File(toolsDir, "top.test.ts").writeText("// test")
    File(nested, "deep.test.ts").writeText("// test")

    val result = TrailmapUnitTestRunner.findTestFiles(toolsDir.toPath())
    val names = result.map { it.fileName.toString() }.sorted()
    assertEquals(listOf("deep.test.ts", "top.test.ts"), names)
  }

  @Test
  fun `findTestFiles ignores files whose name only contains test as a substring`() {
    // Defensive: `*.test.ts` discovery via `.endsWith(".test.ts")` shouldn't be tripped
    // by `latest.ts` or `protest.ts`. Documents the exact suffix match.
    val toolsDir = File(workDir, "tools").apply { mkdirs() }
    File(toolsDir, "latest.ts").writeText("// not a test")
    File(toolsDir, "protest.ts").writeText("// not a test")
    File(toolsDir, "real.test.ts").writeText("// is a test")

    val result = TrailmapUnitTestRunner.findTestFiles(toolsDir.toPath())
    assertEquals(1, result.size)
    assertEquals("real.test.ts", result.single().fileName.toString())
  }

  @Test
  fun `run returns EXIT_OK when there are no trailmaps to test`() {
    // Empty input list is the path taken when `resolveTrailmapsToCheck` came back with an
    // empty workspace — should be silently green, not USAGE.
    val exit = TrailmapUnitTestRunner.run(trailmaps = emptyList())
    assertEquals(TrailmapUnitTestRunner.EXIT_OK, exit)
  }

  @Test
  fun `resolveTestTimeoutMs returns default when env var is unset`() {
    // Default path — the env var isn't set on most JVMs running this test.
    val resolved = TrailmapUnitTestRunner.resolveTestTimeoutMs()
    assertEquals(TrailmapUnitTestRunner.DEFAULT_TEST_TIMEOUT_MS, resolved)
  }

  @Test
  fun `MIN_TEST_TIMEOUT_MS is the documented 1-minute lower clamp`() {
    // The env-var override path (`resolveTestTimeoutMs` with a parseable raw value)
    // clamps to `MIN_TEST_TIMEOUT_MS` via `coerceAtLeast`. We can't drive that path
    // here without setting an env var (which `System.getenv` can't be overridden
    // for in-JVM), but we CAN pin the floor value so a future tweak to the constant
    // doesn't silently relax the guard. The 1-minute floor is a documented contract:
    // see TrailmapUnitTestRunner.resolveTestTimeoutMs kdoc.
    assertEquals(60L * 1000L, TrailmapUnitTestRunner.MIN_TEST_TIMEOUT_MS)
  }

  @Test
  fun `EXIT codes are stable - 0 OK, 1 FAILURE, 2 USAGE`() {
    // The exit-code constants are part of the contract — CheckCommand uses max() over
    // them to surface usage > failure > ok. Pin the values so a re-numbering would
    // break this test loudly.
    assertEquals(0, TrailmapUnitTestRunner.EXIT_OK)
    assertEquals(1, TrailmapUnitTestRunner.EXIT_TEST_FAILURE)
    assertEquals(2, TrailmapUnitTestRunner.EXIT_USAGE)
    // And the ordering they imply for the worst-of-two aggregate:
    assertTrue(TrailmapUnitTestRunner.EXIT_USAGE > TrailmapUnitTestRunner.EXIT_TEST_FAILURE)
    assertTrue(TrailmapUnitTestRunner.EXIT_TEST_FAILURE > TrailmapUnitTestRunner.EXIT_OK)
  }

  @Test
  fun `DEFAULT_TEST_TIMEOUT_MS sits well above MIN_TEST_TIMEOUT_MS`() {
    // Sanity gate: a future tweak that lowers DEFAULT below MIN would silently clamp
    // and produce confusing behavior (the env-var-override path clamps, so the default
    // shouldn't be _below_ the clamp).
    assertNotNull(TrailmapUnitTestRunner.DEFAULT_TEST_TIMEOUT_MS)
    assertTrue(
      TrailmapUnitTestRunner.DEFAULT_TEST_TIMEOUT_MS > TrailmapUnitTestRunner.MIN_TEST_TIMEOUT_MS,
      "Default ${TrailmapUnitTestRunner.DEFAULT_TEST_TIMEOUT_MS}ms should be > min ${TrailmapUnitTestRunner.MIN_TEST_TIMEOUT_MS}ms",
    )
  }
}
