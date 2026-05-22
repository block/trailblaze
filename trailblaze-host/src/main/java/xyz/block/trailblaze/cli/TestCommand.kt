package xyz.block.trailblaze.cli

import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import xyz.block.trailblaze.llm.config.TrailblazeConfigPaths
import xyz.block.trailblaze.util.Console
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.TimeUnit

/**
 * Run scripted-tool unit tests authored alongside `.ts` tools.
 *
 * Discovery convention: any `*.test.ts` file under a pack's `tools/` directory tree is a
 * test file. The runner is `bun test` — bun is the canonical TS runtime for the SDK
 * (same runtime esbuild bundles scripted tools through), and `bun test` is built into
 * the binary with no third-party dependency. Test authors import the mock helpers from
 * `@trailblaze/scripting/testing` (see `sdks/typescript/src/testing.ts`).
 *
 * **CLI shape.** Mirrors [TypecheckCommand]'s walk-up + `--all` discovery so a developer
 * can run from inside a pack tree without naming it explicitly:
 *  - `trailblaze test` (no args, from inside a pack tree): tests the enclosing pack.
 *  - `trailblaze test <pack-id>`: tests a named pack in the discovered workspace.
 *  - `trailblaze test --all`: tests every workspace pack with `*.test.ts` files.
 *
 * **Why a separate subcommand, not folded into `typecheck`.** Type-checking and unit
 * testing are independent: a tool body can be type-clean but logically broken, and an
 * author iterating on test assertions doesn't want to pay tsc's setup cost on every
 * `bun test` run. Keeping them split also matches how `bun test` itself works (no
 * implicit tsc step). Authors who want both run them in sequence:
 * `trailblaze typecheck && trailblaze test`.
 *
 * **Exit codes** follow the same conventions [TypecheckCommand] uses so shell consumers
 * can demultiplex usage vs. operational failures vs. test failures:
 *  - `0` — every pack's tests passed (or no `*.test.ts` files found).
 *  - `1` — at least one pack's tests failed.
 *  - `2` — usage error (missing workspace, unknown pack id, no bun on PATH).
 */
@Command(
  name = "test",
  mixinStandardHelpOptions = true,
  description = ["Run scripted-tool unit tests (*.test.ts) via bun test"],
)
class TestCommand : Callable<Int> {

  @Parameters(
    arity = "0..1",
    paramLabel = "<pack-id>",
    description = [
      "Name of the pack whose tests to run (directory name under " +
        "<workspace>/trails/config/packs/). Omit when running from inside a pack tree — " +
        "the command walks up to the nearest pack root and uses that. Mutually exclusive " +
        "with --all.",
    ],
  )
  var packId: String? = null

  @Option(
    names = ["--all"],
    description = [
      "Run tests for every pack with *.test.ts files in the discovered workspace. " +
        "Mutually exclusive with the positional <pack-id>.",
    ],
  )
  var testAll: Boolean = false

