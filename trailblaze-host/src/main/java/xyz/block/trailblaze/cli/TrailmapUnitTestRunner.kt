package xyz.block.trailblaze.cli

import xyz.block.trailblaze.util.Console
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Runs per-trailmap TypeScript unit tests (`*.test.ts`) via `bun test`. Invoked as the third
 * phase of [CheckCommand] after materialize + typecheck.
 *
 * **Why this is its own object (and not a subcommand).** Up through PR #3234 there was a
 * standalone `trailblaze test` subcommand. The CLI surface that ships meant "Trailblaze
 * test" reads as "run a trail" (Trailblaze's entire raison d'être), which was confusing.
 * The KISS resolution: tests are part of `trailblaze check`'s validation surface, the way
 * `gradle check` includes unit tests. If we ever need a finer-grained invocation
 * ("only run tests, skip everything else"), that becomes `trailblaze check tests` as a
 * subcommand — but we cross that bridge when something demands it, not preemptively.
 *
 * **Discovery + spawn semantics.** Walk each trailmap's `tools/` for `*.test.ts` files. If a
 * trailmap has none, skip silently. If a trailmap has tests but no sibling `tools/tsconfig.json`,
 * fail with a directed error pointing at `trailblaze check` itself (the command that
 * emits the tsconfig — running `check` start-to-end is supposed to materialize the file
 * before this phase reaches it, so an absent tsconfig here is a real bug in the
 * materialize step). Otherwise spawn `bun test <toolsDir>` with the subprocess cwd
 * pinned to the tools dir (matters: bun discovers `bunfig.toml` and the tsconfig
 * path-mapping by walking up from cwd, so running from anywhere else would silently
 * change resolution).
 *
 * **Exit aggregation.** Returns [EXIT_OK] (0) when every trailmap's tests pass or there are
 * no `*.test.ts` files; [EXIT_TEST_FAILURE] (1) when any trailmap's tests fail or a
 * subprocess can't be spawned; [EXIT_USAGE] (2) when a trailmap has tests but no tsconfig
 * (operator-fixable precondition) or when `bun` isn't on PATH at all.
 */
internal object TrailmapUnitTestRunner {

  /** Picocli OK. */
  const val EXIT_OK: Int = 0

  /** At least one trailmap's tests failed (or a subprocess spawn failed). */
  const val EXIT_TEST_FAILURE: Int = 1

  /** Operator-fixable precondition (missing tsconfig, bun not on PATH). */
  const val EXIT_USAGE: Int = 2

  internal const val DEFAULT_TEST_TIMEOUT_MS: Long = 5L * 60L * 1000L
  internal const val MIN_TEST_TIMEOUT_MS: Long = 60L * 1000L
  internal const val TIMEOUT_MS_ENV_VAR: String = "TRAILBLAZE_TEST_TIMEOUT_MS"

  /**
   * Run unit tests across the given trailmap directories. Returns the aggregate exit code per
   * the contract documented on this object. Callers pass the same trailmap list they used for
   * typecheck so the two phases stay consistent.
   *
   * Error messages are prefixed with `trailblaze check:` — this runner is the third phase
   * of `trailblaze check` and is not exposed as its own subcommand. If a future
   * `trailblaze check tests` (or similar) subcommand ever lands, re-add a `commandLabel`
   * parameter then; threading one through speculatively today violates KISS.
   */
  fun run(trailmaps: List<Path>): Int {
    if (trailmaps.isEmpty()) return EXIT_OK

    if (!CliPathUtils.isCommandOnPath("bun")) {
      Console.error(
        "trailblaze check: bun is not on PATH. Install bun (https://bun.sh) — " +
          "`bun test` is the runner for scripted-tool unit tests. Node's built-in test " +
          "runner uses incompatible syntax and is not a drop-in replacement.",
      )
      return EXIT_USAGE
    }

    var sawFailure = false
    var sawMissingTsconfig = false
    val failedTrailmaps = mutableListOf<String>()
    var ranAnyTests = false
    for (trailmap in trailmaps) {
      val toolsDir = trailmap.resolve("tools")
      if (!Files.isDirectory(toolsDir)) continue
      val testFiles = findTestFiles(toolsDir)
      if (testFiles.isEmpty()) continue
      // Pre-flight: a trailmap with `*.test.ts` but no `tools/tsconfig.json` will hit a
      // confusing `Cannot find module @trailblaze/scripting/testing` from bun (which
      // discovers the per-trailmap tsconfig and the `paths` mapping for the `@trailblaze/*`
      // module shape). The tsconfig is emitted by `trailblaze check`'s materialize phase
      // — an absent tsconfig at this point means the materialize step is itself broken,
      // so surface a directed error rather than letting bun emit a cryptic one.
      val tsconfig = toolsDir.resolve("tsconfig.json")
      if (!Files.isRegularFile(tsconfig)) {
        Console.error(
          "trailblaze check: trailmap '${trailmap.fileName}' has *.test.ts files but no " +
            "tools/tsconfig.json — bun would fail with a module-resolution error " +
            "against `@trailblaze/scripting/testing`. The materialize phase should " +
            "have emitted this file; re-run `trailblaze check` from a clean state.",
        )
        sawMissingTsconfig = true
        failedTrailmaps += trailmap.fileName.toString()
        continue
      }
      ranAnyTests = true
      Console.log(
        "── test: ${trailmap.fileName} (${testFiles.size} file${if (testFiles.size == 1) "" else "s"}) ────",
      )
      val exit = runBunTest(toolsDir = toolsDir)
      if (exit != 0) {
        sawFailure = true
        failedTrailmaps += trailmap.fileName.toString()
      }
    }
    if (!ranAnyTests && !sawMissingTsconfig) {
      // Silent success — `trailblaze check` shouldn't bark at a workspace that has no
      // unit tests yet; the materialize + typecheck phases already ran and printed their
      // own status. A "no tests found" line here would just be noise.
      return EXIT_OK
    }
    if (failedTrailmaps.isNotEmpty() && trailmaps.size > 1) {
      Console.error(
        "trailblaze check: failed trailmaps (unit tests): ${failedTrailmaps.joinToString(", ")}",
      )
    }
    return when {
      // Missing tsconfig is a materialize-step regression, not a test failure. Distinct
      // exit code so a CI consumer can demultiplex "your tests are broken" from "your
      // workspace setup is broken."
      sawMissingTsconfig -> EXIT_USAGE
      sawFailure -> EXIT_TEST_FAILURE
      else -> EXIT_OK
    }
  }