  override fun call(): Int {
    if (testAll && packId != null) {
      Console.error("trailblaze test: --all and <pack-id> are mutually exclusive.")
      return EXIT_USAGE
    }
    packId?.let { id ->
      // Reuse the validator the typecheck command already exposes to avoid drift in
      // the rejection rules for pack-id-as-path-segment.
      TypecheckCommand.validatePackId(id)?.let { reason ->
        Console.error("trailblaze test: invalid pack id '$id' — $reason.")
        return EXIT_USAGE
      }
    }

    val callerCwd = CliCallerContext.callerCwd()
    val workspaceRoot = CliPathUtils.findWorkspaceRoot(callerCwd)
    if (workspaceRoot == null) {
      val startAbs = callerCwd.toAbsolutePath().normalize()
      Console.error(
        "trailblaze test: not inside a Trailblaze workspace. Walked up from " +
          "$startAbs to the filesystem root and found no `trails/config/packs/` marker. " +
          "Run from inside a workspace (so the walk-up can find the root).",
      )
      return EXIT_USAGE
    }

    // `bun` is required — `bun test` is the runner, and unlike `tsc` (which runs on
    // either bun or node) there's no equivalent built-in node test runner that
    // matches `bun test`'s API. Fail with an actionable message rather than try to
    // fall back to `node --test` (incompatible test syntax, different defaults).
    if (!CliPathUtils.isCommandOnPath("bun")) {
      Console.error(
        "trailblaze test: bun is not on PATH. Install bun (https://bun.sh) — `bun test` " +
          "is the runner for scripted-tool unit tests. Node's built-in test runner uses " +
          "incompatible syntax and is not a drop-in replacement.",
      )
      return EXIT_USAGE
    }

    val packs = resolvePacksToTest(workspaceRoot = workspaceRoot.toFile(), callerCwd = callerCwd)
      ?: return EXIT_USAGE
    if (packs.isEmpty()) {
      Console.log("trailblaze test: no packs to test.")
      return EXIT_OK
    }

    var sawFailure = false
    var sawMissingTsconfig = false
    val failedPacks = mutableListOf<String>()
    var ranAnyTests = false
    for (pack in packs) {
      val toolsDir = pack.resolve("tools")
      if (!Files.isDirectory(toolsDir)) continue
      val testFiles = findTestFiles(toolsDir)
      if (testFiles.isEmpty()) continue
      // Pre-flight: a pack with `*.test.ts` but no `tools/tsconfig.json` will hit a
      // confusing `Cannot find module @trailblaze/scripting/testing` from bun (which
      // discovers the per-pack tsconfig and the `paths` mapping for the `@trailblaze/*`
      // module shape). The tsconfig is emitted by `trailblaze compile`, so route the
      // operator there with a directed error — same shape `TypecheckCommand` uses for
      // the same precondition. We deliberately don't auto-run `compile` here: it has
      // side effects on every pack (gitignore, framework-managed file checks) and
      // hiding them inside `test` would surprise authors mid-iteration.
      val tsconfig = toolsDir.resolve("tsconfig.json")
      if (!Files.isRegularFile(tsconfig)) {
        Console.error(
          "trailblaze test: pack '${pack.fileName}' has *.test.ts files but no " +
            "tools/tsconfig.json — bun would fail with a module-resolution error " +
            "against `@trailblaze/scripting/testing`. Run `trailblaze compile` first " +
            "to emit framework-managed tsconfigs.",
        )
        sawMissingTsconfig = true
        failedPacks += pack.fileName.toString()
        continue
      }
      ranAnyTests = true
      Console.log("── test: ${pack.fileName} (${testFiles.size} file${if (testFiles.size == 1) "" else "s"}) ────")
      val exit = runBunTest(toolsDir = toolsDir)
      if (exit != 0) {
        sawFailure = true
        failedPacks += pack.fileName.toString()
      }
    }
    if (!ranAnyTests && !sawMissingTsconfig) {
      Console.log("trailblaze test: no *.test.ts files found.")
      return EXIT_OK
    }
    if (failedPacks.isNotEmpty() && packs.size > 1) {
      Console.error("trailblaze test: failed packs: ${failedPacks.joinToString(", ")}")
    }
    return when {
      // Missing tsconfig is an operator-fixable usage error (run `trailblaze compile`),
      // not a test failure. Distinct from EXIT_TEST_FAILURE so a CI consumer can
      // demultiplex "your tests are broken" from "your workspace isn't compiled."
      sawMissingTsconfig -> EXIT_USAGE
      sawFailure -> EXIT_TEST_FAILURE
      else -> EXIT_OK
    }
  }

  /**
   * Walk the pack's `tools/` directory for `*.test.ts` files. Matches what `bun test`
   * itself does for discovery (it would find them on its own when pointed at the dir),
   * but we do it up-front so a pack with zero tests doesn't waste a subprocess spawn and
   * doesn't emit "no tests found" noise into a multi-pack run.
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
   * Resolve the list of pack directories whose tests this run should execute. Same shape
   * as [TypecheckCommand.resolvePacksToCheck] — keep them in sync if you extend either.
   */
  internal fun resolvePacksToTest(workspaceRoot: File, callerCwd: Path): List<Path>? {
    val packsDir = File(workspaceRoot, TrailblazeConfigPaths.WORKSPACE_PACKS_DIR)
    if (!packsDir.isDirectory) {
      Console.error("trailblaze test: no packs/ directory found at ${packsDir.absolutePath}.")
      return null
    }
    if (testAll) {
      val packDirs = packsDir.listFiles { f ->
        f.isDirectory && File(f, TrailblazeConfigPaths.PACK_MANIFEST_FILENAME).isFile
      }
      if (packDirs == null) {
        Console.error(
          "trailblaze test: failed to list packs at ${packsDir.absolutePath} " +
            "(I/O or permission error).",
        )
        return null
      }
      return packDirs.sortedBy { it.name }.map { it.toPath() }
    }
    val explicit = packId
    if (explicit != null) {
      val target = File(packsDir, explicit)
      if (!File(target, TrailblazeConfigPaths.PACK_MANIFEST_FILENAME).isFile) {
        Console.error(
          "trailblaze test: unknown pack '$explicit' — no pack.yaml at ${target.absolutePath}.",
        )
        return null
      }
      return listOf(target.toPath())
    }
    // Walk-up discovery, identical to TypecheckCommand's so the two subcommands feel the
    // same from inside a pack tree.
    val typecheckCommand = TypecheckCommand()
    val pack = typecheckCommand.findEnclosingPack(callerCwd, packsDirAbs = packsDir.canonicalFile.toPath())
    if (pack == null) {
      Console.error(
        "trailblaze test: no pack to test. Pass a pack id (e.g. `trailblaze test wikipedia`) " +
          "or --all, or run from inside a pack's directory tree.",
      )
      return null
    }
    return listOf(pack)
  }

  /**
   * Spawn `bun test <toolsDir>` and pipe output to the caller's terminal verbatim. Bun's
   * own discovery walks the directory for `*.test.ts` matches with no need for an
   * explicit glob argument. `--bail=1` would stop at first failure — we deliberately
   * don't pass it so a CI consumer sees every failing test in a single run instead of
   * having to iterate.
   *
   * **Subprocess cwd pinned to `toolsDir`.** Bun discovers `bunfig.toml` and the
   * tsconfig path-mapping by walking up from its cwd, so running with whatever cwd the
   * operator happens to be in (e.g. the workspace root vs. the pack root) would make
   * tests pass/fail depending on invocation location. Tests that read fixtures via
   * relative paths or rely on `process.cwd()` would also see inconsistent behavior.
   * Pinning to `toolsDir` gives every pack's test suite the same deterministic cwd that
   * a direct `(cd <pack>/tools && bun test)` would give it.
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
          "trailblaze test: bun test did not finish within ${timeoutMs}ms — killing. " +
            "Bump $TIMEOUT_MS_ENV_VAR if this is a known-slow test suite.",
        )
        proc.destroyForcibly()
        proc.waitFor(10, TimeUnit.SECONDS)
        EXIT_TEST_FAILURE
      }
    } catch (e: Exception) {
      Console.error(
        "trailblaze test: failed to spawn bun: ${e.message ?: e.javaClass.simpleName}",
      )
      EXIT_TEST_FAILURE
    }
  }

  /**
   * Resolve the per-pack bun-test subprocess timeout in milliseconds. Same shape as
   * [TypecheckCommand.resolveTscTimeoutMs] — env-var override with a 1-minute lower
   * clamp and a 5-minute default, which is enough headroom for any realistic
   * scripted-tool test suite while still catching infinite loops.
   */
  internal fun resolveTestTimeoutMs(): Long {
    val raw = System.getenv(TIMEOUT_MS_ENV_VAR) ?: return DEFAULT_TEST_TIMEOUT_MS
    val parsed = raw.toLongOrNull()
    if (parsed == null) {
      Console.error(
        "trailblaze test: $TIMEOUT_MS_ENV_VAR='$raw' is not a valid number of " +
          "milliseconds — using default ${DEFAULT_TEST_TIMEOUT_MS}ms.",
      )
      return DEFAULT_TEST_TIMEOUT_MS
    }
    return parsed.coerceAtLeast(MIN_TEST_TIMEOUT_MS)
  }

  internal companion object {
    /** Picocli OK. */
    const val EXIT_OK = 0

    /** At least one pack's tests failed. */
    const val EXIT_TEST_FAILURE = 1

    /** Usage errors (no workspace, unknown pack, no bun). */
    const val EXIT_USAGE = 2

    internal const val DEFAULT_TEST_TIMEOUT_MS: Long = 5L * 60L * 1000L
    internal const val MIN_TEST_TIMEOUT_MS: Long = 60L * 1000L
    internal const val TIMEOUT_MS_ENV_VAR: String = "TRAILBLAZE_TEST_TIMEOUT_MS"
  }
}