  /**
   * Walk a trailmap's `tools/` directory for `*.test.ts` files. Matches what `bun test`
   * itself does for discovery (it would find them on its own when pointed at the dir),
   * but doing it up-front lets a trailmap with zero tests skip the subprocess spawn entirely
   * and stay quiet in the parent run's output.
   */
  internal fun findTestFiles(toolsDir: Path): List<Path> {
    if (!Files.isDirectory(toolsDir)) return emptyList()
    return Files.walk(toolsDir).use { stream ->
      stream
        .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".test.ts") }
        .sorted()
        .toList()
    }
  }

  /**
   * Spawn `bun test <toolsDir>` and pipe output verbatim. `--bail=1` would stop at first
   * failure; deliberately not passed so a CI consumer sees every failing test in a
   * single run instead of having to iterate.
   *
   * **Subprocess cwd pinned to `toolsDir`.** Bun discovers `bunfig.toml` and the
   * tsconfig path-mapping by walking up from its cwd. Running with whatever cwd the
   * operator happens to be in (workspace root vs. trailmap root) would make tests pass/fail
   * depending on invocation location. Pinning to `toolsDir` gives every trailmap's suite
   * the same deterministic cwd that a direct `(cd <trailmap>/tools && bun test)` would.
   */
  private fun runBunTest(toolsDir: Path): Int {
    val toolsDirAbs = toolsDir.toAbsolutePath()
    val argv = listOf("bun", "test", toolsDirAbs.toString())
    val timeoutMs = resolveTestTimeoutMs()
    return try {
      val proc = ProcessBuilder(argv)
        .directory(toolsDirAbs.toFile())
        .redirectErrorStream(true)
        .inheritIO()
        .start()
      if (proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
        proc.exitValue()
      } else {
        Console.error(
          "trailblaze check: bun test did not finish within ${timeoutMs}ms — " +
            "killing. Bump $TIMEOUT_MS_ENV_VAR if this is a known-slow test suite.",
        )
        proc.destroyForcibly()
        proc.waitFor(10, TimeUnit.SECONDS)
        EXIT_TEST_FAILURE
      }
    } catch (e: Exception) {
      Console.error(
        "trailblaze check: failed to spawn bun: " +
          "${e.message ?: e.javaClass.simpleName}",
      )
      EXIT_TEST_FAILURE
    }
  }

  /**
   * Resolve the per-trailmap bun-test subprocess timeout in milliseconds. Env-var override
   * with a 1-minute lower clamp and a 5-minute default — enough headroom for any
   * realistic scripted-tool test suite while still catching infinite loops.
   */
  internal fun resolveTestTimeoutMs(): Long {
    val raw = System.getenv(TIMEOUT_MS_ENV_VAR) ?: return DEFAULT_TEST_TIMEOUT_MS
    val parsed = raw.toLongOrNull()
    if (parsed == null) {
      Console.error(
        "trailblaze check: $TIMEOUT_MS_ENV_VAR='$raw' is not a valid number of " +
          "milliseconds — using default ${DEFAULT_TEST_TIMEOUT_MS}ms.",
      )
      return DEFAULT_TEST_TIMEOUT_MS
    }
    return parsed.coerceAtLeast(MIN_TEST_TIMEOUT_MS)
  }
}
